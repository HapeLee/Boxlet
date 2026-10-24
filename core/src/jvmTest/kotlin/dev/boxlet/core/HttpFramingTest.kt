package dev.boxlet.core

import dev.boxlet.core.net.*
import kotlinx.coroutines.*
import java.io.ByteArrayInputStream
import java.io.EOFException
import java.io.IOException
import java.net.Socket
import java.nio.charset.StandardCharsets
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.*

class HttpFramingTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var server: LocalSendHttpServer? = null
    private val handled = CopyOnWriteArrayList<String>()
    @AfterTest fun close() { server?.stop(); scope.cancel() }

    private fun start(reject: Boolean = false): Int = LocalSendHttpServer(port = 0, scope = scope, https = false) {
        handled += it.path
        if (reject) HttpResponse.empty(403) else HttpResponse.text(200, it.body.readBytes().toString(StandardCharsets.UTF_8))
    }.also { server = it }.start()

    private fun exchange(port: Int, request: String, body: (Socket) -> Unit) = Socket("127.0.0.1", port).use {
        it.soTimeout = 2000
        it.getOutputStream().write(request.toByteArray(StandardCharsets.ISO_8859_1)); it.getOutputStream().flush()
        body(it)
    }

    private data class Response(val code: Int, val headers: Map<String, String>, val body: String)
    private fun response(socket: Socket): Response {
        val input = socket.getInputStream()
        val status = readHttpLine(input) ?: error("Missing response")
        val headers = linkedMapOf<String, String>()
        while (true) {
            val line = readHttpLine(input) ?: error("Missing headers")
            if (line.isEmpty()) break
            headers[line.substringBefore(':').lowercase()] = line.substringAfter(':').trim()
        }
        val bytes = input.readNBytes(headers["content-length"]!!.toInt())
        return Response(status.split(' ')[1].toInt(), headers, bytes.toString(StandardCharsets.UTF_8))
    }

    @Test fun rejectedUnreadBodyRespondsImmediatelyAndCannotBecomeAnotherRequest() {
        val port = start(reject = true)
        exchange(port, "POST /reject HTTP/1.1\r\nHost: local\r\nContent-Length: 99999999\r\n\r\n" +
            "GET /must-not-run HTTP/1.1\r\nHost: local\r\n\r\n") { socket ->
            val result = response(socket)
            assertEquals(403, result.code); assertEquals("close", result.headers["connection"])
        }
        assertEquals(listOf("/reject"), handled.toList())
    }

    @Test fun fixedLengthAndChunkedBodiesLeaveTheNextRequestIntact() {
        val port = start()
        for ((encoding, payload) in listOf("Content-Length: 9" to "Wikipedia",
            "Transfer-Encoding: chunked" to "4;name=value\r\nWiki\r\n5\r\npedia\r\n0\r\nX-Test: yes\r\n\r\n")) {
            exchange(port, "POST /body HTTP/1.1\r\nHost: local\r\n$encoding\r\n\r\n$payload" +
                "GET /next HTTP/1.1\r\nHost: local\r\nConnection: keep-alive, close\r\n\r\n") { socket ->
                val first = response(socket); val next = response(socket)
                assertEquals(200, first.code); assertEquals("Wikipedia", first.body)
                assertEquals("keep-alive", first.headers["connection"])
                assertEquals(200, next.code); assertEquals("close", next.headers["connection"])
                assertEquals(-1, socket.getInputStream().read())
            }
        }
        assertEquals(listOf("/body", "/next", "/body", "/next"), handled.toList())
    }

    @Test fun ambiguousOrOversizedHeadersAreRejectedBeforeDispatch() {
        val port = start()
        val invalid = listOf("Content-Length: 1\r\nContent-Length: 2",
            "Content-Length: 1\r\nTransfer-Encoding: chunked", "Content-Length: -1",
            "Content-Length: 999999999999999999999999", "Transfer-Encoding: not-chunked",
            "Invalid : value", "X-Test: " + "a".repeat(HTTP_LINE_BYTES + 1),
            (0..HTTP_HEADER_COUNT).joinToString("\r\n") { "X-$it: value" },
            (0..5).joinToString("\r\n") { "X-$it: " + "a".repeat(12000) })
        invalid.forEach { headers -> exchange(port, "POST /bad HTTP/1.1\r\nHost: local\r\n$headers\r\n\r\n") {
            val result = response(it); assertEquals(400, result.code); assertEquals("close", result.headers["connection"])
        } }
        assertTrue(handled.isEmpty())
    }

    @Test fun identicalContentLengthsAndHttp10HaveUnambiguousBoundaries() {
        val port = start()
        exchange(port, "POST /body HTTP/1.0\r\nContent-Length: 3\r\nContent-Length: 03\r\n\r\nabc") {
            val result = response(it); assertEquals("abc", result.body); assertEquals("close", result.headers["connection"])
        }
    }

    @Test fun truncatedOrMalformedBodyCannotLookLikeSuccessfulEof() {
        assertFailsWith<EOFException> { LimitedInputStream(ByteArrayInputStream(byteArrayOf(1)), 2).readBytes() }
        listOf("2\r\na", "1\r\naXX", "-1\r\n", "NO\r\n", "ffffffffffffffff\r\n", "0\r\nX: a\r\n").forEach { encoded ->
            assertFailsWith<IOException>(encoded) { ChunkedInputStream(ByteArrayInputStream(encoded.toByteArray())).readBytes() }
        }
        assertFailsWith<IOException> { readAtMost(ByteArrayInputStream(byteArrayOf(1, 2)), 1) }
        val empty = LimitedInputStream(ByteArrayInputStream(byteArrayOf()), 0)
        assertEquals(0, empty.read(ByteArray(0), 0, 0)); assertTrue(empty.complete)
    }
}
