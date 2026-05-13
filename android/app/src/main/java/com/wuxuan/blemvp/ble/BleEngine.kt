package com.wuxuan.blemvp.ble

import android.bluetooth.BluetoothManager
import android.content.Context
import android.util.Log

class BleEngine(context: Context) {

    private val bluetoothManager = context.getSystemService(BluetoothManager::class.java)
    private val adapter = bluetoothManager?.adapter

    private var lifecycleListener: BleLifecycleListener? = null

    private val centralConnector = BleCentralConnector(context) { connected, address ->
        // TODO: track active peers
        if (connected) {
            emitState(BleLifecycleState.CONNECTED, "Central connected: $address")
        } else {
            emitState(BleLifecycleState.RUNNING, "Central disconnected: $address")
        }
    }

    private val gattServer = BleGattServer(
        context = context,
        onInboundWrite = { data, fromAddress ->
            Log.d(TAG, "rx ${data.size}b from $fromAddress")
            // TODO: hand off to message pipeline
        },
        onConnectionStateChanged = { connected, address ->
            if (connected) {
                emitState(BleLifecycleState.CONNECTED, "Peripheral connected: $address")
            } else {
                emitState(BleLifecycleState.RUNNING, "Peripheral disconnected: $address")
            }
        }
    )

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
    }
}
