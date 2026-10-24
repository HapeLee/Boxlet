package dev.boxlet.core.session.bp2

import dev.boxlet.core.protocol.bp2.*
import kotlinx.serialization.Serializable
import okio.BufferedSource
import okio.Source
import okio.buffer
import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.io.EOFException
import java.io.IOException
import java.nio.channels.FileLock
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.concurrent.ConcurrentHashMap

/** Local checkpoint binding. The token must belong to the enclosing accepted transfer. */
@Serializable
data class Bp2CheckpointIdentity(val peerId: String, val transferId: String, val manifestHash: String, val fileId: String) {
    fun validate() {
        Bp2.requireFingerprint(peerId); Bp2.requireId(transferId)
        Bp2.requireFingerprint(manifestHash); Bp2.requireId(fileId)
    }
}

@Serializable
private data class CheckpointRecord(
    val version: Int = 1,
    val generation: Long,
    val identity: Bp2CheckpointIdentity,
    val entry: Bp2Entry,
    val tokenHash: String,
    val expiresAt: Long,
    val offset: String,
    val verifiedRoot: String? = null,
)

data class Bp2CheckpointPosition(val offset: Long, val prefixRoot: String, val verifiedRoot: String?)

/** A reopened source positioned after its verified prefix, with a full-file digest already seeded. */
class Bp2ResumedSource internal constructor(val source: BufferedSource, val content: Bp2ContentDigest) : AutoCloseable {
    override fun close() = source.close()
}

/**
 * Restart-safe staging primitive, not an advertised session resume service.
 * The caller supplies an application-private directory and the transfer's authenticated binding.
 * Data and the on-disk hash journal are forced before publishing a new metadata generation.
 * A crash can lose uncheckpointed progress; file length alone never advances the checkpoint.
 */
class Bp2CheckpointFile private constructor(
    private val directory: File,
    private var record: CheckpointRecord,
    private val lockFile: RandomAccessFile,
    private val lock: FileLock,
    private val data: RandomAccessFile,
    private val hashes: RandomAccessFile,
    private val clock: () -> Long,
) : AutoCloseable {
    val entry: Bp2Entry get() = record.entry
    private val size = Bp2.decimal(entry.size)
    private var content = Bp2ContentDigest(size)
    var written: Long = Bp2.decimal(record.offset)
        private set
    val durableOffset: Long get() = Bp2.decimal(record.offset)
    private var verifiedRoot = record.verifiedRoot
    private var closed = false
    private var failed = false

    companion object {
        private const val MAX_RECORD_BYTES = 16384L
        private val openWriters = ConcurrentHashMap.newKeySet<String>()
        const val LIFETIME_MS = 24L * 60 * 60 * 1000
        fun newToken(): String = Bp2Digests.hex(ByteArray(32).also { SecureRandom().nextBytes(it) })

        fun create(root: File, identity: Bp2CheckpointIdentity, entry: Bp2Entry, resumeToken: String,
                   clock: () -> Long = System::currentTimeMillis): Bp2CheckpointFile {
            identity.validate(); entry.validate(); Bp2.requireFingerprint(resumeToken)
            require(entry.fileId == identity.fileId && entry.kind == "file")
            val directory = slot(root, identity)
            val (guard, lock) = acquire(directory)
            try {
                require(records(directory).isEmpty() && !child(directory, "data.part").exists()) { "Checkpoint already exists" }
                val now = clock(); require(now >= 0 && now <= Long.MAX_VALUE - LIFETIME_MS)
                val record = CheckpointRecord(generation = 0, identity = identity, entry = entry.copy(streamId = null),
                    tokenHash = tokenHash(resumeToken), expiresAt = now + LIFETIME_MS, offset = "0")
                save(directory, record)
                return openFiles(directory, record, guard, lock, clock)
            } catch (error: Throwable) { release(directory, guard, lock); throw error }
        }

        fun reopen(root: File, identity: Bp2CheckpointIdentity, resumeToken: String,
                   clock: () -> Long = System::currentTimeMillis): Bp2CheckpointFile {
            identity.validate(); Bp2.requireFingerprint(resumeToken)
            val directory = slot(root, identity)
            val (guard, lock) = acquire(directory)
            var file: Bp2CheckpointFile? = null
            try {
                val latest = records(directory).maxByOrNull { generation(it) } ?: error("RESUME_EXPIRED")
                require(latest.length() in 2..MAX_RECORD_BYTES) { "Invalid checkpoint metadata size" }
                val record = Bp2.json.decodeFromString<CheckpointRecord>(latest.readText(Charsets.UTF_8))
                record.identity.validate(); record.entry.validate()
                require(record.version == 1 && record.generation == generation(latest))
                require(record.identity == identity && record.entry.fileId == identity.fileId && record.entry.kind == "file" && record.entry.streamId == null) { "RESUME_MISMATCH" }
                Bp2.requireFingerprint(record.tokenHash)
                require(MessageDigest.isEqual(Bp2Digests.unhex(record.tokenHash), Bp2Digests.unhex(tokenHash(resumeToken)))) { "AUTH_FAILED" }
                require(clock() in 0 until record.expiresAt) { "RESUME_EXPIRED" }
                val offset = Bp2.decimal(record.offset); val size = Bp2.decimal(record.entry.size)
                require(offset <= size && (offset % Bp2.CHUNK_BYTES == 0L || (offset == size && record.verifiedRoot != null)))
                record.verifiedRoot?.let { Bp2.requireFingerprint(it); require(offset == size) }
                file = openFiles(directory, record, guard, lock, clock)
                file.validateAndRestore()
                return file
            } catch (error: Throwable) {
                if (file != null) file.close() else release(directory, guard, lock)
                throw error
            }
        }

        private fun openFiles(directory: File, record: CheckpointRecord, guard: RandomAccessFile,
                              lock: FileLock, clock: () -> Long): Bp2CheckpointFile {
            val data = RandomAccessFile(child(directory, "data.part"), "rw")
            try {
                val hashes = RandomAccessFile(child(directory, "chunks.sha256"), "rw")
                return Bp2CheckpointFile(directory, record, guard, lock, data, hashes, clock)
            } catch (error: Throwable) { data.close(); throw error }
        }
        private fun tokenHash(token: String) = Bp2Digests.hex(Bp2Digests.sha256(Bp2Digests.unhex(token)))
        private fun slot(root: File, identity: Bp2CheckpointIdentity): File {
            require(root.mkdirs() || root.isDirectory)
            val directory = child(root.canonicalFile, "${identity.transferId}-${identity.fileId}")
            require(directory.mkdirs() || directory.isDirectory)
            return directory
        }
        private fun child(directory: File, name: String): File = File(directory, name).also {
            require(it.canonicalFile == it.absoluteFile) { "Checkpoint path is redirected" }
        }
        private fun acquire(directory: File): Pair<RandomAccessFile, FileLock> {
            val key = directory.absolutePath
            check(openWriters.add(key)) { "BUSY" }
            var file: RandomAccessFile? = null
            try {
                file = RandomAccessFile(child(directory, "writer.lock"), "rw")
                return file to (file.channel.tryLock() ?: error("BUSY"))
            } catch (error: Throwable) { runCatching { file?.close() }; openWriters.remove(key); throw error }
        }
        private fun release(directory: File, guard: RandomAccessFile, lock: FileLock) {
            try { runCatching { lock.release() }; guard.close() }
            finally { openWriters.remove(directory.absolutePath) }
        }
        private fun generation(file: File) = file.name.removeSuffix(".json").toLongOrNull() ?: -1L
        private fun records(directory: File): List<File> = directory.listFiles { file -> file.name.matches(Regex("[0-9]+\\.json")) }
            .orEmpty().toList().also { files ->
                require(files.size <= 8) { "Checkpoint generation limit exceeded" }
                files.forEach { require(child(directory, it.name).isFile) }
            }
        private fun save(directory: File, record: CheckpointRecord) {
            val bytes = Bp2.json.encodeToString(CheckpointRecord.serializer(), record).encodeToByteArray()
            require(bytes.size <= MAX_RECORD_BYTES)
            val pending = child(directory, "metadata.pending")
            FileOutputStream(pending).use { it.write(bytes); it.fd.sync() }
            val target = child(directory, "${record.generation}.json")
            require(!target.exists() && pending.renameTo(target)) { "Checkpoint publication failed" }
            // Keep the prior generation until the new one has been published; never delete first.
            records(directory).filter { generation(it) < record.generation - 1 }.forEach { require(it.delete()) }
        }
    }

    private fun usable() { check(!closed && !failed); require(clock() in 0 until record.expiresAt) { "RESUME_EXPIRED" } }
    private fun chunks(offset: Long) = if (offset == 0L) 0L else (offset - 1) / Bp2.CHUNK_BYTES + 1

    private fun validateAndRestore() {
        val offset = durableOffset
        require(data.length() >= offset && hashes.length() >= chunks(offset) * Bp2.HASH_BYTES) { "RESUME_MISMATCH" }
        data.seek(0); hashes.seek(0)
        var position = 0L
        while (position < offset) {
            val bytes = ByteArray(minOf(Bp2.CHUNK_BYTES.toLong(), offset - position).toInt())
            data.readFully(bytes)
            val expected = ByteArray(32); hashes.readFully(expected)
            require(MessageDigest.isEqual(expected, Bp2Digests.sha256(bytes))) { "RESUME_MISMATCH" }
            content.addChunkHash(position / Bp2.CHUNK_BYTES, expected)
            position += bytes.size
        }
        if (verifiedRoot != null) require(content.finish() == verifiedRoot) { "RESUME_MISMATCH" }
        // Any tail after the published generation was never a recovery promise.
        data.setLength(offset); hashes.setLength(chunks(offset) * Bp2.HASH_BYTES)
        data.seek(offset); hashes.seek(hashes.length())
    }

    /** A complete, verified chunk; cannot write gaps or append to a sealed staging file. */
    @Synchronized fun append(bytes: ByteArray, sha256: ByteArray) {
        usable(); check(verifiedRoot == null)
        require(bytes.isNotEmpty() && bytes.size.toLong() == minOf(Bp2.CHUNK_BYTES.toLong(), size - written))
        require(MessageDigest.isEqual(Bp2Digests.sha256(bytes), sha256)) { "CHECKSUM_MISMATCH" }
        try {
            data.seek(written); hashes.seek(chunks(written) * Bp2.HASH_BYTES)
            data.write(bytes); hashes.write(sha256)
            content.addChunkHash(written / Bp2.CHUNK_BYTES, sha256)
            written += bytes.size
        } catch (error: Throwable) { failed = true; throw error }
    }

    /** A short final chunk becomes recoverable only after the complete content root is verified. */
    @Synchronized fun seal(contentRoot: String) {
        usable(); Bp2.requireFingerprint(contentRoot)
        verifiedRoot?.let { require(it == contentRoot); return }
        require(written == size)
        try { require(content.finish() == contentRoot) { "CHECKSUM_MISMATCH" }; verifiedRoot = contentRoot }
        catch (error: Throwable) { failed = true; throw error }
        checkpoint()
    }

    @Synchronized fun checkpoint(): Long {
        usable()
        val offset = if (verifiedRoot != null) written else written - written % Bp2.CHUNK_BYTES
        require(record.generation < Long.MAX_VALUE)
        try {
            data.fd.sync(); hashes.fd.sync()
            val next = record.copy(generation = record.generation + 1, offset = offset.toString(), verifiedRoot = verifiedRoot)
            save(directory, next); record = next
            // Computing prefixRoot scans the journal because its domain includes the retained
            // chunk count. Do that on a resume query, not on every periodic checkpoint.
            return offset
        } catch (error: Throwable) { failed = true; throw error }
    }

    @Synchronized fun position(): Bp2CheckpointPosition {
        usable()
        val prefix = Bp2ContentDigest(size, chunks(durableOffset))
        try {
            hashes.seek(0)
            repeatHashes(chunks(durableOffset)) { index, hash -> prefix.addChunkHash(index, hash) }
        } finally { hashes.seek(chunks(written) * Bp2.HASH_BYTES) }
        return Bp2CheckpointPosition(durableOffset, prefix.finish(), verifiedRoot)
    }

    /** Only an authenticated transfer lease may reset an unpublished file. */
    @Synchronized internal fun reset() {
        usable(); require(record.generation < Long.MAX_VALUE)
        try {
            // Publish zero before truncation: restart must never revive the old prefix,
            // including a crash between the metadata publication and either truncation.
            val next = record.copy(generation = record.generation + 1, offset = "0", verifiedRoot = null)
            save(directory, next); record = next
            written = 0; verifiedRoot = null; content = Bp2ContentDigest(size)
            data.setLength(0); hashes.setLength(0); data.seek(0); hashes.seek(0)
            data.fd.sync(); hashes.fd.sync()
        } catch (error: Throwable) { failed = true; throw error }
    }

    /** Source is opened once; it remains at the verified offset for sending the remaining bytes. */
    @Synchronized fun verifySource(openSource: () -> Source): Bp2ResumedSource {
        usable()
        val source = openSource().buffer()
        try {
            val digest = Bp2ContentDigest(size)
            hashes.seek(0)
            var offset = 0L
            repeatHashes(chunks(durableOffset)) { index, expected ->
                val bytes = try { source.readByteArray(minOf(Bp2.CHUNK_BYTES.toLong(), durableOffset - offset)) }
                    catch (error: EOFException) { throw IOException("SOURCE_CHANGED", error) }
                require(MessageDigest.isEqual(Bp2Digests.sha256(bytes), expected)) { "SOURCE_CHANGED" }
                digest.addChunkHash(index, expected); offset += bytes.size
            }
            return Bp2ResumedSource(source, digest)
        } catch (error: Throwable) { source.close(); throw error }
        finally { hashes.seek(chunks(written) * Bp2.HASH_BYTES) }
    }
    private inline fun repeatHashes(count: Long, action: (Long, ByteArray) -> Unit) {
        var index = 0L
        while (index < count) { val hash = ByteArray(32); hashes.readFully(hash); action(index++, hash) }
    }

    /** Publish verified bytes exclusively; a hard link adds no copy and never replaces a target. */
    @Synchronized fun publishTo(target: File) {
        usable(); check(verifiedRoot != null)
        require(target.canonicalFile == target.absoluteFile && !target.exists()) { "COMMIT_FAILED" }
        data.fd.sync()
        java.nio.file.Files.createLink(target.toPath(), child(directory, "data.part").toPath())
        // The intent record is already durable. A crash before unlink is recovered as the same publication.
        data.close()
        require(child(directory, "data.part").delete())
    }

    /** Explicit cancellation/revocation removes recoverable data. Normal close preserves checkpoints. */
    @Synchronized fun discard() {
        check(!closed)
        // Keep the stable lock inode so a second writer cannot race deletion/recreation of it.
        failed = true
        data.close(); hashes.close()
        try {
            (records(directory) + child(directory, "data.part") + child(directory, "chunks.sha256") +
                child(directory, "metadata.pending")).forEach { if (it.exists()) require(it.delete()) }
        } finally { close() }
    }
    @Synchronized override fun close() {
        if (closed) return
        closed = true
        runCatching { data.close() }; runCatching { hashes.close() }
        release(directory, lockFile, lock)
    }
}
