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

class 蓝牙扫描器(
    private val 蓝牙适配器: BluetoothAdapter,
    private val 发现设备: (BluetoothDevice) -> Unit,
    private val 扫描已开始: (mode: String) -> Unit = {},
    private val 扫描出错: (String) -> Unit = {}
) {

    private val 已见地址 = mutableSetOf<String>()

    private val 扫描器: BluetoothLeScanner?
        get() = 蓝牙适配器.bluetoothLeScanner

    private val 回调 = object : ScanCallback() {
        override fun onScanResult(回调类型: Int, 扫描结果: ScanResult?) {
            if (扫描结果 == null) return
            val device = 扫描结果.device
            val address = device.address
            // Skip addresses we have already handed to 发现设备.
            if (已见地址.contains(address)) return

            val 服务UUID列表 = 扫描结果.scanRecord?.serviceUuids.orEmpty()
            val 含目标服务 = 服务UUID列表.any { it.uuid == 蓝牙常量.服务UUID }
            val 标记 = 扫描结果.scanRecord?.getManufacturerSpecificData(蓝牙常量.厂商ID)
            val 含应用标记 = 标记?.contentEquals(蓝牙常量.应用标记) == true

            if (含目标服务 && 含应用标记) {
                // Only lock the address in once we've confirmed it carries our app data.
                // A device whose first packet has no service UUID will be re-evaluated on
                // the next scan 扫描结果 rather than getting silently blacklisted.
                已见地址.add(address)
                Log.d(TAG, "Discovered target device: $address")
                发现设备(device)
                return
            }

            Log.d(TAG, "Ignoring non-target device: $address")
        }

        override fun onScanFailed(错误码: Int) {
            Log.e(TAG, "Scan failed with code: $错误码")
            扫描出错("scan failed: code=$错误码")
        }
    }

    @SuppressLint("MissingPermission")
    fun 开始扫描() {
        已见地址.clear()

        val 设置 = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()

        val leScanner = 扫描器
        if (leScanner == null) {
            扫描出错("扫描器 unavailable")
            return
        }

        // Stop any existing scan first
        try { leScanner.stopScan(回调) } catch (_: Throwable) {}

        try {
            leScanner.startScan(null, 设置, 回调)
            扫描已开始("unfiltered")
            Log.d(TAG, "BLE scan started (unfiltered)")
        } catch (t: Throwable) {
            Log.e(TAG, "Unfiltered scan failed", t)
            扫描出错("scan start failed: ${t.message ?: "unknown"}")
        }
    }

    @SuppressLint("MissingPermission")
    fun 停止扫描() {
        扫描器?.stopScan(回调)
    }

    fun 忘记地址(address: String) {
        已见地址.remove(address)
    }

    companion object {
        private const val TAG = "蓝牙扫描器"
    }
}
