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
import java.util.concurrent.ConcurrentHashMap
import javax.net.ssl.SSLServerSocket
import javax.net.ssl.SSLSocket

internal class HttpRequestHead(
    val method: String,
    val path: String,
    val query: Map<String, String>,
    val headers: Map<String, String>,
    val contentLength: Long,
    val chunked: Boolean,
    val version: String,
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
    private val clients = ConcurrentHashMap.newKeySet<java.net.Socket>()

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
                if (clients.size >= 128) { client.close(); continue }
                clients += client
                val worker = launch {
                    connectionPermits.withPermit {
                        client.use {
                            runCatching { handleConnection(it) }
                        }
                    }
                }
                // Also runs if cancellation wins before the worker starts or acquires a permit.
                worker.invokeOnCompletion { clients -= client; runCatching { client.close() } }
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
        clients.forEach { runCatching { it.close() } }
        clients.clear()
    }

    private suspend fun handleConnection(client: java.net.Socket) {
        client.soTimeout = 20_000
        client.tcpNoDelay = true
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
                writeResponse(output, HttpResponse.json(400, """{"message":"Invalid HTTP request"}"""), false)
                break
            } ?: break

            val bodyStream: HttpRequestBody = when {
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

            // Never wait for a rejected request's remaining payload, or parse it as another request.
            val keepAlive = bodyStream.complete && shouldKeepAlive(head, response)
            writeResponse(output, response, keepAlive)
            if (!keepAlive) break
        }
    }

    private fun shouldKeepAlive(head: HttpRequestHead, response: HttpResponse): Boolean {
        if (response.stream != null && response.streamLength == null) return false
        val responseClose = response.headers.any { (name, value) -> name.equals("connection", true) &&
            value.split(',').any { it.trim().equals("close", true) } }
        if (responseClose) return false
        val connection = head.headers["connection"].orEmpty().split(',').map { it.trim().lowercase() }
        if ("close" in connection) return false
        return head.version == "HTTP/1.1" || "keep-alive" in connection
    }

    private fun writeResponse(output: OutputStream, response: HttpResponse, keepAlive: Boolean) {
        val builder = StringBuilder()
        builder.append("HTTP/1.1 ").append(response.status).append(' ').append(reason(response.status)).append("\r\n")
        response.contentType?.let { builder.append("Content-Type: ").append(it).append("\r\n") }
        builder.append("Connection: ").append(if (keepAlive) "keep-alive" else "close").append("\r\n")
        response.headers.filterKeys { !it.equals("connection", true) }.forEach { (name, value) ->
            builder.append(name).append(": ").append(value).append("\r\n")
        }

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
        val requestLine = readHttpLine(input) ?: return null
        if (requestLine.isBlank()) return null
        val parts = requestLine.split(' ')
        if (parts.size != 3 || parts[2] !in setOf("HTTP/1.0", "HTTP/1.1") ||
            !validHttpFieldName(parts[0]) || !parts[1].startsWith('/')) throw IOException("Malformed request line")

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
        var headerBytes = requestLine.length + 2
        var headerCount = 0
        while (true) {
            val line = readHttpLine(input) ?: throw EOFException("Unexpected end of headers")
            headerBytes += line.length + 2
            if (headerBytes > HTTP_HEADER_BYTES) throw IOException("HTTP headers too large")
            if (line.isEmpty()) break
            if (++headerCount > HTTP_HEADER_COUNT) throw IOException("Too many HTTP headers")
            val index = line.indexOf(':')
            if (index <= 0 || !validHttpFieldName(line.substring(0, index))) throw IOException("Malformed HTTP field")
            val name = line.substring(0, index).lowercase()
            val value = line.substring(index + 1).trim()
            if (value.any { it.code < 32 && it != '\t' || it.code == 127 }) throw IOException("Invalid HTTP field value")
            headers[name] = headers[name]?.let { "$it,$value" } ?: value
        }

        val transferEncoding = headers["transfer-encoding"]
        val lengths = headers["content-length"]?.split(',')?.map { value ->
            val decimal = value.trim()
            if (decimal.isEmpty() || decimal.any { it !in '0'..'9' }) throw IOException("Invalid Content-Length")
            decimal.toLongOrNull() ?: throw IOException("Content-Length overflow")
        }
        if (lengths?.distinct()?.size?.let { it != 1 } == true) throw IOException("Conflicting Content-Length")
        if (transferEncoding != null && (lengths != null || parts[2] != "HTTP/1.1" ||
                !transferEncoding.trim().equals("chunked", true))) throw IOException("Unsupported or ambiguous request framing")
        return HttpRequestHead(method, path, query, headers, lengths?.first() ?: -1L, transferEncoding != null, parts[2])
    }

    private fun decode(value: String): String = runCatching {
        URLDecoder.decode(value, "UTF-8")
    }.getOrDefault(value)
}
