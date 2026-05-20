package com.wuxuan.blemvp.ble

import java.util.UUID

object 蓝牙常量 {
    val 服务UUID: UUID = UUID.fromString("A1B2C3D4-E5F6-7890-ABCD-EF1234567890")
    val 写入UUID: UUID = UUID.fromString("A1B2C3D4-E5F6-7890-ABCD-EF1234567891")
    val 通知UUID: UUID = UUID.fromString("A1B2C3D4-E5F6-7890-ABCD-EF1234567892")

    // App marker to avoid connecting to unrelated BLE devices in crowded environments.
    const val 厂商编号: Int = 0x02E5
    val 应用标记: ByteArray = byteArrayOf(0x42, 0x4D, 0x56, 0x50) // "BMVP"
}
