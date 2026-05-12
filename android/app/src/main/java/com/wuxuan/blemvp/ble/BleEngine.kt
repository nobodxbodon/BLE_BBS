package com.wuxuan.blemvp.ble

import android.bluetooth.BluetoothManager
import android.content.Context
import android.util.Log

class BleEngine(context: Context) {

    private val bluetoothManager = context.getSystemService(BluetoothManager::class.java)
    private val adapter = bluetoothManager?.adapter

    private val scanner = adapter?.let { BleScanner(it) }
    private val advertiser = adapter?.let { BleAdvertiser(it) }

    fun startDay1Foundation() {
        if (adapter == null || !adapter.isEnabled) {
            Log.e(TAG, "Bluetooth adapter unavailable or disabled")
            return
        }

        scanner?.startScan()
        advertiser?.startAdvertising()
    }

    fun stopDay1Foundation() {
        scanner?.stopScan()
        advertiser?.stopAdvertising()
    }

    companion object {
        private const val TAG = "BleEngine"
    }
}
