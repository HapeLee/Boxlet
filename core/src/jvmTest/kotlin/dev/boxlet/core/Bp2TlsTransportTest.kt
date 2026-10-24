package dev.boxlet.core

import dev.boxlet.core.crypto.Certificates
import dev.boxlet.core.protocol.bp2.*
import dev.boxlet.core.transport.bp2.*
import dev.boxlet.core.session.bp2.*
import okio.Buffer
import okio.sink
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import java.nio.file.Files
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.*

class Bp2TlsTransportTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val sender = Certificates.generate()
    private val receiver = Certificates.generate()

    @AfterTest fun cleanup() { scope.cancel() }
    private fun control(type: String, value: String? = null) = buildJsonObject {
        put("type", type); if (value != null) put("value", value)
    }

    @Test fun mutualTlsStreamsFileAndBatchesWithVerifiedContentBeforePublishing() = runBlocking {
        val bytes = ByteArray(2 * Bp2.CHUNK_BYTES + 3) { (it * 31).toByte() }
        val published = CompletableDeferred<ByteArray>()
        val server = Bp2TlsServer(receiver, scope,
            onConnection = { connection ->
                assertEquals(sender.fingerprint.lowercase(), connection.peerFingerprint)
                val hello = Bp2Controls.decode(connection.receive()!!.payload)
                assertEquals("hello", hello.getValue("type").jsonPrimitive.content)
                val helloDto = Bp2.json.decodeFromJsonElement<Bp2Hello>(hello)
                helloDto.validateNative(connection.peerFingerprint)
                connection.sendControl(control("hello-ack"))
                val part = Files.createTempFile("boxlet-bp2", ".part")
                try {
                    val incoming = Bp2IncomingFile(Bp2Entry("2".repeat(32), path = "file.bin",
                        size = bytes.size.toString(), streamId = 1), object : Bp2Destination {
                        override val sink = Files.newOutputStream(part).sink()
                        override suspend fun commit() { published.complete(Files.readAllBytes(part)) }
                        override suspend fun abort() { published.completeExceptionally(AssertionError("Transfer aborted")) }
                    })
                    while (incoming.written < bytes.size) {
                        assertTrue(incoming.accept(connection.receive()!!))
                    }
                    val batch = connection.receive()!!
                    assertEquals(Bp2Kind.BATCH, batch.header.kind)
                    val records = Bp2Payloads.decodeBatch(batch.payload)
                    assertEquals(listOf(3L, 5L), records.map { it.streamId })
                    records.forEach { assertContentEquals(Bp2Digests.sha256(it.bytes), it.sha256) }
                    val finish = Bp2Controls.decode(connection.receive()!!.payload)
                    incoming.finish(finish.getValue("value").jsonPrimitive.content)
                    assertEquals(Bp2IncomingFile.State.COMPLETED, incoming.state)
                    connection.sendControl(control("files-done"))
                } finally { Files.deleteIfExists(part) }
            }, onFailure = { published.completeExceptionally(it) })
        val port = server.start(0)
        try {
            val client = Bp2TlsClient.connect("127.0.0.1", port, sender, receiver.fingerprint, scope)
            client.use {
                val hello = Bp2Hello(requestId = "1".repeat(32), deviceId = sender.fingerprint.lowercase(),
                    alias = "Sender", deviceType = "desktop", features = listOf("send"))
                client.sendControl(Bp2.json.encodeToJsonElement(hello).jsonObject)
                assertEquals("hello-ack", Bp2Controls.decode(client.receive()!!.payload).getValue("type").jsonPrimitive.content)
                val opens = AtomicInteger()
                val credit = Bp2SendCredit()
                val root = Bp2FileSender.send(Bp2Entry("2".repeat(32), path = "file.bin",
                    size = bytes.size.toString(), streamId = 1),
                    { opens.incrementAndGet(); Buffer().write(bytes) }, client,
                    beforeChunk = { assertTrue(credit.reserve(it)) })
                assertEquals(1, opens.get())
                client.send(Bp2Kind.BATCH, payload = Bp2Payloads.encodeBatch(listOf("small", "files").mapIndexed { index, text ->
                    val data = text.encodeToByteArray()
                    Bp2BatchRecord(index * 2 + 3L, data, Bp2Digests.sha256(data))
                }))
                client.sendControl(control("files-finish", root))
                assertEquals("files-done", Bp2Controls.decode(client.receive()!!.payload).getValue("type").jsonPrimitive.content)
                assertContentEquals(bytes, withTimeout(10000) { published.await() })
            }
        } finally { server.close() }
    }

    @Test fun wrongServerPinNeverReachesApplicationHandler() = runBlocking {
        val failed = CompletableDeferred<Unit>()
        val handled = AtomicInteger()
        val server = Bp2TlsServer(receiver, scope, onConnection = { handled.incrementAndGet() },
            onFailure = { failed.complete(Unit) })
        val port = server.start(0)
        try {
            assertFails { Bp2TlsClient.connect("127.0.0.1", port, sender, "0".repeat(64), scope) }
            withTimeout(10000) { failed.await() }
            assertEquals(0, handled.get())
        } finally { server.close() }
    }

    @Test fun clientWithoutADeviceCertificateNeverReachesApplicationHandler() = runBlocking {
        val failed = CompletableDeferred<Unit>()
        val handled = AtomicInteger()
        val server = Bp2TlsServer(receiver, scope, onConnection = { handled.incrementAndGet() },
            onFailure = { failed.complete(Unit) })
        val port = server.start(0)
        try {
            val context = javax.net.ssl.SSLContext.getInstance("TLS").apply {
                init(null, arrayOf(Certificates.clientTrustManager(receiver.fingerprint)), java.security.SecureRandom())
            }
            withContext(Dispatchers.IO) {
                context.socketFactory.createSocket("127.0.0.1", port).use { raw ->
                    val socket = raw as javax.net.ssl.SSLSocket
                    socket.soTimeout = 3000
                    runCatching { socket.startHandshake(); socket.inputStream.read() }
                }
            }
            withTimeout(10000) { failed.await() }
            assertEquals(0, handled.get())
        } finally { server.close() }
    }

    @Test fun closingServerReleasesBlockedReadersAndListeningPort() = runBlocking {
        val accepted = CompletableDeferred<Unit>()
        val stopped = CompletableDeferred<Unit>()
        val server = Bp2TlsServer(receiver, scope, onConnection = {
            accepted.complete(Unit)
            try { it.receive() } finally { stopped.complete(Unit) }
        })
        val port = server.start(0)
        try {
            Bp2TlsClient.connect("127.0.0.1", port, sender, receiver.fingerprint, scope).use {
                withTimeout(10000) { accepted.await() }
                val start = System.nanoTime()
                server.close()
                assertTrue((System.nanoTime() - start) < 2_000_000_000, "Close must not wait for the read timeout")
                withTimeout(10000) { stopped.await() }
            }
            java.net.ServerSocket(port).use { assertEquals(port, it.localPort) }
        } finally { server.close() }
    }

    @Test fun groupedControlsKeepIndividualSequencesAndRejectOversizedGroupsBeforeWriting() = runBlocking {
        val accepted = CompletableDeferred<Bp2TlsConnection>()
        val server = Bp2TlsServer(receiver, scope, {
            accepted.complete(it); awaitCancellation()
        }, { accepted.completeExceptionally(it) })
        val port = server.start(0)
        try {
            Bp2TlsClient.connect("127.0.0.1", port, sender, receiver.fingerprint, scope).use { client ->
                val peer = withTimeout(10000) { accepted.await() }
                client.sendControls(listOf(control("ack", "first"), control("window-update", "second")))
                repeat(2) { index ->
                    val frame = withTimeout(10000) { peer.receive()!! }
                    assertEquals(Bp2Kind.CONTROL, frame.header.kind)
                    assertEquals(index + 1L, frame.header.sequence)
                    assertEquals(if (index == 0) "first" else "second", Bp2Controls.decode(frame.payload)["value"]!!.jsonPrimitive.content)
                }
                assertFailsWith<IllegalArgumentException> { client.sendControls(emptyList()) }
                assertFailsWith<IllegalArgumentException> { client.sendControls(List(65) { control("ack") }) }
                assertFailsWith<IllegalArgumentException> {
                    client.sendControls(List(2) { control("ack", "x".repeat(Bp2.CONTROL_BYTES / 2)) })
                }
                // Validation must not publish the first item of a rejected group or consume a sequence.
                client.sendControl(control("after-rejected-group"))
                val next = withTimeout(10000) { peer.receive()!! }
                assertEquals(3L, next.header.sequence)
                assertEquals("after-rejected-group", Bp2Controls.decode(next.payload)["type"]!!.jsonPrimitive.content)
            }
        } finally { server.close() }
    }
}
