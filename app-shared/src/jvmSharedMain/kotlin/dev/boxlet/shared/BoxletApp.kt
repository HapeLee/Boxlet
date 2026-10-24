package dev.boxlet.shared

import dev.boxlet.core.engine.EngineConfig
import dev.boxlet.core.engine.OutgoingFile
import dev.boxlet.core.engine.PendingReceive
import dev.boxlet.core.engine.ReceiveStorage
import dev.boxlet.core.engine.TransferEngine
import dev.boxlet.core.engine.TransferStatus
import dev.boxlet.core.protocol.Device
import dev.boxlet.core.protocol.DeviceType
import dev.boxlet.core.protocol.ProtocolType
import dev.boxlet.shared.repository.FavoriteDevice
import dev.boxlet.shared.repository.FavoritesRepository
import dev.boxlet.shared.repository.HistoryEntry
import dev.boxlet.shared.repository.HistoryRepository
import dev.boxlet.shared.repository.SecurityRepository
import dev.boxlet.shared.repository.SettingsRepository
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import okio.Buffer
import java.io.File
import java.util.UUID

data class PinRequest(
    val deviceAlias: String,
    val attempt: Int,
)

data class UiState(
    val ready: Boolean = false,
    val settings: Settings = Settings(),
    val devices: List<Device> = emptyList(),
    val outgoing: dev.boxlet.core.engine.OutgoingTransfer? = null,
    val incoming: dev.boxlet.core.engine.IncomingTransfer? = null,
    val share: dev.boxlet.core.engine.ShareState? = null,
    val bridge: dev.boxlet.core.engine.BridgeState? = null,
    val pendingReceive: PendingReceive? = null,
    val pinRequest: PinRequest? = null,
    val history: List<HistoryEntry> = emptyList(),
    val favorites: List<FavoriteDevice> = emptyList(),
    val serverRunning: Boolean = false,
    val error: String? = null,
    val fingerprint: String = "",
    val protocols: List<dev.boxlet.core.routing.AdapterState> = emptyList(),
) {
    val isFavorite: (String) -> Boolean = { fingerprint -> favorites.any { it.fingerprint == fingerprint } }
}

class BoxletApp(
    private val scope: CoroutineScope,
    dataDir: File,
    storage: ReceiveStorage,
    private val defaults: Settings,
    sourceResolver: dev.boxlet.core.session.bp2.Bp2SourceResolver = dev.boxlet.core.session.bp2.Bp2FileSourceResolver,
) {
    private val settingsRepository = SettingsRepository(File(dataDir, "settings.json"), scope, defaults)
    private val security = SecurityRepository(File(dataDir, "security.json")).loadOrCreate()
    private val historyRepository = HistoryRepository(File(dataDir, "history.json"), scope)
    private val favoritesRepository = FavoritesRepository(File(dataDir, "favorites.json"), scope)

    // 命令式 IPC 读取仓库当前值，避免 combine/stateIn 异步传播造成写后立即读到旧值。
    val currentSettings get() = settingsRepository.state.value
    val currentHistory get() = historyRepository.entries.value
    val currentFavorites get() = favoritesRepository.entries.value

    private val sendJournal = dev.boxlet.core.session.bp2.Bp2SendJournal(File(dataDir, "bp2-send"), security, sourceResolver)
    val engine = TransferEngine(scope, security, storage, sendJournal)

    // 发送草稿属于应用会话，切换页面不会丢失；选择内容本身不会触发网络传输。
    private val _sendSelection = MutableStateFlow<List<OutgoingFile>>(emptyList())
    val sendSelection: StateFlow<List<OutgoingFile>> = _sendSelection.asStateFlow()

    fun selectFiles(files: List<OutgoingFile>) {
        _sendSelection.update { it + files }
    }

    fun selectText(text: String) {
        if (text.isBlank()) return
        selectFiles(listOf(textFile(text)))
    }

    fun removeSelectedFile(id: String) {
        _sendSelection.update { files -> files.filterNot { it.id == id } }
    }

    fun clearSendSelection() { _sendSelection.value = emptyList() }

    private val _pinRequest = MutableStateFlow<PinRequest?>(null)
    private val _ready = MutableStateFlow(false)
    private var pinDeferred: CompletableDeferred<String?>? = null

    var onShow: ((List<String>) -> Unit)? = null

    private val baseState = combine(
        engine.state,
        settingsRepository.state,
        historyRepository.entries,
        favoritesRepository.entries,
    ) { engineState, settings, history, favorites ->
        UiState(
            settings = settings,
            devices = engineState.devices,
            outgoing = engineState.outgoing,
            incoming = engineState.incoming,
            share = engineState.share,
            bridge = engineState.bridge,
            pendingReceive = engineState.pendingReceive,
            history = history,
            favorites = favorites,
            serverRunning = engineState.serverRunning,
            error = engineState.error,
            fingerprint = security.fingerprint,
            protocols = engineState.protocols,
        )
    }

    val state: StateFlow<UiState> = combine(baseState, _pinRequest, _ready) { base, pin, ready ->
        base.copy(pinRequest = pin, ready = ready)
    }.stateIn(
        scope = scope,
        started = kotlinx.coroutines.flow.SharingStarted.Eagerly,
        initialValue = UiState(
            settings = settingsRepository.state.value,
            fingerprint = security.fingerprint,
        ),
    )

    init {
        scope.coroutineContext[kotlinx.coroutines.Job]?.invokeOnCompletion { sendJournal.close() }
        engine.installConfig(::engineConfig)
        engine.onShow = { args -> onShow?.invoke(args) }
        scope.launch(start = CoroutineStart.UNDISPATCHED) {
            engine.completions.collect { completion ->
                if (!settingsRepository.state.value.saveToHistory) return@collect
                historyRepository.add(
                    historyRepository.newEntry(
                        fileName = completion.file.fileName.substringAfterLast('/'),
                        fileType = completion.file.fileType,
                        path = completion.savedPath,
                        message = completion.message,
                        fileSize = completion.file.size,
                        senderAlias = completion.senderAlias,
                        timestamp = completion.timestamp,
                        adapterId = completion.adapterId,
                        completionId = completion.completionId,
                    ),
                )
            }
        }
        engine.start()
        _ready.value = true
    }

    private fun engineConfig(): EngineConfig {
        val settings = settingsRepository.state.value
        return EngineConfig(
            alias = settings.alias,
            deviceModel = settings.deviceModel ?: defaults.deviceModel,
            deviceType = settings.deviceType,
            port = settings.port,
            protocolType = if (settings.https) ProtocolType.HTTPS else ProtocolType.HTTP,
            fingerprint = security.fingerprint,
            download = false,
            multicastGroup = settings.multicastGroup,
            discoveryTimeoutMs = settings.discoveryTimeoutMs,
            networkWhitelist = settings.networkWhitelist,
            networkBlacklist = settings.networkBlacklist,
            quickSave = settings.quickSave,
            quickSaveFromFavorites = settings.quickSaveFromFavorites,
            receivePin = settings.receivePin?.takeIf { it.isNotBlank() },
            createChecksums = settings.createChecksums,
            verifyChecksums = settings.verifyChecksums,
            bp2Enabled = settings.bp2Enabled,
            bp2Port = settings.bp2Port,
            bp2TrustedPeers = settings.bp2TrustedPeers,
            autoAccept = { device ->
                favoritesRepository.entries.value.any {
                    it.fingerprint.equals(device.fingerprint, ignoreCase = true)
                }
            },
            showToken = settings.showToken,
        )
    }

    // ---------------------------------------------------------------------
    // Settings
    // ---------------------------------------------------------------------

    fun updateSettings(transform: (Settings) -> Settings) {
        val before = settingsRepository.state.value
        val after = settingsRepository.update(transform)
        val needsRestart = before.port != after.port || before.destination != after.destination ||
            before.bp2Enabled != after.bp2Enabled || before.bp2Port != after.bp2Port ||
            before.bp2TrustedPeers != after.bp2TrustedPeers ||
            before.https != after.https ||
            before.multicastGroup != after.multicastGroup ||
            before.networkWhitelist != after.networkWhitelist ||
            before.networkBlacklist != after.networkBlacklist
        if (needsRestart) engine.restart()
    }

    // ---------------------------------------------------------------------
    // Discovery / sending
    // ---------------------------------------------------------------------

    fun scan() = engine.scan()

    fun announce() = engine.announceNow()

    fun trustBp2Peer(fingerprint: String, trusted: Boolean) {
        val normalized = fingerprint.trim().lowercase()
        dev.boxlet.core.protocol.bp2.Bp2.requireFingerprint(normalized)
        require(!normalized.equals(security.fingerprint, true)) { "Cannot pair with this device itself" }
        updateSettings { settings -> settings.copy(bp2TrustedPeers = if (trusted)
            settings.bp2TrustedPeers + normalized else settings.bp2TrustedPeers.filterNot { it.equals(normalized, true) }.toSet()) }
    }

    fun connectBp2(address: String, fingerprint: String) {
        val endpoint = parseBp2Endpoint(address)
        engine.connectBp2(endpoint.host, endpoint.port, fingerprint.trim().lowercase())
    }

    fun sendFiles(fingerprint: String, files: List<OutgoingFile>) {
        engine.send(fingerprint, files, pinProvider = ::awaitPin)
    }

    fun sendText(fingerprint: String, text: String) {
        engine.send(fingerprint, listOf(textFile(text)), pinProvider = ::awaitPin)
    }

    private fun textFile(text: String): OutgoingFile {
        val bytes = text.encodeToByteArray()
        val id = UUID.randomUUID().toString()
        return OutgoingFile(
            id = id,
            fileName = "$id.txt",
            size = bytes.size.toLong(),
            mimeType = "text/plain",
            preview = text,
            openSource = { Buffer().write(bytes) },
        )
    }

    private suspend fun awaitPin(attempt: Int): String? {
        val alias = engine.state.value.outgoing?.peer?.alias ?: ""
        val deferred = CompletableDeferred<String?>()
        pinDeferred = deferred
        _pinRequest.value = PinRequest(alias, attempt)
        return try {
            deferred.await()
        } finally {
            _pinRequest.value = null
            pinDeferred = null
        }
    }

    fun submitPin(pin: String) {
        pinDeferred?.complete(pin)
    }

    fun cancelPin() {
        pinDeferred?.complete(null)
    }

    fun cancelSend() = engine.cancelSend()

    fun clearOutgoing() = engine.clearOutgoing()

    /**
     * 通过浏览器分享。PIN 直接复用设置里的接收 PIN：没有设置就不校验，
     * 因为分享本身就是“我要把文件给别人”的主动动作。
     */
    fun shareFiles(files: List<OutgoingFile>): dev.boxlet.core.engine.ShareState? =
        engine.startShare(files, settingsRepository.state.value.receivePin)

    fun stopShare() = engine.stopShare()

    /** 打开 SP/1 浏览器接入点，配对码默认复用接收 PIN。 */
    fun startBridge(port: Int = 0, pairCode: String? = null): dev.boxlet.core.engine.BridgeState? =
        engine.startBridge(port, pairCode ?: settingsRepository.state.value.receivePin)

    fun stopBridge() = engine.stopBridge()

    // ---------------------------------------------------------------------
    // Receiving
    // ---------------------------------------------------------------------

    fun acceptReceive(fileIds: Set<String>) = engine.acceptReceive(fileIds)

    fun acceptAllReceive() = engine.acceptAllReceive()

    fun rejectReceive() = engine.rejectReceive()

    fun cancelReceive() = engine.cancelReceive()

    fun clearIncoming() = engine.clearIncoming()

    fun clearError() = engine.clearError()

    fun ensureRestarted() = engine.restart()

    // ---------------------------------------------------------------------
    // Favorites / history
    // ---------------------------------------------------------------------

    fun toggleFavorite(device: Device) {
        val channel = device.preferredChannel ?: return
        favoritesRepository.toggle(
            FavoriteDevice(
                fingerprint = device.fingerprint,
                alias = device.alias,
                host = channel.host,
                port = channel.port,
                https = channel.https,
            ),
        )
    }

    fun removeFavorite(fingerprint: String) = favoritesRepository.remove(fingerprint)

    fun clearHistory() = historyRepository.clear()

    fun removeHistory(id: String) = historyRepository.remove(id)

    val outgoingActive: Boolean
        get() {
            val status = engine.state.value.outgoing?.status ?: return false
            return status == TransferStatus.PREPARING ||
                status == TransferStatus.WAITING_FOR_ACCEPT ||
                status == TransferStatus.TRANSFERRING ||
                status == TransferStatus.PAUSED ||
                status == TransferStatus.PIN_REQUIRED
        }
}
