package dev.boxlet.core

import dev.boxlet.core.engine.ReceiveDestination
import dev.boxlet.core.engine.ReceiveStorage
import dev.boxlet.core.protocol.FileDto
import dev.boxlet.core.protocol.BoxletJson
import dev.boxlet.core.sp.Accept
import dev.boxlet.core.sp.BridgeServer
import dev.boxlet.core.sp.DataEnd
import dev.boxlet.core.sp.DataStart
import dev.boxlet.core.sp.Done
import dev.boxlet.core.sp.Hello
import dev.boxlet.core.sp.Offer
import dev.boxlet.core.sp.OfferFile
import dev.boxlet.core.sp.Sp
import dev.boxlet.core.sp.SpControl
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import okio.buffer
import okio.sink
import org.junit.After
import org.junit.Test
import java.io.File
import java.net.URI
import java.net.http.HttpClient
import java.net.http.WebSocket
import java.nio.ByteBuffer
import java.nio.file.Files
import java.util.concurrent.CompletionStage
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** SP/1 断点续传：同一 bridge 进程内断线重连，按 `accept.resumeFrom` 从断点续写。 */
class BridgeResumeTest {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val directory: File = Files.createTempDirectory("boxlet-resume").toFile()

    @After
    fun tearDown() {
        scope.cancel()
        directory.deleteRecursively()
    }

    private fun storage(): ReceiveStorage = object : ReceiveStorage {
        override suspend fun createDestination(
            sessionId: String,
            file: FileDto,
            senderAlias: String,
        ): ReceiveDestination {
            val target = File(directory, file.fileName)
            val part = File(directory, "${file.fileName}.part")
            val sink = part.sink().buffer()
            return object : ReceiveDestination {
                override val sink = sink
                override val displayPath: String = target.absolutePath
                override suspend fun commit() {
                    sink.flush()
                    sink.close()
                    check(part.renameTo(target)) { "cannot rename part file" }
                }

                override suspend fun abort() {
                    runCatching { sink.close() }
                    runCatching { part.delete() }
                }
            }
        }
    }

    private class Listener : WebSocket.Listener {
        val messages = LinkedBlockingQueue<Pair<Boolean, ByteArray>>()

        override fun onText(webSocket: WebSocket, data: CharSequence, last: Boolean): CompletionStage<*>? {
            if (last) messages.put(true to data.toString().encodeToByteArray())
            webSocket.request(1)
            return null
        }
    }

    @Test
    fun resumesFromInterruptedOffset() {
        val payload = ByteArray(2 * 1024 * 1024) { (it * 31 % 251).toByte() }
        val half = payload.size / 2
        val server = BridgeServer(
            scope = scope,
            port = 0,
            pairCode = null,
            devicesProvider = { emptyList() },
            storage = storage(),
        )
        val port = server.start()
        try {
            // 第一次连接：只发一半就断开。
            val first = Listener()
            val firstSocket = connect(port, first)
            offer(firstSocket, size = payload.size.toLong(), offset = 0)
            assertEquals(emptyMap(), BoxletJson.decodeFromString(Accept.serializer(), await(first, Sp.ACCEPT)).resumeFrom)
            startStream(firstSocket)
            firstSocket.sendBinary(ByteBuffer.wrap(payload, 0, half), true).get(5, TimeUnit.SECONDS)
            val partialBytes = awaitPartialBytes()
            firstSocket.sendClose(WebSocket.NORMAL_CLOSURE, "interrupted").get(5, TimeUnit.SECONDS)
            Thread.sleep(500)
            assertTrue(!File(directory, "resume.bin").exists(), "half file must not be published")

            // 第二次连接：声明 offset，bridge 应该回 resumeFrom = 已收字节数。
            val second = Listener()
            val secondSocket = connect(port, second)
            offer(secondSocket, size = payload.size.toLong(), offset = partialBytes)
            val accept = BoxletJson.decodeFromString(Accept.serializer(), await(second, Sp.ACCEPT))
            val resumeAt = assertNotNull(accept.resumeFrom["f1"]).toInt()
            assertTrue(resumeAt > 0 && resumeAt <= half, "unexpected resume offset $resumeAt")
            startStream(secondSocket)
            secondSocket.sendBinary(ByteBuffer.wrap(payload, resumeAt, payload.size - resumeAt), true)
                .get(5, TimeUnit.SECONDS)
            secondSocket.sendText(
                SpControl.encode(Sp.DATA_END, BoxletJson.encodeToString(DataEnd.serializer(), DataEnd(1, payload.size.toLong()))),
                true,
            ).get(5, TimeUnit.SECONDS)

            val done = BoxletJson.decodeFromString(Done.serializer(), await(second, Sp.DONE))
            assertEquals(payload.size.toLong(), done.bytes)
            assertContentEquals(payload, File(directory, "resume.bin").readBytes())
            secondSocket.sendClose(WebSocket.NORMAL_CLOSURE, "bye")
        } finally {
            server.stop()
        }
    }

    private fun awaitPartialBytes(): Long {
        val part = File(directory, "resume.bin.part")
        val deadline = System.currentTimeMillis() + 5_000
        while (System.currentTimeMillis() < deadline) {
            if (part.length() > 0) return part.length()
            Thread.sleep(50)
        }
        throw AssertionError("bridge did not start writing the partial file")
    }

    private fun connect(port: Int, listener: Listener): WebSocket {
        val socket = HttpClient.newHttpClient()
            .newWebSocketBuilder()
            .buildAsync(URI("ws://127.0.0.1:$port/"), listener)
            .get(5, TimeUnit.SECONDS)
        socket.sendText(
            SpControl.encode(Sp.HELLO, BoxletJson.encodeToString(Hello.serializer(), Hello("browser", "web", 1, null))),
            true,
        ).get(5, TimeUnit.SECONDS)
        return socket
    }

    private fun offer(socket: WebSocket, size: Long, offset: Long) {
        val offer = Offer(files = listOf(OfferFile(id = "f1", name = "resume.bin", size = size, offset = offset)))
        socket.sendText(SpControl.encode(Sp.OFFER, BoxletJson.encodeToString(Offer.serializer(), offer)), true)
            .get(5, TimeUnit.SECONDS)
    }

    private fun startStream(socket: WebSocket) {
        socket.sendText(
            SpControl.encode(Sp.DATA_START, BoxletJson.encodeToString(DataStart.serializer(), DataStart(1, "f1"))),
            true,
        ).get(5, TimeUnit.SECONDS)
    }

    private fun await(listener: Listener, expected: String): String {
        val deadline = System.currentTimeMillis() + 8_000
        while (System.currentTimeMillis() < deadline) {
            val message = listener.messages.poll(500, TimeUnit.MILLISECONDS) ?: continue
            if (!message.first) continue
            val body = message.second.decodeToString()
            if (SpControl.typeOf(body) == expected) return body
        }
        throw AssertionError("timed out waiting for control message '$expected'")
    }
}
