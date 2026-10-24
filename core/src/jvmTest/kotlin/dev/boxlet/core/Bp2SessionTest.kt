package dev.boxlet.core

import dev.boxlet.core.crypto.Certificates
import dev.boxlet.core.protocol.bp2.*
import dev.boxlet.core.session.bp2.*
import dev.boxlet.core.transport.bp2.*
import kotlinx.coroutines.*
import okio.Buffer
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import java.io.IOException
import kotlin.test.*

class Bp2SessionTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val clientIdentity = Certificates.generate()
    private val serverIdentity = Certificates.generate()
    private val clientStore = Store()
    private val serverStore = Store()
    private var server: Bp2TlsServer? = null
    private var client: Bp2Session? = null

    private class Store : Bp2SessionHandler {
        val saved = ConcurrentHashMap<String, ByteArray>()
        val commits = AtomicInteger()
        val aborts = AtomicInteger()
        val sent = ConcurrentHashMap.newKeySet<String>()
        var failCommitPath: String? = null
        var decision: suspend (List<Bp2Entry>) -> Set<String> = { it.map { e -> e.fileId }.toSet() }
        var delayMillis = 0L
        var progressDelayMillis = 0L
        val progressed = CompletableDeferred<Unit>()
        @Volatile var receivedBytes = 0L
        override fun onProgress(transferId: String, fileId: String, written: Long, outgoing: Boolean) {
            if (!outgoing) {
                receivedBytes = written; progressed.complete(Unit)
                if (progressDelayMillis > 0) Thread.sleep(progressDelayMillis)
            }
        }
        override suspend fun onOffer(peer: Bp2Peer, transferId: String, entries: List<Bp2Entry>) = decision(entries)
        override suspend fun createDestination(peer: Bp2Peer, transferId: String, entry: Bp2Entry): Bp2Destination {
            if (delayMillis > 0) delay(delayMillis)
            val buffer = Buffer()
            return object : Bp2Destination {
                override val sink = buffer
                override suspend fun commit() {
                    if (entry.path == failCommitPath) throw IOException("Simulated full disk")
                    saved[entry.path] = buffer.readByteArray(); commits.incrementAndGet()
                }
                override suspend fun abort() { aborts.incrementAndGet() }
            }
        }
        override fun onSent(transferId: String, entry: Bp2Entry) { sent += entry.path }
    }

    @AfterTest fun close() { client?.close(); server?.close(); scope.cancel() }
    private fun hello(fingerprint: String, alias: String, maxStreams: Int = 8) = Bp2Hello(requestId = newBp2Id(),
        deviceId = fingerprint.lowercase(), alias = alias, deviceType = "desktop", features = listOf("send", "receive", "batch", "pause"),
        limits = Bp2Limits(maxActiveStreams = maxStreams))

    private suspend fun pair(maxStreams: Int = 8): Pair<Bp2Session, Bp2Session> {
        val accepted = CompletableDeferred<Bp2Session>()
        server = Bp2TlsServer(serverIdentity, scope, { it == clientIdentity.fingerprint.lowercase() }, {
            val session = Bp2Session.open(it, hello(serverIdentity.fingerprint, "Server", maxStreams), false, serverStore, scope)
            accepted.complete(session)
            session.awaitClosed()
        }, { accepted.completeExceptionally(it) })
        val port = server!!.start(0)
        val connection = Bp2TlsClient.connect("127.0.0.1", port, clientIdentity, serverIdentity.fingerprint, scope)
        val outgoing = Bp2Session.open(connection, hello(clientIdentity.fingerprint, "Client"), true, clientStore, scope)
        client = outgoing
        return outgoing to withTimeout(10000) { accepted.await() }
    }

    private fun file(name: String, data: ByteArray, opens: AtomicInteger? = null) = Bp2OutgoingFile(
        Bp2Entry(newBp2Id(), path = name, size = data.size.toString()),
        { opens?.incrementAndGet(); Buffer().write(data) },
    )

    @Test fun paginatedMixedTransferExceedsWindowAndEverySourceIsReadOnce() = runBlocking {
        val (session, _) = pair()
        serverStore.delayMillis = 2
        val opens = AtomicInteger()
        val content = linkedMapOf("large.bin" to ByteArray(10 * Bp2.CHUNK_BYTES + 9) { (it * 17).toByte() })
        repeat(300) { content["folder/item-$it.txt"] = ByteArray(it % 97) { index -> (index + it).toByte() } }
        val files = content.map { (name, bytes) -> file(name, bytes, opens) }
        val result = withTimeout(30000) { session.send(files) }
        assertEquals(files.size, result.completed.size)
        assertTrue(result.rejected.isEmpty())
        assertEquals(files.size, opens.get())
        assertEquals(files.size, serverStore.commits.get())
        content.forEach { (name, bytes) -> assertContentEquals(bytes, serverStore.saved[name], name) }
    }

    @Test fun oneConnectionSupportsSimultaneousBidirectionalTransfersAndReuse() = runBlocking {
        val (left, right) = pair()
        withTimeout(20000) {
            coroutineScope {
                val a = async { left.send(listOf(file("left.bin", ByteArray(3 * Bp2.CHUNK_BYTES) { 3 }))) }
                val b = async { right.send(listOf(file("right.bin", ByteArray(3 * Bp2.CHUNK_BYTES) { 7 }))) }
                assertEquals(1, a.await().completed.size); assertEquals(1, b.await().completed.size)
            }
            left.send(listOf(file("again.txt", "reused".encodeToByteArray())))
        }
        assertContentEquals("reused".encodeToByteArray(), serverStore.saved["again.txt"])
        assertEquals(2, serverStore.commits.get()); assertEquals(1, clientStore.commits.get())
    }

    @Test fun selectiveAcceptAndFullRejectionDoNotWriteRejectedFiles() = runBlocking {
        val (session, _) = pair()
        serverStore.decision = { entries -> entries.filter { it.path == "keep.txt" }.map { it.fileId }.toSet() }
        val result = withTimeout(10000) { session.send(listOf(file("keep.txt", byteArrayOf(1)), file("skip.txt", byteArrayOf(2)))) }
        assertEquals(1, result.completed.size); assertEquals(1, result.rejected.size)
        assertFalse(serverStore.saved.containsKey("skip.txt"))
        serverStore.decision = { emptySet() }
        assertFailsWith<Bp2Rejected> { withTimeout(10000) { session.send(listOf(file("no.txt", byteArrayOf(3)))) } }
        assertEquals(1, serverStore.commits.get())
    }

    @Test fun pendingConfirmationCanBeCancelledWithoutBlockingTheOtherDirection() = runBlocking {
        val (left, right) = pair()
        val requested = CompletableDeferred<Unit>()
        val never = CompletableDeferred<Set<String>>()
        serverStore.decision = { requested.complete(Unit); never.await() }
        val sending = async { runCatching { left.send(listOf(file("waiting.txt", byteArrayOf(1)))) } }
        withTimeout(10000) { requested.await() }
        withTimeout(10000) { right.send(listOf(file("reverse.txt", byteArrayOf(2)))) }
        sending.cancelAndJoin()
        assertEquals(0, serverStore.commits.get())
        assertContentEquals(byteArrayOf(2), clientStore.saved["reverse.txt"])
    }

    @Test fun oneStreamLimitStillAllowsMixedSmallAndLargeFiles() = runBlocking {
        val (session, _) = pair(maxStreams = 1)
        val files = listOf(file("big", ByteArray(2 * Bp2.CHUNK_BYTES) { 3 })) +
            List(12) { file("small-$it", ByteArray(30) { it.toByte() }) }
        assertEquals(13, withTimeout(20000) { session.send(files) }.completed.size)
    }

    @Test fun groupedPublicationReportsOtherSuccessesWhenFirstFileCannotCommit() = runBlocking {
        val (left, _) = pair()
        serverStore.failCommitPath = "small-0"
        assertFailsWith<IOException> {
            withTimeout(10000) { left.send(List(8) { file("small-$it", byteArrayOf(it.toByte())) }) }
        }
        assertEquals((1..7).map { "small-$it" }.toSet(), clientStore.sent)
        assertEquals(clientStore.sent.toSet(), serverStore.saved.keys.toSet())
        assertEquals(1, serverStore.aborts.get())
        // A storage failure is a transfer result, not a broken TLS session.
        withTimeout(10000) { left.send(listOf(file("after-failure", byteArrayOf(9)))) }
        assertContentEquals(byteArrayOf(9), serverStore.saved["after-failure"])
    }

    @Test fun receiverCanPauseAndContinueWithoutBlockingReverseTransfersOrReopeningSource() = runBlocking {
        val (left, right) = pair()
        serverStore.progressDelayMillis = 20
        val opens = AtomicInteger()
        val bytes = ByteArray(32 * Bp2.CHUNK_BYTES) { 9 }
        val id = newBp2Id()
        val sending = async { left.send(listOf(file("paused.bin", bytes, opens)), id) }
        withTimeout(10000) { serverStore.progressed.await() }
        right.setPaused(id, true)
        delay(150) // Drain the bounded in-flight window; no new DATA may be scheduled after pause.
        val retained = serverStore.receivedBytes
        delay(150)
        assertEquals(retained, serverStore.receivedBytes)
        assertTrue(retained < bytes.size)
        withTimeout(10000) { right.send(listOf(file("reverse-while-paused", byteArrayOf(3)))) }
        assertContentEquals(byteArrayOf(3), clientStore.saved["reverse-while-paused"])
        right.setPaused(id, false)
        assertEquals(1, withTimeout(20000) { sending.await() }.completed.size)
        assertEquals(1, opens.get())
        assertContentEquals(bytes, serverStore.saved["paused.bin"])
    }

    @Test fun senderPauseIsIdempotentAndCancellationWakesItsPausedScheduler() = runBlocking {
        val (left, _) = pair()
        serverStore.progressDelayMillis = 20
        val id = newBp2Id()
        val sending = async { left.send(listOf(file("cancel-paused", ByteArray(32 * Bp2.CHUNK_BYTES))), id) }
        withTimeout(10000) { serverStore.progressed.await() }
        left.setPaused(id, true); left.setPaused(id, true)
        withTimeout(3000) { sending.cancelAndJoin() }
        assertEquals(0, serverStore.commits.get())
    }
}
