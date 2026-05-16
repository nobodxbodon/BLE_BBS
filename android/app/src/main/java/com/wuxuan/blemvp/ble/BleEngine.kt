package com.wuxuan.blemvp.ble

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.util.Log
import com.wuxuan.blemvp.model.Message
import com.wuxuan.blemvp.model.MessagePayload
import com.wuxuan.blemvp.model.WireCodec
import com.wuxuan.blemvp.model.WirePacket
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
import java.util.concurrent.ConcurrentHashMap
import java.io.ByteArrayOutputStream

class BleEngine(context: Context) {

    private val appContext = context.applicationContext
    private val bluetoothManager = context.getSystemService(BluetoothManager::class.java)
    private val adapter = bluetoothManager?.adapter
    private val storageScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val postDao = AppDatabase.getInstance(appContext).postDao()
    private val knownMessageIds: MutableSet<String> = Collections.newSetFromMap(ConcurrentHashMap())

    private var lifecycleListener: BleLifecycleListener? = null
    private val inboundByteBuffers = mutableMapOf<String, ByteArrayOutputStream>()

    private val btStateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action != BluetoothAdapter.ACTION_STATE_CHANGED) return
            val state = intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.ERROR)
            if (state == BluetoothAdapter.STATE_OFF) {
                emitState(BleLifecycleState.ERROR, "Bluetooth turned off on this device")
            }
        }
    }

    private val centralConnector: BleCentralConnector = BleCentralConnector(context,
        onConnectionStateChanged = { connected, address ->
        // TODO: track active peers
        if (connected) {
            emitState(BleLifecycleState.CONNECTED, "Central connected: $address")
        } else {
            storageScope.launch {
                delay(RECONNECT_DELAY_MS)
                scanner?.forgetAddress(address)
            }
            emitState(BleLifecycleState.RUNNING, "Central disconnected: $address")
        }
        },
        onWriteReady = { address ->
            emitState(BleLifecycleState.RUNNING, "Central write ready: $address")
            syncHistoryToPeer(address)
        }
    )

    private val gattServer = BleGattServer(
        context = context,
        onWriteArrived = { fromAddress, byteCount ->
            emitState(BleLifecycleState.RUNNING, "Write arrived: $byteCount bytes from $fromAddress")
        },
        onInboundWrite = { data, fromAddress ->
            Log.d(TAG, "rx ${data.size}b from $fromAddress")
            val buffer = inboundByteBuffers.getOrPut(fromAddress) { ByteArrayOutputStream() }
            buffer.write(data)

            // Accumulate raw bytes and scan for newline (0x0A) byte.
            // Only decode to UTF-8 after a complete frame is found so that
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
                val packet = WireCodec.decode(frame)
                if (packet is WirePacket.PacketMessage) {
                    Log.d(TAG, "decoded message: '${packet.payload.text}'")
                    if (persistPayload(packet.payload, "recv:$fromAddress")) {
                        emitState(BleLifecycleState.RUNNING, "RECV from $fromAddress: ${packet.payload.text}")
                    }
                } else {
                    Log.d(TAG, "decode returned null or non-message packet")
                }
            }

            // Keep unprocessed remainder in the buffer.
            buffer.reset()
            if (consumed < bufBytes.size) {
                buffer.write(bufBytes, consumed, bufBytes.size - consumed)
            }

            // Compat: try single-packet decode on remainder (no trailing newline).
            val remaining = buffer.toByteArray()
            if (remaining.isNotEmpty()) {
                val snapshot = String(remaining, Charsets.UTF_8).trim()
                val packet = WireCodec.decode(snapshot)
                if (packet is WirePacket.PacketMessage) {
                    Log.d(TAG, "compat decode succeeded: '${packet.payload.text}'")
                    if (persistPayload(packet.payload, "recv-compat:$fromAddress")) {
                        emitState(BleLifecycleState.RUNNING, "RECV from $fromAddress: ${packet.payload.text}")
                    }
                    buffer.reset()
                }
            }

            if (buffer.size() > MAX_BUFFER_CHARS) {
                buffer.reset()
                emitState(BleLifecycleState.ERROR, "inbound buffer overflow from $fromAddress")
            }
        },
        onConnectionStateChanged = { connected, address ->
            if (connected) {
                emitState(BleLifecycleState.CONNECTED, "Peripheral connected: $address")
            } else {
                inboundByteBuffers.remove(address)
                emitState(BleLifecycleState.RUNNING, "Peripheral disconnected: $address")
            }
        }
    )
    fun sendMessageToAllPeers(text: String): Int {
        val snapshotBefore = centralConnector.getPeerSnapshot()
        emitState(
            BleLifecycleState.RUNNING,
            "send precheck: active=${snapshotBefore.activeGattCount}, writable=${snapshotBefore.writableCount}, pending=${snapshotBefore.pendingCount}"
        )

        val msg = Message(text = text, senderName = "Android")
        val packet = WirePacket.PacketMessage(MessagePayload.fromMessage(msg))
    persistPayload(packet.payload, "send-local")
        val framed = WireCodec.encode(packet) + "\n"
        val bytes = framed.toByteArray(Charsets.UTF_8)
        Log.d(TAG, "sending '${text}' -> encoded: '$framed' -> ${bytes.size} bytes")
        val count = centralConnector.sendToAllConnectedGatt(bytes)
        Log.d(TAG, "sendToAllConnectedGatt returned count=$count")
        val snapshotAfter = centralConnector.getPeerSnapshot()
        emitState(
            BleLifecycleState.RUNNING,
            "sent count=$count (active=${snapshotAfter.activeGattCount}, writable=${snapshotAfter.writableCount}, pending=${snapshotAfter.pendingCount})"
        )
        return count
    }

    fun getPeerSnapshot(): BleCentralConnector.PeerSnapshot {
        return centralConnector.getPeerSnapshot()
    }

    fun getLocalDeviceAddress(): String {
        return try {
            adapter?.address ?: "Unavailable"
        } catch (_: SecurityException) {
            "Unavailable"
        }
    }

    private val scanner: BleScanner? = adapter?.let {
        BleScanner(
            bluetoothAdapter = it,
            onDiscovered = { device ->
                emitState(BleLifecycleState.CONNECTING, "Discovered ${device.address}, connecting")
                centralConnector.connect(device)
            },
            onScanStarted = { mode ->
                emitState(BleLifecycleState.RUNNING, "scan started ($mode)")
            },
            onScanError = { reason ->
                emitState(BleLifecycleState.ERROR, reason)
            }
        )
    }
    private val advertiser = adapter?.let {
        BleAdvertiser(
            bluetoothAdapter = it,
            onAdvertiseStarted = {
                emitState(BleLifecycleState.RUNNING, "advertising started")
            },
            onAdvertiseError = { reason ->
                emitState(BleLifecycleState.ERROR, reason)
            }
        )
    }

    fun start() {
        if (adapter == null || !adapter.isEnabled) {
            Log.e(TAG, "Bluetooth adapter unavailable or disabled")
            emitState(BleLifecycleState.ERROR, "Bluetooth unavailable or disabled")
            return
        }

        appContext.registerReceiver(btStateReceiver, IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED))
        gattServer.start()
        scanner?.startScan()
        advertiser?.startAdvertising()
        storageScope.launch {
            try {
                val all = postDao.getAllLatestFirst()
                knownMessageIds.clear()
                knownMessageIds.addAll(all.map { it.id })
                emitState(BleLifecycleState.RUNNING, "store ready: cached posts=${all.size}")
            } catch (t: Throwable) {
                Log.e(TAG, "Failed to read cached posts", t)
                emitState(BleLifecycleState.ERROR, "store read failed: ${t.message ?: "unknown"}")
            }
        }
        emitState(BleLifecycleState.RUNNING, "ble up")
    }

    fun stop() {
        try { appContext.unregisterReceiver(btStateReceiver) } catch (_: IllegalArgumentException) { }
        scanner?.stopScan()
        advertiser?.stopAdvertising()
        centralConnector.disconnectAll()
        gattServer.stop()
        emitState(BleLifecycleState.STOPPED, "")
    }

    fun setLifecycleListener(listener: BleLifecycleListener?) {
        lifecycleListener = listener
    }

    fun close() {
        storageScope.cancel()
    }

    private fun emitState(state: BleLifecycleState, detail: String) {
        Log.d(TAG, "state=$state detail=$detail")
        lifecycleListener?.onStateChanged(state, detail)
    }

    private fun persistPayload(payload: MessagePayload, source: String): Boolean {
        if (!knownMessageIds.add(payload.id)) {
            Log.d(TAG, "dedup: skip known id=${payload.id} source=$source")
            return false
        }
        storageScope.launch {
            try {
                postDao.upsert(
                    PostEntity(
                        id = payload.id,
                        text = payload.text,
                        sender = payload.sender,
                        timestampIso8601 = payload.timestamp
                    )
                )
                Log.d(TAG, "Persisted message ${payload.id} source=$source")
            } catch (t: Throwable) {
                Log.e(TAG, "Persist failed source=$source", t)
                emitState(BleLifecycleState.ERROR, "store write failed: ${t.message ?: "unknown"}")
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
                    val payload = MessagePayload(
                        id = post.id,
                        text = post.text,
                        sender = post.sender,
                        timestamp = post.timestampIso8601
                    )
                    val packet = WirePacket.PacketMessage(payload)
                    val framed = WireCodec.encode(packet) + "\n"
                    val bytes = framed.toByteArray(Charsets.UTF_8)
                    centralConnector.sendToPeer(address, bytes)
                }
            }
        }
    }

    companion object {
        private const val TAG = "BleEngine"
        private const val MAX_BUFFER_CHARS = 8192
        private const val RECONNECT_DELAY_MS = 5_000L
    }
}
