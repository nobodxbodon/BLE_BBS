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

class 蓝牙Gatt服务器(
    private val context: Context,
    private val 收到入站写入: (data: ByteArray, fromAddress: String) -> Unit,
    private val 连接状态变化: (connected: Boolean, address: String) -> Unit,
    private val 写入到达: (fromAddress: String, byteCount: Int) -> Unit = { _, _ -> }
) {

    private val 蓝牙管理器 = context.getSystemService(BluetoothManager::class.java)
    private var Gatt服务器实例: BluetoothGattServer? = null

    private val 回调 = object : BluetoothGattServerCallback() {
        override fun onConnectionStateChange(device: BluetoothDevice, status: Int, newState: Int) {
            val connected = newState == BluetoothProfile.STATE_CONNECTED
            Log.d(TAG, "Peripheral state changed: ${device.address} connected=$connected status=$status")
            连接状态变化(connected, device.address)
        }

        override fun onCharacteristicWriteRequest(
            device: BluetoothDevice,
            requestId: Int,
            characteristic: BluetoothGattCharacteristic,
            preparedWrite: Boolean,
            responseNeeded: Boolean,
            offset: Int,
            value: ByteArray
        ) {
            Log.d(TAG, "Write request from ${device.address}: uuid=${characteristic.uuid} bytes=${value.size} responseNeeded=$responseNeeded")
            写入到达(device.address, value.size)
            
            if (characteristic.uuid == 蓝牙常量.写入UUID && value.isNotEmpty()) {
                Log.d(TAG, "UUID matched, invoking 收到入站写入 with ${value.size} bytes from ${device.address}")
                收到入站写入(value, device.address)
            } else {
                Log.d(TAG, "UUID mismatch or empty: uuid=${characteristic.uuid} expected=${蓝牙常量.写入UUID} empty=${value.isEmpty()}")
            }

            if (responseNeeded) {
                Gatt服务器实例?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, offset, null)
                Log.d(TAG, "Sent GATT response to ${device.address}")
            }
        }
    }

    @SuppressLint("MissingPermission")
    fun 启动() {
        if (Gatt服务器实例 != null) return

        val server = 蓝牙管理器?.openGattServer(context, 回调)
        if (server == null) {
            Log.e(TAG, "Failed to open GATT server")
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

        val service = BluetoothGattService(蓝牙常量.服务UUID, BluetoothGattService.SERVICE_TYPE_PRIMARY).apply {
            addCharacteristic(写入特征)
            addCharacteristic(通知特征)
        }

        server.addService(service)
        Gatt服务器实例 = server
        Log.d(TAG, "server open")
    }

    @SuppressLint("MissingPermission")
    fun 停止() {
        try {
            Gatt服务器实例?.clearServices()
            Gatt服务器实例?.close()
        } catch (_: Throwable) {
            // Ignore errors when BT is already off
        }
        Gatt服务器实例 = null
    }

    companion object {
        private const val TAG = "蓝牙Gatt服务器"
    }
}
