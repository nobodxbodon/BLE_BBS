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
    private val on发现设备: (BluetoothDevice) -> Unit,
    private val on扫描已启动: (mode: String) -> Unit = {},
    private val on扫描错误: (String) -> Unit = {}
) {

    private val 已见地址 = mutableSetOf<String>()

    private val 扫描器: BluetoothLeScanner?
        get() = 蓝牙适配器.bluetoothLeScanner

    private val 回调 = object : ScanCallback() {
        override fun onScanResult(回调类型: Int, 扫描结果: ScanResult?) {
            if (扫描结果 == null) return
            val 设备 = 扫描结果.device
            val 地址 = 设备.address
            // Skip 地址列表 we have already handed to on发现设备.
            if (已见地址.contains(地址)) return

            val 识别码列表 = 扫描结果.scanRecord?.serviceUuids.orEmpty()
            val 有目标服务 = 识别码列表.any { it.uuid == 蓝牙常量.服务UUID }
            val 标记 = 扫描结果.scanRecord?.getManufacturerSpecificData(蓝牙常量.厂商编号)
            val 有应用标记 = 标记?.contentEquals(蓝牙常量.应用标记) == true

            if (有目标服务 && 有应用标记) {
                // Only lock the 地址 in once we've confirmed it carries our app data.
                // A 设备 whose first 传输包 has no service UUID will be re-evaluated on
                // the next scan 扫描结果 rather than getting silently blacklisted.
                已见地址.add(地址)
                Log.d(日志标记, "Discovered target 设备: $地址")
                on发现设备(设备)
                return
            }

            Log.d(日志标记, "Ignoring non-target 设备: $地址")
        }

        override fun onScanFailed(错误码: Int) {
            Log.e(日志标记, "Scan failed with code: $错误码")
            on扫描错误("scan failed: code=$错误码")
        }
    }

    @SuppressLint("MissingPermission")
    fun 开始扫描() {
        已见地址.clear()

        val 扫描设置 = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()

        val 低功耗扫描器 = 扫描器
        if (低功耗扫描器 == null) {
            on扫描错误("扫描器 unavailable")
            return
        }

        // Stop any existing scan first
        try { 低功耗扫描器.stopScan(回调) } catch (_: Throwable) {}

        try {
            低功耗扫描器.startScan(null, 扫描设置, 回调)
            on扫描已启动("unfiltered")
            Log.d(日志标记, "BLE scan started (unfiltered)")
        } catch (t: Throwable) {
            Log.e(日志标记, "Unfiltered scan failed", t)
            on扫描错误("scan 启动 failed: ${t.message ?: "unknown"}")
        }
    }

    @SuppressLint("MissingPermission")
    fun 停止扫描() {
        扫描器?.stopScan(回调)
    }

    fun 忘记地址(地址: String) {
        已见地址.remove(地址)
    }

    companion object {
        private const val 日志标记 = "蓝牙扫描器"
    }
}
