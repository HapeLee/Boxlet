package dev.boxlet.core.engine

import dev.boxlet.core.protocol.Device
import dev.boxlet.core.protocol.DeviceType
import dev.boxlet.core.protocol.FileDto
import dev.boxlet.core.protocol.ProtocolType
import okio.Sink
import okio.Source
import kotlin.time.Instant

/** A file selected for sending. [openSource] must return a fresh stream on every call. */
class OutgoingFile(
    val id: String,
    val fileName: String,
    val size: Long,
    val mimeType: String,
    val modified: Instant? = null,
    val preview: String? = null,
    /**
     * 本地原始位置的字符串形式（Android 为 `content://` 等 Uri，桌面为绝对路径）。
     * 只用于在本机界面上做预览（如缩略图），不参与任何传输逻辑，因此缺失时功能不受影响。
     */
    val sourceUri: String? = null,
    /** Explicit permission to rebuild this selected source after a process restart. */
    val recoverySource: dev.boxlet.core.protocol.bp2.Bp2SourceRef? = null,
    val openSource: () -> Source,
) {
    val isMessage: Boolean get() = preview != null && mimeType.startsWith("text/")
}

/** Platform provided storage for an incoming file. */
interface ReceiveDestination {
    val sink: Sink
    val displayPath: String?
    suspend fun commit()
    suspend fun abort()
}

interface ReceiveStorage {
    suspend fun createDestination(sessionId: String, file: FileDto, senderAlias: String): ReceiveDestination
    fun bp2ResumeStore(): dev.boxlet.core.session.bp2.Bp2ResumeStore? = null
}

enum class TransferStatus {
    IDLE,
    PREPARING,
    WAITING_FOR_ACCEPT,
    TRANSFERRING,
    PAUSED,
    COMPLETED,
    FAILED,
    DECLINED,
    BUSY,
    PIN_REQUIRED,
    TOO_MANY_ATTEMPTS,
    CANCELLED,
}

enum class FileTransferStatus {
    QUEUED,
    TRANSFERRING,
    COMPLETED,
    FAILED,
    SKIPPED,
}

data class TransferFileState(
    val id: String,
    val fileName: String,
    val size: Long,
    val mimeType: String,
    val status: FileTransferStatus = FileTransferStatus.QUEUED,
    val bytesTransferred: Long = 0L,
    val error: String? = null,
    val isMessage: Boolean = false,
    val savedPath: String? = null,
    val restartRequired: Boolean = false,
)

data class OutgoingTransfer(
    val sessionId: String,
    val peer: Device,
    val status: TransferStatus = TransferStatus.PREPARING,
    val files: List<TransferFileState> = emptyList(),
    val startedAt: Long = System.currentTimeMillis(),
    val error: String? = null,
    val adapterId: String = "localsend-v2",
) {
    val totalBytes: Long get() = files.sumOf { it.size }
    val transferredBytes: Long get() = files.sumOf { it.bytesTransferred }
    val progress: Float
        get() {
            val total = totalBytes
            if (total <= 0) return if (status == TransferStatus.COMPLETED) 1f else 0f
            return (transferredBytes.toDouble() / total).toFloat().coerceIn(0f, 1f)
        }
}

data class IncomingTransfer(
    val sessionId: String,
    val peer: Device,
    val status: TransferStatus = TransferStatus.TRANSFERRING,
    val files: List<TransferFileState> = emptyList(),
    val startedAt: Long = System.currentTimeMillis(),
    val error: String? = null,
    val adapterId: String = "localsend-v2",
) {
    val totalBytes: Long get() = files.sumOf { it.size }
    val transferredBytes: Long get() = files.sumOf { it.bytesTransferred }
    val progress: Float
        get() {
            val total = totalBytes
            if (total <= 0) return if (status == TransferStatus.COMPLETED) 1f else 0f
            return (transferredBytes.toDouble() / total).toFloat().coerceIn(0f, 1f)
        }
}

data class PendingReceive(
    val sessionId: String,
    val peer: Device,
    val files: List<FileDto>,
    val isMessage: Boolean,
    val message: String?,
    val createdAt: Long = System.currentTimeMillis(),
)

/** Active browser sharing session (protocol Download API). */
data class ShareState(
    val url: String,
    val sessionId: String,
    val fileCount: Int,
    val totalBytes: Long,
    val pinRequired: Boolean,
    val startedAt: Long = System.currentTimeMillis(),
)

/** SP/1 的浏览器接入点。 */
data class BridgeState(
    val port: Int,
    val pairCodeRequired: Boolean,
    /** 可直接填进 Web 页面的地址，形如 `ws://192.168.1.5:53321/`。 */
    val url: String,
    val connectedPeers: Int = 0,
)

data class EngineState(
    val devices: List<Device> = emptyList(),
    val bridge: BridgeState? = null,
    val pendingReceive: PendingReceive? = null,
    val outgoing: OutgoingTransfer? = null,
    val incoming: IncomingTransfer? = null,
    val share: ShareState? = null,
    val serverRunning: Boolean = false,
    val error: String? = null,
    val protocols: List<dev.boxlet.core.routing.AdapterState> = emptyList(),
)

data class ReceiveCompletion(
    val sessionId: String,
    val senderAlias: String,
    val file: FileDto,
    val savedPath: String?,
    val message: String?,
    val timestamp: Long = System.currentTimeMillis(),
    val adapterId: String = "localsend-v2",
    val completionId: String? = null,
)

data class EngineConfig(
    val alias: String,
    val deviceModel: String?,
    val deviceType: DeviceType = DeviceType.DESKTOP,
    val port: Int = dev.boxlet.core.protocol.Protocol.DEFAULT_PORT,
    val protocolType: ProtocolType = ProtocolType.HTTPS,
    val fingerprint: String,
    val download: Boolean = false,
    val multicastGroup: String = dev.boxlet.core.protocol.Protocol.DEFAULT_MULTICAST_GROUP,
    val discoveryTimeoutMs: Long = dev.boxlet.core.protocol.Protocol.DISCOVERY_TIMEOUT_MS,
    val networkWhitelist: List<String> = emptyList(),
    val networkBlacklist: List<String> = emptyList(),
    val quickSave: Boolean = false,
    val quickSaveFromFavorites: Boolean = false,
    val receivePin: String? = null,
    /** Standard LocalSend prehash; false sends without the optional file SHA-256. BP/2 is independent. */
    val createChecksums: Boolean = true,
    val verifyChecksums: Boolean = true,
    val autoAccept: (Device) -> Boolean = { false },
    val showToken: String = "",
    val bp2Enabled: Boolean = false,
    val bp2Port: Int = dev.boxlet.core.protocol.bp2.Bp2.PORT,
    /** Retained only for legacy extension fixtures/benchmarks; never enabled by the application. */
    val legacyStreamingChecksumEnabled: Boolean = false,
) {
    fun toRegisterRequest(): dev.boxlet.core.protocol.RegisterRequest =
        dev.boxlet.core.protocol.RegisterRequest(
            alias = alias,
            deviceModel = deviceModel,
            deviceType = deviceType,
            fingerprint = fingerprint,
            port = port,
            protocol = protocolType,
            download = download,
        )

    fun toMulticastMessage(): dev.boxlet.core.protocol.MulticastMessage =
        dev.boxlet.core.protocol.MulticastMessage(
            alias = alias,
            deviceModel = deviceModel,
            deviceType = deviceType,
            fingerprint = fingerprint,
            port = port,
            protocol = protocolType,
            download = download,
        )
}

typealias PinProvider = suspend (attempt: Int) -> String?
