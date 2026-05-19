package com.wuxuan.blemvp.ble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.le.AdvertiseCallback
import android.bluetooth.le.AdvertiseData
import android.bluetooth.le.AdvertiseSettings
import android.bluetooth.le.BluetoothLeAdvertiser
import android.os.ParcelUuid
import android.util.Log

class BleAdvertiser(
    private val bluetoothAdapter: BluetoothAdapter,
    private val onAdvertiseStarted: () -> Unit = {},
    private val onAdvertiseError: (String) -> Unit = {}
) {

    private val advertiser: BluetoothLeAdvertiser?
        get() = bluetoothAdapter.bluetoothLeAdvertiser

    private val callback = object : AdvertiseCallback() {
        override fun onStartSuccess(settingsInEffect: AdvertiseSettings?) {
            Log.d(TAG, "Advertising started")
            onAdvertiseStarted()
        }

        override fun onStartFailure(errorCode: Int) {
            Log.e(TAG, "Advertising failed with code: $errorCode")
            onAdvertiseError("advertise failed: code=$errorCode")
        }
    }

    @SuppressLint("MissingPermission")
    fun startAdvertising() {
        val settings = AdvertiseSettings.Builder()
            .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY)
            .setConnectable(true)
            .setTimeout(0)
            .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_MEDIUM)
            .build()

        val data = AdvertiseData.Builder()
            .setIncludeDeviceName(false)
            .addServiceUuid(ParcelUuid(BleConstants.SERVICE_UUID))
            .addManufacturerData(BleConstants.MANUFACTURER_ID, BleConstants.APP_MARKER)
            .build()

        val leAdvertiser = advertiser
        if (leAdvertiser == null) {
            onAdvertiseError("advertiser unavailable")
            return
        }
        // Stop any active session first - prevents ADVERTISE_FAILED_ALREADY_STARTED
        // when this is called as part of a periodic restart.
        try { leAdvertiser.stopAdvertising(callback) } catch (_: Throwable) {}
        leAdvertiser.startAdvertising(settings, data, callback)
    }

    @SuppressLint("MissingPermission")
    fun stopAdvertising() {
        advertiser?.stopAdvertising(callback)
        Log.d(TAG, "Advertising stopped")
    }

    companion object {
        private const val TAG = "BleAdvertiser"
    }
}
