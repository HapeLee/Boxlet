package dev.boxlet.core

import dev.boxlet.core.engine.ReceiveDestination
import dev.boxlet.core.engine.ReceiveStorage
import dev.boxlet.core.protocol.FileDto
import dev.boxlet.core.protocol.BoxletJson
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
import okio.Buffer
import okio.Timeout
import org.junit.After
import org.junit.Test
import java.net.URI
import java.net.http.HttpClient
import java.net.http.WebSocket
import java.nio.ByteBuffer
import java.util.concurrent.CompletionStage
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals

/**
 * SP/1 接收端的吞吐回归测试。
 *
 * 落盘换成黑洞 sink，只测 WS 解帧 + 掩码 + 分块路径的开销，避免磁盘速度掩盖协议层的退化。
 */
class BridgeThroughputTest {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @After
    fun tearDown() {
        scope.cancel()
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
    fun receivesLargeFile() {
        val total = 256L * 1024 * 1024
        val chunk = ByteArray(1024 * 1024) { 7 }

        val storage = object : ReceiveStorage {
            override suspend fun createDestination(
                sessionId: String,
                file: FileDto,
                senderAlias: String,
            ): ReceiveDestination = object : ReceiveDestination {
                override val sink = object : okio.Sink {
                    override fun write(source: Buffer, byteCount: Long) = source.skip(byteCount)
                    override fun flush() = Unit
                    override fun close() = Unit
                    override fun timeout(): Timeout = Timeout.NONE
                }
                override val displayPath: String = "blackhole"
                override suspend fun commit() = Unit
                override suspend fun abort() = Unit
            }
        }

        val server = BridgeServer(
            scope = scope,
            port = 0,
            pairCode = null,
            devicesProvider = { emptyList() },
            storage = storage,
        )
        val port = server.start()
        try {
            val listener = Listener()
            val socket = HttpClient.newHttpClient()
                .newWebSocketBuilder()
                .buildAsync(URI("ws://127.0.0.1:$port/"), listener)
                .get(5, TimeUnit.SECONDS)
            socket.sendText(
                SpControl.encode(Sp.HELLO, BoxletJson.encodeToString(Hello.serializer(), Hello("bench", "web", 1, null))),
                true,
            ).get()
            socket.sendText(
                SpControl.encode(
                    Sp.OFFER,
                    BoxletJson.encodeToString(
                        Offer.serializer(),
                        Offer(files = listOf(OfferFile(id = "f1", name = "bench.bin", size = total))),
                    ),
                ),
                true,
            ).get()
            await(listener, Sp.ACCEPT)
            socket.sendText(
                SpControl.encode(Sp.DATA_START, BoxletJson.encodeToString(DataStart.serializer(), DataStart(1, "f1"))),
                true,
            ).get()

            val started = System.nanoTime()
            var sent = 0L
            while (sent < total) {
                socket.sendBinary(ByteBuffer.wrap(chunk), true).get()
                sent += chunk.size
            }
            socket.sendText(
                SpControl.encode(Sp.DATA_END, BoxletJson.encodeToString(DataEnd.serializer(), DataEnd(1, total))),
                true,
            ).get()

            val done = BoxletJson.decodeFromString(Done.serializer(), await(listener, Sp.DONE))
            assertEquals(total, done.bytes)
            val seconds = (System.nanoTime() - started) / 1_000_000_000.0
            println("[bench] ${total shr 20} MiB in ${"%.2f".format(seconds)}s = ${"%.0f".format(total / 1024.0 / 1024.0 / seconds)} MiB/s")
            socket.sendClose(WebSocket.NORMAL_CLOSURE, "bye")
        } finally {
            server.stop()
        }
    }

    private fun await(listener: Listener, expected: String): String {
        val deadline = System.currentTimeMillis() + 60_000
        while (System.currentTimeMillis() < deadline) {
            val message = listener.messages.poll(500, TimeUnit.MILLISECONDS) ?: continue
            if (!message.first) continue
            val body = message.second.decodeToString()
            if (SpControl.typeOf(body) == expected) return body
        }
        throw AssertionError("timed out waiting for control message '$expected'")
    }
}
