package com.wuxuan.blemvp.model

import java.util.UUID

enum class 猜拳手势(val wireValue: String) {
    石头("rock"),
    剪刀("scissors"),
    布("paper");

    companion object {
        fun fromWire(value: String?): 猜拳手势? = entries.firstOrNull { it.wireValue == value }
    }
}

enum class 猜拳结果 {
    本方胜,
    对方胜,
    平局
}

enum class 猜拳日志 {
    本方已出,
    对方已出,
    本方已公开,
    对方已公开,
    对方重新开始,
    校验失败,
    重新开始
}

data class 猜拳界面状态(
    val 局编号: String = UUID.randomUUID().toString(),
    val 本方选择: 猜拳手势? = null,
    val 对方已出: Boolean = false,
    val 对方选择: 猜拳手势? = null,
    val 结果: 猜拳结果? = null,
    val 日志: List<猜拳日志> = emptyList()
)

fun 判断猜拳结果(本方: 猜拳手势, 对方: 猜拳手势): 猜拳结果 {
    if (本方 == 对方) return 猜拳结果.平局
    return when (本方) {
        猜拳手势.石头 -> if (对方 == 猜拳手势.剪刀) 猜拳结果.本方胜 else 猜拳结果.对方胜
        猜拳手势.剪刀 -> if (对方 == 猜拳手势.布) 猜拳结果.本方胜 else 猜拳结果.对方胜
        猜拳手势.布 -> if (对方 == 猜拳手势.石头) 猜拳结果.本方胜 else 猜拳结果.对方胜
    }
}
