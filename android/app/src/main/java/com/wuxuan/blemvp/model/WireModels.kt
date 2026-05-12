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
import kotlinx.serialization.json.put

data class Message(
    val id: UUID = UUID.randomUUID(),
    val text: String,
    val senderName: String,
    val timestampIso8601: String = DateTimeFormatter.ISO_INSTANT.format(Instant.now())
)

@Serializable
data class MessagePayload(
    @SerialName("id") val id: String,
    @SerialName("text") val text: String,
    @SerialName("发帖人") val sender: String,
    @SerialName("时间戳") val timestamp: String
) {
    companion object {
        fun fromMessage(message: Message): MessagePayload = MessagePayload(
            id = message.id.toString(),
            text = message.text,
            sender = message.senderName,
            timestamp = message.timestampIso8601
        )
    }
}

sealed class WirePacket {
    data class PacketMessage(val payload: MessagePayload) : WirePacket()
    data class Ack(val ackId: String) : WirePacket()
}

object WireCodec {
    private val json = Json { ignoreUnknownKeys = true }

    fun encode(packet: WirePacket): String {
        val obj: JsonObject = when (packet) {
            is WirePacket.PacketMessage -> buildJsonObject {
                put("kind", JsonPrimitive("message"))
                put("message", json.encodeToJsonElement(MessagePayload.serializer(), packet.payload))
            }

            is WirePacket.Ack -> buildJsonObject {
                put("kind", JsonPrimitive("ack"))
                put("ackID", JsonPrimitive(packet.ackId))
            }
        }
        return json.encodeToString(JsonObject.serializer(), obj)
    }
}
