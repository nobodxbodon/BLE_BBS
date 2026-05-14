package com.wuxuan.blemvp.ble

import java.util.UUID

object BleConstants {
    val SERVICE_UUID: UUID = UUID.fromString("A1B2C3D4-E5F6-7890-ABCD-EF1234567890")
    val WRITE_UUID: UUID = UUID.fromString("A1B2C3D4-E5F6-7890-ABCD-EF1234567891")
    val NOTIFY_UUID: UUID = UUID.fromString("A1B2C3D4-E5F6-7890-ABCD-EF1234567892")

    // App marker to avoid connecting to unrelated BLE devices in crowded environments.
    const val MANUFACTURER_ID: Int = 0x02E5
    val APP_MARKER: ByteArray = byteArrayOf(0x42, 0x4D, 0x56, 0x50) // "BMVP"
}
