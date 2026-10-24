package dev.boxlet.core.engine

import dev.boxlet.core.crypto.SecurityContext
import dev.boxlet.core.net.ApiClient
import dev.boxlet.core.net.ApiResult
import dev.boxlet.core.net.DiscoveryConfig
import dev.boxlet.core.net.DiscoveryService
import dev.boxlet.core.net.DownloadOutcome
import dev.boxlet.core.net.LocalSendApiServer
import dev.boxlet.core.net.LocalSendHandler
import dev.boxlet.core.net.LocalSendHttpServer
import dev.boxlet.core.net.NetworkInterfaces
import dev.boxlet.core.net.PrepareDownloadOutcome
import dev.boxlet.core.net.PrepareUploadOutcome
import dev.boxlet.core.net.ShareServer
import dev.boxlet.core.net.SharedEntry
import dev.boxlet.core.sp.BridgeServer
import dev.boxlet.core.sp.PeerDevice
import dev.boxlet.core.net.UploadOutcome
import dev.boxlet.core.protocol.Device
import dev.boxlet.core.protocol.DeviceChannel
import dev.boxlet.core.protocol.FileDto
import dev.boxlet.core.protocol.FileMetadata
import dev.boxlet.core.protocol.InfoResponse
import dev.boxlet.core.protocol.MulticastMessage
import dev.boxlet.core.protocol.PrepareUploadRequest
import dev.boxlet.core.protocol.Protocol
import dev.boxlet.core.protocol.ProtocolType
import dev.boxlet.core.protocol.RegisterRequest
import dev.boxlet.core.protocol.BoxletJson
import dev.boxlet.core.protocol.toDevice
import dev.boxlet.core.routing.*
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withTimeoutOrNull
import okio.HashingSink
import okio.buffer
import java.io.InputStream
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.time.Instant

private const val PRUNE_INTERVAL_MS: Long = 15_000

/** 预哈希的读块大小：和 DATA 块一致，一次 read 出 1 MiB。 */
private const val HASH_BUFFER_BYTES: Int = 1024 * 1024

class TransferEngine(
    private val scope: CoroutineScope,
    private val security: SecurityContext,
    private val storage: ReceiveStorage,
    private val sendJournal: dev.boxlet.core.session.bp2.Bp2SendJournal? = null,
) : LocalSendHandler {

    private val api = ApiClient(security)
    private val _state = MutableStateFlow(EngineState())
    val state: StateFlow<EngineState> = _state.asStateFlow()

    private val _completions = MutableSharedFlow<ReceiveCompletion>(extraBufferCapacity = 64)
    val completions = _completions.asSharedFlow()

    var onShow: ((List<String>) -> Unit)? = null

    private var configProvider: () -> EngineConfig = {
        error("EngineConfig provider is not installed")
    }

    private var httpServer: LocalSendHttpServer? = null
    private var discovery: DiscoveryService? = null
    private var officialLocalSendDiscovery: DiscoveryService? = null
    private var bp2Adapter: Bp2EngineAdapter? = null
    private var routedChannel: DeviceChannel? = null
    val bp2Port: Int get() = bp2Adapter?.port ?: 0
    private var sweepJob: Job? = null
    @Volatile
    private var shareServer: ShareServer? = null
    private var bridgeServer: BridgeServer? = null
    private var actualPort: Int = Protocol.DEFAULT_PORT
    private var sendJob: Job? = null
    private var cancelNotification: Job? = null

    @Volatile
    private var receiveSession: ReceiveSession? = null

    private val receiveMutex = Mutex()
    private val pinFailures = ConcurrentHashMap<String, PinFailure>()
    private val lastProbe = ConcurrentHashMap<String, Long>()
    private val sendCancelled = AtomicBoolean(false)

    fun installConfig(provider: () -> EngineConfig) {
        configProvider = provider
    }

    // ---------------------------------------------------------------------
    // Lifecycle
    // ---------------------------------------------------------------------

    fun start() {
        if (httpServer != null || bp2Adapter != null) return
        val config = configProvider()
        val apiServer = LocalSendApiServer(this)
        val server = apiServer.newServer(
            security = security,
            port = if (config.bp2Enabled && config.port == Protocol.DEFAULT_PORT) Protocol.LOCALSEND_PORT else config.port,
            scope = scope,
            https = config.protocolType == ProtocolType.HTTPS,
        )
        val boundPort = runCatching { server.start() }.getOrElse { error ->
            _state.update { it.copy(error = bindFailureMessage(config.port, error)) }
            if (config.bp2Enabled) startBp2()
            return
        }
        actualPort = boundPort
        httpServer = server
        sendCancelled.set(false)
        _state.update { it.copy(serverRunning = true, error = null) }

        var primaryDiscoveryReady = false
        discovery = DiscoveryService(
            scope = scope,
            configProvider = ::discoveryConfig,
            onAnnounce = ::handleAnnounce,
            onFailure = { error ->
                _state.update { it.copy(error = "设备发现已停止：${error.message}") }
            },
        ).also { service ->
            runCatching { service.start() }.onSuccess { primaryDiscoveryReady = true }.onFailure { error ->
                service.stop()
                _state.update { it.copy(error = "UDP 发现启动失败：${error.message}") }
            }
        }

        // Transitional LocalSend server can keep its current HTTP port. Official clients listen
        // on UDP 53317 and connect to the HTTP port advertised in the standard announcement.
        // A custom discovery group still needs the official group for LocalSend compatibility.
        val officialPrimary = actualPort == Protocol.LOCALSEND_PORT && config.multicastGroup == Protocol.DEFAULT_MULTICAST_GROUP
        val needsOfficialDiscovery = config.port != 0 && !officialPrimary
        var officialReady = officialPrimary && primaryDiscoveryReady
        var compatibilityFailure: String? = null
        if (needsOfficialDiscovery) {
            val service = DiscoveryService(
                scope, { discoveryConfig().copy(multicastGroup = Protocol.DEFAULT_MULTICAST_GROUP,
                    discoveryPort = Protocol.LOCALSEND_PORT) }, ::handleAnnounce,
                onFailure = { error ->
                    _state.update { state -> state.copy(protocols = state.protocols.map {
                        if (it.adapterId == "localsend-v2") it.copy(reason = "LocalSend 官方发现已停止：${error.message}") else it
                    }) }
                },
            )
            runCatching { service.start() }.onSuccess {
                officialLocalSendDiscovery = service
                officialReady = true
            }.onFailure {
                service.stop()
                compatibilityFailure = "LocalSend 官方发现不可用：${it.message}"
            }
        }
        _state.update { it.copy(protocols = listOf(dev.boxlet.core.routing.AdapterState(
            "localsend-v2", discoveryReady = officialReady || primaryDiscoveryReady,
            sendReady = true, receiveReady = true, reason = compatibilityFailure,
        ))) }
        if (config.bp2Enabled) startBp2()

        sweepJob = scope.launch {
            while (isActive) {
                delay(PRUNE_INTERVAL_MS)
                pruneStaleDevices()
            }
        }
    }

    fun stop() {
        bp2Adapter?.close()
        bp2Adapter = null
        officialLocalSendDiscovery?.stop()
        officialLocalSendDiscovery = null
        discovery?.stop()
        discovery = null
        sweepJob?.cancel()
        sweepJob = null
        shareServer?.stop()
        shareServer = null
        bridgeServer?.stop()
        bridgeServer = null
        httpServer?.stop()
        httpServer = null
        sendJob?.cancel()
        sendJob = null
        cancelNotification?.cancel()
        cancelNotification = null
        api.cancelAll()
        receiveSession?.decision?.complete(null)
        receiveSession = null
        _state.update { it.copy(serverRunning = false, pendingReceive = null, protocols = emptyList()) }
    }

    fun restart() {
        stop()
        start()
    }

    fun clearError() {
        _state.update { it.copy(error = null) }
    }

    /**
     * 丢弃长时间没再出现的设备。否则对端退出后条目会一直留在列表里、始终显示「在线」，
     * 点进去发送只会失败（真机联调时踩到过）。
     */
    private fun pruneStaleDevices() {
        val cutoff = System.currentTimeMillis() - Protocol.DEVICE_EXPIRY_MS
        _state.update { current ->
            val kept = current.devices.filter { it.lastSeen >= cutoff }
            if (kept.size == current.devices.size) current else current.copy(devices = kept)
        }
    }

    /** 把底层网络异常翻译成用户能照做的提示。 */
    private fun sendFailureMessage(error: Throwable): String {
        val message = error.message.orEmpty()
        return when {
            error is java.net.SocketTimeoutException || message.contains("timeout", ignoreCase = true) ->
                "等待对方响应超时：请确认对方仍在运行，并在提示时点「接受」"
            error is java.net.ConnectException || message.contains("failed to connect", ignoreCase = true) ->
                "无法连接到对方：设备可能已退出，或不在同一网络"
            error is java.net.UnknownHostException -> "无法解析对方地址"
            error is javax.net.ssl.SSLException -> "证书校验失败：对方设备身份已变更"
            message.isBlank() -> "发送失败"
            else -> message
        }
    }

    /** 端口被占用在真机上很常见（同机还装着官方 LocalSend），给出可执行的提示而不是裸异常。 */
    private fun bindFailureMessage(port: Int, error: Throwable): String {
        val occupied = generateSequence(error) { it.cause }.any { cause ->
            cause is java.net.BindException ||
                cause.message?.contains("EADDRINUSE", ignoreCase = true) == true
        }
        return if (occupied) {
            "端口 $port 已被占用：请关闭本机其它 Boxlet 或 LocalSend 实例，或在设置中换一个端口"
        } else {
            "无法启动接收服务：${error.message}"
        }
    }

    fun clearIncoming() {
        _state.update { it.copy(incoming = null) }
    }

    fun clearOutgoing() {
        if (sendJob?.isActive == true) return
        _state.update { it.copy(outgoing = null) }
    }

    /**
     * Starts a browser sharing session (protocol §5). The service is plain HTTP on an ephemeral
     * port because browsers reject our self-signed certificate, see ADR 0003.
     */
    fun startShare(files: List<OutgoingFile>, pin: String?): ShareState? {
        if (files.isEmpty()) return null
        stopShare()
        val config = configProvider()
        val host = NetworkInterfaces.ipv4Addresses(config.networkWhitelist, config.networkBlacklist)
            .firstOrNull()?.address?.hostAddress
        if (host.isNullOrBlank()) {
            _state.update { it.copy(error = "没有可用的局域网地址，无法开启浏览器分享") }
            return null
        }
        val session = ShareServer(
            scope = scope,
            entries = files.map { file ->
                SharedEntry(
                    id = file.id,
                    fileName = file.fileName,
                    size = file.size,
                    mimeType = file.mimeType,
                    openSource = file.openSource,
                )
            },
            pin = pin,
            infoProvider = ::localInfo,
        )
        val bound = runCatching { session.start() }.getOrElse { error ->
            _state.update { it.copy(error = "无法启动分享服务：${error.message}") }
            return null
        }
        if (bound <= 0) {
            session.stop()
            return null
        }
        shareServer = session
        val share = ShareState(
            url = session.url(host),
            sessionId = session.sessionId,
            fileCount = files.size,
            totalBytes = files.sumOf { it.size },
            pinRequired = pin != null,
        )
        _state.update { it.copy(share = share, error = null) }
        // Announce again so peers see `download = true` while the session is alive.
        announceNow()
        return share
    }

    /**
     * 打开 SP/1 的浏览器接入点。浏览器不能监听、不能多播，靠它代做发现与落地。
     * `port = 0` 由系统分配。
     */
    fun startBridge(port: Int, pairCode: String?): BridgeState? {
        stopBridge()
        val config = configProvider()
        val host = NetworkInterfaces.ipv4Addresses(config.networkWhitelist, config.networkBlacklist)
            .firstOrNull()?.address?.hostAddress
        if (host.isNullOrBlank()) {
            _state.update { it.copy(error = "没有可用的局域网地址，无法开启浏览器接入") }
            return null
        }
        val server = BridgeServer(
            scope = scope,
            port = port,
            pairCode = pairCode,
            devicesProvider = {
                _state.value.devices.mapNotNull { device ->
                    val channel = device.channels.firstOrNull() ?: return@mapNotNull null
                    PeerDevice(
                        id = device.fingerprint,
                        alias = device.alias,
                        deviceType = device.deviceType.name.lowercase(),
                        host = channel.host,
                        port = channel.port,
                        online = System.currentTimeMillis() - device.lastSeen < Protocol.DEVICE_ONLINE_WINDOW_MS,
                    )
                }
            },
            storage = storage,
        )
        val bound = runCatching { server.start() }.getOrElse { error ->
            _state.update { it.copy(error = "无法启动浏览器接入：${error.message}") }
            return null
        }
        bridgeServer = server
        val bridge = BridgeState(
            port = bound,
            pairCodeRequired = !pairCode.isNullOrBlank(),
            url = "ws://$host:$bound/",
        )
        _state.update { it.copy(bridge = bridge, error = null) }
        return bridge
    }

    fun stopBridge() {
        if (bridgeServer == null && _state.value.bridge == null) return
        bridgeServer?.stop()
        bridgeServer = null
        _state.update { it.copy(bridge = null) }
    }

    fun stopShare() {
        val hadSession = shareServer != null || _state.value.share != null
        shareServer?.stop()
        shareServer = null
        if (hadSession) {
            _state.update { it.copy(share = null) }
            announceNow()
        }
    }

    fun announceNow() {
        discovery?.announceNow()
        officialLocalSendDiscovery?.announceNow()
        bp2Adapter?.announceNow()
    }

    /** Registers a peer discovered out of band (known host / tests). */
    fun addKnownDevice(device: Device) {
        upsert(device)
    }

    fun connectBp2(host: String, port: Int, fingerprint: String) {
        (bp2Adapter ?: error("BP/2 is not enabled")).connectPeer(host, port, fingerprint)
    }

    // ---------------------------------------------------------------------
    // Discovery
    // ---------------------------------------------------------------------

    private fun startBp2() {
        val adapter = Bp2EngineAdapter(scope, security, storage, { configProvider() }, receiveMutex,
            { receiveSession != null }, { change -> _state.update(change) }, ::upsert,
            { completion -> _completions.emit(completion) }, sendJournal)
        try {
            val status = adapter.start()
            bp2Adapter = adapter
            _state.update { it.copy(serverRunning = true, protocols = it.protocols.filterNot { s -> s.adapterId == status.adapterId } + status) }
        } catch (error: Throwable) {
            adapter.close()
            _state.update { it.copy(protocols = it.protocols + AdapterState(TransferRouter.BOXLET, reason = error.message)) }
        }
    }

    private fun route(device: Device): DeviceChannel {
        val router = TransferRouter().apply { _state.value.protocols.forEach(::update) }
        val channels = device.channels
        val endpoints = channels.mapIndexed { index, channel ->
            DiscoveredEndpoint("${channel.adapterId}:$index", channel.adapterId, device.fingerprint,
                "${channel.host}:${channel.port}", device.alias, device.lastSeen,
                setOf(TransferCapability.FILES, TransferCapability.TEXT, TransferCapability.FOLDERS),
                verifiedCertificate = device.fingerprint.lowercase().takeIf { channel.identityVerified && it.length == 64 })
        }
        val selected = router.select(PeerRecord(device.fingerprint, endpoints))
        return channels[endpoints.indexOf(selected.endpoint)]
    }

    private fun discoveryConfig(): DiscoveryConfig {
        val config = configProvider()
        return DiscoveryConfig(
            port = actualPort,
            multicastGroup = config.multicastGroup,
            alias = config.alias,
            deviceModel = config.deviceModel,
            deviceType = config.deviceType,
            protocolType = config.protocolType,
            fingerprint = config.fingerprint,
            // `download` 描述的是「现在能不能用浏览器取文件」，跟随分享会话，而不是常量。
            download = shareServer != null,
            whitelist = config.networkWhitelist,
            blacklist = config.networkBlacklist,
        )
    }

    private fun handleAnnounce(host: String, message: MulticastMessage) {
        val config = configProvider()
        if (message.fingerprint.equals(config.fingerprint, ignoreCase = true)) return
        val channel = DeviceChannel(host, message.port, message.protocol == ProtocolType.HTTPS)
        // announce = false 是对方向我方公告的 UDP 回退应答：它已经证明自己在这里，
        // 此时不要再回 register（会来回重入），直接把它记进设备列表。
        if (!message.announce) {
            upsert(message.toDevice(channel))
            return
        }
        scope.launch(Dispatchers.IO) {
            val now = System.currentTimeMillis()
            val previous = lastProbe[message.fingerprint]
            if (previous != null && now - previous < 2_000) {
                mergeChannel(message, channel)
                return@launch
            }
            lastProbe[message.fingerprint] = now
            val result = api.register(channel, localInfo(), message.fingerprint)
            if (!result.isSuccessful) {
                // 对方的 HTTP 打不通（防火墙/入站被拒）：发一个回退应答，让它反过来连我。
                discovery?.announceFallback()
                officialLocalSendDiscovery?.announceFallback()
                return@launch
            }
            val info = runCatching {
                BoxletJson.decodeFromString(InfoResponse.serializer(), result.body)
            }.getOrNull() ?: return@launch
            // HTTPS 下响应里的 fingerprint 同样被忽略：对端身份已由 TLS 固定为公告里的指纹。
            if (!channel.https && !info.fingerprint.equals(message.fingerprint, ignoreCase = true)) return@launch
            upsert(info.copy(fingerprint = message.fingerprint).toDevice(channel))
        }
    }

    private fun mergeChannel(message: MulticastMessage, channel: DeviceChannel) {
        val existing = _state.value.devices.firstOrNull {
            it.fingerprint.equals(message.fingerprint, ignoreCase = true)
        } ?: return
        upsert(existing.withChannel(channel))
    }

    /** Announce + optionally scan local /24 subnets when multicast is unavailable. */
    fun scan() {
        announceNow()
        val config = configProvider()
        scope.launch(Dispatchers.IO) {
            val addresses = NetworkInterfaces.ipv4Addresses(config.networkWhitelist, config.networkBlacklist)
            val semaphore = Semaphore(50)
            coroutineScope {
                for (local in addresses) {
                    val ownIp = local.address.hostAddress.orEmpty()
                    val prefix = ownIp.substringBeforeLast('.', missingDelimiterValue = "")
                    if (prefix.isEmpty()) continue
                    for (index in 1..254) {
                        val ip = "$prefix.$index"
                        if (ip == ownIp) continue
                        launch {
                            val scanPort = if (config.bp2Enabled && config.port == Protocol.DEFAULT_PORT)
                                Protocol.LOCALSEND_PORT else config.port
                            semaphore.withPermit { probe(ip, scanPort, config.discoveryTimeoutMs) }
                        }
                    }
                }
            }
        }
    }

    private fun probe(ip: String, port: Int, timeoutMs: Long) {
        val https = DeviceChannel(ip, port, https = true)
        val secure = api.info(https, null)
        if (secure.isSuccessful) {
            parseInfo(secure)?.let { upsert(it.toDevice(https)) }
            return
        }
        val http = DeviceChannel(ip, port, https = false)
        val plain = api.info(http, null)
        if (plain.isSuccessful) {
            parseInfo(plain)?.let { upsert(it.toDevice(http)) }
        }
    }

    private fun parseInfo(result: ApiResult): InfoResponse? = runCatching {
        BoxletJson.decodeFromString(InfoResponse.serializer(), result.body)
    }.getOrNull()

    private fun upsert(device: Device) {
        _state.update { current ->
            val existing = current.devices.firstOrNull {
                it.fingerprint.equals(device.fingerprint, ignoreCase = true)
            }
            val merged = if (existing == null) {
                device
            } else {
                existing.copy(
                    alias = device.alias,
                    version = device.version,
                    deviceModel = device.deviceModel,
                    deviceType = device.deviceType,
                    download = device.download,
                    channels = (device.channels + existing.channels)
                        .distinctBy { listOf(it.adapterId, it.host, it.port, it.https) },
                    lastSeen = System.currentTimeMillis(),
                )
            }
            val devices = (current.devices.filterNot {
                it.fingerprint.equals(device.fingerprint, ignoreCase = true)
            } + merged).sortedBy { it.alias.lowercase() }
            current.copy(devices = devices)
        }
    }

    // ---------------------------------------------------------------------
    // LocalSendHandler (server side)
    // ---------------------------------------------------------------------

    override fun localInfo(): RegisterRequest = configProvider().toRegisterRequest()
        .copy(port = actualPort, download = shareServer != null,
            sendeeCapabilities = if (configProvider().bp2Enabled) null else listOf(Protocol.STREAMING_CHECKSUM))

    override fun showToken(): String = configProvider().showToken

    override fun webIndexHtml(): String = """
        <!doctype html>
        <html lang="zh">
        <head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
        <title>Boxlet</title>
        <style>body{font-family:system-ui,sans-serif;background:#0f1115;color:#e8e8e8;display:grid;place-items:center;height:100vh;margin:0}
        .card{background:#1b1e24;padding:32px 40px;border-radius:14px;max-width:520px;text-align:center}
        h1{margin:0 0 8px;font-size:22px}p{color:#9aa0a6;line-height:1.6}</style></head>
        <body><div class="card"><h1>Boxlet</h1>
        <p>本设备正在局域网内运行。请使用 Boxlet 或 LocalSend 客户端发送文件。</p>
        <p>Web 分享未启用。</p></div></body></html>
    """.trimIndent()

    override suspend fun onRegister(
        request: RegisterRequest,
        remoteIp: String,
        clientFingerprint: String?,
    ): InfoResponse {
        val channel = DeviceChannel(remoteIp, request.port, request.protocol == ProtocolType.HTTPS)
        // 协议规定 HTTPS 模式下报文里的 fingerprint 被忽略，对端身份以 TLS 证书为准。
        // 早期实现把两者不一致当成非法注册直接忽略，遇到该字段填随机串的客户端就会永远发现不了。
        val trusted = if (channel.https && clientFingerprint != null) {
            request.copy(fingerprint = clientFingerprint)
        } else {
            request
        }
        upsert(trusted.toDevice(channel))
        return infoResponse()
    }

    private fun infoResponse(): InfoResponse {
        val info = localInfo()
        return InfoResponse(
            alias = info.alias,
            version = info.version,
            deviceModel = info.deviceModel,
            deviceType = info.deviceType,
            fingerprint = info.fingerprint,
            download = info.download,
            sendeeCapabilities = info.sendeeCapabilities,
        )
    }

    override suspend fun onPrepareUpload(
        request: PrepareUploadRequest,
        pin: String?,
        remoteIp: String,
        clientFingerprint: String?,
    ): PrepareUploadOutcome = receiveMutex.withLock {
        val config = configProvider()
        if (receiveSession != null || bp2Adapter?.isReceiving == true) return@withLock PrepareUploadOutcome.Busy

        if (config.receivePin != null) {
            val now = System.currentTimeMillis()
            val previous = pinFailures[remoteIp]
            val failures = if (previous != null && now - previous.at < 60_000) previous.count else 0
            if (failures >= Protocol.MAX_PIN_ATTEMPTS) return@withLock PrepareUploadOutcome.TooManyRequests
            if (pin.isNullOrBlank()) return@withLock PrepareUploadOutcome.PinRequired
            if (pin != config.receivePin) {
                pinFailures[remoteIp] = PinFailure(failures + 1, now)
                return@withLock PrepareUploadOutcome.InvalidPin
            }
            pinFailures.remove(remoteIp)
        }

        if (request.files.isEmpty()) return@withLock PrepareUploadOutcome.Rejected("No files provided")
        if (config.bp2Enabled && request.files.values.any { it.sendeeChecksum != null })
            return@withLock PrepareUploadOutcome.Invalid("Boxlet extensions are not available on the LocalSend compatibility endpoint")
        if (request.files.values.any {
                it.sendeeChecksum != null && (it.sendeeChecksum != Protocol.STREAMING_CHECKSUM ||
                    it.size < 0 || it.size > Long.MAX_VALUE - 32 ||
                    (it.preview != null && it.fileType.startsWith("text/")))
            }) return@withLock PrepareUploadOutcome.Invalid("Invalid streaming checksum metadata")

        val channel = DeviceChannel(remoteIp, request.info.port, request.info.protocol == ProtocolType.HTTPS)
        // 同 onRegister：HTTPS 下以客户端证书指纹为准，报文里的 fingerprint 仅作参考。
        // 不一致时拒绝会把「字段填随机串但证书合法」的对端挡在 403。
        val peerInfo = if (channel.https && clientFingerprint != null) {
            request.info.copy(fingerprint = clientFingerprint)
        } else {
            request.info
        }
        val peer = peerInfo.toDevice(channel)
        upsert(peer)

        val sessionId = UUID.randomUUID().toString()
        val files = LinkedHashMap<String, IncomingFile>()
        request.files.forEach { (id, dto) ->
            files[id] = IncomingFile(dto = dto, token = UUID.randomUUID().toString())
        }
        val only = files.values.singleOrNull()?.dto
        val isMessage = only != null && only.fileType.startsWith("text/") && only.preview != null
        val messageText = if (isMessage) only.preview else null

        val session = ReceiveSession(
            id = sessionId,
            peer = peer,
            senderIp = remoteIp,
            files = files,
            decision = CompletableDeferred(),
            isMessage = isMessage,
            messageText = messageText,
        )
        receiveSession = session

        _state.update {
            it.copy(
                pendingReceive = PendingReceive(
                    sessionId = sessionId,
                    peer = peer,
                    files = request.files.values.toList(),
                    isMessage = isMessage,
                    message = messageText,
                ),
                incoming = IncomingTransfer(
                    sessionId = sessionId,
                    peer = peer,
                    status = TransferStatus.WAITING_FOR_ACCEPT,
                    files = request.files.values.map { dto ->
                        TransferFileState(
                            id = dto.id,
                            fileName = dto.fileName,
                            size = dto.size,
                            mimeType = dto.fileType,
                            isMessage = isMessage,
                        )
                    },
                ),
            )
        }

        val autoAccept = config.quickSave || (config.quickSaveFromFavorites && config.autoAccept(peer))
        if (autoAccept) {
            session.decision.complete(files.keys.toSet())
        } else {
            scope.launch {
                val decided = withTimeoutOrNull(Protocol.PREPARE_DECISION_TIMEOUT_MS) { session.decision.await() }
                if (decided == null && !session.decision.isCompleted) session.decision.complete(null)
            }
        }

        val acceptedIds = session.decision.await()
        _state.update { it.copy(pendingReceive = it.pendingReceive?.takeUnless { pending -> pending.sessionId == session.id }) }

        if (acceptedIds.isNullOrEmpty()) {
            if (receiveSession === session) receiveSession = null
            _state.update { it.copy(incoming = it.incoming?.takeUnless { incoming -> incoming.sessionId == session.id && incoming.status != TransferStatus.CANCELLED }) }
            return@withLock PrepareUploadOutcome.Rejected("Rejected")
        }

        session.accepted = true
        _state.update { current ->
            current.copy(
                incoming = current.incoming?.let {
                    if (it.sessionId == session.id) it.copy(status = TransferStatus.TRANSFERRING) else it
                },
            )
        }
        PrepareUploadOutcome.Accepted(
            sessionId = sessionId,
            files = acceptedIds.mapNotNull { id -> files[id]?.let { id to it.token } }.toMap(),
        )
    }

    override suspend fun onUpload(
        sessionId: String,
        fileId: String,
        token: String,
        remoteIp: String,
        body: InputStream,
        contentLength: Long,
        onProgress: (Long) -> Unit,
    ): UploadOutcome {
        val session = receiveSession ?: return UploadOutcome.Forbidden("Invalid sessionId")
        if (session.id != sessionId) return UploadOutcome.Forbidden("Invalid sessionId")
        if (!session.accepted) return UploadOutcome.Forbidden("Session is not accepted")
        if (session.senderIp != remoteIp) return UploadOutcome.Forbidden()
        val file = session.files[fileId] ?: return UploadOutcome.Forbidden("Invalid fileId")
        if (file.token != token) return UploadOutcome.Forbidden()
        if (file.status == FileTransferStatus.COMPLETED) return UploadOutcome.Ok

        file.attempts += 1
        file.status = FileTransferStatus.TRANSFERRING
        updateIncomingFile(sessionId, fileId) {
            it.copy(status = FileTransferStatus.TRANSFERRING, bytesTransferred = 0L, error = null)
        }

        if (session.isMessage) {
            val bytes = drain(body)
            file.bytes = bytes
            file.status = FileTransferStatus.COMPLETED
            updateIncomingFile(sessionId, fileId) {
                it.copy(status = FileTransferStatus.COMPLETED, bytesTransferred = bytes)
            }
            _completions.emit(
                ReceiveCompletion(
                    sessionId = sessionId,
                    senderAlias = session.peer.alias,
                    file = file.dto,
                    savedPath = null,
                    message = session.messageText,
                ),
            )
            finishIfDone(session)
            return UploadOutcome.Ok
        }

        val destination = try {
            storage.createDestination(sessionId, file.dto, session.peer.alias)
        } catch (error: Throwable) {
            file.status = FileTransferStatus.FAILED
            updateIncomingFile(sessionId, fileId) { it.copy(status = FileTransferStatus.FAILED, error = error.message) }
            finishIfDone(session)
            return UploadOutcome.Failed("Failed to create file: ${error.message}")
        }
        file.destination = destination

        val streamingChecksum = file.dto.sendeeChecksum == Protocol.STREAMING_CHECKSUM
        val expectedHash = if (configProvider().verifyChecksums) file.dto.sha256?.uppercase() else null
        val hashingSink = if (expectedHash != null || streamingChecksum) HashingSink.sha256(destination.sink) else null
        val sink = (hashingSink ?: destination.sink).buffer()
        var written = 0L
        var lastUpdate = 0L

        try {
            val buffer = ByteArray(64 * 1024)
            while (true) {
                if (streamingChecksum && written == file.dto.size) break
                val limit = if (streamingChecksum) minOf(buffer.size.toLong(), file.dto.size - written).toInt() else buffer.size
                val read = body.read(buffer, 0, limit)
                if (read < 0) break
                sink.write(buffer, 0, read)
                written += read
                if (written > file.dto.size) throw IllegalStateException("File exceeds declared size")
                val now = System.currentTimeMillis()
                if (now - lastUpdate >= 100) {
                    lastUpdate = now
                    file.bytes = written
                    onProgress(written)
                    updateIncomingFile(sessionId, fileId) { it.copy(bytesTransferred = written) }
                }
            }
            if (file.dto.size >= 0 && written != file.dto.size) {
                throw IllegalStateException("Incomplete upload: expected ${file.dto.size}, got $written")
            }
            sink.flush()
            val actualHash = hashingSink?.hash?.hex()?.uppercase()
            val trailerHash = if (streamingChecksum) {
                val trailer = ByteArray(32)
                var offset = 0
                while (offset < trailer.size) {
                    val read = body.read(trailer, offset, trailer.size - offset)
                    check(read > 0) { "Missing streaming checksum" }
                    offset += read
                }
                check(body.read() == -1) { "Unexpected bytes after streaming checksum" }
                okio.ByteString.of(*trailer).hex().uppercase()
            } else null
            if ((expectedHash != null && expectedHash != actualHash) ||
                (streamingChecksum && trailerHash != actualHash)) {
                sink.close()
                runCatching { destination.abort() }
                file.status = if (file.attempts < Protocol.MAX_UPLOAD_ATTEMPTS) {
                    FileTransferStatus.QUEUED
                } else {
                    FileTransferStatus.FAILED
                }
                updateIncomingFile(sessionId, fileId) {
                    it.copy(
                        status = file.status,
                        error = "Checksum mismatch",
                        bytesTransferred = 0L,
                    )
                }
                if (file.status == FileTransferStatus.FAILED) finishIfDone(session)
                return UploadOutcome.ChecksumMismatch
            }

            sink.close()
            destination.commit()
            file.status = FileTransferStatus.COMPLETED
            file.bytes = written
            updateIncomingFile(sessionId, fileId) {
                it.copy(
                    status = FileTransferStatus.COMPLETED,
                    bytesTransferred = written,
                    savedPath = destination.displayPath,
                )
            }
            _completions.emit(
                ReceiveCompletion(
                    sessionId = sessionId,
                    senderAlias = session.peer.alias,
                    file = file.dto,
                    savedPath = destination.displayPath,
                    message = null,
                ),
            )
            finishIfDone(session)
            return UploadOutcome.Ok
        } catch (error: Throwable) {
            try {
                sink.close()
            } catch (_: Throwable) {
                // ignore
            }
            try {
                destination.abort()
            } catch (_: Throwable) {
                // ignore
            }
            file.status = FileTransferStatus.FAILED
            updateIncomingFile(sessionId, fileId) {
                it.copy(status = FileTransferStatus.FAILED, error = error.message ?: "Upload failed")
            }
            finishIfDone(session)
            return UploadOutcome.Failed(error.message ?: "Upload failed")
        }
    }

    override suspend fun onCancel(sessionId: String?, remoteIp: String) {
        val session = receiveSession
        if (session != null && session.senderIp == remoteIp &&
            (session.id == sessionId || sessionId == null)) {
            session.decision.complete(null)
            receiveSession = null
            session.files.values.forEach { file ->
                try {
                    file.destination?.abort()
                } catch (_: Throwable) {
                    // ignore
                }
            }
            _state.update {
                it.copy(
                    pendingReceive = it.pendingReceive?.takeUnless { pending -> pending.sessionId == session.id },
                    incoming = it.incoming?.let { incoming ->
                        if (incoming.sessionId == session.id) incoming.copy(status = TransferStatus.CANCELLED) else incoming
                    },
                )
            }
            return
        }

        // The peer cancelled a session that we are sending.
        val outgoing = _state.value.outgoing
        if (outgoing?.adapterId == TransferRouter.LOCALSEND && outgoing.peer.channels.any { it.host == remoteIp } &&
            (outgoing.sessionId == sessionId || outgoing.status == TransferStatus.PREPARING)) {
            cancelSend()
        }
    }

    /**
     * 主端口的 Download API：`download` 字段为 `true` 时对端就是打这个端口的，
     * 所以这里必须真的服务，而不是回 403。数据源与浏览器分享页是同一个会话。
     */
    override suspend fun onPrepareDownload(pin: String?, remoteIp: String): PrepareDownloadOutcome {
        val share = shareServer ?: return PrepareDownloadOutcome.NoSession
        if (!share.pinMatches(pin)) return PrepareDownloadOutcome.PinRequired
        return PrepareDownloadOutcome.Ok(share.prepareDownloadBody())
    }

    override suspend fun onDownload(
        sessionId: String?,
        fileId: String?,
        pin: String?,
        remoteIp: String,
    ): DownloadOutcome {
        val share = shareServer ?: return DownloadOutcome.NoSession
        if (!share.pinMatches(pin)) return DownloadOutcome.PinRequired
        if (sessionId != share.sessionId) return DownloadOutcome.InvalidSession
        val entry = share.entry(fileId) ?: return DownloadOutcome.InvalidSession
        val source = runCatching { entry.openSource() }.getOrNull() ?: return DownloadOutcome.Failed
        return DownloadOutcome.File(entry.mimeType, entry.fileName, entry.size, source)
    }

    override fun onShow(args: List<String>) {
        onShow?.invoke(args)
    }

    // ---------------------------------------------------------------------
    // UI actions: receiving
    // ---------------------------------------------------------------------

    fun acceptReceive(fileIds: Set<String>) {
        if (bp2Adapter?.accept(fileIds) == true) return
        val session = receiveSession ?: return
        session.decision.complete(fileIds)
    }

    fun acceptAllReceive() {
        if (bp2Adapter?.accept() == true) return
        val session = receiveSession ?: return
        session.decision.complete(session.files.keys.toSet())
    }

    fun rejectReceive() {
        if (bp2Adapter?.reject() == true) return
        receiveSession?.decision?.complete(null)
    }

    fun cancelReceive() {
        if (bp2Adapter?.cancelReceive() == true) return
        val session = receiveSession ?: return
        receiveSession = null
        session.decision.complete(null)
        _state.update {
            it.copy(
                pendingReceive = null,
                incoming = it.incoming?.copy(status = TransferStatus.CANCELLED),
            )
        }
        scope.launch(Dispatchers.IO) {
            session.files.values.forEach { file ->
                try {
                    file.destination?.abort()
                } catch (_: Throwable) {
                    // ignore
                }
            }
            val channel = session.peer.preferredChannel ?: return@launch
            api.cancel(channel, session.peer.fingerprint, session.id)
        }
    }

    // ---------------------------------------------------------------------
    // UI actions: sending
    // ---------------------------------------------------------------------

    fun send(fingerprint: String, files: List<OutgoingFile>, pinProvider: PinProvider) {
        if (sendJob?.isActive == true) return
        if (_state.value.outgoing?.status == TransferStatus.PAUSED) {
            _state.update { it.copy(error = "请先继续或取消暂停的任务") }; return
        }
        if (files.isEmpty()) return
        val device = _state.value.devices.firstOrNull { it.fingerprint == fingerprint } ?: return
        sendCancelled.set(false)
        val selected = runCatching { route(device) }.getOrElse { error ->
            _state.update { it.copy(error = error.message) }; return
        }
        routedChannel = selected
        val previousCancellation = cancelNotification
        sendJob = scope.launch(Dispatchers.IO) {
            previousCancellation?.join()
            currentCoroutineContext().ensureActive()
            if (selected.adapterId == TransferRouter.BOXLET) bp2Adapter?.send(device, selected, files)
            else runSend(device, files, pinProvider, selected)
        }
    }

    fun cancelSend() {
        val outgoing = _state.value.outgoing ?: return
        sendCancelled.set(true)
        if (outgoing.status == TransferStatus.CANCELLED) { sendJob?.cancel(); return }
        bp2Adapter?.cancelRetainedSend()
        if (bp2Adapter == null && outgoing.adapterId == TransferRouter.BOXLET) {
            runCatching { sendJournal?.clear(outgoing.sessionId) }
                .onFailure { _state.update { it.copy(error = "发送恢复记录清理失败") } }
        }
        sendJob?.cancel()
        if (outgoing.adapterId == TransferRouter.LOCALSEND) {
            val channel = routedChannel
            if (channel != null) cancelNotification = scope.launch(Dispatchers.IO) {
                api.cancel(channel, outgoing.peer.fingerprint,
                    if (outgoing.status == TransferStatus.PREPARING) null else outgoing.sessionId)
            }
        }
        updateOutgoing { it.copy(status = TransferStatus.CANCELLED) }
    }

    fun setTransferPaused(outgoing: Boolean, paused: Boolean): Boolean {
        val state = _state.value
        val adapter = if (outgoing) state.outgoing?.adapterId else state.incoming?.adapterId
        val status = if (outgoing) state.outgoing?.status else state.incoming?.status
        if (adapter != TransferRouter.BOXLET || status !in listOf(TransferStatus.TRANSFERRING, TransferStatus.PAUSED)) return false
        return bp2Adapter?.setPaused(outgoing, paused) == true
    }

    fun resetOutgoingFile(fileId: String): Boolean {
        val transfer = _state.value.outgoing ?: return false
        if (transfer.adapterId != TransferRouter.BOXLET || transfer.status != TransferStatus.PAUSED) return false
        return bp2Adapter?.resetFile(fileId) == true
    }

    private suspend fun runSend(device: Device, files: List<OutgoingFile>, pinProvider: PinProvider, selected: DeviceChannel) {
        val config = configProvider()
        val channel = selected
        val sessionId = UUID.randomUUID().toString()
        _state.update {
            it.copy(
                outgoing = OutgoingTransfer(
                    sessionId = sessionId,
                    peer = device,
                    status = TransferStatus.PREPARING,
                    files = files.map { file ->
                        TransferFileState(
                            id = file.id,
                            fileName = file.fileName,
                            size = file.size,
                            mimeType = file.mimeType,
                            isMessage = file.isMessage,
                        )
                    },
                ),
            )
        }

        try {
            // Probe the selected endpoint using the same pinned TLS client as the upload.
            // Missing/unknown capabilities always use the unchanged LocalSend wire format.
            val streamingChecksum = !config.bp2Enabled && config.createChecksums && runCatching {
                val result = api.info(channel, device.fingerprint)
                result.isSuccessful && Protocol.STREAMING_CHECKSUM in
                    (BoxletJson.decodeFromString(InfoResponse.serializer(), result.body).sendeeCapabilities ?: emptyList())
            }.getOrDefault(false)
            val hashes = ConcurrentHashMap<String, String>()
            if (config.createChecksums && !streamingChecksum) {
                if (sendCancelled.get()) return
                // Bound disk readers and coroutine count, even for thousands of files.
                forEachWorker(files.filterNot { it.isMessage }, Protocol.UPLOAD_CONCURRENCY) { file ->
                    hashes[file.id] = hashOf(file)
                }
                if (sendCancelled.get()) return
            }

            val dtos = files.associate { file ->
                val metadata = file.modified?.let { FileMetadata(modified = it.toString(), accessed = null) }
                file.id to FileDto(
                    id = file.id,
                    fileName = file.fileName,
                    size = file.size,
                    fileType = file.mimeType,
                    sha256 = hashes[file.id],
                    preview = file.preview,
                    metadata = metadata,
                    sendeeChecksum = if (streamingChecksum && !file.isMessage) Protocol.STREAMING_CHECKSUM else null,
                )
            }
            val request = PrepareUploadRequest(info = localInfo(), files = dtos)

            var pin: String? = null
            var attempt = 0
            var response: dev.boxlet.core.protocol.PrepareUploadResponse? = null
            while (response == null) {
                if (sendCancelled.get()) return
                val result = api.prepareUpload(channel, device.fingerprint, request, pin)
                when (result.code) {
                    200 -> response = api.parsePrepareUpload(result)
                    204 -> {
                        updateOutgoing { it.copy(status = TransferStatus.COMPLETED) }
                        return
                    }

                    401 -> {
                        attempt += 1
                        if (attempt > 3) {
                            updateOutgoing {
                                it.copy(status = TransferStatus.PIN_REQUIRED, error = "PIN 验证失败")
                            }
                            return
                        }
                        updateOutgoing { it.copy(status = TransferStatus.PIN_REQUIRED) }
                        val entered = pinProvider(attempt) ?: run {
                            updateOutgoing { it.copy(status = TransferStatus.CANCELLED) }
                            return
                        }
                        pin = entered
                    }

                    403 -> {
                        updateOutgoing { it.copy(status = TransferStatus.DECLINED, error = "对方拒绝了传输") }
                        return
                    }

                    409 -> {
                        updateOutgoing { it.copy(status = TransferStatus.BUSY, error = "对方正在接收其他传输") }
                        return
                    }

                    429 -> {
                        updateOutgoing { it.copy(status = TransferStatus.TOO_MANY_ATTEMPTS, error = "PIN 尝试次数过多") }
                        return
                    }

                    else -> {
                        updateOutgoing {
                            it.copy(status = TransferStatus.FAILED, error = result.errorMessage())
                        }
                        return
                    }
                }
            }

            val prepared = response
            // Cancellation must reference the receiver generated session id.
            updateOutgoing { it.copy(sessionId = prepared.sessionId, status = TransferStatus.TRANSFERRING) }
            val tokens = prepared.files
            val accepted = files.filter { tokens.containsKey(it.id) }
            val skipped = files.filterNot { tokens.containsKey(it.id) }
            skipped.forEach { file ->
                updateOutgoingFile(file.id) { it.copy(status = FileTransferStatus.SKIPPED) }
            }
            updateOutgoing { it.copy(status = TransferStatus.TRANSFERRING) }

            val largeFiles = Semaphore(Protocol.UPLOAD_CONCURRENCY)
            val concurrency = if (accepted.count { it.size <= Protocol.SMALL_FILE_BYTES } >= 2)
                Protocol.SMALL_FILE_UPLOAD_CONCURRENCY else Protocol.UPLOAD_CONCURRENCY
            forEachWorker(accepted.sortedBy { it.size }, concurrency) { file ->
                suspend fun upload() = uploadOne(channel, device, prepared.sessionId, file,
                    tokens.getValue(file.id), dtos.getValue(file.id).sendeeChecksum != null)
                if (file.size > Protocol.SMALL_FILE_BYTES) largeFiles.withPermit { upload() }
                else upload()
            }

            val current = _state.value.outgoing
            if (current != null && !sendCancelled.get()) {
                val failed = current.files.any { it.status == FileTransferStatus.FAILED }
                updateOutgoing {
                    it.copy(
                        status = if (failed) TransferStatus.FAILED else TransferStatus.COMPLETED,
                        error = if (failed) "部分文件发送失败" else null,
                    )
                }
            }
        } catch (error: Throwable) {
            if (error is CancellationException) throw error
            if (!sendCancelled.get()) {
                updateOutgoing {
                    it.copy(status = TransferStatus.FAILED, error = sendFailureMessage(error))
                }
            }
        }
    }

    private suspend fun uploadOne(
        channel: DeviceChannel,
        device: Device,
        sessionId: String,
        file: OutgoingFile,
        token: String,
        streamingChecksum: Boolean,
    ) {
        val uploadContext = currentCoroutineContext()
        var attempt = 0
        while (attempt < Protocol.MAX_UPLOAD_ATTEMPTS) {
            attempt += 1
            if (sendCancelled.get()) return
            updateOutgoingFile(file.id) {
                it.copy(status = FileTransferStatus.TRANSFERRING, bytesTransferred = 0L, error = null)
            }
            var lastUpdate = 0L
            val result = api.upload(
                channel = channel,
                expectedFingerprint = device.fingerprint,
                sessionId = sessionId,
                fileId = file.id,
                token = token,
                file = file,
                streamingChecksum = streamingChecksum,
                isCancelled = { sendCancelled.get() },
                onProgress = { bytes ->
                    val now = System.currentTimeMillis()
                    if (now - lastUpdate >= 100 || bytes == file.size) {
                        lastUpdate = now
                        updateOutgoingFile(file.id) {
                            if (uploadContext.isActive) it.copy(bytesTransferred = bytes) else it
                        }
                    }
                },
            )
            uploadContext.ensureActive()

            when (result.code) {
                200 -> {
                    updateOutgoingFile(file.id) {
                        it.copy(status = FileTransferStatus.COMPLETED, bytesTransferred = file.size)
                    }
                    return
                }

                422 -> {
                    if (attempt >= Protocol.MAX_UPLOAD_ATTEMPTS) {
                        updateOutgoingFile(file.id) {
                            it.copy(status = FileTransferStatus.FAILED, error = "校验和不匹配")
                        }
                        return
                    }
                    delay(300)
                }

                else -> {
                    updateOutgoingFile(file.id) {
                        it.copy(
                            status = FileTransferStatus.FAILED,
                            error = result.errorMessage(),
                        )
                    }
                    return
                }
            }
        }
    }

    /**
     * 预哈希是发送前的一次全量读盘，直接按 1 MiB 块喂 [MessageDigest]。
     *
     * 旧实现走 `readAll(HashingSink.sha256(Buffer()))`：8 KiB 一次读，而且被哈希的数据
     * 全都留在那个 Buffer 里，1 GB 文件就吃 1 GB 堆。
     */
    private suspend fun hashOf(file: OutgoingFile): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.openSource().buffer().use { source ->
            val buffer = ByteArray(HASH_BUFFER_BYTES)
            while (true) {
                currentCoroutineContext().ensureActive()
                if (sendCancelled.get()) throw CancellationException("Hashing cancelled")
                val read = source.read(buffer, 0, buffer.size)
                if (read == -1) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { byte ->
            (byte.toInt() and 0xFF).toString(16).padStart(2, '0').uppercase()
        }
    }

    private suspend fun forEachWorker(
        files: List<OutgoingFile>,
        concurrency: Int,
        action: suspend (OutgoingFile) -> Unit,
    ) = coroutineScope {
        val queue = ConcurrentLinkedQueue(files)
        repeat(minOf(concurrency, files.size)) {
            launch(Dispatchers.IO) {
                while (true) {
                    ensureActive()
                    if (sendCancelled.get()) break
                    val file = queue.poll() ?: break
                    action(file)
                }
            }
        }
    }

    // ---------------------------------------------------------------------
    // State helpers
    // ---------------------------------------------------------------------

    private fun updateOutgoing(transform: (OutgoingTransfer) -> OutgoingTransfer) {
        _state.update { current ->
            val outgoing = current.outgoing ?: return@update current
            current.copy(outgoing = transform(outgoing))
        }
    }

    private fun updateOutgoingFile(fileId: String, transform: (TransferFileState) -> TransferFileState) {
        updateOutgoing { outgoing ->
            outgoing.copy(files = outgoing.files.map { if (it.id == fileId) transform(it) else it })
        }
    }

    private fun updateIncomingFile(
        sessionId: String,
        fileId: String,
        transform: (TransferFileState) -> TransferFileState,
    ) {
        _state.update { current ->
            val incoming = current.incoming
            if (incoming == null || incoming.sessionId != sessionId) return@update current
            current.copy(
                incoming = incoming.copy(
                    files = incoming.files.map { if (it.id == fileId) transform(it) else it },
                ),
            )
        }
    }

    private suspend fun finishIfDone(session: ReceiveSession) {
        val done = session.files.values.all {
            it.status == FileTransferStatus.COMPLETED || it.status == FileTransferStatus.FAILED
        }
        if (!done) return
        receiveMutex.withLock {
            if (receiveSession !== session) return@withLock
            receiveSession = null
            val failed = session.files.values.any { it.status == FileTransferStatus.FAILED }
            _state.update {
                it.copy(
                    pendingReceive = null,
                    incoming = it.incoming?.copy(
                        status = if (failed) TransferStatus.FAILED else TransferStatus.COMPLETED,
                        error = if (failed) "部分文件接收失败" else null,
                    ),
                )
            }
        }
    }

    private fun drain(body: InputStream): Long {
        val buffer = ByteArray(64 * 1024)
        var total = 0L
        while (true) {
            val read = body.read(buffer)
            if (read < 0) break
            total += read
        }
        return total
    }

    private data class PinFailure(val count: Int, val at: Long)

    private class IncomingFile(
        val dto: FileDto,
        val token: String,
        var status: FileTransferStatus = FileTransferStatus.QUEUED,
        var attempts: Int = 0,
        var bytes: Long = 0L,
        var destination: ReceiveDestination? = null,
    )

    private class ReceiveSession(
        val id: String,
        val peer: Device,
        val senderIp: String,
        val files: LinkedHashMap<String, IncomingFile>,
        val decision: CompletableDeferred<Set<String>?>,
        val isMessage: Boolean,
        val messageText: String?,
        @Volatile var accepted: Boolean = false,
    )
}
