package dev.boxlet.core.session.bp2

import dev.boxlet.core.protocol.bp2.*
import dev.boxlet.core.transport.bp2.Bp2TlsConnection
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import okio.Sink
import okio.Source
import okio.buffer
import java.io.IOException
import java.io.EOFException

/** Platform destination, independent of LocalSend metadata and handlers. */
interface Bp2Destination {
    val sink: Sink
    val checkpointFile: Bp2CheckpointFile? get() = null
    suspend fun commit()
    suspend fun abort()
}

/** Stage-B sequential file writer; a session must ACCEPT/bind the stream before constructing it. */
class Bp2IncomingFile(private val entry: Bp2Entry, private val destination: Bp2Destination,
                      private val maxPendingBytes: Long = Bp2.WINDOW_BYTES) {
    enum class State { RECEIVING, COMPLETED, FAILED, CANCELLED }
    var state = State.RECEIVING
        private set
    private val checkpoint = destination.checkpointFile
    var written = checkpoint?.written ?: 0L
        private set
    private val size = Bp2.decimal(entry.size)
    private val content = Bp2ContentDigest(size)
    private val sink = if (checkpoint == null) destination.sink.buffer() else null
    val durableOffset: Long? get() = checkpoint?.durableOffset
    private var lastCheckpoint = System.nanoTime()
    private val attempts = mutableMapOf<Long, Int>()
    private val pending = java.util.TreeMap<Long, Bp2Frame>()
    private val recent = linkedMapOf<Long, ByteArray>()
    private var pendingBytes = 0L
    private var completedRoot: String? = null
    init { entry.validate(requireStream = true); require(entry.kind != "directory") }

    /** False means NACK this offset. No unverified bytes are passed to the platform sink. */
    suspend fun accept(frame: Bp2Frame): Boolean {
        check(state == State.RECEIVING)
        try {
            require(frame.header.streamId == entry.streamId)
            if (!Bp2Payloads.verifyData(frame, size)) {
                require(attempts.size < 32 || frame.header.offset in attempts)
                val count = (attempts[frame.header.offset] ?: 0) + 1
                attempts[frame.header.offset] = count
                if (count >= 3) fail()
                return false
            }
            val offset = frame.header.offset
            val hash = frame.payload.copyOfRange(0, Bp2.HASH_BYTES)
            if (offset < written) {
                require(recent[offset]?.contentEquals(hash) == true) { "Conflicting or stale duplicate chunk" }
                return true
            }
            pending[offset]?.let {
                require(it.payload.copyOfRange(0, 32).contentEquals(hash)) { "Conflicting buffered chunk" }
                return true
            }
            require(pending.size < 32 && pendingBytes + frame.payload.size <= maxPendingBytes + 32L * 32)
            pending[offset] = frame
            pendingBytes += frame.payload.size
            attempts.remove(offset)
            while (true) {
                val next = pending.remove(written) ?: break
                val count = next.payload.size - Bp2.HASH_BYTES
                val digest = next.payload.copyOfRange(0, Bp2.HASH_BYTES)
                if (checkpoint == null) {
                    sink!!.write(next.payload, Bp2.HASH_BYTES, count)
                    content.addChunkHash(written / Bp2.CHUNK_BYTES, digest)
                } else checkpoint.append(next.payload.copyOfRange(Bp2.HASH_BYTES, next.payload.size), digest)
                recent[written] = digest
                if (recent.size > 16) recent.remove(recent.keys.first())
                written += count
                pendingBytes -= next.payload.size
            }
            if (checkpoint != null && (written - checkpoint.durableOffset >= 8L * Bp2.CHUNK_BYTES || System.nanoTime() - lastCheckpoint >= 1_000_000_000)) {
                checkpoint.checkpoint(); lastCheckpoint = System.nanoTime()
            }
            return true
        } catch (error: Throwable) { fail(); throw error }
    }

    fun containsChunk(offset: Long): Boolean = offset < written || pending.containsKey(offset)

    /** Only a verified completion root allows the platform to publish the file. */
    suspend fun finish(root: String) {
        if (state == State.COMPLETED) { require(root == completedRoot); return }
        check(state == State.RECEIVING)
        try {
            require(written == size) { "File is incomplete" }
            if (checkpoint != null) checkpoint.seal(root) else require(content.finish() == root) { "File content root mismatch" }
            sink?.close()
            destination.commit()
            completedRoot = root
            state = State.COMPLETED
        } catch (error: Throwable) { fail(); throw error }
    }

    suspend fun cancel() {
        if (state == State.COMPLETED || state == State.CANCELLED) return
        fail()
        state = State.CANCELLED
    }

    suspend fun disconnect(): Boolean {
        if (state != State.RECEIVING || checkpoint == null) { cancel(); return false }
        withContext(NonCancellable + Dispatchers.IO) {
            try { checkpoint.checkpoint() } finally { checkpoint.close() }
        }
        pending.clear(); attempts.clear(); recent.clear(); pendingBytes = 0
        state = State.FAILED // This connection's stream is closed; recovery allocates a new stream.
        return true
    }

    private suspend fun fail() {
        if (state != State.RECEIVING) return
        state = State.FAILED
        pending.clear(); attempts.clear(); recent.clear(); pendingBytes = 0
        withContext(NonCancellable + Dispatchers.IO) {
            runCatching { sink?.close() }
            runCatching { destination.abort() }
        }
    }
}

object Bp2FileSender {
    /** Returns contentRoot for FILE_FINISH. The enclosing session owns flow control and ACKs. */
    suspend fun send(
        entry: Bp2Entry,
        openSource: () -> Source,
        connection: Bp2TlsConnection,
        beforeChunk: suspend (Int) -> Unit,
        onProgress: (Long) -> Unit = {},
    ): String = withContext(Dispatchers.IO) {
        entry.validate(requireStream = true)
        require(entry.kind != "directory")
        val size = Bp2.decimal(entry.size)
        val content = Bp2ContentDigest(size)
        openSource().buffer().use { source ->
            var offset = 0L
            while (offset < size) {
                currentCoroutineContext().ensureActive()
                val length = minOf(Bp2.CHUNK_BYTES.toLong(), size - offset).toInt()
                beforeChunk(length)
                val bytes = try { source.readByteArray(length.toLong()) }
                    catch (error: EOFException) { throw IOException("SOURCE_CHANGED: source shorter than manifest", error) }
                val hash = Bp2Digests.sha256(bytes)
                content.addChunkHash(offset / Bp2.CHUNK_BYTES, hash)
                connection.send(Bp2Kind.DATA, requireNotNull(entry.streamId), offset, hash + bytes)
                offset += length
                onProgress(offset)
            }
            if (!source.exhausted()) throw IOException("SOURCE_CHANGED: source exceeds manifest size")
        }
        content.finish()
    }
}
