package dev.boxlet.core

import dev.boxlet.core.engine.ReceiveDestination
import dev.boxlet.core.engine.ReceiveStorage
import dev.boxlet.core.protocol.FileDto
import dev.boxlet.core.protocol.BoxletJson
import dev.boxlet.core.sp.Accept
import dev.boxlet.core.sp.BridgeServer
import dev.boxlet.core.sp.DataEnd
import dev.boxlet.core.sp.DataStart
import dev.boxlet.core.sp.DeviceList
import dev.boxlet.core.sp.Done
import dev.boxlet.core.sp.Hello
import dev.boxlet.core.sp.Offer
import dev.boxlet.core.sp.OfferFile
import dev.boxlet.core.sp.PeerDevice
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
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** SP/1 端到端：自建 WS 服务端 + JDK WebSocket 客户端，验证控制消息、裸二进制流与落盘。 */
class BridgeServerTest {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val directory: File = Files.createTempDirectory("boxlet-bridge").toFile()

    @After
    fun tearDown() {
        scope.cancel()
        directory.deleteRecursively()
    }

    private class QueueListener : WebSocket.Listener {
        val messages = LinkedBlockingQueue<Pair<Boolean, ByteArray>>()

        override fun onText(webSocket: WebSocket, data: CharSequence, last: Boolean): CompletionStage<*>? {
            if (last) messages.put(true to data.toString().encodeToByteArray())
            webSocket.request(1)
            return null
        }

        override fun onBinary(webSocket: WebSocket, data: ByteBuffer, last: Boolean): CompletionStage<*>? {
            val chunk = ByteArray(data.remaining())
            data.get(chunk)
            if (last) messages.put(false to chunk)
            webSocket.request(1)
            return null
        }
    }

    private fun storage(): ReceiveStorage = object : ReceiveStorage {
        override suspend fun createDestination(
            sessionId: String,
            file: FileDto,
            senderAlias: String,
        ): ReceiveDestination {
            val target = File(directory, file.fileName)
            val sink = target.sink().buffer()
            return object : ReceiveDestination {
                override val sink = sink
                override val displayPath: String = target.absolutePath
                override suspend fun commit() {
                    sink.flush()
                    sink.close()
                }

                override suspend fun abort() {
                    runCatching { sink.close() }
                    runCatching { target.delete() }
                }
            }
        }
    }

    private fun control(type: String, json: String): String = SpControl.encode(type, json)

    @Test
    fun uploadsFileOverWebSocket() {
        val server = BridgeServer(
            scope = scope,
            port = 0,
            pairCode = "1234",
            devicesProvider = {
                listOf(PeerDevice(id = "d1", alias = "Peer", host = "127.0.0.1", port = 53318))
            },
            storage = storage(),
        )
        val port = server.start()
        try {
            val listener = QueueListener()
            val socket = HttpClient.newHttpClient()
                .newWebSocketBuilder()
                .buildAsync(URI("ws://127.0.0.1:$port/"), listener)
                .get(5, TimeUnit.SECONDS)

            socket.sendText(
                control(Sp.HELLO, BoxletJson.encodeToString(Hello.serializer(), Hello("Browser", "web", 1, "1234"))),
                true,
            ).get(5, TimeUnit.SECONDS)

            val devices = await(listener, Sp.DEVICES)
            assertEquals(1, BoxletJson.decodeFromString(DeviceList.serializer(), devices).devices.size)

            val payload = ByteArray(1536 * 1024) { (it * 31 % 251).toByte() }
            val offer = Offer(files = listOf(OfferFile(id = "f1", name = "sample.bin", size = payload.size.toLong())))
            socket.sendText(
                control(Sp.OFFER, BoxletJson.encodeToString(Offer.serializer(), offer)),
                true,
            ).get(5, TimeUnit.SECONDS)
            val accept = BoxletJson.decodeFromString(Accept.serializer(), await(listener, Sp.ACCEPT))
            assertTrue(accept.accepted.contains("f1"))

            socket.sendText(
                control(Sp.DATA_START, BoxletJson.encodeToString(DataStart.serializer(), DataStart(1, "f1"))),
                true,
            ).get(5, TimeUnit.SECONDS)

            val chunk = 1024 * 1024
            var offset = 0
            while (offset < payload.size) {
                val size = minOf(chunk, payload.size - offset)
                socket.sendBinary(ByteBuffer.wrap(payload, offset, size), true).get(5, TimeUnit.SECONDS)
                offset += size
            }

            socket.sendText(
                control(Sp.DATA_END, BoxletJson.encodeToString(DataEnd.serializer(), DataEnd(1, payload.size.toLong()))),
                true,
            ).get(5, TimeUnit.SECONDS)

            val done = BoxletJson.decodeFromString(Done.serializer(), await(listener, Sp.DONE))
            assertEquals(payload.size.toLong(), done.bytes)

            val target = File(directory, "sample.bin")
            assertTrue(target.exists(), "file should be written")
            assertTrue(payload.contentEquals(target.readBytes()))
            socket.sendClose(WebSocket.NORMAL_CLOSURE, "bye")
        } finally {
            server.stop()
        }
    }

    @Test
    fun rejectsWrongPairCode() {
        val server = BridgeServer(
            scope = scope,
            port = 0,
            pairCode = "1234",
            devicesProvider = { emptyList() },
            storage = storage(),
        )
        val port = server.start()
        try {
            val listener = QueueListener()
            val socket = HttpClient.newHttpClient()
                .newWebSocketBuilder()
                .buildAsync(URI("ws://127.0.0.1:$port/"), listener)
                .get(5, TimeUnit.SECONDS)
            socket.sendText(
                control(Sp.HELLO, BoxletJson.encodeToString(Hello.serializer(), Hello("Browser", "web", 1, "wrong"))),
                true,
            ).get(5, TimeUnit.SECONDS)
            await(listener, Sp.REJECT)
        } finally {
            server.stop()
        }
    }

    private fun await(listener: QueueListener, expected: String): String {
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
