package dev.boxlet.core

import dev.boxlet.core.crypto.Certificates
import dev.boxlet.core.protocol.bp2.*
import dev.boxlet.core.session.bp2.*
import dev.boxlet.core.transport.bp2.*
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import okio.Buffer
import okio.ForwardingSource
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.*

class Bp2PipelineTest {
    @Test fun pendingSmallFileAcksDoNotStopLargeFileData() = runBlocking {
        exercise(mixed = true, holdAcks = true)
    }

    @Test fun pendingSmallFilePublicationDoesNotStopLargeFileData() = runBlocking {
        exercise(mixed = true, holdAcks = false)
    }

    @Test fun smallBatchesLeaveRoomForLargeDataWhenPeerOnlyAllowsTwoStreams() = runBlocking {
        exercise(mixed = true, holdAcks = true, peerStreamLimit = 2)
    }

    @Test fun pendingLargeFilePublicationKeepsOtherStreamsMovingWithinPeerLimit() = runBlocking {
        exercise(mixed = false, holdAcks = false)
    }

    @Test fun cancellingWhilePublicationIsPendingStopsAllBoundedWorkersAndClosesSources() = runBlocking {
        exercise(mixed = true, holdAcks = false, cancelDuringPublication = true)
    }

    @Test fun largeStreamSlotsRemainHeldUntilPublicationEvenWhenPeerAllowsEightStreams() = runBlocking {
        exercise(mixed = false, holdAcks = false, holdTwoLargeCompletions = true)
    }

    private suspend fun exercise(mixed: Boolean, holdAcks: Boolean, cancelDuringPublication: Boolean = false,
                                 holdTwoLargeCompletions: Boolean = false, peerStreamLimit: Int? = null) {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val sender = Certificates.generate(); val receiver = Certificates.generate()
        val largeSize = if (holdTwoLargeCompletions) 2 * Bp2.CHUNK_BYTES + 17 else Bp2.BATCH_FILE_BYTES + 17
        val content = if (mixed) (0..3).associate { "small-$it" to ByteArray(4096) { index -> (it + index).toByte() } } +
            ("large" to ByteArray(2 * Bp2.CHUNK_BYTES + 19) { (it * 13).toByte() })
        else linkedMapOf("first" to ByteArray(largeSize) { 1 },
            "next" to ByteArray(largeSize) { 2 }, "third" to ByteArray(largeSize) { 3 })
        val maxStreams = peerStreamLimit ?: if (mixed || holdTwoLargeCompletions) 8 else 2
        val reported = ConcurrentHashMap.newKeySet<String>()
        val saved = ConcurrentHashMap<String, ByteArray>()
        val sourceOpens = AtomicInteger(); val liveSources = AtomicInteger(); val peakSources = AtomicInteger()
        val observed = CompletableDeferred<Unit>()
        val waitingPublication = CompletableDeferred<Unit>()
        val server = Bp2TlsServer(receiver, scope, { it == sender.fingerprint.lowercase() }, { connection ->
            try {
                val initial = Bp2Controls.decode(connection.receive()!!.payload)
                connection.sendControl(Bp2.json.encodeToJsonElement(Bp2HelloAck(
                    replyTo = initial["requestId"]!!.jsonPrimitive.content, connectionId = newBp2Id(),
                    deviceId = receiver.fingerprint.lowercase(), alias = "Slow confirmation peer", deviceType = "desktop",
                    features = listOf("receive", "batch"), limits = Bp2Limits(maxActiveStreams = maxStreams))).jsonObject)
                val entries = mutableListOf<Bp2Entry>()
                val writers = mutableMapOf<Long, Bp2IncomingFile>()
                val occupied = mutableSetOf<Long>()
                val heldAcks = mutableListOf<JsonObject>()
                val heldDone = mutableListOf<JsonObject>()
                val credit = Bp2ReceiveCredit()
                var id = ""
                var otherDataSeen = false
                fun gated(entry: Bp2Entry) = if (mixed) entry.path.startsWith("small-") else
                    entry.path == "first" || (holdTwoLargeCompletions && entry.path == "next")
                suspend fun sendDone(message: JsonObject) {
                    // A stream remains occupied until its final result is sent, not just DATA ACKed.
                    message["items"]!!.jsonArray.forEach { result ->
                        val entry = entries.single { it.fileId == result.jsonObject["fileId"]!!.jsonPrimitive.content }
                        occupied.remove(entry.streamId)
                    }
                    connection.sendControl(message)
                }
                suspend fun accept(frame: Bp2Frame) {
                    val entry = entries.single { it.streamId == frame.header.streamId }
                    if (occupied.add(frame.header.streamId)) assertTrue(occupied.size <= maxStreams, "Exceeded negotiated stream limit")
                    if (holdTwoLargeCompletions) assertTrue(occupied.size <= 2, "EOF cannot free a large-stream slot before files-done")
                    val writer = writers.getOrPut(frame.header.streamId) {
                        val bytes = Buffer()
                        Bp2IncomingFile(entry, object : Bp2Destination {
                            override val sink = bytes
                            override suspend fun commit() { saved[entry.path] = bytes.readByteArray() }
                            override suspend fun abort() { bytes.clear() }
                        })
                    }
                    assertTrue(writer.accept(frame))
                    if (!gated(entry)) {
                        otherDataSeen = true
                        heldAcks.forEach { connection.sendControl(it) }; heldAcks.clear()
                        heldDone.forEach { sendDone(it) }; heldDone.clear()
                    }
                    val ack = control("ack", id) {
                        put("streamId", frame.header.streamId); put("writtenOffset", writer.written.toString())
                        putJsonArray("verifiedRanges") { add(buildJsonArray {
                            add((frame.header.offset / Bp2.CHUNK_BYTES).toString())
                            add((frame.header.offset / Bp2.CHUNK_BYTES + 1).toString())
                        }) }
                    }
                    if (holdAcks && gated(entry) && !otherDataSeen) heldAcks += ack else connection.sendControl(ack)
                }
                while (true) {
                    val frame = connection.receive() ?: break
                    when (frame.header.kind) {
                        Bp2Kind.DATA -> {
                            credit.consume(frame.header.sequence, frame.payload.size - 32)
                            accept(frame)
                        }
                        Bp2Kind.BATCH -> {
                            val records = Bp2Payloads.decodeBatch(frame.payload)
                            credit.consume(frame.header.sequence, records.sumOf { it.bytes.size })
                            records.forEach { record -> accept(Bp2Frame(Bp2Header(Bp2Kind.DATA, record.streamId,
                                32 + record.bytes.size, 0, frame.header.sequence), record.sha256 + record.bytes)) }
                        }
                        Bp2Kind.CONTROL -> {
                            val message = Bp2Controls.decode(frame.payload)
                            when (message["type"]!!.jsonPrimitive.content) {
                                "offer-begin" -> id = message["transferId"]!!.jsonPrimitive.content
                                "offer-page" -> message["entries"]!!.jsonArray.forEach {
                                    entries += Bp2.json.decodeFromJsonElement<Bp2Entry>(it)
                                }
                                "offer-end" -> {
                                    connection.sendControl(control("accept-page", id) {
                                        put("replyTo", message["requestId"]!!.jsonPrimitive.content); put("pageIndex", 0)
                                        putJsonArray("accepted") { entries.forEach { entry -> add(buildJsonObject {
                                            put("fileId", entry.fileId); put("streamId", entry.streamId)
                                        }) } }
                                    })
                                    connection.sendControl(control("accept-end", id) {
                                        put("decisionId", newBp2Id()); put("replyTo", message["requestId"]!!.jsonPrimitive.content)
                                    })
                                }
                                "files-finish" -> {
                                    if (cancelDuringPublication) {
                                        waitingPublication.complete(Unit)
                                        awaitCancellation()
                                    }
                                    val items = message["items"]!!.jsonArray
                                    val results = items.map {
                                        val item = it.jsonObject
                                        val entry = entries.single { it.fileId == item["fileId"]!!.jsonPrimitive.content }
                                        writers.getValue(entry.streamId!!).finish(item["contentRoot"]!!.jsonPrimitive.content)
                                        buildJsonObject { put("fileId", entry.fileId); put("status", "completed") }
                                    }
                                    val done = control("files-done", id) { put("items", JsonArray(results)) }
                                    if (!otherDataSeen && items.any { value -> gated(entries.single {
                                        it.fileId == value.jsonObject["fileId"]!!.jsonPrimitive.content
                                    }) }) {
                                        assertTrue(reported.isEmpty(), "Publication cannot be reported before files-done")
                                        heldDone += done
                                        if (holdTwoLargeCompletions && heldDone.size == 2) {
                                            heldDone.forEach { sendDone(it) }; heldDone.clear()
                                        }
                                    } else sendDone(done)
                                }
                                "transfer-finish" -> {
                                    assertTrue(otherDataSeen, "Another stream must advance before the held confirmation")
                                    assertTrue(occupied.isEmpty()); assertTrue(heldAcks.isEmpty()); assertTrue(heldDone.isEmpty())
                                    connection.sendControl(control("transfer-done", id) { put("status", "completed") })
                                    observed.complete(Unit)
                                    break
                                }
                            }
                            continue
                        }
                    }
                    connection.sendControl(control("window-update") {
                        put("creditTotal", credit.release(frame.header.sequence).toString())
                    })
                }
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
            val session = Bp2Session.open(connection, Bp2Hello(requestId = newBp2Id(), deviceId = sender.fingerprint.lowercase(),
                alias = "Sender", deviceType = "desktop", features = listOf("send", "batch")), true, handler, scope)
            try {
                val files = content.map { (name, bytes) -> Bp2OutgoingFile(Bp2Entry(newBp2Id(), path = name, size = bytes.size.toString()), {
                    sourceOpens.incrementAndGet()
                    val large = bytes.size > Bp2.BATCH_FILE_BYTES
                    if (large) peakSources.accumulateAndGet(liveSources.incrementAndGet(), ::maxOf)
                    object : ForwardingSource(Buffer().write(bytes)) {
                        override fun close() { super.close(); if (large) liveSources.decrementAndGet() }
                    }
                }) }
                if (cancelDuringPublication) {
                    coroutineScope {
                        val sending = async { session.send(files) }
                        withTimeout(10000) { waitingPublication.await() }
                        withTimeout(3000) { sending.cancelAndJoin() }
                        assertTrue(reported.isEmpty()); assertEquals(0, liveSources.get())
                    }
                    return
                }
                assertEquals(files.size, withTimeout(10000) { session.send(files) }.completed.size)
                withTimeout(5000) { observed.await() }
                assertEquals(content.keys, reported.toSet()); assertEquals(files.size, sourceOpens.get())
                assertEquals(0, liveSources.get()); assertTrue(peakSources.get() <= 2)
                content.forEach { (name, bytes) -> assertContentEquals(bytes, saved[name]) }
            } finally { session.close() }
        } finally { server.close(); scope.cancel() }
    }

    private fun control(type: String, id: String? = null, body: JsonObjectBuilder.() -> Unit = {}) = buildJsonObject {
        put("type", type); put("requestId", newBp2Id()); if (id != null) put("transferId", id); body()
    }
}
