package com.wuxuan.blemvp.ble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log

class 蓝牙中心连接器(
    private val 上下文: Context,
    private val on连接状态变化: (connected: Boolean, 地址: String) -> Unit,
    private val on可写就绪: (地址: String) -> Unit = {}
) {
    data class 邻机快照(
        val 活跃Gatt数: Int,
        val 可写邻机数: Int,
        val 待连接数: Int
    )

    fun 已有连接或等待连接(): Boolean {
        return 活跃Gatt表.isNotEmpty() || 待连接地址.isNotEmpty()
    }

    fun 已有可写邻机(地址: String): Boolean {
        return 可写特征表.containsKey(地址)
    }

    // Write to all connected GATTs
    @SuppressLint("MissingPermission")
    fun 发送给所有已连接Gatt(字节: ByteArray): Int {
        val 分块列表 = 拆分载荷(字节)
        if (可写特征表.isEmpty() && 活跃Gatt表.isNotEmpty()) {
            Log.d(日志标记, "No writable 特征 yet, retrying 服务 discovery on ${活跃Gatt表.size} active GATT links")
            活跃Gatt表.values.forEach { gatt连接 ->
                try {
                    gatt连接.discoverServices()
                } catch (_: Throwable) {
                    // Ignore transient discovery failures.
                }
            }
        }

        Log.d(日志标记, "发送给所有已连接Gatt: enqueue ${字节.size} 字节 in ${分块列表.size} 分块列表 to ${可写特征表.size} peers")
        var 入队邻机数 = 0
        for ((地址, 特征) in 可写特征表) {
            val gatt连接 = 活跃Gatt表[地址] ?: continue
            val 写入特征 = 解析写入特征(gatt连接, 特征)
            val 已接受 = 为邻机加入分块(地址, 分块列表)
            if (!已接受) continue

            入队邻机数 += 1
            排空邻机队列(地址, gatt连接, 写入特征)
        }
        Log.d(日志标记, "发送给所有已连接Gatt completed: queued peers=$入队邻机数")
        return 入队邻机数
    }

    fun 获取邻机快照(): 邻机快照 {
        return 邻机快照(
            活跃Gatt数 = 活跃Gatt表.size,
            可写邻机数 = 可写特征表.size,
            待连接数 = 待连接地址.size
        )
    }

    @SuppressLint("MissingPermission")
    fun 发送给指定邻机(地址: String, 字节: ByteArray): Boolean {
        val gatt连接 = 活跃Gatt表[地址] ?: return false
        val 特征 = 可写特征表[地址] ?: return false
        val 写入特征 = 解析写入特征(gatt连接, 特征)

        val 分块列表 = 拆分载荷(字节)
        val 已接受 = 为邻机加入分块(地址, 分块列表)
        if (!已接受) return false

        排空邻机队列(地址, gatt连接, 写入特征)
        return true
    }

    fun 取首个可写邻机地址(): String? {
        return 可写特征表.keys.firstOrNull()
    }

    fun 取可写邻机地址(): List<String> {
        return 可写特征表.keys.toList()
    }

    private val 活跃Gatt表 = mutableMapOf<String, BluetoothGatt>()
    private val 可写特征表 = mutableMapOf<String, BluetoothGattCharacteristic>()
    private val 待连接地址 = mutableSetOf<String>()
    private val 出站队列 = mutableMapOf<String, ArrayDeque<ByteArray>>()
    private val 写入中地址 = mutableSetOf<String>()
    private val 写入中分块 = mutableMapOf<String, ByteArray>()
    private val 写入重试次数 = mutableMapOf<String, Int>()
    private val 主线程处理器 = Handler(Looper.getMainLooper())
    private val 写入超时任务 = mutableMapOf<String, Runnable>()

    private val gatt回调 = object : BluetoothGattCallback() {
        @SuppressLint("MissingPermission")
        override fun onConnectionStateChange(gatt连接: BluetoothGatt, 状态码: Int, 新状态: Int) {
            val 地址 = gatt连接.device.address
            待连接地址.remove(地址)

            when (新状态) {
                BluetoothProfile.STATE_CONNECTED -> {
                    活跃Gatt表[地址] = gatt连接
                    Log.d(日志标记, "Connected as central: $地址")
                    on连接状态变化(true, 地址)
                    // Delay before 服务 discovery to avoid GATT_ERROR 133 on Android.
                    主线程处理器.postDelayed({
                        if (活跃Gatt表.containsKey(地址)) {
                            gatt连接.discoverServices()
                        }
                    }, 发现服务延迟毫秒)
                }

                BluetoothProfile.STATE_DISCONNECTED -> {
                    Log.d(日志标记, "Disconnected as central: $地址 (状态码=$状态码)")
                    取消写入超时(地址)
                    活跃Gatt表.remove(地址)
                    可写特征表.remove(地址)
                    出站队列.remove(地址)
                    写入中地址.remove(地址)
                    写入中分块.remove(地址)
                    写入重试次数.remove(地址)
                    on连接状态变化(false, 地址)
                    gatt连接.close()
                }
            }
        }

        override fun onCharacteristicWrite(
            gatt连接: BluetoothGatt,
            特征: BluetoothGattCharacteristic,
            状态码: Int
        ) {
            val 地址 = gatt连接.device.address
            if (!写入中地址.contains(地址)) return
            取消写入超时(地址)

            if (状态码 == BluetoothGatt.GATT_SUCCESS) {
                写入中地址.remove(地址)
                写入中分块.remove(地址)
                写入重试次数.remove(地址)

                val 下一特征 = 可写特征表[地址]?.let { 解析写入特征(gatt连接, it) }
                if (下一特征 != null) {
                    排空邻机队列(地址, gatt连接, 下一特征)
                }
                return
            }

            val 分块 = 写入中分块[地址]
            val 重试次数 = 写入重试次数[地址] ?: 0
            if (分块 != null && 重试次数 < 最大写入重试次数) {
                val 写入特征 = 可写特征表[地址]?.let { 解析写入特征(gatt连接, it) }
                if (写入特征 != null && 写入分块(gatt连接, 写入特征, 分块)) {
                    写入重试次数[地址] = 重试次数 + 1
                    Log.w(日志标记, "write retry ${重试次数 + 1}/$最大写入重试次数 for $地址")
                    return
                }
            }

            if (分块 != null) {
                出站队列.getOrPut(地址) { ArrayDeque() }.addFirst(分块)
            }
            写入中地址.remove(地址)
            写入中分块.remove(地址)
            写入重试次数.remove(地址)
            try {
                gatt连接.discoverServices()
            } catch (_: Throwable) {
                // Ignore discovery retry failures.
            }
            Log.w(日志标记, "write callback failed for $地址 状态码=$状态码")
        }

        override fun onServicesDiscovered(gatt连接: BluetoothGatt, 状态码: Int) {
            val 地址 = gatt连接.device.address
            Log.d(日志标记, "onServicesDiscovered: $地址 状态码=$状态码")
            if (状态码 != BluetoothGatt.GATT_SUCCESS) {
                Log.w(日志标记, "Service discovery failed for $地址 状态码=$状态码")
                return
            }

            val 服务 = gatt连接.getService(蓝牙常量.服务UUID)
            Log.d(日志标记, "getService returned: ${if (服务 != null) "found" else "null"}")
            val 特征 = 服务?.getCharacteristic(蓝牙常量.写入UUID)
            Log.d(日志标记, "getCharacteristic(写入UUID) returned: ${if (特征 != null) "found" else "null"}")
            if (特征 != null) {
                可写特征表[地址] = 特征
                on可写就绪(地址)
                Log.d(日志标记, "Write 特征 ready for $地址")
            } else {
                Log.w(日志标记, "Write 特征 NOT found for $地址")
            }
        }
    }

    @SuppressLint("MissingPermission")
    fun 连接(device: BluetoothDevice) {
        val 地址 = device.address
        if (活跃Gatt表.containsKey(地址) || 待连接地址.contains(地址)) {
            return
        }

        待连接地址.add(地址)
        val gatt连接 = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            device.connectGatt(上下文, false, gatt回调, BluetoothDevice.TRANSPORT_LE)
        } else {
            device.connectGatt(上下文, false, gatt回调)
        }

        if (gatt连接 == null) {
            待连接地址.remove(地址)
            Log.e(日志标记, "connectGatt returned null for $地址")
        }
    }

    @SuppressLint("MissingPermission")
    fun 断开全部() {
        待连接地址.clear()
        写入超时任务.values.forEach { 主线程处理器.removeCallbacks(it) }
        写入超时任务.clear()
        val 快照 = 活跃Gatt表.values.toList()
        活跃Gatt表.clear()
        可写特征表.clear()
        出站队列.clear()
        写入中地址.clear()
        写入中分块.clear()
        写入重试次数.clear()

        快照.forEach { gatt连接 ->
            try {
                gatt连接.disconnect()
                gatt连接.close()
            } catch (_: Throwable) {
                // Ignore disconnect 关闭 race conditions.
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun 写入分块(
        gatt连接: BluetoothGatt,
        特征: BluetoothGattCharacteristic,
        分块: ByteArray
    ): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val 状态码 = gatt连接.writeCharacteristic(
                特征,
                分块,
                BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
            )
            return 状态码 == BluetoothGatt.GATT_SUCCESS
        }

        @Suppress("DEPRECATION")
        特征.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
        @Suppress("DEPRECATION")
        特征.value = 分块
        @Suppress("DEPRECATION")
        return gatt连接.writeCharacteristic(特征)
    }

    private fun 为邻机加入分块(地址: String, 分块列表: List<ByteArray>): Boolean {
        if (分块列表.isEmpty()) return false
        val 队列 = 出站队列.getOrPut(地址) { ArrayDeque() }
        for (分块 in 分块列表) {
            if (队列.size >= 每邻机最大待发分块数) {
                队列.removeFirstOrNull()
            }
            队列.addLast(分块)
        }
        return true
    }

    @SuppressLint("MissingPermission")
    private fun 排空邻机队列(
        地址: String,
        gatt连接: BluetoothGatt,
        特征: BluetoothGattCharacteristic
    ) {
        if (写入中地址.contains(地址)) return

        val 队列 = 出站队列[地址] ?: return
        val nextChunk = 队列.removeFirstOrNull() ?: return
        val 已启动 = 写入分块(gatt连接, 特征, nextChunk)
        if (!已启动) {
            队列.addFirst(nextChunk)
            Log.w(日志标记, "Failed to 启动 queued write for $地址")
            return
        }

        写入中地址.add(地址)
        写入中分块[地址] = nextChunk
        写入重试次数[地址] = 0
        安排写入超时(地址, gatt连接)
    }

    private fun 安排写入超时(地址: String, gatt连接: BluetoothGatt) {
        取消写入超时(地址)
        val 任务 = Runnable {
            Log.w(日志标记, "Write timeout for $地址 — closing zombie GATT")
            活跃Gatt表.remove(地址)
            可写特征表.remove(地址)
            出站队列.remove(地址)
            写入中地址.remove(地址)
            写入中分块.remove(地址)
            写入重试次数.remove(地址)
            try { gatt连接.disconnect(); gatt连接.close() } catch (_: Throwable) {}
            on连接状态变化(false, 地址)
        }
        写入超时任务[地址] = 任务
        主线程处理器.postDelayed(任务, 写入超时毫秒)
    }

    private fun 取消写入超时(地址: String) {
        写入超时任务.remove(地址)?.let { 主线程处理器.removeCallbacks(it) }
    }

    private fun 解析写入特征(
        gatt连接: BluetoothGatt,
        缓存特征: BluetoothGattCharacteristic
    ): BluetoothGattCharacteristic {
        val 新特征 = gatt连接
            .getService(蓝牙常量.服务UUID)
            ?.getCharacteristic(蓝牙常量.写入UUID)
        if (新特征 != null) {
            可写特征表[gatt连接.device.address] = 新特征
            return 新特征
        }
        return 缓存特征
    }

    private fun 拆分载荷(字节: ByteArray): List<ByteArray> {
        if (字节.isEmpty()) return emptyList()
        return 字节.asList().chunked(最大分块字节数).map { it.toByteArray() }
    }

    companion object {
        private const val 日志标记 = "蓝牙中心连接器"
        private const val 最大分块字节数 = 20
        private const val 每邻机最大待发分块数 = 120
        private const val 最大写入重试次数 = 2
        private const val 写入超时毫秒 = 3_000L
        private const val 发现服务延迟毫秒 = 300L
    }
}
