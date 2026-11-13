package app.pdfa.pages.tools

import app.pdfa.engine.DocHandle
import app.pdfa.engine.EngineApi
import app.pdfa.engine.EngineError
import app.pdfa.engine.SplitPart
import app.pdfa.engine.SplitSpec
import app.pdfa.engine.fake.FakeEngine
import app.pdfa.engine.fake.FakePage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
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
class SplitControllerTest {
    private val fake = FakeEngine()

    private suspend fun setup(pages: Int = 7, engine: (FakeEngine) -> EngineApi = { it }, prepare: suspend () -> Unit = {}): Pair<SplitController, DocHandle> {
        val h = fake.open(FakeEngine.fakePdf(List(pages) { FakePage(100f, 200f, label = "P${it + 1}") }))
        return SplitController(engine(fake), h, pages, prepare) to h
    }

    private suspend fun labelsOf(bytes: ByteArray): List<String> {
        val h = fake.open(bytes)
        return fake.pageInfos(h).map { it.label }.also { fake.close(h) }
    }

    @Test fun extract_typedRange_savesThoseTypedPagesInDocumentOrder() = runTest {
        val (c, _) = setup()
        c.setInput("6, 1-2")
        assertEquals(3, c.state.value.outcomeCount)
        assertTrue(c.run())
        assertEquals(SplitPhase.READY, c.state.value.phase)
        var saved: ByteArray? = null
        assertTrue(c.saveFile { saved = it; true })
        assertEquals(listOf("P1", "P2", "P6"), labelsOf(saved!!))
        assertEquals(SaveReport.FileSaved, c.state.value.report)
        assertEquals(SplitPhase.DONE, c.state.value.phase)
    }

    @Test fun extract_invalidOrEmptyInput_cannotRun() = runTest {
        val (c, _) = setup()
        assertFalse(c.state.value.canRun)
        assertFalse(c.run())
        c.setInput("9")
        assertEquals(InputError.OUT_OF_BOUNDS, c.state.value.inputError)
        assertFalse(c.run())
        c.setInput("3-1")
        assertEquals(InputError.REVERSED, c.state.value.inputError)
        c.setInput("1-2, 2")
        assertEquals(InputError.OVERLAP, c.state.value.inputError)
        c.setInput("1-2")
        assertNull(c.state.value.inputError)
        assertTrue(c.state.value.canRun)
    }

    @Test fun split_everyN_makesNamedParts() = runTest {
        val (c, _) = setup(7)
        c.setMode(SplitMode.EVERY_N)
        c.setInput("3")
        assertEquals(3, c.state.value.outcomeCount)
        assertTrue(c.run())
        assertEquals(3, c.state.value.readyParts)
        val names = ArrayList<String>(); val pages = ArrayList<List<String>>()
        val report = c.saveParts { i, name, bytes -> names += name; pages += labelsOf(bytes); assertEquals(names.size - 1, i); true }
        assertEquals(SaveReport.PartsSaved(3), report)
        assertEquals(listOf("part-01.pdf", "part-02.pdf", "part-03.pdf"), names)
        assertEquals(listOf(listOf("P1", "P2", "P3"), listOf("P4", "P5", "P6"), listOf("P7")), pages)
        assertEquals(SplitPhase.DONE, c.state.value.phase)
    }

    @Test fun split_before_usesZeroBasedPointsToTheEngine() = runTest {
        var spec: SplitSpec? = null
        val (c, _) = setup(7, engine = { f -> object : EngineApi by f {
            override suspend fun split(handle: DocHandle, s: SplitSpec): List<SplitPart> { spec = s; return f.split(handle, s) }
        } })
        c.setMode(SplitMode.BEFORE)
        c.setInput("6, 3")
        assertTrue(c.run())
        assertEquals(SplitSpec.Before(listOf(2, 5)), spec)
        assertEquals(3, c.state.value.readyParts)
    }

    @Test fun split_overTheCap_isRefusedBeforeTheEngineIsCalled() = runTest {
        var calls = 0
        val (c, _) = setup(1001, engine = { f -> object : EngineApi by f {
            override suspend fun split(handle: DocHandle, s: SplitSpec): List<SplitPart> { calls++; return f.split(handle, s) }
        } })
        c.setMode(SplitMode.EVERY_N)
        c.setInput("1")
        assertEquals(InputError.TOO_MANY_PARTS, c.state.value.inputError)
        assertFalse(c.run())
        assertEquals(0, calls)
        c.setInput("2")
        assertTrue(c.state.value.canRun)
    }

    @Test fun split_exactly1000Parts_isAllowed() = runTest {
        val (c, _) = setup(1000)
        c.setMode(SplitMode.EVERY_N)
        c.setInput("1")
        assertEquals(1000, c.state.value.outcomeCount)
        assertTrue(c.run())
        val names = ArrayList<String>()
        assertEquals(SaveReport.PartsSaved(1000), c.saveParts { _, n, _ -> names += n; true })
        assertEquals("part-0001.pdf", names.first())
        assertEquals("part-1000.pdf", names.last())
    }

    @Test fun savingParts_stopsAtTheFirstFailure_andNamesIt() = runTest {
        val (c, _) = setup(5)
        c.setMode(SplitMode.EVERY_N); c.setInput("1")
        c.run()
        val tried = ArrayList<Int>()
        val r = c.saveParts { i, _, _ -> tried += i; i != 2 }
        assertEquals(SaveReport.PartsFailed(written = 2, total = 5, failedPart = 3), r)
        assertEquals(listOf(0, 1, 2), tried)
        assertEquals(SplitPhase.READY, c.state.value.phase)
        // The parts are still held: another folder can be tried, and this time all of them are written.
        assertEquals(SaveReport.PartsSaved(5), c.saveParts { _, _, _ -> true })
    }

    @Test fun savingParts_aThrowingSinkCountsAsAFailure() = runTest {
        val (c, _) = setup(3)
        c.setMode(SplitMode.EVERY_N); c.setInput("1")
        c.run()
        val r = c.saveParts { i, _, _ -> if (i == 1) throw java.io.IOException("full") else true }
        assertEquals(SaveReport.PartsFailed(1, 3, 2), r)
    }

    @Test fun saveFile_failure_keepsResult() = runTest {
        val (c, _) = setup()
        c.setInput("1")
        c.run()
        assertFalse(c.saveFile { false })
        assertEquals(SaveReport.FileFailed, c.state.value.report)
        assertEquals(SplitPhase.READY, c.state.value.phase)
        assertTrue(c.saveFile { true })
    }

    @Test fun cancel_returnsToEditing_andRethrows() = runTest(UnconfinedTestDispatcher()) {
        val gate = CompletableDeferred<Unit>()
        val (c, _) = setup(4, engine = { f -> object : EngineApi by f {
            override suspend fun split(handle: DocHandle, s: SplitSpec): List<SplitPart> { gate.await(); return f.split(handle, s) }
        } })
        c.setMode(SplitMode.EVERY_N); c.setInput("2")
        var thrown: Throwable? = null
        val job = launch { try { c.run() } catch (e: CancellationException) { thrown = e; throw e } }
        assertEquals(SplitPhase.WORKING, c.state.value.phase)
        job.cancel(); job.join()
        assertTrue(thrown is CancellationException)
        assertEquals(SplitPhase.EDITING, c.state.value.phase)
        assertTrue(c.state.value.cancelled)
        assertTrue(c.state.value.canRun)
        assertEquals("2", c.state.value.input)
    }

    @Test fun prepareRunsBeforeTheEngineCall() = runTest {
        val order = ArrayList<String>()
        val (c, _) = setup(3, engine = { f -> object : EngineApi by f {
            override suspend fun extract(handle: DocHandle, pages: List<Int>): ByteArray { order += "extract"; return f.extract(handle, pages) }
        } }, prepare = { order += "prepare" })
        c.setInput("1")
        c.run()
        assertEquals(listOf("prepare", "extract"), order)
    }

    private suspend fun problemFor(error: EngineError, split: Boolean): ToolProblem? {
        val (c, _) = setup(4)
        if (split) { c.setMode(SplitMode.EVERY_N); c.setInput("2") } else c.setInput("1-2")
        fake.failNext(error, if (split) "split" else "extract")
        assertFalse(c.run())
        assertEquals(SplitPhase.EDITING, c.state.value.phase)
        assertFalse(c.saveFile { true })
        assertTrue(c.saveParts { _, _, _ -> true } == null)
        return c.state.value.problem
    }

    @Test fun engineErrors_extractAndSplit() = runTest {
        for (split in listOf(false, true)) {
            assertEquals(ToolProblem.TOO_LARGE, problemFor(EngineError.TooLarge("x"), split))
            assertEquals(ToolProblem.NOT_PERMITTED, problemFor(EngineError.NotPermitted("x"), split))
            assertEquals(ToolProblem.ENGINE_DIED, problemFor(EngineError.EngineDied("x"), split))
            assertEquals(ToolProblem.FAILED, problemFor(EngineError.Panic("x"), split))
        }
        assertEquals(ToolProblem.TIMEOUT, EngineError.Timeout("x").toProblem())
    }

    @Test fun modeChangeClearsInputAndResult() = runTest {
        val (c, _) = setup()
        c.setInput("1-2")
        c.setMode(SplitMode.EVERY_N)
        assertEquals("", c.state.value.input)
        assertNull(c.state.value.outcomeCount)
    }

    @Test fun again_goesBackToTheForm() = runTest {
        val (c, _) = setup()
        c.setInput("1")
        c.run()
        c.again()
        assertEquals(SplitPhase.EDITING, c.state.value.phase)
        assertTrue(c.state.value.canRun)
        assertFalse(c.saveFile { true })
    }
}
