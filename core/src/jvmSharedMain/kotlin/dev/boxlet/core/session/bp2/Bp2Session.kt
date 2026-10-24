package dev.boxlet.core.session.bp2

import dev.boxlet.core.protocol.bp2.*
import dev.boxlet.core.transport.bp2.Bp2TlsConnection
import dev.boxlet.core.transport.bp2.Bp2ConnectionLost
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.*
import okio.Source
import okio.buffer
import java.io.IOException
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

fun newBp2Id(): String = UUID.randomUUID().toString().replace("-", "")

data class Bp2Peer(val hello: Bp2Hello, val fingerprint: String, val host: String)
data class Bp2OutgoingFile(val entry: Bp2Entry, val openSource: () -> Source)
data class Bp2SendResult(val transferId: String, val completed: Set<String>, val rejected: Set<String>)
class Bp2Rejected(message: String) : IOException(message)
class Bp2SourcePrefixChanged(val fileId: String) : IOException("SOURCE_CHANGED")

interface Bp2SessionHandler {
    val resumeStore: Bp2ResumeStore? get() = null
    suspend fun onOffer(peer: Bp2Peer, transferId: String, entries: List<Bp2Entry>): Set<String>
    suspend fun createDestination(peer: Bp2Peer, transferId: String, entry: Bp2Entry): Bp2Destination
    fun onProgress(transferId: String, fileId: String, written: Long, outgoing: Boolean) {}
    fun onAccepted(transferId: String, accepted: Set<String>) {}
    fun onSent(transferId: String, entry: Bp2Entry) {}
    fun onPaused(transferId: String, paused: Boolean, outgoing: Boolean) {}
    suspend fun onResuming(peer: Bp2Peer, transferId: String, entries: List<Bp2Entry>, positions: List<Bp2ResumePosition>) {}
    suspend fun onAuthorizedResume(peer: Bp2Peer, transferId: String) {}
    fun onResumePositions(transferId: String, positions: List<Bp2ResumePosition>, outgoing: Boolean) {}
    suspend fun onFilePublished(peer: Bp2Peer, transferId: String, entry: Bp2Entry, publication: Bp2PublishedFile?) {
        onReceived(peer, transferId, entry)
    }
    suspend fun onReceived(peer: Bp2Peer, transferId: String, entry: Bp2Entry) {}
    fun onTransferEnded(transferId: String, status: String, outgoing: Boolean) {}
}

/** One duplex session. Only the actor mutates incoming transfers; user confirmation runs separately. */
class Bp2Session private constructor(
    private val connection: Bp2TlsConnection,
    val peer: Bp2Peer,
    private val local: Bp2Hello,
    private val initiator: Boolean,
    private val handler: Bp2SessionHandler,
    parent: CoroutineScope,
) : AutoCloseable {
    private val job = SupervisorJob(parent.coroutineContext[Job])
    private val scope = CoroutineScope(parent.coroutineContext + job + Dispatchers.IO)
    private val inbox = Channel<Event>(8)
    private val stopped = CompletableDeferred<Unit>()
    private val closed = AtomicBoolean(false)
    @Volatile var terminationCause: Throwable? = null
        private set
    private val sendMutex = Mutex()
    private val creditMutex = Mutex()
    private val sendCredit = Bp2SendCredit(Bp2.decimal(peer.hello.limits.receiveWindow))
    private val receiveCredit = Bp2ReceiveCredit(Bp2.decimal(local.limits.receiveWindow))
    private val signal = MutableStateFlow(0L)
    private var nextStream = if (initiator) 1L else 2L
    @Volatile private var outgoing: Outgoing? = null
    private var incoming: Incoming? = null
    private val usedTransfers = linkedSetOf<String>()
    @Volatile private var lastReceived = System.nanoTime()
    private var lastRemoteStream = 0L
    private val refused = mutableMapOf<String, String>()
    private val cancelledStreams = mutableMapOf<Long, Long>()
    private data class PauseRequest(val id: String, val paused: Boolean, val done: CompletableDeferred<Unit>)
    private val pauseRequests = mutableMapOf<String, PauseRequest>()
    private var retainedCancellation: Pair<String, CompletableDeferred<Unit>>? = null

    private sealed interface Event {
        data class Wire(val frame: Bp2Frame) : Event
        data class Decision(val id: String, val accepted: Set<String>?, val error: Throwable?) : Event
        data class Cancel(val id: String, val done: CompletableDeferred<Unit>) : Event
        data class Pause(val request: PauseRequest) : Event
    }
    private data class ChunkKey(val stream: Long, val offset: Long)
    private class PendingChunk(val payload: ByteArray) { var attempts = 1 }
    private class Outgoing(val id: String, val entries: Map<String, Bp2Entry>) {
        @Volatile var paused = false
        val streams = entries.values.associateBy { requireNotNull(it.streamId) }
        val accepted = linkedSetOf<String>()
        var acceptPage = 0
        val decision = CompletableDeferred<Set<String>>()
        val finished = ConcurrentHashMap<String, CompletableDeferred<Unit>>()
        val transferDone = CompletableDeferred<Unit>()
        val chunks = ConcurrentHashMap<ChunkKey, PendingChunk>()
        val retries = Channel<ChunkKey>(32)
        val repairCount = AtomicInteger()
        val failure = AtomicReference<Throwable?>()
        var manifestHash = ""
        var onTicket: (Bp2ResumeTicket) -> Unit = {}
        var expectedReply: String? = null
        val resumePositions = linkedMapOf<String, Bp2ResumePosition>()
        var resumePage = 0
        val resumeReady = CompletableDeferred<Unit>()
        val resets = ConcurrentHashMap<String, Pair<String, CompletableDeferred<Bp2ResumePosition>>>()
        fun check() { failure.get()?.let { throw it } }
        fun fail(error: Throwable) {
            if (!failure.compareAndSet(null, error)) return
            decision.completeExceptionally(error); transferDone.completeExceptionally(error)
            resumeReady.completeExceptionally(error)
            resets.values.forEach { it.second.completeExceptionally(error) }
            finished.values.forEach { it.completeExceptionally(error) }
        }
    }
    private class Incoming(val id: String, val count: Int, val total: Long, val streamFloor: Long) {
        val entries = linkedMapOf<String, Bp2Entry>()
        val streams = mutableSetOf<Long>()
        val streamEntries = mutableMapOf<Long, Bp2Entry>()
        val pageHashes = mutableMapOf<Int, String>()
        val manifest = Bp2ManifestDigest()
        var bytes = 0L
        var nextPage = 0
        var sealed = false
        var accepted = false
        var complete = false
        var endRequest = ""
        var decisionJob: Job? = null
        var manifestHash = ""
        var retained: Bp2ResumeLease? = null
        var resumePositions: Map<String, Bp2ResumePosition> = emptyMap()
        var readyPage = 0
        val readyFiles = mutableSetOf<String>()
        val selected = linkedSetOf<String>()
        val files = mutableMapOf<Long, Receiving>()
        val results = mutableMapOf<String, String>()
    }
    private class Receiving(val entry: Bp2Entry, val writer: Bp2IncomingFile) {
        val pendingCredit = java.util.TreeMap<Long, Long>() // chunk offset -> received frame sequence
    }

    companion object {
        suspend fun open(connection: Bp2TlsConnection, local: Bp2Hello, initiator: Boolean,
                         handler: Bp2SessionHandler, scope: CoroutineScope): Bp2Session = coroutineScope {
            val deadline = launch { delay(10000); connection.close() }
            val committed = AtomicBoolean(false)
            val cancellation = launch(start = CoroutineStart.UNDISPATCHED) {
                try { awaitCancellation() } finally { if (!committed.get()) connection.close() }
            }
            try {
                val remote = if (initiator) {
                    connection.sendControl(Bp2.json.encodeToJsonElement(local).jsonObject)
                    val frame = connection.receive() ?: throw Bp2ConnectionLost()
                    require(frame.header.kind == Bp2Kind.CONTROL)
                    val ack = Bp2.json.decodeFromJsonElement<Bp2HelloAck>(Bp2Controls.decode(frame.payload))
                    require(ack.type == "hello-ack" && ack.replyTo == local.requestId && ack.minor <= local.minor)
                    Bp2.requireId(ack.connectionId)
                    ack.asHello()
                } else {
                    val frame = connection.receive() ?: throw Bp2ConnectionLost()
                    require(frame.header.kind == Bp2Kind.CONTROL)
                    val hello = Bp2.json.decodeFromJsonElement<Bp2Hello>(Bp2Controls.decode(frame.payload))
                    hello.validateNative(connection.peerFingerprint)
                    val ack = Bp2HelloAck(replyTo = hello.requestId, connectionId = newBp2Id(),
                        minor = minOf(local.minor, hello.minor), deviceId = local.deviceId, alias = local.alias,
                        deviceType = local.deviceType, features = local.features, limits = local.limits,
                        listenPort = local.listenPort)
                    connection.sendControl(Bp2.json.encodeToJsonElement(ack).jsonObject)
                    hello
                }
                remote.validateNative(connection.peerFingerprint)
                Bp2Session(connection, Bp2Peer(remote, connection.peerFingerprint, connection.remoteHost),
                    local, initiator, handler, scope).also { it.start(); committed.set(true) }
            } catch (error: Throwable) { connection.close(); throw error }
            finally { deadline.cancel(); cancellation.cancel() }
        }
    }

    private fun start() {
        scope.launch(start = CoroutineStart.UNDISPATCHED) {
            var failure: Throwable = Bp2ConnectionLost()
            try {
                for (event in inbox) when (event) {
                    is Event.Wire -> process(event.frame)
                    is Event.Decision -> decide(event)
                    is Event.Cancel -> {
                        if (incoming?.id == event.id) discardIncoming("cancelled")
                        command("cancel", event.id) { put("reason", "Cancelled by receiver") }
                        event.done.complete(Unit)
                    }
                    is Event.Pause -> {
                        val request = event.request
                        if (!activeTransfer(request.id) || pauseRequests.size >= 8) {
                            request.done.completeExceptionally(IllegalStateException("Transfer is not active"))
                        } else {
                            // Stop local scheduling immediately; the peer may still deliver bounded in-flight frames.
                            if (request.paused) outgoing?.takeIf { it.id == request.id }?.paused = true
                            wake()
                            val control = message(if (request.paused) "pause" else "continue", request.id)
                            pauseRequests[control.str("requestId")] = request
                            connection.sendControl(control)
                        }
                    }
                }
            } catch (error: Throwable) { failure = error }
            finally {
                terminationCause = failure
                outgoing?.fail(failure); wake()
                retainedCancellation?.second?.completeExceptionally(failure)
                pauseRequests.values.forEach { it.done.completeExceptionally(failure) }; pauseRequests.clear()
                try { withContext(NonCancellable) { runCatching { discardIncoming(if (failure is Bp2ConnectionLost || failure is CancellationException) "disconnected" else "failed") } } }
                finally { close(); stopped.complete(Unit) }
            }
        }
        scope.launch {
            try {
                while (isActive) {
                    val frame = connection.receive() ?: break
                    lastReceived = System.nanoTime()
                    if (frame.header.kind == Bp2Kind.CONTROL) {
                        val control = Bp2Controls.decode(frame.payload)
                        when (control.str("type")) {
                            "ping" -> { connection.sendControl(message("pong") { put("replyTo", control.str("requestId")) }); continue }
                            "pong" -> continue
                        }
                    }
                    inbox.send(Event.Wire(frame))
                }
                inbox.close(Bp2ConnectionLost())
            } catch (error: Throwable) { inbox.close(error) }
        }
        scope.launch {
            while (isActive) {
                delay(15000)
                if (System.nanoTime() - lastReceived >= 30_000_000_000L) { close(); break }
                runCatching { connection.sendControl(message("ping")) }.onFailure { close() }
            }
        }
    }

    suspend fun send(files: List<Bp2OutgoingFile>, transferId: String = newBp2Id(), resume: Bp2ResumeTicket? = null,
                     resetFiles: Set<String> = emptySet(), onFileReset: (String) -> Unit = {},
                     preserveOnCancellation: () -> Boolean = { false },
                     onResumeTicket: (Bp2ResumeTicket) -> Unit = {}): Bp2SendResult = sendMutex.withLock {
        check(!closed.get()); require("receive" in peer.hello.features)
        require(files.isNotEmpty() && files.size <= peer.hello.limits.maxManifestEntries)
        Bp2.requireId(transferId)
        require(resetFiles.isEmpty() || (resume != null && resume.accepted.containsAll(resetFiles)))
        val assigned = files.map { file ->
            require(nextStream <= 0xffffffffL)
            file.copy(entry = file.entry.copy(streamId = nextStream.also { nextStream += 2 }))
        }
        assigned.forEach { it.entry.validate(requireStream = true); require(it.entry.kind != "directory") }
        require(assigned.map { it.entry.fileId }.distinct().size == assigned.size)
        val entries = assigned.associate { it.entry.fileId to it.entry }
        val context = Outgoing(transferId, entries)
        val manifest = Bp2ManifestDigest(); assigned.forEach { manifest.add(it.entry) }
        context.manifestHash = manifest.finish(); context.onTicket = onResumeTicket
        outgoing = context
        try {
            coroutineScope {
                val repair = launch {
                    for (key in context.retries) {
                        val pending = context.chunks[key]
                        try {
                            if (pending != null) {
                                require(++pending.attempts <= 3) { "CHECKSUM_MISMATCH: retry limit" }
                                reserve(context, pending.payload.size - 32, true)
                                connection.send(Bp2Kind.DATA, key.stream, key.offset, pending.payload)
                            }
                        } catch (error: Throwable) { context.fail(error); wake() }
                        finally { context.repairCount.decrementAndGet(); wake() }
                    }
                }
                try {
                    var total = 0L
                    assigned.forEach { val n = Bp2.decimal(it.entry.size); require(total <= Long.MAX_VALUE - n); total += n }
                    if (resume == null) {
                        command("offer-begin", transferId) { put("entryCount", assigned.size); put("totalBytes", total.toString()) }
                        sendPages("offer-page", transferId, assigned.map { Bp2.json.encodeToJsonElement(it.entry) }, "entries")
                        val end = message("offer-end", transferId) { put("manifestHash", context.manifestHash) }
                        context.expectedReply = end.str("requestId"); connection.sendControl(end)
                    } else {
                        require("resume" in peer.hello.features && resume.transferId == transferId && resume.manifestHash == context.manifestHash)
                        val request = message("resume", transferId) { put("manifestHash", resume.manifestHash); put("resumeToken", resume.resumeToken) }
                        context.expectedReply = request.str("requestId"); connection.sendControl(request)
                        withTimeout(120000) { context.resumeReady.await() }
                        require(context.resumePositions.keys == resume.accepted)
                        // Completed results remain valid if another source check pauses before READY.
                        handler.onResumePositions(transferId, context.resumePositions.values.toList(), true)
                        for (fileId in resetFiles) {
                            require("reset-file" in peer.hello.features) { "UNSUPPORTED_MESSAGE: reset-file" }
                            val position = context.resumePositions.getValue(fileId)
                            require(position.completedRoot == null) { "FILE_ALREADY_COMPLETED" }
                            if (position.offset != "0") {
                                val request = message("reset-file", transferId) {
                                    put("fileId", fileId); put("offset", position.offset); put("prefixRoot", position.prefixRoot)
                                }
                                val response = CompletableDeferred<Bp2ResumePosition>()
                                val requestId = request.str("requestId")
                                context.resets[requestId] = fileId to response
                                try {
                                    connection.sendControl(request)
                                    context.resumePositions[fileId] = withTimeout(30000) { response.await() }
                                } finally { context.resets.remove(requestId) }
                            }
                            onFileReset(fileId)
                        }
                        // Validate before READY, then again on the actual reopened source used for DATA.
                        assigned.filter { it.entry.fileId in resume.accepted }.forEach { file ->
                            val position = context.resumePositions.getValue(file.entry.fileId)
                            if (position.completedRoot == null && Bp2.decimal(position.offset) > 0) verifiedSource(file, position).close()
                        }
                        val readyId = newBp2Id(); context.expectedReply = readyId
                        sendPages("resume-ready-page", transferId, assigned.filter { it.entry.fileId in resume.accepted }.map { file ->
                            val position = context.resumePositions.getValue(file.entry.fileId)
                            buildJsonObject { put("fileId", file.entry.fileId); put("streamId", file.entry.streamId!!); put("offset", position.offset)
                                position.prefixRoot?.let { put("prefixRoot", it) } }
                        }, "items", readyId)
                        connection.sendControl(buildJsonObject { put("type", "resume-ready-end"); put("requestId", readyId); put("transferId", transferId) })
                    }
                    val accepted = withTimeout(75000) { context.decision.await() }
                    require(accepted.all { it in entries })
                    handler.onAccepted(transferId, accepted)
                    handler.onResumePositions(transferId, context.resumePositions.values.toList(), true)
                    val completed = context.resumePositions.values.filter { it.completedRoot != null }.map { it.fileId }.toSet()
                    completed.forEach { id ->
                        context.finished[id] = CompletableDeferred<Unit>().also { it.complete(Unit) }
                        handler.onSent(transferId, entries.getValue(id))
                    }
                    transmit(context, assigned.filter { it.entry.fileId in accepted && it.entry.fileId !in completed })
                    command("transfer-finish", transferId)
                    withTimeout(30000) { context.transferDone.await() }
                    val result = Bp2SendResult(transferId, accepted, entries.keys - accepted)
                    handler.onTransferEnded(transferId, if (result.rejected.isEmpty()) "completed" else "partial", true)
                    result
                } finally { repair.cancel(); context.retries.close() }
            }
        } catch (error: Throwable) {
            val retain = error is Bp2ConnectionLost || error is Bp2SourceUnavailable || error is Bp2SourcePrefixChanged ||
                (error is CancellationException && preserveOnCancellation())
            if (!retain) withContext(NonCancellable) { runCatching { withTimeout(1000) {
                command("cancel", transferId) { put("reason", "Sender stopped transfer") }
            } } }
            val status = if (retain) "disconnected" else when (error) {
                is CancellationException -> "cancelled"
                is Bp2Rejected -> if (error.message?.contains("BUSY") == true) "busy" else "rejected"
                is Bp2ConnectionLost -> "disconnected"
                else -> "failed"
            }
            handler.onTransferEnded(transferId, status, true)
            throw error
        } finally { outgoing = null }
    }

    private suspend fun transmit(context: Outgoing, files: List<Bp2OutgoingFile>) = coroutineScope {
        fun small(file: Bp2OutgoingFile) = Bp2.decimal(file.entry.size) in 0..Bp2.BATCH_FILE_BYTES &&
            (context.resumePositions[file.entry.fileId]?.offset ?: "0") == "0"
        val small = java.util.ArrayDeque(files.filter(::small))
        val rest = java.util.ArrayDeque(files.filterNot(::small))
        class Active(val file: Bp2OutgoingFile) : AutoCloseable {
            private val resumed = context.resumePositions[file.entry.fileId]?.let { verifiedSource(file, it) }
            val source = resumed?.source ?: file.openSource().buffer()
            val size = Bp2.decimal(file.entry.size)
            val digest = resumed?.content ?: Bp2ContentDigest(size)
            var offset = context.resumePositions[file.entry.fileId]?.let { Bp2.decimal(it.offset) } ?: 0L
            override fun close() { source.close() }
        }
        val active = java.util.ArrayDeque<Active>()
        class Publication(val streams: Int, val largeStreams: Int, val done: Deferred<Unit>)
        val publishing = mutableListOf<Publication>()
        fun freeStreams() = peer.hello.limits.maxActiveStreams - active.size - publishing.sumOf { it.streams }
        fun largeStreams() = active.count { it.size > Bp2.CHUNK_BYTES } + publishing.sumOf { it.largeStreams }
        fun publish(files: List<Pair<Bp2Entry, String>>) {
            val done = async {
                files.forEach { (entry, _) -> waitChunks(context, requireNotNull(entry.streamId)) }
                finishFiles(context, files)
            }
            // Wake only after the deferred is complete, so the scheduler cannot miss retirement.
            done.invokeOnCompletion { wake() }
            publishing += Publication(files.size, files.count { Bp2.decimal(it.first.size) > Bp2.CHUNK_BYTES }, done)
        }
        try {
            while (small.isNotEmpty() || rest.isNotEmpty() || active.isNotEmpty() || publishing.isNotEmpty()) {
                val observed = signal.value
                context.check(); currentCoroutineContext().ensureActive()
                val completed = publishing.filter { it.done.isCompleted }
                completed.forEach { it.done.await() }
                publishing.removeAll(completed.toSet())
                awaitUnpaused(context)
                // Leave a slot for queued non-BATCH data when all existing streams await publication.
                val reserveForData = active.isEmpty() && rest.isNotEmpty() &&
                    (Bp2.decimal(rest.first.entry.size) <= Bp2.CHUNK_BYTES || largeStreams() < 2)
                val smallSlots = freeStreams() - if (reserveForData) 1 else 0
                if (small.isNotEmpty() && smallSlots > 0) {
                    val count = minOf(small.size, smallSlots,
                        if (rest.isNotEmpty() || active.isNotEmpty()) 4 else Bp2.BATCH_RECORDS)
                    val batchFiles = List(count) { small.removeFirst() }
                    val records = batchFiles.map {
                        val bytes = it.openSource().buffer().use { source ->
                            val data = try { source.readByteArray(Bp2.decimal(it.entry.size)) }
                                catch (error: java.io.EOFException) { throw IOException("SOURCE_CHANGED", error) }
                            require(source.exhausted()) { "SOURCE_CHANGED" }; data
                        }
                        Bp2BatchRecord(requireNotNull(it.entry.streamId), bytes, Bp2Digests.sha256(bytes))
                    }
                    // Empty files participate in grouped publication, without zero-length DATA/BATCH records.
                    val dataRecords = records.filter { it.bytes.isNotEmpty() }
                    if (dataRecords.isNotEmpty()) {
                        reserve(context, dataRecords.sumOf { it.bytes.size })
                        dataRecords.forEach { context.chunks[ChunkKey(it.streamId, 0)] = PendingChunk(it.sha256 + it.bytes) }
                        connection.send(Bp2Kind.BATCH, payload = Bp2Payloads.encodeBatch(dataRecords))
                    }
                    val finished = batchFiles.zip(records).map { (file, record) ->
                        val digest = Bp2ContentDigest(record.bytes.size.toLong()).also {
                            if (record.bytes.isNotEmpty()) it.addChunkHash(0, record.sha256)
                        }
                        file.entry to digest.finish()
                    }
                    publish(finished)
                }
                val activeLimit = minOf(2, peer.hello.limits.maxActiveStreams)
                while (active.size < activeLimit && rest.isNotEmpty() && freeStreams() > 0) {
                    // EOF closes a source handle, but its large-stream slot stays held through files-done.
                    if (Bp2.decimal(rest.first.entry.size) > Bp2.CHUNK_BYTES && largeStreams() >= 2) break
                    active.addLast(Active(rest.removeFirst()))
                }
                if (active.isEmpty()) {
                    if (publishing.isNotEmpty()) withTimeout(30000) { signal.first { it != observed } }
                    continue
                }
                val current = active.removeFirst()
                try {
                    if (current.offset < current.size) {
                        val length = minOf(Bp2.CHUNK_BYTES.toLong(), current.size - current.offset).toInt()
                        reserve(context, length)
                        val bytes = try { current.source.readByteArray(length.toLong()) }
                            catch (error: java.io.EOFException) { throw IOException("SOURCE_CHANGED", error) }
                        val hash = Bp2Digests.sha256(bytes)
                        current.digest.addChunkHash(current.offset / Bp2.CHUNK_BYTES, hash)
                        val payload = hash + bytes
                        val stream = requireNotNull(current.file.entry.streamId)
                        context.chunks[ChunkKey(stream, current.offset)] = PendingChunk(payload)
                        connection.send(Bp2Kind.DATA, stream, current.offset, payload)
                        current.offset += length
                    }
                    if (current.offset == current.size) {
                        require(current.source.exhausted()) { "SOURCE_CHANGED" }
                        current.close()
                        publish(listOf(current.file.entry to current.digest.finish()))
                    } else active.addLast(current)
                } catch (error: Throwable) { current.close(); throw error }
            }
        } finally {
            publishing.forEach { it.done.cancel() }
            active.forEach { runCatching { it.close() } }
        }
    }

    private suspend fun reserve(context: Outgoing, bytes: Int, retry: Boolean = false) {
        while (true) {
            if (!retry) awaitUnpaused(context)
            val observed = signal.value
            context.check(); check(!closed.get())
            if (creditMutex.withLock { (retry || context.repairCount.get() == 0) && sendCredit.reserve(bytes, retry) }) return
            withTimeout(30000) { signal.first { it != observed } }
        }
    }
    private suspend fun waitChunks(context: Outgoing, stream: Long) {
        while (true) {
            val observed = signal.value
            context.check(); check(!closed.get())
            if (context.chunks.keys.none { it.stream == stream }) return
            withTimeout(30000) { signal.first { it != observed } }
        }
    }
    private suspend fun finishFiles(context: Outgoing, files: List<Pair<Bp2Entry, String>>) {
        // Register the whole group before writing: a fast peer may reply during sendControl.
        val pending = files.map { (entry, _) ->
            CompletableDeferred<Unit>().also { context.finished[entry.fileId] = it }
        }
        command("files-finish", context.id) { putJsonArray("items") {
            files.forEach { (entry, root) -> add(buildJsonObject {
                put("fileId", entry.fileId); put("size", entry.size); put("contentRoot", root)
            }) }
        } }
        var failure: IOException? = null
        files.zip(pending).forEach { (file, done) ->
            val error = try { withTimeout(30000) { done.await() }; null } catch (error: IOException) { error }
            if (error == null) handler.onSent(context.id, file.first)
            else if (failure == null) failure = error
        }
        failure?.let { throw it }
    }

    private suspend fun process(frame: Bp2Frame) {
        when (frame.header.kind) {
            Bp2Kind.DATA -> receiveData(frame)
            Bp2Kind.BATCH -> {
                val records = Bp2Payloads.decodeBatch(frame.payload)
                require(records.size <= local.limits.maxActiveStreams)
                // One transport frame consumes one credit receipt, even though records ACK separately.
                receiveCredit.consume(frame.header.sequence, records.sumOf { it.bytes.size })
                val controls = mutableListOf<JsonObject>()
                for (record in records) {
                    if (cancelledStreams.containsKey(record.streamId)) continue
                    val entry = currentIncoming().streamEntries[record.streamId]
                        ?: error("Unknown BATCH stream")
                    require(Bp2.decimal(entry.size) == record.bytes.size.toLong())
                    receiveChunk(Bp2Frame(Bp2Header(Bp2Kind.DATA, record.streamId, 32 + record.bytes.size,
                        sequence = frame.header.sequence), record.sha256 + record.bytes), receipt = false, controls)
                }
                controls += windowMessage(receiveCredit.release(frame.header.sequence))
                connection.sendControls(controls)
            }
            Bp2Kind.CONTROL -> processControl(Bp2Controls.decode(frame.payload))
        }
    }

    private suspend fun processControl(message: JsonObject) {
        when (message.str("type")) {
            "resume" -> try { receiveResume(message) } catch (error: Throwable) {
                if (error is Bp2ConnectionLost || error is CancellationException) throw error
                val code = listOf("AUTH_FAILED", "RESUME_EXPIRED", "RESUME_MISMATCH", "BUSY", "QUOTA_EXCEEDED")
                    .firstOrNull { it in error.message.orEmpty() } ?: "RESUME_MISMATCH"
                respond("error", message) { put("code", code); put("scope", "transfer"); put("retryable", false) }
            }
            "resume-page" -> {
                val context = requireOutgoing(message)
                require(message.str("replyTo") == context.expectedReply && message.number("pageIndex") == context.resumePage++.toLong())
                val items = message.array("items"); require(items.size in 1..256)
                items.forEach { value ->
                    val position = Bp2.json.decodeFromJsonElement<Bp2ResumePosition>(value)
                    val entry = context.entries[position.fileId] ?: error("Invalid resume file")
                    val offset = Bp2.decimal(position.offset); val size = Bp2.decimal(entry.size)
                    require(offset <= size && (offset % Bp2.CHUNK_BYTES == 0L || offset == size))
                    if (position.completedRoot == null) Bp2.requireFingerprint(requireNotNull(position.prefixRoot))
                    else { Bp2.requireFingerprint(position.completedRoot); require(offset == size) }
                    require(context.resumePositions.put(position.fileId, position) == null)
                }
            }
            "resume-end" -> {
                val context = requireOutgoing(message); require(message.str("replyTo") == context.expectedReply)
                context.resumeReady.complete(Unit)
            }
            "reset-file" -> {
                require("reset-file" in local.features) { "UNSUPPORTED_MESSAGE" }
                val transfer = requireIncoming(message)
                require(transfer.retained != null && !transfer.accepted && transfer.readyPage == 0 && transfer.readyFiles.isEmpty())
                val fileId = message.str("fileId")
                val previous = transfer.resumePositions.getValue(fileId)
                require(previous.completedRoot == null && previous.offset == message.str("offset") && previous.prefixRoot == message.str("prefixRoot")) { "RESUME_MISMATCH" }
                val position = transfer.retained!!.resetFile(fileId, previous)
                transfer.resumePositions = transfer.resumePositions + (fileId to position)
                respond("file-reset", message) {
                    put("fileId", fileId); put("offset", position.offset); put("prefixRoot", position.prefixRoot)
                }
            }
            "file-reset" -> {
                val context = requireOutgoing(message)
                val request = context.resets[message.str("replyTo")] ?: error("Unexpected reset acknowledgement")
                val fileId = message.str("fileId")
                require(fileId == request.first && message.str("offset") == "0")
                val expected = Bp2ContentDigest(Bp2.decimal(context.entries.getValue(fileId).size), 0).finish()
                require(message.str("prefixRoot") == expected) { "RESUME_MISMATCH" }
                request.second.complete(Bp2ResumePosition(fileId, "0", expected))
            }
            "resume-ready-page" -> receiveResumeReadyPage(message)
            "resume-ready-end" -> {
                val transfer = requireIncoming(message)
                require(transfer.retained != null && !transfer.accepted && transfer.readyFiles == transfer.selected)
                transfer.endRequest = message.str("requestId")
                lastRemoteStream = maxOf(lastRemoteStream, transfer.streams.maxOrNull() ?: 0)
                sendAcceptance(transfer)
                transfer.resumePositions.values.filter { it.completedRoot != null }.forEach { position ->
                    val entry = transfer.entries.getValue(position.fileId)
                    transfer.results[entry.fileId] = position.completedRoot!!
                    handler.onFilePublished(peer, transfer.id, entry, transfer.retained!!.published(entry))
                }
                handler.onResumePositions(transfer.id, transfer.resumePositions.values.toList(), false)
            }
            "cancel-resume" -> {
                val ticket = Bp2ResumeTicket(message.str("transferId"), message.str("manifestHash"), message.str("resumeToken"), emptySet())
                val store = handler.resumeStore ?: error("UNSUPPORTED_MESSAGE")
                store.validateTicket(peer.fingerprint, ticket, forCancellation = true)
                handler.onAuthorizedResume(peer, ticket.transferId)
                store.resume(peer.fingerprint, ticket, forCancellation = true).use { it.discard() }
                respond("cancelled", message)
            }
            "pause", "continue" -> {
                val id = message.str("transferId")
                require("pause" in local.features && activeTransfer(id)) { "Transfer is not active" }
                val paused = message.str("type") == "pause"
                applyPause(id, paused)
                respond(if (paused) "paused" else "continued", message)
            }
            "paused", "continued" -> {
                val request = pauseRequests.remove(message.str("replyTo")) ?: error("Unexpected pause acknowledgement")
                require(request.id == message.str("transferId") && request.paused == (message.str("type") == "paused"))
                if (activeTransfer(request.id)) applyPause(request.id, request.paused)
                request.done.complete(Unit)
            }
            "window-update" -> { creditMutex.withLock { sendCredit.update(Bp2.decimal(message.str("creditTotal"))) }; wake() }
            "ack" -> acknowledge(message)
            "nack" -> {
                val context = requireOutgoing(message)
                val index = Bp2.decimal(message.str("chunkIndex")); require(index <= Long.MAX_VALUE / Bp2.CHUNK_BYTES)
                val key = ChunkKey(message.number("streamId"), index * Bp2.CHUNK_BYTES)
                if (context.chunks.containsKey(key)) {
                    context.repairCount.incrementAndGet()
                    require(context.retries.trySend(key).isSuccess) { "Excessive NACKs" }; wake()
                }
            }
            "offer-begin" -> {
                val id = message.str("transferId"); Bp2.requireId(id)
                require(id !in usedTransfers && usedTransfers.size < 1024) { "Duplicate transfer or session limit" }
                usedTransfers += id
                if (incoming != null && !incoming!!.complete) {
                    require(refused.size < 16); refused[id] = "BUSY"; return
                }
                val count = message.number("entryCount")
                require(count in 1L..local.limits.maxManifestEntries.toLong())
                incoming = Incoming(id, count.toInt(), Bp2.decimal(message.str("totalBytes")), lastRemoteStream)
            }
            "offer-page" -> receivePage(message)
            "offer-end" -> {
                refused.remove(message.str("transferId"))?.let { reason -> respond("reject", message) { put("reason", reason) }; return }
                val transfer = requireIncoming(message)
                require(!transfer.sealed && transfer.entries.size == transfer.count && transfer.bytes == transfer.total)
                transfer.manifestHash = transfer.manifest.finish()
                require(transfer.manifestHash == message.str("manifestHash"))
                lastRemoteStream = maxOf(lastRemoteStream, transfer.streams.maxOrNull() ?: 0)
                transfer.sealed = true; transfer.endRequest = message.str("requestId")
                transfer.decisionJob = scope.launch {
                    try {
                        val accepted = withTimeout(60000) { handler.onOffer(peer, transfer.id, transfer.entries.values.toList()) }
                        inbox.send(Event.Decision(transfer.id, accepted, null))
                    } catch (error: Throwable) { runCatching { inbox.send(Event.Decision(transfer.id, null, error)) } }
                }
            }
            "accept-page" -> {
                val context = requireOutgoing(message)
                require(message.str("replyTo") == context.expectedReply)
                require(message.number("pageIndex") == context.acceptPage++.toLong() && !context.decision.isCompleted)
                val values = message.array("accepted"); require(values.size <= 256)
                values.forEach {
                    val item = it.jsonObject; val entry = context.entries[item.str("fileId")] ?: error("Unknown accepted file")
                    require(item.number("streamId") == entry.streamId && context.accepted.add(entry.fileId))
                }
            }
            "accept-end" -> {
                val context = requireOutgoing(message)
                require(message.str("replyTo") == context.expectedReply)
                Bp2.requireId(message.str("decisionId")); require(context.accepted.isNotEmpty())
                message["resumeToken"]?.jsonPrimitive?.content?.let { token ->
                    Bp2.requireFingerprint(token)
                    context.onTicket(Bp2ResumeTicket(context.id, context.manifestHash, token, context.accepted.toSet()))
                }
                context.decision.complete(context.accepted.toSet())
            }
            "reject" -> { val context = requireOutgoing(message); context.fail(Bp2Rejected(message.str("reason"))); wake() }
            "files-finish" -> receiveFinish(message)
            "files-done" -> {
                val context = requireOutgoing(message)
                val items = message.array("items"); require(items.size in 1..64)
                items.forEach {
                    val item = it.jsonObject
                    val done = context.finished[item.str("fileId")] ?: error("Unrequested file completion")
                    if (item.str("status") == "completed") done.complete(Unit)
                    else done.completeExceptionally(IOException("Remote file publication failed"))
                }
            }
            "transfer-finish" -> {
                val transfer = requireIncoming(message)
                require(transfer.accepted && transfer.selected.all { it in transfer.results })
                transfer.complete = true
                val status = if (transfer.results.size == transfer.count) "completed" else "partial"
                transfer.retained?.let { it.complete(); it.close() }; transfer.retained = null
                handler.onTransferEnded(transfer.id, status, false)
                respond("transfer-done", message) { put("status", status) }
            }
            "transfer-done" -> {
                val context = requireOutgoing(message)
                require(context.finished.size == context.accepted.size && context.finished.values.all { it.isCompleted && !it.isCancelled })
                require(message.str("status") in listOf("completed", "partial"))
                context.transferDone.complete(Unit)
            }
            "cancel", "cancelled" -> {
                val id = message.str("transferId")
                retainedCancellation?.takeIf { it.first == id }?.second?.complete(Unit)
                if (incoming?.id == id) discardIncoming("cancelled")
                outgoing?.takeIf { it.id == id }?.fail(CancellationException("Cancelled by peer")); wake()
                if (message.str("type") == "cancel") respond("cancelled", message)
            }
            "error" -> { val error = IOException(message.str("code")); outgoing?.fail(error); retainedCancellation?.second?.completeExceptionally(error); wake() }
            else -> respond("error", message) { put("code", "UNSUPPORTED_MESSAGE") }
        }
    }

    private fun receivePage(message: JsonObject) {
        if (message.str("transferId") in refused) return
        val transfer = requireIncoming(message)
        require(!transfer.sealed)
        val pageValue = message.number("pageIndex"); require(pageValue in 0L..Int.MAX_VALUE.toLong())
        val page = pageValue.toInt()
        val values = message.array("entries"); require(values.size in 1..256)
        val hash = Bp2Digests.hex(Bp2Digests.sha256(values.toString().encodeToByteArray()))
        if (page < transfer.nextPage) { require(transfer.pageHashes[page] == hash); return }
        require(page == transfer.nextPage++ && transfer.entries.size + values.size <= transfer.count)
        values.forEach {
            val entry = Bp2.json.decodeFromJsonElement<Bp2Entry>(it)
            entry.validate(requireStream = true); require(entry.kind != "directory")
            val stream = requireNotNull(entry.streamId)
            require(stream % 2 == if (initiator) 0L else 1L)
            require(stream > transfer.streamFloor)
            require(transfer.streams.add(stream) && transfer.entries.put(entry.fileId, entry) == null)
            transfer.manifest.add(entry)
            transfer.streamEntries[stream] = entry
            val bytes = Bp2.decimal(entry.size); require(transfer.bytes <= Long.MAX_VALUE - bytes); transfer.bytes += bytes
        }
        transfer.pageHashes[page] = hash
    }

    private suspend fun decide(event: Event.Decision) {
        val transfer = incoming?.takeIf { it.id == event.id && !it.complete } ?: return
        val accepted = event.accepted.orEmpty()
        require(accepted.all { it in transfer.entries })
        if (accepted.isEmpty() || event.error != null) {
            connection.sendControl(message("reject", transfer.id) {
                put("replyTo", transfer.endRequest); put("reason", event.error?.message ?: "Rejected")
            })
            discardIncoming("rejected"); return
        }
        transfer.selected += accepted
        handler.resumeStore?.takeIf { it.supports(transfer.entries.values.toList()) }?.let { store ->
            transfer.retained = store.create(peer.fingerprint, transfer.id, transfer.manifestHash, transfer.entries.values.toList(), accepted)
        }
        sendAcceptance(transfer)
    }

    private suspend fun sendAcceptance(transfer: Incoming) {
        sendPages("accept-page", transfer.id, transfer.selected.map { id -> buildJsonObject {
            put("fileId", id); put("streamId", requireNotNull(transfer.entries.getValue(id).streamId))
        } }, "accepted", transfer.endRequest)
        connection.sendControl(message("accept-end", transfer.id) {
            put("replyTo", transfer.endRequest); put("decisionId", newBp2Id())
            transfer.retained?.let { put("resumeToken", it.token) }
        })
        transfer.accepted = true
    }

    private suspend fun receiveResume(message: JsonObject) {
        require(incoming == null || incoming!!.complete) { "BUSY" }
        val store = handler.resumeStore ?: error("UNSUPPORTED_MESSAGE")
        val id = message.str("transferId"); Bp2.requireId(id)
        require(id !in usedTransfers && usedTransfers.size < 1024); usedTransfers += id
        val ticket = Bp2ResumeTicket(id, message.str("manifestHash"), message.str("resumeToken"), emptySet())
        store.validateTicket(peer.fingerprint, ticket)
        handler.onAuthorizedResume(peer, id)
        val lease = store.resume(peer.fingerprint, ticket)
        try {
            val positions = lease.positions()
            handler.onResuming(peer, id, lease.entries, positions)
            val total = lease.entries.fold(0L) { sum, entry -> val n = Bp2.decimal(entry.size); require(sum <= Long.MAX_VALUE - n); sum + n }
            val transfer = Incoming(id, lease.entries.size, total, lastRemoteStream)
            transfer.entries.putAll(lease.entries.associateBy { it.fileId }); transfer.selected += lease.selected
            transfer.retained = lease; transfer.sealed = true; transfer.manifestHash = ticket.manifestHash
            transfer.resumePositions = positions.associateBy { it.fileId }
            incoming = transfer
            sendPages("resume-page", id, positions.map { Bp2.json.encodeToJsonElement(it) }, "items", message.str("requestId"))
            respond("resume-end", message)
        } catch (error: Throwable) { lease.close(); throw error }
    }
    private fun receiveResumeReadyPage(message: JsonObject) {
        val transfer = requireIncoming(message)
        require(transfer.retained != null && !transfer.accepted && message.number("pageIndex") == transfer.readyPage++.toLong())
        val items = message.array("items"); require(items.size in 1..256)
        items.forEach { value ->
            val item = value.jsonObject; val id = item.str("fileId")
            val position = transfer.resumePositions[id] ?: error("Invalid resume stream")
            require(transfer.readyFiles.add(id) && item.str("offset") == position.offset)
            if (position.completedRoot == null) require(item.str("prefixRoot") == position.prefixRoot)
            val stream = item.number("streamId")
            require(stream in 1..0xffffffffL && stream > transfer.streamFloor && stream % 2 == if (initiator) 0L else 1L)
            require(transfer.streams.add(stream))
            val entry = transfer.entries.getValue(id).copy(streamId = stream)
            transfer.entries[id] = entry; transfer.streamEntries[stream] = entry
        }
    }

    private suspend fun receiveData(frame: Bp2Frame) {
        val count = frame.payload.size - 32
        receiveCredit.consume(frame.header.sequence, count)
        cancelledStreams[frame.header.streamId]?.let { size ->
            require(frame.header.offset <= size - count)
            window(receiveCredit.release(frame.header.sequence)); return
        }
        val controls = mutableListOf<JsonObject>()
        receiveChunk(frame, receipt = true, controls)
        connection.sendControls(controls)
    }
    private suspend fun receiveChunk(frame: Bp2Frame, receipt: Boolean, controls: MutableList<JsonObject>) {
        val transfer = currentIncoming()
        require(transfer.accepted && !transfer.complete)
        val entry = transfer.streamEntries[frame.header.streamId]
            ?: error("Unknown stream")
        require(entry.fileId in transfer.selected && entry.fileId !in transfer.results)
        val file = transfer.files[frame.header.streamId] ?: run {
            require(transfer.files.size < local.limits.maxActiveStreams)
            Receiving(entry, Bp2IncomingFile(entry, transfer.retained?.destination(entry) ?: handler.createDestination(peer, transfer.id, entry),
                Bp2.decimal(local.limits.receiveWindow))).also { transfer.files[frame.header.streamId] = it }
        }
        val duplicate = file.writer.containsChunk(frame.header.offset)
        if (file.writer.accept(frame)) {
            if (receipt) {
                var total: Long? = null
                if (duplicate) total = receiveCredit.release(frame.header.sequence)
                else file.pendingCredit[frame.header.offset] = frame.header.sequence
                while (file.pendingCredit.firstKeyOrNull()?.let { it < file.writer.written } == true) {
                    val receipt = file.pendingCredit.pollFirstEntry() ?: break
                    total = receiveCredit.release(receipt.value)
                }
                total?.let { controls += windowMessage(it) }
            }
            controls += message("ack", transfer.id) {
                put("streamId", frame.header.streamId); put("writtenOffset", file.writer.written.toString())
                file.writer.durableOffset?.let { put("durableOffset", it.toString()) }
                putJsonArray("verifiedRanges") { add(buildJsonArray {
                    add((frame.header.offset / Bp2.CHUNK_BYTES).toString()); add((frame.header.offset / Bp2.CHUNK_BYTES + 1).toString())
                }) }
            }
            handler.onProgress(transfer.id, entry.fileId, file.writer.written, false)
        } else {
            if (receipt) controls += windowMessage(receiveCredit.release(frame.header.sequence))
            require(file.writer.state != Bp2IncomingFile.State.FAILED) { "CHECKSUM_MISMATCH: retry limit" }
            controls += message("nack", transfer.id) {
                put("streamId", frame.header.streamId); put("chunkIndex", (frame.header.offset / Bp2.CHUNK_BYTES).toString())
                put("reason", "CHECKSUM_MISMATCH")
            }
        }
    }

    private suspend fun receiveFinish(message: JsonObject) {
        val transfer = requireIncoming(message)
        require(transfer.accepted)
        val items = message.array("items"); require(items.size in 1..64)
        val completions = items.map {
            val item = it.jsonObject
            val entry = transfer.entries[item.str("fileId")] ?: error("Unknown file completion")
            require(entry.fileId in transfer.selected && item.str("size") == entry.size)
            val root = item.str("contentRoot")
            require(root.length == 64 && root.all { char -> char in '0'..'9' || char in 'a'..'f' })
            entry to root
        }
        require(completions.map { it.first.fileId }.distinct().size == completions.size)
        val results = completions.map { (entry, root) ->
            if (entry.fileId in transfer.results) require(transfer.results[entry.fileId] == root)
            else {
                try {
                    val file = transfer.files[entry.streamId] ?: run {
                        require(Bp2.decimal(entry.size) == 0L)
                        Receiving(entry, Bp2IncomingFile(entry, transfer.retained?.destination(entry) ?: handler.createDestination(peer, transfer.id, entry)))
                    }
                    file.writer.finish(root)
                } catch (_: IOException) {
                    // Publication is per file. Report the other successes even when one disk write fails.
                    return@map buildJsonObject {
                        put("fileId", entry.fileId); put("status", "failed"); put("code", "COMMIT_FAILED")
                    }
                }
                transfer.results[entry.fileId] = root
                transfer.files.remove(entry.streamId)
                handler.onFilePublished(peer, transfer.id, entry, transfer.retained?.published(entry))
            }
            buildJsonObject { put("fileId", entry.fileId); put("status", "completed") }
        }
        respond("files-done", message) { put("items", JsonArray(results)) }
    }

    private fun acknowledge(message: JsonObject) {
        val context = requireOutgoing(message)
        val stream = message.number("streamId")
        val entry = context.streams[stream] ?: error("Unknown ACK stream")
        val written = Bp2.decimal(message.str("writtenOffset"))
        require(written <= Bp2.decimal(entry.size))
        val ranges = message.array("verifiedRanges"); require(ranges.size in 1..64)
        val chunks = if (Bp2.decimal(entry.size) == 0L) 0L else (Bp2.decimal(entry.size) - 1) / Bp2.CHUNK_BYTES + 1
        ranges.forEach {
            val range = it.jsonArray; require(range.size == 2)
            val begin = Bp2.decimal(range[0].jsonPrimitive.content); val end = Bp2.decimal(range[1].jsonPrimitive.content)
            require(begin < end && end <= chunks)
            context.chunks.keys.filter { key -> key.stream == stream && key.offset / Bp2.CHUNK_BYTES in begin until end }
                .forEach { key -> context.chunks.remove(key) }
        }
        handler.onProgress(context.id, entry.fileId, written, true)
        wake()
    }

    suspend fun cancelIncoming(transferId: String) {
        val done = CompletableDeferred<Unit>()
        inbox.send(Event.Cancel(transferId, done))
        withTimeout(5000) { done.await() }
    }
    suspend fun cancelRetained(ticket: Bp2ResumeTicket) {
        val done = CompletableDeferred<Unit>(); retainedCancellation = ticket.transferId to done
        command("cancel-resume", ticket.transferId) { put("manifestHash", ticket.manifestHash); put("resumeToken", ticket.resumeToken) }
        try { withTimeout(5000) { done.await() } } finally { retainedCancellation = null }
    }
    /** Transfer-level pause keeps TLS, credit accounting and reverse-direction traffic alive. */
    suspend fun setPaused(transferId: String, paused: Boolean) {
        require("pause" in peer.hello.features) { "Peer does not support pause" }
        Bp2.requireId(transferId)
        val done = CompletableDeferred<Unit>()
        inbox.send(Event.Pause(PauseRequest(transferId, paused, done)))
        withTimeout(10000) { done.await() }
    }
    private fun activeTransfer(id: String) = outgoing?.let { it.id == id && it.decision.isCompleted && !it.transferDone.isCompleted } == true ||
        incoming?.let { it.id == id && it.accepted && !it.complete } == true
    private fun applyPause(id: String, paused: Boolean) {
        outgoing?.takeIf { it.id == id }?.let { it.paused = paused; handler.onPaused(id, paused, true) }
        incoming?.takeIf { it.id == id && !it.complete }?.let { handler.onPaused(id, paused, false) }
        wake()
    }
    private suspend fun awaitUnpaused(context: Outgoing) {
        while (true) {
            val observed = signal.value
            context.check(); check(!closed.get())
            if (!context.paused) return
            signal.first { it != observed }
        }
    }
    suspend fun awaitClosed() { stopped.await() }
    private suspend fun discardIncoming(status: String) {
        val transfer = incoming ?: return
        val recoverable = transfer.retained != null
        try {
            transfer.decisionJob?.cancel()
            transfer.files.values.forEach { file ->
                runCatching { if (status == "disconnected" && transfer.retained != null) file.writer.disconnect() else file.writer.cancel() }
                file.pendingCredit.values.forEach { sequence -> runCatching { window(receiveCredit.release(sequence)) } }
            }
            if (!transfer.complete) {
                require(cancelledStreams.size + transfer.streams.size <= 100000) { "Cancelled stream tombstone limit" }
                transfer.entries.values.forEach { it.streamId?.let { stream -> cancelledStreams[stream] = Bp2.decimal(it.size) } }
            }
            transfer.files.clear()
            transfer.retained?.let { lease ->
                try { if (status != "disconnected" && !transfer.complete) lease.discard() } finally { lease.close() }
            }
        } finally {
            transfer.files.clear()
            try { transfer.retained?.close() } finally {
                transfer.retained = null; incoming = null
                if (!transfer.complete) handler.onTransferEnded(transfer.id, if (status == "disconnected" && !recoverable) "failed" else status, false)
            }
        }
    }
    private fun currentIncoming() = incoming ?: error("No incoming transfer")
    private fun requireIncoming(message: JsonObject) = currentIncoming().also { require(it.id == message.str("transferId")) }
    private fun requireOutgoing(message: JsonObject) = (outgoing ?: error("No outgoing transfer")).also {
        require(it.id == message.str("transferId"))
    }
    private fun windowMessage(total: Long) = message("window-update") { put("creditTotal", total.toString()) }
    private suspend fun window(total: Long) { connection.sendControl(windowMessage(total)) }
    private fun wake() { signal.update { it + 1 } }
    private suspend fun command(type: String, id: String, body: JsonObjectBuilder.() -> Unit = {}) {
        connection.sendControl(message(type, id, body))
    }
    private suspend fun respond(type: String, original: JsonObject, body: JsonObjectBuilder.() -> Unit = {}) {
        connection.sendControl(message(type, original["transferId"]?.jsonPrimitive?.content) {
            original["requestId"]?.let { put("replyTo", it) }; body()
        })
    }
    private suspend fun sendPages(type: String, id: String, values: List<JsonElement>, field: String, replyTo: String? = null) {
        var page = 0; var count = 0; val buffer = mutableListOf<JsonElement>()
        suspend fun flush() {
            if (buffer.isEmpty()) return
            command(type, id) {
                put("pageIndex", page++); put(field, JsonArray(buffer.toList()))
                if (replyTo != null) put("replyTo", replyTo)
            }
            buffer.clear(); count = 0
        }
        for (value in values) {
            val bytes = value.toString().encodeToByteArray().size + 1
            require(bytes <= Bp2.CONTROL_BYTES - 1024)
            if (buffer.size == 256 || count + bytes > Bp2.CONTROL_BYTES - 1024) flush()
            buffer += value; count += bytes
        }
        flush()
    }
    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        connection.close(); inbox.close(); job.cancel(); wake()
    }
}

private fun message(type: String, id: String? = null, body: JsonObjectBuilder.() -> Unit = {}) = buildJsonObject {
    put("type", type); put("requestId", newBp2Id()); if (id != null) put("transferId", id); body()
}
private fun JsonObject.str(key: String): String {
    val value = getValue(key).jsonPrimitive; require(value.isString); return value.content
}
private fun JsonObject.number(key: String): Long {
    val value = getValue(key).jsonPrimitive; require(!value.isString); return value.long
}
private fun JsonObject.array(key: String) = getValue(key).jsonArray
private fun <K, V> java.util.TreeMap<K, V>.firstKeyOrNull(): K? = if (isEmpty()) null else firstKey()

private fun verifiedSource(file: Bp2OutgoingFile, position: Bp2ResumePosition): Bp2ResumedSource {
    val size = Bp2.decimal(file.entry.size); val retained = Bp2.decimal(position.offset)
    val count = if (retained == 0L) 0L else (retained - 1) / Bp2.CHUNK_BYTES + 1
    val prefix = Bp2ContentDigest(size, count); val content = Bp2ContentDigest(size)
    val source = file.openSource().buffer()
    try {
        var offset = 0L
        while (offset < retained) {
            val bytes = try { source.readByteArray(minOf(Bp2.CHUNK_BYTES.toLong(), retained - offset)) }
                catch (error: java.io.EOFException) { throw IOException("SOURCE_CHANGED", error) }
            val hash = Bp2Digests.sha256(bytes)
            prefix.addChunkHash(offset / Bp2.CHUNK_BYTES, hash); content.addChunkHash(offset / Bp2.CHUNK_BYTES, hash)
            offset += bytes.size
        }
        if (prefix.finish() != position.prefixRoot) throw Bp2SourcePrefixChanged(file.entry.fileId)
        return Bp2ResumedSource(source, content)
    } catch (error: Throwable) { source.close(); throw error }
}
