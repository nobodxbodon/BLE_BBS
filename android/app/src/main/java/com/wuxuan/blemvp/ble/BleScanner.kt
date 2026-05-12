package com.wuxuan.blemvp.ble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.le.BluetoothLeScanner
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.os.ParcelUuid
import android.util.Log

class BleScanner(private val bluetoothAdapter: BluetoothAdapter) {

    private val scanner: BluetoothLeScanner?
        get() = bluetoothAdapter.bluetoothLeScanner

    private val callback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult?) {
            if (result == null) return
            Log.d(TAG, "Discovered device: ${result.device.address}")
        }

        override fun onScanFailed(errorCode: Int) {
            Log.e(TAG, "Scan failed with code: $errorCode")
        }
    }

    @SuppressLint("MissingPermission")
    fun startScan() {
        val filters = listOf(
            ScanFilter.Builder()
                .setServiceUuid(ParcelUuid(BleConstants.SERVICE_UUID))
                .build()
        )

        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()

        scanner?.startScan(filters, settings, callback)
        Log.d(TAG, "BLE scan started")
    }

    @SuppressLint("MissingPermission")
    fun stopScan() {
        scanner?.stopScan(callback)
        Log.d(TAG, "BLE scan stopped")
    }

    companion object {
        private const val TAG = "BleScanner"
    }
}
