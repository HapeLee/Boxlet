package dev.boxlet.core.bp2

import dev.boxlet.core.protocol.bp2.*
import kotlinx.serialization.json.*
import kotlin.test.*

class Bp2ProtocolTest {
    private val vectors get() = Bp2.json.parseToJsonElement(BP2_VECTORS).jsonObject
    private fun JsonObject.text(key: String) = getValue(key).jsonPrimitive.content

    @Test fun framesMatchIndependentPythonGoldenVectors() {
        for (vector in vectors.getValue("frames").jsonArray.map { it.jsonObject }) {
            val bytes = Bp2Digests.unhex(vector.text("hex"))
            val frame = Bp2FrameCodec.decodeMessage(bytes)
            assertContentEquals(bytes, Bp2FrameCodec.encode(frame))
            val expected = when (vector.text("name")) {
                "control-ping" -> Bp2Controls.encode(vector.getValue("jsonPayload").jsonObject)
                "data-abc" -> Bp2Payloads.data("abc".encodeToByteArray())
                else -> Bp2Payloads.encodeBatch(vector.getValue("records").jsonArray.map {
                    val entry = it.jsonObject
                    val data = entry.text("utf8").encodeToByteArray()
                    Bp2BatchRecord(entry.getValue("streamId").jsonPrimitive.long, data, Bp2Digests.sha256(data))
                })
            }
            assertContentEquals(expected, frame.payload)
        }
    }

    @Test fun digestsMatchAllFileAndPrefixGoldenVectors() {
        for (vector in vectors.getValue("files").jsonArray.map { it.jsonObject }) {
            val source = vector.getValue("source").jsonObject
            val bytes = source["utf8"]?.jsonPrimitive?.content?.encodeToByteArray() ?: (
                ByteArray(source.getValue("repeatCount").jsonPrimitive.int) { source.getValue("repeatByte").jsonPrimitive.int.toByte() } +
                    (source["suffixUtf8"]?.jsonPrimitive?.content ?: "").encodeToByteArray())
            val hashes = bytes.asList().chunked(Bp2.CHUNK_BYTES).map { Bp2Digests.sha256(it.toByteArray()) }
            assertEquals(vector.getValue("chunkSha256").jsonArray.map { it.jsonPrimitive.content }, hashes.map(Bp2Digests::hex))
            val digest = Bp2ContentDigest(bytes.size.toLong())
            hashes.forEachIndexed { index, hash -> digest.addChunkHash(index.toLong(), hash) }
            assertEquals(vector.text("contentRoot"), digest.finish())
            for (prefix in vector.getValue("prefixRoots").jsonArray.map { it.jsonObject }) {
                val count = Bp2.decimal(prefix.text("retainedChunks"))
                val prefixDigest = Bp2ContentDigest(bytes.size.toLong(), count)
                hashes.take(count.toInt()).forEachIndexed { index, hash -> prefixDigest.addChunkHash(index.toLong(), hash) }
                assertEquals(prefix.text("prefixRoot"), prefixDigest.finish())
            }
        }
    }

    @Test fun manifestMatchesCanonicalUnicodeGoldenVector() {
        val vector = vectors.getValue("manifest").jsonObject
        val digest = Bp2ManifestDigest()
        vector.getValue("entries").jsonArray.forEach { digest.add(Bp2.json.decodeFromJsonElement<Bp2Entry>(it)) }
        assertEquals(vector.text("manifestHash"), digest.finish())
    }

    @Test fun decoderHandlesEverySplitAndCombinedFrames() {
        val frames = vectors.getValue("frames").jsonArray.mapIndexed { index, value ->
            val frame = Bp2FrameCodec.decodeMessage(Bp2Digests.unhex(value.jsonObject.text("hex")))
            Bp2FrameCodec.encode(frame.copy(header = frame.header.copy(sequence = index + 1L)))
        }
        val combined = frames.fold(ByteArray(0)) { all, part -> all + part }
        for (split in 0..combined.size) {
            val decoder = Bp2FrameDecoder()
            val decoded = mutableListOf<Bp2Frame>()
            decoder.feed(combined.copyOfRange(0, split)) { decoded += it }
            decoder.feed(combined.copyOfRange(split, combined.size)) { decoded += it }
            decoder.finish()
            assertEquals(listOf(1L, 2L, 3L), decoded.map { it.header.sequence })
        }
        val decoder = Bp2FrameDecoder()
        var count = 0
        combined.forEach { decoder.feed(byteArrayOf(it)) { count++ } }
        decoder.finish(); assertEquals(3, count)
    }

    @Test fun rejectsBadHeadersBeforeAllocatingPayload() {
        val good = Bp2FrameCodec.encodeHeader(Bp2Header(Bp2Kind.DATA, 1, 35, sequence = 1))
        val mutations = listOf(0 to 0, 4 to 99, 5 to 1, 7 to 31, 12 to 127, 16 to 128, 24 to 128)
        mutations.forEach { (at, value) ->
            val invalid = good.copyOf().also { it[at] = value.toByte() }
            assertFails { Bp2FrameDecoder().feed(invalid) { fail("Invalid frame was emitted") } }
        }
        val wrongSequence = Bp2FrameCodec.encodeHeader(Bp2Header(Bp2Kind.DATA, 1, 35, sequence = 2))
        assertFails { Bp2FrameDecoder().feed(wrongSequence) {} }
        assertFails { Bp2FrameCodec.encodeHeader(Bp2Header(Bp2Kind.DATA, 1, 35, offset = Long.MAX_VALUE, sequence = 1)) }
    }

    @Test fun rejectsTruncationAndMultipleWssFrames() {
        val bytes = Bp2Digests.unhex(vectors.getValue("frames").jsonArray[1].jsonObject.text("hex"))
        for (length in 1 until bytes.size) {
            val decoder = Bp2FrameDecoder()
            decoder.feed(bytes.copyOf(length)) {}
            assertFails { decoder.finish() }
        }
        assertFails { Bp2FrameCodec.decodeMessage(bytes + bytes) }
    }

    @Test fun validatesDataLengthAndDetectsCorruption() {
        val payload = Bp2Payloads.data("abc".encodeToByteArray())
        val frame = Bp2Frame(Bp2Header(Bp2Kind.DATA, 1, payload.size, sequence = 1), payload)
        assertTrue(Bp2Payloads.verifyData(frame, 3))
        assertFalse(Bp2Payloads.verifyData(frame.copy(payload = payload.copyOf().also { it[34] = 0 }), 3))
        assertFails { Bp2Payloads.verifyData(frame, 4) }
        assertFails { Bp2Payloads.verifyData(frame, 2) }
        assertFails { Bp2Payloads.data(ByteArray(0)) }
    }

    @Test fun batchChecksStructureButAllowsIndependentChecksumFailure() {
        val record = Bp2BatchRecord(1, byteArrayOf(3), ByteArray(32))
        val encoded = Bp2Payloads.encodeBatch(listOf(record))
        val decoded = Bp2Payloads.decodeBatch(encoded).single()
        assertContentEquals(record.bytes, decoded.bytes)
        assertFalse(decoded.sha256.contentEquals(Bp2Digests.sha256(decoded.bytes)))
        assertFails { Bp2Payloads.decodeBatch(encoded + byteArrayOf(0)) }
        assertFails { Bp2Payloads.decodeBatch(encoded.copyOf(encoded.size - 1)) }
        assertFails { Bp2Payloads.encodeBatch(listOf(record, record)) }
        assertFails { Bp2Payloads.encodeBatch(List(9) { record.copy(streamId = it + 1L) }) }
    }

    @Test fun strictControlRejectsDuplicateKeysInvalidNumbersUtf8AndDepth() {
        val invalid = listOf(
            "{\"type\":\"ping\",\"type\":\"cancel\"}",
            "{\"type\":\"ping\",\"x\":{\"a\":1,\"\\u0061\":2}}",
            "{\"type\":\"ping\",\"x\":NaN}", "{\"type\":\"ping\",\"x\":01}",
            "{\"type\":\"ping\",}", "{\"type\":\"ping\"}false",
            "{\"type\":\"ping\",\"x\":" + "[".repeat(65) + "0" + "]".repeat(65) + "}",
        )
        invalid.forEach { assertFails(it) { Bp2Controls.decode(it.encodeToByteArray()) } }
        assertFails { Bp2Controls.decode(byteArrayOf(0xc0.toByte(), 0xaf.toByte())) }
        assertEquals("ping", Bp2Controls.decode("{\"type\":\"ping\",\"x\":[true,null,1e2,{}]}".encodeToByteArray())["type"]?.jsonPrimitive?.content)
    }

    @Test fun sizesPathsAndChunkOrderAreCheckedWithoutIntTruncation() {
        assertEquals(5368709120L, Bp2.decimal("5368709120"))
        val highOffset = 5368709120L
        val highFrame = Bp2Frame(Bp2Header(Bp2Kind.DATA, 0xffffffffL, 35, highOffset, 1), Bp2Payloads.data("abc".encodeToByteArray()))
        assertEquals(highOffset, Bp2FrameCodec.decodeMessage(Bp2FrameCodec.encode(highFrame)).header.offset)
        assertTrue(Bp2Payloads.verifyData(highFrame, highOffset + 3))
        listOf("-1", "+1", "01", " 1", "1.0", "9223372036854775808").forEach { assertFails { Bp2.decimal(it) } }
        val entry = Bp2Entry("0".repeat(32), path = "test.bin", size = "5368709120", streamId = 1)
        entry.validate(requireStream = true)
        listOf("../secret", "/absolute", "x/../y", "C:/file", "a\\b", "a//b", "\u0000").forEach {
            assertFails { entry.copy(path = it).validate() }
        }
        val content = Bp2ContentDigest(Bp2.CHUNK_BYTES + 1L)
        assertFails { content.addChunkHash(1, ByteArray(32)) }
        assertFails { content.finish() }
        val manifest = Bp2ManifestDigest()
        manifest.add(entry); assertFails { manifest.add(entry) }
    }

    @Test fun creditReservesRepairSpaceAndNeverDoubleReturns() {
        val sender = Bp2SendCredit(2L * Bp2.CHUNK_BYTES)
        assertTrue(sender.reserve(Bp2.CHUNK_BYTES))
        assertFalse(sender.reserve(1))
        assertTrue(sender.reserve(Bp2.CHUNK_BYTES, retry = true))
        assertFalse(sender.reserve(1, retry = true))
        val receiver = Bp2ReceiveCredit(2L * Bp2.CHUNK_BYTES)
        receiver.consume(2, Bp2.CHUNK_BYTES); receiver.consume(3, Bp2.CHUNK_BYTES)
        assertFails { receiver.consume(4, 1) }
        val total = receiver.release(2)
        assertEquals(total, receiver.release(2))
        sender.update(total); sender.update(total)
        assertEquals(Bp2.CHUNK_BYTES.toLong(), sender.available)
        receiver.consume(4, Bp2.CHUNK_BYTES)
        assertEquals(total + Bp2.CHUNK_BYTES, receiver.release(4))
        assertFails { sender.update(total - 1) }
    }
}
