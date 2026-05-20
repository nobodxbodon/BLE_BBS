package com.wuxuan.blemvp.ble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.le.AdvertiseCallback
import android.bluetooth.le.AdvertiseData
import android.bluetooth.le.AdvertiseSettings
import android.bluetooth.le.BluetoothLeAdvertiser
import android.os.ParcelUuid
import android.util.Log

class 蓝牙广播器(
    private val bluetoothAdapter: BluetoothAdapter,
    private val onAdvertiseStarted: () -> Unit = {},
    private val onAdvertiseError: (String) -> Unit = {}
) {

    private val 广播器: BluetoothLeAdvertiser?
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
    fun 开始广播() {
        val settings = AdvertiseSettings.Builder()
            .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY)
            .setConnectable(true)
            .setTimeout(0)
            .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_MEDIUM)
            .build()

        val data = AdvertiseData.Builder()
            .setIncludeDeviceName(false)
            .addServiceUuid(ParcelUuid(蓝牙常量.服务UUID))
            .addManufacturerData(蓝牙常量.厂商编号, 蓝牙常量.应用标记)
            .build()

        val leAdvertiser = 广播器
        if (leAdvertiser == null) {
            onAdvertiseError("广播器 unavailable")
            return
        }
        // Stop any active session first - prevents ADVERTISE_FAILED_ALREADY_STARTED
        // when this is called as part of a periodic restart.
        try { leAdvertiser.stopAdvertising(callback) } catch (_: Throwable) {}
        leAdvertiser.startAdvertising(settings, data, callback)
    }

    @SuppressLint("MissingPermission")
    fun 停止广播() {
        广播器?.stopAdvertising(callback)
        Log.d(TAG, "Advertising stopped")
    }

    companion object {
        private const val TAG = "蓝牙广播器"
    }
}
