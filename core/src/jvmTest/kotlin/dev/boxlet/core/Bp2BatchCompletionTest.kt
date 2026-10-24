package dev.boxlet.core

import dev.boxlet.core.crypto.Certificates
import dev.boxlet.core.protocol.bp2.*
import dev.boxlet.core.session.bp2.*
import dev.boxlet.core.transport.bp2.*
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import okio.Buffer
import java.io.IOException
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.*

class Bp2BatchCompletionTest {
    @Test fun smallFilesShareCompletionRoundTripsWithPartialSelectionAndReorderedResults() = runBlocking {
        exercise(failPublication = false)
    }

    @Test fun oneFailedPublicationCannotCompleteTheBatchOrTransfer() = runBlocking {
        exercise(failPublication = true)
    }

    @Test fun emptyFilesShareCompletionWithoutSendingZeroLengthData() = runBlocking {
        exercise(failPublication = false, emptyFiles = true)
    }

    private suspend fun exercise(failPublication: Boolean, emptyFiles: Boolean = false) {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val sender = Certificates.generate(); val receiver = Certificates.generate()
        val saved = mutableMapOf<String, ByteArray>()
        val reported = CopyOnWriteArrayList<String>()
        val groups = mutableListOf<Int>()
        val observed = CompletableDeferred<Unit>()
        val server = Bp2TlsServer(receiver, scope, { it == sender.fingerprint.lowercase() }, { connection ->
            try {
                val initial = Bp2Controls.decode(connection.receive()!!.payload)
                connection.sendControl(Bp2.json.encodeToJsonElement(Bp2HelloAck(
                    replyTo = initial["requestId"]!!.jsonPrimitive.content, connectionId = newBp2Id(),
                    deviceId = receiver.fingerprint.lowercase(), alias = "Batch peer", deviceType = "desktop",
                    features = listOf("receive", "batch"))).jsonObject)
                val entries = mutableListOf<Bp2Entry>()
                val writers = mutableMapOf<Long, Bp2IncomingFile>()
                val credit = Bp2ReceiveCredit()
                var id = ""
                while (true) {
                    val frame = connection.receive() ?: break
                    if (frame.header.kind == Bp2Kind.BATCH) {
                        assertFalse(emptyFiles, "Empty files must not send DATA/BATCH")
                        val records = Bp2Payloads.decodeBatch(frame.payload)
                        credit.consume(frame.header.sequence, records.sumOf { it.bytes.size })
                        for (record in records) {
                            val writer = writers.getValue(record.streamId)
                            assertTrue(writer.accept(Bp2Frame(Bp2Header(Bp2Kind.DATA, record.streamId,
                                32 + record.bytes.size, 0, frame.header.sequence), record.sha256 + record.bytes)))
                            connection.sendControl(control("ack", id) {
                                put("streamId", record.streamId); put("writtenOffset", writer.written.toString())
                                putJsonArray("verifiedRanges") { add(buildJsonArray { add("0"); add("1") }) }
                            })
                        }
                        connection.sendControl(control("window-update") {
                            put("creditTotal", credit.release(frame.header.sequence).toString())
                        })
                        continue
                    }
                    val message = Bp2Controls.decode(frame.payload)
                    when (message["type"]!!.jsonPrimitive.content) {
                        "offer-begin" -> id = message["transferId"]!!.jsonPrimitive.content
                        "offer-page" -> message["entries"]!!.jsonArray.forEach {
                            entries += Bp2.json.decodeFromJsonElement<Bp2Entry>(it)
                        }
                        "offer-end" -> {
                            val selected = entries.filter { it.path != "skip" }
                            selected.forEach { entry ->
                                val bytes = Buffer()
                                writers[entry.streamId!!] = Bp2IncomingFile(entry, object : Bp2Destination {
                                    override val sink = bytes
                                    override suspend fun commit() { saved[entry.path] = bytes.readByteArray() }
                                    override suspend fun abort() {}
                                })
                            }
                            connection.sendControl(control("accept-page", id) {
                                put("replyTo", message["requestId"]!!.jsonPrimitive.content); put("pageIndex", 0)
                                putJsonArray("accepted") { selected.forEach { entry -> add(buildJsonObject {
                                    put("fileId", entry.fileId); put("streamId", entry.streamId)
                                }) } }
                            })
                            connection.sendControl(control("accept-end", id) {
                                put("decisionId", newBp2Id()); put("replyTo", message["requestId"]!!.jsonPrimitive.content)
                            })
                        }
                        "files-finish" -> {
                            val items = message["items"]!!.jsonArray
                            if (groups.isEmpty()) assertTrue(reported.isEmpty(), "ACK is not publication success")
                            groups += items.size
                            // A grouped completion must not change per-file publication/integrity semantics.
                            val results = items.mapIndexed { index, value ->
                                val item = value.jsonObject
                                val entry = entries.single { it.fileId == item["fileId"]!!.jsonPrimitive.content }
                                val failed = failPublication && index == items.lastIndex
                                if (!failed) writers.getValue(entry.streamId!!).finish(item["contentRoot"]!!.jsonPrimitive.content)
                                buildJsonObject { put("fileId", entry.fileId); put("status", if (failed) "failed" else "completed") }
                            }
                            connection.sendControl(control("files-done", id) { put("items", JsonArray(results.reversed())) })
                        }
                        "transfer-finish" -> {
                            assertFalse(failPublication, "A failed file must prevent transfer completion")
                            connection.sendControl(control("transfer-done", id) { put("status", "partial") })
                            observed.complete(Unit); break
                        }
                        "cancel" -> { observed.complete(Unit); break }
                    }
                }
                observed.complete(Unit)
            } catch (error: Throwable) { observed.completeExceptionally(error) }
        }, { observed.completeExceptionally(it) })
        try {
            val port = server.start(0)
            val connection = Bp2TlsClient.connect("127.0.0.1", port, sender, receiver.fingerprint, scope)
            val handler = object : Bp2SessionHandler {
                override suspend fun onOffer(peer: Bp2Peer, transferId: String, entries: List<Bp2Entry>) = emptySet<String>()
                override suspend fun createDestination(peer: Bp2Peer, transferId: String, entry: Bp2Entry): Bp2Destination = error("No reverse offer")
                override fun onSent(transferId: String, entry: Bp2Entry) { reported += entry.path }
            }
            val session = Bp2Session.open(connection, Bp2Hello(requestId = newBp2Id(),
                deviceId = sender.fingerprint.lowercase(), alias = "Sender", deviceType = "desktop",
                features = listOf("send", "batch")), true, handler, scope)
            try {
                val count = if (failPublication) 8 else 17
                val content = (0 until count).associate { "item-$it" to ByteArray(if (emptyFiles || it % 3 == 0) 0 else 4096) { index -> (index + it).toByte() } } +
                    ("skip" to byteArrayOf(1))
                val files = content.map { (name, data) -> Bp2OutgoingFile(
                    Bp2Entry(newBp2Id(), path = name, size = data.size.toString()), { Buffer().write(data) }) }
                if (failPublication) {
                    assertFailsWith<IOException> { withTimeout(15000) { session.send(files) } }
                } else {
                    val result = withTimeout(15000) { session.send(files) }
                    assertEquals(count, result.completed.size); assertEquals(1, result.rejected.size)
                }
                withTimeout(5000) { observed.await() }
                assertEquals(if (failPublication) listOf(8) else listOf(8, 8, 1), groups)
                assertEquals(if (failPublication) 7 else count, reported.size)
                assertFalse("skip" in saved)
                saved.forEach { (name, bytes) -> assertContentEquals(content.getValue(name), bytes) }
            } finally { session.close() }
        } finally { server.close(); scope.cancel() }
    }

    private fun control(type: String, id: String? = null, body: JsonObjectBuilder.() -> Unit = {}) = buildJsonObject {
        put("type", type); put("requestId", newBp2Id()); if (id != null) put("transferId", id); body()
    }
}
