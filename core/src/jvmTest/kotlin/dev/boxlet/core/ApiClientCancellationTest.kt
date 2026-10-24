package dev.boxlet.core

import dev.boxlet.core.crypto.Certificates
import dev.boxlet.core.engine.OutgoingFile
import dev.boxlet.core.net.*
import dev.boxlet.core.protocol.*
import kotlinx.coroutines.*
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okio.Buffer
import okio.Source
import okio.Timeout
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicLong
import kotlin.test.*

class ApiClientCancellationTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val api = ApiClient(Certificates.generate())
    private val sockets = ConcurrentLinkedQueue<Socket>()
    private var listener: ServerSocket? = null
    @AfterTest fun close() { api.cancelAll(); listener?.close(); sockets.forEach { it.close() }; scope.cancel() }

    private fun peer(block: suspend (Socket) -> Unit): DeviceChannel {
        val server = ServerSocket(0); listener = server
        scope.launch { val socket = server.accept(); sockets += socket; socket.soTimeout = 3000; socket.use { block(it) } }
        return DeviceChannel("127.0.0.1", server.localPort, false)
    }
    private fun headers(socket: Socket): Map<String, String> {
        val input = socket.getInputStream()
        assertNotNull(readHttpLine(input))
        val fields = linkedMapOf<String, String>()
        while (true) {
            val line = readHttpLine(input) ?: error("Incomplete request")
            if (line.isEmpty()) return fields
            fields[line.substringBefore(':').lowercase()] = line.substringAfter(':').trim()
        }
    }
    private fun file(size: Long = 1, source: () -> Source = { Buffer().writeByte(1) }) =
        OutgoingFile("file", "file.bin", size, "application/octet-stream", openSource = source)

    @Test fun cancellingPreparationClosesTheCallWhileThePeerWaitsForUserDecision() = runBlocking {
        val requested = CompletableDeferred<Unit>(); val disconnected = CompletableDeferred<Unit>()
        val channel = peer { socket ->
            val fields = headers(socket)
            socket.getInputStream().readNBytes(fields["content-length"]!!.toInt())
            requested.complete(Unit)
            assertEquals(-1, socket.getInputStream().read()); disconnected.complete(Unit)
        }
        val pending = async { api.prepareUpload(channel, null, PrepareUploadRequest(RegisterRequest("test", fingerprint = "test"), emptyMap()), null) }
        withTimeout(3000) { requested.await() }
        withTimeout(3000) { pending.cancelAndJoin(); disconnected.await() }
    }

    @Test fun cancellationInterruptsAStalledSocketWriteAndClosesTheSource() = runBlocking {
        val requested = CompletableDeferred<Unit>(); val closed = CompletableDeferred<Unit>()
        val read = AtomicLong(); val size = 256L * 1024 * 1024
        val channel = peer { socket -> headers(socket); requested.complete(Unit); awaitCancellation() }
        val upload = async { api.upload(channel, null, "session", "file", "token", file(size) {
            object : Source {
                override fun timeout() = Timeout.NONE
                override fun close() { closed.complete(Unit) }
                override fun read(sink: Buffer, byteCount: Long): Long {
                    val count = minOf(byteCount, size - read.get()).toInt()
                    if (count == 0) return -1
                    sink.write(ByteArray(count)); read.addAndGet(count.toLong()); return count.toLong()
                }
            }
        }, { false }, {}) }
        withTimeout(3000) { requested.await() }
        delay(100)
        assertTrue(read.get() < size, "The peer must actually hold back the request body")
        withTimeout(3000) { upload.cancelAndJoin(); closed.await() }
    }

    @Test fun cancellationClosesAPartialResponseBodyAndTheClientRemainsReusable() = runBlocking {
        val started = CompletableDeferred<Unit>(); val disconnected = CompletableDeferred<Unit>()
        val channel = peer { socket ->
            val fields = headers(socket); socket.getInputStream().readNBytes(fields["content-length"]!!.toInt())
            socket.getOutputStream().write("HTTP/1.1 200 OK\r\nContent-Length: 100000\r\n\r\nx".toByteArray()); socket.getOutputStream().flush()
            started.complete(Unit)
            assertEquals(-1, socket.getInputStream().read()); disconnected.complete(Unit)
        }
        val upload = async { api.upload(channel, null, "session", "file", "token", file(), { false }, {}) }
        withTimeout(3000) { started.await() }
        withTimeout(3000) { upload.cancelAndJoin(); disconnected.await() }
        val server = LocalSendHttpServer(port = 0, scope = scope, https = false) {
            it.body.readBytes(); HttpResponse.text(200, "ready")
        }
        try {
            val port = server.start(); api.cancelAll()
            assertEquals("ready", api.info(DeviceChannel("127.0.0.1", port, false), null).body)
            assertEquals(200, api.upload(DeviceChannel("127.0.0.1", port, false), null,
                "session", "file", "token", file(), { false }, {}).code)
        } finally { server.stop() }
    }

    @Test fun showArgumentsAreEncodedAsJsonIncludingControlCharacters() {
        val arguments = listOf("line\nbreak", "tab\tvalue", "quote\"slash\\", "中文")
        val server = LocalSendHttpServer(port = 0, scope = scope, https = false) {
            val values = BoxletJson.parseToJsonElement(it.readBodyText()).jsonObject["args"]!!.jsonArray.map { value -> value.jsonPrimitive.content }
            assertEquals(arguments, values)
            HttpResponse.empty(204)
        }
        try { assertEquals(204, api.show(server.start(), "token", arguments).code) } finally { server.stop() }
    }
}
