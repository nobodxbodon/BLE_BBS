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

class BleGattServer(
    private val context: Context,
    private val onInboundWrite: (data: ByteArray, fromAddress: String) -> Unit,
    private val onConnectionStateChanged: (connected: Boolean, address: String) -> Unit
) {

    private val bluetoothManager = context.getSystemService(BluetoothManager::class.java)
    private var gattServer: BluetoothGattServer? = null

    private val callback = object : BluetoothGattServerCallback() {
        override fun onConnectionStateChange(device: BluetoothDevice, status: Int, newState: Int) {
            val connected = newState == BluetoothProfile.STATE_CONNECTED
            Log.d(TAG, "Peripheral state changed: ${device.address} connected=$connected status=$status")
            onConnectionStateChanged(connected, device.address)
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
            if (characteristic.uuid == BleConstants.WRITE_UUID && value.isNotEmpty()) {
                onInboundWrite(value, device.address)
            }

            if (responseNeeded) {
                gattServer?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, offset, null)
            }
        }
    }

    @SuppressLint("MissingPermission")
    fun start() {
        if (gattServer != null) return

        val server = bluetoothManager?.openGattServer(context, callback)
        if (server == null) {
            Log.e(TAG, "Failed to open GATT server")
            return
        }

        val writeCharacteristic = BluetoothGattCharacteristic(
            BleConstants.WRITE_UUID,
            BluetoothGattCharacteristic.PROPERTY_WRITE or BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE,
            BluetoothGattCharacteristic.PERMISSION_WRITE
        )

        val notifyCharacteristic = BluetoothGattCharacteristic(
            BleConstants.NOTIFY_UUID,
            BluetoothGattCharacteristic.PROPERTY_NOTIFY,
            BluetoothGattCharacteristic.PERMISSION_READ
        )

        val service = BluetoothGattService(BleConstants.SERVICE_UUID, BluetoothGattService.SERVICE_TYPE_PRIMARY).apply {
            addCharacteristic(writeCharacteristic)
            addCharacteristic(notifyCharacteristic)
        }

        server.addService(service)
        gattServer = server
        Log.d(TAG, "server open")
    }

    @SuppressLint("MissingPermission")
    fun stop() {
        gattServer?.clearServices()
        gattServer?.close()
        gattServer = null
    }

    companion object {
        private const val TAG = "BleGattServer"
    }
}
