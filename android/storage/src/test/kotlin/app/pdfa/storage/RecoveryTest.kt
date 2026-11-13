package app.pdfa.storage

import java.io.File
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest

class RecoveryTest : AutosaveTestBase() {

    /** A staged copy of [bytes] and a marker naming [uri], as an interrupted save leaves them. */
    private fun makePending(dir: File, uri: String, staged: ByteArray) {
        File(dir, Layout.STAGING).writeBytes(staged)
        File(dir, Layout.PENDING).writeText("$uri\n${sha256Hex(staged)}")
    }

    private fun snapshotOf(dir: File): Map<String, Long> =
        dir.walkTopDown().filter { it.isFile }.associate { it.relativeTo(dir).path to it.length() }

    // ---- scanner ----

    @Test
    fun aCleanAutosaveIsRecoverableWithItsMetadata() = runTest {
        val w = ws(root())
        val doc = w.importDoc(uri, pdfBytes(1))
        val j = journal(w)
        val saved = assertIs<AutosaveEvent.Saved>(j.save(doc.docId, pdfBytes(2)))
        val found = recoveryScanner(w).scan().single()
        assertEquals(doc.docId, found.docId)
        assertEquals("report.pdf", found.displayName)
        assertEquals(saved.savedAtEpochMs, found.autosaveSavedAt)
        assertEquals(pdfBytes(2).size.toLong(), found.autosaveSize)
        assertEquals(false, found.sourceChanged)
        assertEquals(PendingStatus.NotPending, found.hasPendingCopy)
    }

    @Test
    fun aRecordMadeAgainstAnotherSourceVersionReportsSourceChanged() = runTest {
        val w = ws(root())
        val doc = w.importDoc(uri, pdfBytes(1))
        writeCurrent(w.dirOf(doc.docId), 1, pdfBytes(2), sourceSha = "0".repeat(64))
        assertEquals(true, recoveryScanner(w).scan().single().sourceChanged)
    }

    @Test
    fun aFlippedByteWithTheSameSizeIsNotRecoverable() = runTest {
        val w = ws(root())
        val doc = w.importDoc(uri, pdfBytes(1))
        journal(w).save(doc.docId, pdfBytes(2))
        val pdf = pdfOf(w.dirOf(doc.docId))
        val bytes = pdf.readBytes()
        bytes[100] = (bytes[100].toInt() xor 0x01).toByte()
        pdf.writeBytes(bytes)
        val verdict = assertIs<RecoveryCandidate.NotRecoverable>(recoveryScanner(w).scanAll().single())
        assertEquals(NotRecoverableReason.PDF_HASH_MISMATCH, verdict.reason)
        assertTrue(recoveryScanner(w).scan().isEmpty())
    }

    @Test
    fun aTruncatedAutosaveIsNotRecoverable() = runTest {
        val w = ws(root())
        val doc = w.importDoc(uri, pdfBytes(1))
        journal(w).save(doc.docId, pdfBytes(2))
        val pdf = pdfOf(w.dirOf(doc.docId))
        pdf.writeBytes(pdf.readBytes().copyOf(1000))
        val verdict = assertIs<RecoveryCandidate.NotRecoverable>(recoveryScanner(w).scanAll().single())
        assertEquals(NotRecoverableReason.PDF_SIZE_MISMATCH, verdict.reason)
    }

    @Test
    fun aMissingRecordIsNotRecoverable() = runTest {
        val w = ws(root())
        val doc = w.importDoc(uri, pdfBytes(1))
        journal(w).save(doc.docId, pdfBytes(2))
        File(w.dirOf(doc.docId), AutosaveLayout.RECORD).delete()
        val verdict = assertIs<RecoveryCandidate.NotRecoverable>(recoveryScanner(w).scanAll().single())
        assertEquals(NotRecoverableReason.RECORD_MISSING, verdict.reason)
    }

    @Test
    fun aMalformedRecordIsNotRecoverable() = runTest {
        val w = ws(root())
        val doc = w.importDoc(uri, pdfBytes(1))
        journal(w).save(doc.docId, pdfBytes(2))
        File(w.dirOf(doc.docId), AutosaveLayout.RECORD).writeText("{\"version\":1,")
        val verdict = assertIs<RecoveryCandidate.NotRecoverable>(recoveryScanner(w).scanAll().single())
        assertEquals(NotRecoverableReason.RECORD_INVALID, verdict.reason)
    }

    @Test
    fun aRecordWithAnUppercaseOrShortHashIsInvalid() = runTest {
        val w = ws(root())
        val doc = w.importDoc(uri, pdfBytes(1))
        journal(w).save(doc.docId, pdfBytes(2))
        val bytes = pdfBytes(2)
        File(w.dirOf(doc.docId), AutosaveLayout.RECORD).writeText(
            AutosaveRecord(AutosaveSlot(1L, 1L, bytes.size.toLong(), sha256Hex(bytes).uppercase(), "0".repeat(64)), null).toJson(),
        )
        val verdict = assertIs<RecoveryCandidate.NotRecoverable>(recoveryScanner(w).scanAll().single())
        assertEquals(NotRecoverableReason.RECORD_INVALID, verdict.reason)
    }

    @Test
    fun aRecordClaimingMoreThanTheSizeLimitIsInvalid() {
        val text = AutosaveRecord(AutosaveSlot(1L, 1L, MAX_DOCUMENT_BYTES + 1, "1".repeat(64), "0".repeat(64)), null).toJson()
        assertNull(AutosaveRecord.parse(text))
    }

    @Test
    fun aMissingPdfIsNotRecoverable() = runTest {
        val w = ws(root())
        val doc = w.importDoc(uri, pdfBytes(1))
        journal(w).save(doc.docId, pdfBytes(2))
        pdfOf(w.dirOf(doc.docId)).delete()
        val verdict = assertIs<RecoveryCandidate.NotRecoverable>(recoveryScanner(w).scanAll().single())
        assertEquals(NotRecoverableReason.PDF_MISSING, verdict.reason)
    }

    @Test
    fun anAutosaveWithoutAManifestIsNotRecoverable() = runTest {
        val w = ws(root())
        val doc = w.importDoc(uri, pdfBytes(1))
        journal(w).save(doc.docId, pdfBytes(2))
        File(w.dirOf(doc.docId), Layout.MANIFEST).delete()
        val verdict = assertIs<RecoveryCandidate.NotRecoverable>(recoveryScanner(w).scanAll().single())
        assertEquals(NotRecoverableReason.MANIFEST_UNREADABLE, verdict.reason)
    }

    @Test
    fun garbageInsideWorkIsIgnored() = runTest {
        val w = ws(root())
        val doc = w.importDoc(uri, pdfBytes(1))
        journal(w).save(doc.docId, pdfBytes(2))
        File(w.root, "work/stray.txt").writeText("x")
        File(w.root, "work/not-a-document").mkdirs()
        File(w.root, "work/not-a-document/autosave.pdf").writeText("junk")
        File(w.root, "work/00000000-0000-0000-0000-000000000000").mkdirs()
        File(w.root, "work/${"f".repeat(36)}").mkdirs()
        val found = recoveryScanner(w).scanAll()
        assertEquals(listOf(doc.docId), found.map { it.docId })
        assertIs<RecoveryCandidate.Recoverable>(found.single())
    }

    @Test
    fun aMissingWorkDirectoryGivesAnEmptyScan() {
        val scanner = RecoveryScanner(File(tmp.root, "absent"), SafWriter())
        assertTrue(scanner.scanAll().isEmpty())
        assertTrue(scanner.scan().isEmpty())
    }

    @Test
    fun scanningNeverChangesAnyFile() = runTest {
        val w = ws(root())
        val a = w.importDoc(uri, pdfBytes(1))
        val b = w.importDoc("content://provider/doc/43", pdfBytes(3))
        journal(w).save(a.docId, pdfBytes(2))
        generationFile(w.dirOf(b.docId), 7).writeBytes(pdfBytes(9))
        val before = snapshotOf(w.root)
        recoveryScanner(w).scanAll()
        recoveryScanner(w).scan()
        assertEquals(before, snapshotOf(w.root))
    }

    @Test
    fun aPendingCopyIsReportedButDoesNotHideTheAutosave() = runTest {
        val w = ws(root())
        val doc = w.importDoc(uri, pdfBytes(1))
        journal(w).save(doc.docId, pdfBytes(2))
        makePending(w.dirOf(doc.docId), uri, pdfBytes(4))
        val found = recoveryScanner(w).scan().single()
        assertEquals(PendingStatus.Pending, found.hasPendingCopy)
    }

    @Test
    fun anUnreadablePendingMarkerIsReportedAsUnknown() = runTest {
        val w = ws(root())
        val doc = w.importDoc(uri, pdfBytes(1))
        journal(w).save(doc.docId, pdfBytes(2))
        File(w.dirOf(doc.docId), Layout.PENDING).mkdirs()
        val found = recoveryScanner(w).scan().single()
        assertIs<PendingStatus.Unknown>(found.hasPendingCopy)
    }

    @Test
    fun aLeftoverTempFileDoesNotAffectTheVerdict() = runTest {
        val w = ws(root())
        val doc = w.importDoc(uri, pdfBytes(1))
        journal(w).save(doc.docId, pdfBytes(2))
        File(w.dirOf(doc.docId), AutosaveLayout.TMP).writeBytes(ByteArray(7))
        assertEquals(pdfBytes(2).size.toLong(), recoveryScanner(w).scan().single().autosaveSize)
    }

    @Test
    fun resultsAreOrderedByDocumentId() = runTest {
        val w = ws(root())
        val docs = listOf(
            w.importDoc("content://p/1", pdfBytes(1)),
            w.importDoc("content://p/2", pdfBytes(2)),
            w.importDoc("content://p/3", pdfBytes(3)),
        )
        val j = journal(w)
        docs.forEach { j.save(it.docId, pdfBytes(5)) }
        val ids = recoveryScanner(w).scan().map { it.docId }
        assertEquals(docs.map { it.docId }.sorted(), ids)
    }

    // ---- actions ----

    @Test
    fun restoreReturnsTheVerifiedBytes() = runTest {
        val w = ws(root())
        val doc = w.importDoc(uri, pdfBytes(1))
        val j = journal(w)
        j.save(doc.docId, pdfBytes(2))
        val restored = recoveryActions(w, j).restore(doc.docId)
        assertContentEquals(pdfBytes(2), restored)
    }

    @Test
    fun restoreRefusesACorruptAutosave() = runTest {
        val w = ws(root())
        val doc = w.importDoc(uri, pdfBytes(1))
        val j = journal(w)
        j.save(doc.docId, pdfBytes(2))
        val pdf = pdfOf(w.dirOf(doc.docId))
        val bytes = pdf.readBytes()
        bytes[7] = (bytes[7].toInt() xor 0x20).toByte()
        pdf.writeBytes(bytes)
        val failure = assertFailsWith<RecoveryException> { recoveryActions(w, j).restore(doc.docId) }
        assertEquals(NotRecoverableReason.PDF_HASH_MISMATCH, failure.reason)
    }

    @Test
    fun discardRemovesOnlyTheAutosaveFiles() = runTest {
        val w = ws(root())
        val doc = w.importDoc(uri, pdfBytes(1))
        val j = journal(w)
        j.save(doc.docId, pdfBytes(2))
        val dir = w.dirOf(doc.docId)
        val result = recoveryActions(w, j).discard(doc.docId)
        assertEquals(RemovalResult.Removed(2), result)
        assertFalse(pdfOf(dir).exists())
        assertFalse(File(dir, AutosaveLayout.RECORD).exists())
        assertTrue(File(dir, Layout.MANIFEST).isFile)
        assertTrue(File(dir, Layout.ORIGINAL).isFile)
    }

    @Test
    fun discardRefusesWhileACopyIsPendingAndTouchesNothing() = runTest {
        val w = ws(root())
        val doc = w.importDoc(uri, pdfBytes(1))
        val j = journal(w)
        j.save(doc.docId, pdfBytes(2))
        val dir = w.dirOf(doc.docId)
        makePending(dir, uri, pdfBytes(4))
        val before = snapshotOf(dir)
        assertEquals(RemovalResult.RefusedPendingCopy, recoveryActions(w, j).discard(doc.docId))
        assertEquals(before, snapshotOf(dir))
        assertContentEquals(pdfBytes(4), File(dir, Layout.STAGING).readBytes())
    }

    @Test
    fun discardRefusesWhenThePendingStateIsUnknown() = runTest {
        val w = ws(root())
        val doc = w.importDoc(uri, pdfBytes(1))
        val j = journal(w)
        j.save(doc.docId, pdfBytes(2))
        File(w.dirOf(doc.docId), Layout.PENDING).mkdirs()
        assertIs<RemovalResult.RefusedUnknownStatus>(recoveryActions(w, j).discard(doc.docId))
        assertTrue(pdfOf(w.dirOf(doc.docId)).exists())
    }

    @Test
    fun discardCancelsAPendingAutosaveSoItDoesNotComeBack() = runTest {
        val w = ws(root())
        val doc = w.importDoc(uri, pdfBytes(1))
        val j = journal(w)
        j.onEdited(doc.docId) { pdfBytes(2) }
        recoveryActions(w, j).discard(doc.docId)
        advanceUntilIdle()
        assertFalse(pdfOf(w.dirOf(doc.docId)).exists())
    }

    @Test
    fun aFailedDeleteIsReportedNotSwallowed() = runTest {
        val w = ws(root())
        val doc = w.importDoc(uri, pdfBytes(1))
        val j = journal(w)
        j.save(doc.docId, pdfBytes(2))
        val fs = FaultFs().apply { failDelete = true }
        assertIs<RemovalResult.Failed>(recoveryActions(w, j, fs).discard(doc.docId))
    }

    @Test
    fun afterASuccessfulSaveTheAutosaveIsCleared() = runTest {
        val w = ws(root())
        val doc = w.importDoc(uri, pdfBytes(1))
        val j = journal(w)
        j.save(doc.docId, pdfBytes(2))
        assertEquals(RemovalResult.Removed(2), recoveryActions(w, j).afterSuccessfulSave(doc.docId))
        assertTrue(recoveryScanner(w).scanAll().isEmpty())
    }

    @Test
    fun afterASaveThatLeftACopyPendingDoesNotClearTheAutosave() = runTest {
        val w = ws(root())
        val doc = w.importDoc(uri, pdfBytes(1))
        val j = journal(w)
        j.save(doc.docId, pdfBytes(2))
        makePending(w.dirOf(doc.docId), uri, pdfBytes(4))
        assertEquals(RemovalResult.RefusedPendingCopy, recoveryActions(w, j).afterSuccessfulSave(doc.docId))
        assertTrue(pdfOf(w.dirOf(doc.docId)).exists())
    }

    @Test
    fun aDebouncedAutosaveFromBeforeTheSaveCannotComeBackAfterIt() = runTest {
        val w = ws(root())
        val doc = w.importDoc(uri, pdfBytes(1))
        val j = journal(w)
        j.onEdited(doc.docId) { pdfBytes(2) }
        recoveryActions(w, j).afterSuccessfulSave(doc.docId)
        advanceUntilIdle()
        assertFalse(pdfOf(w.dirOf(doc.docId)).exists())
    }

    @Test
    fun sweepRemovesOldAutosavesAndKeepsFreshOnes() = runTest {
        val w = ws(root())
        val old = w.importDoc("content://p/old", pdfBytes(1))
        val fresh = w.importDoc("content://p/new", pdfBytes(2))
        val j = journal(w)
        j.save(old.docId, pdfBytes(3))
        advanceTimeBy(5_000)
        j.save(fresh.docId, pdfBytes(4))
        val swept = recoveryActions(w, j).sweepOldAutosaves(maxAgeMs = 3_000)
        assertEquals(1, swept)
        assertEquals(listOf(fresh.docId), recoveryScanner(w).scan().map { it.docId })
    }

    @Test
    fun sweepKeepsAutosavesWithAPendingCopyByDefault() = runTest {
        val w = ws(root())
        val doc = w.importDoc(uri, pdfBytes(1))
        val j = journal(w)
        j.save(doc.docId, pdfBytes(2))
        makePending(w.dirOf(doc.docId), uri, pdfBytes(4))
        advanceTimeBy(60_000)
        assertEquals(0, recoveryActions(w, j).sweepOldAutosaves(maxAgeMs = 1_000))
        assertTrue(pdfOf(w.dirOf(doc.docId)).exists())
    }

    @Test
    fun sweepWithKeepPendingFalseRemovesTheAutosaveButNeverTheStaging() = runTest {
        val w = ws(root())
        val doc = w.importDoc(uri, pdfBytes(1))
        val j = journal(w)
        j.save(doc.docId, pdfBytes(2))
        makePending(w.dirOf(doc.docId), uri, pdfBytes(4))
        advanceTimeBy(60_000)
        assertEquals(1, recoveryActions(w, j).sweepOldAutosaves(maxAgeMs = 1_000, keepPending = false))
        val dir = w.dirOf(doc.docId)
        assertFalse(pdfOf(dir).exists())
        assertContentEquals(pdfBytes(4), File(dir, Layout.STAGING).readBytes())
        assertTrue(File(dir, Layout.PENDING).isFile)
        assertTrue(File(dir, Layout.ORIGINAL).isFile)
    }

    @Test
    fun sweepDoesNotTouchDocumentsWithoutAutosaves() = runTest {
        val w = ws(root())
        val doc = w.importDoc(uri, pdfBytes(1))
        advanceTimeBy(60_000)
        assertEquals(0, recoveryActions(w, journal(w)).sweepOldAutosaves(maxAgeMs = 1))
        assertTrue(File(w.dirOf(doc.docId), Layout.ORIGINAL).isFile)
    }

    // ---- engine bridge ----

    @Test
    fun latestBytesReturnsVerifiedBytesOnlyForRecoverableDocuments() = runTest {
        val w = ws(root())
        val good = w.importDoc("content://p/good", pdfBytes(1))
        val bad = w.importDoc("content://p/bad", pdfBytes(2))
        val j = journal(w)
        j.save(good.docId, pdfBytes(5))
        j.save(bad.docId, pdfBytes(6))
        pdfOf(w.dirOf(bad.docId)).writeBytes(pdfBytes(7))
        val source = AutosaveRecoverySource(w.store, recoveryScanner(w), w.dispatcher)
        assertContentEquals(pdfBytes(5), source.latestBytes(good.docId))
        assertNull(source.latestBytes(bad.docId))
        assertNull(source.latestBytes("../escape"))
        assertNull(source.latestBytes("no-such-document"))
    }

    @Test
    fun passwordsAreNeverRecovered() {
        val w = Ws(File(tmp.root, "ws"), Dispatchers.Unconfined)
        assertNull(AutosaveRecoverySource(w.store, RecoveryScanner(w.root, w.writer)).passwordFor("any"))
    }
}
