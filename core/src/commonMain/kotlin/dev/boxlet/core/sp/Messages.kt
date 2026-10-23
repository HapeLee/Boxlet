package dev.boxlet.core.sp

import kotlinx.serialization.Serializable

/**
 * SP/1 控制消息。数据面（DATA 帧）是裸字节，不经过 JSON。
 * 见 `docs/adr/0005-boxlet-protocol.md`。
 */
@Serializable
data class Hello(
    val alias: String,
    val deviceType: String = "web",
    val version: Int = 1,
    /** 设置里配置的配对码；bridge 开启时必填。 */
    val pairCode: String? = null,
)

@Serializable
data class PeerDevice(
    val id: String,
    val alias: String,
    val deviceType: String = "desktop",
    val host: String,
    val port: Int,
    val online: Boolean = true,
)

@Serializable
data class DeviceList(val devices: List<PeerDevice>)

@Serializable
data class DeviceEvent(val device: PeerDevice, val removed: Boolean = false)

@Serializable
data class OfferFile(
    val id: String,
    val name: String,
    val size: Long,
    val mime: String = "application/octet-stream",
    /** 断点续传起点，接收端可在此基础上继续。 */
    val offset: Long = 0L,
)

@Serializable
data class Offer(
    val files: List<OfferFile>,
    val pin: String? = null,
    /** 目标节点 id：为空表示发给 bridge 自己。 */
    val peerId: String? = null,
)

@Serializable
data class Accept(
    val accepted: List<String>,
    val resumeFrom: Map<String, Long> = emptyMap(),
    val token: String? = null,
)

@Serializable
data class Reject(val reason: String)

@Serializable
data class Ack(val streamId: Int, val bytes: Long)

@Serializable
data class Done(val streamId: Int, val bytes: Long, val fileId: String? = null)

@Serializable
data class Cancel(val streamId: Int? = null, val reason: String)

/** 之后的 binary 消息都归属于 [streamId]。 */
@Serializable
data class DataStart(val streamId: Int, val fileId: String)

@Serializable
data class DataEnd(val streamId: Int, val bytes: Long)
