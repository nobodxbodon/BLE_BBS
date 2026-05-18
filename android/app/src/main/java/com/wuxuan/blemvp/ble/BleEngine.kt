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
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.io.ByteArrayOutputStream

class BleEngine(context: Context) {

    private val appContext = context.applicationContext
    private val bluetoothManager = context.getSystemService(BluetoothManager::class.java)
    private val adapter = bluetoothManager?.adapter
    private val storageScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val postDao = AppDatabase.getInstance(appContext).postDao()
    private val knownMessageIds: MutableSet<String> = Collections.newSetFromMap(ConcurrentHashMap())

    private val localDeviceId: String = run {
        val prefs = appContext.getSharedPreferences("blemvp_prefs", Context.MODE_PRIVATE)
        prefs.getString("device_id", null) ?: UUID.randomUUID().toString().also {
            prefs.edit().putString("device_id", it).apply()
        }
    }

    private var lifecycleListener: BleLifecycleListener? = null
    private var isBleStarted = false
    private var scanRestartJob: kotlinx.coroutines.Job? = null
    private val inboundByteBuffers = mutableMapOf<String, ByteArrayOutputStream>()

    private val btStateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action != BluetoothAdapter.ACTION_STATE_CHANGED) return
            val state = intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.ERROR)
            when (state) {
                BluetoothAdapter.STATE_OFF -> {
                    // BT is going off — null the GATT-server reference now so start() works
                    // cleanly when BT comes back on. Connections are dead; clean up maps.
                    centralConnector.disconnectAll()
                    gattServer.stop()
                    emitState(BleLifecycleState.ERROR, "Bluetooth turned off on this device")
                }
                BluetoothAdapter.STATE_ON -> {
                    if (isBleStarted) {
                        // BT was re-enabled while BLE was running — clean up stale state and restart
                        centralConnector.disconnectAll()
                        inboundByteBuffers.clear()
                        gattServer.start()
                        scanner?.startScan()
                        advertiser?.startAdvertising()
                        emitState(BleLifecycleState.RUNNING, "Bluetooth re-enabled, BLE restarted")
                    }
                }
            }
        }
    }

    private val centralConnector: BleCentralConnector = BleCentralConnector(context,
        onConnectionStateChanged = { connected, address ->
        if (connected) {
            emitState(BleLifecycleState.CONNECTED, "Central connected: $address")
        } else {
            // Forget immediately so the peer is rediscoverable as soon as it comes back
            // online (e.g. after a Bluetooth restart). Reconnect hammering is prevented
            // by the delay in the onDiscovered → connect path below.
            scanner?.forgetAddress(address)
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
    fun sendMessageToAllPeers(text: String): Pair<Int, ByteArray> {
        val snapshotBefore = centralConnector.getPeerSnapshot()
        emitState(
            BleLifecycleState.RUNNING,
            "send precheck: active=${snapshotBefore.activeGattCount}, writable=${snapshotBefore.writableCount}, pending=${snapshotBefore.pendingCount}"
        )

        val msg = Message(text = text, senderName = localDeviceId)
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
        return Pair(count, bytes)
    }

    /** Re-send pre-encoded bytes without creating a new message ID. Use for retries only. */
    fun retrySendToAllPeers(bytes: ByteArray): Int {
        return centralConnector.sendToAllConnectedGatt(bytes)
    }

    fun getPeerSnapshot(): BleCentralConnector.PeerSnapshot {
        return centralConnector.getPeerSnapshot()
    }

    /** Push our full local history to every currently-writable peer. Call this manually
     *  if a peer came back into range but the automatic on-connect sync didn't deliver. */
    fun forceSync() {
        val addresses = centralConnector.getWritablePeerAddresses()
        Log.d(TAG, "forceSync: pushing history to ${addresses.size} peer(s)")
        addresses.forEach { syncHistoryToPeer(it) }
    }

    fun getLocalDeviceId(): String = localDeviceId

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
                // Delay on IO then hop to Main for the GATT connect call. All BleCentralConnector
                // state (activeGatts, pendingConnections) is accessed from the main thread only.
                storageScope.launch {
                    delay(RECONNECT_DELAY_MS)
                    withContext(Dispatchers.Main) {
                        centralConnector.connect(device)
                    }
                }
            },
            onScanStarted = { mode ->
                emitState(BleLifecycleState.RUNNING, "scan started ($mode)")
            },
            onScanError = { reason ->
                emitState(BleLifecycleState.ERROR, reason)
                // Auto-retry scan after a short pause. This handles transient hardware
                if (isBleStarted) {
                    storageScope.launch {
                        delay(SCAN_ERROR_RETRY_MS)
                        if (isBleStarted) scanner?.startScan()
                    }
                }
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
        if (isBleStarted) return  // idempotent — ignore if already running

        if (adapter == null || !adapter.isEnabled) {
            Log.e(TAG, "Bluetooth adapter unavailable or disabled")
            emitState(BleLifecycleState.ERROR, "Bluetooth unavailable or disabled")
            return
        }

        appContext.registerReceiver(btStateReceiver, IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED))
        gattServer.start()
        scanner?.startScan()
        advertiser?.startAdvertising()
        // Periodic restart
        scanRestartJob = storageScope.launch {
            while (true) {
                delay(SCAN_RESTART_INTERVAL_MS)
                if (!isBleStarted) break
                Log.d(TAG, "periodic scan+advertise restart")
                scanner?.startScan()
                advertiser?.startAdvertising()
            }
        }
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
        isBleStarted = true
    }

    fun stop() {
        isBleStarted = false
        scanRestartJob?.cancel()
        scanRestartJob = null
        try { appContext.unregisterReceiver(btStateReceiver) } catch (_: IllegalArgumentException) { }
        scanner?.stopScan()
        advertiser?.stopAdvertising()
        centralConnector.disconnectAll()
        gattServer.stop()
        emitState(BleLifecycleState.STOPPED, "")
    }

    /**
     * Restart scan and advertising immediately.
     */
    fun rearmScan() {
        if (!isBleStarted) return
        Log.d(TAG, "rearmScan: restarting scan + advertising")
        scanner?.startScan()
        advertiser?.startAdvertising()
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
        private const val RECONNECT_DELAY_MS = 1_500L
        private const val SCAN_ERROR_RETRY_MS = 5_000L          // retry after scan failure
        private const val SCAN_RESTART_INTERVAL_MS = 5 * 60_000L // every 5 min
    }
}
