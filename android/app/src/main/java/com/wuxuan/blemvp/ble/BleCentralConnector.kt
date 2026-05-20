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
    private val on连接状态变化: (connected: Boolean, address: String) -> Unit,
    private val onWriteReady: (address: String) -> Unit = {}
) {
    data class 邻机快照(
        val 活跃Gatt数: Int,
        val 可写邻机数: Int,
        val 待连接数: Int
    )

    fun 已有连接或等待连接(): Boolean {
        return activeGatts.isNotEmpty() || pendingConnections.isNotEmpty()
    }

    fun 已有可写邻机(address: String): Boolean {
        return writableCharacteristics.containsKey(address)
    }

    // Write to all connected GATTs
    @SuppressLint("MissingPermission")
    fun 发送给所有已连接Gatt(bytes: ByteArray): Int {
        val chunks = chunkPayload(bytes)
        if (writableCharacteristics.isEmpty() && activeGatts.isNotEmpty()) {
            Log.d(TAG, "No writable characteristic yet, retrying service discovery on ${activeGatts.size} active GATT links")
            activeGatts.values.forEach { gatt ->
                try {
                    gatt.discoverServices()
                } catch (_: Throwable) {
                    // Ignore transient discovery failures.
                }
            }
        }

        Log.d(TAG, "发送给所有已连接Gatt: enqueue ${bytes.size} bytes in ${chunks.size} chunks to ${writableCharacteristics.size} peers")
        var queuedPeerCount = 0
        for ((address, characteristic) in writableCharacteristics) {
            val gatt = activeGatts[address] ?: continue
            val writeCharacteristic = resolveWriteCharacteristic(gatt, characteristic)
            val accepted = enqueueChunksForPeer(address, chunks)
            if (!accepted) continue

            queuedPeerCount += 1
            drainPeerQueue(address, gatt, writeCharacteristic)
        }
        Log.d(TAG, "发送给所有已连接Gatt completed: queued peers=$queuedPeerCount")
        return queuedPeerCount
    }

    fun 获取邻机快照(): 邻机快照 {
        return 邻机快照(
            活跃Gatt数 = activeGatts.size,
            可写邻机数 = writableCharacteristics.size,
            待连接数 = pendingConnections.size
        )
    }

    @SuppressLint("MissingPermission")
    fun 发送给指定邻机(address: String, bytes: ByteArray): Boolean {
        val gatt = activeGatts[address] ?: return false
        val characteristic = writableCharacteristics[address] ?: return false
        val writeCharacteristic = resolveWriteCharacteristic(gatt, characteristic)

        val chunks = chunkPayload(bytes)
        val accepted = enqueueChunksForPeer(address, chunks)
        if (!accepted) return false

        drainPeerQueue(address, gatt, writeCharacteristic)
        return true
    }

    fun 取首个可写邻机地址(): String? {
        return writableCharacteristics.keys.firstOrNull()
    }

    fun 取可写邻机地址(): List<String> {
        return writableCharacteristics.keys.toList()
    }

    private val activeGatts = mutableMapOf<String, BluetoothGatt>()
    private val writableCharacteristics = mutableMapOf<String, BluetoothGattCharacteristic>()
    private val pendingConnections = mutableSetOf<String>()
    private val outboundQueues = mutableMapOf<String, ArrayDeque<ByteArray>>()
    private val inFlightWrites = mutableSetOf<String>()
    private val inFlightChunks = mutableMapOf<String, ByteArray>()
    private val inFlightRetryCounts = mutableMapOf<String, Int>()
    private val handler = Handler(Looper.getMainLooper())
    private val writeTimeouts = mutableMapOf<String, Runnable>()

    private val gattCallback = object : BluetoothGattCallback() {
        @SuppressLint("MissingPermission")
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            val address = gatt.device.address
            pendingConnections.remove(address)

            when (newState) {
                BluetoothProfile.STATE_CONNECTED -> {
                    activeGatts[address] = gatt
                    Log.d(TAG, "Connected as central: $address")
                    on连接状态变化(true, address)
                    // Delay before service discovery to avoid GATT_ERROR 133 on Android.
                    handler.postDelayed({
                        if (activeGatts.containsKey(address)) {
                            gatt.discoverServices()
                        }
                    }, DISCOVER_DELAY_MS)
                }

                BluetoothProfile.STATE_DISCONNECTED -> {
                    Log.d(TAG, "Disconnected as central: $address (status=$status)")
                    cancelWriteTimeout(address)
                    activeGatts.remove(address)
                    writableCharacteristics.remove(address)
                    outboundQueues.remove(address)
                    inFlightWrites.remove(address)
                    inFlightChunks.remove(address)
                    inFlightRetryCounts.remove(address)
                    on连接状态变化(false, address)
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
            if (!inFlightWrites.contains(address)) return
            cancelWriteTimeout(address)

            if (status == BluetoothGatt.GATT_SUCCESS) {
                inFlightWrites.remove(address)
                inFlightChunks.remove(address)
                inFlightRetryCounts.remove(address)

                val nextCharacteristic = writableCharacteristics[address]?.let { resolveWriteCharacteristic(gatt, it) }
                if (nextCharacteristic != null) {
                    drainPeerQueue(address, gatt, nextCharacteristic)
                }
                return
            }

            val chunk = inFlightChunks[address]
            val retries = inFlightRetryCounts[address] ?: 0
            if (chunk != null && retries < MAX_WRITE_RETRIES) {
                val writeCharacteristic = writableCharacteristics[address]?.let { resolveWriteCharacteristic(gatt, it) }
                if (writeCharacteristic != null && writeChunk(gatt, writeCharacteristic, chunk)) {
                    inFlightRetryCounts[address] = retries + 1
                    Log.w(TAG, "write retry ${retries + 1}/$MAX_WRITE_RETRIES for $address")
                    return
                }
            }

            if (chunk != null) {
                outboundQueues.getOrPut(address) { ArrayDeque() }.addFirst(chunk)
            }
            inFlightWrites.remove(address)
            inFlightChunks.remove(address)
            inFlightRetryCounts.remove(address)
            try {
                gatt.discoverServices()
            } catch (_: Throwable) {
                // Ignore discovery retry failures.
            }
            Log.w(TAG, "write callback failed for $address status=$status")
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
                writableCharacteristics[address] = characteristic
                onWriteReady(address)
                Log.d(TAG, "Write characteristic ready for $address")
            } else {
                Log.w(TAG, "Write characteristic NOT found for $address")
            }
        }
    }

    @SuppressLint("MissingPermission")
    fun 连接(device: BluetoothDevice) {
        val address = device.address
        if (activeGatts.containsKey(address) || pendingConnections.contains(address)) {
            return
        }

        pendingConnections.add(address)
        val gatt = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            device.connectGatt(context, false, gattCallback, BluetoothDevice.TRANSPORT_LE)
        } else {
            device.connectGatt(context, false, gattCallback)
        }

        if (gatt == null) {
            pendingConnections.remove(address)
            Log.e(TAG, "connectGatt returned null for $address")
        }
    }

    @SuppressLint("MissingPermission")
    fun 断开全部() {
        pendingConnections.clear()
        writeTimeouts.values.forEach { handler.removeCallbacks(it) }
        writeTimeouts.clear()
        val snapshot = activeGatts.values.toList()
        activeGatts.clear()
        writableCharacteristics.clear()
        outboundQueues.clear()
        inFlightWrites.clear()
        inFlightChunks.clear()
        inFlightRetryCounts.clear()

        snapshot.forEach { gatt ->
            try {
                gatt.disconnect()
                gatt.close()
            } catch (_: Throwable) {
                // Ignore disconnect 关闭 race conditions.
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun writeChunk(
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

    private fun enqueueChunksForPeer(address: String, chunks: List<ByteArray>): Boolean {
        if (chunks.isEmpty()) return false
        val queue = outboundQueues.getOrPut(address) { ArrayDeque() }
        for (chunk in chunks) {
            if (queue.size >= MAX_PENDING_CHUNKS_PER_PEER) {
                queue.removeFirstOrNull()
            }
            queue.addLast(chunk)
        }
        return true
    }

    @SuppressLint("MissingPermission")
    private fun drainPeerQueue(
        address: String,
        gatt: BluetoothGatt,
        characteristic: BluetoothGattCharacteristic
    ) {
        if (inFlightWrites.contains(address)) return

        val queue = outboundQueues[address] ?: return
        val nextChunk = queue.removeFirstOrNull() ?: return
        val started = writeChunk(gatt, characteristic, nextChunk)
        if (!started) {
            queue.addFirst(nextChunk)
            Log.w(TAG, "Failed to 启动 queued write for $address")
            return
        }

        inFlightWrites.add(address)
        inFlightChunks[address] = nextChunk
        inFlightRetryCounts[address] = 0
        scheduleWriteTimeout(address, gatt)
    }

    private fun scheduleWriteTimeout(address: String, gatt: BluetoothGatt) {
        cancelWriteTimeout(address)
        val runnable = Runnable {
            Log.w(TAG, "Write timeout for $address — closing zombie GATT")
            activeGatts.remove(address)
            writableCharacteristics.remove(address)
            outboundQueues.remove(address)
            inFlightWrites.remove(address)
            inFlightChunks.remove(address)
            inFlightRetryCounts.remove(address)
            try { gatt.disconnect(); gatt.close() } catch (_: Throwable) {}
            on连接状态变化(false, address)
        }
        writeTimeouts[address] = runnable
        handler.postDelayed(runnable, WRITE_TIMEOUT_MS)
    }

    private fun cancelWriteTimeout(address: String) {
        writeTimeouts.remove(address)?.let { handler.removeCallbacks(it) }
    }

    private fun resolveWriteCharacteristic(
        gatt: BluetoothGatt,
        cachedCharacteristic: BluetoothGattCharacteristic
    ): BluetoothGattCharacteristic {
        val fresh = gatt
            .getService(蓝牙常量.服务UUID)
            ?.getCharacteristic(蓝牙常量.写入UUID)
        if (fresh != null) {
            writableCharacteristics[gatt.device.address] = fresh
            return fresh
        }
        return cachedCharacteristic
    }

    private fun chunkPayload(bytes: ByteArray): List<ByteArray> {
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
