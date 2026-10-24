package dev.boxlet.core.net

import java.io.ByteArrayOutputStream
import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import java.nio.charset.StandardCharsets

internal const val HTTP_LINE_BYTES = 16 * 1024
internal const val HTTP_HEADER_BYTES = 64 * 1024
internal const val HTTP_HEADER_COUNT = 128

/** Reuse requires a complete framing boundary, not merely a handler that returned successfully. */
internal abstract class HttpRequestBody : InputStream() {
    abstract val complete: Boolean
}

internal class LimitedInputStream(private val delegate: InputStream, private var remaining: Long) : HttpRequestBody() {
    init { require(remaining >= 0) }
    override val complete get() = remaining == 0L
    override fun read(): Int {
        if (complete) return -1
        val value = delegate.read()
        if (value < 0) throw EOFException("Incomplete HTTP body")
        remaining--
        return value
    }
    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        checkBounds(buffer, offset, length)
        if (length == 0) return 0
        if (complete) return -1
        val read = delegate.read(buffer, offset, minOf(length.toLong(), remaining).toInt())
        if (read < 0) throw EOFException("Incomplete HTTP body")
        remaining -= read
        return read
    }
    override fun available(): Int = minOf(remaining, delegate.available().toLong()).toInt()
}

/** Decodes standard chunk extensions and bounded trailers; malformed/truncated framing is an error. */
internal class ChunkedInputStream(private val delegate: InputStream) : HttpRequestBody() {
    private var remaining = 0L
    override var complete = false
        private set

    override fun read(): Int {
        val single = ByteArray(1)
        return if (read(single, 0, 1) < 0) -1 else single[0].toInt() and 0xff
    }
    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        checkBounds(buffer, offset, length)
        if (length == 0) return 0
        if (complete || (remaining == 0L && !nextChunk())) return -1
        val read = delegate.read(buffer, offset, minOf(length.toLong(), remaining).toInt())
        if (read < 0) throw EOFException("Incomplete HTTP chunk")
        remaining -= read
        if (remaining == 0L && (delegate.read() != 13 || delegate.read() != 10))
            throw IOException("Invalid chunk terminator")
        return read
    }
    private fun nextChunk(): Boolean {
        val line = readHttpLine(delegate) ?: throw EOFException("Missing HTTP chunk size")
        val size = line.substringBefore(';')
        if (size.isEmpty() || size.any { it !in '0'..'9' && it.lowercaseChar() !in 'a'..'f' })
            throw IOException("Invalid HTTP chunk size")
        remaining = size.toLongOrNull(16) ?: throw IOException("HTTP chunk size overflow")
        if (remaining != 0L) return true
        var bytes = 0; var count = 0
        while (true) {
            val trailer = readHttpLine(delegate) ?: throw EOFException("Incomplete HTTP trailers")
            bytes += trailer.length + 2
            if (bytes > HTTP_HEADER_BYTES || ++count > HTTP_HEADER_COUNT) throw IOException("HTTP trailers too large")
            if (trailer.isEmpty()) break
            if (!validHttpFieldName(trailer.substringBefore(':')) || ':' !in trailer) throw IOException("Malformed HTTP trailer")
        }
        complete = true
        return false
    }
}

internal fun readHttpLine(input: InputStream): String? {
    val bytes = ByteArrayOutputStream(128)
    while (true) {
        val value = input.read()
        if (value < 0) {
            if (bytes.size() == 0) return null
            throw EOFException("Incomplete HTTP line")
        }
        if (value == 13) {
            if (input.read() != 10) throw IOException("Invalid HTTP line ending")
            return bytes.toString(StandardCharsets.ISO_8859_1.name())
        }
        if (value == 10) throw IOException("Invalid HTTP line ending")
        if (bytes.size() >= HTTP_LINE_BYTES) throw IOException("HTTP line too long")
        bytes.write(value)
    }
}

internal fun validHttpFieldName(name: String) = name.isNotEmpty() && name.all {
    it in 'a'..'z' || it in 'A'..'Z' || it in '0'..'9' || it in "!#$%&'*+-.^_`|~"
}

internal fun readAtMost(input: InputStream, maxBytes: Int): ByteArray {
    require(maxBytes >= 0)
    val result = ByteArrayOutputStream(minOf(maxBytes, 8192))
    val chunk = ByteArray(8192)
    while (result.size() < maxBytes) {
        val read = input.read(chunk, 0, minOf(chunk.size, maxBytes - result.size()))
        if (read < 0) return result.toByteArray()
        if (read == 0) throw IOException("HTTP body made no progress")
        result.write(chunk, 0, read)
    }
    if (input.read() != -1) throw IOException("HTTP body exceeds limit")
    return result.toByteArray()
}

private fun checkBounds(buffer: ByteArray, offset: Int, length: Int) {
    if (offset < 0 || length < 0 || offset > buffer.size - length) throw IndexOutOfBoundsException()
}
