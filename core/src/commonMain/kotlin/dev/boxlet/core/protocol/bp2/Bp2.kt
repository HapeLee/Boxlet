package dev.boxlet.core.protocol.bp2

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

object Bp2 {
    const val NAME = "Boxlet Protocol"
    const val MAGIC = "BXP2"
    const val ADAPTER_ID = "boxlet-bp2"
    const val DISCOVERY_PROTOCOL = "boxlet"
    const val ALPN = "boxlet/2"
    const val WSS_SUBPROTOCOL = "boxlet.v2"
    const val WSS_PATH = "/boxlet/v2"
    const val MAJOR = 2
    const val MINOR = 0
    const val PORT = 53318
    const val WSS_PORT = 53321
    const val MULTICAST_V4 = "239.255.83.68"
    const val MULTICAST_V6 = "ff12::5344"
    const val HEADER_BYTES = 32
    const val CHUNK_BYTES = 1048576
    const val HASH_BYTES = 32
    const val CONTROL_BYTES = 262144
    const val BATCH_BYTES = 1048576
    const val BATCH_RECORDS = 8
    const val BATCH_FILE_BYTES = 65536
    const val WINDOW_BYTES = 8388608L
    const val MAX_STREAMS = 8
    const val MANIFEST_PAGE_ENTRIES = 256
    val json = Json { ignoreUnknownKeys = true; explicitNulls = false; encodeDefaults = true }

    fun decimal(value: String): Long {
        require(value == "0" || (value.isNotEmpty() && value[0] in '1'..'9' && value.all { it in '0'..'9' })) {
            "Invalid unsigned decimal"
        }
        return value.toLongOrNull()?.also { require(it >= 0) } ?: error("Decimal exceeds 2^63-1")
    }

    fun requireId(value: String) {
        require(value.length == 32 && value.all { it in '0'..'9' || it in 'a'..'f' }) { "Invalid 128-bit ID" }
    }

    fun requireFingerprint(value: String) {
        require(value.length == 64 && value.all { it in '0'..'9' || it in 'a'..'f' }) { "Invalid certificate fingerprint" }
    }
}

@Serializable
data class Bp2Limits(
    val maxActiveStreams: Int = Bp2.MAX_STREAMS,
    val receiveWindow: String = Bp2.WINDOW_BYTES.toString(),
    val maxManifestEntries: Int = 100000,
) {
    fun validate() {
        require(maxActiveStreams in 1..Bp2.MAX_STREAMS)
        require(Bp2.decimal(receiveWindow) in 2L * Bp2.CHUNK_BYTES..32L * Bp2.CHUNK_BYTES)
        require(maxManifestEntries in 1..100000)
    }
}

@Serializable
data class Bp2Hello(
    val type: String = "hello",
    val requestId: String,
    val major: Int = Bp2.MAJOR,
    val minor: Int = Bp2.MINOR,
    val deviceId: String,
    val alias: String,
    val deviceType: String,
    val features: List<String>,
    val limits: Bp2Limits = Bp2Limits(),
    val listenPort: Int? = null,
) {
    fun validateNative(certificateFingerprint: String) {
        require(type == "hello" && major == Bp2.MAJOR && minor >= 0)
        Bp2.requireId(requestId)
        Bp2.requireFingerprint(deviceId)
        require(deviceId == certificateFingerprint.lowercase()) { "HELLO identity differs from TLS certificate" }
        require(alias.encodeToByteArray().size in 1..256)
        require(deviceType in listOf("mobile", "desktop", "headless", "server"))
        require(features.size <= 32 && features.distinct().size == features.size)
        limits.validate()
        require(listenPort == null || listenPort in 1..65535)
    }
}

@Serializable
data class Bp2HelloAck(
    val type: String = "hello-ack",
    val replyTo: String,
    val connectionId: String,
    val major: Int = Bp2.MAJOR,
    val minor: Int = Bp2.MINOR,
    val deviceId: String,
    val alias: String,
    val deviceType: String,
    val features: List<String>,
    val limits: Bp2Limits = Bp2Limits(),
    val listenPort: Int? = null,
) {
    fun asHello() = Bp2Hello(requestId = replyTo, major = major, minor = minor, deviceId = deviceId,
        alias = alias, deviceType = deviceType, features = features, limits = limits, listenPort = listenPort)
}

@Serializable
data class Bp2Announcement(
    val protocol: String = Bp2.DISCOVERY_PROTOCOL,
    val major: Int = Bp2.MAJOR,
    val deviceId: String,
    val alias: String,
    val deviceType: String,
    val tlsPort: Int = Bp2.PORT,
    val wssPort: Int? = null,
) {
    fun validate() {
        require(protocol == Bp2.DISCOVERY_PROTOCOL && major == Bp2.MAJOR)
        Bp2.requireFingerprint(deviceId)
        require(alias.encodeToByteArray().size in 1..256)
        require(tlsPort in 1..65535 && (wssPort == null || wssPort in 1..65535))
    }
}
