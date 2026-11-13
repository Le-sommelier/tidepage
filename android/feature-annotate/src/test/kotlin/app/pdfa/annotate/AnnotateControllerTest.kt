package app.pdfa.annotate

import app.pdfa.engine.DocHandle
import app.pdfa.engine.EditState
import app.pdfa.engine.EngineApi
import app.pdfa.engine.EngineError
import app.pdfa.engine.MarkupKind
import app.pdfa.engine.NewMarkup
import app.pdfa.engine.Pt
import app.pdfa.engine.Rect
import app.pdfa.engine.fake.FakeEngine
import app.pdfa.engine.fake.FakePage
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class AnnotateControllerTest {
    private val engine = FakeEngine()
    private val sent = ArrayList<NewMarkup>()

    /** The engine as the controller sees it: every addMarkup is recorded. */
    private val recording = object : EngineApi by engine {
        override suspend fun addMarkup(handle: DocHandle, markup: NewMarkup): EditState {
            val state = engine.addMarkup(handle, markup)
            sent += markup
            return state
        }
    }

    private suspend fun TestScope.setup(settleMs: Long = 0L): Pair<AnnotateController, DocHandle> {
        val handle = engine.open(FakeEngine.fakePdf(listOf(FakePage(width = 200f, height = 300f), FakePage(width = 300f, height = 200f))))
        val c = AnnotateController(edit = { change -> recording.change(handle) }, scope = backgroundScope, groupWindowMs = 1_000, settleMs = settleMs)
        return c to handle
    }

    private val line = listOf(Pt(10f, 10f), Pt(50f, 10f), Pt(90f, 40f))

    private fun AnnotateController.draw(page: Int = 0, pts: List<Pt> = line) = addStroke(page, pts, scale = 2f, pageWidth = 200f, pageHeight = 300f)

    @Test
    fun strokesCloseInTimeAreSentAsOneMarkupAfterTheWindow() = runTest {
        val (c, h) = setup()
        c.draw()
        advanceTimeBy(500)
        c.draw(pts = line.map { Pt(it.x, it.y + 20f) })
        assertEquals(1, c.state.value.pending.size, "one open group, drawn as a preview")
        assertEquals(MarkStatus.OPEN, c.state.value.pending.single().status)
        advanceTimeBy(900)
        runCurrent()
        assertTrue(sent.isEmpty(), "the window restarts after each stroke")
        advanceTimeBy(200)
        runCurrent()
        val m = sent.single()
        assertEquals(MarkupKind.INK, m.kind)
        assertEquals(2, m.strokes.size)
        assertEquals(0, m.page)
        assertTrue(c.state.value.pending.isEmpty(), "cleared once the engine has it")
        assertTrue(engine.state(h).canUndo)
    }

    @Test
    fun aStrokeOnAnotherPageOrInAnotherStyleStartsANewMarkup() = runTest {
        val (c, _) = setup()
        c.draw(page = 0)
        c.draw(page = 1)
        c.selectWidth(LineWidth.THICK)
        c.draw(page = 1)
        assertTrue(c.flush())
        assertEquals(listOf(0, 1, 1), sent.map { it.page })
        assertEquals(listOf(2.5f, 2.5f, 5f), sent.map { it.widthPt })
        assertTrue(sent.all { it.strokes.size == 1 })
    }

    @Test
    fun undoRemovesTheWholeGroup() = runTest {
        val (c, h) = setup()
        c.draw()
        c.draw()
        c.draw()
        assertTrue(c.flush())
        assertEquals(3, sent.single().strokes.size)
        assertTrue(c.undo())
        val s = engine.state(h)
        assertFalse(s.canUndo, "one undo step for the three strokes")
        assertTrue(s.canRedo)
    }

    @Test
    fun undoOfAnOpenGroupDropsItWithoutAnEngineCall() = runTest {
        val (c, h) = setup()
        c.draw()
        assertTrue(c.undo())
        assertTrue(c.state.value.pending.isEmpty())
        advanceTimeBy(5_000)
        assertTrue(sent.isEmpty())
        assertFalse(engine.state(h).canUndo)
    }

    @Test
    fun aFailedCommitIsKeptVisibleThenRetried() = runTest {
        val (c, h) = setup()
        engine.failNext(EngineError.Io("disk"), "addMarkup")
        c.draw()
        assertFalse(c.flush(), "something failed")
        val failed = c.state.value.pending.single()
        assertEquals(MarkStatus.FAILED, failed.status)
        assertEquals(AnnotateProblem.NOT_ADDED, c.state.value.problem)
        assertFalse(engine.state(h).canUndo)

        assertTrue(c.retryAll())
        assertTrue(c.state.value.pending.isEmpty())
        assertNull(c.state.value.problem)
        assertEquals(1, sent.size)
        assertTrue(engine.state(h).canUndo)
    }

    @Test
    fun aFailedMarkIsOnlyDroppedWhenTheUserDiscardsIt() = runTest {
        val (c, _) = setup()
        engine.failNext(EngineError.InvalidArgument("no"), "addMarkup")
        c.draw()
        c.flush()
        advanceTimeBy(60_000)
        assertEquals(MarkStatus.FAILED, c.state.value.pending.single().status, "still there after a minute")
        c.discardFailed()
        assertTrue(c.state.value.pending.isEmpty())
        assertNull(c.state.value.problem)
    }

    @Test
    fun aCommittedMarkSettlesBrieflyThenDisappears() = runTest {
        val (c, _) = setup(settleMs = 700)
        c.draw()
        c.flush()
        assertEquals(MarkStatus.SETTLING, c.state.value.pending.single().status)
        advanceTimeBy(800)
        runCurrent()
        assertTrue(c.state.value.pending.isEmpty())
    }

    @Test
    fun commitsAreSerialisedInOrder() = runTest {
        val (c, _) = setup()
        c.draw(page = 0)
        c.draw(page = 1) // closes the first group: both commits are queued
        c.selectTool(AnnotateTool.HIGHLIGHTER)
        assertTrue(c.highlight(0, listOf(Rect(10f, 10f, 80f, 22f)), 200f, 300f))
        assertTrue(c.flush())
        assertEquals(listOf(MarkupKind.INK, MarkupKind.INK, MarkupKind.HIGHLIGHT), sent.map { it.kind })
        assertEquals(listOf(0, 1, 0), sent.map { it.page })
    }

    @Test
    fun highlightSendsOneRectPerLineInTheHighlightColour() = runTest {
        val (c, _) = setup()
        c.selectTool(AnnotateTool.HIGHLIGHTER)
        c.selectSwatch(AnnotatePalette.highlight[2])
        assertTrue(c.highlight(0, listOf(Rect(10f, 10f, 80f, 22f), Rect(10f, 30f, 60f, 42f)), 200f, 300f))
        val m = sent.single()
        assertEquals(2, m.rects.size)
        assertEquals(AnnotatePalette.highlight[2].rgb, m.rgb)
        assertEquals(AnnotatePalette.highlight[2].opacity, m.opacity)
        assertFalse(c.highlight(0, emptyList(), 200f, 300f), "nothing to highlight")
    }

    @Test
    fun aNoteIsPlacedThenSavedWithItsText() = runTest {
        val (c, _) = setup()
        c.selectTool(AnnotateTool.NOTE)
        c.placeNote(1, Pt(295f, 10f), 300f, 200f)
        assertEquals(Rect(280f, 10f, 300f, 30f), c.state.value.noteDraft?.rect)
        assertFalse(c.saveNote("   "), "blank text adds nothing")
        assertTrue(c.saveNote("Check this figure"))
        val m = sent.single()
        assertEquals(MarkupKind.NOTE, m.kind)
        assertEquals(1, m.page)
        assertEquals("Check this figure", m.contents)
        assertNull(c.state.value.noteDraft)
    }

    @Test
    fun changingTheToolSendsTheOpenGroup() = runTest {
        val (c, _) = setup()
        c.draw()
        c.selectTool(AnnotateTool.NOTE)
        runCurrent()
        assertEquals(1, sent.size)
    }

    @Test
    fun aStrokeInProgressHoldsTheWindowOpen() = runTest {
        val (c, _) = setup()
        c.draw()
        c.strokeStarted()
        advanceTimeBy(5_000)
        runCurrent()
        assertTrue(sent.isEmpty(), "not sent while the next stroke is drawn")
        c.draw()
        advanceTimeBy(1_100)
        runCurrent()
        assertEquals(2, sent.single().strokes.size)
    }

    @Test
    fun aTapIsSentAsAValidDot() = runTest {
        val (c, _) = setup()
        c.draw(pts = listOf(Pt(40f, 40f)))
        c.flush()
        val stroke = sent.single().strokes.single().points
        assertEquals(2, stroke.size)
    }

    @Test
    fun aGroupNeverExceedsTheEnginePointCap() = runTest {
        val (c, _) = setup()
        // Zigzags survive simplification point for point.
        val zig = (0 until 60_000).map { Pt((it % 190).toFloat() + 5f, if (it % 2 == 0) 10f else 200f) }
        c.draw(pts = zig)
        c.draw(pts = zig)
        c.flush()
        assertTrue(sent.size >= 2)
        assertTrue(sent.all { m -> m.strokes.sumOf { it.points.size } <= MarkupLimits.MAX_POINTS_PER_GROUP })
    }
}
