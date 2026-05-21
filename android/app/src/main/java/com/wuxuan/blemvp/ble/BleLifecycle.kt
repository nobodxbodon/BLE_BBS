package com.wuxuan.blemvp.ble

enum class 蓝牙生命周期状态 {
    空闲,
    运行中,   // scan + advertise both up
    连接中,
    已连接,
    已停止,
    错误
}

fun interface 蓝牙生命周期监听器 {
    fun onStateChanged(state: 蓝牙生命周期状态, detail: String)
}
