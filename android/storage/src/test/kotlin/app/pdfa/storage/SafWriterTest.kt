package app.pdfa.storage

import java.io.File
import java.io.IOException
import java.io.SyncFailedException
import java.util.concurrent.CountDownLatch
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Rule
import org.junit.rules.TemporaryFolder

/** Simulated process death. An Error, so no `catch (Exception)` in the module can swallow it. */
class SimulatedCrash(step: String) : Error("crash at $step")

class SafWriterTest {

    @get:Rule val tmp = TemporaryFolder()

    private val uri = "content://provider/doc/7"
    private val target = "content://provider/doc/copy"
    private val alias = "content://provider/alias/7"
    private lateinit var streams: FakeUriStreams
    private lateinit var doc: ImportedDoc

    private val steps = listOf(
        "staged", "verified", "promoted", "marker", "truncated", "copied", "synced", "readback",
        "manifest", "manifest-written", "marker-cleared", "staging-cleared",
    )

    private fun pdf(size: Int, seed: Int = 0): ByteArray {
        val bytes = ByteArray(size) { ((it * 31 + seed) % 200 + 32).toByte() }
        "%PDF-1.4".toByteArray(Charsets.US_ASCII).copyInto(bytes, 0)
        return bytes
    }

    private fun open(originalSize: Int = 2048, root: File = File(tmp.root, "ws")) {
        streams = FakeUriStreams()
        streams.put(uri, pdf(originalSize), lastModified = 100L, displayName = "a.pdf")
        doc = runBlocking { WorkspaceStore(root).importFrom(uri, streams) }
    }

    private fun writer(
        hook: (String) -> Unit = {},
        fileOps: FileOps = DefaultFileOps,
        max: Long = SafWriter.DEFAULT_MAX_IN_MEMORY_BYTES,
        timeoutMs: Long = SafWriter.DEFAULT_COPY_TIMEOUT_MS,
    ) = SafWriter(Dispatchers.IO, max, timeoutMs, fileOps, hook)

    private fun staging() = File(doc.dir, "staging.pdf")
    private fun pending() = File(doc.dir, "copy.pending")
    private fun manifestOnDisk() = Layout.readManifest(doc.dir)

    private fun save(
        bytes: ByteArray,
        verify: (ByteArray) -> Boolean = { true },
        overwrite: Boolean = false,
        w: SafWriter = writer(),
    ): SaveResult = runBlocking { w.saveBack(doc, bytes, streams, verify, overwrite) }

    private fun copy(bytes: ByteArray, to: String = target, verify: (ByteArray) -> Boolean = { true }, w: SafWriter = writer()): CopyResult =
        runBlocking { w.saveCopyTo(doc, bytes, to, streams, verify) }

    /** Leaves a pending copy behind: the first 1000 bytes of [bytes] reached the source. */
    private fun leavePending(bytes: ByteArray) {
        streams.failWriteAfter = 1000
        assertIs<SaveResult.CopyFailed>(save(bytes))
        streams.failWriteAfter = null
    }

    // ---- happy path and sequencing ----

    @Test
    fun happyPathReplacesSourceUpdatesManifestAndCleansUp() {
        open()
        val newBytes = pdf(3000, seed = 5)
        var verifiedBytes: ByteArray? = null

        val result = save(newBytes, verify = { verifiedBytes = it; true })

        assertIs<SaveResult.Saved>(result)
        assertContentEquals(newBytes, streams.bytes(uri))
        assertContentEquals(newBytes, verifiedBytes)
        val m = manifestOnDisk()
        assertEquals(newBytes.size.toLong(), m.size)
        assertEquals(sha256Hex(newBytes), m.sha256)
        assertEquals(streams.stat(uri).lastModified, m.lastModified)
        assertFalse(staging().exists())
        assertFalse(pending().exists())
    }

    @Test
    fun twoConsecutiveSavesSucceedWithoutOverwriteFlagAndStaleDocSnapshot() {
        open()
        val first = pdf(2500, seed = 1)
        val second = pdf(2600, seed = 2)
        assertIs<SaveResult.Saved>(save(first))
        // `doc` still has the import-time manifest; the writer must read the one on disk.
        assertIs<SaveResult.Saved>(save(second))
        assertContentEquals(second, streams.bytes(uri))
        assertEquals(sha256Hex(second), manifestOnDisk().sha256)
    }

    @Test
    fun verifyFailureLeavesOriginalAndNeverTruncates() {
        open()
        val before = streams.bytes(uri)
        val manifestBefore = manifestOnDisk()

        val result = save(pdf(3000, seed = 9), verify = { false })

        assertIs<SaveResult.VerificationFailed>(result)
        assertEquals(0, streams.outputsOpened.get())
        assertContentEquals(before, streams.bytes(uri))
        assertEquals(manifestBefore, manifestOnDisk())
        assertFalse(staging().exists())
        assertFalse(File(doc.dir, "staging.next").exists())
        assertFalse(pending().exists())
    }

    @Test
    fun verifyThrowingCountsAsFailure() {
        open()
        val result = save(pdf(3000), verify = { error("engine exploded") })
        assertIs<SaveResult.VerificationFailed>(result)
        assertEquals(0, streams.outputsOpened.get())
    }

    @Test
    fun verifyMutatingItsArgumentCannotDesyncStagingOrHash() {
        open()
        val newBytes = pdf(3000, seed = 3)
        val expected = newBytes.copyOf()

        val result = save(newBytes, verify = { it.fill(0); true })

        assertIs<SaveResult.Saved>(result)
        assertContentEquals(expected, streams.bytes(uri))
        assertEquals(sha256Hex(expected), manifestOnDisk().sha256)
    }

    @Test
    fun providerStaleSizeWithCorrectBytesIsAccepted() {
        open()
        streams.staleSize = 99_999
        val newBytes = pdf(3000, seed = 4)

        val result = save(newBytes)

        assertIs<SaveResult.Saved>(result)
        assertEquals(newBytes.size.toLong(), manifestOnDisk().size)
        assertContentEquals(newBytes, streams.bytes(uri))
    }

    // ---- conflict detection ----

    @Test
    fun conflictWhenSizeChangedWritesNothing() {
        open()
        val external = pdf(5000, seed = 3)
        streams.put(uri, external, lastModified = doc.manifest.lastModified)

        val result = save(pdf(2100, seed = 4))

        val conflict = assertIs<SaveResult.Conflict>(result)
        assertEquals(external.size.toLong(), conflict.current.size)
        assertEquals(2048L, conflict.expected.size)
        assertEquals(0, streams.outputsOpened.get())
        assertContentEquals(external, streams.bytes(uri))
        assertFalse(staging().exists())
    }

    @Test
    fun conflictWhenSameSizeButDifferentContentEvenWithUnchangedTimestamp() {
        open()
        streams.put(uri, pdf(2048, seed = 77), lastModified = doc.manifest.lastModified)

        assertIs<SaveResult.Conflict>(save(pdf(2048, seed = 4)))
        assertEquals(0, streams.outputsOpened.get())
    }

    @Test
    fun sameContentWithNewTimestampIsNotAConflict() {
        open()
        streams.setLastModified(uri, 9_999L)
        assertIs<SaveResult.Saved>(save(pdf(2048, seed = 8)))
    }

    @Test
    fun sourceChangedBetweenHashAndTruncationIsDetectedByRehash() {
        open()
        streams.inputOpens.set(0)
        // input open 1 is the prepare hash; open 2 is the re-check right before truncation.
        streams.onInputOpen = { n -> if (n == 2) streams.put(uri, pdf(5000, seed = 3), lastModified = 5L) }

        val result = save(pdf(2100, seed = 4))

        assertIs<SaveResult.Conflict>(result)
        assertEquals(0, streams.outputsOpened.get())
        assertFalse(staging().exists())
        assertFalse(pending().exists())
    }

    @Test
    fun overwriteFlagSavesOverConflictAndKeepsTheExternalVersion() {
        open()
        val external = pdf(5000, seed = 3)
        streams.put(uri, external, lastModified = 5L)
        val mine = pdf(2100, seed = 4)

        val result = save(mine, overwrite = true)

        val saved = assertIs<SaveResult.Saved>(result)
        assertContentEquals(mine, streams.bytes(uri))
        val snapshot = assertNotNullName(saved.snapshot)
        assertContentEquals(external, File(doc.dir, snapshot).readBytes())
    }

    private fun assertNotNullName(name: String?): String {
        assertTrue(name != null && name.startsWith("external-"), name)
        return name!!
    }

    // ---- copy-back failures and pending state ----

    @Test
    fun copyFailureMidwayKeepsStagingAndMarker() {
        open()
        val newBytes = pdf(3000, seed = 6)
        streams.failWriteAfter = 1000

        val result = save(newBytes)

        val failed = assertIs<SaveResult.CopyFailed>(result)
        assertTrue(failed.retryable)
        assertTrue(failed.stagingKept)
        assertEquals(1000, streams.bytes(uri).size)
        assertTrue(staging().isFile)
        assertTrue(pending().isFile)
        assertEquals(PendingStatus.Pending, writer().pendingCopyStatus(doc))
    }

    @Test
    fun writeErrorDetailCarriesClassNameOnly() {
        open()
        streams.failWriteAfter = 1000
        val failed = assertIs<SaveResult.CopyFailed>(save(pdf(3000, seed = 6)))
        assertTrue(failed.detail.contains("IOException"), failed.detail)
        assertFalse(failed.detail.contains("injected"), failed.detail)
    }

    @Test
    fun retryAfterMidwayFailureSucceedsEvenThoughSourceIsNowPartial() {
        open()
        val newBytes = pdf(3000, seed = 6)
        leavePending(newBytes)

        // The source is partial and its size and timestamp differ from the manifest. Retry must
        // finish the copy and must not report Conflict.
        val retry = runBlocking { writer().finishPendingCopy(doc, streams) }

        assertIs<SaveResult.Saved>(retry)
        assertContentEquals(newBytes, streams.bytes(uri))
        assertFalse(staging().exists())
        assertFalse(pending().exists())
        assertEquals(sha256Hex(newBytes), manifestOnDisk().sha256)
    }

    @Test
    fun stagingHoldsTheFullVerifiedBytesWhileCopyIsPending() {
        open()
        val newBytes = pdf(3000, seed = 6)
        streams.failWriteAfter = 10
        assertIs<SaveResult.CopyFailed>(save(newBytes))
        assertContentEquals(newBytes, staging().readBytes())
    }

    @Test
    fun lengthMismatchAfterWriteIsCopyFailedAndRetryable() {
        open()
        val newBytes = pdf(3000, seed = 7)
        streams.dropTailOnClose = 1
        assertIs<SaveResult.CopyFailed>(save(newBytes))
        assertTrue(staging().isFile)
        streams.dropTailOnClose = 0
        assertIs<SaveResult.Saved>(runBlocking { writer().finishPendingCopy(doc, streams) })
        assertContentEquals(newBytes, streams.bytes(uri))
    }

    @Test
    fun hashMismatchAfterWriteIsCopyFailedAndRetryable() {
        open()
        val newBytes = pdf(3000, seed = 7)
        streams.flipFirstByte = true
        assertIs<SaveResult.CopyFailed>(save(newBytes))
        streams.flipFirstByte = false
        assertIs<SaveResult.Saved>(runBlocking { writer().finishPendingCopy(doc, streams) })
        assertContentEquals(newBytes, streams.bytes(uri))
    }

    @Test
    fun readBackImpossibleIsUnverifiableNotMismatch() {
        open()
        val newBytes = pdf(3000, seed = 7)
        streams.onClose = { streams.failOpenInput = true }

        val result = save(newBytes)

        assertIs<SaveResult.Unverifiable>(result)
        assertTrue(staging().isFile)
        assertTrue(pending().isFile)
        streams.onClose = null
        streams.failOpenInput = false
        assertIs<SaveResult.Saved>(runBlocking { writer().finishPendingCopy(doc, streams) })
    }

    @Test
    fun syncFailureIsRecordedWithClassNameAndProofDecides() {
        open()
        streams.syncThrows = SyncFailedException("/secret/path/x.pdf")
        val newBytes = pdf(3000, seed = 8)

        val result = save(newBytes)

        val saved = assertIs<SaveResult.Saved>(result)
        assertTrue(saved.warning!!.contains("SyncFailedException"), saved.warning)
        assertFalse(saved.warning!!.contains("secret"))
        assertContentEquals(newBytes, streams.bytes(uri))
    }

    @Test
    fun closeFailureIsRecordedAndReadBackDecides() {
        open()
        streams.closeThrows = true
        val newBytes = pdf(3000, seed = 8)

        val result = save(newBytes)

        val saved = assertIs<SaveResult.Saved>(result)
        assertTrue(saved.warning!!.contains("close failed: IOException"), saved.warning)
    }

    @Test
    fun pendingMarkerWithoutStagingIsOrphanAndRemoved() {
        open()
        pending().writeText(uri)
        assertEquals(PendingStatus.NotPending, writer().pendingCopyStatus(doc))
        assertIs<SaveResult.Saved>(save(pdf(3000, seed = 10)))
        assertFalse(pending().exists())
    }

    @Test
    fun stagingWithoutMarkerIsOrphanAndRemoved() {
        open()
        staging().writeBytes(pdf(3000, seed = 11))
        assertEquals(PendingStatus.NotPending, writer().pendingCopyStatus(doc))
        assertIs<SaveResult.Saved>(save(pdf(3000, seed = 12)))
        assertFalse(staging().exists())
    }

    @Test
    fun unreadablePendingStateIsUnknownAndSaveRefuses() {
        open()
        pending().mkdirs()
        val status = writer().pendingCopyStatus(doc)
        assertIs<PendingStatus.Unknown>(status)
        val refused = save(pdf(3000, seed = 13))
        assertIs<SaveResult.Io>(refused)
        assertEquals(0, streams.outputsOpened.get())
        assertIs<SaveResult.Io>(runBlocking { writer().finishPendingCopy(doc, streams) })
    }

    @Test
    fun failedVerifyWhileCopyPendingKeepsStagingAndMarker() {
        open()
        val a = pdf(3000, seed = 20)
        val b = pdf(3100, seed = 21)
        leavePending(a)

        val rejected = save(b, verify = { false })

        assertIs<SaveResult.VerificationFailed>(rejected)
        assertTrue(rejected.detail.contains("pending"), rejected.detail)
        assertContentEquals(a, staging().readBytes())
        assertTrue(pending().isFile)
        assertEquals(1000, streams.bytes(uri).size)
        assertEquals(PendingStatus.Pending, writer().pendingCopyStatus(doc))

        assertIs<SaveResult.Saved>(runBlocking { writer().finishPendingCopy(doc, streams) })
        assertContentEquals(a, streams.bytes(uri))
        assertEquals(PendingStatus.NotPending, writer().pendingCopyStatus(doc))
    }

    @Test
    fun failedStagingWhileCopyPendingKeepsPreviousStaging() {
        open()
        val a = pdf(3000, seed = 20)
        leavePending(a)
        // staging.next cannot be written: the new bytes fail the staging write, not the verify.
        val failingWriterOps = object : FileOps {
            override fun delete(file: File): Boolean = file.delete()
            override fun move(from: File, to: File) {
                if (from.name == "staging.next") throw IOException("injected move failure")
                DefaultFileOps.move(from, to)
            }
        }

        val result = save(pdf(3100, seed = 21), w = writer(fileOps = failingWriterOps))

        assertIs<SaveResult.Io>(result)
        assertContentEquals(a, staging().readBytes())
        assertTrue(pending().isFile)
    }

    @Test
    fun healthySaveWhileCopyPendingSupersedesStaging() {
        open()
        val a = pdf(3000, seed = 22)
        val b = pdf(3100, seed = 23)
        leavePending(a)

        assertIs<SaveResult.Saved>(save(b))

        assertContentEquals(b, streams.bytes(uri))
        assertEquals(sha256Hex(b), manifestOnDisk().sha256)
        assertFalse(staging().exists())
        assertFalse(pending().exists())
    }

    @Test
    fun finishPendingCopyWorksFromAFreshWriterAfterRestart() {
        open()
        val a = pdf(3000, seed = 24)
        leavePending(a)

        // Simulate a restart: a new writer and a re-read document, no bytes in memory.
        val restarted = writer()
        val reopened = WorkspaceStore(File(tmp.root, "ws")).load(doc.docId)
        assertEquals(PendingStatus.Pending, restarted.pendingCopyStatus(reopened))

        val saved = assertIs<SaveResult.Saved>(runBlocking { restarted.finishPendingCopy(reopened, streams) })
        assertEquals(sha256Hex(a), saved.doc.manifest.sha256)
        assertContentEquals(a, streams.bytes(uri))
        assertEquals(PendingStatus.NotPending, restarted.pendingCopyStatus(reopened))
    }

    @Test
    fun finishPendingCopyWithNothingPendingIsIo() {
        open()
        assertEquals(PendingStatus.NotPending, writer().pendingCopyStatus(doc))
        assertIs<SaveResult.Io>(runBlocking { writer().finishPendingCopy(doc, streams) })
        assertEquals(0, streams.outputsOpened.get())
    }

    @Test
    fun manifestWriteFailureIsSavedButManifestStale() {
        open()
        val newBytes = pdf(3000, seed = 40)
        val failingManifest = writer(hook = { if (it == "manifest") throw IOException("boom") })

        val result = save(newBytes, w = failingManifest)

        assertIs<SaveResult.SavedButManifestStale>(result)
        assertContentEquals(newBytes, streams.bytes(uri))
        assertEquals(PendingStatus.Pending, writer().pendingCopyStatus(doc))

        val fin = assertIs<SaveResult.Saved>(runBlocking { writer().finishPendingCopy(doc, streams) })
        assertEquals(sha256Hex(newBytes), fin.doc.manifest.sha256)
        assertEquals(sha256Hex(newBytes), manifestOnDisk().sha256)
    }

    @Test
    fun failedMarkerRemovalIsReportedAndRetryable() {
        open()
        val newBytes = pdf(3000, seed = 41)
        val ops = object : FileOps {
            override fun delete(file: File): Boolean = if (file.name == "copy.pending") false else file.delete()
            override fun move(from: File, to: File) = DefaultFileOps.move(from, to)
        }

        val result = save(newBytes, w = writer(fileOps = ops))

        assertIs<SaveResult.CopyFailed>(result)
        assertTrue(result.detail.contains("marker"), result.detail)
        assertEquals(PendingStatus.Pending, writer().pendingCopyStatus(doc))
        assertIs<SaveResult.Saved>(runBlocking { writer().finishPendingCopy(doc, streams) })
        assertEquals(PendingStatus.NotPending, writer().pendingCopyStatus(doc))
    }

    @Test
    fun failedStagingRemovalAfterSuccessIsAWarningNotAPending() {
        open()
        val newBytes = pdf(3000, seed = 42)
        val ops = object : FileOps {
            override fun delete(file: File): Boolean = if (file.name == "staging.pdf") false else file.delete()
            override fun move(from: File, to: File) = DefaultFileOps.move(from, to)
        }

        val result = save(newBytes, w = writer(fileOps = ops))

        val saved = assertIs<SaveResult.Saved>(result)
        assertTrue(saved.warning?.contains("staging") == true, saved.warning)
        assertContentEquals(newBytes, streams.bytes(uri))
        assertEquals(PendingStatus.NotPending, writer().pendingCopyStatus(doc))
    }

    // ---- crash injection ----

    @Test
    fun crashAtEveryStepLeavesRecoverableState() {
        val old = pdf(2048, seed = 0)
        val new = pdf(3000, seed = 50)
        for (step in steps) {
            open(root = File(tmp.root, "crash-$step"))
            val crashing = writer(hook = { if (it == step) throw SimulatedCrash(it) })
            assertFailsWith<SimulatedCrash>(step) { runBlocking { crashing.saveBack(doc, new, streams, { true }) } }

            // Recovery: a new writer and a re-read document, as after a restart.
            val reopened = WorkspaceStore(File(tmp.root, "crash-$step")).load(doc.docId)
            val recovery = writer()
            val src = streams.bytes(uri)
            when (recovery.pendingCopyStatus(reopened)) {
                PendingStatus.Pending -> {
                    assertContentEquals(new, staging().readBytes(), step)
                    assertIs<SaveResult.Saved>(runBlocking { recovery.finishPendingCopy(reopened, streams) }, step)
                    assertContentEquals(new, streams.bytes(uri), step)
                }
                PendingStatus.NotPending -> assertTrue(
                    src.contentEquals(old) || src.contentEquals(new),
                    "source partial after crash at $step with no pending copy",
                )
                else -> throw AssertionError("unknown pending state at $step")
            }
            assertTrue(
                manifestOnDisk().sha256 == sha256Hex(old) || manifestOnDisk().sha256 == sha256Hex(new),
                step,
            )
        }
    }

    // ---- cancellation ----

    @Test
    fun cancellationBeforeTruncationLeavesSourceUntouched() {
        open(originalSize = 300_000)
        streams.readDelayMs = 40
        val before = streams.bytes(uri)
        val newBytes = pdf(300_000, seed = 31)

        runBlocking {
            val job = launch(Dispatchers.Default) { writer().saveBack(doc, newBytes, streams, { true }) }
            delay(60)
            job.cancel()
            job.join()
            assertTrue(job.isCancelled)
        }

        assertContentEquals(before, streams.bytes(uri))
        assertEquals(0, streams.outputsOpened.get())
        assertFalse(pending().exists())
        assertFalse(staging().exists())
    }

    @Test
    fun cancellationDuringCopyPhaseStillCompletesConsistently() {
        open()
        streams.writeDelayMs = 30
        val newBytes = pdf(300_000, seed = 32)

        runBlocking {
            withTimeout(5_000) {
                val job = launch(Dispatchers.Default) { writer().saveBack(doc, newBytes, streams, { true }) }
                while (streams.outputsOpened.get() == 0) delay(2)
                job.cancel()
                job.join()
            }
        }

        assertContentEquals(newBytes, streams.bytes(uri))
        assertEquals(sha256Hex(newBytes), manifestOnDisk().sha256)
        assertFalse(pending().exists())
        assertFalse(staging().exists())
    }

    // ---- concurrency, lock map, memory cap ----

    @Test
    fun concurrentSavesOnTheSameDocumentAreSerialised() {
        open()
        streams.writeDelayMs = 20
        val a = pdf(2100, seed = 10)
        val b = pdf(2200, seed = 11)
        val w = writer()

        val results = runBlocking {
            listOf(
                async(Dispatchers.Default) { w.saveBack(doc, a, streams, { true }) },
                async(Dispatchers.Default) { w.saveBack(doc, b, streams, { true }) },
            ).awaitAll()
        }

        assertTrue(results.all { it is SaveResult.Saved }, results.toString())
        assertEquals(1, streams.maxActiveWriters.get())
        assertEquals(2, streams.outputsOpened.get())
    }

    @Test
    fun lockMapDoesNotKeepIdleDocuments() {
        open()
        val w = writer()
        assertIs<SaveResult.Saved>(save(pdf(3000, seed = 14), w = w))
        assertEquals(0, w.lockCount())
    }

    @Test
    fun saveBackRefusesOverMemoryCapBeforeAnyWork() {
        open()
        val result = save(pdf(2000, seed = 15), w = writer(max = 1000))
        assertIs<SaveResult.TooLarge>(result)
        assertEquals(0, streams.outputsOpened.get())
        assertFalse(staging().exists())
        assertFalse(File(doc.dir, "staging.next").exists())
    }

    @Test
    fun saveCopyRefusesOverMemoryCapBeforeAnyWork() {
        open()
        streams.put(target, ByteArray(0))
        assertIs<CopyResult.TooLarge>(copy(pdf(2000, seed = 16), w = writer(max = 1000)))
        assertEquals(0, streams.outputsOpened.get())
    }

    // ---- Save a copy and export ----

    @Test
    fun saveCopyWritesTargetAndLeavesOriginalAlone() {
        open()
        streams.put(target, ByteArray(0))
        val originalBefore = streams.bytes(uri)
        val manifestBefore = manifestOnDisk()
        val newBytes = pdf(2700, seed = 12)

        val copied = assertIs<CopyResult.Copied>(copy(newBytes))

        assertEquals(newBytes.size.toLong(), copied.size)
        assertEquals(sha256Hex(newBytes), copied.sha256)
        assertContentEquals(newBytes, streams.bytes(target))
        assertContentEquals(originalBefore, streams.bytes(uri))
        assertEquals(manifestBefore, manifestOnDisk())
        assertFalse(File(doc.dir, "save_copy.pdf").exists())
    }

    @Test
    fun saveCopyIgnoresSourceConflict() {
        open()
        streams.put(uri, pdf(5000, seed = 3), lastModified = 1L)
        streams.put(target, ByteArray(0))
        assertIs<CopyResult.Copied>(copy(pdf(2100, seed = 4)))
    }

    @Test
    fun saveCopyMidwayFailureKeepsCopyStagingAndRetrySucceeds() {
        open()
        streams.put(target, ByteArray(0))
        val newBytes = pdf(3000, seed = 13)
        streams.failWriteAfter = 500

        assertIs<CopyResult.CopyFailed>(copy(newBytes))
        assertTrue(File(doc.dir, "save_copy.pdf").isFile)

        streams.failWriteAfter = null
        assertIs<CopyResult.Copied>(copy(newBytes))
        assertContentEquals(newBytes, streams.bytes(target))
        assertFalse(File(doc.dir, "save_copy.pdf").exists())
    }

    @Test
    fun saveCopyVerifyFailureNeverOpensTarget() {
        open()
        streams.put(target, ByteArray(0))
        assertIs<CopyResult.VerificationFailed>(copy(pdf(3000), verify = { false }))
        assertEquals(0, streams.outputsOpened.get())
    }

    @Test
    fun saveCopyRefusesTheSourceDocumentUnderAnotherUriForm() {
        open()
        streams.aliases[alias] = uri
        val before = streams.bytes(uri)

        assertIs<CopyResult.Io>(copy(pdf(3000), to = alias))
        assertIs<CopyResult.Io>(copy(pdf(3000), to = uri))

        assertEquals(0, streams.outputsOpened.get())
        assertContentEquals(before, streams.bytes(uri))
    }

    @Test
    fun exportStagingWritesPendingBytesWhileSourceStaysPartial() {
        open()
        val a = pdf(3000, seed = 60)
        leavePending(a)
        streams.put(target, ByteArray(0))

        val exported = assertIs<CopyResult.Copied>(runBlocking { writer().exportStaging(doc, streams, target) })

        assertEquals(sha256Hex(a), exported.sha256)
        assertContentEquals(a, streams.bytes(target))
        assertEquals(1000, streams.bytes(uri).size)
        assertTrue(staging().isFile)
        assertEquals(PendingStatus.Pending, writer().pendingCopyStatus(doc))
    }

    @Test
    fun exportStagingWithoutPendingCopyIsIo() {
        open()
        streams.put(target, ByteArray(0))
        assertIs<CopyResult.Io>(runBlocking { writer().exportStaging(doc, streams, target) })
        assertEquals(0, streams.outputsOpened.get())
    }

    @Test
    fun deleteFailureOnOrphanStagingSurfacesAsIo() {
        open()
        staging().writeBytes(pdf(3000, seed = 61))
        val ops = object : FileOps {
            override fun delete(file: File): Boolean = if (file.name == "staging.pdf") false else file.delete()
            override fun move(from: File, to: File) = DefaultFileOps.move(from, to)
        }
        assertIs<SaveResult.Io>(save(pdf(3000, seed = 62), w = writer(fileOps = ops)))
        assertEquals(0, streams.outputsOpened.get())
    }

    // ---- overwrite path: time-of-check and time-of-use ----

    @Test
    fun overwritePathChangeBeforeTruncationIsConflictAndNothingIsLost() {
        open()
        val external1 = pdf(5000, seed = 3)
        streams.put(uri, external1, lastModified = 5L)
        streams.inputOpens.set(0)
        // open 1: prepare hash (external1). open 2: snapshot copy (external1). open 3: re-check before truncation.
        val external2 = pdf(5100, seed = 9)
        streams.onInputOpen = { n -> if (n == 3) streams.put(uri, external2, lastModified = 6L) }

        val result = save(pdf(2100, seed = 4), overwrite = true)

        val c = assertIs<SaveResult.Conflict>(result)
        assertFalse(c.sourceMayBeTruncated)
        assertFalse(c.stagingKept)
        assertEquals(0, streams.outputsOpened.get())
        val snapshots = doc.dir.listFiles { f -> f.name.startsWith("external-") && f.name.endsWith(".pdf") }!!
        assertEquals(1, snapshots.size)
        assertContentEquals(external1, snapshots[0].readBytes())
        assertContentEquals(external2, streams.bytes(uri))
        assertFalse(staging().exists())
    }

    @Test
    fun snapshotReadFailureStopsOverwriteAndLeavesNoPart() {
        open()
        val external = pdf(5000, seed = 3)
        streams.put(uri, external, lastModified = 5L)
        streams.inputOpens.set(0)
        streams.failReadAtOpen = 2 // open 2 is the snapshot copy
        streams.failReadBytes = 100

        val result = save(pdf(2100, seed = 4), overwrite = true)

        assertIs<SaveResult.Io>(result)
        assertEquals(0, streams.outputsOpened.get())
        assertContentEquals(external, streams.bytes(uri))
        val leftovers = doc.dir.list()!!.filter { it.endsWith(".part") || it.startsWith("external-") }
        assertEquals(emptyList(), leftovers)
        assertFalse(staging().exists())
    }

    @Test
    fun snapshotWriteFailureDoesNotProceedToOverwrite() {
        open()
        val external = pdf(5000, seed = 3)
        streams.put(uri, external, lastModified = 5L)
        val failingMove = object : FileOps {
            override fun delete(file: File): Boolean = file.delete()
            override fun move(from: File, to: File) {
                if (to.name.startsWith("external-")) throw IOException("injected disk full")
                DefaultFileOps.move(from, to)
            }
        }

        val result = save(pdf(2100, seed = 4), overwrite = true, w = writer(fileOps = failingMove))

        assertIs<SaveResult.Io>(result)
        assertEquals(0, streams.outputsOpened.get())
        assertContentEquals(external, streams.bytes(uri))
        assertEquals(emptyList(), doc.dir.list()!!.filter { it.endsWith(".part") || it.startsWith("external-") })
    }

    // ---- pending copy: finish branches and external edits ----

    @Test
    fun finishWhenSourceAlreadyHoldsTheNewBytesDoesNotTruncate() {
        open()
        val newBytes = pdf(3000, seed = 70)
        val manifestFails = writer(hook = { if (it == "manifest") throw IOException("boom") })
        val stale = assertIs<SaveResult.SavedButManifestStale>(save(newBytes, w = manifestFails))
        assertFalse(stale.sourceMayBeTruncated)
        assertTrue(stale.stagingKept)
        assertEquals(sha256Hex(newBytes), stale.doc.manifest.sha256)
        val opened = streams.outputsOpened.get()

        val fin = assertIs<SaveResult.Saved>(runBlocking { writer().finishPendingCopy(doc, streams) })

        assertEquals(opened, streams.outputsOpened.get())
        assertEquals(sha256Hex(newBytes), fin.doc.manifest.sha256)
        assertFalse(pending().exists())
        assertFalse(staging().exists())
    }

    @Test
    fun finishAfterCrashBeforeTruncationStillCopiesAndSaves() {
        open()
        val newBytes = pdf(3000, seed = 71)
        val crashing = writer(hook = { if (it == "marker") throw SimulatedCrash(it) })
        assertFailsWith<SimulatedCrash> { runBlocking { crashing.saveBack(doc, newBytes, streams, { true }) } }
        val before = streams.outputsOpened.get()

        assertIs<SaveResult.Saved>(runBlocking { writer().finishPendingCopy(doc, streams) })

        assertEquals(before + 1, streams.outputsOpened.get())
        assertContentEquals(newBytes, streams.bytes(uri))
    }

    @Test
    fun finishWithExternalEditWhilePendingIsConflictAndNothingIsTruncated() {
        open()
        val a = pdf(3000, seed = 72)
        leavePending(a)
        val edit = pdf(2600, seed = 73)
        streams.put(uri, edit, lastModified = 77L)
        val before = streams.outputsOpened.get()

        val c = assertIs<SaveResult.Conflict>(runBlocking { writer().finishPendingCopy(doc, streams) })

        assertTrue(c.sourceMayBeTruncated)
        assertTrue(c.stagingKept)
        assertEquals(before, streams.outputsOpened.get())
        assertContentEquals(edit, streams.bytes(uri))
        assertContentEquals(a, staging().readBytes())
        assertTrue(pending().isFile)
    }

    @Test
    fun finishWithExternalEditAndOverwriteSnapshotsThenSaves() {
        open()
        val a = pdf(3000, seed = 74)
        leavePending(a)
        val edit = pdf(2600, seed = 75)
        streams.put(uri, edit, lastModified = 77L)

        val saved = assertIs<SaveResult.Saved>(
            runBlocking { writer().finishPendingCopy(doc, streams, overwriteIfSourceChanged = true) },
        )

        val snapshot = assertNotNull(saved.snapshot)
        assertContentEquals(edit, File(doc.dir, snapshot).readBytes())
        assertContentEquals(a, streams.bytes(uri))
        assertFalse(pending().exists())
    }

    @Test
    fun saveBackWhilePendingWithExternalEditIsConflictAndKeepsPreviousStaging() {
        open()
        val a = pdf(3000, seed = 76)
        leavePending(a)
        val edit = pdf(2600, seed = 77)
        streams.put(uri, edit, lastModified = 78L)
        val before = streams.outputsOpened.get()

        val c = assertIs<SaveResult.Conflict>(save(pdf(3100, seed = 78)))

        assertTrue(c.stagingKept)
        assertEquals(before, streams.outputsOpened.get())
        assertContentEquals(edit, streams.bytes(uri))
        assertContentEquals(a, staging().readBytes())
    }

    @Test
    fun pendingStateFlagsAreExplicitOnEveryFailure() {
        open()
        val a = pdf(3000, seed = 79)
        leavePending(a)
        val verifyRejected = assertIs<SaveResult.VerificationFailed>(save(pdf(3100, seed = 80), verify = { false }))
        assertTrue(verifyRejected.sourceMayBeTruncated)
        assertTrue(verifyRejected.stagingKept)
    }

    // ---- hung provider ----

    @Test
    fun hungCloseTimesOutAsCopyFailedAndReleasesTheDocumentLock() {
        open()
        val gate = CountDownLatch(1)
        streams.closeGate = gate
        val w = writer(timeoutMs = 3_000)
        val newBytes = pdf(3000, seed = 81)
        val started = System.nanoTime()
        val result = try {
            save(newBytes, w = w)
        } finally {
            gate.countDown()
        }
        val elapsedMs = (System.nanoTime() - started) / 1_000_000

        val failed = assertIs<SaveResult.CopyFailed>(result, "got $result after $elapsedMs ms")
        assertTrue(failed.detail.contains("timed out"), failed.detail)
        assertTrue(failed.stagingKept)
        assertTrue(failed.sourceMayBeTruncated)
        assertTrue(elapsedMs < 10_000, "took $elapsedMs ms")
        assertEquals(0, w.lockCount())
        assertTrue(pending().isFile)
        // The bytes reached the source before close hung; a finish completes the state.
        assertIs<SaveResult.Saved>(runBlocking { writer().finishPendingCopy(doc, streams) })
    }

    // ---- identity ----

    @Test
    fun saveCopyIsAllowedWhenTheDocumentIdentityCannotBeDetermined() {
        open()
        streams.put(target, ByteArray(0))
        streams.documentKeyNull = true
        val before = streams.bytes(doc.manifest.uri)
        val newBytes = pdf(3000)

        val copied = assertIs<CopyResult.Copied>(copy(newBytes))
        assertNull(copied.replacedSource, "the source was not the target")
        assertContentEquals(newBytes, streams.bytes(target))
        assertContentEquals(before, streams.bytes(doc.manifest.uri))
    }

    @Test
    fun saveCopyOntoAnUnrecognisedAddressOfTheSourceReportsTheSourceReplaced() {
        open()
        streams.link(alias, uri) // one file, two addresses, and no identity to match them
        streams.documentKeyNull = true
        val newBytes = pdf(3000)

        val copied = assertIs<CopyResult.Copied>(copy(newBytes, to = alias))
        val replaced = assertNotNull(copied.replacedSource, "the target was the source")
        assertContentEquals(newBytes, streams.bytes(uri), "the source holds the complete new document")
        assertEquals(copied.sha256, replaced.manifest.sha256)
        assertEquals(3000L, replaced.manifest.size)
        // The record matches the file: the next save-back is not a conflict.
        doc = replaced
        assertIs<SaveResult.Saved>(save(pdf(3500)))
    }

    @Test
    fun aFailedCopyOntoAnUnrecognisedAddressOfTheSourceKeepsTheStagedBytes() {
        open()
        streams.link(alias, uri)
        streams.documentKeyNull = true
        streams.failWriteAfter = 1000

        val failed = assertIs<CopyResult.CopyFailed>(copy(pdf(3000), to = alias))
        assertTrue(failed.stagingKept)
        assertTrue(failed.targetMayBeTruncated)
        assertEquals(3000L, File(doc.dir, Layout.SAVE_COPY_STAGING).length())
    }

    @Test
    fun saveCopyOntoTheSameUriIsRefusedEvenWithoutIdentity() {
        open()
        streams.documentKeyNull = true

        assertIs<CopyResult.Io>(runBlocking { writer().saveCopyTo(doc, pdf(3000), doc.manifest.uri, streams, { true }) })
        assertEquals(0, streams.outputsOpened.get())
    }
}
