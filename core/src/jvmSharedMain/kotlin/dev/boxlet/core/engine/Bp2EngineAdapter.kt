package dev.boxlet.core.engine

import dev.boxlet.core.crypto.SecurityContext
import dev.boxlet.core.net.Bp2DiscoveryService
import dev.boxlet.core.protocol.*
import dev.boxlet.core.protocol.bp2.*
import dev.boxlet.core.routing.AdapterState
import dev.boxlet.core.session.bp2.*
import dev.boxlet.core.transport.bp2.*
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import okio.Buffer
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

/** BP/2 adapter, sharing application storage/UI but no LocalSend wire handlers. */
internal class Bp2EngineAdapter(
    parent: CoroutineScope,
    private val security: SecurityContext,
    private val storage: ReceiveStorage,
    private val config: () -> EngineConfig,
    private val receiveGate: Mutex,
    private val legacyBusy: () -> Boolean,
    private val updateState: ((EngineState) -> EngineState) -> Unit,
    private val onDevice: (Device) -> Unit,
    private val onCompletion: suspend (ReceiveCompletion) -> Unit,
    private val sendJournal: Bp2SendJournal? = null,
) : AutoCloseable {
    private val job = SupervisorJob(parent.coroutineContext[Job])
    private val scope = CoroutineScope(parent.coroutineContext + job + Dispatchers.IO)
    private val stopped = AtomicBoolean(false)
    private val busy = AtomicBoolean(false)
    val isReceiving get() = busy.get()
    private var server: Bp2TlsServer? = null
    private var discovery: Bp2DiscoveryService? = null
    private var resumeStore: Bp2ResumeStore? = null
    var port: Int = 0
        private set
    private val sessions = ConcurrentHashMap.newKeySet<Bp2Session>()
    @Volatile private var sendingSession: Pair<String, Bp2Session>? = null
    private data class PendingSend(val id: String, val device: Device, val channel: DeviceChannel,
        val files: List<Bp2OutgoingFile>, val view: View, var ticket: Bp2ResumeTicket? = null,
        val sources: Map<String, Bp2SourceRef?> = emptyMap(), var expiresAt: Long = 0,
        @Volatile var durable: Boolean = false, val cancelled: AtomicBoolean = AtomicBoolean(false),
        val resetFiles: MutableSet<String> = ConcurrentHashMap.newKeySet())
    @Volatile private var retainedSend: PendingSend? = null
    @Volatile private var activeSend: PendingSend? = null
    private val sendActive = AtomicBoolean(false)
    private val continuePending = AtomicBoolean(false)
    private var resumedSendJob: Job? = null
    private val probes = ConcurrentHashMap<String, Long>()
    private val probePermits = Semaphore(2)
    private data class ReceiveControl(val id: String, val ids: Set<String>, val session: Bp2Session,
                                      val decision: CompletableDeferred<Set<String>>)
    @Volatile private var receiving: ReceiveControl? = null
    @Volatile private var retainedReceive: Pair<String, String>? = null
    @Volatile private var incomingView: View? = null
    @Volatile private var outgoingView: View? = null
    private data class Published(val path: String?, val message: String?, val timestamp: Long? = null)
    private val published = ConcurrentHashMap<String, Published>()
    private val publishLock = Any()

    private fun mutate(change: (EngineState) -> EngineState) { if (!stopped.get()) updateState(change) }

    /** O(1) per-file mutations, with immutable UI snapshots at most every 100 ms. */
    private class View(val id: String, initial: List<TransferFileState>) {
        private val lock = Any()
        private val files = initial.toMutableList()
        private val indexes = files.withIndex().associate { it.value.id to it.index }
        private var dirty = true
        fun update(fileId: String, transform: (TransferFileState) -> TransferFileState) = synchronized(lock) {
            indexes[fileId]?.let { files[it] = transform(files[it]); dirty = true }
        }
        fun snapshot(force: Boolean = false): List<TransferFileState>? = synchronized(lock) {
            if (!dirty && !force) null else files.toList().also { dirty = false }
        }
        fun terminate(status: TransferStatus, reason: String?) = synchronized(lock) {
            if (status == TransferStatus.COMPLETED) return@synchronized
            files.indices.forEach { index ->
                if (files[index].status in listOf(FileTransferStatus.QUEUED, FileTransferStatus.TRANSFERRING)) {
                    files[index] = files[index].copy(status = if (status == TransferStatus.DECLINED)
                        FileTransferStatus.SKIPPED else FileTransferStatus.FAILED, error = reason)
                }
            }
            dirty = true
        }
    }

    fun start(): AdapterState {
        resumeStore = runCatching { storage.bp2ResumeStore() }.getOrNull()
        resumeStore?.cleanup()
        scope.launch { while (isActive) { delay(60000); resumeStore?.cleanup() } }
        server = Bp2TlsServer(security, scope, ::acceptConnection)
        port = server!!.start(config().bp2Port)
        hello().validateNative(security.fingerprint.lowercase())
        restoreSend()
        var discoveryReady = false
        var reason: String? = null
        // Port=0 is the engine's isolated test/manual-connect mode, with no LAN announcements.
        if (config().port != 0) {
            val service = Bp2DiscoveryService(scope, {
                Bp2Announcement(deviceId = security.fingerprint.lowercase(), alias = config().alias,
                    deviceType = config().deviceType.name.lowercase(), tlsPort = port)
            }, config().networkWhitelist, config().networkBlacklist, ::discover) { error ->
                mutate { state -> state.copy(protocols = state.protocols.map {
                    if (it.adapterId == "boxlet-bp2") it.copy(reason = error.message) else it
                }) }
            }
            runCatching { service.start() }.onSuccess { discovery = service; discoveryReady = true }
                .onFailure { service.close(); reason = "BP/2 发现不可用：${it.message}" }
        }
        scope.launch { while (isActive) { delay(100); flushViews() } }
        return AdapterState("boxlet-bp2", discoveryReady = discoveryReady, sendReady = true, receiveReady = true, reason = reason)
    }

    private suspend fun acceptConnection(connection: Bp2TlsConnection) {
        val owner = CompletableDeferred<Bp2Session>()
        val session = Bp2Session.open(connection, hello(), false, handler(owner), scope)
        owner.complete(session); sessions += session
        try { verifiedPeer(session.peer); session.awaitClosed() } finally { sessions -= session }
    }

    private fun hello() = Bp2Hello(requestId = newBp2Id(), deviceId = security.fingerprint.lowercase(),
        alias = config().alias, deviceType = config().deviceType.name.lowercase(),
        features = listOf("send", "receive", "batch", "pause") + if (resumeStore != null) listOf("resume", "reset-file") else emptyList(),
        listenPort = port)

    fun announceNow() { discovery?.announceNow() }
    fun connectPeer(host: String, peerPort: Int, fingerprint: String) {
        require(host.isNotBlank() && host.length <= 253 && host.none { it.isWhitespace() })
        val announcement = Bp2Announcement(deviceId = fingerprint.lowercase(), alias = "Manual connection",
            deviceType = "desktop", tlsPort = peerPort).also { it.validate() }
        discover(host, announcement, true)
    }
    private fun discover(host: String, announcement: Bp2Announcement) = discover(host, announcement, false)
    private suspend fun openConnection(channel: DeviceChannel, fingerprint: String) =
        Bp2TlsClient.connect(channel.host, channel.port, security, fingerprint, scope)
    private fun discover(host: String, announcement: Bp2Announcement, manual: Boolean) {
        // The announcement pin must match TLS/HELLO before a device is exposed; it is never saved as trust.
        val fingerprint = announcement.deviceId
        if (stopped.get()) return
        val key = fingerprint
        if (probes.size >= 1024 && !probes.containsKey(key)) return
        val now = System.currentTimeMillis()
        val previous = probes.put(key, now)
        if (previous != null && now - previous < 4000) return
        // Drop busy discovery probes; the next announcement retries without an unbounded queue.
        if (!probePermits.tryAcquire()) return
        scope.launch {
            runCatching {
                val connection = openConnection(DeviceChannel(host, announcement.tlsPort, true, Bp2.ADAPTER_ID), fingerprint)
                val owner = CompletableDeferred<Bp2Session>()
                val session = Bp2Session.open(connection, hello(), true, handler(owner), scope)
                owner.complete(session)
                try { ensureActive(); verifiedPeer(session.peer) } finally { session.close() }
            }.onFailure { error ->
                if (manual && !stopped.get()) mutate { it.copy(error = "BP/2 连接失败：${error.message}") }
            }
        }.invokeOnCompletion { probePermits.release() }
    }
    private fun verifiedPeer(peer: Bp2Peer) {
        if (stopped.get()) return
        val peerPort = peer.hello.listenPort ?: return
        onDevice(Device(fingerprint = peer.fingerprint.uppercase(), alias = peer.hello.alias, version = "BP/2.0",
            deviceType = runCatching { DeviceType.valueOf(peer.hello.deviceType.uppercase()) }.getOrDefault(DeviceType.DESKTOP),
            channels = listOf(DeviceChannel(peer.host, peerPort, true, "boxlet-bp2", identityVerified = true)),
            lastSeen = System.currentTimeMillis()))
    }

    suspend fun send(device: Device, channel: DeviceChannel, files: List<OutgoingFile>) {
        val id = newBp2Id()
        val outgoing = files.map { file ->
            val modified = file.modified?.let {
                java.time.format.DateTimeFormatterBuilder().appendInstant(3).toFormatter().format(java.time.Instant.parse(it.toString()))
            }.orEmpty()
            val preview = file.preview.orEmpty().take(1000).let {
                if (it.isNotEmpty() && Character.isHighSurrogate(it.last())) it.dropLast(1) else it
            }
            Bp2OutgoingFile(Bp2Entry(newBp2Id(), if (file.isMessage) "text" else "file", file.fileName,
                file.size.toString(), file.mimeType, modified, preview), file.openSource)
        }
        val view = View(id, outgoing.map { it.entry.state() })
        outgoingView = view
        mutate { it.copy(outgoing = OutgoingTransfer(id, device, files = view.snapshot(true)!!, adapterId = "boxlet-bp2")) }
        check(sendActive.compareAndSet(false, true)) { "BUSY" }
        activeSend = PendingSend(id, device, channel, outgoing, view, sources = outgoing.zip(files).associate { it.first.entry.fileId to it.second.recoverySource })
        try { runSend(activeSend!!) } finally { activeSend = null; sendActive.set(false) }
    }

    private suspend fun runSend(task: PendingSend) {
        val (id, device, channel, outgoing, view) = task
        var attempt = 0
        while (true) {
            var session: Bp2Session? = null
            try {
                currentCoroutineContext().ensureActive()
                val connection = openConnection(channel, device.fingerprint)
                val owner = CompletableDeferred<Bp2Session>()
                session = Bp2Session.open(connection, hello(), true, handler(owner), scope)
                owner.complete(session); sessions += session
                sendingSession = id to session
                verifiedPeer(session.peer)
                mutate { state -> state.copy(outgoing = state.outgoing?.takeIf { it.sessionId == id }?.copy(
                    status = TransferStatus.WAITING_FOR_ACCEPT, error = null) ?: state.outgoing) }
                session.send(outgoing, id, task.ticket,
                    resetFiles = task.resetFiles.toSet(), onFileReset = { fileId ->
                        task.resetFiles.remove(fileId)
                        view.update(fileId) { it.copy(bytesTransferred = 0, status = FileTransferStatus.QUEUED, error = null, restartRequired = false) }
                        flushViews()
                    },
                    preserveOnCancellation = { stopped.get() && task.durable && !task.cancelled.get() }) { persistTicket(task, it) }
                clearJournal(id)
                retainedSend = null
                return
            } catch (error: Throwable) {
                if (stopped.get() && task.durable && !task.cancelled.get()) return
                val certificateFailure = generateSequence(error) { it.cause }.any { it is java.security.cert.CertificateException }
                val disconnected = !certificateFailure && (error is Bp2ConnectionLost || (attempt > 0 &&
                    (error is java.net.SocketException || error is java.net.SocketTimeoutException || error is javax.net.ssl.SSLException)))
                val busy = error is java.io.IOException && error.message == "BUSY"
                if (error is Bp2SourcePrefixChanged && task.ticket != null) {
                    retainedSend = task
                    view.update(error.fileId) { it.copy(error = "源文件已变化", restartRequired = true) }
                    flushViews()
                    mutate { state -> state.copy(outgoing = state.outgoing?.takeIf { it.sessionId == id }?.copy(
                        status = TransferStatus.PAUSED, error = "源文件已变化，请恢复原内容，或选择使用当前内容从头重传该文件") ?: state.outgoing) }
                    return
                }
                if (error is Bp2SourceUnavailable && task.ticket != null) {
                    retainedSend = task
                    mutate { state -> state.copy(outgoing = state.outgoing?.takeIf { it.sessionId == id }?.copy(
                        status = TransferStatus.PAUSED, error = "源文件不可访问，请恢复读取权限或原文件后继续") ?: state.outgoing) }
                    return
                }
                if ((disconnected || busy) && task.ticket != null) {
                    if (busy) view.snapshot(true)!!.filter { it.status == FileTransferStatus.FAILED }.forEach { file ->
                        view.update(file.id) { it.copy(status = FileTransferStatus.QUEUED, error = null) }
                    }
                    session?.close(); session?.let { sessions -= it }; sendingSession = null
                    withContext(NonCancellable) { withTimeoutOrNull(5000) { session?.awaitClosed() } }
                    mutate { state -> state.copy(outgoing = state.outgoing?.takeIf { it.sessionId == id }?.copy(
                        status = TransferStatus.PAUSED, error = if (attempt < 3) "连接中断，正在重连…" else "连接中断，可继续传输") ?: state.outgoing) }
                    if (attempt < 3) {
                        delay((500L shl attempt) + kotlin.random.Random.nextLong(100)); attempt++; continue
                    }
                    retainedSend = task
                    return
                }
                retainedSend = null
                clearJournal(id)
                val status = when (error) {
                    is CancellationException -> TransferStatus.CANCELLED
                    is Bp2Rejected -> if (error.message?.contains("BUSY") == true) TransferStatus.BUSY else TransferStatus.DECLINED
                    else -> TransferStatus.FAILED
                }
                view.terminate(status, error.message)
                flushViews()
                mutate { state -> state.copy(outgoing = state.outgoing?.takeIf { it.sessionId == id }?.copy(
                    status = status, error = error.message) ?: state.outgoing) }
                if (error is CancellationException) throw error
            } finally { sendingSession = null; session?.close(); session?.let { sessions -= it }; flushViews() }
            return
        }
    }

    fun setPaused(outgoing: Boolean, paused: Boolean): Boolean {
        if (stopped.get()) return false
        if (outgoing && retainedSend != null) {
            if (paused) return true
            val task = retainedSend ?: return false
            if (sendActive.compareAndSet(false, true)) {
                retainedSend = null
                activeSend = task
                resumedSendJob = scope.launch { try { runSend(task) } finally { activeSend = null; sendActive.set(false) } }
            } else if (continuePending.compareAndSet(false, true)) {
                // A PAUSED snapshot can arrive just before the old sender finishes cleanup.
                scope.launch {
                    try {
                        while (sendActive.get() && retainedSend === task && !task.cancelled.get()) delay(10)
                        if (retainedSend === task && !task.cancelled.get()) setPaused(true, false)
                    } finally { continuePending.set(false) }
                }
            }
            return true
        }
        if (outgoing && sendingSession == null && sendActive.get() && !paused) return true
        val selected = if (outgoing) sendingSession else receiving?.let { it.id to it.session }
        selected ?: return false
        scope.launch {
            runCatching { selected.second.setPaused(selected.first, paused) }
                .onFailure { error -> if (isActive) mutate { it.copy(error = "BP/2 暂停/继续失败：${error.message}") } }
        }
        return true
    }

    fun cancelRetainedSend() {
        val task = retainedSend ?: activeSend ?: return
        task.cancelled.set(true)
        clearJournal(task.id)
        resumedSendJob?.cancel()
        retainedSend = null
        task.view.terminate(TransferStatus.CANCELLED, "已取消"); flushViews()
        val ticket = task.ticket ?: return
        if (sendingSession != null) return // The active sender sends the authenticated CANCEL on its own connection.
        scope.launch {
            runCatching {
                val connection = openConnection(task.channel, task.device.fingerprint)
                val owner = CompletableDeferred<Bp2Session>()
                val session = Bp2Session.open(connection, hello(), true, handler(owner), scope); owner.complete(session)
                try { session.cancelRetained(ticket) } finally { session.close() }
            }.onFailure { error -> if (isActive) mutate { it.copy(error = "本机已取消；离线对端的保留进度将在凭证过期后清理：${error.message}") } }
        }
    }

    fun resetFile(fileId: String): Boolean {
        val task = retainedSend ?: return false
        if (stopped.get() || task.cancelled.get() || task.ticket == null) return false
        if (task.view.snapshot(true)!!.none { it.id == fileId && it.restartRequired }) return false
        task.resetFiles.add(fileId)
        return setPaused(true, false)
    }

    private fun persistTicket(task: PendingSend, ticket: Bp2ResumeTicket) {
        task.ticket = ticket
        val journal = sendJournal ?: return
        if (stopped.get() || task.cancelled.get() || ticket.accepted.any { task.sources[it] == null }) return
        if (task.expiresAt == 0L) task.expiresAt = System.currentTimeMillis() + Bp2CheckpointFile.LIFETIME_MS
        runCatching {
            journal.save(Bp2SendSnapshot(task.id, task.device.copy(channels = listOf(task.channel)), task.channel,
                ticket.manifestHash, ticket.resumeToken, ticket.accepted,
                task.files.map { Bp2SavedSource(it.entry.copy(streamId = null), task.sources[it.entry.fileId]) }, task.expiresAt))
            task.durable = true
        }.onFailure { mutate { it.copy(error = "发送恢复记录保存失败，本次仅支持进程内续传") } }
    }

    private fun clearJournal(id: String) {
        runCatching { sendJournal?.clear(id) }.onFailure { mutate { it.copy(error = "发送恢复记录清理失败") } }
    }

    private fun restoreSend() {
        val journal = sendJournal ?: return
        val saved = runCatching { journal.load() }.getOrElse {
            runCatching { journal.quarantine() }
            mutate { it.copy(error = "发送恢复记录无法验证，已隔离，未恢复任务") }
            return
        } ?: return
        val files = saved.files.map { Bp2OutgoingFile(it.entry, journal.source(it)) }
        val view = View(saved.transferId, files.map { file -> file.entry.state().let {
            if (file.entry.fileId in saved.accepted) it else it.copy(status = FileTransferStatus.SKIPPED)
        } })
        outgoingView = view
        retainedSend = PendingSend(saved.transferId, saved.device, saved.channel, files, view, saved.ticket(),
            saved.files.associate { it.entry.fileId to it.reference }, saved.expiresAt, durable = true)
        mutate { it.copy(outgoing = OutgoingTransfer(saved.transferId, saved.device, status = TransferStatus.PAUSED,
            files = view.snapshot(true)!!, adapterId = Bp2.ADAPTER_ID, error = "已恢复发送任务，继续前将验证源文件与对端进度")) }
    }

    fun accept(ids: Set<String>? = null): Boolean {
        val current = receiving ?: return false
        current.decision.complete(ids ?: current.ids)
        return true
    }
    fun reject(): Boolean = accept(emptySet())
    fun cancelReceive(): Boolean {
        val current = receiving
        if (current == null) {
            val retained = retainedReceive ?: return false
            retainedReceive = null
            scope.launch {
                runCatching { resumeStore?.cancelLocal(retained.second, retained.first) }
                    .onFailure { error -> if (isActive) mutate { it.copy(error = "保留任务清理失败：${error.message}") } }
                incomingView?.takeIf { it.id == retained.first }?.terminate(TransferStatus.CANCELLED, "已取消"); flushViews()
                mutate { state -> state.copy(incoming = state.incoming?.takeIf { it.sessionId == retained.first }?.copy(status = TransferStatus.CANCELLED) ?: state.incoming) }
            }
            return true
        }
        scope.launch { runCatching { current.session.cancelIncoming(current.id) } }
        return true
    }

    private fun handler(owner: CompletableDeferred<Bp2Session>) = object : Bp2SessionHandler {
        override val resumeStore get() = this@Bp2EngineAdapter.resumeStore
        override suspend fun onAuthorizedResume(peer: Bp2Peer, transferId: String) {
            val previous = receiving?.takeIf { it.id == transferId && it.session.peer.fingerprint == peer.fingerprint } ?: return
            if (previous.session === owner.await()) return
            previous.session.close()
            withTimeout(5000) { previous.session.awaitClosed() }
        }
        override suspend fun onResuming(peer: Bp2Peer, transferId: String, entries: List<Bp2Entry>, positions: List<Bp2ResumePosition>) {
            if (!receiveGate.tryLock()) error("BUSY")
            try {
                if (legacyBusy() || !busy.compareAndSet(false, true)) error("BUSY")
                val selected = positions.map { it.fileId }.toSet()
                receiving = ReceiveControl(transferId, selected, owner.await(), CompletableDeferred<Set<String>>().also { it.complete(selected) })
                retainedReceive = null
            } finally { receiveGate.unlock() }
            val view = View(transferId, entries.map { it.state() }); incomingView = view
            val selected = positions.map { it.fileId }.toSet()
            entries.filter { it.fileId !in selected }.forEach { entry -> view.update(entry.fileId) { it.copy(status = FileTransferStatus.SKIPPED) } }
            mutate { it.copy(pendingReceive = null, incoming = IncomingTransfer(transferId, Device(peer.fingerprint.uppercase(), peer.hello.alias),
                files = view.snapshot(true)!!, adapterId = Bp2.ADAPTER_ID)) }
            onResumePositions(transferId, positions, false)
        }
        override fun onResumePositions(transferId: String, positions: List<Bp2ResumePosition>, outgoing: Boolean) {
            val view = (if (outgoing) outgoingView else incomingView)?.takeIf { it.id == transferId } ?: return
            positions.forEach { position -> view.update(position.fileId) { it.copy(bytesTransferred = Bp2.decimal(position.offset), error = null, restartRequired = false,
                status = if (position.completedRoot != null) FileTransferStatus.COMPLETED else FileTransferStatus.QUEUED) } }
            flushViews()
        }
        override suspend fun onFilePublished(peer: Bp2Peer, transferId: String, entry: Bp2Entry, publication: Bp2PublishedFile?) {
            publication?.let { published["$transferId:${entry.fileId}"] = Published(it.path, null, it.timestamp) }
            onReceived(peer, transferId, entry)
        }
        override suspend fun onOffer(peer: Bp2Peer, transferId: String, entries: List<Bp2Entry>): Set<String> {
            if (!receiveGate.tryLock()) error("BUSY")
            try {
                if (legacyBusy() || !busy.compareAndSet(false, true)) error("BUSY")
                receiving = ReceiveControl(transferId, entries.map { it.fileId }.toSet(), owner.await(), CompletableDeferred())
                retainedReceive = null
            } finally { receiveGate.unlock() }
            val view = View(transferId, entries.map { it.state() })
            incomingView = view
            val device = Device(peer.fingerprint.uppercase(), peer.hello.alias)
            val only = entries.singleOrNull()?.takeIf { it.kind == "text" }
            mutate { state -> state.copy(pendingReceive = PendingReceive(transferId, device, entries.map { it.dto() },
                only != null, only?.preview), incoming = IncomingTransfer(transferId, device,
                    status = TransferStatus.WAITING_FOR_ACCEPT, files = view.snapshot(true)!!, adapterId = "boxlet-bp2")) }
            val current = receiving ?: error("Receive cancelled")
            val settings = config()
            if (settings.quickSave || (settings.quickSaveFromFavorites && settings.autoAccept(device))) current.decision.complete(current.ids)
            val selected = current.decision.await()
            require(selected.all { it in current.ids })
            entries.filter { it.fileId !in selected }.forEach { entry -> view.update(entry.fileId) { it.copy(status = FileTransferStatus.SKIPPED) } }
            mutate { state -> state.copy(pendingReceive = state.pendingReceive?.takeUnless { it.sessionId == transferId },
                incoming = state.incoming?.takeIf { it.sessionId == transferId }?.copy(status = TransferStatus.TRANSFERRING,
                    files = view.snapshot(true)!!) ?: state.incoming) }
            return selected
        }
        override suspend fun createDestination(peer: Bp2Peer, transferId: String, entry: Bp2Entry): Bp2Destination {
            val key = "$transferId:${entry.fileId}"
            if (entry.kind == "text") {
                val buffer = Buffer()
                return object : Bp2Destination {
                    override val sink = buffer
                    override suspend fun commit() { published[key] = Published(null, buffer.readByteArray().decodeToString(throwOnInvalidSequence = true)) }
                    override suspend fun abort() { buffer.clear(); published.remove(key) }
                }
            }
            // Conversion is only the existing platform-storage boundary, not the BP/2 wire contract.
            val destination = storage.createDestination(transferId, entry.dto(), peer.hello.alias)
            return object : Bp2Destination {
                override val sink = destination.sink
                override suspend fun commit() { destination.commit(); published[key] = Published(destination.displayPath, null) }
                override suspend fun abort() { destination.abort(); published.remove(key) }
            }
        }
        override fun onProgress(transferId: String, fileId: String, written: Long, outgoing: Boolean) {
            val view = (if (outgoing) outgoingView else incomingView)?.takeIf { it.id == transferId } ?: return
            view.update(fileId) { it.copy(bytesTransferred = maxOf(it.bytesTransferred, written), status = FileTransferStatus.TRANSFERRING) }
        }
        override fun onAccepted(transferId: String, accepted: Set<String>) {
            val view = outgoingView?.takeIf { it.id == transferId } ?: return
            view.snapshot(true)!!.filter { it.id !in accepted }.forEach { file -> view.update(file.id) { it.copy(status = FileTransferStatus.SKIPPED) } }
            mutate { state -> state.copy(outgoing = state.outgoing?.takeIf { it.sessionId == transferId }?.copy(
                status = TransferStatus.TRANSFERRING, files = view.snapshot(true)!!) ?: state.outgoing) }
        }
        override fun onSent(transferId: String, entry: Bp2Entry) {
            outgoingView?.takeIf { it.id == transferId }?.update(entry.fileId) {
                it.copy(status = FileTransferStatus.COMPLETED, bytesTransferred = Bp2.decimal(entry.size))
            }
        }
        override fun onPaused(transferId: String, paused: Boolean, outgoing: Boolean) {
            val status = if (paused) TransferStatus.PAUSED else TransferStatus.TRANSFERRING
            mutate { state -> if (outgoing) state.copy(outgoing = state.outgoing?.let {
                if (it.sessionId == transferId && it.status in listOf(TransferStatus.TRANSFERRING, TransferStatus.PAUSED)) it.copy(status = status) else it
            }) else state.copy(incoming = state.incoming?.let {
                if (it.sessionId == transferId && it.status in listOf(TransferStatus.TRANSFERRING, TransferStatus.PAUSED)) it.copy(status = status) else it
            }) }
        }
        override suspend fun onReceived(peer: Bp2Peer, transferId: String, entry: Bp2Entry) {
            val result = published.remove("$transferId:${entry.fileId}") ?: error("Missing publication result")
            incomingView?.takeIf { it.id == transferId }?.update(entry.fileId) {
                it.copy(status = FileTransferStatus.COMPLETED, bytesTransferred = Bp2.decimal(entry.size), savedPath = result.path)
            }
            onCompletion(ReceiveCompletion(transferId, peer.hello.alias, entry.dto().copy(preview = result.message), result.path,
                result.message, timestamp = result.timestamp ?: System.currentTimeMillis(), adapterId = "boxlet-bp2",
                completionId = "bp2:${peer.fingerprint}:$transferId:${entry.fileId}"))
        }
        override fun onTransferEnded(transferId: String, status: String, outgoing: Boolean) {
            if (outgoing && status in listOf("completed", "partial")) clearJournal(transferId)
            val mapped = when (status) {
                "completed", "partial" -> TransferStatus.COMPLETED
                "cancelled" -> TransferStatus.CANCELLED
                "rejected" -> TransferStatus.DECLINED
                "busy" -> TransferStatus.BUSY
                "disconnected" -> TransferStatus.PAUSED
                else -> TransferStatus.FAILED
            }
            if (mapped != TransferStatus.PAUSED) (if (outgoing) outgoingView else incomingView)?.takeIf { it.id == transferId }
                ?.terminate(mapped, if (mapped == TransferStatus.CANCELLED) "已取消" else null)
            flushViews()
            mutate { state -> if (outgoing) state.copy(outgoing = state.outgoing?.takeIf { it.sessionId == transferId }
                ?.copy(status = mapped) ?: state.outgoing) else state.copy(
                    pendingReceive = state.pendingReceive?.takeUnless { it.sessionId == transferId },
                    incoming = state.incoming?.takeIf { it.sessionId == transferId }?.copy(status = mapped) ?: state.incoming) }
            if (!outgoing && receiving?.id == transferId) {
                if (mapped == TransferStatus.PAUSED) retainedReceive = transferId to receiving!!.session.peer.fingerprint
                receiving?.decision?.cancel(); receiving = null; busy.set(false)
                published.keys.removeAll { it.startsWith("$transferId:") }
            }
        }
    }

    private fun flushViews() = synchronized(publishLock) {
        val incoming = incomingView; val received = incoming?.snapshot()
        val outgoing = outgoingView; val sent = outgoing?.snapshot()
        if (received == null && sent == null) return@synchronized
        mutate { state -> state.copy(
            incoming = if (received != null && state.incoming?.sessionId == incoming.id) state.incoming.copy(files = received) else state.incoming,
            outgoing = if (sent != null && state.outgoing?.sessionId == outgoing.id) state.outgoing.copy(files = sent) else state.outgoing,
        ) }
    }
    override fun close() {
        if (!stopped.compareAndSet(false, true)) return
        // Closed-session callbacks cannot update a newer adapter's task with the same ID.
        // Publish the stopped state here before those callbacks are suppressed.
        val retained = (activeSend ?: retainedSend)?.takeIf { it.durable && !it.cancelled.get() }
        val active = setOf(TransferStatus.PREPARING, TransferStatus.WAITING_FOR_ACCEPT, TransferStatus.TRANSFERRING, TransferStatus.PAUSED)
        updateState { state -> state.copy(
            outgoing = state.outgoing?.let {
                if (it.adapterId != Bp2.ADAPTER_ID || it.status !in active) it
                else it.copy(status = if (it.sessionId == retained?.id) TransferStatus.PAUSED else TransferStatus.CANCELLED,
                    error = if (it.sessionId == retained?.id) "引擎已停止，重新启用 BP/2 后可继续" else "引擎已停止")
            },
            incoming = state.incoming?.let {
                if (it.adapterId == Bp2.ADAPTER_ID && it.status in active) it.copy(status = TransferStatus.CANCELLED, error = "连接已关闭") else it
            },
        ) }
        discovery?.close(); server?.close(); sessions.forEach { it.close() }; job.cancel()
        receiving?.decision?.cancel(); receiving = null; busy.set(false)
    }
}

private fun Bp2Entry.dto() = FileDto(fileId, path, Bp2.decimal(size), mime,
    preview = preview.takeIf { kind == "text" }, metadata = modified.takeIf { it.isNotEmpty() }?.let { FileMetadata(modified = it) })
private fun Bp2Entry.state() = TransferFileState(fileId, path, Bp2.decimal(size), mime, isMessage = kind == "text")
