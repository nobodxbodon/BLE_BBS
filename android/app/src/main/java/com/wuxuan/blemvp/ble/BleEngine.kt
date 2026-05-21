package com.wuxuan.blemvp.ble

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.util.Log
import com.wuxuan.blemvp.model.帖文
import com.wuxuan.blemvp.model.帖文载荷
import com.wuxuan.blemvp.model.线载编解码
import com.wuxuan.blemvp.model.线载包
import com.wuxuan.blemvp.storage.AppDatabase
import com.wuxuan.blemvp.storage.PostEntity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Collections
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.io.ByteArrayOutputStream

class 蓝牙引擎(context: Context) {

    private val 应用上下文 = context.applicationContext
    private val 蓝牙管理器 = context.getSystemService(BluetoothManager::class.java)
    private val 蓝牙适配器实例 = 蓝牙管理器?.adapter
    private val 存储作用域 = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val 帖文访问对象实例 = AppDatabase.getInstance(应用上下文).postDao()
    private val 已知帖文ID: MutableSet<String> = Collections.newSetFromMap(ConcurrentHashMap())

    private val 本机设备ID: String = run {
        val prefs = 应用上下文.getSharedPreferences("blemvp_prefs", Context.MODE_PRIVATE)
        prefs.getString("device_id", null) ?: UUID.randomUUID().toString().also {
            prefs.edit().putString("device_id", it).apply()
        }
    }

    private var 生命周期监听器: 蓝牙生命周期监听器? = null
    private var 蓝牙已启动 = false
    private var 扫描重启任务: kotlinx.coroutines.Job? = null
    private val 入站字节缓冲 = mutableMapOf<String, ByteArrayOutputStream>()

    private val 蓝牙状态接收器 = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action != BluetoothAdapter.ACTION_STATE_CHANGED) return
            val state = intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.ERROR)
            when (state) {
                BluetoothAdapter.STATE_OFF -> {
                    // BT is going off — null the GATT-server reference now so 启动() works
                    // cleanly when BT comes back on. Connections are dead; clean up maps.
                    中心连接器.断开全部()
                    Gatt服务器实例.停止()
                    发出状态(蓝牙生命周期状态.错误, "Bluetooth turned off on this device")
                }
                BluetoothAdapter.STATE_ON -> {
                    if (蓝牙已启动) {
                        // BT was re-enabled while BLE was running — clean up stale state and restart
                        中心连接器.断开全部()
                        入站字节缓冲.clear()
                        Gatt服务器实例.启动()
                        扫描器?.开始扫描()
                        广播器?.开始广播()
                        发出状态(蓝牙生命周期状态.运行中, "Bluetooth re-enabled, BLE restarted")
                    }
                }
            }
        }
    }

    private val 中心连接器: 蓝牙中心连接器 = 蓝牙中心连接器(context,
        连接状态变化 = { connected, address ->
        if (connected) {
            发出状态(蓝牙生命周期状态.已连接, "Central connected: $address")
        } else {
            // Forget immediately so the peer is rediscoverable as soon as it comes back
            // online (e.g. after a Bluetooth restart). Reconnect hammering is prevented
            // by the delay in the 发现设备 → connect path below.
            扫描器?.忘记地址(address)
            发出状态(蓝牙生命周期状态.运行中, "Central disconnected: $address")
        }
        },
        写入就绪 = { address ->
            发出状态(蓝牙生命周期状态.运行中, "Central write ready: $address")
            同步历史到对端(address)
        }
    )

    private val Gatt服务器实例 = 蓝牙Gatt服务器(
        context = context,
        写入到达 = { fromAddress, byteCount ->
            发出状态(蓝牙生命周期状态.运行中, "Write arrived: $byteCount bytes from $fromAddress")
        },
        收到入站写入 = { data, fromAddress ->
            Log.d(TAG, "rx ${data.size}b from $fromAddress")
            val buffer = 入站字节缓冲.getOrPut(fromAddress) { ByteArrayOutputStream() }
            buffer.write(data)

            // Accumulate raw bytes and scan for newline (0x0A) byte.
            // Only 解码 to UTF-8 after a complete frame is found so that
            // multi-byte characters (e.g. Chinese) split across chunk boundaries
            // are reassembled before decoding.
            val bufBytes = buffer.toByteArray()
            var consumed = 0
            while (consumed < bufBytes.size) {
                var nlIdx = -1
                for (i in consumed until bufBytes.size) {
                    if (bufBytes[i] == 0x0A.toByte()) { nlIdx = i; break }
                }
                if (nlIdx < 0) break

                val frame = String(bufBytes, consumed, nlIdx - consumed, Charsets.UTF_8).trim()
                consumed = nlIdx + 1
                if (frame.isBlank()) continue

                Log.d(TAG, "parsing frame (${nlIdx - (consumed - 1)} bytes)")
                val packet = 线载编解码.解码(frame)
                if (packet is 线载包.帖文包) {
                    Log.d(TAG, "decoded message: '${packet.payload.内容}'")
                    if (保存载荷(packet.payload, "recv:$fromAddress")) {
                        发出状态(蓝牙生命周期状态.运行中, "RECV from $fromAddress: ${packet.payload.内容}")
                    }
                } else {
                    Log.d(TAG, "解码 returned null or non-message packet")
                }
            }

            // Keep unprocessed remainder in the buffer.
            buffer.reset()
            if (consumed < bufBytes.size) {
                buffer.write(bufBytes, consumed, bufBytes.size - consumed)
            }

            // Compat: try single-packet 解码 on remainder (no trailing newline).
            val remaining = buffer.toByteArray()
            if (remaining.isNotEmpty()) {
                val snapshot = String(remaining, Charsets.UTF_8).trim()
                val packet = 线载编解码.解码(snapshot)
                if (packet is 线载包.帖文包) {
                    Log.d(TAG, "compat 解码 succeeded: '${packet.payload.内容}'")
                    if (保存载荷(packet.payload, "recv-compat:$fromAddress")) {
                        发出状态(蓝牙生命周期状态.运行中, "RECV from $fromAddress: ${packet.payload.内容}")
                    }
                    buffer.reset()
                }
            }

            if (buffer.size() > MAX_BUFFER_CHARS) {
                buffer.reset()
                发出状态(蓝牙生命周期状态.错误, "inbound buffer overflow from $fromAddress")
            }
        },
        连接状态变化 = { connected, address ->
            if (connected) {
                发出状态(蓝牙生命周期状态.已连接, "Peripheral connected: $address")
            } else {
                入站字节缓冲.remove(address)
                发出状态(蓝牙生命周期状态.运行中, "Peripheral disconnected: $address")
            }
        }
    )
    fun 发送帖文给所有对端(text: String): Pair<Int, ByteArray> {
        val snapshotBefore = 中心连接器.获取对等快照()
        发出状态(
            蓝牙生命周期状态.运行中,
            "send precheck: active=${snapshotBefore.活跃Gatt数}, writable=${snapshotBefore.可写数}, pending=${snapshotBefore.待连接数}"
        )

        val msg = 帖文(内容 = text, 发帖人名称 = 本机设备ID)
        val packet = 线载包.帖文包(帖文载荷.来自帖文(msg))
        保存载荷(packet.payload, "send-local")
        val framed = 线载编解码.编码(packet) + "\n"
        val bytes = framed.toByteArray(Charsets.UTF_8)
        Log.d(TAG, "sending '${text}' -> encoded: '$framed' -> ${bytes.size} bytes")
        val count = 中心连接器.发给所有已连接Gatt(bytes)
        Log.d(TAG, "发给所有已连接Gatt returned count=$count")
        val snapshotAfter = 中心连接器.获取对等快照()
        发出状态(
            蓝牙生命周期状态.运行中,
            "sent count=$count (active=${snapshotAfter.活跃Gatt数}, writable=${snapshotAfter.可写数}, pending=${snapshotAfter.待连接数})"
        )
        return Pair(count, bytes)
    }

    /** Re-send pre-encoded bytes without creating a new message ID. Use for retries only. */
    fun 重试发送给所有对端(bytes: ByteArray): Int {
        return 中心连接器.发给所有已连接Gatt(bytes)
    }

    fun 获取对等快照(): 蓝牙中心连接器.对等快照 {
        return 中心连接器.获取对等快照()
    }

    /** Push our full local history to every currently-writable peer. Call this manually
     *  if a peer came back into range but the automatic on-connect sync didn't deliver. */
    fun 强制同步历史() {
        val addresses = 中心连接器.获取可写对端地址()
        Log.d(TAG, "强制同步历史: pushing history to ${addresses.size} peer(s)")
        addresses.forEach { 同步历史到对端(it) }
    }

    fun 获取本机设备ID(): String = 本机设备ID

    fun 获取本机设备地址(): String {
        return try {
            蓝牙适配器实例?.address ?: "Unavailable"
        } catch (_: SecurityException) {
            "Unavailable"
        }
    }

    private val 扫描器: 蓝牙扫描器? = 蓝牙适配器实例?.let {
        蓝牙扫描器(
            蓝牙适配器 = it,
            发现设备 = { device ->
                发出状态(蓝牙生命周期状态.连接中, "Discovered ${device.address}, connecting")
                // Delay on IO then hop to Main for the GATT connect call. All 蓝牙中心连接器
                // state (活跃Gatt连接, 待连接地址) is accessed from the main thread only.
                存储作用域.launch {
                    delay(RECONNECT_DELAY_MS)
                    withContext(Dispatchers.Main) {
                        中心连接器.连接(device)
                    }
                }
            },
            扫描已开始 = { mode ->
                发出状态(蓝牙生命周期状态.运行中, "scan started ($mode)")
            },
            扫描出错 = { reason ->
                发出状态(蓝牙生命周期状态.错误, reason)
                // Auto-retry scan after a short pause. This handles transient hardware
                if (蓝牙已启动) {
                    存储作用域.launch {
                        delay(SCAN_ERROR_RETRY_MS)
                        if (蓝牙已启动) 扫描器?.开始扫描()
                    }
                }
            }
        )
    }
    private val 广播器 = 蓝牙适配器实例?.let {
        蓝牙广播器(
            蓝牙适配器 = it,
            广播已开始 = {
                发出状态(蓝牙生命周期状态.运行中, "advertising started")
            },
            广播出错 = { reason ->
                发出状态(蓝牙生命周期状态.错误, reason)
            }
        )
    }

    fun 启动() {
        if (蓝牙已启动) return  // idempotent — ignore if already running

        if (蓝牙适配器实例 == null || !蓝牙适配器实例.isEnabled) {
            Log.e(TAG, "Bluetooth 蓝牙适配器实例 unavailable or disabled")
            发出状态(蓝牙生命周期状态.错误, "Bluetooth unavailable or disabled")
            return
        }

        应用上下文.registerReceiver(蓝牙状态接收器, IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED))
        Gatt服务器实例.启动()
        扫描器?.开始扫描()
        广播器?.开始广播()
        // Periodic restart
        扫描重启任务 = 存储作用域.launch {
            while (true) {
                delay(SCAN_RESTART_INTERVAL_MS)
                if (!蓝牙已启动) break
                Log.d(TAG, "periodic scan+advertise restart")
                扫描器?.开始扫描()
                广播器?.开始广播()
            }
        }
        存储作用域.launch {
            try {
                val all = 帖文访问对象实例.getAllLatestFirst()
                已知帖文ID.clear()
                已知帖文ID.addAll(all.map { it.id })
                发出状态(蓝牙生命周期状态.运行中, "store ready: cached 帖文列表=${all.size}")
            } catch (t: Throwable) {
                Log.e(TAG, "Failed to read cached 帖文列表", t)
                发出状态(蓝牙生命周期状态.错误, "store read failed: ${t.message ?: "unknown"}")
            }
        }
        发出状态(蓝牙生命周期状态.运行中, "ble up")
        蓝牙已启动 = true
    }

    fun 停止() {
        蓝牙已启动 = false
        扫描重启任务?.cancel()
        扫描重启任务 = null
        try { 应用上下文.unregisterReceiver(蓝牙状态接收器) } catch (_: IllegalArgumentException) { }
        扫描器?.停止扫描()
        广播器?.停止广播()
        中心连接器.断开全部()
        Gatt服务器实例.停止()
        发出状态(蓝牙生命周期状态.已停止, "")
    }

    /**
     * Restart scan and advertising immediately.
     */
    fun 重启扫描广播() {
        if (!蓝牙已启动) return
        Log.d(TAG, "重启扫描广播: restarting scan + advertising")
        扫描器?.开始扫描()
        广播器?.开始广播()
    }

    fun 设置生命周期监听器(listener: 蓝牙生命周期监听器?) {
        生命周期监听器 = listener
    }

    fun 关闭() {
        存储作用域.cancel()
    }

    private fun 发出状态(state: 蓝牙生命周期状态, detail: String) {
        Log.d(TAG, "state=$state detail=$detail")
        生命周期监听器?.onStateChanged(state, detail)
    }

    private fun 保存载荷(payload: 帖文载荷, source: String): Boolean {
        if (!已知帖文ID.add(payload.id)) {
            Log.d(TAG, "dedup: skip known id=${payload.id} source=$source")
            return false
        }
        存储作用域.launch {
            try {
                帖文访问对象实例.upsert(
                    PostEntity(
                        id = payload.id,
                        text = payload.内容,
                        sender = payload.发帖人,
                        timestampIso8601 = payload.时间戳
                    )
                )
                Log.d(TAG, "Persisted message ${payload.id} source=$source")
            } catch (t: Throwable) {
                Log.e(TAG, "Persist failed source=$source", t)
                发出状态(蓝牙生命周期状态.错误, "store write failed: ${t.message ?: "unknown"}")
            }
        }
        return true
    }

    private fun 同步历史到对端(address: String) {
        存储作用域.launch {
            val 帖文列表 = try {
                帖文访问对象实例.getAllLatestFirst().asReversed() // oldest first → chronological delivery
            } catch (t: Throwable) {
                Log.e(TAG, "同步历史到对端: failed to load 帖文列表", t)
                return@launch
            }
            Log.d(TAG, "同步历史到对端 $address: ${帖文列表.size} 帖文列表")
            withContext(Dispatchers.Main) {
                for (帖文项 in 帖文列表) {
                    val payload = 帖文载荷(
                        id = 帖文项.id,
                        内容 = 帖文项.text,
                        发帖人 = 帖文项.sender,
                        时间戳 = 帖文项.timestampIso8601
                    )
                    val packet = 线载包.帖文包(payload)
                    val framed = 线载编解码.编码(packet) + "\n"
                    val bytes = framed.toByteArray(Charsets.UTF_8)
                    中心连接器.发给对端(address, bytes)
                }
            }
        }
    }

    companion object {
        private const val TAG = "蓝牙引擎"
        private const val MAX_BUFFER_CHARS = 8192
        private const val RECONNECT_DELAY_MS = 1_500L
        private const val SCAN_ERROR_RETRY_MS = 5_000L          // retry after scan failure
        private const val SCAN_RESTART_INTERVAL_MS = 5 * 60_000L // every 5 min
    }
}
