package dev.boxlet.core

import dev.boxlet.core.protocol.bp2.*
import dev.boxlet.core.session.bp2.*
import okio.Buffer
import java.io.File
import java.io.RandomAccessFile
import java.nio.file.Files
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.TimeUnit
import kotlin.test.*

class Bp2CheckpointFileTest {
    private val root = Files.createTempDirectory("boxlet-checkpoint-test-").toFile()
    private val identity = Bp2CheckpointIdentity("1".repeat(64), "2".repeat(32), "3".repeat(64), "4".repeat(32))
    private val token = Bp2CheckpointFile.newToken()
    private val block = ByteArray(Bp2.CHUNK_BYTES) { (it * 19).toByte() }
    private var now = 1000L
    private val slot get() = File(root, "${identity.transferId}-${identity.fileId}")
    private fun entry(size: Long) = Bp2Entry(identity.fileId, path = "目录/file.bin", size = size.toString())
    private fun create(size: Long) = Bp2CheckpointFile.create(root, identity, entry(size), token) { now }
    private fun reopen() = Bp2CheckpointFile.reopen(root, identity, token) { now }
    private fun Bp2CheckpointFile.add(bytes: ByteArray) = append(bytes, Bp2Digests.sha256(bytes))
    @AfterTest fun cleanup() {
        check(root.name.startsWith("boxlet-checkpoint-test-") && root.canonicalFile.parentFile == File(System.getProperty("java.io.tmpdir")).canonicalFile)
        root.deleteRecursively()
    }

    @Test fun restartDiscardsUncheckpointedTailAndResumesOneSourceAtVerifiedPrefix() {
        val suffix = "剩余数据".encodeToByteArray()
        val all = block + block + suffix
        create(all.size.toLong()).use { file ->
            file.add(block); file.checkpoint(); file.add(block)
            assertEquals(Bp2.CHUNK_BYTES.toLong(), file.durableOffset)
        }
        reopen().use { file ->
            assertEquals(Bp2.CHUNK_BYTES.toLong(), file.written)
            assertEquals(Bp2.CHUNK_BYTES.toLong(), File(slot, "data.part").length())
            val expectedPrefix = Bp2ContentDigest(all.size.toLong(), 1).also { it.addChunkHash(0, Bp2Digests.sha256(block)) }.finish()
            assertEquals(expectedPrefix, file.position().prefixRoot)
            val opens = AtomicInteger()
            file.verifySource { opens.incrementAndGet(); Buffer().write(all) }.use { source ->
                var offset = file.written
                while (!source.source.exhausted()) {
                    val bytes = source.source.readByteArray(minOf(Bp2.CHUNK_BYTES.toLong(), all.size - offset))
                    source.content.addChunkHash(offset / Bp2.CHUNK_BYTES, Bp2Digests.sha256(bytes))
                    file.add(bytes); offset += bytes.size
                }
                file.seal(source.content.finish())
            }
            assertEquals(1, opens.get())
            assertEquals(all.size.toLong(), file.position().offset)
        }
        reopen().use { assertNotNull(it.position().verifiedRoot); assertEquals(all.size.toLong(), it.written) }
        assertContentEquals(all, File(slot, "data.part").readBytes())
    }

    @Test fun tokenIdentityManifestAndExpiryMustMatchAndPlainTokenNeverGoesToDisk() {
        create(block.size.toLong()).close()
        assertTrue(slot.listFiles().orEmpty().filter { it.extension == "json" }.none { token in it.readText() })
        assertFails { Bp2CheckpointFile.reopen(root, identity, "0".repeat(64)) { now } }
        assertFails { Bp2CheckpointFile.reopen(root, identity.copy(peerId = "5".repeat(64)), token) { now } }
        assertFails { Bp2CheckpointFile.reopen(root, identity.copy(manifestHash = "6".repeat(64)), token) { now } }
        now += Bp2CheckpointFile.LIFETIME_MS
        assertFailsWith<IllegalArgumentException> { reopen() }
    }

    @Test fun resetPublishesZeroBeforeTruncationAndCannotReviveTheOldTail() {
        create(2L * block.size).use { file ->
            file.add(block); file.checkpoint()
            file.reset()
            assertEquals(0L, file.durableOffset); assertEquals(0L, file.written)
            assertNull(file.position().verifiedRoot)
        }
        // Simulate stopping after zero metadata publication but before old tails are removed.
        File(slot, "data.part").writeBytes(block)
        File(slot, "chunks.sha256").writeBytes(Bp2Digests.sha256(block))
        reopen().use { file ->
            assertEquals(0L, file.durableOffset); assertEquals(0L, File(slot, "data.part").length())
            assertEquals(0L, File(slot, "chunks.sha256").length())
            val replacement = ByteArray(block.size) { 4 }
            file.add(replacement); file.checkpoint()
        }
        reopen().use { assertEquals(block.size.toLong(), it.durableOffset) }
        assertTrue(File(slot, "data.part").readBytes().all { it == 4.toByte() })
    }

    @Test fun journalAndDataAreRevalidatedAndCannotBeRecoveredFromLengthAlone() {
        for (name in listOf("data.part", "chunks.sha256")) {
            create(block.size.toLong()).use { it.add(block); it.checkpoint() }
            RandomAccessFile(File(slot, name), "rw").use { it.seek(0); it.writeByte(123) }
            assertFails { reopen() }
            // Isolated fixture cleanup; retain stable lock inode.
            slot.listFiles().orEmpty().filter { it.name != "writer.lock" }.forEach { assertTrue(it.delete()) }
        }
    }

    @Test fun incompleteLastChunkIsNotDurableUntilRootHasBeenVerified() {
        create(3).use {
            it.add("abc".encodeToByteArray())
            assertEquals(0L, it.checkpoint())
        }
        reopen().use {
            assertEquals(0L, it.written)
            assertEquals(0L, File(slot, "data.part").length())
            it.add("abc".encodeToByteArray())
            val root = Bp2ContentDigest(3).also { digest -> digest.addChunkHash(0, Bp2Digests.sha256("abc".encodeToByteArray())) }.finish()
            it.seal(root); it.seal(root)
            assertEquals(3L, it.position().offset)
        }
    }

    @Test fun changedPrefixIsRejectedAndASecondWriterCannotOpenTheSameCheckpoint() {
        create(2L * block.size).use { file ->
            file.add(block); file.checkpoint()
            assertFails { reopen() }
            val changed = block.copyOf().also { it[it.size / 2] = (it[it.size / 2] + 1).toByte() }
            assertFails { file.verifySource { Buffer().write(changed).write(block) } }
            // A failed comparison must not move the hash journal's append cursor.
            file.add(block); file.checkpoint()
        }
        reopen().use { assertEquals(2L * block.size, it.written) }
    }

    @Test fun cancellationDeletesRecoveryStateAndMetadataGenerationsStayBounded() {
        create(block.size.toLong()).use { file ->
            file.add(block)
            repeat(20) { file.checkpoint() }
            assertEquals(2, slot.listFiles().orEmpty().count { it.extension == "json" })
            file.discard()
        }
        assertFails { reopen() }
        assertFalse(File(slot, "data.part").exists())
    }

    @Test fun emptyFileRootCanBeSealedAndReopened() {
        create(0).use { it.seal(Bp2ContentDigest(0).finish()) }
        reopen().use { assertEquals(Bp2ContentDigest(0).finish(), it.position().verifiedRoot) }
    }

    @Test fun abruptProcessExitKeepsOnlyThePublishedGeneration() {
        val classes = listOf(Bp2CheckpointCrashProbe::class.java, Bp2CheckpointFile::class.java, kotlin.Unit::class.java,
            kotlinx.serialization.json.Json::class.java, kotlinx.serialization.KSerializer::class.java, Buffer::class.java)
        val classpath = classes.map { File(it.protectionDomain.codeSource.location.toURI()).absolutePath }.distinct().joinToString(File.pathSeparator)
        val java = File(System.getProperty("java.home"), "bin/java").absolutePath
        val process = ProcessBuilder(java, "-cp", classpath, Bp2CheckpointCrashProbe::class.java.name, root.absolutePath, token)
            .redirectErrorStream(true).start()
        try {
            assertTrue(process.waitFor(15, TimeUnit.SECONDS), "Checkpoint writer did not exit")
            assertEquals(0, process.exitValue(), process.inputStream.bufferedReader().readText())
        } finally { if (process.isAlive) process.destroyForcibly().waitFor(5, TimeUnit.SECONDS) }
        Bp2CheckpointFile.reopen(root, identity, token).use {
            assertEquals(Bp2.CHUNK_BYTES.toLong(), it.written)
            assertEquals(Bp2.CHUNK_BYTES.toLong(), File(slot, "data.part").length())
        }
    }
}

/** Test subprocess deliberately bypasses close/finally/shutdown hooks, like a killed engine. */
object Bp2CheckpointCrashProbe {
    @JvmStatic fun main(args: Array<String>) {
        val identity = Bp2CheckpointIdentity("1".repeat(64), "2".repeat(32), "3".repeat(64), "4".repeat(32))
        val file = Bp2CheckpointFile.create(File(args[0]), identity,
            Bp2Entry(identity.fileId, path = "file.bin", size = (2L * Bp2.CHUNK_BYTES).toString()), args[1])
        val bytes = ByteArray(Bp2.CHUNK_BYTES) { 7 }; val hash = Bp2Digests.sha256(bytes)
        file.append(bytes, hash); file.checkpoint(); file.append(bytes, hash)
        Runtime.getRuntime().halt(0)
    }
}
