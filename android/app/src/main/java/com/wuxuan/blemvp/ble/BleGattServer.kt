package com.wuxuan.blemvp.ble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattServer
import android.bluetooth.BluetoothGattServerCallback
import android.bluetooth.BluetoothGattService
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.util.Log

class 蓝牙Gatt服务端(
    private val 上下文: Context,
    private val on入站写入: (数据: ByteArray, 来源地址: String) -> Unit,
    private val on连接状态变化: (已连接: Boolean, 地址: String) -> Unit,
    private val on写入到达: (来源地址: String, 字节数: Int) -> Unit = { _, _ -> }
) {

    private val 蓝牙管理器 = 上下文.getSystemService(BluetoothManager::class.java)
    private var gatt服务端: BluetoothGattServer? = null

    private val 回调 = object : BluetoothGattServerCallback() {
        override fun onConnectionStateChange(设备: BluetoothDevice, 状态码: Int, 新状态: Int) {
            val 已连接 = 新状态 == BluetoothProfile.STATE_CONNECTED
            Log.d(日志标记, "Peripheral state changed: ${设备.address} 已连接=$已连接 状态码=$状态码")
            on连接状态变化(已连接, 设备.address)
        }

        override fun onCharacteristicWriteRequest(
            设备: BluetoothDevice,
            请求编号: Int,
            特征: BluetoothGattCharacteristic,
            预备写入: Boolean,
            需要响应: Boolean,
            偏移: Int,
            值: ByteArray
        ) {
            Log.d(日志标记, "Write request from ${设备.address}: uuid=${特征.uuid} bytes=${值.size} 需要响应=$需要响应")
            on写入到达(设备.address, 值.size)
            
            if (特征.uuid == 蓝牙常量.写入UUID && 值.isNotEmpty()) {
                Log.d(日志标记, "UUID matched, invoking on入站写入 with ${值.size} bytes from ${设备.address}")
                on入站写入(值, 设备.address)
            } else {
                Log.d(日志标记, "UUID mismatch or empty: uuid=${特征.uuid} expected=${蓝牙常量.写入UUID} empty=${值.isEmpty()}")
            }

            if (需要响应) {
                gatt服务端?.sendResponse(设备, 请求编号, BluetoothGatt.GATT_SUCCESS, 偏移, null)
                Log.d(日志标记, "Sent GATT response to ${设备.address}")
            }
        }
    }

    @SuppressLint("MissingPermission")
    fun 启动() {
        if (gatt服务端 != null) return

        val 服务端 = 蓝牙管理器?.openGattServer(上下文, 回调)
        if (服务端 == null) {
            Log.e(日志标记, "Failed to open GATT 服务端")
            return
        }

        val 写入特征 = BluetoothGattCharacteristic(
            蓝牙常量.写入UUID,
            BluetoothGattCharacteristic.PROPERTY_WRITE or BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE,
            BluetoothGattCharacteristic.PERMISSION_WRITE
        )

        val 通知特征 = BluetoothGattCharacteristic(
            蓝牙常量.通知UUID,
            BluetoothGattCharacteristic.PROPERTY_NOTIFY,
            BluetoothGattCharacteristic.PERMISSION_READ
        )

        val 服务 = BluetoothGattService(蓝牙常量.服务UUID, BluetoothGattService.SERVICE_TYPE_PRIMARY).apply {
            addCharacteristic(写入特征)
            addCharacteristic(通知特征)
        }

        服务端.addService(服务)
        gatt服务端 = 服务端
        Log.d(日志标记, "服务端 open")
    }

    @SuppressLint("MissingPermission")
    fun 停止() {
        try {
            gatt服务端?.clearServices()
            gatt服务端?.close()
        } catch (_: Throwable) {
            // Ignore errors when BT is already off
        }
        gatt服务端 = null
    }

    companion object {
        private const val 日志标记 = "蓝牙Gatt服务端"
    }
}
