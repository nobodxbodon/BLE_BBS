package com.wuxuan.blemvp.ble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.le.BluetoothLeScanner
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.os.ParcelUuid
import android.util.Log

class BleScanner(
    private val bluetoothAdapter: BluetoothAdapter,
    private val onDiscovered: (BluetoothDevice) -> Unit,
    private val onScanStarted: (mode: String) -> Unit = {},
    private val onScanError: (String) -> Unit = {}
) {

    private val seenAddresses = mutableSetOf<String>()

    private val scanner: BluetoothLeScanner?
        get() = bluetoothAdapter.bluetoothLeScanner

    private val callback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult?) {
            if (result == null) return
            val device = result.device
            val address = device.address
            // Skip addresses we have already handed to onDiscovered.
            if (seenAddresses.contains(address)) return

            val uuids = result.scanRecord?.serviceUuids.orEmpty()
            val hasTargetService = uuids.any { it.uuid == BleConstants.SERVICE_UUID }
            val marker = result.scanRecord?.getManufacturerSpecificData(BleConstants.MANUFACTURER_ID)
            val hasAppMarker = marker?.contentEquals(BleConstants.APP_MARKER) == true

            if (hasTargetService && hasAppMarker) {
                // Only lock the address in once we've confirmed it carries our app data.
                // A device whose first packet has no service UUID will be re-evaluated on
                // the next scan result rather than getting silently blacklisted.
                seenAddresses.add(address)
                Log.d(TAG, "Discovered target device: $address")
                onDiscovered(device)
                return
            }

            Log.d(TAG, "Ignoring non-target device: $address")
        }

        override fun onScanFailed(errorCode: Int) {
            Log.e(TAG, "Scan failed with code: $errorCode")
            onScanError("scan failed: code=$errorCode")
        }
    }

    @SuppressLint("MissingPermission")
    fun startScan() {
        seenAddresses.clear()

        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()

        val leScanner = scanner
        if (leScanner == null) {
            onScanError("scanner unavailable")
            return
        }

        // Stop any existing scan first
        try { leScanner.stopScan(callback) } catch (_: Throwable) {}

        try {
            leScanner.startScan(null, settings, callback)
            onScanStarted("unfiltered")
            Log.d(TAG, "BLE scan started (unfiltered)")
        } catch (t: Throwable) {
            Log.e(TAG, "Unfiltered scan failed", t)
            onScanError("scan start failed: ${t.message ?: "unknown"}")
        }
    }

    @SuppressLint("MissingPermission")
    fun stopScan() {
        scanner?.stopScan(callback)
    }

    fun forgetAddress(address: String) {
        seenAddresses.remove(address)
    }

    companion object {
        private const val TAG = "BleScanner"
    }
}
