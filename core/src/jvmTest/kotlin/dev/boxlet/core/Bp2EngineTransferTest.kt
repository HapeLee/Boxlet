package dev.boxlet.core

import dev.boxlet.core.crypto.Certificates
import dev.boxlet.core.engine.*
import dev.boxlet.core.protocol.*
import dev.boxlet.core.protocol.bp2.Bp2
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import okio.Buffer
import okio.ForwardingSource
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.*

class Bp2EngineTransferTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val senderIdentity = Certificates.generate()
    private val receiverIdentity = Certificates.generate()
    private val engines = mutableListOf<TransferEngine>()
    private val saved = ConcurrentHashMap<String, ByteArray>()
    private val storage = object : ReceiveStorage {
        override suspend fun createDestination(sessionId: String, file: FileDto, senderAlias: String): ReceiveDestination {
            val buffer = Buffer()
            return object : ReceiveDestination {
                override val sink = buffer
                override val displayPath = "memory://${file.fileName}"
                override suspend fun commit() { saved[file.fileName] = buffer.readByteArray() }
                override suspend fun abort() { buffer.clear() }
            }
        }
    }
    @AfterTest fun cleanup() { engines.forEach { it.stop() }; scope.cancel() }
    private fun config(sender: Boolean, quick: Boolean) = EngineConfig(
        alias = if (sender) "Sender" else "Receiver", deviceModel = "Test", port = 0, quickSave = quick,
        fingerprint = if (sender) senderIdentity.fingerprint else receiverIdentity.fingerprint,
        bp2Enabled = true, bp2Port = 0,
    )
    private fun engine(sender: Boolean, quick: Boolean = true, configure: (EngineConfig) -> EngineConfig = { it }): TransferEngine {
        return TransferEngine(scope, if (sender) senderIdentity else receiverIdentity, storage).also {
            it.installConfig { configure(config(sender, quick)) }; it.start(); engines += it
        }
    }
    private fun connect(sender: TransferEngine, receiver: TransferEngine, native: Boolean = true) {
        sender.addKnownDevice(Device(receiverIdentity.fingerprint, "Receiver", channels =
            (if (native) listOf(DeviceChannel("127.0.0.1", receiver.bp2Port, true, "boxlet-bp2")) else emptyList()) +
                DeviceChannel("127.0.0.1", receiver.localInfo().port, true)))
    }
    private suspend fun terminal(engine: TransferEngine) = withTimeout(20000) {
        engine.state.first { it.outgoing?.status in setOf(TransferStatus.COMPLETED, TransferStatus.FAILED,
            TransferStatus.DECLINED, TransferStatus.CANCELLED, TransferStatus.BUSY) }.outgoing!!
    }
    private fun file(id: String, bytes: ByteArray, opens: AtomicInteger? = null) = OutgoingFile(id, "$id.bin",
        bytes.size.toLong(), "application/octet-stream", openSource = { opens?.incrementAndGet(); Buffer().write(bytes) })

    @Test fun actualSendEntryPrefersBp2AndPublishesMixedFilesOnce() = runBlocking {
        val receiver = engine(false); val sender = engine(true)
        connect(sender, receiver)
        val opens = AtomicInteger()
        val bytes = ByteArray(9 * Bp2.CHUNK_BYTES + 1) { (it * 13).toByte() }
        val files = listOf(file("large", bytes, opens), file("empty", ByteArray(0), opens)) +
            List(20) { file("small-$it", byteArrayOf(it.toByte()), opens) }
        sender.send(receiverIdentity.fingerprint, files) { null }
        val result = terminal(sender)
        assertEquals(TransferStatus.COMPLETED, result.status, result.error)
        assertEquals("boxlet-bp2", result.adapterId)
        assertTrue(result.files.all { it.status == FileTransferStatus.COMPLETED })
        assertEquals(files.size, opens.get())
        assertContentEquals(bytes, saved["large.bin"])
        assertEquals(files.size, saved.size)
        assertEquals("boxlet-bp2", receiver.state.value.incoming?.adapterId)
    }

    @Test fun unpairedPeersAreDiscoveredAndTransferOnlyAfterReceiveConfirmation() = runBlocking {
        val receiver = engine(false, quick = false)
        val sender = engine(true)
        sender.connectBp2("127.0.0.1", receiver.bp2Port, receiverIdentity.fingerprint)
        withTimeout(10000) { sender.state.first { state -> state.devices.any { device ->
            device.fingerprint.equals(receiverIdentity.fingerprint, true) &&
                device.channels.any { it.adapterId == Bp2.ADAPTER_ID && it.identityVerified }
        } } }
        val opens = AtomicInteger()
        val bytes = ByteArray(Bp2.CHUNK_BYTES + 7) { it.toByte() }
        sender.send(receiverIdentity.fingerprint, listOf(file("unpaired", bytes, opens))) { null }
        val pending = withTimeout(10000) { receiver.state.first { it.pendingReceive != null }.pendingReceive!! }
        assertEquals(0, opens.get(), "Discovery and waiting for consent must not read the source")
        assertTrue(saved.isEmpty())
        receiver.acceptReceive(pending.files.map { it.id }.toSet())
        val result = terminal(sender)
        assertEquals(TransferStatus.COMPLETED, result.status, result.error)
        assertEquals(Bp2.ADAPTER_ID, result.adapterId)
        assertEquals(1, opens.get())
        assertContentEquals(bytes, saved["unpaired.bin"])
    }

    @Test fun unpairedTransferCanBeRejectedWithoutReadingOrPublishingFiles() = runBlocking {
        val receiver = engine(false, quick = false)
        val sender = engine(true)
        connect(sender, receiver)
        val opens = AtomicInteger()
        sender.send(receiverIdentity.fingerprint, listOf(file("reject-unpaired", byteArrayOf(1), opens))) { null }
        withTimeout(10000) { receiver.state.first { it.pendingReceive != null } }
        receiver.rejectReceive()
        assertEquals(TransferStatus.DECLINED, terminal(sender).status)
        assertEquals(0, opens.get()); assertTrue(saved.isEmpty())
    }


    @Test fun certificateMustStillMatchTheSelectedEndpoint() = runBlocking {
        val receiver = engine(false)
        val sender = engine(true)
        val wrongFingerprint = "1".repeat(64)
        sender.addKnownDevice(Device(wrongFingerprint, "Wrong identity", channels = listOf(
            DeviceChannel("127.0.0.1", receiver.bp2Port, true, Bp2.ADAPTER_ID),
            DeviceChannel("127.0.0.1", receiver.localInfo().port, true))))
        val opens = AtomicInteger()
        sender.send(wrongFingerprint, listOf(file("wrong-pin", byteArrayOf(1), opens))) { null }
        val result = terminal(sender)
        assertEquals(TransferStatus.FAILED, result.status)
        assertEquals(Bp2.ADAPTER_ID, result.adapterId)
        assertEquals(0, opens.get()); assertTrue(saved.isEmpty()); assertNull(receiver.state.value.pendingReceive)
    }

    @Test fun favoriteDeviceCanAutoReceiveUsingItsCertificateIdentity() = runBlocking {
        val receiver = engine(false, quick = false) { settings -> settings.copy(
            quickSaveFromFavorites = true,
            autoAccept = { device -> device.fingerprint.equals(senderIdentity.fingerprint, true) },
        ) }
        val sender = engine(true)
        connect(sender, receiver)
        sender.send(receiverIdentity.fingerprint, listOf(file("favorite", byteArrayOf(2, 3)))) { null }
        val result = terminal(sender)
        assertEquals(TransferStatus.COMPLETED, result.status, result.error)
        assertEquals(Bp2.ADAPTER_ID, result.adapterId)
        assertContentEquals(byteArrayOf(2, 3), saved["favorite.bin"])
    }

    @Test fun nonFavoriteWithAFavoriteAliasStillRequiresConfirmation() = runBlocking {
        val favorite = Device("f".repeat(64), "Sender")
        val receiver = engine(false, quick = false) { settings -> settings.copy(
            quickSaveFromFavorites = true,
            autoAccept = { device -> device.fingerprint.equals(favorite.fingerprint, true) },
        ) }
        val sender = engine(true)
        connect(sender, receiver)
        val opens = AtomicInteger()
        sender.send(receiverIdentity.fingerprint, listOf(file("not-favorite", byteArrayOf(1), opens))) { null }
        val pending = withTimeout(10000) { receiver.state.first { it.pendingReceive != null }.pendingReceive!! }
        assertEquals(favorite.alias, pending.peer.alias)
        assertEquals(0, opens.get()); assertTrue(saved.isEmpty())
        receiver.rejectReceive()
        assertEquals(TransferStatus.DECLINED, terminal(sender).status)
    }

    @Test fun rejectAndCancelUseExistingReceiveActionsWithoutCreatingFiles() = runBlocking {
        val receiver = engine(false, quick = false); val sender = engine(true)
        connect(sender, receiver)
        sender.send(receiverIdentity.fingerprint, listOf(file("no", byteArrayOf(1)))) { null }
        withTimeout(10000) { receiver.state.first { it.pendingReceive != null } }
        receiver.rejectReceive()
        assertEquals(TransferStatus.DECLINED, terminal(sender).status)
        assertTrue(saved.isEmpty())
    }

    @Test fun localSendCancelCannotCancelAnAuthenticatedBp2Transfer() = runBlocking {
        val receiver = engine(false, quick = false); val sender = engine(true)
        connect(sender, receiver)
        sender.send(receiverIdentity.fingerprint, listOf(file("isolated", byteArrayOf(6)))) { null }
        withTimeout(10000) { receiver.state.first { it.pendingReceive != null } }
        val before = sender.state.value.outgoing!!
        sender.onCancel(before.sessionId, "127.0.0.1")
        assertEquals(before.status, sender.state.value.outgoing?.status)
        receiver.acceptReceive(receiver.state.value.pendingReceive!!.files.map { it.id }.toSet())
        assertEquals(TransferStatus.COMPLETED, terminal(sender).status)
        assertContentEquals(byteArrayOf(6), saved["isolated.bin"])
    }

    @Test fun localSendOnlyEndpointStillUsesStandardCompatibilityProtocol() = runBlocking {
        val receiver = engine(false); val sender = engine(true)
        connect(sender, receiver, native = false)
        sender.send(receiverIdentity.fingerprint, listOf(file("legacy", byteArrayOf(4)))) { null }
        val result = terminal(sender)
        assertEquals(TransferStatus.COMPLETED, result.status, result.error)
        assertEquals("localsend-v2", result.adapterId)
        assertNull(receiver.localInfo().sendeeCapabilities)
        assertContentEquals(byteArrayOf(4), saved["legacy.bin"])
    }


    @Test fun existingReceiveDialogSupportsSelectiveBp2Acceptance() = runBlocking {
        val receiver = engine(false, quick = false); val sender = engine(true)
        connect(sender, receiver)
        val opens = AtomicInteger()
        sender.send(receiverIdentity.fingerprint, listOf(file("keep", byteArrayOf(1), opens), file("skip", byteArrayOf(2), opens))) { null }
        val pending = withTimeout(10000) { receiver.state.first { it.pendingReceive != null }.pendingReceive!! }
        receiver.acceptReceive(setOf(pending.files.single { it.fileName == "keep.bin" }.id))
        val result = terminal(sender)
        assertEquals(TransferStatus.COMPLETED, result.status, result.error)
        assertEquals(FileTransferStatus.SKIPPED, result.files.single { it.fileName == "skip.bin" }.status)
        assertEquals(1, opens.get()); assertEquals(setOf("keep.bin"), saved.keys)
        assertContentEquals(byteArrayOf(1), saved["keep.bin"])
    }

    @Test fun standardLocalSendDefaultDoesNotNegotiatePrivateTrailerWithOlderBoxlet() = runBlocking {
        val receiver = TransferEngine(scope, receiverIdentity, storage).also {
            it.installConfig { config(false, true).copy(bp2Enabled = false, legacyStreamingChecksumEnabled = true) }; it.start(); engines += it
        }
        val sender = engine(true) { it.copy(bp2Enabled = false) }
        connect(sender, receiver, native = false)
        assertEquals(0, sender.bp2Port)
        assertTrue(Protocol.STREAMING_CHECKSUM in receiver.localInfo().sendeeCapabilities.orEmpty())
        val opens = AtomicInteger()
        sender.send(receiverIdentity.fingerprint, listOf(file("standard", byteArrayOf(8, 9), opens))) { null }
        val result = terminal(sender)
        assertEquals(TransferStatus.COMPLETED, result.status, result.error)
        assertEquals("localsend-v2", result.adapterId)
        assertEquals(2, opens.get(), "Standard LocalSend prehashes rather than sending a private checksum trailer")
        assertContentEquals(byteArrayOf(8, 9), saved["standard.bin"])
    }

    @Test fun cancellingPendingReceiveProducesCancelledInsteadOfDeclined() = runBlocking {
        val receiver = engine(false, quick = false); val sender = engine(true)
        connect(sender, receiver)
        sender.send(receiverIdentity.fingerprint, listOf(file("cancel", byteArrayOf(1)))) { null }
        withTimeout(10000) { receiver.state.first { it.pendingReceive != null } }
        receiver.cancelReceive()
        assertEquals(TransferStatus.CANCELLED, terminal(sender).status)
        assertTrue(saved.isEmpty())
    }

    @Test fun messagesPublishVerifiedBytesRatherThanTrustingThePreview() = runBlocking {
        val receiver = engine(false); val sender = engine(true)
        connect(sender, receiver)
        val actual = "BP/2 实际消息正文"
        val bytes = actual.encodeToByteArray()
        val completion = async(start = CoroutineStart.UNDISPATCHED) { receiver.completions.first() }
        sender.send(receiverIdentity.fingerprint, listOf(OutgoingFile("text", "message.txt", bytes.size.toLong(),
            "text/plain", preview = "untrusted preview", openSource = { Buffer().write(bytes) }))) { null }
        assertEquals(TransferStatus.COMPLETED, terminal(sender).status)
        val received = withTimeout(5000) { completion.await() }
        assertEquals(actual, received.message)
        assertEquals("boxlet-bp2", received.adapterId)
        assertTrue(saved.isEmpty(), "Text must not silently become a disk file")
    }

    @Test fun enginePauseKeepsBothTaskStatesAndContinueCompletesTheSameSource() = runBlocking {
        val receiver = engine(false); val sender = engine(true)
        connect(sender, receiver)
        val bytes = ByteArray(5 * Bp2.CHUNK_BYTES) { 7 }
        val opens = AtomicInteger()
        val file = OutgoingFile("pause", "pause.bin", bytes.size.toLong(), "application/octet-stream", openSource = {
            opens.incrementAndGet()
            object : ForwardingSource(Buffer().write(bytes)) {
                override fun read(sink: Buffer, byteCount: Long): Long { Thread.sleep(1); return super.read(sink, byteCount) }
            }
        })
        sender.send(receiverIdentity.fingerprint, listOf(file)) { null }
        withTimeout(10000) { sender.state.first { it.outgoing?.status == TransferStatus.TRANSFERRING } }
        assertTrue(sender.setTransferPaused(true, true))
        withTimeout(10000) { sender.state.first { it.outgoing?.status == TransferStatus.PAUSED } }
        withTimeout(10000) { receiver.state.first { it.incoming?.status == TransferStatus.PAUSED } }
        delay(300)
        assertTrue(saved.isEmpty())
        assertTrue(receiver.setTransferPaused(false, false))
        assertEquals(TransferStatus.COMPLETED, terminal(sender).status)
        assertEquals(1, opens.get())
        assertContentEquals(bytes, saved["pause.bin"])
    }
}
