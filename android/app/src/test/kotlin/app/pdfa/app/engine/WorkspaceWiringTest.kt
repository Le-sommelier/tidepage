package app.pdfa.app.engine

import app.pdfa.app.intent.DocRef
import app.pdfa.app.recents.StoreRecentsRepository
import app.pdfa.storage.AutosaveRecoverySource
import app.pdfa.storage.OutputStreamWithSync
import app.pdfa.storage.RecentsStore
import app.pdfa.storage.RecoveryScanner
import app.pdfa.storage.SafWriter
import app.pdfa.storage.SourceStat
import app.pdfa.storage.UriStreams
import app.pdfa.storage.WorkspaceStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import java.io.ByteArrayInputStream
import java.io.File
import java.io.InputStream
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

private class MapStreams(private val docs: Map<String, ByteArray>) : UriStreams {
    override fun openInput(uri: String): InputStream = ByteArrayInputStream(docs.getValue(uri))
    override fun openOutputTruncating(uri: String): OutputStreamWithSync = error("read only")
    override fun stat(uri: String): SourceStat = SourceStat(docs.getValue(uri).size.toLong(), null, "name.pdf")
}

class WorkspaceWiringTest {
    private val root: File = Files.createTempDirectory("pdfa-app").toFile()
    private val pdf = "%PDF-1.4\nbody".toByteArray()
    private val uri = "content://com.example.provider/document/1"
    private val streams = MapStreams(mapOf(uri to pdf))
    private val store = WorkspaceStore(root, Dispatchers.Unconfined)
    private val recovery = WorkspaceRecovery(
        store,
        AutosaveRecoverySource(store, RecoveryScanner(root, SafWriter())),
        Dispatchers.Unconfined,
    )

    @Test
    fun recoveryFallsBackToTheWorkingCopyAndSaysSo() = runBlocking {
        val doc = store.importFrom(uri, streams)
        assertFalse(recovery.restoredFromOriginal(doc.docId))

        assertContentEquals(pdf, recovery.latestBytes(doc.docId))
        assertTrue(recovery.restoredFromOriginal(doc.docId))
        assertNull(recovery.passwordFor(doc.docId))
    }

    @Test
    fun recoveryOfAnUnknownOrDamagedDocumentIsNull() = runBlocking {
        assertNull(recovery.latestBytes("00000000-0000-0000-0000-000000000000"))
        assertNull(recovery.latestBytes("../../etc/passwd"))

        val doc = store.importFrom(uri, streams)
        doc.file.appendBytes(byteArrayOf(1, 2, 3)) // no longer the size the manifest recorded
        assertNull(recovery.latestBytes(doc.docId))
        assertFalse(recovery.restoredFromOriginal(doc.docId))
    }

    @Test
    fun aPristineWorkingCopyIsDiscardedButOneWithOtherFilesIsKept() = runBlocking {
        val copies = WorkingCopies(root)
        val plain = store.importFrom(uri, streams)
        val edited = store.importFrom(uri, streams)
        File(edited.dir, "autosave.pdf").writeBytes(pdf)

        assertTrue(copies.discard(plain.docId))
        assertFalse(plain.dir.exists())
        assertFalse(copies.discard(edited.docId))
        assertTrue(edited.dir.exists())
        assertFalse(copies.discard("../work"))
    }

    @Test
    fun theStartupSweepRemovesOnlyPristineCopies() = runBlocking {
        val copies = WorkingCopies(root)
        store.importFrom(uri, streams)
        store.importFrom(uri, streams)
        val edited = store.importFrom(uri, streams)
        File(edited.dir, "staging.pdf").writeBytes(pdf)

        assertEquals(2, copies.sweepPristine())
        assertEquals(listOf(edited.docId), File(root, "work").list()!!.toList())
        assertEquals(0, WorkingCopies(File(root, "missing")).sweepPristine())
    }

    @Test
    fun recentsSurviveARestartAndKeepUnavailableRows() = runBlocking {
        val dir = File(root, "recents").apply { mkdirs() }
        val a = DocRef(uri)
        val b = DocRef("content://com.example.provider/document/2")
        val first = StoreRecentsRepository({ RecentsStore(dir, streams) }, this, Dispatchers.Unconfined)
        first.add(a, "a.pdf")
        first.add(b, "b.pdf")
        first.markUnavailable(a)

        val second = StoreRecentsRepository({ RecentsStore(dir, streams) }, this, Dispatchers.Unconfined)
        val rows = second.observe().first { it.isNotEmpty() }
        assertEquals(listOf(b, a), rows.map { it.doc })
        assertEquals(listOf("b.pdf", "a.pdf"), rows.map { it.name })
        assertEquals(listOf(true, false), rows.map { it.available })

        second.add(a, "a.pdf") // a successful open makes it available again, at the top
        assertEquals(listOf(a to true, b to true), second.observe().first().map { it.doc to it.available })
        second.remove(b)
        assertEquals(listOf(a), second.observe().first().map { it.doc })
        second.clear()
        assertEquals(emptyList(), second.observe().first())
    }
}
