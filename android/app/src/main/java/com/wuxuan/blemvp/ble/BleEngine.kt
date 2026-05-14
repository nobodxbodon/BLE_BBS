package com.wuxuan.blemvp.ble

import android.bluetooth.BluetoothManager
import android.content.Context
import android.util.Log
import com.wuxuan.blemvp.model.Message
import com.wuxuan.blemvp.model.MessagePayload
import com.wuxuan.blemvp.model.WireCodec
import com.wuxuan.blemvp.model.WirePacket

class BleEngine(context: Context) {

    private val bluetoothManager = context.getSystemService(BluetoothManager::class.java)
    private val adapter = bluetoothManager?.adapter

    private var lifecycleListener: BleLifecycleListener? = null
    private val inboundTextBuffers = mutableMapOf<String, StringBuilder>()

    private val centralConnector = BleCentralConnector(context,
        onConnectionStateChanged = { connected, address ->
        // TODO: track active peers
        if (connected) {
            emitState(BleLifecycleState.CONNECTED, "Central connected: $address")
        } else {
            emitState(BleLifecycleState.RUNNING, "Central disconnected: $address")
        }
        },
        onWriteReady = { address ->
            emitState(BleLifecycleState.RUNNING, "Central write ready: $address")
        }
    )

    private val gattServer = BleGattServer(
        context = context,
        onWriteArrived = { fromAddress, byteCount ->
            emitState(BleLifecycleState.RUNNING, "Write arrived: $byteCount bytes from $fromAddress")
        },
        onInboundWrite = { data, fromAddress ->
            Log.d(TAG, "rx ${data.size}b from $fromAddress")
            val chunk = data.toString(Charsets.UTF_8)
            Log.d(TAG, "chunk text: '$chunk'")
            val buffer = inboundTextBuffers.getOrPut(fromAddress) { StringBuilder() }
            buffer.append(chunk)
            Log.d(TAG, "buffer now has ${buffer.length} chars")

            // Primary framing for Android<->Android baseline transport.
            while (true) {
                val newlineIndex = buffer.indexOf("\n")
                if (newlineIndex < 0) {
                    Log.d(TAG, "no newline yet, buffer: '$buffer'")
                    break
                }

                val frame = buffer.substring(0, newlineIndex).trim()
                buffer.delete(0, newlineIndex + 1)
                if (frame.isBlank()) {
                    Log.d(TAG, "blank frame, skipping")
                    continue
                }

                Log.d(TAG, "parsing frame: '$frame'")
                val packet = WireCodec.decode(frame)
                if (packet is WirePacket.PacketMessage) {
                    Log.d(TAG, "decoded message: '${packet.payload.text}'")
                    emitState(BleLifecycleState.RUNNING, "RECV from $fromAddress: ${packet.payload.text}")
                } else {
                    Log.d(TAG, "decode returned null or non-message packet")
                }
            }

            // Compatibility: handle single-packet payloads without newline delimiter.
            val snapshot = buffer.toString().trim()
            if (snapshot.isNotEmpty()) {
                Log.d(TAG, "trying single-packet compat decode on: '$snapshot'")
                val packet = WireCodec.decode(snapshot)
                if (packet is WirePacket.PacketMessage) {
                    Log.d(TAG, "compat decode succeeded: '${packet.payload.text}'")
                    emitState(BleLifecycleState.RUNNING, "RECV from $fromAddress: ${packet.payload.text}")
                    buffer.clear()
                } else {
                    Log.d(TAG, "compat decode returned null or non-message")
                }
            }

            if (buffer.length > MAX_BUFFER_CHARS) {
                buffer.clear()
                emitState(BleLifecycleState.ERROR, "inbound buffer overflow from $fromAddress")
            }
        },
        onConnectionStateChanged = { connected, address ->
            if (connected) {
                emitState(BleLifecycleState.CONNECTED, "Peripheral connected: $address")
            } else {
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

    private val scanner = adapter?.let {
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

        gattServer.start()
        scanner?.startScan()
        advertiser?.startAdvertising()
        emitState(BleLifecycleState.RUNNING, "ble up")
    }

    fun stop() {
        scanner?.stopScan()
        advertiser?.stopAdvertising()
        centralConnector.disconnectAll()
        gattServer.stop()
        emitState(BleLifecycleState.STOPPED, "")
    }

    fun setLifecycleListener(listener: BleLifecycleListener?) {
        lifecycleListener = listener
    }

    private fun emitState(state: BleLifecycleState, detail: String) {
        Log.d(TAG, "state=$state detail=$detail")
        lifecycleListener?.onStateChanged(state, detail)
    }

    companion object {
        private const val TAG = "BleEngine"
        private const val MAX_BUFFER_CHARS = 8192
    }
}
