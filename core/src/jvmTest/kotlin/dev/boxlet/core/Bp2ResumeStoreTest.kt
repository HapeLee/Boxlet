package dev.boxlet.core

import dev.boxlet.core.protocol.bp2.*
import dev.boxlet.core.session.bp2.*
import kotlinx.coroutines.runBlocking
import java.io.File
import java.nio.file.Files
import kotlin.test.*

class Bp2ResumeStoreTest {
    private val root = Files.createTempDirectory("boxlet-resume-store-").toFile()
    private val privateRoot = File(root, "private")
    private val target = File(root, "received")
    private var now = 1000L
    private val peer = "1".repeat(64)
    private val entry = Bp2Entry("2".repeat(32), path = "file.bin", size = "3")
    private val id = "3".repeat(32)
    private fun hash(entries: List<Bp2Entry>) = Bp2ManifestDigest().also { d -> entries.forEach(d::add) }.finish()
    private fun store(quota: Long = 1024 * 1024) = Bp2ResumeStore(privateRoot, target, quota) { now }
    private fun create(store: Bp2ResumeStore, values: List<Bp2Entry> = listOf(entry)) =
        store.create(peer, id, hash(values), values, values.map { it.fileId }.toSet())
    private fun ticket(lease: Bp2ResumeLease) = Bp2ResumeTicket(id, hash(listOf(entry)), lease.token, setOf(entry.fileId))
    @AfterTest fun cleanup() { check(root.name.startsWith("boxlet-resume-store-")); root.deleteRecursively() }

    @Test fun credentialsBindPeerManifestAndTransferAndLocksSpanRepositoryInstances() {
        val store = store(); val lease = create(store); val ticket = ticket(lease)
        store.validateTicket(peer, ticket) // Valid takeover can be authorized while the old lease is held.
        assertFails { store.validateTicket(peer, ticket.copy(resumeToken = "0".repeat(64))) }
        assertEquals("0", lease.positions().single().offset)
        assertFails { store().resume(peer, ticket) }
        lease.close()
        assertFails { store.resume("4".repeat(64), ticket) }
        assertFails { store.resume(peer, ticket.copy(manifestHash = "5".repeat(64))) }
        assertFails { store.resume(peer, ticket.copy(resumeToken = "0".repeat(64))) }
        assertTrue(privateRoot.walkTopDown().filter { it.isFile && it.extension == "json" }.none { ticket.resumeToken in it.readText() })
        store.resume(peer, ticket).use { assertEquals("0", it.positions().single().offset) }
    }

    @Test fun expiredAndRevokedTasksAreCleanedWithoutRemovingPublishedFiles() = runBlocking {
        val store = store(); val lease = create(store); val ticket = ticket(lease)
        val bytes = "abc".encodeToByteArray()
        val root = Bp2ContentDigest(3).also { it.addChunkHash(0, Bp2Digests.sha256(bytes)) }.finish()
        val writer = Bp2IncomingFile(entry.copy(streamId = 1), lease.destination(entry))
        writer.accept(Bp2Frame(Bp2Header(Bp2Kind.DATA, 1, 35, sequence = 1), Bp2Payloads.data(bytes)))
        writer.finish(root); lease.complete(); lease.close()
        now += Bp2CheckpointFile.LIFETIME_MS
        store.cleanup()
        assertFails { store.resume(peer, ticket) }
        assertContentEquals(bytes, File(target, "file.bin").readBytes())
        assertTrue(File(privateRoot, "transfers").listFiles().orEmpty().isEmpty())
    }

    @Test fun localCancellationAndTrustRevocationRemoveOnlyPrivatePartialBytes() {
        val store = store(); val block = ByteArray(Bp2.CHUNK_BYTES) { 7 }
        val value = entry.copy(size = Bp2.CHUNK_BYTES.toString())
        val lease = create(store, listOf(value)); val token = lease.token
        lease.file(value).use { it.append(block, Bp2Digests.sha256(block)); it.checkpoint() }; lease.close()
        store.cleanup(emptySet())
        assertFails { store.resume(peer, Bp2ResumeTicket(id, hash(listOf(value)), token, setOf(value.fileId))) }
        assertTrue(File(privateRoot, "chunks").walkTopDown().none { it.name == "data.part" })
        val second = create(store); val ticket = ticket(second); second.close()
        store.cancelLocal(peer, id)
        assertFails { store.resume(peer, ticket) }
    }

    @Test fun quotaIsCheckedBeforeAcceptAndPublicationDoesNotOverwriteANameConflict() = runBlocking {
        assertFails { create(store(quota = 2)) }
        val store = store(); target.mkdirs(); File(target, "file.bin").writeText("existing")
        val lease = create(store)
        val writer = Bp2IncomingFile(entry.copy(streamId = 1), lease.destination(entry))
        val bytes = "abc".encodeToByteArray()
        writer.accept(Bp2Frame(Bp2Header(Bp2Kind.DATA, 1, 35, sequence = 1), Bp2Payloads.data(bytes)))
        writer.finish(Bp2ContentDigest(3).also { it.addChunkHash(0, Bp2Digests.sha256(bytes)) }.finish())
        val published = lease.published(entry)!!
        assertNotEquals(File(target, "file.bin").path, published.path)
        assertEquals("existing", File(target, "file.bin").readText())
        assertContentEquals(bytes, File(published.path).readBytes())
        lease.close()
    }

    @Test fun resetBindsTheOldPositionAndCannotTouchRejectedOrPublishedFiles() = runBlocking {
        val store = store(quota = 8L * Bp2.CHUNK_BYTES)
        val changed = entry.copy(size = (2L * Bp2.CHUNK_BYTES).toString())
        val untouched = entry.copy(fileId = "4".repeat(32), path = "untouched.bin", size = changed.size)
        val completed = entry.copy(fileId = "5".repeat(32), path = "completed.bin")
        val rejected = entry.copy(fileId = "6".repeat(32), path = "rejected.bin")
        val values = listOf(changed, untouched, completed, rejected)
        val lease = store.create(peer, id, hash(values), values, setOf(changed.fileId, untouched.fileId, completed.fileId))
        val block = ByteArray(Bp2.CHUNK_BYTES) { 7 }
        for (value in listOf(changed, untouched)) lease.file(value).use { it.append(block, Bp2Digests.sha256(block)); it.checkpoint() }
        val writer = Bp2IncomingFile(completed.copy(streamId = 1), lease.destination(completed))
        val bytes = "abc".encodeToByteArray()
        writer.accept(Bp2Frame(Bp2Header(Bp2Kind.DATA, 1, 35, sequence = 1), Bp2Payloads.data(bytes)))
        writer.finish(Bp2ContentDigest(3).also { it.addChunkHash(0, Bp2Digests.sha256(bytes)) }.finish())
        val before = lease.positions().associateBy { it.fileId }
        val position = before.getValue(changed.fileId)
        assertFails { lease.resetFile(changed.fileId, position.copy(prefixRoot = "0".repeat(64))) }
        assertFails { lease.resetFile(rejected.fileId, position.copy(fileId = rejected.fileId)) }
        assertFails { lease.resetFile(completed.fileId, before.getValue(completed.fileId)) }
        assertEquals(before, lease.positions().associateBy { it.fileId })
        assertEquals("0", lease.resetFile(changed.fileId, position).offset)
        val after = lease.positions().associateBy { it.fileId }
        assertEquals(before[untouched.fileId], after[untouched.fileId]); assertEquals(before[completed.fileId], after[completed.fileId])
        assertContentEquals(bytes, File(target, "completed.bin").readBytes())
        assertTrue(File(target, "completed.bin").delete())
        assertFails { lease.resetFile(completed.fileId, Bp2ResumePosition(completed.fileId, "0", position.prefixRoot)) }
        lease.close()
    }
}
