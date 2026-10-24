package dev.boxlet.core

import dev.boxlet.core.crypto.Certificates
import dev.boxlet.core.protocol.bp2.*
import dev.boxlet.core.session.bp2.*
import dev.boxlet.core.transport.bp2.*
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import okio.Buffer
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.*

class Bp2SessionRepairTest {
    @Test fun aBadChunkIsRepairedFromBoundedPendingDataWithoutReopeningSource() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val sender = Certificates.generate(); val receiver = Certificates.generate()
        val data = ByteArray(2 * Bp2.CHUNK_BYTES) { (it * 7).toByte() }
        val published = CompletableDeferred<ByteArray>()
        val repairs = AtomicInteger()
        val server = Bp2TlsServer(receiver, scope, { connection ->
            val initial = Bp2Controls.decode(connection.receive()!!.payload)
            connection.sendControl(Bp2.json.encodeToJsonElement(Bp2HelloAck(replyTo = initial["requestId"]!!.jsonPrimitive.content,
                connectionId = newBp2Id(), deviceId = receiver.fingerprint.lowercase(), alias = "Repair peer",
                deviceType = "desktop", features = listOf("receive", "batch"))).jsonObject)
            var entry: Bp2Entry? = null
            var id = ""
            var writer: Bp2IncomingFile? = null
            val credit = Bp2ReceiveCredit()
            val pending = java.util.TreeMap<Long, Long>()
            var damaged = false
            while (true) {
                val frame = connection.receive() ?: break
                if (frame.header.kind == Bp2Kind.DATA) {
                    val file = writer!!
                    credit.consume(frame.header.sequence, frame.payload.size - 32)
                    if (frame.header.offset == 0L && !damaged) {
                        val corrupt = frame.copy(payload = frame.payload.copyOf().also { it[32] = (it[32].toInt() xor 1).toByte() })
                        assertFalse(file.accept(corrupt))
                        damaged = true
                        connection.sendControl(control("window-update") { put("creditTotal", credit.release(frame.header.sequence).toString()) })
                        continue
                    }
                    if (frame.header.offset == 0L) repairs.incrementAndGet()
                    assertTrue(file.accept(frame))
                    pending[frame.header.offset] = frame.header.sequence
                    while (pending.isNotEmpty() && pending.firstKey() < file.written) {
                        val total = credit.release(pending.pollFirstEntry().value)
                        connection.sendControl(control("window-update") { put("creditTotal", total.toString()) })
                    }
                    connection.sendControl(control("ack", id) {
                        put("streamId", frame.header.streamId); put("writtenOffset", file.written.toString())
                        putJsonArray("verifiedRanges") { add(buildJsonArray {
                            add((frame.header.offset / Bp2.CHUNK_BYTES).toString()); add((frame.header.offset / Bp2.CHUNK_BYTES + 1).toString())
                        }) }
                    })
                    if (frame.header.offset == Bp2.CHUNK_BYTES.toLong()) connection.sendControl(control("nack", id) {
                        put("streamId", frame.header.streamId); put("chunkIndex", "0"); put("reason", "CHECKSUM_MISMATCH")
                    })
                    continue
                }
                val message = Bp2Controls.decode(frame.payload)
                when (message["type"]!!.jsonPrimitive.content) {
                    "offer-begin" -> id = message["transferId"]!!.jsonPrimitive.content
                    "offer-page" -> entry = Bp2.json.decodeFromJsonElement(message["entries"]!!.jsonArray.single())
                    "offer-end" -> {
                        val buffer = Buffer()
                        writer = Bp2IncomingFile(entry!!, object : Bp2Destination {
                            override val sink = buffer
                            override suspend fun commit() { published.complete(buffer.readByteArray()) }
                            override suspend fun abort() { published.completeExceptionally(AssertionError("Unexpected abort")) }
                        })
                        connection.sendControl(control("accept-page", id) {
                            put("replyTo", message["requestId"]!!.jsonPrimitive.content)
                            put("pageIndex", 0); putJsonArray("accepted") { add(buildJsonObject {
                                put("fileId", entry!!.fileId); put("streamId", entry!!.streamId)
                            }) }
                        })
                        connection.sendControl(control("accept-end", id) { put("decisionId", newBp2Id()); put("replyTo", message["requestId"]!!.jsonPrimitive.content) })
                    }
                    "files-finish" -> {
                        writer!!.finish(message["items"]!!.jsonArray.single().jsonObject["contentRoot"]!!.jsonPrimitive.content)
                        connection.sendControl(control("files-done", id) { putJsonArray("items") { add(buildJsonObject {
                            put("fileId", entry!!.fileId); put("status", "completed")
                        }) } })
                    }
                    "transfer-finish" -> {
                        connection.sendControl(control("transfer-done", id) { put("status", "completed") }); break
                    }
                }
            }
        }, { published.completeExceptionally(it) })
        try {
            val port = server.start(0)
            val connection = Bp2TlsClient.connect("127.0.0.1", port, sender, receiver.fingerprint, scope)
            val handler = object : Bp2SessionHandler {
                override suspend fun onOffer(peer: Bp2Peer, transferId: String, entries: List<Bp2Entry>) = emptySet<String>()
                override suspend fun createDestination(peer: Bp2Peer, transferId: String, entry: Bp2Entry): Bp2Destination = error("Unexpected reverse offer")
            }
            val session = Bp2Session.open(connection, Bp2Hello(requestId = newBp2Id(), deviceId = sender.fingerprint.lowercase(),
                alias = "Sender", deviceType = "desktop", features = listOf("send", "batch")), true, handler, scope)
            try {
                val opens = AtomicInteger()
                val result = withTimeout(15000) { session.send(listOf(Bp2OutgoingFile(
                    Bp2Entry(newBp2Id(), path = "repair.bin", size = data.size.toString()),
                    { opens.incrementAndGet(); Buffer().write(data) }))) }
                assertEquals(1, result.completed.size)
                assertContentEquals(data, withTimeout(5000) { published.await() })
                assertEquals(1, opens.get()); assertEquals(1, repairs.get())
            } finally { session.close() }
        } finally { server.close(); scope.cancel() }
    }

    private fun control(type: String, id: String? = null, body: JsonObjectBuilder.() -> Unit = {}) = buildJsonObject {
        put("type", type); put("requestId", newBp2Id()); if (id != null) put("transferId", id); body()
    }
}
