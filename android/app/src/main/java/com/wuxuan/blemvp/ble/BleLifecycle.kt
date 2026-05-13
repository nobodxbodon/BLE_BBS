package com.wuxuan.blemvp.ble

enum class BleLifecycleState {
    IDLE,
    RUNNING,   // scan + advertise both up
    CONNECTING,
    CONNECTED,
    STOPPED,
    ERROR
}

fun interface BleLifecycleListener {
    fun onStateChanged(state: BleLifecycleState, detail: String)
}
