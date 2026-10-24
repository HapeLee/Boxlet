package dev.boxlet.core

import dev.boxlet.core.crypto.Certificates
import dev.boxlet.core.protocol.bp2.*
import dev.boxlet.core.session.bp2.*
import dev.boxlet.core.transport.bp2.*
import kotlinx.coroutines.*
import okio.Buffer
import okio.ForwardingSource
import java.io.File
import java.nio.file.Files
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.*

class Bp2ResumeSessionTest {
    private val root = Files.createTempDirectory("boxlet-resume-session-").toFile()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val sender = Certificates.generate()
    private val receiver = Certificates.generate()
    private val sessions = mutableListOf<Bp2Session>()
    private val remote = AtomicReference<Bp2Session>()
    private var server: Bp2TlsServer? = null
    private var ticket: Bp2ResumeTicket? = null
    private val target = File(root, "received")
    private var store = Bp2ResumeStore(File(root, "private"), target)
    private var cutAt = 2L * Bp2.CHUNK_BYTES
    private val cut = AtomicInteger()
    private val resumedOffsets = mutableListOf<Long>()
    private val ended = java.util.concurrent.ConcurrentLinkedQueue<String>()
    private val errors = java.util.concurrent.ConcurrentLinkedQueue<String>()
    private val senderResumePositions = java.util.concurrent.ConcurrentLinkedQueue<List<Bp2ResumePosition>>()
    private val handler = object : Bp2SessionHandler {
        override val resumeStore get() = store
        override suspend fun onOffer(peer: Bp2Peer, transferId: String, entries: List<Bp2Entry>) = entries.map { it.fileId }.toSet()
        override suspend fun createDestination(peer: Bp2Peer, transferId: String, entry: Bp2Entry): Bp2Destination = error("Expected direct resumable storage")
        override fun onProgress(transferId: String, fileId: String, written: Long, outgoing: Boolean) {
            if (!outgoing && written >= cutAt && cut.compareAndSet(0, 1)) remote.get().close()
        }
        override fun onResumePositions(transferId: String, positions: List<Bp2ResumePosition>, outgoing: Boolean) {
            if (!outgoing) resumedOffsets += positions.filter { it.completedRoot == null }.map { Bp2.decimal(it.offset) }
        }
        override fun onTransferEnded(transferId: String, status: String, outgoing: Boolean) { ended += status }
    }
    private val senderHandler = object : Bp2SessionHandler {
        override suspend fun onOffer(peer: Bp2Peer, transferId: String, entries: List<Bp2Entry>) = emptySet<String>()
        override suspend fun createDestination(peer: Bp2Peer, transferId: String, entry: Bp2Entry): Bp2Destination = error("Unexpected receive")
        override fun onResumePositions(transferId: String, positions: List<Bp2ResumePosition>, outgoing: Boolean) {
            if (outgoing) senderResumePositions += positions
        }
    }
    private fun hello(id: String) = Bp2Hello(requestId = newBp2Id(), deviceId = id.lowercase(), alias = "Resume", deviceType = "desktop",
        features = listOf("send", "receive", "batch", "resume", "reset-file"))
    private suspend fun connect(): Bp2Session {
        if (server == null) {
            server = Bp2TlsServer(receiver, scope, { connection ->
                val session = Bp2Session.open(connection, hello(receiver.fingerprint), false, handler, scope)
                remote.set(session); session.awaitClosed()
            }, { errors += it.stackTraceToString() })
            server!!.start(0)
        }
        val connection = Bp2TlsClient.connect("127.0.0.1", server!!.port, sender, receiver.fingerprint, scope)
        return Bp2Session.open(connection, hello(sender.fingerprint), true, senderHandler, scope).also { sessions += it }
    }
    private fun file(name: String, bytes: ByteArray, opens: AtomicInteger) = Bp2OutgoingFile(
        Bp2Entry(newBp2Id(), path = name, size = bytes.size.toString()), {
            opens.incrementAndGet()
            object : ForwardingSource(Buffer().write(bytes)) {
                override fun read(sink: Buffer, byteCount: Long): Long { Thread.sleep(1); return super.read(sink, byteCount) }
            }
        })
    @AfterTest fun close() {
        sessions.forEach { it.close() }; server?.close(); scope.cancel()
        check(root.name.startsWith("boxlet-resume-session-")); root.deleteRecursively()
    }

    @Test fun reconnectUsesDurableOffsetAndDoesNotRepublishCompletedFiles() = runBlocking {
        val smallOpens = AtomicInteger(); val largeOpens = AtomicInteger(); val emptyOpens = AtomicInteger()
        val bytes = ByteArray(12 * Bp2.CHUNK_BYTES + 3) { (it * 7).toByte() }
        val files = listOf(file("small.bin", byteArrayOf(7), smallOpens), file("large.bin", bytes, largeOpens), file("empty.bin", byteArrayOf(), emptyOpens))
        val first = connect(); val id = newBp2Id()
        assertFailsWith<Bp2ConnectionLost> { withTimeout(20000) { first.send(files, id, onResumeTicket = { ticket = it }) } }
        withTimeout(10000) { remote.get().awaitClosed() }; first.close()
        store = Bp2ResumeStore(File(root, "private"), target) // Recreate the repository, including disk metadata reload.
        assertTrue(ended.contains("disconnected"), "Ended=$ended errors=$errors cause=${remote.get().terminationCause?.stackTraceToString()}")
        assertFalse(File(root, "private/transfers/$id/revoked").exists(), "Ended=$ended errors=$errors")
        val second = connect()
        val result = withTimeout(30000) { second.send(files, id, ticket) }
        assertEquals(3, result.completed.size)
        assertTrue(resumedOffsets.any { it >= 2L * Bp2.CHUNK_BYTES })
        assertContentEquals(bytes, File(target, "large.bin").readBytes())
        assertEquals(3, target.listFiles().orEmpty().size)
        assertEquals(1, smallOpens.get()); assertEquals(1, emptyOpens.get())
        assertEquals(3, largeOpens.get(), "Initial source + prefix check + the verified source used for remaining DATA")
        // Lost TRANSFER_DONE can be queried from a third connection without reopening any source.
        second.close(); withTimeout(10000) { remote.get().awaitClosed() }
        val before = largeOpens.get()
        assertEquals(3, withTimeout(30000) { connect().send(files, id, ticket) }.completed.size)
        assertEquals(before, largeOpens.get()); assertEquals(3, target.listFiles().orEmpty().size)
    }

    @Test fun changedPrefixPausesUntilExplicitResetWithoutRepeatingOtherFiles() = runBlocking {
        val bytes = ByteArray(10 * Bp2.CHUNK_BYTES) { 7 }; val opens = AtomicInteger()
        val file = file("changed.bin", bytes, opens); val id = newBp2Id(); val smallOpens = AtomicInteger()
        val untouched = ByteArray(10 * Bp2.CHUNK_BYTES) { 3 }
        val files = listOf(file("done.bin", byteArrayOf(2), smallOpens), file, file("untouched.bin", untouched, AtomicInteger()))
        val first = connect()
        assertFailsWith<Bp2ConnectionLost> { first.send(files, id, onResumeTicket = { ticket = it }) }
        withTimeout(10000) { remote.get().awaitClosed() }; first.close()
        bytes[10] = 8
        assertTrue(File(target, "done.bin").isFile)
        senderResumePositions.clear()
        val second = connect()
        val failure = assertFailsWith<Bp2SourcePrefixChanged> { withTimeout(20000) { second.send(files, id, ticket) } }
        assertTrue(senderResumePositions.any { positions -> positions.any {
            it.fileId == files.first().entry.fileId && it.completedRoot != null
        } }, "Completed results must be reported even when another source fails prefix verification before READY")
        assertEquals(file.entry.fileId, failure.fileId)
        second.close(); withTimeout(10000) { remote.get().awaitClosed() }
        assertFalse(File(target, "changed.bin").exists())
        store.resume(sender.fingerprint.lowercase(), ticket!!).use { lease ->
            assertTrue(Bp2.decimal(lease.positions().single { it.fileId == file.entry.fileId }.offset) > 0)
        }
        val resets = mutableListOf<String>()
        val result = withTimeout(30000) { connect().send(files, id, ticket, resetFiles = setOf(file.entry.fileId), onFileReset = { resets += it }) }
        assertEquals(3, result.completed.size); assertEquals(listOf(file.entry.fileId), resets)
        assertContentEquals(bytes, File(target, "changed.bin").readBytes())
        assertContentEquals(untouched, File(target, "untouched.bin").readBytes())
        assertEquals(1, smallOpens.get()); assertEquals(3, target.listFiles().orEmpty().size)
        assertTrue(resumedOffsets.contains(0)); assertTrue(resumedOffsets.any { it >= Bp2.CHUNK_BYTES })
    }

    @Test fun disconnectAfterResetAcknowledgementKeepsZeroAndCanResumeWithoutAnotherReset() = runBlocking {
        val bytes = ByteArray(10 * Bp2.CHUNK_BYTES) { 7 }
        val file = file("ack.bin", bytes, AtomicInteger()); val id = newBp2Id(); val first = connect()
        assertFailsWith<Bp2ConnectionLost> { first.send(listOf(file), id, onResumeTicket = { ticket = it }) }
        first.close(); withTimeout(10000) { remote.get().awaitClosed() }; bytes[0] = 9
        val second = connect()
        assertFailsWith<Bp2ConnectionLost> {
            second.send(listOf(file), id, ticket, resetFiles = setOf(file.entry.fileId),
                onFileReset = { second.close(); throw Bp2ConnectionLost() })
        }
        withTimeout(10000) { remote.get().awaitClosed() }
        store.resume(sender.fingerprint.lowercase(), ticket!!).use { assertEquals("0", it.positions().single().offset) }
        assertEquals(1, withTimeout(30000) { connect().send(listOf(file), id, ticket) }.completed.size)
        assertContentEquals(bytes, File(target, "ack.bin").readBytes())
    }

    @Test fun explicitCancellationInvalidatesTheCredentialAndRemovesThePartialBytes() = runBlocking {
        val id = newBp2Id(); val first = connect()
        assertFailsWith<Bp2ConnectionLost> { first.send(listOf(file("cancel.bin", ByteArray(10 * Bp2.CHUNK_BYTES), AtomicInteger())), id,
            onResumeTicket = { ticket = it }) }
        withTimeout(10000) { remote.get().awaitClosed() }; first.close()
        withTimeout(15000) { connect().cancelRetained(ticket!!) }
        assertFails { store.resume(sender.fingerprint.lowercase(), ticket!!) }
        assertTrue(File(root, "private/chunks").walkTopDown().none { it.name == "data.part" })
    }


}
