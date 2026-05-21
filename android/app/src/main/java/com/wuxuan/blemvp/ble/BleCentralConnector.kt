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
    private val context: Context,
    private val 连接状态变化: (connected: Boolean, address: String) -> Unit,
    private val 写入就绪: (address: String) -> Unit = {}
) {
    data class 对等快照(
        val 活跃Gatt数: Int,
        val 可写数: Int,
        val 待连接数: Int
    )

    fun 有连接或待连接(): Boolean {
        return 活跃Gatt连接.isNotEmpty() || 待连接地址.isNotEmpty()
    }

    fun 有可写对端(address: String): Boolean {
        return 可写特征.containsKey(address)
    }

    // Write to all connected GATTs
    @SuppressLint("MissingPermission")
    fun 发给所有已连接Gatt(bytes: ByteArray): Int {
        val chunks = 切分载荷(bytes)
        if (可写特征.isEmpty() && 活跃Gatt连接.isNotEmpty()) {
            Log.d(TAG, "No writable characteristic yet, retrying service discovery on ${活跃Gatt连接.size} active GATT links")
            活跃Gatt连接.values.forEach { gatt ->
                try {
                    gatt.discoverServices()
                } catch (_: Throwable) {
                    // Ignore transient discovery failures.
                }
            }
        }

        Log.d(TAG, "发给所有已连接Gatt: enqueue ${bytes.size} bytes in ${chunks.size} chunks to ${可写特征.size} peers")
        var queuedPeerCount = 0
        for ((address, characteristic) in 可写特征) {
            val gatt = 活跃Gatt连接[address] ?: continue
            val 写入特征 = 解析写入特征(gatt, characteristic)
            val accepted = 为对端排队分片(address, chunks)
            if (!accepted) continue

            queuedPeerCount += 1
            消费对端队列(address, gatt, 写入特征)
        }
        Log.d(TAG, "发给所有已连接Gatt completed: queued peers=$queuedPeerCount")
        return queuedPeerCount
    }

    fun 获取对等快照(): 对等快照 {
        return 对等快照(
            活跃Gatt数 = 活跃Gatt连接.size,
            可写数 = 可写特征.size,
            待连接数 = 待连接地址.size
        )
    }

    @SuppressLint("MissingPermission")
    fun 发给对端(address: String, bytes: ByteArray): Boolean {
        val gatt = 活跃Gatt连接[address] ?: return false
        val characteristic = 可写特征[address] ?: return false
        val 写入特征 = 解析写入特征(gatt, characteristic)

        val chunks = 切分载荷(bytes)
        val accepted = 为对端排队分片(address, chunks)
        if (!accepted) return false

        消费对端队列(address, gatt, 写入特征)
        return true
    }

    fun 首个可写对端地址(): String? {
        return 可写特征.keys.firstOrNull()
    }

    fun 获取可写对端地址(): List<String> {
        return 可写特征.keys.toList()
    }

    private val 活跃Gatt连接 = mutableMapOf<String, BluetoothGatt>()
    private val 可写特征 = mutableMapOf<String, BluetoothGattCharacteristic>()
    private val 待连接地址 = mutableSetOf<String>()
    private val 出站队列 = mutableMapOf<String, ArrayDeque<ByteArray>>()
    private val 进行中写入 = mutableSetOf<String>()
    private val 进行中分片 = mutableMapOf<String, ByteArray>()
    private val 进行中重试次数 = mutableMapOf<String, Int>()
    private val 主线程处理器 = Handler(Looper.getMainLooper())
    private val 写入超时任务 = mutableMapOf<String, Runnable>()

    private val Gatt回调 = object : BluetoothGattCallback() {
        @SuppressLint("MissingPermission")
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            val address = gatt.device.address
            待连接地址.remove(address)

            when (newState) {
                BluetoothProfile.STATE_CONNECTED -> {
                    活跃Gatt连接[address] = gatt
                    Log.d(TAG, "Connected as central: $address")
                    连接状态变化(true, address)
                    // Delay before service discovery to avoid GATT_ERROR 133 on Android.
                    主线程处理器.postDelayed({
                        if (活跃Gatt连接.containsKey(address)) {
                            gatt.discoverServices()
                        }
                    }, DISCOVER_DELAY_MS)
                }

                BluetoothProfile.STATE_DISCONNECTED -> {
                    Log.d(TAG, "Disconnected as central: $address (status=$status)")
                    取消写入超时(address)
                    活跃Gatt连接.remove(address)
                    可写特征.remove(address)
                    出站队列.remove(address)
                    进行中写入.remove(address)
                    进行中分片.remove(address)
                    进行中重试次数.remove(address)
                    连接状态变化(false, address)
                    gatt.close()
                }
            }
        }

        override fun onCharacteristicWrite(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            status: Int
        ) {
            val address = gatt.device.address
            if (!进行中写入.contains(address)) return
            取消写入超时(address)

            if (status == BluetoothGatt.GATT_SUCCESS) {
                进行中写入.remove(address)
                进行中分片.remove(address)
                进行中重试次数.remove(address)

                val nextCharacteristic = 可写特征[address]?.let { 解析写入特征(gatt, it) }
                if (nextCharacteristic != null) {
                    消费对端队列(address, gatt, nextCharacteristic)
                }
                return
            }

            val chunk = 进行中分片[address]
            val retries = 进行中重试次数[address] ?: 0
            if (chunk != null && retries < MAX_WRITE_RETRIES) {
                val 写入特征 = 可写特征[address]?.let { 解析写入特征(gatt, it) }
                if (写入特征 != null && 写入分片(gatt, 写入特征, chunk)) {
                    进行中重试次数[address] = retries + 1
                    Log.w(TAG, "write retry ${retries + 1}/$MAX_WRITE_RETRIES for $address")
                    return
                }
            }

            if (chunk != null) {
                出站队列.getOrPut(address) { ArrayDeque() }.addFirst(chunk)
            }
            进行中写入.remove(address)
            进行中分片.remove(address)
            进行中重试次数.remove(address)
            try {
                gatt.discoverServices()
            } catch (_: Throwable) {
                // Ignore discovery retry failures.
            }
            Log.w(TAG, "write 回调 failed for $address status=$status")
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            val address = gatt.device.address
            Log.d(TAG, "onServicesDiscovered: $address status=$status")
            if (status != BluetoothGatt.GATT_SUCCESS) {
                Log.w(TAG, "Service discovery failed for $address status=$status")
                return
            }

            val service = gatt.getService(蓝牙常量.服务UUID)
            Log.d(TAG, "getService returned: ${if (service != null) "found" else "null"}")
            val characteristic = service?.getCharacteristic(蓝牙常量.写入UUID)
            Log.d(TAG, "getCharacteristic(写入UUID) returned: ${if (characteristic != null) "found" else "null"}")
            if (characteristic != null) {
                可写特征[address] = characteristic
                写入就绪(address)
                Log.d(TAG, "Write characteristic ready for $address")
            } else {
                Log.w(TAG, "Write characteristic NOT found for $address")
            }
        }
    }

    @SuppressLint("MissingPermission")
    fun 连接(device: BluetoothDevice) {
        val address = device.address
        if (活跃Gatt连接.containsKey(address) || 待连接地址.contains(address)) {
            return
        }

        待连接地址.add(address)
        val gatt = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            device.connectGatt(context, false, Gatt回调, BluetoothDevice.TRANSPORT_LE)
        } else {
            device.connectGatt(context, false, Gatt回调)
        }

        if (gatt == null) {
            待连接地址.remove(address)
            Log.e(TAG, "connectGatt returned null for $address")
        }
    }

    @SuppressLint("MissingPermission")
    fun 断开全部() {
        待连接地址.clear()
        写入超时任务.values.forEach { 主线程处理器.removeCallbacks(it) }
        写入超时任务.clear()
        val snapshot = 活跃Gatt连接.values.toList()
        活跃Gatt连接.clear()
        可写特征.clear()
        出站队列.clear()
        进行中写入.clear()
        进行中分片.clear()
        进行中重试次数.clear()

        snapshot.forEach { gatt ->
            try {
                gatt.disconnect()
                gatt.close()
            } catch (_: Throwable) {
                // Ignore disconnect close race conditions.
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun 写入分片(
        gatt: BluetoothGatt,
        characteristic: BluetoothGattCharacteristic,
        chunk: ByteArray
    ): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val status = gatt.writeCharacteristic(
                characteristic,
                chunk,
                BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
            )
            return status == BluetoothGatt.GATT_SUCCESS
        }

        @Suppress("DEPRECATION")
        characteristic.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
        @Suppress("DEPRECATION")
        characteristic.value = chunk
        @Suppress("DEPRECATION")
        return gatt.writeCharacteristic(characteristic)
    }

    private fun 为对端排队分片(address: String, chunks: List<ByteArray>): Boolean {
        if (chunks.isEmpty()) return false
        val queue = 出站队列.getOrPut(address) { ArrayDeque() }
        for (chunk in chunks) {
            if (queue.size >= MAX_PENDING_CHUNKS_PER_PEER) {
                queue.removeFirstOrNull()
            }
            queue.addLast(chunk)
        }
        return true
    }

    @SuppressLint("MissingPermission")
    private fun 消费对端队列(
        address: String,
        gatt: BluetoothGatt,
        characteristic: BluetoothGattCharacteristic
    ) {
        if (进行中写入.contains(address)) return

        val queue = 出站队列[address] ?: return
        val nextChunk = queue.removeFirstOrNull() ?: return
        val started = 写入分片(gatt, characteristic, nextChunk)
        if (!started) {
            queue.addFirst(nextChunk)
            Log.w(TAG, "Failed to start queued write for $address")
            return
        }

        进行中写入.add(address)
        进行中分片[address] = nextChunk
        进行中重试次数[address] = 0
        安排写入超时(address, gatt)
    }

    private fun 安排写入超时(address: String, gatt: BluetoothGatt) {
        取消写入超时(address)
        val runnable = Runnable {
            Log.w(TAG, "Write timeout for $address — closing zombie GATT")
            活跃Gatt连接.remove(address)
            可写特征.remove(address)
            出站队列.remove(address)
            进行中写入.remove(address)
            进行中分片.remove(address)
            进行中重试次数.remove(address)
            try { gatt.disconnect(); gatt.close() } catch (_: Throwable) {}
            连接状态变化(false, address)
        }
        写入超时任务[address] = runnable
        主线程处理器.postDelayed(runnable, WRITE_TIMEOUT_MS)
    }

    private fun 取消写入超时(address: String) {
        写入超时任务.remove(address)?.let { 主线程处理器.removeCallbacks(it) }
    }

    private fun 解析写入特征(
        gatt: BluetoothGatt,
        cachedCharacteristic: BluetoothGattCharacteristic
    ): BluetoothGattCharacteristic {
        val fresh = gatt
            .getService(蓝牙常量.服务UUID)
            ?.getCharacteristic(蓝牙常量.写入UUID)
        if (fresh != null) {
            可写特征[gatt.device.address] = fresh
            return fresh
        }
        return cachedCharacteristic
    }

    private fun 切分载荷(bytes: ByteArray): List<ByteArray> {
        if (bytes.isEmpty()) return emptyList()
        return bytes.asList().chunked(MAX_CHUNK_BYTES).map { it.toByteArray() }
    }

    companion object {
        private const val TAG = "蓝牙中心连接器"
        private const val MAX_CHUNK_BYTES = 20
        private const val MAX_PENDING_CHUNKS_PER_PEER = 120
        private const val MAX_WRITE_RETRIES = 2
        private const val WRITE_TIMEOUT_MS = 3_000L
        private const val DISCOVER_DELAY_MS = 300L
    }
}
