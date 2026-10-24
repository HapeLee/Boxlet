package dev.boxlet.core.transport.bp2

import dev.boxlet.core.crypto.Certificates
import dev.boxlet.core.crypto.SecurityContext
import dev.boxlet.core.protocol.bp2.*
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.JsonObject
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.EOFException
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Socket
import java.net.ServerSocket
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resumeWithException
import javax.net.ssl.SSLSocket

class Bp2ConnectionLost(cause: Throwable? = null) : IOException("BP/2 connection lost", cause)

/** Frame transport only. Identity authorization and session HELLO are separate gates. */
class Bp2TlsConnection internal constructor(
    private val socket: SSLSocket,
    private val transportSocket: Socket,
    parentScope: CoroutineScope,
) : AutoCloseable {
    val peerFingerprint: String = requireNotNull(Certificates.peerFingerprint(socket.session)).lowercase()
    val remoteHost: String = requireNotNull(transportSocket.inetAddress.hostAddress).substringBefore('%')
    private val job = SupervisorJob(parentScope.coroutineContext[Job])
    private val scope = CoroutineScope(parentScope.coroutineContext + job + Dispatchers.IO)
    private val input = BufferedInputStream(socket.inputStream, 64 * 1024)
    private val output = BufferedOutputStream(socket.outputStream, 64 * 1024)
    private val readMutex = Mutex()
    private val closed = AtomicBoolean(false)
    private var nextRead = 1L
    private var nextWrite = 1L
    private var readExhausted = false
    private var writeExhausted = false
    private data class FrameWrite(val kind: Bp2Kind, val streamId: Long, val offset: Long, val payload: ByteArray)
    private data class Write(val frames: List<FrameWrite>, val done: CompletableDeferred<Unit>)
    private val writes = Channel<Write>(8, onUndeliveredElement = {
        it.done.completeExceptionally(IOException("BP/2 connection closed"))
    })

    init {
        socket.soTimeout = 30000
        // A blocked socket write is not coroutine-cancellable. A suspended sibling closes the
        // socket immediately on parent cancellation, before waiting for the writer to finish.
        scope.launch(start = CoroutineStart.UNDISPATCHED) {
            try { awaitCancellation() } finally { close() }
        }
        scope.launch {
            try {
                for (item in writes) {
                    try {
                        for (frame in item.frames) {
                            check(!writeExhausted) { "Sequence exhausted; reconnect" }
                            val header = Bp2Header(frame.kind, frame.streamId, frame.payload.size, frame.offset, nextWrite)
                            output.write(Bp2FrameCodec.encodeHeader(header))
                            output.write(frame.payload)
                            if (nextWrite == Long.MAX_VALUE) writeExhausted = true else nextWrite++
                        }
                        output.flush()
                        item.done.complete(Unit)
                    } catch (error: Throwable) {
                        item.done.completeExceptionally(error)
                        throw error
                    }
                }
            } catch (_: Throwable) {
                // The caller gets the original write failure through its deferred.
            } finally { close() }
        }
        job.invokeOnCompletion { close() }
    }

    /** Payload ownership passes to the writer until this suspending call completes. */
    suspend fun send(kind: Bp2Kind, streamId: Long = 0, offset: Long = 0, payload: ByteArray) {
        Bp2Header(kind, streamId, payload.size, offset, 1).validate()
        write(listOf(FrameWrite(kind, streamId, offset, payload)))
    }

    private suspend fun write(frames: List<FrameWrite>) {
        if (closed.get()) throw Bp2ConnectionLost()
        val done = CompletableDeferred<Unit>()
        try {
            writes.send(Write(frames, done)); done.await()
        } catch (error: CancellationException) {
            currentCoroutineContext().ensureActive(); throw Bp2ConnectionLost(error)
        } catch (error: IOException) { throw Bp2ConnectionLost(error) }
    }

    suspend fun sendControl(control: JsonObject) = send(Bp2Kind.CONTROL, payload = Bp2Controls.encode(control))

    /** Adjacent controls retain separate wire frames/sequences but share one bounded writer flush. */
    suspend fun sendControls(controls: List<JsonObject>) {
        require(controls.size in 1..64)
        var bytes = 0L
        val frames = controls.map {
            val payload = Bp2Controls.encode(it)
            bytes += payload.size + Bp2.HEADER_BYTES
            require(bytes <= Bp2.CONTROL_BYTES) { "Control write group exceeds budget" }
            FrameWrite(Bp2Kind.CONTROL, 0, 0, payload)
        }
        write(frames)
    }

    suspend fun receive(): Bp2Frame? = readMutex.withLock {
        withContext(Dispatchers.IO) {
            try {
                if (closed.get()) throw Bp2ConnectionLost()
                check(!readExhausted) { "Sequence exhausted; reconnect" }
                val bytes = ByteArray(Bp2.HEADER_BYTES)
                if (!readFully(bytes, cleanEof = true)) return@withContext null
                val header = Bp2FrameCodec.decodeHeader(bytes)
                require(header.sequence == nextRead) { "Non-contiguous BP/2 sequence" }
                val payload = ByteArray(header.payloadLength)
                readFully(payload)
                if (nextRead == Long.MAX_VALUE) readExhausted = true else nextRead++
                Bp2Frame(header, payload)
            } catch (error: IOException) { close(); throw Bp2ConnectionLost(error) }
            catch (error: Throwable) { close(); throw error }
        }
    }

    private fun readFully(target: ByteArray, cleanEof: Boolean = false): Boolean {
        var offset = 0
        while (offset < target.size) {
            val read = input.read(target, offset, target.size - offset)
            if (read < 0) {
                if (offset == 0 && cleanEof) { close(); return false }
                throw EOFException("EOF inside BP/2 frame")
            }
            offset += read
        }
        return true
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        // Close the underlying TCP socket first: JSSE close_notify may otherwise wait for a
        // blocked peer/read timeout. Cancellation must not wait 30 seconds for TLS shutdown.
        runCatching { transportSocket.close() }
        runCatching { socket.close() }
        writes.cancel(CancellationException("BP/2 connection closed"))
        job.cancel()
    }
}

object Bp2TlsClient {
    /** Always verify the expected certificate pin; receive consent is handled by the session. */
    suspend fun connect(
        host: String, port: Int, identity: SecurityContext, expectedFingerprint: String, scope: CoroutineScope,
    ): Bp2TlsConnection {
        Bp2.requireFingerprint(expectedFingerprint.lowercase())
        require(port in 1..65535)
        scope.ensureActive()
        return suspendCancellableCoroutine { continuation ->
            val transport = Socket()
            continuation.invokeOnCancellation { runCatching { transport.close() } }
            val worker = scope.launch(Dispatchers.IO) {
                var socket: SSLSocket? = null
                try {
                    transport.connect(InetSocketAddress(host, port), 5000)
                    val tls = Certificates.clientContext(identity, expectedFingerprint).socketFactory
                        .createSocket(transport, host, port, true) as SSLSocket
                    socket = tls
                    tls.configureBp2()
                    tls.startHandshake()
                    ensureActive()
                    val connection = Bp2TlsConnection(tls, transport, scope)
                    continuation.resume(connection) { _, value, _ -> value.close() }
                } catch (error: Throwable) {
                    runCatching { transport.close() }; runCatching { socket?.close() }
                    continuation.resumeWithException(error)
                }
            }
            worker.invokeOnCompletion { cause -> if (cause != null) continuation.cancel(cause) }
        }
    }
}

class Bp2TlsServer(
    private val identity: SecurityContext,
    private val scope: CoroutineScope,
    private val onConnection: suspend (Bp2TlsConnection) -> Unit,
    private val onFailure: (Throwable) -> Unit = {},
) : AutoCloseable {
    private var server: ServerSocket? = null
    private var acceptJob: Job? = null
    private val sockets = ConcurrentHashMap.newKeySet<Socket>()
    private val permits = Semaphore(16)
    var port: Int = 0
        private set

    fun start(port: Int = Bp2.PORT): Int {
        check(server == null)
        require(port in 0..65535)
        val context = Certificates.serverContext(identity)
        val listener = ServerSocket()
        try {
            listener.reuseAddress = true
            listener.bind(InetSocketAddress(port), 16)
        } catch (error: Throwable) { listener.close(); throw error }
        server = listener
        this.port = listener.localPort
        acceptJob = scope.launch(Dispatchers.IO) {
            try {
                while (isActive) {
                    val transport = listener.accept()
                    if (!permits.tryAcquire()) { transport.close(); continue }
                    sockets += transport
                    launch {
                        var connection: Bp2TlsConnection? = null
                        var socket: SSLSocket? = null
                        try {
                            val tls = context.socketFactory.createSocket(transport,
                                transport.inetAddress.hostAddress, transport.port, true) as SSLSocket
                            socket = tls
                            tls.useClientMode = false
                            tls.needClientAuth = true
                            tls.configureBp2()
                            tls.startHandshake()
                            (tls.session.peerCertificates.first() as java.security.cert.X509Certificate).checkValidity()
                            ensureActive()
                            connection = Bp2TlsConnection(tls, transport, this)
                            onConnection(connection)
                        } catch (error: Throwable) {
                            if (isActive) onFailure(error)
                        } finally {
                            connection?.close(); runCatching { transport.close() }; runCatching { socket?.close() }
                            sockets -= transport; permits.release()
                        }
                    }
                }
            } catch (error: Throwable) { if (isActive && !listener.isClosed) onFailure(error) }
        }
        return this.port
    }

    override fun close() {
        runCatching { server?.close() }
        server = null
        sockets.forEach { runCatching { it.close() } }
        acceptJob?.cancel()
        acceptJob = null
        port = 0
    }
}

private fun secureSuites(suites: Array<String>): Array<String> = suites.filter {
    it.startsWith("TLS_AES_") || it.startsWith("TLS_CHACHA20_") ||
        (it.startsWith("TLS_ECDHE_") && (it.contains("_GCM_") || it.contains("_CHACHA20_")))
}.toTypedArray().also { require(it.isNotEmpty()) { "No suitable TLS AEAD suite" } }

private fun SSLSocket.configureBp2() {
    enabledProtocols = supportedProtocols.filter { it in setOf("TLSv1.3", "TLSv1.2") }.toTypedArray()
    enabledCipherSuites = secureSuites(supportedCipherSuites)
    soTimeout = 10000
    tcpNoDelay = true
}
