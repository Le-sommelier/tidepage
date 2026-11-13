package app.pdfa.pages.tools

import app.pdfa.engine.DocHandle
import app.pdfa.engine.EngineApi
import app.pdfa.engine.EngineError
import app.pdfa.engine.MergeSource
import app.pdfa.engine.fake.FakeEngine
import app.pdfa.engine.fake.FakePage
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class MergeControllerTest {
    private val fake = FakeEngine()
    private val files = HashMap<String, SourceLoad>()

    private fun pdf(prefix: String, n: Int, password: String? = null): ByteArray =
        FakeEngine.fakePdf(List(n) { FakePage(100f, 200f, label = "$prefix${it + 1}") }, password = password)

    private fun put(uri: String, prefix: String, n: Int, password: String? = null) {
        files[uri] = SourceLoad.Loaded(uri, pdf(prefix, n, password))
    }

    private fun controller(engine: EngineApi = fake) = MergeController(engine) { files[it] ?: SourceLoad.Failed(it, SourceLoad.Reason.UNREADABLE) }

    private suspend fun labelsOf(bytes: ByteArray): List<String> {
        val h = fake.open(bytes)
        return fake.pageInfos(h).map { it.label }.also { fake.close(h) }
    }

    /** Saves the result into [out] and returns what was written. */
    private suspend fun MergeController.saveInto(out: MutableList<ByteArray>): Boolean =
        save("t") { _, b -> out += b; true }

    private fun MergeController.ids() = state.value.items.map { it.id }

    @Test fun add_probesPageCounts_andKeepsPickOrder() = runTest {
        put("a", "A", 3); put("b", "B", 2)
        val c = controller()
        c.addSources(listOf("a", "b"))
        val items = c.state.value.items
        assertEquals(listOf("a", "b"), items.map { it.name })
        assertEquals(listOf(3, 2), items.map { it.pageCount })
        assertTrue(items.all { it.status == SourceStatus.READY })
        assertFalse(c.state.value.adding)
        assertTrue(c.state.value.canMerge)
    }

    @Test fun add_closesEveryProbeHandle() = runTest {
        put("a", "A", 3); put("b", "B", 2); put("p", "P", 1, password = "pw")
        val c = controller()
        c.addSources(listOf("a", "b", "p"))
        // The fake rejects a closed handle, and a leak would show as an open document. Reopen works either way,
        // so check through a delegating engine that counts.
        var opened = 0; var closed = 0
        val counting = object : EngineApi by fake {
            override suspend fun open(bytes: ByteArray, password: String?): DocHandle { val h = fake.open(bytes, password); opened++; return h }
            override suspend fun close(handle: DocHandle) { closed++; fake.close(handle) }
        }
        val c2 = controller(counting)
        c2.addSources(listOf("a", "b", "p"))
        c2.submitPassword(c2.ids()[2], "nope")
        c2.submitPassword(c2.ids()[2], "pw")
        assertEquals(opened, closed)
        assertEquals(3, opened) // a, b, and p once the right password is given; refused opens never get a handle
    }

    @Test fun unusableFiles_showWhy_andBlockMerge() = runTest {
        put("a", "A", 1); put("b", "B", 1)
        files["x"] = SourceLoad.Failed("x", SourceLoad.Reason.NOT_PDF)
        files["y"] = SourceLoad.Failed("y", SourceLoad.Reason.TOO_LARGE)
        files["bad"] = SourceLoad.Loaded("bad", byteArrayOf(1, 2, 3))
        val c = controller()
        c.addSources(listOf("a", "b", "x", "y", "bad"))
        assertEquals(
            listOf(SourceStatus.READY, SourceStatus.READY, SourceStatus.NOT_PDF, SourceStatus.TOO_LARGE, SourceStatus.DAMAGED),
            c.state.value.items.map { it.status },
        )
        assertFalse(c.state.value.canMerge)
        c.remove(c.ids()[4]); c.remove(c.ids()[3]); c.remove(c.ids()[2])
        assertTrue(c.state.value.canMerge)
    }

    @Test fun canMerge_needsTwoFiles() = runTest {
        put("a", "A", 1)
        val c = controller()
        c.addSources(listOf("a"))
        assertFalse(c.state.value.canMerge)
        assertFalse(c.merge())
        assertEquals(MergePhase.EDITING, c.state.value.phase)
    }

    @Test fun reorder_moveUpDownAtTheEnds_andRemove() = runTest {
        put("a", "A", 1); put("b", "B", 1); put("c", "C", 1)
        val c = controller()
        c.addSources(listOf("a", "b", "c"))
        val (a, b, cc) = c.ids()
        c.moveUp(a) // already first: no change
        assertEquals(listOf(a, b, cc), c.ids())
        c.moveDown(cc) // already last
        assertEquals(listOf(a, b, cc), c.ids())
        c.moveDown(a)
        assertEquals(listOf(b, a, cc), c.ids())
        c.moveUp(cc)
        assertEquals(listOf(b, cc, a), c.ids())
        c.moveUp(999) // unknown id
        assertEquals(listOf(b, cc, a), c.ids())
        c.remove(cc)
        assertEquals(listOf(b, a), c.ids())
    }

    @Test fun mergeFollowsListOrderAndRanges() = runTest {
        put("a", "A", 3); put("b", "B", 6)
        val c = controller()
        c.addSources(listOf("a", "b"))
        val (a, b) = c.ids()
        c.setRange(b, "2-3, 6")
        c.moveUp(b)
        assertTrue(c.merge())
        assertEquals(MergePhase.MERGED, c.state.value.phase)
        assertEquals(6, c.state.value.mergedPages)
        val out = ArrayList<ByteArray>()
        assertTrue(c.saveInto(out))
        assertEquals(listOf("B2", "B3", "B6", "A1", "A2", "A3"), labelsOf(out.single()))
        assertEquals(MergePhase.SAVED, c.state.value.phase)
        assertEquals("t", c.state.value.savedTo)
        assertTrue(a != b)
    }

    @Test fun rangeValidation_perSourcePageCount_blocksMerge() = runTest {
        put("a", "A", 3); put("b", "B", 6)
        val c = controller()
        c.addSources(listOf("a", "b"))
        val (a, b) = c.ids()
        c.setRange(a, "4") // a has 3 pages
        assertEquals(InputError.OUT_OF_BOUNDS, c.state.value.items[0].rangeError)
        assertFalse(c.state.value.canMerge)
        c.setRange(a, "3")
        assertNull(c.state.value.items[0].rangeError)
        c.setRange(b, "3-1")
        assertEquals(InputError.REVERSED, c.state.value.items[1].rangeError)
        c.setRange(b, "2, 2")
        assertEquals(InputError.OVERLAP, c.state.value.items[1].rangeError)
        c.setRange(b, "  ")
        assertNull(c.state.value.items[1].rangeError)
        assertTrue(c.state.value.canMerge)
        assertEquals(1, c.state.value.items[0].pagesUsed)
        assertEquals(6, c.state.value.items[1].pagesUsed)
    }

    @Test fun password_stateMachine() = runTest {
        put("a", "A", 1); put("p", "P", 2, password = "pw")
        val c = controller()
        c.addSources(listOf("a", "p"))
        val (_, p) = c.ids()
        assertEquals(SourceStatus.NEEDS_PASSWORD, c.state.value.items[1].status)
        assertNull(c.state.value.items[1].pageCount)
        assertFalse(c.state.value.canMerge)
        c.setRange(p, "1") // typed before the count is known: no verdict yet
        assertNull(c.state.value.items[1].rangeError)

        c.submitPassword(p, "wrong")
        assertEquals(SourceStatus.WRONG_PASSWORD, c.state.value.items[1].status)
        c.submitPassword(p, "wrong again")
        assertEquals(SourceStatus.WRONG_PASSWORD, c.state.value.items[1].status)
        c.submitPassword(p, "pw")
        assertEquals(SourceStatus.READY, c.state.value.items[1].status)
        assertEquals(2, c.state.value.items[1].pageCount)
        assertTrue(c.state.value.canMerge)

        assertTrue(c.merge())
        val out = ArrayList<ByteArray>()
        c.saveInto(out)
        assertEquals(listOf("A1", "P1"), labelsOf(out.single()))
    }

    @Test fun password_rangeTypedEarlyIsCheckedOnceUnlocked() = runTest {
        put("a", "A", 1); put("p", "P", 2, password = "pw")
        val c = controller()
        c.addSources(listOf("a", "p"))
        val p = c.ids()[1]
        c.setRange(p, "5")
        c.submitPassword(p, "pw")
        assertEquals(InputError.OUT_OF_BOUNDS, c.state.value.items[1].rangeError)
        assertFalse(c.state.value.canMerge)
    }

    @Test fun password_neverAppearsInState_andIsDroppedAfterMerge() = runTest {
        put("a", "A", 1); put("p", "P", 1, password = "s3cretvalue")
        val c = controller()
        c.addSources(listOf("a", "p"))
        c.submitPassword(c.ids()[1], "s3cretvalue")
        assertFalse(c.state.value.toString().contains("s3cretvalue"))
        var seen: String? = null
        val spy = object : EngineApi by fake {
            override suspend fun merge(sources: List<MergeSource>): ByteArray { seen = sources[1].password; return fake.merge(sources) }
        }
        val c2 = controller(spy)
        c2.addSources(listOf("a", "p"))
        c2.submitPassword(c2.ids()[1], "s3cretvalue")
        assertTrue(c2.merge())
        assertEquals("s3cretvalue", seen) // the engine needs it...
        // ...and the neutral names keep file names out of the engine's messages.
        assertFalse(c2.state.value.toString().contains("s3cretvalue"))
    }

    @Test fun submitPassword_ignoredForReadyFiles() = runTest {
        put("a", "A", 1); put("b", "B", 1)
        val c = controller()
        c.addSources(listOf("a", "b"))
        c.submitPassword(c.ids()[0], "x")
        assertEquals(SourceStatus.READY, c.state.value.items[0].status)
    }

    @Test fun sourceNamesSentToTheEngineAreNeutral() = runTest {
        put("secret-report.pdf", "A", 1); put("b", "B", 1)
        var names = emptyList<String>()
        val spy = object : EngineApi by fake {
            override suspend fun merge(sources: List<MergeSource>): ByteArray { names = sources.map { it.name }; return fake.merge(sources) }
        }
        val c = controller(spy)
        c.addSources(listOf("secret-report.pdf", "b"))
        c.merge()
        assertEquals(listOf("1.pdf", "2.pdf"), names)
    }

    @Test fun cancel_endsEditing_notBusy_andRethrows() = runTest(UnconfinedTestDispatcher()) {
        put("a", "A", 1); put("b", "B", 1)
        val gate = CompletableDeferred<Unit>()
        val slow = object : EngineApi by fake {
            override suspend fun merge(sources: List<MergeSource>): ByteArray { gate.await(); return fake.merge(sources) }
        }
        val c = controller(slow)
        c.addSources(listOf("a", "b"))
        var thrown: Throwable? = null
        val job = launch {
            try { c.merge() } catch (e: CancellationException) { thrown = e; throw e }
        }
        assertEquals(MergePhase.MERGING, c.state.value.phase)
        job.cancel()
        job.join()
        assertTrue(thrown is CancellationException)
        assertEquals(MergePhase.EDITING, c.state.value.phase)
        assertTrue(c.state.value.cancelled)
        assertEquals(2, c.state.value.items.size) // the list is intact
        assertTrue(c.state.value.canMerge)
        // And the merge can run again.
        gate.complete(Unit)
        assertTrue(c.merge())
        assertFalse(c.state.value.cancelled)
    }

    @Test fun engineCancelledError_isCancelled_notAFailure() = runTest {
        put("a", "A", 1); put("b", "B", 1)
        val c = controller()
        c.addSources(listOf("a", "b"))
        fake.failNext(EngineError.Cancelled, "merge")
        assertFalse(c.merge())
        assertTrue(c.state.value.cancelled)
        assertNull(c.state.value.problem)
        assertEquals(MergePhase.EDITING, c.state.value.phase)
    }

    private suspend fun problemFor(error: EngineError): ToolProblem? {
        put("a", "A", 1); put("b", "B", 1)
        val c = controller()
        c.addSources(listOf("a", "b"))
        fake.failNext(error, "merge")
        assertFalse(c.merge(), "never claim success on failure")
        assertEquals(MergePhase.EDITING, c.state.value.phase)
        assertNull(c.state.value.mergedPages)
        assertFalse(c.saveInto(ArrayList()), "nothing to save after a failure")
        assertTrue(c.state.value.canMerge, "the list survives, so the user can try again")
        return c.state.value.problem
    }

    @Test fun eachEngineError_hasItsOwnProblem() = runTest {
        assertEquals(ToolProblem.TOO_LARGE, problemFor(EngineError.TooLarge("x")))
        assertEquals(ToolProblem.NOT_PERMITTED, problemFor(EngineError.NotPermitted("x")))
        assertEquals(ToolProblem.ENGINE_DIED, problemFor(EngineError.Timeout("x"))) // the fake turns a timeout into a dead engine
        assertEquals(ToolProblem.TIMEOUT, EngineError.Timeout("x").toProblem())
        assertEquals(ToolProblem.ENGINE_DIED, problemFor(EngineError.EngineDied("x")))
        assertEquals(ToolProblem.DAMAGED, problemFor(EngineError.InvalidPdf("x")))
        assertEquals(ToolProblem.FAILED, problemFor(EngineError.Panic("x")))
    }

    @Test fun save_failure_keepsTheResult_forAnotherTry() = runTest {
        put("a", "A", 1); put("b", "B", 1)
        val c = controller()
        c.addSources(listOf("a", "b"))
        c.merge()
        assertFalse(c.save("first") { _, _ -> false })
        assertEquals(MergePhase.MERGED, c.state.value.phase)
        assertTrue(c.state.value.saveFailed)
        assertFalse(c.save("second") { _, _ -> throw java.io.IOException("disk full") })
        assertTrue(c.state.value.saveFailed)
        val out = ArrayList<ByteArray>()
        assertTrue(c.saveInto(out))
        assertEquals(MergePhase.SAVED, c.state.value.phase)
        assertFalse(c.state.value.saveFailed)
        assertEquals(listOf("A1", "B1"), labelsOf(out.single()))
    }

    @Test fun listIsLockedWhileMergingAndAfter() = runTest {
        put("a", "A", 1); put("b", "B", 1); put("c", "C", 1)
        val c = controller()
        c.addSources(listOf("a", "b"))
        c.merge()
        val before = c.ids()
        c.remove(before[0]); c.moveDown(before[0]); c.setRange(before[0], "1")
        assertEquals(before, c.ids())
        c.addSources(listOf("c"))
        assertEquals(2, c.state.value.items.size)
    }

    @Test fun reset_clearsEverything() = runTest {
        put("a", "A", 1); put("b", "B", 1)
        val c = controller()
        c.addSources(listOf("a", "b"))
        c.merge()
        c.reset()
        assertEquals(MergeState(), c.state.value)
        assertFalse(c.saveInto(ArrayList()))
    }

    @Test fun limits_tooManyFiles_areSkippedAndCounted() = runTest {
        put("a", "A", 1)
        val c = controller()
        c.addSources(List(MergeController.MAX_SOURCES + 3) { "a" })
        assertEquals(MergeController.MAX_SOURCES, c.state.value.items.size)
        assertEquals(3, c.state.value.skipped)
    }

    @Test fun engineTimeoutWhileProbing_marksTheFile_andTellsWhy() = runTest {
        put("a", "A", 1)
        val c = controller()
        fake.failNext(EngineError.Timeout("x"), "open") // the fake reports a timeout as a dead engine
        c.addSources(listOf("a"))
        assertEquals(SourceStatus.UNREADABLE, c.state.value.items[0].status)
        assertEquals(ToolProblem.ENGINE_DIED, c.state.value.problem)
        assertFalse(c.state.value.adding)
    }
}
