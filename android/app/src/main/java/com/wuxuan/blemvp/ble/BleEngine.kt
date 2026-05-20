package com.wuxuan.blemvp.ble

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.util.Log
import com.wuxuan.blemvp.model.帖子
import com.wuxuan.blemvp.model.帖子载荷
import com.wuxuan.blemvp.model.传输编解码器
import com.wuxuan.blemvp.model.传输包
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

    private val appContext = context.applicationContext
    private val bluetoothManager = context.getSystemService(BluetoothManager::class.java)
    private val adapter = bluetoothManager?.adapter
    private val storageScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val postDao = AppDatabase.getInstance(appContext).postDao()
    private val 已知帖子编号: MutableSet<String> = Collections.newSetFromMap(ConcurrentHashMap())

    private val 本机设备编号: String = run {
        val prefs = appContext.getSharedPreferences("blemvp_prefs", Context.MODE_PRIVATE)
        prefs.getString("device_id", null) ?: UUID.randomUUID().toString().also {
            prefs.edit().putString("device_id", it).apply()
        }
    }

    private var 生命周期监听器: 蓝牙生命周期监听器? = null
    private var 蓝牙已启动 = false
    private var 扫描重启任务: kotlinx.coroutines.Job? = null
    private val 入站字节缓冲区 = mutableMapOf<String, ByteArrayOutputStream>()

    private val 蓝牙状态接收器 = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action != BluetoothAdapter.ACTION_STATE_CHANGED) return
            val state = intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.ERROR)
            when (state) {
                BluetoothAdapter.STATE_OFF -> {
                    // BT is going off — null the GATT-server reference now so 启动() works
                    // cleanly when BT comes back on. Connections are dead; clean up maps.
                    中心连接器.断开全部()
                    gatt服务端.停止()
                    emitState(蓝牙生命周期状态.错误, "Bluetooth turned off on this device")
                }
                BluetoothAdapter.STATE_ON -> {
                    if (蓝牙已启动) {
                        // BT was re-enabled while BLE was running — clean up stale state and restart
                        中心连接器.断开全部()
                        入站字节缓冲区.clear()
                        gatt服务端.启动()
                        扫描器?.开始扫描()
                        广播器?.开始广播()
                        emitState(蓝牙生命周期状态.运行中, "Bluetooth re-enabled, BLE restarted")
                    }
                }
            }
        }
    }

    private val 中心连接器: 蓝牙中心连接器 = 蓝牙中心连接器(context,
        on连接状态变化 = { connected, address ->
        if (connected) {
            emitState(蓝牙生命周期状态.已连接, "Central connected: $address")
        } else {
            // Forget immediately so the peer is rediscoverable as soon as it comes back
            // online (e.g. after a Bluetooth restart). Reconnect hammering is prevented
            // by the delay in the onDiscovered → 连接 path below.
            扫描器?.忘记地址(address)
            emitState(蓝牙生命周期状态.运行中, "Central disconnected: $address")
        }
        },
        onWriteReady = { address ->
            emitState(蓝牙生命周期状态.运行中, "Central write ready: $address")
            syncHistoryToPeer(address)
        }
    )

    private val gatt服务端 = 蓝牙Gatt服务端(
        context = context,
        onWriteArrived = { fromAddress, byteCount ->
            emitState(蓝牙生命周期状态.运行中, "Write arrived: $byteCount bytes from $fromAddress")
        },
        onInboundWrite = { data, fromAddress ->
            Log.d(TAG, "rx ${data.size}b from $fromAddress")
            val buffer = 入站字节缓冲区.getOrPut(fromAddress) { ByteArrayOutputStream() }
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
                val 收到包 = 传输编解码器.解码(frame)
                if (收到包 is 传输包.帖子包) {
                    Log.d(TAG, "decoded message: '${收到包.载荷.正文}'")
                    if (persistPayload(收到包.载荷, "recv:$fromAddress")) {
                        emitState(蓝牙生命周期状态.运行中, "RECV from $fromAddress: ${收到包.载荷.正文}")
                    }
                } else {
                    Log.d(TAG, "解码 returned null or non-message 传输包")
                }
            }

            // Keep unprocessed remainder in the buffer.
            buffer.reset()
            if (consumed < bufBytes.size) {
                buffer.write(bufBytes, consumed, bufBytes.size - consumed)
            }

            // Compat: try single-传输包 解码 on remainder (no trailing newline).
            val remaining = buffer.toByteArray()
            if (remaining.isNotEmpty()) {
                val snapshot = String(remaining, Charsets.UTF_8).trim()
                val 收到包 = 传输编解码器.解码(snapshot)
                if (收到包 is 传输包.帖子包) {
                    Log.d(TAG, "compat 解码 succeeded: '${收到包.载荷.正文}'")
                    if (persistPayload(收到包.载荷, "recv-compat:$fromAddress")) {
                        emitState(蓝牙生命周期状态.运行中, "RECV from $fromAddress: ${收到包.载荷.正文}")
                    }
                    buffer.reset()
                }
            }

            if (buffer.size() > MAX_BUFFER_CHARS) {
                buffer.reset()
                emitState(蓝牙生命周期状态.错误, "inbound buffer overflow from $fromAddress")
            }
        },
        on连接状态变化 = { connected, address ->
            if (connected) {
                emitState(蓝牙生命周期状态.已连接, "Peripheral connected: $address")
            } else {
                入站字节缓冲区.remove(address)
                emitState(蓝牙生命周期状态.运行中, "Peripheral disconnected: $address")
            }
        }
    )
    fun 发送帖子给所有邻机(text: String): Pair<Int, ByteArray> {
        val snapshotBefore = 中心连接器.获取邻机快照()
        emitState(
            蓝牙生命周期状态.运行中,
            "send precheck: active=${snapshotBefore.活跃Gatt数}, writable=${snapshotBefore.可写邻机数}, pending=${snapshotBefore.待连接数}"
        )

        val msg = 帖子(正文 = text, 发帖人 = 本机设备编号)
        val 待发包 = 传输包.帖子包(帖子载荷.由帖子生成(msg))
        persistPayload(待发包.载荷, "send-local")
        val framed = 传输编解码器.编码(待发包) + "\n"
        val bytes = framed.toByteArray(Charsets.UTF_8)
        Log.d(TAG, "sending '${text}' -> encoded: '$framed' -> ${bytes.size} bytes")
        val count = 中心连接器.发送给所有已连接Gatt(bytes)
        Log.d(TAG, "发送给所有已连接Gatt returned count=$count")
        val snapshotAfter = 中心连接器.获取邻机快照()
        emitState(
            蓝牙生命周期状态.运行中,
            "sent count=$count (active=${snapshotAfter.活跃Gatt数}, writable=${snapshotAfter.可写邻机数}, pending=${snapshotAfter.待连接数})"
        )
        return Pair(count, bytes)
    }

    /** Re-send pre-encoded bytes without creating a new message ID. Use for retries only. */
    fun 重试发送给所有邻机(bytes: ByteArray): Int {
        return 中心连接器.发送给所有已连接Gatt(bytes)
    }

    fun 获取邻机快照(): 蓝牙中心连接器.邻机快照 {
        return 中心连接器.获取邻机快照()
    }

    /** Push our full local history to every currently-writable peer. Call this manually
     *  if a peer came back into range but the automatic on-连接 sync didn't deliver. */
    fun 强制同步() {
        val addresses = 中心连接器.取可写邻机地址()
        Log.d(TAG, "强制同步: pushing history to ${addresses.size} peer(s)")
        addresses.forEach { syncHistoryToPeer(it) }
    }

    fun 获取本机设备编号(): String = 本机设备编号

    fun 获取本机设备地址(): String {
        return try {
            adapter?.address ?: "Unavailable"
        } catch (_: SecurityException) {
            "Unavailable"
        }
    }

    private val 扫描器: 蓝牙扫描器? = adapter?.let {
        蓝牙扫描器(
            bluetoothAdapter = it,
            onDiscovered = { device ->
                emitState(蓝牙生命周期状态.连接中, "Discovered ${device.address}, connecting")
                // Delay on IO then hop to Main for the GATT 连接 call. All 蓝牙中心连接器
                // state (activeGatts, pendingConnections) is accessed from the main thread only.
                storageScope.launch {
                    delay(RECONNECT_DELAY_MS)
                    withContext(Dispatchers.Main) {
                        中心连接器.连接(device)
                    }
                }
            },
            onScanStarted = { mode ->
                emitState(蓝牙生命周期状态.运行中, "scan started ($mode)")
            },
            onScanError = { reason ->
                emitState(蓝牙生命周期状态.错误, reason)
                // Auto-retry scan after a short pause. This handles transient hardware
                if (蓝牙已启动) {
                    storageScope.launch {
                        delay(SCAN_ERROR_RETRY_MS)
                        if (蓝牙已启动) 扫描器?.开始扫描()
                    }
                }
            }
        )
    }
    private val 广播器 = adapter?.let {
        蓝牙广播器(
            bluetoothAdapter = it,
            onAdvertiseStarted = {
                emitState(蓝牙生命周期状态.运行中, "advertising started")
            },
            onAdvertiseError = { reason ->
                emitState(蓝牙生命周期状态.错误, reason)
            }
        )
    }

    fun 启动() {
        if (蓝牙已启动) return  // idempotent — ignore if already running

        if (adapter == null || !adapter.isEnabled) {
            Log.e(TAG, "Bluetooth adapter unavailable or disabled")
            emitState(蓝牙生命周期状态.错误, "Bluetooth unavailable or disabled")
            return
        }

        appContext.registerReceiver(蓝牙状态接收器, IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED))
        gatt服务端.启动()
        扫描器?.开始扫描()
        广播器?.开始广播()
        // Periodic restart
        扫描重启任务 = storageScope.launch {
            while (true) {
                delay(SCAN_RESTART_INTERVAL_MS)
                if (!蓝牙已启动) break
                Log.d(TAG, "periodic scan+advertise restart")
                扫描器?.开始扫描()
                广播器?.开始广播()
            }
        }
        storageScope.launch {
            try {
                val all = postDao.getAllLatestFirst()
                已知帖子编号.clear()
                已知帖子编号.addAll(all.map { it.id })
                emitState(蓝牙生命周期状态.运行中, "store ready: cached posts=${all.size}")
            } catch (t: Throwable) {
                Log.e(TAG, "Failed to read cached posts", t)
                emitState(蓝牙生命周期状态.错误, "store read failed: ${t.message ?: "unknown"}")
            }
        }
        emitState(蓝牙生命周期状态.运行中, "ble up")
        蓝牙已启动 = true
    }

    fun 停止() {
        蓝牙已启动 = false
        扫描重启任务?.cancel()
        扫描重启任务 = null
        try { appContext.unregisterReceiver(蓝牙状态接收器) } catch (_: IllegalArgumentException) { }
        扫描器?.停止扫描()
        广播器?.停止广播()
        中心连接器.断开全部()
        gatt服务端.停止()
        emitState(蓝牙生命周期状态.已停止, "")
    }

    /**
     * Restart scan and advertising immediately.
     */
    fun 重启扫描() {
        if (!蓝牙已启动) return
        Log.d(TAG, "重启扫描: restarting scan + advertising")
        扫描器?.开始扫描()
        广播器?.开始广播()
    }

    fun 设置生命周期监听器(listener: 蓝牙生命周期监听器?) {
        生命周期监听器 = listener
    }

    fun 关闭() {
        storageScope.cancel()
    }

    private fun emitState(state: 蓝牙生命周期状态, detail: String) {
        Log.d(TAG, "state=$state detail=$detail")
        生命周期监听器?.状态变化(state, detail)
    }

    private fun persistPayload(载荷: 帖子载荷, source: String): Boolean {
        if (!已知帖子编号.add(载荷.编号)) {
            Log.d(TAG, "dedup: skip known id=${载荷.编号} source=$source")
            return false
        }
        storageScope.launch {
            try {
                postDao.upsert(
                    PostEntity(
                        id = 载荷.编号,
                        text = 载荷.正文,
                        sender = 载荷.发帖人,
                        timestampIso8601 = 载荷.时间戳
                    )
                )
                Log.d(TAG, "Persisted message ${载荷.编号} source=$source")
            } catch (t: Throwable) {
                Log.e(TAG, "Persist failed source=$source", t)
                emitState(蓝牙生命周期状态.错误, "store write failed: ${t.message ?: "unknown"}")
            }
        }
        return true
    }

    private fun syncHistoryToPeer(address: String) {
        storageScope.launch {
            val posts = try {
                postDao.getAllLatestFirst().asReversed() // oldest first → chronological delivery
            } catch (t: Throwable) {
                Log.e(TAG, "syncHistoryToPeer: failed to load posts", t)
                return@launch
            }
            Log.d(TAG, "syncHistoryToPeer $address: ${posts.size} posts")
            withContext(Dispatchers.Main) {
                for (post in posts) {
                    val 载荷 = 帖子载荷(
                        编号 = post.id,
                        正文 = post.text,
                        发帖人 = post.sender,
                        时间戳 = post.timestampIso8601
                    )
                    val 待发包 = 传输包.帖子包(载荷)
                    val framed = 传输编解码器.编码(待发包) + "\n"
                    val bytes = framed.toByteArray(Charsets.UTF_8)
                    中心连接器.发送给指定邻机(address, bytes)
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
