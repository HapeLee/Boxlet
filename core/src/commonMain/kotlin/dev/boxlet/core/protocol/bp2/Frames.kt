package dev.boxlet.core.protocol.bp2

import okio.Buffer

enum class Bp2Kind(val wire: Int, val maxPayload: Int) {
    CONTROL(1, Bp2.CONTROL_BYTES), DATA(2, Bp2.CHUNK_BYTES + Bp2.HASH_BYTES), BATCH(3, Bp2.BATCH_BYTES);
}

data class Bp2Header(
    val kind: Bp2Kind,
    val streamId: Long,
    val payloadLength: Int,
    val offset: Long = 0,
    val sequence: Long,
) {
    fun validate() {
        require(streamId in 0..0xffffffffL && offset >= 0 && sequence > 0)
        require(payloadLength in 0..kind.maxPayload) { "BP/2 payload exceeds limit" }
        when (kind) {
            Bp2Kind.CONTROL -> require(streamId == 0L && offset == 0L && payloadLength >= 2)
            Bp2Kind.BATCH -> require(streamId == 0L && offset == 0L && payloadLength >= 53)
            Bp2Kind.DATA -> {
                require(streamId != 0L && payloadLength > Bp2.HASH_BYTES)
                require(offset % Bp2.CHUNK_BYTES == 0L)
                require(offset <= Long.MAX_VALUE - (payloadLength - Bp2.HASH_BYTES))
            }
        }
    }
}

data class Bp2Frame(val header: Bp2Header, val payload: ByteArray)

object Bp2FrameCodec {
    fun encodeHeader(header: Bp2Header): ByteArray {
        header.validate()
        return Buffer().apply {
            writeUtf8(Bp2.MAGIC); writeByte(header.kind.wire); writeByte(0); writeShort(Bp2.HEADER_BYTES)
            writeInt(header.streamId.toInt()); writeInt(header.payloadLength)
            writeLong(header.offset); writeLong(header.sequence)
        }.readByteArray()
    }

    /** Validates before any payload-sized allocation. */
    fun decodeHeader(bytes: ByteArray): Bp2Header {
        require(bytes.size == Bp2.HEADER_BYTES)
        val input = Buffer().write(bytes)
        require(input.readUtf8(4) == Bp2.MAGIC) { "Invalid BP/2 magic" }
        val kindId = input.readByte().toInt() and 255
        val kind = Bp2Kind.entries.firstOrNull { it.wire == kindId } ?: error("Unknown BP/2 frame kind")
        require(input.readByte() == 0.toByte()) { "Unknown frame flags" }
        require(input.readShort().toInt() == Bp2.HEADER_BYTES) { "Invalid frame header length" }
        return Bp2Header(kind, input.readInt().toLong() and 0xffffffffL,
            input.readInt(), input.readLong(), input.readLong()).also { it.validate() }
    }

    fun encode(frame: Bp2Frame): ByteArray {
        require(frame.payload.size == frame.header.payloadLength)
        return encodeHeader(frame.header) + frame.payload
    }

    /** One complete WSS binary message, with no trailing second frame. */
    fun decodeMessage(bytes: ByteArray): Bp2Frame {
        require(bytes.size in Bp2.HEADER_BYTES..Bp2.HEADER_BYTES + Bp2Kind.DATA.maxPayload)
        val header = decodeHeader(bytes.copyOfRange(0, Bp2.HEADER_BYTES))
        require(bytes.size == Bp2.HEADER_BYTES + header.payloadLength) { "Incomplete or combined WSS frame" }
        return Bp2Frame(header, bytes.copyOfRange(Bp2.HEADER_BYTES, bytes.size))
    }
}

/** Incremental TCP decoder. Keeps only one bounded frame, even when input contains many frames. */
class Bp2FrameDecoder {
    private val head = ByteArray(Bp2.HEADER_BYTES)
    private var headBytes = 0
    private var header: Bp2Header? = null
    private var body = ByteArray(0)
    private var bodyBytes = 0
    private var nextSequence = 1L
    private var exhausted = false
    private var failed = false

    fun feed(bytes: ByteArray, onFrame: (Bp2Frame) -> Unit) {
        check(!failed) { "Decoder has failed" }
        try {
            var cursor = 0
            while (cursor < bytes.size) {
                if (header == null) {
                    check(!exhausted) { "Sequence exhausted; reconnect" }
                    val count = minOf(head.size - headBytes, bytes.size - cursor)
                    bytes.copyInto(head, headBytes, cursor, cursor + count)
                    cursor += count; headBytes += count
                    if (headBytes != head.size) continue
                    val parsed = Bp2FrameCodec.decodeHeader(head)
                    require(parsed.sequence == nextSequence) { "Non-contiguous BP/2 sequence" }
                    header = parsed
                    body = ByteArray(parsed.payloadLength)
                }
                val count = minOf(body.size - bodyBytes, bytes.size - cursor)
                bytes.copyInto(body, bodyBytes, cursor, cursor + count)
                cursor += count; bodyBytes += count
                if (bodyBytes == body.size) {
                    val complete = Bp2Frame(header!!, body)
                    if (nextSequence == Long.MAX_VALUE) exhausted = true else nextSequence++
                    header = null; headBytes = 0; bodyBytes = 0; body = ByteArray(0)
                    onFrame(complete)
                }
            }
        } catch (error: Throwable) {
            failed = true
            throw error
        }
    }

    fun finish() { check(!failed && headBytes == 0 && header == null) { "EOF inside BP/2 frame" } }
}

data class Bp2BatchRecord(val streamId: Long, val bytes: ByteArray, val sha256: ByteArray)

object Bp2Payloads {
    fun data(bytes: ByteArray): ByteArray {
        require(bytes.size in 1..Bp2.CHUNK_BYTES)
        return Bp2Digests.sha256(bytes) + bytes
    }

    fun verifyData(frame: Bp2Frame, fileSize: Long): Boolean {
        require(frame.header.kind == Bp2Kind.DATA && frame.payload.size == frame.header.payloadLength)
        frame.header.validate()
        require(fileSize >= 0 && frame.header.offset < fileSize)
        val count = frame.payload.size - Bp2.HASH_BYTES
        require(count.toLong() == minOf(Bp2.CHUNK_BYTES.toLong(), fileSize - frame.header.offset))
        return frame.payload.copyOfRange(0, Bp2.HASH_BYTES).contentEquals(
            Bp2Digests.sha256(frame.payload, Bp2.HASH_BYTES, count))
    }

    fun encodeBatch(records: List<Bp2BatchRecord>): ByteArray {
        require(records.size in 1..Bp2.BATCH_RECORDS)
        require(records.map { it.streamId }.distinct().size == records.size)
        var size = 4
        records.forEach {
            require(it.streamId in 1..0xffffffffL && it.bytes.size in 1..Bp2.BATCH_FILE_BYTES && it.sha256.size == 32)
            size += 48 + it.bytes.size
        }
        require(size <= Bp2.BATCH_BYTES)
        return Buffer().apply {
            writeShort(records.size); writeShort(0)
            records.forEach { writeInt(it.streamId.toInt()); writeLong(0); writeInt(it.bytes.size); write(it.sha256); write(it.bytes) }
        }.readByteArray()
    }

    /** Structural validation is separate from per-record digest failures, so valid records can succeed. */
    fun decodeBatch(bytes: ByteArray): List<Bp2BatchRecord> {
        require(bytes.size in 53..Bp2.BATCH_BYTES)
        val input = Buffer().write(bytes)
        val count = input.readShort().toInt() and 65535
        require(count in 1..Bp2.BATCH_RECORDS && input.readShort() == 0.toShort())
        val ids = mutableSetOf<Long>()
        return List(count) {
            require(input.size >= 48)
            val id = input.readInt().toLong() and 0xffffffffL
            require(id > 0 && ids.add(id) && input.readLong() == 0L)
            val length = input.readInt()
            require(length in 1..Bp2.BATCH_FILE_BYTES && input.size >= 32L + length)
            val digest = input.readByteArray(32)
            Bp2BatchRecord(id, input.readByteArray(length.toLong()), digest)
        }.also { require(input.size == 0L) { "Trailing BATCH bytes" } }
    }
}
