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
    private val context: Context,
    private val onInboundWrite: (data: ByteArray, fromAddress: String) -> Unit,
    private val on连接状态变化: (connected: Boolean, address: String) -> Unit,
    private val onWriteArrived: (fromAddress: String, byteCount: Int) -> Unit = { _, _ -> }
) {

    private val bluetoothManager = context.getSystemService(BluetoothManager::class.java)
    private var gatt服务端: BluetoothGattServer? = null

    private val callback = object : BluetoothGattServerCallback() {
        override fun onConnectionStateChange(device: BluetoothDevice, status: Int, newState: Int) {
            val connected = newState == BluetoothProfile.STATE_CONNECTED
            Log.d(TAG, "Peripheral state changed: ${device.address} connected=$connected status=$status")
            on连接状态变化(connected, device.address)
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
            onWriteArrived(device.address, value.size)
            
            if (characteristic.uuid == 蓝牙常量.写入UUID && value.isNotEmpty()) {
                Log.d(TAG, "UUID matched, invoking onInboundWrite with ${value.size} bytes from ${device.address}")
                onInboundWrite(value, device.address)
            } else {
                Log.d(TAG, "UUID mismatch or empty: uuid=${characteristic.uuid} expected=${蓝牙常量.写入UUID} empty=${value.isEmpty()}")
            }

            if (responseNeeded) {
                gatt服务端?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, offset, null)
                Log.d(TAG, "Sent GATT response to ${device.address}")
            }
        }
    }

    @SuppressLint("MissingPermission")
    fun 启动() {
        if (gatt服务端 != null) return

        val server = bluetoothManager?.openGattServer(context, callback)
        if (server == null) {
            Log.e(TAG, "Failed to open GATT server")
            return
        }

        val writeCharacteristic = BluetoothGattCharacteristic(
            蓝牙常量.写入UUID,
            BluetoothGattCharacteristic.PROPERTY_WRITE or BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE,
            BluetoothGattCharacteristic.PERMISSION_WRITE
        )

        val notifyCharacteristic = BluetoothGattCharacteristic(
            蓝牙常量.通知UUID,
            BluetoothGattCharacteristic.PROPERTY_NOTIFY,
            BluetoothGattCharacteristic.PERMISSION_READ
        )

        val service = BluetoothGattService(蓝牙常量.服务UUID, BluetoothGattService.SERVICE_TYPE_PRIMARY).apply {
            addCharacteristic(writeCharacteristic)
            addCharacteristic(notifyCharacteristic)
        }

        server.addService(service)
        gatt服务端 = server
        Log.d(TAG, "server open")
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
        private const val TAG = "蓝牙Gatt服务端"
    }
}
