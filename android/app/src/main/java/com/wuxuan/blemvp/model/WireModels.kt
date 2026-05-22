package com.wuxuan.blemvp.model

import java.time.Instant
import java.time.format.DateTimeFormatter
import java.util.UUID
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

data class 帖子(
    val 编号: UUID = UUID.randomUUID(),
    val 正文: String,
    val 发帖人: String,
    val 时间戳Iso8601: String = DateTimeFormatter.ISO_INSTANT.format(Instant.now())
)

@Serializable
data class 帖子载荷(
    @SerialName("id") val 编号: String,
    @SerialName("text") val 正文: String,
    @SerialName("发帖人") val 发帖人: String,
    @SerialName("时间戳") val 时间戳: String
) {
    companion object {
        fun 由帖子生成(帖子: 帖子): 帖子载荷 = 帖子载荷(
            编号 = 帖子.编号.toString(),
            正文 = 帖子.正文,
            发帖人 = 帖子.发帖人,
            时间戳 = 帖子.时间戳Iso8601
        )
    }
}

@Serializable
data class 猜拳载荷(
    @SerialName("id") val 编号: String,
    @SerialName("roundID") val 局编号: String,
    @SerialName("sender") val 发送方: String,
    @SerialName("event") val 事件: String,
    @SerialName("move") val 手势: String? = null,
    @SerialName("salt") val 盐值: String? = null,
    @SerialName("commit") val 提交哈希: String? = null,
    @SerialName("timestamp") val 时间戳: String = DateTimeFormatter.ISO_INSTANT.format(Instant.now())
)

sealed class 传输包 {
    data class 帖子包(val 载荷: 帖子载荷) : 传输包()
    data class 确认包(val 确认编号: String) : 传输包()
    data class 猜拳包(val 载荷: 猜拳载荷) : 传输包()
}

object 传输编解码器 {
    private val json = Json { ignoreUnknownKeys = true }

    fun 编码(传输包: 传输包): String {
        val obj: JsonObject = when (传输包) {
            is 传输包.帖子包 -> buildJsonObject {
                put("kind", JsonPrimitive("message"))
                put("message", json.encodeToJsonElement(帖子载荷.serializer(), 传输包.载荷))
            }

            is 传输包.确认包 -> buildJsonObject {
                put("kind", JsonPrimitive("ack"))
                put("ackID", JsonPrimitive(传输包.确认编号))
            }

            is 传输包.猜拳包 -> buildJsonObject {
                put("kind", JsonPrimitive("game"))
                put("game", json.encodeToJsonElement(猜拳载荷.serializer(), 传输包.载荷))
            }
        }
        return json.encodeToString(JsonObject.serializer(), obj)
    }

    fun 解码(json文本: String): 传输包? {
        return try {
            val obj = json.decodeFromString(JsonObject.serializer(), json文本)
            when (obj["kind"]?.jsonPrimitive?.contentOrNull) {
                "message" -> {
                    val 载荷 = obj["message"]?.let { json.decodeFromJsonElement(帖子载荷.serializer(), it) }
                    if (载荷 != null) 传输包.帖子包(载荷) else null
                }

                "ack" -> {
                    val 确认编号 = obj["ackID"]?.jsonPrimitive?.contentOrNull
                    if (确认编号 != null) 传输包.确认包(确认编号) else null
                }

                "game" -> {
                    val 载荷 = obj["game"]?.let { json.decodeFromJsonElement(猜拳载荷.serializer(), it) }
                    if (载荷 != null) 传输包.猜拳包(载荷) else null
                }

                else -> null
            }
        } catch (_: Throwable) {
            null
        }
    }
}
