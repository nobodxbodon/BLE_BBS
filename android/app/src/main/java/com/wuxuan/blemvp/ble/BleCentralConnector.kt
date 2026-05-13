package com.wuxuan.blemvp.ble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.os.Build
import android.util.Log

class BleCentralConnector(
    private val context: Context,
    private val onConnectionStateChanged: (connected: Boolean, address: String) -> Unit
) {

    private val activeGatts = mutableMapOf<String, BluetoothGatt>()
    private val pendingConnections = mutableSetOf<String>()

    private val gattCallback = object : BluetoothGattCallback() {
        @SuppressLint("MissingPermission")
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            val address = gatt.device.address
            pendingConnections.remove(address)

            when (newState) {
                BluetoothProfile.STATE_CONNECTED -> {
                    activeGatts[address] = gatt
                    Log.d(TAG, "Connected as central: $address")
                    onConnectionStateChanged(true, address)
                    gatt.discoverServices()
                }

                BluetoothProfile.STATE_DISCONNECTED -> {
                    Log.d(TAG, "Disconnected as central: $address (status=$status)")
                    activeGatts.remove(address)
                    onConnectionStateChanged(false, address)
                    gatt.close()
                }
            }
        }
    }

    @SuppressLint("MissingPermission")
    fun connect(device: BluetoothDevice) {
        val address = device.address
        if (activeGatts.containsKey(address) || pendingConnections.contains(address)) {
            return
        }

        pendingConnections.add(address)
        val gatt = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            device.connectGatt(context, false, gattCallback, BluetoothDevice.TRANSPORT_LE)
        } else {
            device.connectGatt(context, false, gattCallback)
        }

        if (gatt == null) {
            pendingConnections.remove(address)
            Log.e(TAG, "connectGatt returned null for $address")
        }
    }

    @SuppressLint("MissingPermission")
    fun disconnectAll() {
        pendingConnections.clear()
        val snapshot = activeGatts.values.toList()
        activeGatts.clear()

        snapshot.forEach { gatt ->
            try {
                gatt.disconnect()
                gatt.close()
            } catch (_: Throwable) {
                // Ignore disconnect close race conditions.
            }
        }
    }

    companion object {
        private const val TAG = "BleCentralConnector"
    }
}
