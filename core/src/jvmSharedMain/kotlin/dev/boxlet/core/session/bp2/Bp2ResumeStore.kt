package dev.boxlet.core.session.bp2

import dev.boxlet.core.protocol.bp2.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.serializer
import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.nio.channels.FileLock
import java.nio.file.Files
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap

@Serializable
internal data class ResumeRecord(val peer: String, val transferId: String, val manifestHash: String,
    val tokenHash: String, val expiresAt: Long, val count: Int, val targetRoot: String, val reservedBytes: String)
@Serializable
data class Bp2PublishedFile(val path: String, val contentRoot: String, val timestamp: Long)

/** Private metadata + directly published staging files. No extra whole-file copy is introduced. */
class Bp2ResumeStore(private val root: File, private val targetRoot: File,
    private val maxRetainedBytes: Long = 256L * 1024 * 1024 * 1024, private val clock: () -> Long = System::currentTimeMillis) {
    private val target = targetRoot.canonicalFile
    private val recordsRoot = File(root.canonicalFile, "transfers")
    internal val chunks = File(root.canonicalFile, "chunks")

    init {
        require(maxRetainedBytes >= 0)
        val privatePath = root.canonicalFile.toPath(); val targetPath = target.toPath()
        require(!privatePath.startsWith(targetPath) && !targetPath.startsWith(privatePath)) { "Private resume storage overlaps the receive directory" }
        require(recordsRoot.mkdirs() || recordsRoot.isDirectory); require(chunks.mkdirs() || chunks.isDirectory)
    }
    fun supports(entries: List<Bp2Entry>) = entries.isNotEmpty() && entries.size <= 100000 && entries.all { it.kind == "file" }

    /** Verify the filesystem supports exclusive, atomic publication without replacing existing files. */
    fun available(): Boolean {
        require(target.mkdirs() || target.isDirectory)
        val source = File.createTempFile("probe-", ".part", root)
        val destination = File(target, ".boxlet-probe-${newBp2Id()}")
        return try { Files.createLink(destination.toPath(), source.toPath()); true }
        catch (_: Exception) { false }
        finally { destination.delete(); source.delete() }
    }
    @Synchronized fun create(peer: String, id: String, manifestHash: String, entries: List<Bp2Entry>, selected: Set<String>): Bp2ResumeLease {
        Bp2.requireFingerprint(peer); Bp2.requireId(id); Bp2.requireFingerprint(manifestHash)
        require(supports(entries) && selected.isNotEmpty() && entries.map { it.fileId }.toSet().containsAll(selected))
        cleanup()
        var reserved = 0L; var active = 0
        transferDirectories().filter { !safe(it, "complete").exists() && !safe(it, "revoked").exists() }.forEach { directory ->
            active++
            runCatching { Bp2.json.decodeFromString<ResumeRecord>(read(safe(directory, "transfer.json"), 16384)) }.getOrNull()?.let {
                val n = Bp2.decimal(it.reservedBytes); require(reserved <= maxRetainedBytes - n) { "QUOTA_EXCEEDED" }; reserved += n
            }
        }
        require(active < 128) { "QUOTA_EXCEEDED" }
        val total = entries.filter { it.fileId in selected }.fold(0L) { sum, entry ->
            val n = Bp2.decimal(entry.size); require(sum <= maxRetainedBytes - n) { "QUOTA_EXCEEDED" }; sum + n
        }
        require(reserved <= maxRetainedBytes - total) { "QUOTA_EXCEEDED" }
        require(root.usableSpace >= total) { "NO_SPACE" }
        val directory = safe(recordsRoot, id)
        require(!directory.exists()) { "Duplicate retained transfer" }; require(directory.mkdir())
        val token = Bp2CheckpointFile.newToken()
        val record = ResumeRecord(peer, id, manifestHash, hashToken(token), now() + Bp2CheckpointFile.LIFETIME_MS,
            entries.size, target.path, total.toString())
        val lease = lock(directory)
        try {
            var metadataBytes = 0L
            entries.chunked(16).forEachIndexed { page, values ->
                val text = Bp2.json.encodeToString(kotlinx.serialization.builtins.ListSerializer(Bp2Entry.serializer()), values.map { it.copy(streamId = null) })
                metadataBytes += text.encodeToByteArray().size
                require(metadataBytes <= 64L * 1024 * 1024) { "QUOTA_EXCEEDED" }
                writeNew(safe(directory, "entries-$page.json"), text)
            }
            writeNew(safe(directory, "selected.json"), Bp2.json.encodeToString(kotlinx.serialization.builtins.SetSerializer(String.serializer()), selected))
            writeNew(safe(directory, "transfer.json"), Bp2.json.encodeToString(ResumeRecord.serializer(), record))
            return Bp2ResumeLease(this, directory, record, entries.map { it.copy(streamId = null) }, selected, token, lease.first, lease.second)
        } catch (error: Throwable) { release(directory, lease.first, lease.second); throw error }
    }
    @Synchronized fun resume(peer: String, ticket: Bp2ResumeTicket, forCancellation: Boolean = false): Bp2ResumeLease {
        Bp2.requireFingerprint(peer); Bp2.requireId(ticket.transferId); Bp2.requireFingerprint(ticket.manifestHash); Bp2.requireFingerprint(ticket.resumeToken)
        val directory = safe(recordsRoot, ticket.transferId)
        require(directory.isDirectory) { "RESUME_EXPIRED" }
        val lease = lock(directory)
        try {
            val record = authorizedRecord(peer, ticket, forCancellation)
            val entries = ArrayList<Bp2Entry>(record.count)
            var metadataBytes = 0L
            for (page in 0 until (record.count + 15) / 16) {
                val text = read(safe(directory, "entries-$page.json"), Bp2.CONTROL_BYTES)
                metadataBytes += text.encodeToByteArray().size; require(metadataBytes <= 64L * 1024 * 1024)
                val values = Bp2.json.decodeFromString<List<Bp2Entry>>(text)
                require(values.size in 1..16); values.forEach { it.validate(); require(it.kind == "file" && it.streamId == null) }
                entries += values
            }
            require(entries.size == record.count)
            val manifest = Bp2ManifestDigest(); entries.forEach(manifest::add); require(manifest.finish() == record.manifestHash)
            val selected = Bp2.json.decodeFromString<Set<String>>(read(safe(directory, "selected.json"), 4 * 1024 * 1024))
            require(selected.isNotEmpty() && entries.map { it.fileId }.toSet().containsAll(selected))
            return Bp2ResumeLease(this, directory, record, entries, selected, ticket.resumeToken, lease.first, lease.second)
        } catch (error: Throwable) { release(directory, lease.first, lease.second); throw error }
    }
    /** Authorize takeover without acquiring the old session's exclusive lease; resume rechecks. */
    @Synchronized fun validateTicket(peer: String, ticket: Bp2ResumeTicket, forCancellation: Boolean = false) {
        authorizedRecord(peer, ticket, forCancellation)
    }
    private fun authorizedRecord(peer: String, ticket: Bp2ResumeTicket, forCancellation: Boolean): ResumeRecord {
        Bp2.requireFingerprint(peer); Bp2.requireId(ticket.transferId); Bp2.requireFingerprint(ticket.manifestHash); Bp2.requireFingerprint(ticket.resumeToken)
        val directory = safe(recordsRoot, ticket.transferId)
        require(directory.isDirectory) { "RESUME_EXPIRED" }
        val record = Bp2.json.decodeFromString<ResumeRecord>(read(safe(directory, "transfer.json"), 16384))
        require(record.peer == peer && record.transferId == ticket.transferId && record.manifestHash == ticket.manifestHash &&
            MessageDigest.isEqual(Bp2Digests.unhex(record.tokenHash), Bp2Digests.unhex(hashToken(ticket.resumeToken)))) { "AUTH_FAILED" }
        require(forCancellation || (!safe(directory, "revoked").exists() && now() < record.expiresAt)) { "RESUME_EXPIRED" }
        require(record.targetRoot == target.path && record.count in 1..100000) { "RESUME_MISMATCH" }
        return record
    }
    private fun lock(directory: File): Pair<RandomAccessFile, FileLock> {
        check(leases.add(directory.path)) { "BUSY" }
        var file: RandomAccessFile? = null
        try { file = RandomAccessFile(safe(directory, "lease.lock"), "rw"); return file to (file.channel.tryLock() ?: error("BUSY")) }
        catch (error: Throwable) { file?.close(); leases.remove(directory.path); throw error }
    }
    internal fun release(directory: File, file: RandomAccessFile, lock: FileLock) {
        try { runCatching { lock.release() }; file.close() } finally { leases.remove(directory.path) }
    }
    internal fun destination(entry: Bp2Entry): File {
        entry.validate()
        require(target.mkdirs() || target.isDirectory)
        var directory = target
        val parts = entry.path.split('/').map(dev.boxlet.core.protocol.FileNames::sanitizeSegment)
        parts.dropLast(1).forEach { directory = safe(directory, it); require(directory.mkdirs() || directory.isDirectory) }
        val desired = parts.last()
        var result = safe(directory, desired)
        if (result.exists()) {
            val extension = desired.substringAfterLast('.', "").let { if (it.isNotEmpty()) ".$it" else "" }
            var base = if (extension.isEmpty()) desired else desired.dropLast(extension.length)
            val prefix = "${newBp2Id().take(8)}-"
            while ((prefix + base + extension).encodeToByteArray().size > 255 && base.isNotEmpty()) {
                base = base.dropLast(1)
                if (base.isNotEmpty() && Character.isHighSurrogate(base.last())) base = base.dropLast(1)
            }
            result = safe(directory, prefix + base + extension)
        }
        require(!result.exists()) { "COMMIT_FAILED" }
        return result
    }
    internal fun finalPath(relative: String): File {
        require(relative.isNotEmpty() && relative.split('/').all { it.isNotEmpty() && it != "." && it != ".." && '\\' !in it && ':' !in it })
        return relative.split('/').fold(target) { directory, part -> safe(directory, part) }
    }
    internal fun relative(file: File) = file.relativeTo(target).invariantSeparatorsPath
    internal fun now() = clock()
    @Synchronized fun cancelLocal(peer: String, id: String) {
        Bp2.requireFingerprint(peer); Bp2.requireId(id)
        val directory = safe(recordsRoot, id); val lease = lock(directory)
        try {
            val record = Bp2.json.decodeFromString<ResumeRecord>(read(safe(directory, "transfer.json"), 16384))
            require(record.peer == peer && record.transferId == id) { "AUTH_FAILED" }
            safe(directory, "revoked").createNewFile()
        } finally { release(directory, lease.first, lease.second) }
        cleanup()
    }
    private fun transferDirectories() = recordsRoot.listFiles().orEmpty().filter { it.isDirectory && it.name.matches(Regex("[0-9a-f]{32}")) }
    /** Mark revocation first; a live lease prevents deleting its files until it has closed. */
    @Synchronized fun cleanup() {
        transferDirectories().forEach { directory ->
            runCatching {
                val record = runCatching { Bp2.json.decodeFromString<ResumeRecord>(read(safe(directory, "transfer.json"), 16384)) }.getOrNull()
                val expired = record?.let { now() >= it.expiresAt } ?: (now() - directory.lastModified() >= Bp2CheckpointFile.LIFETIME_MS)
                if (expired) safe(directory, "revoked").createNewFile()
                if (!safe(directory, "revoked").exists()) return@runCatching
                val lease = lock(directory)
                try {
                    chunks.listFiles().orEmpty().filter { it.name.startsWith("${directory.name}-") && it.name.matches(Regex("[0-9a-f]{32}-[0-9a-f]{32}")) }
                        .forEach { removeOwned(it) }
                    directory.listFiles().orEmpty().filter { it.name != "lease.lock" }.forEach { removeOwned(it) }
                } finally { release(directory, lease.first, lease.second) }
                removeOwned(directory)
            }
        }
    }
    private fun removeOwned(file: File) {
        val owner = root.canonicalFile.path + File.separator
        require(file.canonicalFile == file.absoluteFile && file.path.startsWith(owner)) { "Cleanup path is outside private storage" }
        if (file.isDirectory) file.listFiles().orEmpty().forEach(::removeOwned)
        require(!file.exists() || file.delete()) { "Cleanup failed" }
    }
    companion object {
        private val leases = ConcurrentHashMap.newKeySet<String>()
        internal fun hashToken(token: String) = Bp2Digests.hex(Bp2Digests.sha256(Bp2Digests.unhex(token)))
        internal fun safe(directory: File, name: String) = File(directory, name).also { require(it.canonicalFile == it.absoluteFile) { "Redirected storage path" } }
        internal fun read(file: File, limit: Int): String { require(file.isFile && file.length() in 2..limit.toLong()); return file.readText(Charsets.UTF_8) }
        internal fun writeNew(file: File, text: String) {
            val pending = safe(requireNotNull(file.parentFile), "${file.name}.pending")
            FileOutputStream(pending).use { it.write(text.encodeToByteArray()); it.fd.sync() }
            require(!file.exists() && pending.renameTo(file)) { "Metadata publication failed" }
        }
    }
}

class Bp2ResumeLease internal constructor(private val store: Bp2ResumeStore, private val directory: File,
    private val record: ResumeRecord, val entries: List<Bp2Entry>, val selected: Set<String>, val token: String,
    private val guard: RandomAccessFile, private val lock: FileLock) : AutoCloseable {
    private var closed = false
    private val publications = mutableMapOf<String, Bp2PublishedFile>()
    val transferId get() = record.transferId
    private fun checkActive() { check(!closed); require(store.now() < record.expiresAt && !Bp2ResumeStore.safe(directory, "revoked").exists()) { "RESUME_EXPIRED" } }
    private fun identity(entry: Bp2Entry) = Bp2CheckpointIdentity(record.peer, transferId, record.manifestHash, entry.fileId)
    private fun hasCheckpoint(entry: Bp2Entry) = File(store.chunks, "$transferId-${entry.fileId}/0.json").exists() ||
        File(store.chunks, "$transferId-${entry.fileId}").listFiles { f -> f.name.matches(Regex("[0-9]+\\.json")) }.orEmpty().isNotEmpty()
    fun file(entry: Bp2Entry): Bp2CheckpointFile {
        checkActive(); require(entry.fileId in selected)
        return if (hasCheckpoint(entry)) Bp2CheckpointFile.reopen(store.chunks, identity(entry), token, store::now)
        else Bp2CheckpointFile.create(store.chunks, identity(entry), entry.copy(streamId = null), token, store::now)
    }
    fun positions(): List<Bp2ResumePosition> {
        checkActive()
        return entries.filter { it.fileId in selected }.map { entry ->
            val published = published(entry)
            if (published != null) Bp2ResumePosition(entry.fileId, entry.size, completedRoot = published.contentRoot)
            else if (hasCheckpoint(entry)) file(entry).use { Bp2ResumePosition(entry.fileId, it.durableOffset.toString(), it.position().prefixRoot) }
            else Bp2ResumePosition(entry.fileId, "0", Bp2ContentDigest(Bp2.decimal(entry.size), 0).finish())
        }
    }
    fun resetFile(fileId: String, expected: Bp2ResumePosition): Bp2ResumePosition {
        checkActive(); Bp2.requireId(fileId)
        require(fileId in selected && expected.fileId == fileId && expected.completedRoot == null) { "RESUME_MISMATCH" }
        val entry = entries.single { it.fileId == fileId }
        // A publication intent is immutable, even if its final target was moved externally.
        require(!Bp2ResumeStore.safe(directory, "publish-$fileId.json").exists()) { "FILE_ALREADY_COMPLETED" }
        file(entry).use { checkpoint ->
            val position = checkpoint.position()
            require(position.offset.toString() == expected.offset && position.prefixRoot == expected.prefixRoot) { "RESUME_MISMATCH" }
            checkpoint.reset()
            return Bp2ResumePosition(fileId, "0", checkpoint.position().prefixRoot)
        }
    }
    fun published(entry: Bp2Entry): Bp2PublishedFile? {
        publications[entry.fileId]?.let { return it }
        val intent = Bp2ResumeStore.safe(directory, "publish-${entry.fileId}.json")
        if (!intent.exists()) return null
        val value = Bp2.json.decodeFromString<Bp2PublishedFile>(Bp2ResumeStore.read(intent, 16384))
        val target = store.finalPath(value.path)
        if (!target.exists()) return null
        require(target.isFile && target.length() == Bp2.decimal(entry.size)) { "RESUME_MISMATCH" }
        // After a process restart the final file, not its name/length alone, proves publication.
        val digest = Bp2ContentDigest(target.length())
        target.inputStream().use { input ->
            val buffer = ByteArray(Bp2.CHUNK_BYTES); var index = 0L; var remaining = target.length()
            while (remaining > 0) {
                val count = minOf(remaining, buffer.size.toLong()).toInt(); var got = 0
                while (got < count) { val n = input.read(buffer, got, count - got); require(n > 0); got += n }
                digest.addChunkHash(index++, Bp2Digests.sha256(buffer, 0, count)); remaining -= count
            }
        }
        require(digest.finish() == value.contentRoot) { "RESUME_MISMATCH" }
        return value.copy(path = target.path).also { publications[entry.fileId] = it }
    }
    fun destination(entry: Bp2Entry): Bp2Destination {
        val checkpoint = file(entry)
        return object : Bp2Destination {
            override val checkpointFile = checkpoint
            override val sink: okio.Sink get() = error("Checkpoint writes verified chunks directly")
            override suspend fun commit() {
                checkActive()
                val receiptFile = Bp2ResumeStore.safe(directory, "publish-${entry.fileId}.json")
                val value = if (receiptFile.exists()) Bp2.json.decodeFromString<Bp2PublishedFile>(Bp2ResumeStore.read(receiptFile, 16384))
                    else Bp2PublishedFile(store.relative(store.destination(entry)), requireNotNull(checkpoint.position().verifiedRoot), store.now()).also {
                        Bp2ResumeStore.writeNew(receiptFile, Bp2.json.encodeToString(Bp2PublishedFile.serializer(), it))
                    }
                val target = store.finalPath(value.path)
                if (!target.exists()) checkpoint.publishTo(target)
                else require(published(entry)?.contentRoot == value.contentRoot) { "COMMIT_FAILED" }
                publications[entry.fileId] = value.copy(path = target.path)
                dev.boxlet.core.protocol.rfc3339ToMillis(entry.modified.takeIf { it.isNotEmpty() })?.let { target.setLastModified(it) }
                checkpoint.close()
            }
            override suspend fun abort() { checkpoint.discard() }
        }
    }
    fun discard() {
        check(!closed)
        Bp2ResumeStore.safe(directory, "revoked").createNewFile()
        entries.filter { it.fileId in selected && published(it) == null && hasCheckpoint(it) }.forEach { entry ->
            // The transfer lease excludes other sessions; the per-file lock excludes stale writers.
            Bp2CheckpointFile.reopen(store.chunks, identity(entry), token, store::now).use { it.discard() }
        }
    }
    fun complete() {
        checkActive()
        Bp2ResumeStore.safe(directory, "complete").createNewFile()
    }
    override fun close() { if (!closed) { closed = true; store.release(directory, guard, lock) } }
}
