package dev.boxlet.core

import dev.boxlet.core.crypto.Certificates
import dev.boxlet.core.engine.*
import dev.boxlet.core.protocol.*
import dev.boxlet.core.protocol.bp2.Bp2
import dev.boxlet.core.session.bp2.Bp2ResumeStore
import dev.boxlet.core.session.bp2.Bp2SendJournal
import dev.boxlet.core.session.bp2.newBp2Id
import dev.boxlet.core.session.bp2.*
import dev.boxlet.core.transport.bp2.*
import dev.boxlet.core.protocol.bp2.*
import dev.boxlet.core.protocol.bp2.Bp2SourceRef
import okio.source
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import okio.Buffer
import java.io.File
import java.net.ServerSocket
import java.net.Socket
import java.nio.file.Files
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlin.test.*

class Bp2EngineResumeTest {
    private val root = Files.createTempDirectory("boxlet-engine-resume-").toFile()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val leftId = Certificates.generate()
    private val rightId = Certificates.generate()
    private val engines = mutableListOf<TransferEngine>()
    private val output = File(root, "received")
    private val storage = object : ReceiveStorage {
        override fun bp2ResumeStore() = Bp2ResumeStore(File(root, "private"), output)
        override suspend fun createDestination(sessionId: String, file: FileDto, senderAlias: String): ReceiveDestination = error("Expected native persistent storage")
    }
    private var proxy: CutProxy? = null
    private val journals = mutableListOf<Bp2SendJournal>()
    private inner class CutProxy(private val receiver: TransferEngine) : AutoCloseable {
        private val listener = ServerSocket(0)
        val port get() = listener.localPort
        val connections = AtomicInteger()
        val forwarded = AtomicLong()
        val forwardedBeforeCut = AtomicLong()
        val cut = CompletableDeferred<Unit>()
        private val dropped = AtomicBoolean()
        @Volatile var refuseReconnects = false
        @Volatile var publishedBeforeCut: String? = null
        private val sockets = ConcurrentLinkedQueue<Socket>()
        private val job = scope.launch {
            while (isActive && !listener.isClosed) {
                val client = try { listener.accept() } catch (_: Exception) { break }
                sockets += client; connections.incrementAndGet()
                if (dropped.get() && refuseReconnects) { client.close(); continue }
                launch {
                    val target = Socket("127.0.0.1", receiver.bp2Port); sockets += target
                    try {
                        coroutineScope {
                            val up = launch {
                                val buffer = ByteArray(16384)
                                try {
                                    val input = client.getInputStream(); val output = target.getOutputStream()
                                    while (isActive) {
                                        val n = input.read(buffer); if (n < 0) break
                                        output.write(buffer, 0, n)
                                        val bytes = forwarded.addAndGet(n.toLong())
                                        val publicationReady = publishedBeforeCut?.let {
                                            File(this@Bp2EngineResumeTest.output, it).isFile
                                        } ?: true
                                        if (bytes > 3L * Bp2.CHUNK_BYTES + 65536 && publicationReady && dropped.compareAndSet(false, true)) {
                                            // Forwarded TLS bytes alone do not prove the receiver processed them.
                                            withTimeout(5000) { receiver.state.first { (it.incoming?.transferredBytes ?: 0) >= 2L * Bp2.CHUNK_BYTES } }
                                            forwardedBeforeCut.set(bytes)
                                            cut.complete(Unit); break
                                        }
                                    }
                                } finally { client.close(); target.close() }
                            }
                            val down = launch {
                                try { target.getInputStream().copyTo(client.getOutputStream()) }
                                finally { client.close(); target.close() }
                            }
                            joinAll(up, down)
                        }
                    } catch (_: Exception) { client.close(); target.close() }
                }
            }
        }
        override fun close() { listener.close(); sockets.forEach { runCatching { it.close() } }; job.cancel() }
    }
    private fun engine(left: Boolean, journal: Bp2SendJournal? = null): TransferEngine {
        val own = if (left) leftId else rightId
        val selectedStorage = if (!left) storage else object : ReceiveStorage {
            override suspend fun createDestination(sessionId: String, file: FileDto, senderAlias: String): ReceiveDestination = error("Unexpected receive")
        }
        return TransferEngine(scope, own, selectedStorage, journal).also {
            it.installConfig { EngineConfig(alias = if (left) "Left" else "Right", deviceModel = "Test", fingerprint = own.fingerprint,
                port = 0, bp2Port = 0, bp2Enabled = true,
                quickSave = true) }
            it.start(); engines += it
        }
    }
    private fun connect(sender: TransferEngine, receiver: TransferEngine): CutProxy {
        val link = CutProxy(receiver); proxy = link
        sender.addKnownDevice(Device(rightId.fingerprint, "Right", channels = listOf(DeviceChannel("127.0.0.1", link.port, true, Bp2.ADAPTER_ID))))
        return link
    }
    private fun file(name: String, bytes: ByteArray, opens: AtomicInteger) = OutgoingFile(name, name, bytes.size.toLong(), "application/octet-stream",
        openSource = { opens.incrementAndGet(); Buffer().write(bytes) })
    private suspend fun terminal(sender: TransferEngine) = withTimeout(30000) {
        sender.state.first { it.outgoing?.status in listOf(TransferStatus.COMPLETED, TransferStatus.FAILED, TransferStatus.CANCELLED) }.outgoing!!
    }
    @AfterTest fun close() {
        proxy?.close(); engines.forEach { it.stop() }; scope.cancel(); journals.forEach { it.close() }
        check(root.name.startsWith("boxlet-engine-resume-")); root.deleteRecursively()
    }

    @Test fun actualSendEntryReconnectsAutomaticallyAndSkipsPublishedFiles() = runBlocking {
        val receiver = engine(false); val sender = engine(true); val proxy = connect(sender, receiver)
        proxy.publishedBeforeCut = "small.bin"
        val bytes = ByteArray(12 * Bp2.CHUNK_BYTES + 7) { (it * 3).toByte() }
        val largeOpens = AtomicInteger(); val smallOpens = AtomicInteger()
        sender.send(rightId.fingerprint, listOf(file("small.bin", byteArrayOf(2), smallOpens), file("large.bin", bytes, largeOpens))) { null }
        val done = terminal(sender)
        assertEquals(TransferStatus.COMPLETED, done.status, done.error)
        assertNull(done.error)
        assertTrue(proxy.connections.get() >= 2)
        assertEquals(1, smallOpens.get()); assertEquals(3, largeOpens.get())
        // Pipelining can leave most of the bounded window in flight when the link is cut.
        // Measure the reconnect itself: at least 2 MiB was processed before the cut, so a fresh
        // 12 MiB resend must fail this bound, irrespective of the earlier unprocessed TLS bytes.
        val resumedBytes = proxy.forwarded.get() - proxy.forwardedBeforeCut.get()
        assertTrue(resumedBytes < bytes.size - Bp2.CHUNK_BYTES,
            "A fresh whole-file retransmission must not replace resume: $resumedBytes reconnect bytes")
        assertContentEquals(bytes, File(output, "large.bin").readBytes())
        assertEquals(2, output.listFiles().orEmpty().size)
    }

    @Test fun retryLimitLeavesAPausedTaskThatCanBeContinuedManually() = runBlocking {
        val receiver = engine(false); val sender = engine(true); val proxy = connect(sender, receiver)
        proxy.refuseReconnects = true
        val bytes = ByteArray(12 * Bp2.CHUNK_BYTES) { 7 }; val opens = AtomicInteger()
        sender.send(rightId.fingerprint, listOf(file("manual.bin", bytes, opens))) { null }
        withTimeout(20000) { sender.state.first { it.outgoing?.status == TransferStatus.PAUSED && it.outgoing.error == "连接中断，可继续传输" } }
        assertEquals(4, proxy.connections.get(), "One original connection and three bounded retries")
        proxy.refuseReconnects = false
        assertTrue(sender.setTransferPaused(true, false))
        assertEquals(TransferStatus.COMPLETED, terminal(sender).status)
        assertContentEquals(bytes, File(output, "manual.bin").readBytes())
    }

    private fun journal() = Bp2SendJournal(File(root, "outgoing"), leftId).also(journals::add)
    private fun diskFile(name: String, bytes: ByteArray): OutgoingFile {
        val file = File(root, name); file.writeBytes(bytes)
        return OutgoingFile(name, name, bytes.size.toLong(), "application/octet-stream",
            recoverySource = Bp2SourceRef("file", file.canonicalPath), openSource = { file.source() })
    }
    private suspend fun paused(sender: TransferEngine) = withTimeout(20000) {
        sender.state.first { it.outgoing?.status == TransferStatus.PAUSED && it.outgoing.error == "连接中断，可继续传输" }.outgoing!!
    }

    @Test fun senderRestartRestoresPausedTaskAndDoesNotReopenAnAlreadyDeliveredMissingFile() = runBlocking {
        val receiver = engine(false); val firstJournal = journal(); val sender = engine(true, firstJournal)
        val link = connect(sender, receiver); link.refuseReconnects = true
        link.publishedBeforeCut = "already.bin"
        val bytes = ByteArray(12 * Bp2.CHUNK_BYTES) { 7 }
        sender.send(rightId.fingerprint, listOf(diskFile("already.bin", byteArrayOf(1)), diskFile("restart.bin", bytes))) { null }
        val original = paused(sender)
        sender.stop(); firstJournal.close(); assertTrue(File(root, "already.bin").delete())
        val secondJournal = journal(); val restored = engine(true, secondJournal)
        assertEquals(original.sessionId, restored.state.value.outgoing?.sessionId)
        assertEquals(TransferStatus.PAUSED, restored.state.value.outgoing?.status)
        val count = link.connections.get(); delay(100); assertEquals(count, link.connections.get(), "Restart does not automatically read/send selected files")
        link.refuseReconnects = false
        assertTrue(restored.setTransferPaused(true, false))
        assertEquals(TransferStatus.COMPLETED, terminal(restored).status)
        assertContentEquals(bytes, File(output, "restart.bin").readBytes()); assertEquals(2, output.listFiles().orEmpty().size)
        assertNull(secondJournal.load())
    }

    @Test fun missingOriginalSourceKeepsTheCredentialUntilTheFileIsRestored() = runBlocking {
        val receiver = engine(false); val firstJournal = journal(); val sender = engine(true, firstJournal)
        val link = connect(sender, receiver); link.refuseReconnects = true
        val bytes = ByteArray(12 * Bp2.CHUNK_BYTES) { 9 }
        sender.send(rightId.fingerprint, listOf(diskFile("permission.bin", bytes))) { null }
        paused(sender); sender.stop(); firstJournal.close()
        val original = File(root, "permission.bin"); val temporarilyMissing = File(root, "missing.bin")
        assertTrue(original.renameTo(temporarilyMissing))
        val secondJournal = journal(); val restored = engine(true, secondJournal); link.refuseReconnects = false
        assertTrue(restored.setTransferPaused(true, false))
        withTimeout(20000) { restored.state.first { it.outgoing?.status == TransferStatus.PAUSED && "源文件不可访问" in it.outgoing.error.orEmpty() } }
        assertNotNull(secondJournal.load())
        assertTrue(original.exists().not()); assertTrue(temporarilyMissing.renameTo(original))
        assertTrue(restored.setTransferPaused(true, false))
        assertEquals(TransferStatus.COMPLETED, terminal(restored).status)
        assertContentEquals(bytes, File(output, "permission.bin").readBytes())
    }

    @Test fun cancellingWhileAdapterIsDisabledDoesNotRestoreTheCancelledJournal() = runBlocking {
        val receiver = engine(false); val saved = journal(); val sender = engine(true, saved)
        val link = connect(sender, receiver); link.refuseReconnects = true
        sender.send(rightId.fingerprint, listOf(diskFile("cancel.bin", ByteArray(12 * Bp2.CHUNK_BYTES) { 3 }))) { null }
        paused(sender)
        assertNotNull(saved.load())
        val enabled = EngineConfig(alias = "Left", deviceModel = "Test", fingerprint = leftId.fingerprint,
            port = 0, bp2Port = 0, bp2Enabled = true, quickSave = true)
        sender.installConfig { enabled.copy(bp2Enabled = false) }; sender.restart()
        assertEquals(TransferStatus.PAUSED, sender.state.value.outgoing?.status)
        sender.cancelSend()
        assertNull(saved.load())
        sender.installConfig { enabled }; sender.restart()
        assertEquals(TransferStatus.CANCELLED, sender.state.value.outgoing?.status)
        assertFalse(sender.setTransferPaused(true, false))
    }

    @Test fun changedSourceKeepsTheTaskUntilTheUserExplicitlyRestartsOnlyThatFile() = runBlocking {
        val receiver = engine(false); val saved = journal(); val sender = engine(true, saved)
        val link = connect(sender, receiver); link.refuseReconnects = true
        link.publishedBeforeCut = "done.bin"
        val bytes = ByteArray(12 * Bp2.CHUNK_BYTES) { 3 }
        sender.send(rightId.fingerprint, listOf(diskFile("done.bin", byteArrayOf(1)), diskFile("changed.bin", bytes))) { null }
        val original = paused(sender)
        bytes[0] = 9; File(root, "changed.bin").writeBytes(bytes); link.refuseReconnects = false
        assertTrue(sender.setTransferPaused(true, false))
        val changed = withTimeout(20000) { sender.state.first {
            it.outgoing?.status == TransferStatus.PAUSED && it.outgoing.files.any { file -> file.restartRequired }
        }.outgoing!! }
        assertEquals(original.sessionId, changed.sessionId); assertNotNull(saved.load())
        assertEquals(FileTransferStatus.COMPLETED, changed.files.single { it.fileName == "done.bin" }.status)
        assertFalse(sender.resetOutgoingFile(newBp2Id()))
        assertTrue(sender.resetOutgoingFile(changed.files.single { it.restartRequired }.id))
        assertEquals(TransferStatus.COMPLETED, terminal(sender).status)
        assertContentEquals(bytes, File(output, "changed.bin").readBytes()); assertEquals(2, output.listFiles().orEmpty().size)
        assertNull(saved.load())
    }

    @Test fun authenticatedTcpResumeTakesOverAnOldLiveSessionOnlyWithTheCorrectCredential() = runBlocking {
        val receiver = engine(false)
        val release = java.util.concurrent.CountDownLatch(1)
        val blocked = CompletableDeferred<Unit>(); val ticket = CompletableDeferred<Bp2ResumeTicket>()
        val opens = AtomicInteger(); val bytes = ByteArray(12 * Bp2.CHUNK_BYTES) { 5 }
        val file = Bp2OutgoingFile(Bp2Entry(newBp2Id(), path = "takeover.bin", size = bytes.size.toString()), {
            val source = Buffer().write(bytes)
            if (opens.getAndIncrement() == 0) object : okio.ForwardingSource(source) {
                private var read = 0L
                override fun read(sink: Buffer, byteCount: Long): Long {
                    if (read >= 4L * Bp2.CHUNK_BYTES) {
                        blocked.complete(Unit); check(release.await(30, java.util.concurrent.TimeUnit.SECONDS))
                    }
                    return super.read(sink, byteCount).also { if (it > 0) read += it }
                }
            } else source
        })
        val handler = object : Bp2SessionHandler {
            override suspend fun onOffer(peer: Bp2Peer, transferId: String, entries: List<Bp2Entry>) = emptySet<String>()
            override suspend fun createDestination(peer: Bp2Peer, transferId: String, entry: Bp2Entry): Bp2Destination = error("Unexpected receive")
        }
        val sessions = mutableListOf<Bp2Session>()
        suspend fun connect(): Bp2Session {
            val connection = Bp2TlsClient.connect("127.0.0.1", receiver.bp2Port, leftId, rightId.fingerprint, scope)
            return Bp2Session.open(connection, Bp2Hello(requestId = newBp2Id(), deviceId = leftId.fingerprint.lowercase(),
                alias = "Takeover", deviceType = "desktop", features = listOf("send", "receive", "batch", "resume")), true, handler, scope).also(sessions::add)
        }
        try {
            val id = newBp2Id(); val first = connect()
            val sending = scope.async { runCatching { first.send(listOf(file), id, onResumeTicket = { ticket.complete(it) }) } }
            withTimeout(15000) { blocked.await(); receiver.state.first { (it.incoming?.transferredBytes ?: 0) >= 2L * Bp2.CHUNK_BYTES } }
            val credential = ticket.await()
            val invalid = connect()
            assertFails { withTimeout(10000) { invalid.send(listOf(file), id, credential.copy(resumeToken = "0".repeat(64))) } }
            invalid.close()
            assertEquals(TransferStatus.TRANSFERRING, receiver.state.value.incoming?.status, "Invalid token must not evict the live session")
            val resumed = connect()
            assertEquals(1, withTimeout(30000) { resumed.send(listOf(file), id, credential) }.completed.size)
            assertContentEquals(bytes, File(output, "takeover.bin").readBytes()); assertEquals(1, output.listFiles().orEmpty().size)
            release.countDown(); assertTrue(withTimeout(10000) { sending.await() }.isFailure)
        } finally { release.countDown(); sessions.forEach { it.close() } }
    }
}
