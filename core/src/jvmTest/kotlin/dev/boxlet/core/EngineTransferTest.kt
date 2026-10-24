package dev.boxlet.core

import dev.boxlet.core.crypto.Certificates
import dev.boxlet.core.engine.EngineConfig
import dev.boxlet.core.engine.OutgoingFile
import dev.boxlet.core.engine.ReceiveDestination
import dev.boxlet.core.engine.ReceiveStorage
import dev.boxlet.core.engine.TransferEngine
import dev.boxlet.core.engine.TransferStatus
import dev.boxlet.core.protocol.Device
import dev.boxlet.core.protocol.DeviceChannel
import dev.boxlet.core.protocol.DeviceType
import dev.boxlet.core.protocol.FileDto
import dev.boxlet.core.protocol.RegisterRequest
import dev.boxlet.core.protocol.PrepareUploadRequest
import dev.boxlet.core.net.PrepareUploadOutcome
import dev.boxlet.core.protocol.ProtocolType
import dev.boxlet.core.protocol.Protocol
import dev.boxlet.core.net.LocalSendApiServer
import dev.boxlet.core.net.LocalSendHandler
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okio.Buffer
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNull
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

class EngineTransferTest {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val senderSecurity = Certificates.generate()
    private val receiverSecurity = Certificates.generate()

    @AfterTest
    fun tearDown() {
        scope.cancel()
    }

    @Test
    fun sendsFileOverMutualTls() = runBlocking { transfer(https = true) }

    @Test
    fun sendsFileOverPlainHttp() = runBlocking { transfer(https = false) }

    @Test
    fun fallsBackToPrehashForLegacyReceiver() = runBlocking { transfer(https = true, legacy = true) }

    @Test
    fun sendsSmallFilesOnFourWorkersBeforeLargeFiles() = runBlocking { transfer(https = true, manyFiles = true) }

    @Test fun quickSendUsesStandardLocalSendAndDoesNotReadSourcesBeforeAcceptance() = runBlocking {
        val received = ConcurrentHashMap<String, ByteArray>()
        val sourceOpens = AtomicInteger()
        val contents = linkedMapOf("large.bin" to ByteArray(2 * 1024 * 1024 + 7) { (it * 17).toByte() },
            "small.txt" to "quick send 中文".encodeToByteArray(), "empty.bin" to ByteArray(0))
        val storage = object : ReceiveStorage {
            override suspend fun createDestination(sessionId: String, file: FileDto, senderAlias: String): ReceiveDestination {
                val bytes = Buffer()
                return object : ReceiveDestination {
                    override val sink = bytes
                    override val displayPath = "memory://${file.fileName}"
                    override suspend fun commit() { received[file.fileName] = bytes.readByteArray() }
                    override suspend fun abort() { bytes.clear() }
                }
            }
        }
        val receiver = TransferEngine(scope, receiverSecurity, storage)
        val sender = TransferEngine(scope, senderSecurity, storage)
        try {
            receiver.installConfig { baseConfig(receiverSecurity.fingerprint, "Receiver", true).copy(legacyStreamingChecksumEnabled = false) }
            sender.installConfig { baseConfig(senderSecurity.fingerprint, "Sender", true).copy(legacyStreamingChecksumEnabled = false, createChecksums = false) }
            receiver.start(); sender.start()
            assertNull(receiver.localInfo().sendeeCapabilities)
            sender.addKnownDevice(Device(receiverSecurity.fingerprint, "Receiver", channels =
                listOf(DeviceChannel("127.0.0.1", receiver.localInfo().port, true))))
            sender.send(receiverSecurity.fingerprint, contents.map { (name, bytes) -> OutgoingFile(
                name, name, bytes.size.toLong(), "application/octet-stream",
                openSource = { sourceOpens.incrementAndGet(); Buffer().write(bytes) }) }) { null }
            val pending = withTimeout(10000) { receiver.state.first { it.pendingReceive != null }.pendingReceive!! }
            assertEquals(0, sourceOpens.get(), "Quick send must offer metadata before opening any file content")
            pending.files.forEach { assertNull(it.sha256); assertNull(it.sendeeChecksum) }
            receiver.acceptReceive(pending.files.map { it.id }.toSet())
            val done = withTimeout(10000) { sender.state.first {
                it.outgoing?.status in listOf(TransferStatus.COMPLETED, TransferStatus.FAILED)
            }.outgoing!! }
            assertEquals(TransferStatus.COMPLETED, done.status, done.error)
            assertEquals("localsend-v2", done.adapterId)
            withTimeout(10000) { receiver.state.first { it.incoming?.status == TransferStatus.COMPLETED } }
            assertEquals(contents.size, sourceOpens.get(), "Each accepted source should be opened only for its upload")
            contents.forEach { (name, bytes) -> assertContentEquals(bytes, received[name]) }
        } finally { sender.stop(); receiver.stop() }
    }

    @Test fun cancellationRequiresTheSendingIpAndTheCurrentSession() = runBlocking {
        val storage = object : ReceiveStorage {
            override suspend fun createDestination(sessionId: String, file: FileDto, senderAlias: String): ReceiveDestination = error("No upload expected")
        }
        val receiver = TransferEngine(scope, receiverSecurity, storage)
        receiver.installConfig { baseConfig(receiverSecurity.fingerprint, "Receiver", false).copy(quickSave = true) }
        try {
            val accepted = receiver.onPrepareUpload(PrepareUploadRequest(
                RegisterRequest("Sender", fingerprint = senderSecurity.fingerprint, protocol = ProtocolType.HTTP),
                mapOf("one" to FileDto("one", "one.bin", 1, "application/octet-stream"))), null, "10.0.0.2", null) as PrepareUploadOutcome.Accepted
            receiver.onCancel(null, "10.0.0.3")
            receiver.onCancel(accepted.sessionId, "10.0.0.3")
            receiver.onCancel("previous-session", "10.0.0.2")
            assertEquals(TransferStatus.TRANSFERRING, receiver.state.value.incoming?.status)
            receiver.onCancel(accepted.sessionId, "10.0.0.2")
            assertEquals(TransferStatus.CANCELLED, receiver.state.value.incoming?.status)
        } finally { receiver.stop() }
    }

    @Test fun cancellingPendingPreparationReleasesTheReceiverAndAllowsTheNextSend() = runBlocking {
        val saved = ConcurrentHashMap<String, ByteArray>()
        val storage = object : ReceiveStorage {
            override suspend fun createDestination(sessionId: String, file: FileDto, senderAlias: String): ReceiveDestination {
                val bytes = Buffer()
                return object : ReceiveDestination {
                    override val sink = bytes
                    override val displayPath = "memory://${file.fileName}"
                    override suspend fun commit() { saved[file.fileName] = bytes.readByteArray() }
                    override suspend fun abort() { bytes.clear() }
                }
            }
        }
        val receiver = TransferEngine(scope, receiverSecurity, storage)
        val sender = TransferEngine(scope, senderSecurity, storage)
        try {
            receiver.installConfig { baseConfig(receiverSecurity.fingerprint, "Receiver", false).copy(bp2Enabled = true, bp2Port = 0) }; receiver.start()
            sender.installConfig { baseConfig(senderSecurity.fingerprint, "Sender", false).copy(bp2Enabled = true, bp2Port = 0) }; sender.start()
            sender.addKnownDevice(Device(receiverSecurity.fingerprint, "Receiver", channels = listOf(DeviceChannel("127.0.0.1", receiver.localInfo().port, false))))
            fun file(value: Int) = OutgoingFile("same-id", "next.bin", 1, "application/octet-stream", openSource = { Buffer().writeByte(value) })
            sender.send(receiverSecurity.fingerprint, listOf(file(1))) { null }
            val first = withTimeout(10000) { receiver.state.first { it.pendingReceive != null }.pendingReceive!! }
            sender.cancelSend()
            withTimeout(5000) { receiver.state.first { it.incoming?.sessionId == first.sessionId && it.incoming.status == TransferStatus.CANCELLED } }
            assertEquals(TransferStatus.CANCELLED, sender.state.value.outgoing?.status)
            sender.send(receiverSecurity.fingerprint, listOf(file(2))) { null }
            withTimeout(10000) { receiver.state.first { it.pendingReceive?.sessionId?.let { id -> id != first.sessionId } == true } }
            receiver.acceptReceive(receiver.state.value.pendingReceive!!.files.map { it.id }.toSet())
            val done = withTimeout(10000) { sender.state.first { it.outgoing?.status in listOf(TransferStatus.COMPLETED, TransferStatus.FAILED) }.outgoing!! }
            assertEquals(TransferStatus.COMPLETED, done.status, done.error)
            assertContentEquals(byteArrayOf(2), saved["next.bin"])
        } finally { sender.stop(); receiver.stop() }
    }

    private suspend fun transfer(https: Boolean, legacy: Boolean = false, manyFiles: Boolean = false) {
        val received = ConcurrentHashMap<String, ByteArray>()
        val metadata = ConcurrentHashMap<String, FileDto>()
        val destinations = AtomicInteger()
        val fourUploads = CompletableDeferred<Unit>()
        val opens = AtomicInteger()
        val storage = object : ReceiveStorage {
            override suspend fun createDestination(
                sessionId: String,
                file: FileDto,
                senderAlias: String,
            ): ReceiveDestination {
                metadata[file.id] = file
                if (manyFiles) {
                    val order = destinations.incrementAndGet()
                    if (order <= 4) {
                        assertEquals(true, file.size <= Protocol.SMALL_FILE_BYTES, "small files must start first")
                        if (order == 4) fourUploads.complete(Unit)
                        withTimeout(5_000) { fourUploads.await() }
                    }
                }
                val buffer = Buffer()
                return object : ReceiveDestination {
                    override val sink = buffer
                    override val displayPath = "memory://${file.fileName}"
                    override suspend fun commit() {
                        received[file.fileName] = buffer.readByteArray()
                    }

                    override suspend fun abort() = Unit
                }
            }
        }

        val receiver = TransferEngine(scope, receiverSecurity, storage).apply {
            installConfig {
                baseConfig(receiverSecurity.fingerprint, "Receiver", https).copy(quickSave = true)
            }
            start()
        }
        // Hide the capability at the actual endpoint to emulate an unmodified LocalSend peer.
        var legacyPort = 0
        val legacyServer = if (legacy) LocalSendApiServer(object : LocalSendHandler by receiver {
            override fun localInfo() = receiver.localInfo().copy(sendeeCapabilities = null)
        }).newServer(receiverSecurity, 0, scope, https = https).also { legacyPort = it.start() } else null
        val receiverPort = if (legacy) legacyPort else receiver.localInfo().port

        val sender = TransferEngine(scope, senderSecurity, storage).apply {
            installConfig { baseConfig(senderSecurity.fingerprint, "Sender", https) }
            start()
        }
        sender.addKnownDevice(
            Device(
                fingerprint = receiverSecurity.fingerprint,
                alias = "Receiver",
                deviceType = DeviceType.DESKTOP,
                channels = listOf(DeviceChannel("127.0.0.1", receiverPort, https = https)),
            ),
        )

        val contents = if (manyFiles) listOf(ByteArray(2 * 1024 * 1024) { 9 }) +
            List(11) { index -> ByteArray(index * 1024) { (it * 31 + index).toByte() } }
            else listOf(ByteArray(256 * 1024) { (it * 31).toByte() })
        val files = contents.mapIndexed { index, content ->
            OutgoingFile(
                id = "file-$index", fileName = "payload-$index.bin", size = content.size.toLong(),
                mimeType = "application/octet-stream",
                openSource = { opens.incrementAndGet(); Buffer().write(content) },
            )
        }

        try {
            sender.send(receiverSecurity.fingerprint, files) { null }

            val outgoing = withTimeout(30_000) {
                sender.state.first {
                    it.outgoing?.status != null &&
                        it.outgoing.status != TransferStatus.PREPARING &&
                        it.outgoing.status != TransferStatus.TRANSFERRING &&
                        it.outgoing.status != TransferStatus.PIN_REQUIRED
                }
            }
            assertEquals(TransferStatus.COMPLETED, outgoing.outgoing?.status, outgoing.outgoing?.error)
            withTimeout(30_000) {
                receiver.state.first { it.incoming?.status == TransferStatus.COMPLETED }
            }

            files.forEachIndexed { index, file ->
                assertContentEquals(contents[index], received[file.fileName])
                val dto = metadata.getValue(file.id)
                assertEquals(if (legacy) null else Protocol.STREAMING_CHECKSUM, dto.sendeeChecksum)
                assertEquals(legacy, dto.sha256 != null)
            }
            // Streaming checksum opens every source once, including zero-length files.
            assertEquals(files.size * if (legacy) 2 else 1, opens.get())
        } finally {
            sender.stop()
            legacyServer?.stop()
            receiver.stop()
        }
    }

    private fun baseConfig(fingerprint: String, alias: String, https: Boolean) = EngineConfig(
        alias = alias,
        deviceModel = "JVM test",
        deviceType = DeviceType.DESKTOP,
        port = 0,
        protocolType = if (https) ProtocolType.HTTPS else ProtocolType.HTTP,
        fingerprint = fingerprint,
        createChecksums = true,
        verifyChecksums = true,
        legacyStreamingChecksumEnabled = true,
        quickSave = false,
        showToken = "test",
    )
}
