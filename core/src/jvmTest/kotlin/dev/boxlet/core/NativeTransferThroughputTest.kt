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
import dev.boxlet.core.protocol.ProtocolType
import dev.boxlet.core.net.LocalSendApiServer
import dev.boxlet.core.net.LocalSendHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okio.buffer
import okio.sink
import okio.source
import org.junit.After
import org.junit.Test
import java.io.File
import java.nio.file.Files
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 原生互传（安卓 ↔ 桌面共用的一套引擎）的吞吐回归。
 *
 * 两个引擎跑在同一 JVM 里走回环，CPU 是共享的，数字比真机互传悲观；但相对变化可信，
 * 用来盯住预哈希、TLS、落盘这几条路径的退化。
 */
class NativeTransferThroughputTest {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val directory: File = Files.createTempDirectory("boxlet-native").toFile()
    private val senderSecurity = Certificates.generate()
    private val receiverSecurity = Certificates.generate()

    @After
    fun tearDown() {
        scope.cancel()
        directory.deleteRecursively()
    }

    private fun storage(dir: File): ReceiveStorage = object : ReceiveStorage {
        override suspend fun createDestination(
            sessionId: String,
            file: FileDto,
            senderAlias: String,
        ): ReceiveDestination {
            val target = File(dir, file.fileName)
            val sink = target.outputStream().buffered(1024 * 1024).sink().buffer()
            return object : ReceiveDestination {
                override val sink = sink
                override val displayPath: String = target.absolutePath
                override suspend fun commit() {
                    sink.close()
                }
                override suspend fun abort() {
                    runCatching { sink.close() }
                    runCatching { target.delete() }
                }
            }
        }
    }

    private fun config(fingerprint: String, alias: String, https: Boolean, checksums: Boolean) = EngineConfig(
        alias = alias,
        deviceModel = "JVM test",
        deviceType = DeviceType.DESKTOP,
        port = 0,
        protocolType = if (https) ProtocolType.HTTPS else ProtocolType.HTTP,
        fingerprint = fingerprint,
        createChecksums = checksums,
        verifyChecksums = checksums,
        quickSave = true,
        showToken = "test",
    )

    @Test
    fun transfersAtExpectedSpeed() = runBlocking {
        val total = 64L * 1024 * 1024
        val source = File(directory, "source.bin")
        source.outputStream().buffered(1024 * 1024).use { out ->
            val chunk = ByteArray(1024 * 1024) { 7 }
            repeat(64) { out.write(chunk) }
        }

        // 预热 JIT 与页缓存，避免第一轮数字失真
        transfer(source, total, checksums = false, tag = "warmup", expectMinMiB = 0)
        transfer(source, total, checksums = true, tag = "legacy-prehash", expectMinMiB = 150, legacy = true)
        repeat(2) { pass ->
            transfer(source, total, checksums = true, tag = "checksums#${pass + 1}", expectMinMiB = 150)
        }
        transfer(source, total, checksums = false, tag = "nochecksum", expectMinMiB = 200)
    }

    private suspend fun transfer(source: File, total: Long, checksums: Boolean, tag: String, expectMinMiB: Int, legacy: Boolean = false) {
        val receivedDir = File(directory, "recv-$tag-${System.nanoTime()}").apply { mkdirs() }
        val receiver = TransferEngine(scope, receiverSecurity, storage(receivedDir)).apply {
            installConfig { config(receiverSecurity.fingerprint, "Receiver", https = true, checksums = checksums) }
            start()
        }
        var port = receiver.localInfo().port
        val legacyServer = if (legacy) LocalSendApiServer(object : LocalSendHandler by receiver {
            override fun localInfo() = receiver.localInfo().copy(sendeeCapabilities = null)
        }).newServer(receiverSecurity, 0, scope).also { port = it.start() } else null
        val sender = TransferEngine(scope, senderSecurity, storage(receivedDir)).apply {
            installConfig { config(senderSecurity.fingerprint, "Sender", https = true, checksums = checksums) }
            start()
        }
        sender.addKnownDevice(
            Device(
                fingerprint = receiverSecurity.fingerprint,
                alias = "Receiver",
                deviceType = DeviceType.DESKTOP,
                channels = listOf(DeviceChannel("127.0.0.1", port, https = true)),
            ),
        )

        val file = OutgoingFile(
            id = "bench",
            fileName = "bench.bin",
            size = total,
            mimeType = "application/octet-stream",
            openSource = { source.source() },
        )
        val started = System.nanoTime()
        sender.send(receiverSecurity.fingerprint, listOf(file)) { null }
        val outgoing = withTimeout(180_000) {
            sender.state.first {
                it.outgoing?.status != null &&
                    it.outgoing.status != TransferStatus.PREPARING &&
                    it.outgoing.status != TransferStatus.TRANSFERRING &&
                    it.outgoing.status != TransferStatus.WAITING_FOR_ACCEPT &&
                    it.outgoing.status != TransferStatus.PIN_REQUIRED
            }
        }
        val seconds = (System.nanoTime() - started) / 1_000_000_000.0
        val rate = total / 1048576.0 / seconds
        println("[native] $tag checksums=$checksums ${total shr 20} MiB = ${"%.0f".format(rate)} MiB/s")
        assertEquals(TransferStatus.COMPLETED, outgoing.outgoing?.status, outgoing.outgoing?.error)
        if (expectMinMiB > 0) {
            assertTrue(rate >= expectMinMiB, "$tag regressed to ${"%.0f".format(rate)} MiB/s")
        }
        sender.stop()
        legacyServer?.stop()
        receiver.stop()
    }
}
