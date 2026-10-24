package dev.boxlet.core

import dev.boxlet.core.protocol.bp2.*
import dev.boxlet.core.session.bp2.*
import kotlinx.coroutines.runBlocking
import okio.Buffer
import kotlin.test.*

class Bp2IncomingFileTest {
    private class Destination(private val failCommit: Boolean = false) : Bp2Destination {
        override val sink = Buffer()
        var committed = 0
        var aborted = 0
        var saved: ByteArray? = null
        override suspend fun commit() {
            if (failCommit) error("Disk publication failed")
            committed++; saved = sink.readByteArray()
        }
        override suspend fun abort() { aborted++ }
    }
    private val entry = Bp2Entry("1".repeat(32), path = "test.bin", size = "3", streamId = 1)
    private val data = "abc".encodeToByteArray()
    private fun frame(bytes: ByteArray = Bp2Payloads.data(data)) =
        Bp2Frame(Bp2Header(Bp2Kind.DATA, 1, bytes.size, sequence = 1), bytes)
    private val root get() = Bp2ContentDigest(3).also { it.addChunkHash(0, Bp2Digests.sha256(data)) }.finish()

    @Test fun corruptChunkCanBeRetriedAndCommitIsIdempotent() = runBlocking {
        val destination = Destination()
        val incoming = Bp2IncomingFile(entry, destination)
        val bad = Bp2Payloads.data(data).also { it[32] = 0 }
        assertFalse(incoming.accept(frame(bad)))
        assertEquals(0L, destination.sink.size)
        assertEquals(0L, incoming.written)
        assertTrue(incoming.accept(frame()))
        assertEquals(0, destination.committed)
        incoming.finish(root); incoming.finish(root)
        assertEquals(1, destination.committed)
        assertContentEquals(data, destination.saved)
        assertFails { incoming.finish("0".repeat(64)) }
        Unit
    }

    @Test fun threeBadChunksAbortWithoutPublishing() = runBlocking {
        val destination = Destination()
        val incoming = Bp2IncomingFile(entry, destination)
        repeat(3) { assertFalse(incoming.accept(frame(Bp2Payloads.data(data).also { it[0] = 0 }))) }
        assertEquals(Bp2IncomingFile.State.FAILED, incoming.state)
        assertEquals(1, destination.aborted)
        assertEquals(0, destination.committed)
    }

    @Test fun incompleteRootMismatchAndCommitFailureCannotReportSuccess() = runBlocking {
        for (case in 0..2) {
            val destination = Destination(failCommit = case == 2)
            val incoming = Bp2IncomingFile(entry, destination)
            if (case != 0) incoming.accept(frame())
            assertFails { incoming.finish(if (case == 1) "0".repeat(64) else root) }
            assertEquals(Bp2IncomingFile.State.FAILED, incoming.state)
            assertEquals(1, destination.aborted)
            assertEquals(0, destination.committed)
        }
    }

    @Test fun cancelAndEmptyFileHaveExplicitPublicationSemantics() = runBlocking {
        val cancelled = Destination()
        val incoming = Bp2IncomingFile(entry, cancelled)
        incoming.cancel(); incoming.cancel()
        assertFails { incoming.accept(frame()) }
        assertEquals(1, cancelled.aborted)
        assertEquals(0, cancelled.committed)
        val destination = Destination()
        Bp2IncomingFile(entry.copy(size = "0"), destination).finish(Bp2ContentDigest(0).finish())
        assertEquals(1, destination.committed)
        assertContentEquals(ByteArray(0), destination.saved)
    }
}
