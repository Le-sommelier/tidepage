package app.pdfa.storage

import java.io.File
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.rules.TemporaryFolder

class WorkspaceStoreTest {

    @get:Rule val tmp = TemporaryFolder()

    private val uri = "content://provider/doc/42"

    private fun store(): WorkspaceStore = WorkspaceStore(File(tmp.root, "ws"))

    private fun pdfBytes(size: Int = 2048, magicAt: Int = 0): ByteArray {
        val bytes = ByteArray(size) { (it % 200 + 32).toByte() }
        "%PDF-1.7".toByteArray(Charsets.US_ASCII).copyInto(bytes, magicAt)
        return bytes
    }

    private fun children(dir: File): List<String> = dir.list()?.toList()?.sorted() ?: emptyList()

    private fun import(streams: UriStreams, maxBytes: Long = WorkspaceStore.MAX_IMPORT_BYTES, store: WorkspaceStore = store()) =
        runBlocking { store.importFrom(uri, streams, maxBytes) }

    @Test
    fun importOkCopiesBytesAndWritesManifest() {
        val streams = FakeUriStreams()
        val bytes = pdfBytes()
        streams.put(uri, bytes, lastModified = 777L, displayName = "rapport é.pdf")
        val ws = store()

        val doc = import(streams, store = ws)

        assertContentEquals(bytes, doc.file.readBytes())
        assertEquals(uri, doc.manifest.uri)
        assertEquals("rapport é.pdf", doc.manifest.displayName)
        assertEquals(bytes.size.toLong(), doc.manifest.size)
        assertEquals(777L, doc.manifest.lastModified)
        assertEquals(sha256Hex(bytes), doc.manifest.sha256)
        assertEquals(setOf("original.pdf", "manifest.json"), children(doc.dir).toSet())
        assertEquals(doc, ws.load(doc.docId))
    }

    @Test
    fun importTooLargeWithLoweredCapLeavesNoResidue() {
        val streams = FakeUriStreams()
        streams.put(uri, pdfBytes(size = 3000))
        val e = assertFailsWith<StorageException> { import(streams, maxBytes = 2048) }
        assertTrue(e.error is StorageError.TooLarge)
        assertEquals(emptyList(), children(File(tmp.root, "ws/work")))
        assertEquals(emptyList(), children(File(tmp.root, "ws/tmp")))
    }

    @Test
    fun importAtExactlyTheCapIsAccepted() {
        val streams = FakeUriStreams()
        streams.put(uri, pdfBytes(size = 2048))
        assertEquals(2048L, import(streams, maxBytes = 2048).manifest.size)
    }

    @Test
    fun importRejectsStatDeclaredSizeOverCapBeforeReading() {
        val streams = FakeUriStreams()
        streams.put(uri, pdfBytes(size = 5000))
        streams.failOpenInput = true
        val e = assertFailsWith<StorageException> { import(streams, maxBytes = 4096) }
        assertTrue(e.error is StorageError.TooLarge)
    }

    @Test
    fun importNotPdfIsRejected() {
        val streams = FakeUriStreams()
        streams.put(uri, "<html>not a pdf</html>".repeat(10).toByteArray())
        val ws = store()
        val e = assertFailsWith<StorageException> { import(streams, store = ws) }
        assertTrue(e.error is StorageError.NotPdf)
        assertEquals(emptyList(), children(File(tmp.root, "ws/work")))
    }

    @Test
    fun emptySourceIsNotPdf() {
        val streams = FakeUriStreams()
        streams.put(uri, ByteArray(0))
        val e = assertFailsWith<StorageException> { import(streams) }
        assertTrue(e.error is StorageError.NotPdf)
    }

    @Test
    fun magicAtOffset1019IsAccepted() {
        val streams = FakeUriStreams()
        streams.put(uri, pdfBytes(size = 4096, magicAt = 1019))
        assertEquals(4096L, import(streams).manifest.size)
    }

    @Test
    fun magicAtOffset1020IsRejected() {
        val streams = FakeUriStreams()
        streams.put(uri, pdfBytes(size = 4096, magicAt = 1020))
        val e = assertFailsWith<StorageException> { import(streams) }
        assertTrue(e.error is StorageError.NotPdf)
    }

    @Test
    fun declaredNameDoesNotOverrideMagicCheck() {
        val streams = FakeUriStreams()
        streams.put(uri, "MZ executable".toByteArray(), displayName = "invoice.pdf")
        val e = assertFailsWith<StorageException> { import(streams) }
        assertTrue(e.error is StorageError.NotPdf)
    }

    @Test
    fun crashBeforeRenameLeavesNoHalfDocumentInWorkspace() {
        val streams = FakeUriStreams()
        streams.put(uri, pdfBytes())
        val ws = store()
        assertFailsWith<StorageException> {
            runBlocking {
                ws.importFrom(uri, streams, beforeCommit = { throw IllegalStateException("simulated kill") })
            }
        }
        assertEquals(emptyList(), children(File(tmp.root, "ws/work")))
        assertEquals(emptyList(), children(File(tmp.root, "ws/tmp")))
    }

    @Test
    fun crashLeftoversAreSweptAtStartup() {
        val streams = FakeUriStreams()
        streams.put(uri, pdfBytes())
        val ws = store()
        val leftover = File(tmp.root, "ws/tmp/dead-import").apply { mkdirs() }
        File(leftover, "original.pdf").writeBytes(pdfBytes(64))
        val kept = import(streams, store = ws)

        assertEquals(0, ws.sweepTmp())

        assertFalse(leftover.exists())
        assertTrue(kept.file.isFile)
    }

    @Test
    fun sweepRemovesPartAndStagingNextFilesInsideDocuments() {
        val streams = FakeUriStreams()
        streams.put(uri, pdfBytes())
        val ws = store()
        val doc = import(streams, store = ws)
        File(doc.dir, "manifest.json.part").writeText("x")
        File(doc.dir, "staging.next").writeText("x")

        assertEquals(0, ws.sweepTmp())

        assertEquals(setOf("original.pdf", "manifest.json"), children(doc.dir).toSet())
    }

    @Test
    fun readFailureDuringImportIsIoAndLeavesNothing() {
        val streams = FakeUriStreams()
        streams.put(uri, pdfBytes(size = 8192))
        streams.failInputAfter = 4000
        val ws = store()
        val e = assertFailsWith<StorageException> { import(streams, store = ws) }
        assertTrue(e.error is StorageError.Io)
        assertEquals(emptyList(), children(File(tmp.root, "ws/work")))
        assertEquals(emptyList(), children(File(tmp.root, "ws/tmp")))
    }

    @Test
    fun statFailureIsIo() {
        val streams = FakeUriStreams()
        streams.put(uri, pdfBytes())
        streams.failStat = true
        val e = assertFailsWith<StorageException> { import(streams) }
        assertTrue(e.error is StorageError.Io)
    }

    @Test
    fun errorDetailNeverContainsTheUri() {
        val streams = FakeUriStreams()
        streams.put(uri, "junk".toByteArray())
        streams.failOpenInput = true
        val e = assertFailsWith<StorageException> { import(streams) }
        assertFalse(e.error.detail.contains("content://"))
        assertFalse(e.error.detail.contains("42"))
    }

    @Test
    fun providerSizeThatDisagreesWithBytesFailsTheImport() {
        // A stale SIZE column must not produce a silently truncated or padded import.
        val streams = FakeUriStreams()
        streams.put(uri, pdfBytes(size = 2048))
        streams.staleSize = 2058
        val e = assertFailsWith<StorageException> { import(streams) }
        assertTrue(e.error is StorageError.Io)
        assertEquals(emptyList(), children(File(tmp.root, "ws/work")))
    }

    @Test
    fun cancellationDuringImportLeavesNoResidue() {
        val streams = FakeUriStreams()
        streams.put(uri, pdfBytes(size = 400_000))
        streams.readDelayMs = 40
        val ws = store()
        runBlocking {
            val job = launch(Dispatchers.Default) { ws.importFrom(uri, streams) }
            delay(60)
            job.cancel()
            job.join()
            assertTrue(job.isCancelled)
        }
        assertEquals(emptyList(), children(File(tmp.root, "ws/work")))
        assertEquals(emptyList(), children(File(tmp.root, "ws/tmp")))
    }

    @Test
    fun loneSurrogateInUriIsRejectedAsIoNotMangled() {
        val streams = FakeUriStreams()
        val weird = "content://provider/doc/\uD800"
        streams.put(weird, pdfBytes())
        val ws = store()
        val e = assertFailsWith<StorageException> { runBlocking { ws.importFrom(weird, streams) } }
        assertTrue(e.error is StorageError.Io)
        assertEquals(emptyList(), children(File(tmp.root, "ws/work")))
    }

    @Test
    fun twoImportsOfTheSameFileGetDistinctIds() {
        val streams = FakeUriStreams()
        streams.put(uri, pdfBytes())
        val ws = store()
        val a = import(streams, store = ws)
        val b = import(streams, store = ws)
        assertNotEquals(a.docId, b.docId)
        assertEquals(a.manifest.sha256, b.manifest.sha256)
    }

    @Test
    fun loadRejectsPathLikeDocIdsAsStorageError() {
        val e = assertFailsWith<StorageException> { store().load("../../etc") }
        assertTrue(e.error is StorageError.Io)
    }

    @Test
    fun loadOfMissingDocumentIsStorageError() {
        val e = assertFailsWith<StorageException> { store().load("00000000-0000-0000-0000-000000000000") }
        assertTrue(e.error is StorageError.Io)
    }

    @Test
    fun importCancelledAtTheCommitPointLeavesACompleteDocument() {
        val streams = FakeUriStreams()
        streams.put(uri, pdfBytes())
        val ws = store()
        val holder = arrayOfNulls<Job>(1)
        runBlocking {
            val job = launch(Dispatchers.Default, start = CoroutineStart.LAZY) {
                // Cancelled from beforeCommit: the rename must still happen and the result must come back.
                ws.importFrom(uri, streams, beforeCommit = { holder[0]!!.cancel() })
            }
            holder[0] = job
            job.start()
            job.join()
            assertTrue(job.isCancelled)
        }
        val docs = File(tmp.root, "ws/work").listFiles()!!
        assertEquals(1, docs.size)
        assertEquals(setOf("original.pdf", "manifest.json"), children(docs[0]).toSet())
        assertEquals(2048L, ws.load(docs[0].name).manifest.size)
        assertEquals(emptyList(), children(File(tmp.root, "ws/tmp")))
    }
}
