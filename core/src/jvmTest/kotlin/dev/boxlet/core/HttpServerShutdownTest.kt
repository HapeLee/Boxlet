package dev.boxlet.core

import dev.boxlet.core.crypto.Certificates
import dev.boxlet.core.net.HttpResponse
import dev.boxlet.core.net.LocalSendHttpServer
import kotlinx.coroutines.*
import java.net.InetSocketAddress
import java.net.Socket
import kotlin.test.*

class HttpServerShutdownTest {
    @Test fun stopClosesAcceptedKeepAliveConnectionWithoutWaitingForReadTimeout() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val server = LocalSendHttpServer(port = 0, scope = scope, https = false) { HttpResponse.empty(204) }
        val client = Socket()
        try {
            client.connect(InetSocketAddress("127.0.0.1", server.start()), 2000)
            client.soTimeout = 2000
            client.getOutputStream().write("GET / HTTP/1.1\r\nHost: localhost\r\nConnection: keep-alive\r\n\r\n".toByteArray())
            client.getOutputStream().flush()
            val input = client.getInputStream().bufferedReader()
            assertTrue(input.readLine().contains("204"))
            while (!input.readLine().isNullOrEmpty()) { /* consume response headers */ }
            server.stop()
            assertEquals(-1, withContext(Dispatchers.IO) { input.read() })
            scope.cancel(); withTimeout(3000) { scope.coroutineContext.job.join() }
        } finally { client.close(); server.stop(); scope.cancel() }
    }

    @Test fun stopReleasesIncompleteTlsHandshakesAndConnectionsQueuedForPermits() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val server = LocalSendHttpServer(Certificates.generate(), port = 0, scope = scope) { error("Handshake never completed") }
        val clients = mutableListOf<Socket>()
        try {
            val port = server.start()
            // More than the 64 handler permits: cancellation must also close queued sockets.
            repeat(70) { clients += Socket().apply { connect(InetSocketAddress("127.0.0.1", port), 2000) } }
            server.stop(); scope.cancel()
            withTimeout(3000) { scope.coroutineContext.job.join() }
        } finally { clients.forEach { it.close() }; server.stop(); scope.cancel() }
    }
}
