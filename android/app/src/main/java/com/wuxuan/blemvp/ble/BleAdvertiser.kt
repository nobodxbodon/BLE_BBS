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
    private val 蓝牙适配器: BluetoothAdapter,
    private val on广播已启动: () -> Unit = {},
    private val on广播错误: (String) -> Unit = {}
) {

    private val 广播器: BluetoothLeAdvertiser?
        get() = 蓝牙适配器.bluetoothLeAdvertiser

    private val 回调 = object : AdvertiseCallback() {
        override fun onStartSuccess(生效设置: AdvertiseSettings?) {
            Log.d(日志标记, "Advertising started")
            on广播已启动()
        }

        override fun onStartFailure(错误码: Int) {
            Log.e(日志标记, "Advertising failed with code: $错误码")
            on广播错误("advertise failed: code=$错误码")
        }
    }

    @SuppressLint("MissingPermission")
    fun 开始广播() {
        val 广播设置 = AdvertiseSettings.Builder()
            .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY)
            .setConnectable(true)
            .setTimeout(0)
            .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_MEDIUM)
            .build()

        val 广播数据 = AdvertiseData.Builder()
            .setIncludeDeviceName(false)
            .addServiceUuid(ParcelUuid(蓝牙常量.服务UUID))
            .addManufacturerData(蓝牙常量.厂商编号, 蓝牙常量.应用标记)
            .build()

        val 低功耗广播器 = 广播器
        if (低功耗广播器 == null) {
            on广播错误("广播器 unavailable")
            return
        }
        // Stop any active session first - prevents ADVERTISE_FAILED_ALREADY_STARTED
        // when this is called as part of a periodic restart.
        try { 低功耗广播器.stopAdvertising(回调) } catch (_: Throwable) {}
        低功耗广播器.startAdvertising(广播设置, 广播数据, 回调)
    }

    @SuppressLint("MissingPermission")
    fun 停止广播() {
        广播器?.stopAdvertising(回调)
        Log.d(日志标记, "Advertising stopped")
    }

    companion object {
        private const val 日志标记 = "蓝牙广播器"
    }
}
