package app.pdfa.storage

import java.io.File
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest

class AutosaveJournalTest : AutosaveTestBase() {

    @Test
    fun burstOfEditsCoalescesToOneWrite() = runTest {
        val w = ws(root())
        val doc = w.importDoc(uri, pdfBytes(1))
        val j = journal(w)
        var calls = 0
        repeat(5) { i ->
            j.onEdited(doc.docId) {
                calls++
                pdfBytes(10 + i)
            }
            advanceTimeBy(100)
        }
        advanceUntilIdle()
        assertEquals(1, calls)
        assertIs<AutosaveEvent.Saved>(j.lastEvent(doc.docId))
    }

    @Test
    fun onlyTheLastProviderInTheWindowRuns() = runTest {
        val w = ws(root())
        val doc = w.importDoc(uri, pdfBytes(1))
        val j = journal(w)
        j.onEdited(doc.docId) { pdfBytes(100) }
        advanceTimeBy(500)
        j.onEdited(doc.docId) { pdfBytes(200) }
        advanceUntilIdle()
        assertContentEquals(pdfBytes(200), pdfOf(w.dirOf(doc.docId)).readBytes())
    }

    @Test
    fun editsSeparatedByTheWindowWriteInOrder() = runTest {
        val w = ws(root())
        val doc = w.importDoc(uri, pdfBytes(1))
        val j = journal(w)
        j.onEdited(doc.docId) { pdfBytes(1) }
        advanceTimeBy(2_500)
        j.onEdited(doc.docId) { pdfBytes(2) }
        advanceUntilIdle()
        assertContentEquals(pdfBytes(2), pdfOf(w.dirOf(doc.docId)).readBytes())
        assertIs<AutosaveEvent.Saved>(j.lastEvent(doc.docId))
    }

    @Test
    fun successWritesPdfAndRecordWithTheExpectedFields() = runTest {
        val w = ws(root())
        val doc = w.importDoc(uri, pdfBytes(1))
        val j = journal(w)
        val bytes = pdfBytes(5)
        val event = j.save(doc.docId, bytes)
        val saved = assertIs<AutosaveEvent.Saved>(event)
        val record = assertNotNull(AutosaveRecord.parse(File(w.dirOf(doc.docId), AutosaveLayout.RECORD).readText()))
        assertEquals(doc.manifest.sha256, record.current.sourceManifestSha256)
        assertEquals(bytes.size.toLong(), record.current.size)
        assertEquals(sha256Hex(bytes), record.current.sha256)
        assertEquals(saved.savedAtEpochMs, record.current.savedAtEpochMs)
        assertEquals(1L, record.current.generation)
        assertNull(record.previous)
    }

    @Test
    fun commitStepsRunInTheDocumentedOrder() = runTest {
        val w = ws(root())
        val doc = w.importDoc(uri, pdfBytes(1))
        val points = mutableListOf<String>()
        val j = journal(w, hook = { points += it })
        j.save(doc.docId, pdfBytes(2))
        assertEquals(
            listOf("gen-temp-written", "gen-renamed", "record-temp-written", "record-renamed", "old-generation-deleted"),
            points,
        )
    }

    @Test
    fun noTempFileIsLeftAfterASuccessfulWrite() = runTest {
        val w = ws(root())
        val doc = w.importDoc(uri, pdfBytes(1))
        journal(w).save(doc.docId, pdfBytes(2))
        assertFalse(File(w.dirOf(doc.docId), AutosaveLayout.TMP).exists())
    }

    @Test
    fun atMostOneWriteIsInFlightPerDocument() = runTest {
        val w = ws(root())
        val doc = w.importDoc(uri, pdfBytes(1))
        val j = journal(w)
        val inFlight = AtomicInteger(0)
        val maxInFlight = AtomicInteger(0)
        val provider: suspend (Int) -> suspend () -> ByteArray = { seed ->
            {
                maxInFlight.accumulateAndGet(inFlight.incrementAndGet()) { a, b -> maxOf(a, b) }
                delay(3_000)
                inFlight.decrementAndGet()
                pdfBytes(seed)
            }
        }
        j.onEdited(doc.docId, provider(1))
        advanceTimeBy(2_500)
        j.onEdited(doc.docId, provider(2))
        advanceUntilIdle()
        assertEquals(1, maxInFlight.get())
    }

    @Test
    fun editDuringAWriteQueuesOneMoreWriteWithTheNewestBytes() = runTest {
        val w = ws(root())
        val doc = w.importDoc(uri, pdfBytes(1))
        val j = journal(w)
        var runs = 0
        val slow: suspend (Int) -> suspend () -> ByteArray = { seed ->
            {
                runs++
                delay(3_000)
                pdfBytes(seed)
            }
        }
        j.onEdited(doc.docId, slow(1))
        advanceTimeBy(2_500)
        j.onEdited(doc.docId, slow(2))
        advanceTimeBy(1_500)
        j.onEdited(doc.docId, slow(3))
        advanceUntilIdle()
        assertEquals(2, runs)
        assertContentEquals(pdfBytes(3), pdfOf(w.dirOf(doc.docId)).readBytes())
    }

    @Test
    fun writeFailureIsReportedNotThrown() = runTest {
        val w = ws(root())
        val doc = w.importDoc(uri, pdfBytes(1))
        val fs = FaultFs().apply { failWriteOn = AutosaveLayout.TMP }
        val j = journal(w, fs = fs)
        val event = j.save(doc.docId, pdfBytes(2))
        val failed = assertIs<AutosaveEvent.Failed>(event)
        assertEquals(AutosaveFailure.WRITE_FAILED, failed.reason)
        assertEquals(failed, j.lastEvent(doc.docId))
        assertFalse(pdfOf(w.dirOf(doc.docId)).exists())
    }

    @Test
    fun diskFullKeepsThePreviousAutosaveIntact() = runTest {
        val w = ws(root())
        val doc = w.importDoc(uri, pdfBytes(1))
        val fs = FaultFs()
        val j = journal(w, fs = fs)
        j.save(doc.docId, pdfBytes(2))
        fs.noSpaceOn = AutosaveLayout.TMP
        val failed = assertIs<AutosaveEvent.Failed>(j.save(doc.docId, pdfBytes(3)))
        assertEquals(AutosaveFailure.NO_SPACE, failed.reason)
        val scanner = recoveryScanner(w)
        assertContentEquals(pdfBytes(2), readVerifiedAutosave(w.dirOf(doc.docId)))
        assertEquals(1, scanner.scan().size)
    }

    @Test
    fun diskFullLeavesNoTempFileBehind() = runTest {
        val w = ws(root())
        val doc = w.importDoc(uri, pdfBytes(1))
        val fs = FaultFs().apply { noSpaceOn = AutosaveLayout.TMP }
        journal(w, fs = fs).save(doc.docId, pdfBytes(2))
        assertFalse(File(w.dirOf(doc.docId), AutosaveLayout.TMP).exists())
        assertFalse(pdfOf(w.dirOf(doc.docId)).exists())
    }

    @Test
    fun recordNotCommittedKeepsThePreviousAutosaveCurrent() = runTest {
        val w = ws(root())
        val doc = w.importDoc(uri, pdfBytes(1))
        val fs = FaultFs()
        val j = journal(w, fs = fs)
        j.save(doc.docId, pdfBytes(2))
        // Writes to autosave.tmp so far: 2 (generation 1 and its record). The next save writes generation 2 (3), then the record (4).
        fs.failTmpWriteNumber = 4
        val failed = assertIs<AutosaveEvent.Failed>(j.save(doc.docId, pdfBytes(3)))
        assertEquals(AutosaveFailure.RECORD_NOT_COMMITTED, failed.reason)
        // The record still names generation 1, so the older autosave is the one recovered.
        val recovered = assertIs<RecoveryCandidate.Recoverable>(recoveryScanner(w).scanAll().single())
        assertEquals(1L, recovered.doc.generation)
        assertContentEquals(pdfBytes(2), readVerifiedAutosave(w.dirOf(doc.docId)))
        // The uncommitted generation 2 is an orphan. Sweep removes it.
        assertTrue(generationFile(w.dirOf(doc.docId), 2).exists())
        assertEquals(1, recoveryActions(w, j).sweepOldAutosaves(maxAgeMs = Long.MAX_VALUE))
        assertFalse(generationFile(w.dirOf(doc.docId), 2).exists())
    }

    @Test
    fun providerFailureIsReportedAndTheJournalKeepsWorking() = runTest {
        val w = ws(root())
        val doc = w.importDoc(uri, pdfBytes(1))
        val j = journal(w)
        j.onEdited(doc.docId) { throw IllegalStateException("engine") }
        val failed = assertIs<AutosaveEvent.Failed>(j.flush(doc.docId))
        assertEquals(AutosaveFailure.PROVIDER_FAILED, failed.reason)
        assertEquals("IllegalStateException", failed.detail)
        assertIs<AutosaveEvent.Saved>(j.save(doc.docId, pdfBytes(4)))
    }

    @Test
    fun tooLargeBytesAreRefusedBeforeAnyWrite() = runTest {
        val w = ws(root())
        val doc = w.importDoc(uri, pdfBytes(1))
        val j = journal(w, maxBytes = 1_000)
        val failed = assertIs<AutosaveEvent.Failed>(j.save(doc.docId, pdfBytes(2, size = 2_000)))
        assertEquals(AutosaveFailure.TOO_LARGE, failed.reason)
        assertFalse(pdfOf(w.dirOf(doc.docId)).exists())
    }

    @Test
    fun flushWritesWithoutWaitingForTheWindow() = runTest {
        val w = ws(root())
        val doc = w.importDoc(uri, pdfBytes(1))
        var calls = 0
        val j = journal(w)
        j.onEdited(doc.docId) {
            calls++
            pdfBytes(2)
        }
        assertIs<AutosaveEvent.Saved>(j.flush(doc.docId))
        assertEquals(1, calls)
    }

    @Test
    fun flushCancelsTheDebouncedRequestSoItRunsOnce() = runTest {
        val w = ws(root())
        val doc = w.importDoc(uri, pdfBytes(1))
        var calls = 0
        val j = journal(w)
        j.onEdited(doc.docId) {
            calls++
            pdfBytes(2)
        }
        j.flush(doc.docId)
        advanceUntilIdle()
        assertEquals(1, calls)
    }

    @Test
    fun flushWithNothingPendingReturnsNull() = runTest {
        val w = ws(root())
        val doc = w.importDoc(uri, pdfBytes(1))
        assertNull(journal(w).flush(doc.docId))
    }

    @Test
    fun cancellingTheScopeDropsADebouncedRequestThatHasNotStarted() = runTest {
        val w = ws(root())
        val doc = w.importDoc(uri, pdfBytes(1))
        val scope = CoroutineScope(SupervisorJob() + w.dispatcher)
        val j = journal(w, scope = scope)
        j.onEdited(doc.docId) { pdfBytes(2) }
        scope.cancel()
        advanceUntilIdle()
        assertFalse(pdfOf(w.dirOf(doc.docId)).exists())
    }

    @Test
    fun aWriteThatStartedFinishesWhenTheCallerIsCancelled() = runTest {
        val w = ws(root())
        val doc = w.importDoc(uri, pdfBytes(1))
        val j = journal(w)
        val gate = CompletableDeferred<Unit>()
        j.onEdited(doc.docId) {
            gate.await()
            pdfBytes(3)
        }
        val caller = launch { j.flush(doc.docId) }
        runCurrent()
        caller.cancel()
        gate.complete(Unit)
        advanceUntilIdle()
        assertContentEquals(pdfBytes(3), readVerifiedAutosave(w.dirOf(doc.docId)))
    }

    /**
     * A crash at each commit step of the second save. Before the record is renamed the first autosave (A) must
     * still be the recovered one. From the record rename on, the second (B) is. Never zero. A fresh journal then
     * saves again over the leftovers.
     */
    @Test
    fun aCrashAtEveryCommitStepKeepsAValidAutosave() = runTest {
        val steps = listOf(
            "gen-temp-written" to "A",
            "gen-renamed" to "A",
            "record-temp-written" to "A",
            "record-renamed" to "B",
            "old-generation-deleted" to "B",
        )
        for ((step, expected) in steps) {
            val w = ws(File(tmp.root, "ws-$step"))
            val doc = w.importDoc(uri, pdfBytes(1))
            val dir = w.dirOf(doc.docId)
            var crashAt: String? = null
            val j = journal(w, hook = { if (it == crashAt) throw AutosaveCrash(it) })
            assertIs<AutosaveEvent.Saved>(j.save(doc.docId, pdfBytes(2))) // A
            crashAt = step
            assertFailsWith<AutosaveCrash> { j.save(doc.docId, pdfBytes(3)) } // B

            // A fresh scanner stands in for the restarted process.
            val recovered = assertIs<RecoveryCandidate.Recoverable>(recoveryScanner(w).scanAll().single())
            assertFalse(recovered.doc.usedFallback, "step $step")
            assertContentEquals(if (expected == "A") pdfBytes(2) else pdfBytes(3), readVerifiedAutosave(dir), "step $step")

            // A fresh journal saves again, over whatever the crash left (including a stale autosave.tmp).
            crashAt = null
            assertIs<AutosaveEvent.Saved>(journal(w).save(doc.docId, pdfBytes(4)), "step $step")
            assertContentEquals(pdfBytes(4), readVerifiedAutosave(dir), "step $step")
        }
    }

    @Test
    fun aCorruptNewestGenerationFallsBackToThePreviousValidOne() = runTest {
        val w = ws(root())
        val doc = w.importDoc(uri, pdfBytes(1))
        val j = journal(w)
        j.save(doc.docId, pdfBytes(2))
        j.save(doc.docId, pdfBytes(3))
        val newest = pdfOf(w.dirOf(doc.docId))
        val bytes = newest.readBytes()
        bytes[9] = (bytes[9].toInt() xor 0x10).toByte()
        newest.writeBytes(bytes)
        val recovered = assertIs<RecoveryCandidate.Recoverable>(recoveryScanner(w).scanAll().single())
        assertTrue(recovered.doc.usedFallback)
        assertEquals(1L, recovered.doc.generation)
        assertContentEquals(pdfBytes(2), readVerifiedAutosave(w.dirOf(doc.docId)))
    }

    @Test
    fun whenBothGenerationsAreCorruptTheDocumentIsNotRecoverable() = runTest {
        val w = ws(root())
        val doc = w.importDoc(uri, pdfBytes(1))
        val j = journal(w)
        j.save(doc.docId, pdfBytes(2))
        j.save(doc.docId, pdfBytes(3))
        for (g in generationNumbers(w.dirOf(doc.docId))) {
            generationFile(w.dirOf(doc.docId), g).writeBytes(pdfBytes(9))
        }
        val verdict = assertIs<RecoveryCandidate.NotRecoverable>(recoveryScanner(w).scanAll().single())
        assertEquals(NotRecoverableReason.PDF_HASH_MISMATCH, verdict.reason)
    }

    @Test
    fun orphanGenerationsAreIgnoredAndSweptNeverRecovered() = runTest {
        val w = ws(root())
        val doc = w.importDoc(uri, pdfBytes(1))
        val dir = w.dirOf(doc.docId)
        var crashAt: String? = null
        val j = journal(w, hook = { if (it == crashAt) throw AutosaveCrash(it) })
        j.save(doc.docId, pdfBytes(2))
        crashAt = "gen-renamed"
        assertFailsWith<AutosaveCrash> { j.save(doc.docId, pdfBytes(3)) }
        assertEquals(listOf(1L, 2L), generationNumbers(dir).sorted())
        assertContentEquals(pdfBytes(2), readVerifiedAutosave(dir))
        assertEquals(1, recoveryActions(w, journal(w)).sweepOldAutosaves(maxAgeMs = Long.MAX_VALUE))
        assertEquals(listOf(1L), generationNumbers(dir))
        assertContentEquals(pdfBytes(2), readVerifiedAutosave(dir))
    }

    @Test
    fun twoQuickCrashesInARowStillLeaveARecoverableState() = runTest {
        val w = ws(root())
        val doc = w.importDoc(uri, pdfBytes(1))
        val dir = w.dirOf(doc.docId)
        var crashAt: String? = null
        val j = journal(w, hook = { if (it == crashAt) throw AutosaveCrash(it) })
        j.save(doc.docId, pdfBytes(2)) // generation 1
        crashAt = "record-renamed"
        assertFailsWith<AutosaveCrash> { j.save(doc.docId, pdfBytes(3)) } // generation 2 is current
        crashAt = "gen-renamed"
        assertFailsWith<AutosaveCrash> { j.save(doc.docId, pdfBytes(4)) } // generation 3 is an orphan
        assertContentEquals(pdfBytes(3), readVerifiedAutosave(dir))

        crashAt = null
        assertIs<AutosaveEvent.Saved>(journal(w).save(doc.docId, pdfBytes(5)))
        assertContentEquals(pdfBytes(5), readVerifiedAutosave(dir))
        val record = assertNotNull(readRecord(dir))
        assertEquals(4L, record.current.generation)
        assertEquals(2L, record.previous?.generation)
    }

    @Test
    fun removedDocumentDirectoryIsReportedAsUnavailable() = runTest {
        val w = ws(root())
        val doc = w.importDoc(uri, pdfBytes(1))
        w.dirOf(doc.docId).deleteRecursively()
        val failed = assertIs<AutosaveEvent.Failed>(journal(w).save(doc.docId, pdfBytes(2)))
        assertEquals(AutosaveFailure.DOCUMENT_UNAVAILABLE, failed.reason)
    }

    @Test
    fun manyConcurrentEditsCoalesceIntoOneWriteWithTheLastBytes() = runTest {
        val w = ws(root())
        val doc = w.importDoc(uri, pdfBytes(1))
        val j = journal(w)
        var calls = 0
        repeat(20) { i ->
            launch { j.onEdited(doc.docId) { calls++; pdfBytes(50 + i) } }
        }
        advanceUntilIdle()
        assertEquals(1, calls)
        assertIs<AutosaveEvent.Saved>(j.lastEvent(doc.docId))
    }

    @Test
    fun eventsFlowDeliversEachAttempt() = runTest {
        val w = ws(root())
        val doc = w.importDoc(uri, pdfBytes(1))
        val j = journal(w)
        val seen = mutableListOf<AutosaveEvent>()
        backgroundScope.launch(start = CoroutineStart.UNDISPATCHED) { j.events.toList(seen) }
        runCurrent()
        j.save(doc.docId, pdfBytes(2))
        j.save(doc.docId, pdfBytes(3))
        runCurrent()
        assertEquals(2, seen.size)
        assertTrue(seen.all { it is AutosaveEvent.Saved })
    }

    @Test
    fun invalidDocumentIdIsRejected() = runTest {
        val w = ws(root())
        assertFailsWith<IllegalArgumentException> { journal(w).onEdited("../escape") { pdfBytes(1) } }
    }
}
