package dev.boxlet.core

import dev.boxlet.core.crypto.Certificates
import dev.boxlet.core.crypto.SecurityContext
import dev.boxlet.core.engine.*
import dev.boxlet.core.protocol.*
import dev.boxlet.core.protocol.bp2.Bp2
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.*
import okio.ForwardingSource
import okio.ForwardingSink
import okio.buffer
import okio.sink
import okio.source
import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.Locale
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicBoolean

/** Manual same-storage comparison; this is not an official-client or wireless benchmark. */
object Bp2ProtocolBenchmark {
    private data class Dataset(val name: String, val files: List<File>) {
        val bytes = files.sumOf { it.length() }
        val hashes = files.associate { it.name to hash(it) }
    }
    private data class Sample(val dataset: String, val protocol: String, val round: Int,
                              val bytes: Long, val seconds: Double, val opens: Long, val readBytes: Long) {
        val rate get() = bytes / 1048576.0 / seconds
        fun json() = buildJsonObject {
            put("dataset", dataset); put("protocol", protocol); put("round", round); put("bytes", bytes)
            put("seconds", seconds); put("MiBps", rate); put("sourceOpens", opens); put("sourceReadBytes", readBytes)
        }
    }

    @JvmStatic fun main(args: Array<String>) = runBlocking {
        val workspace = Files.createTempDirectory("boxlet-protocol-benchmark-").toFile()
        val report = File(args.single())
        try {
            fun dataset(name: String, count: Int, size: Int): Dataset {
                val directory = File(workspace, name).apply { mkdirs() }
                return Dataset(name, List(count) { index ->
                    File(directory, "item-$index.bin").also { file ->
                        val chunk = ByteArray(minOf(size, Bp2.CHUNK_BYTES)) { (it * 31 + index).toByte() }
                        file.outputStream().buffered().use { output ->
                            var remaining = size
                            while (remaining > 0) {
                                val length = minOf(chunk.size, remaining); output.write(chunk, 0, length); remaining -= length
                            }
                        }
                    }
                })
            }
            val large = dataset("large-64MiB", 1, 64 * Bp2.CHUNK_BYTES)
            val small = dataset("small-128x4KiB", 128, 4096)
            val mixedLarge = dataset("mixed-large", 4, 4 * Bp2.CHUNK_BYTES)
            val mixedSmall = dataset("mixed-small", 64, 4096)
            // Unique names across subgroups, with the same manifest used by both adapters.
            mixedSmall.files.forEach { file -> check(file.renameTo(File(file.parentFile, "tiny-${file.name}"))) }
            val mixed = Dataset("mixed-4x4MiB+64x4KiB", mixedLarge.files + mixedSmall.files.map { File(it.parentFile, "tiny-${it.name}") })
            val sender = Certificates.generate(); val receiver = Certificates.generate()
            val samples = mutableListOf<Sample>()
            for (data in listOf(large, small, mixed)) {
                // Warm each path with the exact workload, then alternate order to limit cache/JIT bias.
                for (native in listOf(true, false)) measure(workspace, data, native, -1, sender, receiver)
                repeat(3) { round ->
                    for (native in if (round % 2 == 0) listOf(true, false) else listOf(false, true)) {
                        val sample = measure(workspace, data, native, round, sender, receiver)
                        samples += sample
                        println("[protocol] ${sample.dataset} ${sample.protocol} #${round + 1}: ${"%.2f".format(Locale.ROOT, sample.rate)} MiB/s, ${sample.opens} source opens")
                    }
                }
            }
            val document = buildJsonObject {
                put("environment", "${System.getProperty("os.name")} ${System.getProperty("os.arch")}, Java ${System.getProperty("java.version")}; same JVM loopback, shared CPU and filesystem cache")
                put("comparison", "BP/2 versus Boxlet's standard LocalSend v2.2 adapter; not the official LocalSend app")
                put("transport", "TCP/TLS for both; fresh engines per sample, authenticated peer configured before timing")
                put("storage", "identical buffered file sink, fd.sync per file, atomic rename; no resume journals or history for either adapter")
                put("timing", "send action through completed result; includes source hashing, TLS/prepare and publication; excludes setup and independent SHA-256 verification")
                put("warmup", "one unmeasured run of each exact workload per protocol; 3 measured runs, alternating order")
                put("limitation", "warm cache, local disk and shared CPU; does not predict Android, wireless or default resumable-storage performance")
                put("highVariance", samples.groupBy { it.dataset to it.protocol }.values.any { values ->
                    values.maxOf { it.rate } / values.minOf { it.rate } > 1.5
                })
                put("samples", JsonArray(samples.map { it.json() }))
                putJsonArray("medians") {
                    samples.groupBy { it.dataset to it.protocol }.forEach { (key, values) -> add(buildJsonObject {
                        put("dataset", key.first); put("protocol", key.second)
                        put("MiBps", values.map { it.rate }.sorted()[values.size / 2])
                        put("minMiBps", values.minOf { it.rate }); put("maxMiBps", values.maxOf { it.rate })
                    }) }
                }
            }
            report.parentFile.mkdirs(); report.writeText(document.toString())
            println("[protocol] Report: ${report.absolutePath}")
        } finally { workspace.deleteRecursively() }
    }

    private suspend fun measure(root: File, data: Dataset, native: Boolean, round: Int,
                                senderIdentity: SecurityContext, receiverIdentity: SecurityContext): Sample {
        val directory = Files.createTempDirectory(root.toPath(), "receive-").toFile()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val storage = object : ReceiveStorage {
            override suspend fun createDestination(sessionId: String, file: FileDto, senderAlias: String): ReceiveDestination {
                val target = File(directory, file.fileName)
                val part = File(directory, "${file.id}.part")
                val output = FileOutputStream(part)
                val synced = AtomicBoolean()
                val closed = AtomicBoolean()
                val sink = object : ForwardingSink(output.sink()) {
                    override fun close() {
                        if (!closed.compareAndSet(false, true)) return
                        try { flush(); output.fd.sync(); synced.set(true) } finally { super.close() }
                    }
                }.buffer()
                return object : ReceiveDestination {
                    override val sink = sink
                    override val displayPath = target.absolutePath
                    override suspend fun commit() {
                        check(synced.get()) { "File must be synced before publication" }
                        Files.move(part.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE)
                    }
                    override suspend fun abort() { runCatching { sink.close() }; part.delete() }
                }
            }
        }
        fun config(identity: SecurityContext, other: SecurityContext) = EngineConfig(
            alias = "Benchmark", deviceModel = "JVM benchmark", fingerprint = identity.fingerprint, port = 0, protocolType = ProtocolType.HTTPS,
            bp2Enabled = true, bp2Port = 0, bp2TrustedPeers = setOf(other.fingerprint),
            createChecksums = true, verifyChecksums = true, quickSave = true,
        )
        val receiver = TransferEngine(scope, receiverIdentity, storage)
        val sender = TransferEngine(scope, senderIdentity, storage)
        try {
            receiver.installConfig { config(receiverIdentity, senderIdentity) }; receiver.start()
            sender.installConfig { config(senderIdentity, receiverIdentity) }; sender.start()
            check(receiver.localInfo().sendeeCapabilities == null) { "Comparison requires standard LocalSend, without private extensions" }
            sender.addKnownDevice(Device(receiverIdentity.fingerprint, "Benchmark receiver", channels = listOf(
                DeviceChannel("127.0.0.1", if (native) receiver.bp2Port else receiver.localInfo().port,
                    https = true, adapterId = if (native) "boxlet-bp2" else "localsend-v2"))))
            val opens = AtomicLong(); val read = AtomicLong()
            val files = data.files.mapIndexed { index, source -> OutgoingFile(
                id = index.toString(), fileName = source.name, size = source.length(), mimeType = "application/octet-stream",
                openSource = {
                    opens.incrementAndGet()
                    object : ForwardingSource(source.source()) {
                        override fun read(sink: okio.Buffer, byteCount: Long): Long = super.read(sink, byteCount).also {
                            if (it > 0) read.addAndGet(it)
                        }
                    }
                }) }
            val start = System.nanoTime()
            sender.send(receiverIdentity.fingerprint, files) { null }
            val completed = withTimeout(180000) { sender.state.first {
                it.outgoing?.status in setOf(TransferStatus.COMPLETED, TransferStatus.FAILED, TransferStatus.DECLINED, TransferStatus.CANCELLED)
            }.outgoing!! }
            val seconds = (System.nanoTime() - start) / 1_000_000_000.0
            check(completed.status == TransferStatus.COMPLETED) { "Benchmark ${data.name} native=$native round=$round failed: ${completed.error}" }
            check(completed.adapterId == if (native) "boxlet-bp2" else "localsend-v2")
            check(opens.get() == data.files.size.toLong() * if (native) 1 else 2) { "Unexpected source reopening" }
            check(read.get() == data.bytes * if (native) 1 else 2) { "Unexpected source read volume" }
            check(directory.listFiles()!!.size == data.files.size)
            data.hashes.forEach { (name, expected) -> check(hash(File(directory, name)) == expected) { "SHA-256 mismatch: $name" } }
            return Sample(data.name, if (native) "BP/2" else "LocalSend v2.2", round, data.bytes, seconds, opens.get(), read.get())
        } finally {
            sender.stop(); receiver.stop(); scope.cancel()
            withContext(NonCancellable) { withTimeout(5000) { scope.coroutineContext.job.join() } }
            directory.deleteRecursively()
        }
    }

    private fun hash(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().buffered().use { source ->
            val bytes = ByteArray(Bp2.CHUNK_BYTES)
            while (true) { val count = source.read(bytes); if (count < 0) break; digest.update(bytes, 0, count) }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}
