package dev.boxlet.core.sp

import dev.boxlet.core.protocol.BoxletJson
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * SP/1 控制面：WS text 消息，JSON。
 *
 * 数据面不在这里——binary 消息是裸 payload，由 `data-start` 指定归属，见 ADR 0005。
 */
object Sp {
    const val CHUNK = 1024 * 1024

    const val HELLO = "hello"
    const val DEVICES = "devices"
    const val DEVICE_EVENT = "device-event"
    const val OFFER = "offer"
    const val ACCEPT = "accept"
    const val REJECT = "reject"
    const val DATA_START = "data-start"
    const val DATA_END = "data-end"
    const val ACK = "ack"
    const val DONE = "done"
    const val CANCEL = "cancel"
    const val PING = "ping"
    const val PONG = "pong"
}

object SpControl {

    /** 拼出 `{"type":"…", …}`，避免给每个 DTO 都加一个 type 字段。 */
    fun encode(type: String, body: String): String {
        val trimmed = body.trim()
        if (trimmed.isEmpty() || trimmed == "{}") return """{"type":"$type"}"""
        require(trimmed.startsWith("{")) { "control body must be an object: $trimmed" }
        return """{"type":"$type",""" + trimmed.substring(1)
    }

    fun typeOf(text: String): String? = runCatching {
        BoxletJson.parseToJsonElement(text).jsonObject["type"]?.jsonPrimitive?.content
    }.getOrNull()
}
