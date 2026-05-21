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

data class 帖文(
    val id: UUID = UUID.randomUUID(),
    val 内容: String,
    val 发帖人名称: String,
    val ISO8601时间戳: String = DateTimeFormatter.ISO_INSTANT.format(Instant.now())
)

@Serializable
data class 帖文载荷(
    @SerialName("id") val id: String,
    @SerialName("text") val 内容: String,
    @SerialName("发帖人") val 发帖人: String,
    @SerialName("时间戳") val 时间戳: String
) {
    companion object {
        fun 来自帖文(message: 帖文): 帖文载荷 = 帖文载荷(
            id = message.id.toString(),
            内容 = message.内容,
            发帖人 = message.发帖人名称,
            时间戳 = message.ISO8601时间戳
        )
    }
}

sealed class 线载包 {
    data class 帖文包(val payload: 帖文载荷) : 线载包()
    data class 确认包(val 确认ID: String) : 线载包()
}

object 线载编解码 {
    private val json = Json { ignoreUnknownKeys = true }

    fun 编码(packet: 线载包): String {
        val obj: JsonObject = when (packet) {
            is 线载包.帖文包 -> buildJsonObject {
                put("kind", JsonPrimitive("message"))
                put("message", json.encodeToJsonElement(帖文载荷.serializer(), packet.payload))
            }

            is 线载包.确认包 -> buildJsonObject {
                put("kind", JsonPrimitive("ack"))
                put("ackID", JsonPrimitive(packet.确认ID))
            }
        }
        return json.encodeToString(JsonObject.serializer(), obj)
    }

    fun 解码(jsonStr: String): 线载包? {
        return try {
            val obj = json.decodeFromString(JsonObject.serializer(), jsonStr)
            when (obj["kind"]?.jsonPrimitive?.contentOrNull) {
                "message" -> {
                    val payload = obj["message"]?.let { json.decodeFromJsonElement(帖文载荷.serializer(), it) }
                    if (payload != null) 线载包.帖文包(payload) else null
                }

                "ack" -> {
                    val ackId = obj["ackID"]?.jsonPrimitive?.contentOrNull
                    if (ackId != null) 线载包.确认包(ackId) else null
                }

                else -> null
            }
        } catch (_: Throwable) {
            null
        }
    }
}
