package dev.boxlet.core

import dev.boxlet.core.crypto.Certificates
import dev.boxlet.core.protocol.Device
import dev.boxlet.core.protocol.DeviceChannel
import dev.boxlet.core.protocol.bp2.*
import dev.boxlet.core.session.bp2.*
import java.io.File
import java.nio.file.Files
import okio.buffer
import kotlinx.serialization.json.*
import kotlin.test.*

class Bp2SendJournalTest {
    private val root = Files.createTempDirectory("boxlet-send-journal-").toFile()
    private val directory = File(root, "journal")
    private val identity = Certificates.generate()
    private var now = 1000L
    private val journals = mutableListOf<Bp2SendJournal>()
    private fun journal(own: dev.boxlet.core.crypto.SecurityContext = identity) = Bp2SendJournal(directory, own, clock = { now }).also(journals::add)
    private fun task(): Bp2SendSnapshot {
        val file = File(root, "private-original-name.bin").also { it.writeText("abc") }
        val entry = Bp2Entry("1".repeat(32), path = "file.bin", size = "3")
        val hash = Bp2ManifestDigest().also { it.add(entry) }.finish()
        return Bp2SendSnapshot("2".repeat(32), Device("3".repeat(64), "Peer"), DeviceChannel("127.0.0.1", 53318, true, Bp2.ADAPTER_ID),
            hash, Bp2CheckpointFile.newToken(), setOf(entry.fileId), listOf(Bp2SavedSource(entry, Bp2SourceRef("file", file.canonicalPath))), now + 10000)
    }
    @AfterTest fun close() { journals.forEach { it.close() }; check(root.name.startsWith("boxlet-send-journal-")); root.deleteRecursively() }

    @Test fun encryptedTaskSurvivesReopenWithoutWritingTokenOrSourcePathInPlaintext() {
        val snapshot = task(); val first = journal(); first.save(snapshot)
        assertEquals(snapshot, first.load())
        directory.listFiles().orEmpty().filter { it.extension == "bin" }.forEach { file ->
            val bytes = file.readBytes().toString(Charsets.ISO_8859_1)
            assertFalse(snapshot.ticket().resumeToken in bytes)
            assertFalse("private-original-name" in bytes)
        }
        first.close()
        val second = journal(); val loaded = second.load()!!
        assertEquals(snapshot, loaded)
        second.source(loaded.files.single())().use { source -> assertEquals("abc", source.buffer().readUtf8()) }
    }

    @Test fun changedIdentityAndSecondWriterCannotReadOrReplaceTheTask() {
        val first = journal(); val snapshot = task(); first.save(snapshot)
        assertFails { journal() }
        first.close()
        val wrong = journal(Certificates.generate()); assertFails { wrong.load() }; wrong.close()
        assertEquals(snapshot, journal().load())
    }

    @Test fun tamperingDoesNotFallBackToAnOlderValidTask() {
        val value = task(); val store = journal(); store.save(value); store.save(value.copy(expiresAt = value.expiresAt + 1))
        val newest = directory.listFiles().orEmpty().filter { it.name.matches(Regex("[0-9]+\\.bin")) }.maxBy { it.name.removeSuffix(".bin").toLong() }
        val bytes = newest.readBytes(); bytes[bytes.lastIndex] = (bytes.last().toInt() xor 1).toByte(); newest.writeBytes(bytes)
        assertFails { store.load() }
        store.quarantine()
        assertNull(store.load()); assertTrue(directory.listFiles().orEmpty().any { it.name.startsWith("unreadable-") })
    }

    @Test fun expiryAndClearMakeOlderGenerationsInert() {
        val store = journal(); val snapshot = task(); store.save(snapshot)
        store.clear("4".repeat(32)); assertNotNull(store.load())
        store.clear(snapshot.transferId); assertNull(store.load())
        assertEquals(1, directory.listFiles().orEmpty().count { it.name.matches(Regex("[0-9]+\\.bin")) })
        store.save(snapshot); now = snapshot.expiresAt; assertNull(store.load())
    }

    @Test fun onlyTheOriginalAcceptedSourcesMayBeRebuilt() {
        val store = journal(); val snapshot = task()
        assertFails { store.save(snapshot.copy(accepted = setOf("5".repeat(32)))) }
        assertFails { store.save(snapshot.copy(files = snapshot.files.map { it.copy(reference = null) })) }
        assertFails { store.save(snapshot.copy(manifestHash = "6".repeat(64))) }
        store.save(snapshot)
        File(snapshot.files.single().reference!!.locator).delete()
        assertNotNull(store.load(), "Unavailable sources are checked when continuing, not discarded at startup")
        assertFailsWith<Bp2SourceUnavailable> { store.source(snapshot.files.single())() }
    }

    @Test fun unknownFrozenTransportCannotBeImplicitlyReinterpretedAsTcp() {
        fun record(value: JsonElement?) = buildJsonObject { putJsonObject("task") {
            putJsonObject("channel") { value?.let { put("carrier", it) } }
        } }
        validateSavedTcpTransport(record(null))
        validateSavedTcpTransport(record(JsonPrimitive("tcp-tls")))
        validateSavedTcpTransport(buildJsonObject {})
        assertFails { validateSavedTcpTransport(record(JsonPrimitive("retired-udp"))) }
        assertFails { validateSavedTcpTransport(record(JsonPrimitive(1))) }
    }
}
