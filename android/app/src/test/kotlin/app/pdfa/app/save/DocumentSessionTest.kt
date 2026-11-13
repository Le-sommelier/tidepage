package app.pdfa.app.save

import app.pdfa.engine.DocHandle
import app.pdfa.engine.EditState
import app.pdfa.engine.EngineApi
import app.pdfa.engine.EngineError
import app.pdfa.engine.fake.FakeEngine
import app.pdfa.storage.AutosaveJournal
import app.pdfa.storage.AutosaveRecoverySource
import app.pdfa.storage.CopyResult
import app.pdfa.storage.OutputStreamWithSync
import app.pdfa.storage.RecoveryActions
import app.pdfa.storage.RecoveryScanner
import app.pdfa.storage.SafWriter
import app.pdfa.storage.SaveResult
import app.pdfa.storage.SourceStat
import app.pdfa.storage.UriStreams
import app.pdfa.storage.WorkspaceStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.runBlocking
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

private const val PREFIX = "%PDF-1.4\n"

/** The fake engine's files do not start with `%PDF-`; :storage insists on it. This adds and strips the header. */
private open class PdfShaped(val fake: FakeEngine = FakeEngine()) : EngineApi by fake {
    override suspend fun open(bytes: ByteArray, password: String?): DocHandle =
        fake.open(bytes.copyOfRange(PREFIX.length, bytes.size), password)

    override suspend fun saveIncremental(handle: DocHandle): ByteArray = PREFIX.toByteArray() + fake.saveIncremental(handle)
    override suspend fun saveFull(handle: DocHandle): ByteArray = PREFIX.toByteArray() + fake.saveFull(handle)
}

/** In-memory documents with switchable failures. */
private class MemStreams : UriStreams {
    val docs = HashMap<String, ByteArray>()
    var failWrites = false
    var failOpenInputAfter: Int? = null
    var opens = 0
    var noIdentity = false
    /** This URI is a second address of the source document. */
    var aliasOfSource: String? = null
    private fun key(uri: String): String = if (uri == aliasOfSource) "content://provider/document/source" else uri

    override fun openInput(uri: String): InputStream {
        opens++
        failOpenInputAfter?.let { if (opens > it) throw IOException("read refused") }
        return ByteArrayInputStream(docs[key(uri)] ?: throw IOException("no such document"))
    }

    override fun openOutputTruncating(raw: String): OutputStreamWithSync {
        val uri = key(raw)
        docs[uri] = ByteArray(0)
        val buffer = ByteArrayOutputStream()
        return object : OutputStreamWithSync() {
            override fun write(b: Int) = write(byteArrayOf(b.toByte()), 0, 1)
            override fun write(b: ByteArray, off: Int, len: Int) {
                if (failWrites) {
                    // Half of the chunk lands, like a provider that dies mid-write.
                    buffer.write(b, off, len / 2)
                    docs[uri] = buffer.toByteArray()
                    throw IOException("write refused")
                }
                buffer.write(b, off, len)
                docs[uri] = buffer.toByteArray()
            }
            override fun sync() = Unit
        }
    }

    override fun stat(uri: String): SourceStat =
        SourceStat((docs[key(uri)] ?: throw IOException("no such document")).size.toLong(), null, "doc.pdf")

    override fun documentKey(uri: String): String? = if (noIdentity) null else uri
}

class DocumentSessionTest {
    private val root: File = Files.createTempDirectory("pdfa-save").toFile()
    private val source = "content://provider/document/source"
    private val target = "content://provider/document/copy"
    private val streams = MemStreams()
    private val store = WorkspaceStore(root, Dispatchers.Unconfined)
    private var writer = SafWriter()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val journal = AutosaveJournal(store, scope)
    private var engine: PdfShaped = PdfShaped()

    private fun original(): ByteArray = PREFIX.toByteArray() + FakeEngine.fakePdf(pageCount = 3, text = listOf("one", "two", "three"))

    private suspend fun open(engineOverride: EngineApi? = null, restored: Boolean = false): DocumentSession {
        if (source !in streams.docs) streams.docs[source] = original()
        val doc = store.importFrom(source, streams)
        return sessionFor(doc.docId, engineOverride, restored)
    }

    private suspend fun sessionFor(docId: String, engineOverride: EngineApi? = null, restored: Boolean = false): DocumentSession {
        val doc = store.load(docId)
        val e = engineOverride ?: engine
        // Like the app: a restored session opens the recovered autosave, not the working copy.
        val autosave = if (restored) AutosaveRecoverySource(store, RecoveryScanner(root, writer)).latestBytes(docId) else null
        val handle = e.open(autosave ?: doc.file.readBytes(), null)
        val recovery = RecoveryActions(root, store, journal, writer)
        return DocumentSession(e, handle, doc, writer, streams, journal, recovery, e.state(handle), restored, Dispatchers.Unconfined)
            .also { it.refreshPending() }
    }

    private suspend fun pagesOf(bytes: ByteArray): Int {
        val e = PdfShaped()
        return e.pageInfos(e.open(bytes, null)).size
    }

    private suspend fun DocumentSession.deletePage() = edit { delete(it, listOf(0)) }

    private val saved = SaveOutcome.Saved(snapshotKept = false, warning = false)

    // ---- editing ----

    @Test
    fun anEditMakesTheDocumentDirtyAndUndoable() = runBlocking<Unit> {
        val s = open()
        assertEquals(SessionState(pageCount = 3), s.state.value)

        s.deletePage()
        assertTrue(s.state.value.dirty)
        assertTrue(s.state.value.canUndo)
        assertEquals(2, s.state.value.pageCount)
        assertEquals(1, s.state.value.revision)

        s.undo()
        assertEquals(3, s.state.value.pageCount)
        assertTrue(s.state.value.canRedo)
        s.redo()
        assertEquals(2, s.state.value.pageCount)
        assertEquals(3, s.state.value.revision)
    }

    @Test
    fun aRefusedEditThrowsAndLeavesTheStateTrue() = runBlocking<Unit> {
        val s = open()
        val error = runCatching { s.edit { delete(it, listOf(0, 1, 2)) } }.exceptionOrNull()
        assertIs<EngineError>(error)
        assertFalse(s.state.value.dirty)
        assertEquals(3, s.state.value.pageCount)
    }

    @Test
    fun editsAreAutosavedAndRecoverableAfterAProcessDeath() = runBlocking<Unit> {
        val s = open()
        s.deletePage()
        s.flush()

        // A new process: fresh objects over the same directory.
        val found = RecoveryScanner(root, SafWriter()).scan().single()
        assertEquals(s.docId, found.docId)
        val bytes = RecoveryActions(root, store, AutosaveJournal(store, scope), SafWriter()).restore(found.docId)
        assertEquals(2, pagesOf(bytes))
        assertContentEquals(original(), streams.docs[source], "the user's file is untouched by autosave")
    }

    // ---- save a copy ----

    @Test
    fun saveACopyWritesANewFileAndLeavesTheOriginalAlone() = runBlocking<Unit> {
        val s = open()
        s.deletePage()
        s.flush()

        assertEquals(SaveOutcome.CopySaved, s.saveCopy(target))

        assertEquals(2, pagesOf(streams.docs.getValue(target)))
        assertContentEquals(original(), streams.docs[source])
        assertFalse(s.state.value.dirty, "confirmSaved ran")
        assertFalse(s.state.value.saving)
        assertTrue(RecoveryScanner(root, writer).scan().isEmpty(), "the work is in the copy: no autosave is offered later")
    }

    @Test
    fun saveACopyOntoTheDocumentItselfIsRefused() = runBlocking<Unit> {
        val s = open()
        s.deletePage()
        assertEquals(SaveOutcome.CopyTargetRefused, s.saveCopy(source))
        assertTrue(s.state.value.dirty)
        assertContentEquals(original(), streams.docs[source])

    }

    @Test
    fun saveACopyWorksWhenTheDocumentHasNoIdentity() = runBlocking<Unit> {
        // Documents opened from a file manager or a mail app: the provider gives no identity to compare.
        streams.noIdentity = true
        val s = open()
        s.deletePage()
        assertEquals(SaveOutcome.CopySaved, s.saveCopy(target))
        assertEquals(2, pagesOf(streams.docs.getValue(target)))
        assertContentEquals(original(), streams.docs[source], "the original is unchanged")
        assertFalse(s.state.value.dirty)
    }

    @Test
    fun saveACopyOntoAnotherAddressOfTheOriginalIsReportedAsReplacingIt() = runBlocking<Unit> {
        streams.noIdentity = true
        streams.aliasOfSource = target
        val s = open()
        s.deletePage()
        assertEquals(saved, s.saveCopy(target), "never 'copy saved, original unchanged'")
        assertEquals(2, pagesOf(streams.docs.getValue(source)))
        assertFalse(s.state.value.dirty)
        // The record follows the file: a later save is not a conflict.
        s.deletePage()
        assertEquals(saved, s.saveToOriginal())
        assertEquals(1, pagesOf(streams.docs.getValue(source)))
    }

    @Test
    fun aFailedCopyIsNeverReportedAsSaved() = runBlocking<Unit> {
        val s = open()
        s.deletePage()
        streams.failWrites = true
        val outcome = s.saveCopy(target)
        assertEquals(SaveOutcome.CopyTargetFailed(targetMayBeTruncated = true), outcome)
        assertEquals(listOf(SaveAction.SAVE_COPY), outcome.actions)
        assertTrue(s.state.value.dirty)

        streams.failWrites = false
        assertEquals(SaveOutcome.CopySaved, s.saveCopy("content://provider/document/elsewhere"))
        assertFalse(s.state.value.dirty)
    }

    // ---- replace original ----

    @Test
    fun replaceOriginalWritesBackAndASecondSaveIsNotAConflict() = runBlocking<Unit> {
        val s = open()
        s.deletePage()
        s.flush()
        assertEquals(saved, s.saveToOriginal())
        assertEquals(2, pagesOf(streams.docs.getValue(source)))
        assertFalse(s.state.value.dirty)
        assertFalse(s.state.value.pendingCopy)
        assertTrue(RecoveryScanner(root, writer).scan().isEmpty())

        s.deletePage()
        assertEquals(saved, s.saveToOriginal())
        assertEquals(1, pagesOf(streams.docs.getValue(source)))
    }

    @Test
    fun aFileChangedElsewhereIsAConflictUntilTheUserChoosesOverwrite() = runBlocking<Unit> {
        val s = open()
        s.deletePage()
        val external = PREFIX.toByteArray() + FakeEngine.fakePdf(pageCount = 7)
        streams.docs[source] = external

        val outcome = s.saveToOriginal()
        assertEquals(SaveOutcome.Conflict(sourceMayBeTruncated = false, stagingKept = false), outcome)
        assertEquals(listOf(SaveAction.SAVE_COPY, SaveAction.OVERWRITE), outcome.actions)
        assertContentEquals(external, streams.docs[source], "nothing was written")
        assertTrue(s.state.value.dirty)

        assertEquals(SaveOutcome.Saved(snapshotKept = true, warning = false), s.saveToOriginal(overwrite = true))
        assertEquals(2, pagesOf(streams.docs.getValue(source)))
        val snapshot = s.doc.dir.listFiles()!!.single { it.name.startsWith("external-") }
        assertContentEquals(external, snapshot.readBytes(), "the other version is kept")
        assertFalse(s.state.value.dirty)
    }

    @Test
    fun aFailedWriteKeepsTheChangesStagedAndSurvivesAProcessDeath() = runBlocking<Unit> {
        val s = open()
        s.deletePage()
        s.flush()
        streams.failWrites = true

        val outcome = s.saveToOriginal()
        assertEquals(SaveOutcome.CopyFailed(sourceMayBeTruncated = true, stagingKept = true), outcome)
        assertEquals(listOf(SaveAction.RETRY, SaveAction.EXPORT_PENDING), outcome.actions)
        assertTrue(s.state.value.dirty)
        assertTrue(s.state.value.pendingCopy)
        assertFalse(s.discardChanges(), "discard is refused while the staged bytes may be the only full copy")
        assertTrue(File(s.doc.dir, "staging.pdf").isFile)

        // Save a copy elsewhere works meanwhile and does not end the pending state.
        streams.failWrites = false
        assertEquals(SaveOutcome.CopySaved, s.exportPending(target))
        assertEquals(2, pagesOf(streams.docs.getValue(target)))
        assertTrue(s.state.value.pendingCopy)
        assertTrue(s.state.value.dirty)

        // Process death, then a retry from the new process finishes the same save.
        writer = SafWriter()
        engine = PdfShaped()
        val again = sessionFor(s.docId, restored = true)
        assertTrue(again.state.value.pendingCopy)
        assertTrue(again.state.value.dirty)
        assertEquals(saved, again.retryPending())
        assertEquals(2, pagesOf(streams.docs.getValue(source)))
        assertFalse(again.state.value.pendingCopy)
        assertFalse(again.state.value.dirty)
        assertFalse(File(again.doc.dir, "staging.pdf").exists())
    }

    @Test
    fun aRetryAfterFurtherEditsSavesThoseEditsToo() = runBlocking<Unit> {
        val s = open()
        s.deletePage()
        streams.failWrites = true
        assertIs<SaveOutcome.CopyFailed>(s.saveToOriginal())
        assertTrue(s.state.value.pendingCopy)

        // The user keeps working, then taps Retry: the staged bytes are now older than the document.
        streams.failWrites = false
        s.deletePage()
        assertEquals(saved, s.retryPending())
        assertEquals(1, pagesOf(streams.docs.getValue(source)), "the file holds the edit made after the failed save")
        assertFalse(s.state.value.dirty)
        assertFalse(s.state.value.pendingCopy)
    }

    @Test
    fun aWriteThatCannotBeReadBackIsUnverifiable() = runBlocking<Unit> {
        val s = open()
        s.deletePage()
        streams.opens = 0
        streams.failOpenInputAfter = 2 // the two conflict checks pass, the read-back does not
        val outcome = s.saveToOriginal()
        assertIs<SaveOutcome.Unverifiable>(outcome)
        assertTrue(outcome.sourceMayBeTruncated)
        assertTrue(outcome.stagingKept)
        assertTrue(s.state.value.dirty)
        assertTrue(s.state.value.pendingCopy)

        streams.failOpenInputAfter = null
        assertEquals(saved, s.retryPending())
        assertFalse(s.state.value.dirty)
    }

    @Test
    fun anUnreadableSourceIsARetryableIoFailure() = runBlocking<Unit> {
        val s = open()
        s.deletePage()
        streams.opens = 0
        streams.failOpenInputAfter = 0
        val outcome = s.saveToOriginal()
        assertEquals(SaveOutcome.Io(sourceMayBeTruncated = false, stagingKept = false), outcome)
        assertTrue(SaveAction.RETRY in outcome.actions)
        assertTrue(s.state.value.dirty)

        streams.failOpenInputAfter = null
        assertIs<SaveOutcome.Saved>(s.retryPending(), "a retry with nothing staged is a fresh save")
    }

    @Test
    fun aDocumentAboveTheSaveLimitIsTooLarge() = runBlocking<Unit> {
        writer = SafWriter(maxInMemoryBytes = 16)
        val s = open()
        s.deletePage()
        assertEquals(SaveOutcome.TooLarge(sourceMayBeTruncated = false, stagingKept = false), s.saveToOriginal())
        assertEquals(SaveOutcome.TooLarge(sourceMayBeTruncated = false, stagingKept = false), s.saveCopy(target))
        assertTrue(s.state.value.dirty)
        assertContentEquals(original(), streams.docs[source])
    }

    @Test
    fun bytesTheEngineCannotReopenAreNotWritten() = runBlocking<Unit> {
        var opens = 0
        val picky = object : PdfShaped() {
            override suspend fun open(bytes: ByteArray, password: String?): DocHandle {
                if (opens++ > 0) throw EngineError.InvalidPdf("damaged")
                return super.open(bytes, password)
            }
        }
        val s = open(picky)
        s.deletePage()
        assertEquals(SaveOutcome.VerificationFailed(sourceMayBeTruncated = false, stagingKept = false), s.saveToOriginal())
        assertEquals(SaveOutcome.VerificationFailed(sourceMayBeTruncated = false, stagingKept = false), s.saveCopy(target))
        assertContentEquals(original(), streams.docs[source])
        assertNull(streams.docs[target])
        assertTrue(s.state.value.dirty)
    }

    @Test
    fun aPageCountThatDiffersAfterReopeningFailsVerification() = runBlocking<Unit> {
        val lossy = object : PdfShaped() {
            override suspend fun saveIncremental(handle: DocHandle): ByteArray = original()
        }
        val s = open(lossy)
        s.deletePage() // 2 pages now, but the produced bytes still hold 3
        assertIs<SaveOutcome.VerificationFailed>(s.saveToOriginal())
        assertTrue(s.state.value.dirty)
    }

    @Test
    fun savedIsNotShownWhenTheEngineCannotConfirm() = runBlocking<Unit> {
        val flaky = object : PdfShaped() {
            override suspend fun confirmSaved(handle: DocHandle): EditState = throw EngineError.EngineDied("gone")
        }
        val s = open(flaky)
        s.deletePage()
        assertEquals(SaveOutcome.SavedUnconfirmed, s.saveToOriginal())
        assertTrue(s.state.value.dirty, "dirty stays until confirmSaved succeeds")
        assertEquals(SaveOutcome.SavedUnconfirmed, s.saveCopy(target))
        assertTrue(s.state.value.dirty)
    }

    @Test
    fun engineFailuresWhileProducingBytesWriteNothing() = runBlocking<Unit> {
        var error: EngineError = EngineError.NotPermitted("not the owner")
        val refusing = object : PdfShaped() {
            override suspend fun saveIncremental(handle: DocHandle): ByteArray = throw error
            override suspend fun saveFull(handle: DocHandle): ByteArray = throw error
        }
        val s = open(refusing)
        s.deletePage()
        val denied = s.saveToOriginal()
        assertEquals(SaveOutcome.EngineFailed(notPermitted = true), denied)
        assertTrue(denied.actions.isEmpty())
        error = EngineError.EngineDied("gone")
        assertEquals(SaveOutcome.EngineFailed(notPermitted = false), s.saveCopy(target))
        assertContentEquals(original(), streams.docs[source])
        assertNull(streams.docs[target])
    }

    // ---- restart and recovery ----

    @Test
    fun lostEditsAreReportedOnlyWhenThereWasUnsavedWork() = runBlocking<Unit> {
        val clean = open()
        clean.onEditsLost(originalRestored = true)
        assertNull(clean.state.value.editsLost)
        assertEquals(1, clean.state.value.revision, "the viewer still redraws")

        val s = open()
        s.deletePage()
        s.onEditsLost(originalRestored = false)
        assertEquals(EditsLostNotice(originalRestored = false), s.state.value.editsLost)
        assertTrue(s.state.value.dirty)
        s.dismissEditsLost()
        assertNull(s.state.value.editsLost)
    }

    @Test
    fun aRecoveredDocumentIsUnsavedUntilItIsSaved() = runBlocking<Unit> {
        val s = open(restored = true)
        assertTrue(s.state.value.dirty)
        assertFalse(s.state.value.canUndo)
        assertEquals(SaveOutcome.CopySaved, s.saveCopy(target))
        assertFalse(s.state.value.dirty)
    }

    @Test
    fun discardDropsTheAutosave() = runBlocking<Unit> {
        val s = open()
        s.deletePage()
        s.flush()
        assertEquals(1, RecoveryScanner(root, writer).scan().size)
        assertTrue(s.discardChanges())
        assertTrue(RecoveryScanner(root, writer).scan().isEmpty())
        assertContentEquals(original(), streams.docs[source])
    }

    // ---- the mapping, row by row ----

    @Test
    fun everyStorageResultMapsToOneOutcome() = runBlocking<Unit> {
        val doc = open().doc
        val stat = SourceStat(1, null)
        assertEquals(saved, SaveOutcome.from(SaveResult.Saved(doc)))
        assertEquals(SaveOutcome.Saved(true, true), SaveOutcome.from(SaveResult.Saved(doc, "external-1.pdf", "sync")))
        assertEquals(SaveOutcome.SavedUnconfirmed, SaveOutcome.from(SaveResult.Saved(doc), confirmed = false))
        val stale = SaveOutcome.from(SaveResult.SavedButManifestStale(doc, "x"))
        assertEquals(SaveOutcome.SavedRecordStale, stale)
        assertEquals(listOf(SaveAction.RETRY), stale.actions)
        assertTrue(stale.stagingKept)
        assertEquals(
            SaveOutcome.Conflict(sourceMayBeTruncated = true, stagingKept = true),
            SaveOutcome.from(SaveResult.Conflict(stat, stat, "x", sourceMayBeTruncated = true, stagingKept = true)),
        )
        assertEquals(SaveOutcome.CopyFailed(true, true), SaveOutcome.from(SaveResult.CopyFailed(true, true, true, "x")))
        assertEquals(SaveOutcome.Unverifiable(true, true), SaveOutcome.from(SaveResult.Unverifiable("x", true, true)))
        val pendingVerify = SaveOutcome.from(SaveResult.VerificationFailed("x", true, true))
        assertEquals(listOf(SaveAction.RETRY, SaveAction.EXPORT_PENDING), pendingVerify.actions)
        assertEquals(listOf(SaveAction.EXPORT_PENDING), SaveOutcome.from(SaveResult.TooLarge("x", true, true)).actions)
        assertEquals(SaveOutcome.Io(true, true), SaveOutcome.from(SaveResult.Io("x", true, true)))

        assertEquals(SaveOutcome.CopySaved, SaveOutcome.from(CopyResult.Copied(1, "h")))
        assertEquals(SaveOutcome.CopyTargetFailed(true), SaveOutcome.from(CopyResult.CopyFailed(true, true, true, "x")))
        assertEquals(SaveOutcome.CopyTargetFailed(false), SaveOutcome.from(CopyResult.Unverifiable("x", false, true)))
        assertEquals(SaveOutcome.VerificationFailed(false, false), SaveOutcome.from(CopyResult.VerificationFailed("x")))
        assertEquals(SaveOutcome.TooLarge(false, false), SaveOutcome.from(CopyResult.TooLarge("x")))
        assertEquals(SaveOutcome.CopyTargetRefused, SaveOutcome.from(CopyResult.Io("x")))
    }
}
