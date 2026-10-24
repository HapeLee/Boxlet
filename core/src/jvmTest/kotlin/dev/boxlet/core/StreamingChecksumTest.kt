package dev.boxlet.core

import dev.boxlet.core.crypto.Certificates
import dev.boxlet.core.engine.*
import dev.boxlet.core.net.PrepareUploadOutcome
import dev.boxlet.core.net.UploadOutcome
import dev.boxlet.core.protocol.*
import kotlinx.coroutines.*
import okio.Buffer
import java.io.ByteArrayInputStream
import java.security.MessageDigest
import kotlin.test.*

class StreamingChecksumTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val security = Certificates.generate()
    private var commits = 0
    private var aborts = 0
    private var saved: ByteArray? = null
    private val storage = object : ReceiveStorage {
        override suspend fun createDestination(sessionId: String, file: FileDto, senderAlias: String): ReceiveDestination {
            val buffer = Buffer()
            return object : ReceiveDestination {
                override val sink = buffer
                override val displayPath = "memory://test"
                override suspend fun commit() { commits++; saved = buffer.readByteArray() }
                override suspend fun abort() { aborts++ }
            }
        }
    }

    @AfterTest
    fun tearDown() { scope.cancel() }

    private fun receiver(legacyExtension: Boolean = true) = TransferEngine(scope, security, storage).apply {
        installConfig {
            EngineConfig(alias = "Receiver", deviceModel = null, fingerprint = security.fingerprint, quickSave = true,
                // The negotiated trailer must still be checked when legacy verification is disabled.
                verifyChecksums = false, legacyStreamingChecksumEnabled = legacyExtension)
        }
    }

    private suspend fun prepare(receiver: TransferEngine, size: Int): PrepareUploadOutcome.Accepted {
        return receiver.onPrepareUpload(
            PrepareUploadRequest(
                RegisterRequest(alias = "Sender", fingerprint = "sender", protocol = ProtocolType.HTTP),
                mapOf("f" to FileDto("f", "test.bin", size.toLong(), "application/octet-stream",
                    sendeeChecksum = Protocol.STREAMING_CHECKSUM))),
            null, "127.0.0.1", null,
        ) as PrepareUploadOutcome.Accepted
    }

    private suspend fun upload(receiver: TransferEngine, session: PrepareUploadOutcome.Accepted, bytes: ByteArray) =
        receiver.onUpload(session.sessionId, "f", session.files.getValue("f"), "127.0.0.1",
            ByteArrayInputStream(bytes), bytes.size.toLong()) {}

    private fun digest(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes)

    @Test fun standardEndpointDoesNotAdvertiseOrAcceptPrivateChecksumTrailers() = runBlocking {
        val receiver = receiver(legacyExtension = false)
        assertNull(receiver.localInfo().sendeeCapabilities)
        val result = receiver.onPrepareUpload(PrepareUploadRequest(
            RegisterRequest(alias = "Sender", fingerprint = "sender", protocol = ProtocolType.HTTP),
            mapOf("f" to FileDto("f", "test.bin", 3, "application/octet-stream",
                sendeeChecksum = Protocol.STREAMING_CHECKSUM))), null, "127.0.0.1", null)
        assertIs<PrepareUploadOutcome.Invalid>(result)
        assertNull(receiver.state.value.pendingReceive)
        assertEquals(0, commits)
    }

    @Test
    fun corruptPayloadIsAbortedAndRetryCanCommit() = runBlocking {
        val receiver = receiver()
        val content = ByteArray(4096) { it.toByte() }
        val session = prepare(receiver, content.size)
        val corrupt = content.copyOf().also { it[200] = (it[200].toInt() xor 1).toByte() }
        assertEquals(UploadOutcome.ChecksumMismatch, upload(receiver, session, corrupt + digest(content)))
        assertEquals(0, commits)
        assertEquals(1, aborts)
        assertEquals(UploadOutcome.Ok, upload(receiver, session, content + digest(content)))
        assertEquals(1, commits)
        assertContentEquals(content, saved)
        assertEquals(TransferStatus.COMPLETED, receiver.state.value.incoming?.status)
    }

    @Test
    fun checksumMismatchStopsAfterThreeAttempts() = runBlocking {
        val receiver = receiver()
        val content = byteArrayOf(1, 2, 3)
        val session = prepare(receiver, content.size)
        repeat(Protocol.MAX_UPLOAD_ATTEMPTS) {
            assertEquals(UploadOutcome.ChecksumMismatch, upload(receiver, session, content + ByteArray(32)))
        }
        assertEquals(0, commits)
        assertEquals(3, aborts)
        assertEquals(TransferStatus.FAILED, receiver.state.value.incoming?.status)
    }

    @Test
    fun truncatedPayloadOrTrailerAndExtraBytesNeverCommit() = runBlocking {
        val content = byteArrayOf(1, 2, 3)
        val valid = content + digest(content)
        for (invalid in listOf(content.copyOf(2), valid.copyOf(valid.size - 1), valid + byteArrayOf(0))) {
            val receiver = receiver()
            val session = prepare(receiver, content.size)
            assertIs<UploadOutcome.Failed>(upload(receiver, session, invalid))
            assertEquals(TransferStatus.FAILED, receiver.state.value.incoming?.status)
        }
        assertEquals(0, commits)
        assertEquals(3, aborts)
    }

    @Test
    fun emptyFileStillRequiresAndVerifiesTrailer() = runBlocking {
        val receiver = receiver()
        val session = prepare(receiver, 0)
        assertEquals(UploadOutcome.Ok, upload(receiver, session, digest(byteArrayOf())))
        assertContentEquals(byteArrayOf(), saved)
    }
}
