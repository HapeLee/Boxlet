package dev.boxlet.core.net

import dev.boxlet.core.engine.OutgoingFile
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody
import okio.Buffer
import okio.BufferedSink
import okio.HashingSink
import okio.buffer
import java.io.IOException

/** Streams the declared bytes; the legacy private checksum trailer is explicitly negotiated. */
internal class LocalSendUploadBody(
    private val file: OutgoingFile,
    private val isCancelled: () -> Boolean,
    private val onProgress: (Long) -> Unit,
    private val streamingChecksum: Boolean,
) : RequestBody() {
    override fun contentType() = "application/octet-stream".toMediaType()
    override fun contentLength(): Long = if (streamingChecksum) Math.addExact(file.size, 32L) else file.size

    override fun writeTo(sink: BufferedSink) {
        val hashingSink = if (streamingChecksum) HashingSink.sha256(sink) else null
        val dataSink = hashingSink?.buffer() ?: sink
        var written = 0L
        file.openSource().use { source ->
            val buffer = Buffer()
            while (true) {
                if (isCancelled()) throw IOException("Upload cancelled")
                val read = source.read(buffer, 64 * 1024)
                if (read == -1L) break
                if (read > file.size - written) throw IOException("Source exceeds declared size")
                dataSink.write(buffer, read)
                written += read
                onProgress(written)
            }
        }
        if (written != file.size) throw IOException("Source size mismatch: expected ${file.size}, read $written")
        if (hashingSink != null) {
            dataSink.flush()
            sink.write(hashingSink.hash)
        }
    }
}
