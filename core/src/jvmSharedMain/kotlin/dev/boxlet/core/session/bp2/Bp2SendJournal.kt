package dev.boxlet.core.session.bp2

import dev.boxlet.core.crypto.Certificates
import dev.boxlet.core.crypto.SecurityContext
import dev.boxlet.core.protocol.Device
import dev.boxlet.core.protocol.DeviceChannel
import dev.boxlet.core.protocol.bp2.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*
import okio.Source
import okio.source
import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.nio.channels.FileLock
import java.security.SecureRandom
import java.security.spec.MGF1ParameterSpec
import java.util.concurrent.ConcurrentHashMap
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.OAEPParameterSpec
import javax.crypto.spec.PSource
import javax.crypto.spec.SecretKeySpec

fun interface Bp2SourceResolver {
    fun open(reference: Bp2SourceRef, entry: Bp2Entry): Source
}

class Bp2SourceUnavailable(cause: Throwable? = null) : java.io.IOException("SOURCE_PERMISSION_REQUIRED", cause)

object Bp2FileSourceResolver : Bp2SourceResolver {
    override fun open(reference: Bp2SourceRef, entry: Bp2Entry): Source {
        reference.validate(); if (reference.kind != "file") throw Bp2SourceUnavailable()
        val file = File(reference.locator)
        if (!file.isAbsolute || file.canonicalFile.path != file.path || !file.isFile || !file.canRead()) throw Bp2SourceUnavailable()
        require(file.length() == Bp2.decimal(entry.size)) { "SOURCE_CHANGED" }
        return try { file.source() } catch (error: java.io.IOException) { throw Bp2SourceUnavailable(error) }
    }
}

@Serializable
data class Bp2SavedSource(val entry: Bp2Entry, val reference: Bp2SourceRef?)

@Serializable
data class Bp2SendSnapshot(val transferId: String, val device: Device, val channel: DeviceChannel,
    val manifestHash: String, private val resumeToken: String, val accepted: Set<String>,
    val files: List<Bp2SavedSource>, val expiresAt: Long) {
    fun ticket() = Bp2ResumeTicket(transferId, manifestHash, resumeToken, accepted)
    fun validate() {
        Bp2.requireId(transferId); Bp2.requireFingerprint(manifestHash); Bp2.requireFingerprint(resumeToken)
        Bp2.requireFingerprint(device.fingerprint.lowercase())
        require(channel.adapterId == Bp2.ADAPTER_ID && channel.https && channel.port in 1..65535)
        require(channel.host.isNotBlank() && channel.host.length <= 253 && channel.host.none { it.isWhitespace() })
        require(files.size in 1..100000 && accepted.isNotEmpty() && expiresAt > 0)
        val digest = Bp2ManifestDigest()
        files.forEach { saved -> saved.entry.validate(); require(saved.entry.kind == "file" && saved.entry.streamId == null); saved.reference?.validate(); digest.add(saved.entry) }
        require(digest.finish() == manifestHash)
        val selected = files.associateBy { it.entry.fileId }
        require(selected.keys.containsAll(accepted) && accepted.all { selected.getValue(it).reference != null })
    }
    override fun toString() = "Bp2SendSnapshot(transferId=$transferId, resumeToken=<redacted>, files=${files.size})"
}

@Serializable
private data class JournalRecord(val version: Int = 1, val task: Bp2SendSnapshot? = null)

/** DTO decoding ignores extensions, but a frozen endpoint must never change transports. */
internal fun validateSavedTcpTransport(record: JsonObject) {
    val task = record["task"]?.takeUnless { it == JsonNull }?.jsonObject ?: return
    val channel = task.getValue("channel").jsonObject
    val previousTransport = channel["carrier"] ?: return
    require(previousTransport is JsonPrimitive && previousTransport.isString && previousTransport.content == "tcp-tls") {
        "Unsupported saved transport"
    }
}

/** One durable outgoing task per application data directory. No source bytes are stored. */
class Bp2SendJournal(root: File, identity: SecurityContext,
    private val resolver: Bp2SourceResolver = Bp2FileSourceResolver,
    private val clock: () -> Long = System::currentTimeMillis) : AutoCloseable {
    private val root = root.canonicalFile
    private val keys = Certificates.localStateKeys(identity)
    private val aad = "boxlet-send-journal-v1\u0000${identity.fingerprint.lowercase()}".encodeToByteArray()
    private val guard: RandomAccessFile
    private val lock: FileLock
    private var closed = false
    init {
        require(this.root.mkdirs() || this.root.isDirectory)
        check(owners.add(this.root.path)) { "Outgoing journal is in use" }
        var opened: RandomAccessFile? = null
        try {
            opened = RandomAccessFile(child("owner.lock"), "rw")
            lock = opened.channel.tryLock() ?: error("Outgoing journal is in use")
            guard = opened
        } catch (error: Throwable) { opened?.close(); owners.remove(this.root.path); throw error }
    }
    @Synchronized fun load(): Bp2SendSnapshot? {
        check(!closed)
        val record = readLatest() ?: return null
        val task = record.task ?: return null
        task.validate()
        if (clock() >= task.expiresAt) { publish(JournalRecord()); return null }
        return task
    }
    @Synchronized fun save(task: Bp2SendSnapshot) {
        check(!closed); task.validate(); require(clock() < task.expiresAt) { "RESUME_EXPIRED" }
        val current = readLatest()?.task
        require(current == null || current.transferId == task.transferId) { "Another outgoing task is retained" }
        publish(JournalRecord(task = task))
    }
    @Synchronized fun clear(transferId: String) {
        check(!closed); Bp2.requireId(transferId)
        if (readLatest()?.task?.transferId == transferId) publish(JournalRecord())
    }
    /** Keep damaged ciphertext for diagnosis, then make it inert instead of replaying an older generation. */
    @Synchronized fun quarantine() {
        check(!closed)
        generations().forEach { file -> require(file.renameTo(child("unreadable-${newBp2Id()}.bin"))) }
        publish(JournalRecord())
    }
    fun source(saved: Bp2SavedSource): () -> Source = {
        val reference = saved.reference ?: error("SOURCE_PERMISSION_REQUIRED")
        resolver.open(reference, saved.entry)
    }
    private fun readLatest(): JournalRecord? {
        val file = generations().maxByOrNull(::generation) ?: return null
        require(file.length() in 300..MAX_BYTES.toLong()) { "Invalid outgoing journal size" }
        val buffer = java.nio.ByteBuffer.wrap(file.readBytes())
        require(buffer.int == MAGIC && buffer.get().toInt() == 1) { "Invalid outgoing journal format" }
        val count = buffer.short.toInt() and 65535
        require(count in 128..1024 && buffer.remaining() >= count + 12 + 16)
        val wrapped = ByteArray(count); buffer.get(wrapped)
        val nonce = ByteArray(12); buffer.get(nonce)
        val encrypted = ByteArray(buffer.remaining()); buffer.get(encrypted)
        val unwrap = Cipher.getInstance("RSA/ECB/OAEPWithSHA-256AndMGF1Padding")
        unwrap.init(Cipher.DECRYPT_MODE, keys.private, OAEP)
        val key = unwrap.doFinal(wrapped)
        try {
            require(key.size == 32)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, nonce)); cipher.updateAAD(aad)
            val text = cipher.doFinal(encrypted).decodeToString(throwOnInvalidSequence = true)
            validateSavedTcpTransport(Bp2.json.parseToJsonElement(text).jsonObject)
            return Bp2.json.decodeFromString<JournalRecord>(text).also { require(it.version == 1) }
        } finally { key.fill(0) }
    }
    private fun publish(record: JournalRecord) {
        val current = generations(); val next = (current.maxOfOrNull(::generation) ?: -1L) + 1
        require(next >= 0 && next < Long.MAX_VALUE)
        val bytes = Bp2.json.encodeToString(JournalRecord.serializer(), record).encodeToByteArray()
        require(bytes.size <= MAX_BYTES - 2048) { "Outgoing metadata exceeds limit" }
        val random = SecureRandom(); val key = ByteArray(32).also(random::nextBytes); val nonce = ByteArray(12).also(random::nextBytes)
        try {
            val wrap = Cipher.getInstance("RSA/ECB/OAEPWithSHA-256AndMGF1Padding"); wrap.init(Cipher.ENCRYPT_MODE, keys.public, OAEP)
            val wrapped = wrap.doFinal(key)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, nonce)); cipher.updateAAD(aad)
            val header = java.nio.ByteBuffer.allocate(7 + wrapped.size + nonce.size)
                .putInt(MAGIC).put(1).putShort(wrapped.size.toShort()).put(wrapped).put(nonce).array()
            val pending = child("journal.pending")
            FileOutputStream(pending).use { it.write(header); it.write(cipher.doFinal(bytes)); it.fd.sync() }
            require(pending.renameTo(child("$next.bin"))) { "Outgoing journal publication failed" }
            current.filter { record.task == null || generation(it) < next - 1 }.forEach { require(it.delete()) }
        } finally { key.fill(0); bytes.fill(0) }
    }
    private fun child(name: String) = File(root, name).also { require(it.canonicalFile == it.absoluteFile) { "Redirected outgoing journal path" } }
    private fun generation(file: File) = file.name.removeSuffix(".bin").toLongOrNull() ?: error("Invalid journal generation")
    private fun generations(): List<File> = root.listFiles { file -> file.name.matches(Regex("[0-9]+\\.bin")) }.orEmpty().toList().also {
        require(it.size <= 8); it.forEach { file -> require(child(file.name).isFile) }
    }
    @Synchronized override fun close() {
        if (closed) return
        closed = true
        try { runCatching { lock.release() }; guard.close() } finally { owners.remove(root.path) }
    }
    companion object {
        private const val MAGIC = 0x4250534a // BPSJ
        private const val MAX_BYTES = 64 * 1024 * 1024
        private val owners = ConcurrentHashMap.newKeySet<String>()
        private val OAEP = OAEPParameterSpec("SHA-256", "MGF1", MGF1ParameterSpec.SHA256, PSource.PSpecified.DEFAULT)
    }
}
