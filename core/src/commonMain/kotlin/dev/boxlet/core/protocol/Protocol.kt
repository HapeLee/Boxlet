package dev.boxlet.core.protocol

/**
 * LocalSend v2.2 wire constants.
 *
 * The application defaults to [LOCALSEND_PORT]. The former Boxlet port [DEFAULT_PORT]
 * remains for configuration migration and internal BP/2 experiments. Announcements
 * carry the actual server port; official discovery uses [LOCALSEND_PORT].
 */
object Protocol {
    const val VERSION: String = "2.2"

    /** 旧 Boxlet 默认端口；应用默认值现为 [LOCALSEND_PORT]。 */
    const val DEFAULT_PORT: Int = 53318

    /** LocalSend 官方发现端口及当前应用默认的 HTTP(S) 监听端口。 */
    const val LOCALSEND_PORT: Int = 53317

    const val DEFAULT_MULTICAST_GROUP: String = "224.0.0.167"
    const val DEFAULT_MULTICAST_GROUP_V6: String = "ff12::fd3a:e420"
    const val API_PREFIX: String = "/api/localsend/v2"
    const val DISCOVERY_TIMEOUT_MS: Long = 500
    const val ANNOUNCE_INTERVAL_MS: Long = 8_000
    val ANNOUNCE_DELAYS_MS: LongArray = longArrayOf(100, 500, 2000)
    const val MAX_UPLOAD_ATTEMPTS: Int = 3
    const val UPLOAD_CONCURRENCY: Int = 2
    const val SMALL_FILE_UPLOAD_CONCURRENCY: Int = 4
    const val SMALL_FILE_BYTES: Long = 1024 * 1024
    /** Negotiated Boxlet extension: file bytes followed by a raw 32-byte SHA-256 digest. */
    const val STREAMING_CHECKSUM: String = "sha256-trailer-v1"
    const val MAX_PIN_ATTEMPTS: Int = 3
    const val PREPARE_DECISION_TIMEOUT_MS: Long = 60_000

    /** 设备最后一次出现在多播/注册响应后保留多久；超过即从「附近设备」移除，避免残留条目被当成在线设备。 */
    const val DEVICE_EXPIRY_MS: Long = 90_000

    /** 在线判定窗口：lastSeen 在此范围内才算「在线」。UI 与清理逻辑共用。 */
    const val DEVICE_ONLINE_WINDOW_MS: Long = 20_000
}
