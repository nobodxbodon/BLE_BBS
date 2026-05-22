package com.wuxuan.blemvp.ble

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.util.Log
import com.wuxuan.blemvp.model.判断猜拳结果
import com.wuxuan.blemvp.model.帖子
import com.wuxuan.blemvp.model.帖子载荷
import com.wuxuan.blemvp.model.传输编解码器
import com.wuxuan.blemvp.model.传输包
import com.wuxuan.blemvp.model.猜拳手势
import com.wuxuan.blemvp.model.猜拳日志
import com.wuxuan.blemvp.model.猜拳界面状态
import com.wuxuan.blemvp.model.猜拳载荷
import com.wuxuan.blemvp.storage.AppDatabase
import com.wuxuan.blemvp.storage.PostEntity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.security.MessageDigest
import java.util.Collections
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.io.ByteArrayOutputStream

class 蓝牙引擎(上下文: Context) {

    private val 应用上下文 = 上下文.applicationContext
    private val 蓝牙管理器 = 上下文.getSystemService(BluetoothManager::class.java)
    private val 蓝牙适配器 = 蓝牙管理器?.adapter
    private val 存储协程域 = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val 帖子访问对象 = AppDatabase.getInstance(应用上下文).postDao()
    private val 已知帖子编号: MutableSet<String> = Collections.newSetFromMap(ConcurrentHashMap())
    private val 已处理游戏事件编号: MutableSet<String> = Collections.newSetFromMap(ConcurrentHashMap())
    private val _猜拳状态流 = MutableStateFlow(猜拳界面状态())
    val 猜拳状态流: StateFlow<猜拳界面状态> = _猜拳状态流.asStateFlow()
    private var 本局盐值: String = UUID.randomUUID().toString()
    private var 本方已公开 = false
    private var 对方提交哈希: String? = null
    private var 对方编号: String? = null

    private val 本机设备编号: String = run {
        val 偏好设置 = 应用上下文.getSharedPreferences("blemvp_prefs", Context.MODE_PRIVATE)
        偏好设置.getString("device_id", null) ?: UUID.randomUUID().toString().also {
            偏好设置.edit().putString("device_id", it).apply()
        }
    }

    private var 生命周期监听器: 蓝牙生命周期监听器? = null
    private var 蓝牙已启动 = false
    private var 扫描重启任务: kotlinx.coroutines.Job? = null
    private val 入站字节缓冲区 = mutableMapOf<String, ByteArrayOutputStream>()

    private val 蓝牙状态接收器 = object : BroadcastReceiver() {
        override fun onReceive(上下文: Context, 意图: Intent) {
            if (意图.action != BluetoothAdapter.ACTION_STATE_CHANGED) return
            val 状态 = 意图.getIntExtra(BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.ERROR)
            when (状态) {
                BluetoothAdapter.STATE_OFF -> {
                    // BT is going off — null the GATT-server reference now so 启动() works
                    // cleanly when BT comes back on. Connections are dead; clean up maps.
                    中心连接器.断开全部()
                    gatt服务端.停止()
                    发出状态(蓝牙生命周期状态.错误, "Bluetooth turned off on this device")
                }
                BluetoothAdapter.STATE_ON -> {
                    if (蓝牙已启动) {
                        // BT was re-enabled while BLE was running — clean up stale state and restart
                        中心连接器.断开全部()
                        入站字节缓冲区.clear()
                        gatt服务端.启动()
                        扫描器?.开始扫描()
                        广播器?.开始广播()
                        发出状态(蓝牙生命周期状态.运行中, "Bluetooth re-enabled, BLE restarted")
                    }
                }
            }
        }
    }

    private val 中心连接器: 蓝牙中心连接器 = 蓝牙中心连接器(上下文,
        on连接状态变化 = { connected, address ->
        if (connected) {
            发出状态(蓝牙生命周期状态.已连接, "Central connected: $address")
        } else {
            // Forget immediately so the peer is rediscoverable as soon as it comes back
            // online (e.g. after a Bluetooth restart). Reconnect hammering is prevented
            // by the delay in the on发现设备 → 连接 path below.
            扫描器?.忘记地址(address)
            发出状态(蓝牙生命周期状态.运行中, "Central disconnected: $address")
        }
        },
        on可写就绪 = { address ->
            发出状态(蓝牙生命周期状态.运行中, "Central write ready: $address")
            同步历史给邻机(address)
        }
    )

    private val gatt服务端 = 蓝牙Gatt服务端(
        上下文 = 上下文,
        on写入到达 = { 来源地址, 字节数 ->
            发出状态(蓝牙生命周期状态.运行中, "Write arrived: $字节数 bytes from $来源地址")
        },
        on入站写入 = { data, 来源地址 ->
            Log.d(日志标记, "rx ${data.size}b from $来源地址")
            val buffer = 入站字节缓冲区.getOrPut(来源地址) { ByteArrayOutputStream() }
            buffer.write(data)

            // Accumulate raw bytes and scan for newline (0x0A) byte.
            // Only 解码 to UTF-8 after a complete frame is found so that
            // multi-byte characters (e.g. Chinese) split across chunk boundaries
            // are reassembled before decoding.
            val 缓冲字节 = buffer.toByteArray()
            var 已消费 = 0
            while (已消费 < 缓冲字节.size) {
                var 换行位置 = -1
                for (i in 已消费 until 缓冲字节.size) {
                    if (缓冲字节[i] == 0x0A.toByte()) { 换行位置 = i; break }
                }
                if (换行位置 < 0) break

                val frame = String(缓冲字节, 已消费, 换行位置 - 已消费, Charsets.UTF_8).trim()
                已消费 = 换行位置 + 1
                if (frame.isBlank()) continue

                Log.d(日志标记, "parsing frame (${换行位置 - (已消费 - 1)} bytes)")
                val 收到包 = 传输编解码器.解码(frame)
                if (收到包 is 传输包.帖子包) {
                    Log.d(日志标记, "decoded message: '${收到包.载荷.正文}'")
                    if (保存载荷(收到包.载荷, "recv:$来源地址")) {
                        发出状态(蓝牙生命周期状态.运行中, "RECV from $来源地址: ${收到包.载荷.正文}")
                    }
                } else if (收到包 is 传输包.猜拳包) {
                    处理猜拳载荷(收到包.载荷, 来源地址)
                } else {
                    Log.d(日志标记, "解码 returned null or non-message 传输包")
                }
            }

            // Keep unprocessed remainder in the buffer.
            buffer.reset()
            if (已消费 < 缓冲字节.size) {
                buffer.write(缓冲字节, 已消费, 缓冲字节.size - 已消费)
            }

            // Compat: try single-传输包 解码 on remainder (no trailing newline).
            val 剩余字节 = buffer.toByteArray()
            if (剩余字节.isNotEmpty()) {
                val 快照 = String(剩余字节, Charsets.UTF_8).trim()
                val 收到包 = 传输编解码器.解码(快照)
                if (收到包 is 传输包.帖子包) {
                    Log.d(日志标记, "compat 解码 succeeded: '${收到包.载荷.正文}'")
                    if (保存载荷(收到包.载荷, "recv-compat:$来源地址")) {
                        发出状态(蓝牙生命周期状态.运行中, "RECV from $来源地址: ${收到包.载荷.正文}")
                    }
                    buffer.reset()
                } else if (收到包 is 传输包.猜拳包) {
                    处理猜拳载荷(收到包.载荷, 来源地址)
                    buffer.reset()
                }
            }

            if (buffer.size() > 最大缓冲字符数) {
                buffer.reset()
                发出状态(蓝牙生命周期状态.错误, "inbound buffer overflow from $来源地址")
            }
        },
        on连接状态变化 = { connected, address ->
            if (connected) {
                发出状态(蓝牙生命周期状态.已连接, "Peripheral connected: $address")
            } else {
                入站字节缓冲区.remove(address)
                发出状态(蓝牙生命周期状态.运行中, "Peripheral disconnected: $address")
            }
        }
    )
    fun 发送帖子给所有邻机(text: String): Pair<Int, ByteArray> {
        val 发送前快照 = 中心连接器.获取邻机快照()
        发出状态(
            蓝牙生命周期状态.运行中,
            "send precheck: active=${发送前快照.活跃Gatt数}, writable=${发送前快照.可写邻机数}, pending=${发送前快照.待连接数}"
        )

        val 待发正文 = 帖子(正文 = text, 发帖人 = 本机设备编号)
        val 待发包 = 传输包.帖子包(帖子载荷.由帖子生成(待发正文))
        保存载荷(待发包.载荷, "send-local")
        val framed = 传输编解码器.编码(待发包) + "\n"
        val bytes = framed.toByteArray(Charsets.UTF_8)
        Log.d(日志标记, "sending '${text}' -> encoded: '$framed' -> ${bytes.size} bytes")
        val count = 中心连接器.发送给所有已连接Gatt(bytes)
        Log.d(日志标记, "发送给所有已连接Gatt returned count=$count")
        val 发送后快照 = 中心连接器.获取邻机快照()
        发出状态(
            蓝牙生命周期状态.运行中,
            "sent count=$count (active=${发送后快照.活跃Gatt数}, writable=${发送后快照.可写邻机数}, pending=${发送后快照.待连接数})"
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

    fun 选择猜拳手势(手势: 猜拳手势) {
        if (_猜拳状态流.value.本方选择 != null) return
        _猜拳状态流.value = _猜拳状态流.value.copy(
            本方选择 = 手势,
            日志 = 添加猜拳日志(_猜拳状态流.value.日志, 猜拳日志.本方已出)
        )
        发送猜拳载荷(
            事件 = "commit",
            提交哈希 = 生成提交哈希(_猜拳状态流.value.局编号, 本机设备编号, 手势, 本局盐值)
        )
        尝试公开本方选择()
    }

    fun 重新开始猜拳() {
        val 新局编号 = UUID.randomUUID().toString()
        本局盐值 = UUID.randomUUID().toString()
        本方已公开 = false
        对方提交哈希 = null
        对方编号 = null
        _猜拳状态流.value = 猜拳界面状态(
            局编号 = 新局编号,
            日志 = 添加猜拳日志(emptyList(), 猜拳日志.重新开始)
        )
        发送猜拳载荷(事件 = "reset")
    }

    /** Push our full local history to every currently-writable peer. Call this manually
     *  if a peer came back into range but the automatic on-连接 sync didn't deliver. */
    fun 强制同步() {
        val 地址列表 = 中心连接器.取可写邻机地址()
        Log.d(日志标记, "强制同步: pushing history to ${地址列表.size} peer(s)")
        地址列表.forEach { 同步历史给邻机(it) }
    }

    fun 获取本机设备编号(): String = 本机设备编号

    fun 获取本机设备地址(): String {
        return try {
            蓝牙适配器?.address ?: "Unavailable"
        } catch (_: SecurityException) {
            "Unavailable"
        }
    }

    private val 扫描器: 蓝牙扫描器? = 蓝牙适配器?.let {
        蓝牙扫描器(
            蓝牙适配器 = it,
            on发现设备 = { device ->
                发出状态(蓝牙生命周期状态.连接中, "Discovered ${device.address}, connecting")
                // Delay on IO then hop to Main for the GATT 连接 call. All 蓝牙中心连接器
                // state (活跃Gatt表, 待连接地址) is accessed from the main thread only.
                存储协程域.launch {
                    delay(重连延迟毫秒)
                    withContext(Dispatchers.Main) {
                        中心连接器.连接(device)
                    }
                }
            },
            on扫描已启动 = { mode ->
                发出状态(蓝牙生命周期状态.运行中, "scan started ($mode)")
            },
            on扫描错误 = { reason ->
                发出状态(蓝牙生命周期状态.错误, reason)
                // Auto-retry scan after a short pause. This handles transient hardware
                if (蓝牙已启动) {
                    存储协程域.launch {
                        delay(扫描错误重试毫秒)
                        if (蓝牙已启动) 扫描器?.开始扫描()
                    }
                }
            }
        )
    }
    private val 广播器 = 蓝牙适配器?.let {
        蓝牙广播器(
            蓝牙适配器 = it,
            on广播已启动 = {
                发出状态(蓝牙生命周期状态.运行中, "advertising started")
            },
            on广播错误 = { reason ->
                发出状态(蓝牙生命周期状态.错误, reason)
            }
        )
    }

    fun 启动() {
        if (蓝牙已启动) return  // idempotent — ignore if already running

        if (蓝牙适配器 == null || !蓝牙适配器.isEnabled) {
            Log.e(日志标记, "Bluetooth 蓝牙适配器 unavailable or disabled")
            发出状态(蓝牙生命周期状态.错误, "Bluetooth unavailable or disabled")
            return
        }

        应用上下文.registerReceiver(蓝牙状态接收器, IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED))
        gatt服务端.启动()
        扫描器?.开始扫描()
        广播器?.开始广播()
        // Periodic restart
        扫描重启任务 = 存储协程域.launch {
            while (true) {
                delay(扫描重启间隔毫秒)
                if (!蓝牙已启动) break
                Log.d(日志标记, "periodic scan+advertise restart")
                扫描器?.开始扫描()
                广播器?.开始广播()
            }
        }
        存储协程域.launch {
            try {
                val all = 帖子访问对象.getAllLatestFirst()
                已知帖子编号.clear()
                已知帖子编号.addAll(all.map { it.id })
                发出状态(蓝牙生命周期状态.运行中, "store ready: cached posts=${all.size}")
            } catch (t: Throwable) {
                Log.e(日志标记, "Failed to read cached posts", t)
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
        gatt服务端.停止()
        发出状态(蓝牙生命周期状态.已停止, "")
    }

    /**
     * Restart scan and advertising immediately.
     */
    fun 重启扫描() {
        if (!蓝牙已启动) return
        Log.d(日志标记, "重启扫描: restarting scan + advertising")
        扫描器?.开始扫描()
        广播器?.开始广播()
    }

    fun 设置生命周期监听器(listener: 蓝牙生命周期监听器?) {
        生命周期监听器 = listener
    }

    fun 关闭() {
        存储协程域.cancel()
    }

    private fun 发出状态(state: 蓝牙生命周期状态, detail: String) {
        Log.d(日志标记, "state=$state detail=$detail")
        生命周期监听器?.状态变化(state, detail)
    }

    private fun 保存载荷(载荷: 帖子载荷, source: String): Boolean {
        if (!已知帖子编号.add(载荷.编号)) {
            Log.d(日志标记, "dedup: skip known id=${载荷.编号} source=$source")
            return false
        }
        存储协程域.launch {
            try {
                帖子访问对象.upsert(
                    PostEntity(
                        id = 载荷.编号,
                        text = 载荷.正文,
                        sender = 载荷.发帖人,
                        timestampIso8601 = 载荷.时间戳
                    )
                )
                Log.d(日志标记, "Persisted message ${载荷.编号} source=$source")
            } catch (t: Throwable) {
                Log.e(日志标记, "Persist failed source=$source", t)
                发出状态(蓝牙生命周期状态.错误, "store write failed: ${t.message ?: "unknown"}")
            }
        }
        return true
    }

    private fun 处理猜拳载荷(载荷: 猜拳载荷, 来源地址: String) {
        if (载荷.发送方 == 本机设备编号) return
        if (!已处理游戏事件编号.add(载荷.编号)) return
        when (载荷.事件) {
            "reset" -> {
                本局盐值 = UUID.randomUUID().toString()
                本方已公开 = false
                对方提交哈希 = null
                对方编号 = 载荷.发送方
                _猜拳状态流.value = 猜拳界面状态(
                    局编号 = 载荷.局编号,
                    日志 = 添加猜拳日志(emptyList(), 猜拳日志.对方重新开始)
                )
                发出状态(蓝牙生命周期状态.运行中, "RPS reset from $来源地址")
            }
            "commit" -> {
                if (载荷.局编号 != _猜拳状态流.value.局编号) {
                    if (_猜拳状态流.value.本方选择 == null && _猜拳状态流.value.对方选择 == null) {
                        _猜拳状态流.value = 猜拳界面状态(局编号 = 载荷.局编号)
                        本局盐值 = UUID.randomUUID().toString()
                        本方已公开 = false
                        对方提交哈希 = null
                    } else {
                        return
                    }
                }
                对方提交哈希 = 载荷.提交哈希 ?: return
                对方编号 = 载荷.发送方
                _猜拳状态流.value = _猜拳状态流.value.copy(
                    对方已出 = true,
                    日志 = 添加猜拳日志(_猜拳状态流.value.日志, 猜拳日志.对方已出)
                )
                尝试公开本方选择()
                发出状态(蓝牙生命周期状态.运行中, "RPS commit from $来源地址")
            }
            "reveal" -> {
                if (载荷.局编号 != _猜拳状态流.value.局编号) return
                val 对方手势 = 猜拳手势.fromWire(载荷.手势) ?: return
                val 对方盐值 = 载荷.盐值 ?: return
                val 期待哈希 = 生成提交哈希(载荷.局编号, 载荷.发送方, 对方手势, 对方盐值)
                if (期待哈希 != 对方提交哈希) {
                    _猜拳状态流.value = _猜拳状态流.value.copy(
                        日志 = 添加猜拳日志(_猜拳状态流.value.日志, 猜拳日志.校验失败)
                    )
                    return
                }
                val 本方手势 = _猜拳状态流.value.本方选择
                _猜拳状态流.value = _猜拳状态流.value.copy(
                    对方选择 = 对方手势,
                    结果 = if (本方手势 != null) 判断猜拳结果(本方手势, 对方手势) else null,
                    日志 = 添加猜拳日志(_猜拳状态流.value.日志, 猜拳日志.对方已公开)
                )
                发出状态(蓝牙生命周期状态.运行中, "RPS reveal from $来源地址")
            }
        }
    }

    private fun 尝试公开本方选择() {
        val 本方手势 = _猜拳状态流.value.本方选择 ?: return
        if (本方已公开 || 对方提交哈希 == null) return
        本方已公开 = true
        发送猜拳载荷(
            事件 = "reveal",
            手势 = 本方手势.wireValue,
            盐值 = 本局盐值
        )
        val 对方手势 = _猜拳状态流.value.对方选择
        _猜拳状态流.value = _猜拳状态流.value.copy(
            结果 = if (对方手势 != null) 判断猜拳结果(本方手势, 对方手势) else _猜拳状态流.value.结果,
            日志 = 添加猜拳日志(_猜拳状态流.value.日志, 猜拳日志.本方已公开)
        )
    }

    private fun 发送猜拳载荷(
        事件: String,
        手势: String? = null,
        盐值: String? = null,
        提交哈希: String? = null
    ) {
        val 载荷 = 猜拳载荷(
            编号 = UUID.randomUUID().toString(),
            局编号 = _猜拳状态流.value.局编号,
            发送方 = 本机设备编号,
            事件 = 事件,
            手势 = 手势,
            盐值 = 盐值,
            提交哈希 = 提交哈希
        )
        val framed = 传输编解码器.编码(传输包.猜拳包(载荷)) + "\n"
        val bytes = framed.toByteArray(Charsets.UTF_8)
        val count = 中心连接器.发送给所有已连接Gatt(bytes)
        if (count == 0 && 中心连接器.获取邻机快照().可写邻机数 > 0) {
            存储协程域.launch {
                delay(400)
                withContext(Dispatchers.Main) {
                    中心连接器.发送给所有已连接Gatt(bytes)
                }
            }
        }
        Log.d(日志标记, "RPS event=$事件 sent count=$count")
    }

    private fun 添加猜拳日志(当前日志: List<猜拳日志>, 新日志: 猜拳日志): List<猜拳日志> {
        return (listOf(新日志) + 当前日志).take(8)
    }

    private fun 生成提交哈希(
        局编号: String,
        发送方: String,
        手势: 猜拳手势,
        盐值: String
    ): String {
        val bytes = "$局编号|$发送方|${手势.wireValue}|$盐值".toByteArray(Charsets.UTF_8)
        return MessageDigest.getInstance("SHA-256")
            .digest(bytes)
            .joinToString("") { "%02x".format(it) }
    }

    private fun 同步历史给邻机(address: String) {
        存储协程域.launch {
            val posts = try {
                帖子访问对象.getAllLatestFirst().asReversed() // oldest first → chronological delivery
            } catch (t: Throwable) {
                Log.e(日志标记, "同步历史给邻机: failed to load posts", t)
                return@launch
            }
            Log.d(日志标记, "同步历史给邻机 $address: ${posts.size} posts")
            withContext(Dispatchers.Main) {
                for (帖子记录 in posts) {
                    val 载荷 = 帖子载荷(
                        编号 = 帖子记录.id,
                        正文 = 帖子记录.text,
                        发帖人 = 帖子记录.sender,
                        时间戳 = 帖子记录.timestampIso8601
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
        private const val 日志标记 = "蓝牙引擎"
        private const val 最大缓冲字符数 = 8192
        private const val 重连延迟毫秒 = 1_500L
        private const val 扫描错误重试毫秒 = 5_000L          // retry after scan failure
        private const val 扫描重启间隔毫秒 = 5 * 60_000L // every 5 min
    }
}
