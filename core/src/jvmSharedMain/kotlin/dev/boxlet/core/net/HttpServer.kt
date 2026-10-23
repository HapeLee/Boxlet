package dev.boxlet.core.net

import dev.boxlet.core.crypto.Certificates
import dev.boxlet.core.crypto.SecurityContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import okio.Source
import okio.buffer
import okio.sink
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import javax.net.ssl.SSLServerSocket
import javax.net.ssl.SSLSocket

internal class HttpRequestHead(
    val method: String,
    val path: String,
    val query: Map<String, String>,
    val headers: Map<String, String>,
    val contentLength: Long,
    val chunked: Boolean,
)

class HttpCall internal constructor(
    val method: String,
    val path: String,
    val query: Map<String, String>,
    private val headers: Map<String, String>,
    val body: InputStream,
    val contentLength: Long,
    val remoteIp: String,
    val clientFingerprint: String?,
) {
    fun header(name: String): String? = headers[name.lowercase()]

    fun queryParam(name: String): String? = query[name]

    /** Reads the whole body with a hard limit to protect against malformed peers. */
    fun readBodyText(maxBytes: Int = 1 shl 20): String {
        val bytes = readAtMost(body, maxBytes)
        return String(bytes, StandardCharsets.UTF_8)
    }
}

class HttpResponse(
    val status: Int,
    val contentType: String? = "application/json; charset=utf-8",
    val body: ByteArray = ByteArray(0),
    val headers: Map<String, String> = emptyMap(),
    val stream: Source? = null,
    val streamLength: Long? = null,
) {
    companion object {
        fun json(status: Int, text: String): HttpResponse =
            HttpResponse(status, "application/json; charset=utf-8", text.toByteArray(StandardCharsets.UTF_8))

        fun empty(status: Int): HttpResponse = HttpResponse(status, contentType = null)

        fun text(status: Int, text: String, contentType: String = "text/plain; charset=utf-8"): HttpResponse =
            HttpResponse(status, contentType, text.toByteArray(StandardCharsets.UTF_8))
    }
}

fun interface HttpHandler {
    suspend fun handle(call: HttpCall): HttpResponse
}

/**
 * Minimal HTTP/1.1 server over TLS, tuned for the LocalSend API.
 *
 * Why not Ktor: the protocol requires optional client certificates whose trust decisions
 * are based on a SHA-256 pin of a self-signed certificate rather than a CA chain. Owning
 * the socket layer keeps that behavior identical on Android and the JVM.
 */
class LocalSendHttpServer(
    /** Only required when [https] is true; the plain-HTTP share service passes null. */
    private val security: SecurityContext? = null,
    private val port: Int,
    private val scope: CoroutineScope,
    private val https: Boolean = true,
    private val handler: HttpHandler,
) {
    private var serverSocket: java.net.ServerSocket? = null
    private var acceptJob: Job? = null
    private val connectionPermits = Semaphore(64)

    @Volatile
    var isRunning: Boolean = false
        private set

    fun start(): Int {
        check(serverSocket == null) { "Server already started" }
        val socket: java.net.ServerSocket = if (https) {
            val context = Certificates.serverContext(
                requireNotNull(security) { "HTTPS server requires a security context" },
            )
            val ssl = context.serverSocketFactory.createServerSocket() as SSLServerSocket
            ssl.reuseAddress = true
            ssl.bind(InetSocketAddress(port), 64)
            ssl.soTimeout = 0
            ssl.enabledProtocols = ssl.supportedProtocols
                .filter { it == "TLSv1.3" || it == "TLSv1.2" }
                .toTypedArray()
            // Optional, not mandatory: browsers must still be able to load the web page.
            // Setting needClientAuth=false here would clear this flag on the accepted socket.
            ssl.wantClientAuth = true
            ssl
        } else {
            java.net.ServerSocket().apply {
                reuseAddress = true
                bind(InetSocketAddress(port), 64)
            }
        }
        serverSocket = socket
        isRunning = true

        acceptJob = scope.launch(Dispatchers.IO) {
            while (isActive) {
                val client = try {
                    socket.accept()
                } catch (error: IOException) {
                    if (!isActive || socket.isClosed) break else continue
                }
                launch {
                    connectionPermits.withPermit {
                        client.use {
                            runCatching { handleConnection(it) }
                        }
                    }
                }
            }
        }

        return socket.localPort
    }

    fun stop() {
        isRunning = false
        acceptJob?.cancel()
        acceptJob = null
        runCatching { serverSocket?.close() }
        serverSocket = null
    }

    private suspend fun handleConnection(client: java.net.Socket) {
        client.soTimeout = 20_000
        val ssl = client as? SSLSocket
        ssl?.startHandshake()

        val input = BufferedInputStream(client.getInputStream(), 16 * 1024)
        val output = BufferedOutputStream(client.getOutputStream(), 16 * 1024)
        val remoteIp = client.inetAddress?.hostAddress?.substringBefore('%') ?: "0.0.0.0"
        val clientFingerprint = ssl?.let { Certificates.peerFingerprint(it.session) }

        while (true) {
            val head = try {
                readHead(input)
            } catch (_: EOFException) {
                break
            } catch (_: IOException) {
                break
            } ?: break

            val bodyStream: InputStream = when {
                head.chunked -> ChunkedInputStream(input)
                head.contentLength > 0 -> LimitedInputStream(input, head.contentLength)
                else -> LimitedInputStream(input, 0)
            }

            val call = HttpCall(
                method = head.method,
                path = head.path,
                query = head.query,
                headers = head.headers,
                body = bodyStream,
                contentLength = head.contentLength,
                remoteIp = remoteIp,
                clientFingerprint = clientFingerprint,
            )

            val response = try {
                handler.handle(call)
            } catch (error: Throwable) {
                println("[Boxlet] http handler failed: ${error.message}")
                HttpResponse.json(500, """{"message":"Internal server error"}""")
            }

            // Draining unread body bytes keeps the connection reusable.
            runCatching { drain(bodyStream) }

            val keepAlive = shouldKeepAlive(head, response)
            writeResponse(output, response, keepAlive)
            if (!keepAlive) break
        }
    }

    private fun shouldKeepAlive(head: HttpRequestHead, response: HttpResponse): Boolean {
        if (response.stream != null && response.streamLength == null) return false
        val connection = head.headers["connection"]?.lowercase()
        if (connection == "close") return false
        if (connection == "keep-alive") return true
        return true
    }

    private fun writeResponse(output: OutputStream, response: HttpResponse, keepAlive: Boolean) {
        val builder = StringBuilder()
        builder.append("HTTP/1.1 ").append(response.status).append(' ').append(reason(response.status)).append("\r\n")
        response.contentType?.let { builder.append("Content-Type: ").append(it).append("\r\n") }
        builder.append("Connection: ").append(if (keepAlive) "keep-alive" else "close").append("\r\n")
        response.headers.forEach { (name, value) -> builder.append(name).append(": ").append(value).append("\r\n") }

        val stream = response.stream
        if (stream != null) {
            response.streamLength?.let { builder.append("Content-Length: ").append(it).append("\r\n") }
            builder.append("\r\n")
            output.write(builder.toString().toByteArray(StandardCharsets.ISO_8859_1))
            output.flush()
            val sink = output.sink()
            stream.use { source ->
                source.buffer().use { it.readAll(sink) }
            }
            sink.flush()
        } else {
            builder.append("Content-Length: ").append(response.body.size).append("\r\n\r\n")
            output.write(builder.toString().toByteArray(StandardCharsets.ISO_8859_1))
            if (response.body.isNotEmpty()) output.write(response.body)
        }
        output.flush()
    }

    private fun drain(body: InputStream) {
        val buffer = ByteArray(8 * 1024)
        var total = 0L
        while (total < 8L * 1024 * 1024) {
            val read = body.read(buffer)
            if (read < 0) return
            total += read
        }
    }

    private fun reason(status: Int): String = when (status) {
        200 -> "OK"
        204 -> "No Content"
        400 -> "Bad Request"
        401 -> "Unauthorized"
        403 -> "Forbidden"
        404 -> "Not Found"
        409 -> "Conflict"
        422 -> "Unprocessable Entity"
        429 -> "Too Many Requests"
        500 -> "Internal Server Error"
        else -> "Status"
    }

    private fun readHead(input: InputStream): HttpRequestHead? {
        val requestLine = readLine(input) ?: return null
        if (requestLine.isBlank()) return null
        val parts = requestLine.split(' ')
        if (parts.size < 2) throw IOException("Malformed request line")

        val method = parts[0].uppercase()
        val target = parts[1]
        val rawQuery = target.substringAfter('?', "")
        val path = target.substringBefore('?')
        val query = if (rawQuery.isEmpty()) {
            emptyMap()
        } else {
            rawQuery.split('&').mapNotNull { pair ->
                if (pair.isBlank()) return@mapNotNull null
                val key = decode(pair.substringBefore('='))
                val value = decode(pair.substringAfter('=', ""))
                key to value
            }.toMap()
        }

        val headers = LinkedHashMap<String, String>()
        while (true) {
            val line = readLine(input) ?: throw EOFException("Unexpected end of headers")
            if (line.isEmpty()) break
            val index = line.indexOf(':')
            if (index <= 0) continue
            headers[line.substring(0, index).trim().lowercase()] = line.substring(index + 1).trim()
        }

        val transferEncoding = headers["transfer-encoding"]?.lowercase()
        val chunked = transferEncoding?.contains("chunked") == true
        val contentLength = headers["content-length"]?.toLongOrNull() ?: -1L
        return HttpRequestHead(method, path, query, headers, contentLength, chunked)
    }

    private fun readLine(input: InputStream): String? {
        val buffer = java.io.ByteArrayOutputStream(128)
        while (true) {
            val value = input.read()
            if (value < 0) {
                if (buffer.size() == 0) return null
                break
            }
            if (value == '\n'.code) break
            if (buffer.size() > 16 * 1024) throw IOException("Header line too long")
            if (value != '\r'.code) buffer.write(value)
        }
        return buffer.toString(StandardCharsets.ISO_8859_1.name())
    }

    private fun decode(value: String): String = runCatching {
        URLDecoder.decode(value, "UTF-8")
    }.getOrDefault(value)
}

internal fun readAtMost(input: InputStream, maxBytes: Int): ByteArray {
    val buffer = java.io.ByteArrayOutputStream(minOf(maxBytes, 8192))
    val chunk = ByteArray(8192)
    var total = 0
    while (total < maxBytes) {
        val read = input.read(chunk, 0, minOf(chunk.size, maxBytes - total))
        if (read < 0) break
        buffer.write(chunk, 0, read)
        total += read
    }
    return buffer.toByteArray()
}

internal class LimitedInputStream(
    private val delegate: InputStream,
    private var remaining: Long,
) : InputStream() {
    override fun read(): Int {
        if (remaining <= 0) return -1
        val value = delegate.read()
        if (value >= 0) remaining--
        return value
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (remaining <= 0) return -1
        val read = delegate.read(buffer, offset, minOf(length.toLong(), remaining).toInt())
        if (read > 0) remaining -= read
        return read
    }

    override fun available(): Int = minOf(remaining, delegate.available().toLong()).toInt()
}

/** Decodes HTTP/1.1 chunked transfer encoding, which some official clients use for uploads. */
internal class ChunkedInputStream(private val delegate: InputStream) : InputStream() {
    private var chunkRemaining = 0L
    private var finished = false

    override fun read(): Int {
        val single = ByteArray(1)
        val read = read(single, 0, 1)
        return if (read < 0) -1 else single[0].toInt() and 0xFF
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (finished) return -1
        if (chunkRemaining == 0L) {
            if (!nextChunk()) return -1
        }
        val read = delegate.read(buffer, offset, minOf(length.toLong(), chunkRemaining).toInt())
        if (read < 0) {
            finished = true
            return -1
        }
        chunkRemaining -= read
        if (chunkRemaining == 0L) consumeCrlf()
        return read
    }

    private fun nextChunk(): Boolean {
        val line = readAsciiLine() ?: return false
        val size = line.substringBefore(';').trim().toLongOrNull(16) ?: return false
        if (size == 0L) {
            // Consume optional trailers.
            while (true) {
                val trailer = readAsciiLine() ?: break
                if (trailer.isEmpty()) break
            }
            finished = true
            return false
        }
        chunkRemaining = size
        return true
    }

    private fun consumeCrlf() {
        val buffer = ByteArray(2)
        var read = 0
        while (read < 2) {
            val value = delegate.read(buffer, read, 2 - read)
            if (value < 0) return
            read += value
        }
    }

    private fun readAsciiLine(): String? {
        val builder = StringBuilder()
        while (true) {
            val value = delegate.read()
            if (value < 0) return if (builder.isEmpty()) null else builder.toString()
            if (value == '\n'.code) break
            if (value != '\r'.code) builder.append(value.toChar())
        }
        return builder.toString()
    }
}
