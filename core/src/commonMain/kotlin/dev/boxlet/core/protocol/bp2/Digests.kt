package dev.boxlet.core.protocol.bp2

import kotlinx.serialization.Serializable
import okio.Buffer
import okio.HashingSink
import okio.blackholeSink
import kotlin.time.Instant

@Serializable
data class Bp2Entry(
    val fileId: String,
    val kind: String = "file",
    val path: String,
    val size: String,
    val mime: String = "",
    val modified: String = "",
    val preview: String = "",
    val streamId: Long? = null,
) {
    fun validate(requireStream: Boolean = false) {
        Bp2.requireId(fileId)
        val length = Bp2.decimal(size)
        require(kind in listOf("file", "directory", "text"))
        require(path.isNotEmpty() && path.encodeToByteArray(throwOnInvalidSequence = true).size <= 4096)
        require(path.none { it == '\\' || it == '\u0000' || it == ':' })
        require(path.split('/').all { it.isNotEmpty() && it != "." && it != ".." && it.encodeToByteArray().size <= 255 })
        require(mime.encodeToByteArray(throwOnInvalidSequence = true).size <= 256)
        require(preview.encodeToByteArray(throwOnInvalidSequence = true).size <= 4096)
        if (modified.isNotEmpty()) {
            require(Regex("\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}\\.\\d{3}Z").matches(modified))
            Instant.parse(modified)
        }
        if (kind == "directory") require(length == 0L && streamId == null)
        if (kind == "text") require(length <= Bp2.BATCH_FILE_BYTES)
        if (streamId != null) require(streamId in 1..0xffffffffL)
        if (requireStream && kind != "directory") require(streamId != null)
    }
}

object Bp2Digests {
    fun sha256(bytes: ByteArray, offset: Int = 0, count: Int = bytes.size - offset): ByteArray {
        require(offset >= 0 && count >= 0 && offset <= bytes.size - count)
        return Buffer().write(bytes, offset, count).sha256().toByteArray()
    }

    fun hex(bytes: ByteArray): String = bytes.joinToString("") { (it.toInt() and 255).toString(16).padStart(2, '0') }

    fun unhex(value: String): ByteArray {
        require(value.length % 2 == 0 && value.all { it in '0'..'9' || it in 'a'..'f' })
        return ByteArray(value.length / 2) { value.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
    }
}

private class DigestWriter(domain: String) {
    private val sink = HashingSink.sha256(blackholeSink())
    private var finished = false
    init { write(Buffer().writeUtf8(domain).writeByte(0)) }
    fun write(buffer: Buffer) { check(!finished); sink.write(buffer, buffer.size) }
    fun finish(): ByteArray { check(!finished); finished = true; return sink.hash.toByteArray() }
}

/** Incremental canonical manifest hash; stream IDs deliberately do not identify file content. */
class Bp2ManifestDigest {
    private val writer = DigestWriter("boxlet-manifest-v2")
    private val ids = mutableSetOf<String>()
    fun add(entry: Bp2Entry) {
        entry.validate()
        require(ids.size < 100000 && ids.add(entry.fileId)) { "Duplicate file ID or manifest limit exceeded" }
        val buffer = Buffer().writeByte(when (entry.kind) { "file" -> 1; "directory" -> 2; else -> 3 })
            .write(Bp2Digests.unhex(entry.fileId)).writeLong(Bp2.decimal(entry.size))
        listOf(entry.path, entry.mime, entry.modified, entry.preview).forEach {
            val bytes = it.encodeToByteArray(throwOnInvalidSequence = true)
            buffer.writeInt(bytes.size).write(bytes)
        }
        writer.write(buffer)
    }
    fun finish(): String = Bp2Digests.hex(writer.finish())
}

/** Ordered chunk-hash accumulator. Keeps O(1) memory, rather than all source file bytes. */
class Bp2ContentDigest(val fileSize: Long, val prefixChunks: Long? = null) {
    private val chunks = if (fileSize == 0L) 0L else (fileSize - 1) / Bp2.CHUNK_BYTES + 1
    private val count = prefixChunks ?: chunks
    private val writer = DigestWriter(if (prefixChunks == null) "boxlet-file-v2" else "boxlet-prefix-v2")
    private var added = 0L
    init {
        require(fileSize >= 0 && count in 0..chunks)
        writer.write(Buffer().writeLong(fileSize).writeInt(Bp2.CHUNK_BYTES).apply {
            if (prefixChunks != null) writeLong(prefixChunks)
        })
    }
    fun addChunkHash(index: Long, sha256: ByteArray) {
        require(index == added && added < count && sha256.size == 32) { "Missing, duplicate or out-of-order chunk hash" }
        writer.write(Buffer().write(sha256)); added++
    }
    fun finish(): String { require(added == count) { "Incomplete content digest" }; return Bp2Digests.hex(writer.finish()) }
}
