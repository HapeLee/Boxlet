package dev.boxlet.core.protocol

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** JSON used for every wire message and persistence file. */
val BoxletJson: Json = Json {
    ignoreUnknownKeys = true
    encodeDefaults = true
    explicitNulls = false
    isLenient = true
    coerceInputValues = true
    prettyPrint = false
}

@Serializable
enum class DeviceType {
    @SerialName("mobile")
    MOBILE,

    @SerialName("desktop")
    DESKTOP,

    @SerialName("web")
    WEB,

    @SerialName("headless")
    HEADLESS,

    @SerialName("server")
    SERVER,
}

@Serializable
enum class ProtocolType {
    @SerialName("http")
    HTTP,

    @SerialName("https")
    HTTPS,
}

/** UDP multicast announce payload. */
@Serializable
data class MulticastMessage(
    val alias: String,
    val version: String = Protocol.VERSION,
    val deviceModel: String? = null,
    val deviceType: DeviceType = DeviceType.DESKTOP,
    val fingerprint: String,
    val port: Int = Protocol.DEFAULT_PORT,
    val protocol: ProtocolType = ProtocolType.HTTPS,
    val download: Boolean = false,
    /** Legacy v2 field, ignored by peers that do not know it. */
    val announce: Boolean = true,
)

/** Body of POST /register and the `info` field of prepare-upload. */
@Serializable
data class RegisterRequest(
    val alias: String,
    val version: String = Protocol.VERSION,
    val deviceModel: String? = null,
    val deviceType: DeviceType? = null,
    val fingerprint: String,
    val port: Int = Protocol.DEFAULT_PORT,
    val protocol: ProtocolType = ProtocolType.HTTPS,
    val download: Boolean = false,
    val sendeeCapabilities: List<String>? = null,
)

fun MulticastMessage.toDevice(channel: DeviceChannel): Device = Device(
    fingerprint = fingerprint,
    alias = alias,
    version = version,
    deviceModel = deviceModel,
    deviceType = deviceType,
    download = download,
    channels = listOf(channel),
    lastSeen = nowMillis(),
)

/** Response of register/info. Must not contain port or protocol. */
@Serializable
data class InfoResponse(
    val alias: String,
    val version: String = Protocol.VERSION,
    val deviceModel: String? = null,
    val deviceType: DeviceType? = null,
    val fingerprint: String,
    val download: Boolean = false,
    val sendeeCapabilities: List<String>? = null,
)

@Serializable
data class FileMetadata(
    val modified: String? = null,
    val accessed: String? = null,
)

@Serializable
data class FileDto(
    val id: String,
    val fileName: String,
    val size: Long,
    val fileType: String,
    val sha256: String? = null,
    val preview: String? = null,
    val metadata: FileMetadata? = null,
    /** Only sent after the receiver advertises the corresponding capability. */
    val sendeeChecksum: String? = null,
)

@Serializable
data class PrepareUploadRequest(
    val info: RegisterRequest,
    val files: Map<String, FileDto>,
)

@Serializable
data class PrepareUploadResponse(
    val sessionId: String,
    val files: Map<String, String>,
)

@Serializable
data class PrepareDownloadRequest(
    val info: RegisterRequest,
    val files: Map<String, FileDto>,
)

@Serializable
data class PrepareDownloadResponse(
    val info: InfoResponse,
    val sessionId: String,
    val files: Map<String, FileDto>,
)

@Serializable
data class ApiError(val message: String)

/** A reachable transport endpoint of a peer. */
@Serializable
data class DeviceChannel(
    val host: String,
    val port: Int = Protocol.DEFAULT_PORT,
    val https: Boolean = true,
    val adapterId: String = "localsend-v2",
    val identityVerified: Boolean = false,
) {
    val url: String
        get() {
            val scheme = if (https) "https" else "http"
            val safeHost = if (host.contains(':') && !host.startsWith("[")) "[$host]" else host
            return "$scheme://$safeHost:$port"
        }
}

/** A peer known to this device. */
@Serializable
data class Device(
    val fingerprint: String,
    val alias: String,
    val version: String = Protocol.VERSION,
    val deviceModel: String? = null,
    val deviceType: DeviceType = DeviceType.DESKTOP,
    val download: Boolean = false,
    val channels: List<DeviceChannel> = emptyList(),
    val lastSeen: Long = 0L,
) {
    fun withChannel(channel: DeviceChannel): Device {
        val merged = (channels + channel).distinctBy { listOf(it.adapterId, it.host, it.port, it.https) }
        return copy(channels = merged, lastSeen = nowMillis())
    }

    val preferredChannel: DeviceChannel?
        get() = channels.firstOrNull { it.adapterId == "boxlet-bp2" } ?: channels.firstOrNull { it.https } ?: channels.firstOrNull()
}

internal fun RegisterRequest.toDevice(channel: DeviceChannel): Device = Device(
    fingerprint = fingerprint,
    alias = alias,
    version = version,
    deviceModel = deviceModel,
    deviceType = deviceType ?: DeviceType.DESKTOP,
    download = download,
    channels = listOf(channel),
    lastSeen = nowMillis(),
)

internal fun InfoResponse.toDevice(channel: DeviceChannel): Device = Device(
    fingerprint = fingerprint,
    alias = alias,
    version = version,
    deviceModel = deviceModel,
    deviceType = deviceType ?: DeviceType.DESKTOP,
    download = download,
    channels = listOf(channel),
    lastSeen = nowMillis(),
)
