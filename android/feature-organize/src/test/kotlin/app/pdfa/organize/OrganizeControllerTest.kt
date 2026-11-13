package app.pdfa.organize

import app.pdfa.engine.DocHandle
import app.pdfa.engine.EditState
import app.pdfa.engine.EngineApi
import app.pdfa.engine.EngineError
import app.pdfa.engine.fake.FakeEngine
import app.pdfa.engine.fake.FakePage
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class OrganizeControllerTest {
    private val engine = FakeEngine()
    private var edits = 0

    /** A document of [n] pages labelled P0..P(n-1), each a different size. */
    private suspend fun setup(n: Int = 5): Pair<OrganizeController, DocHandle> {
        val pages = List(n) { FakePage(width = 100f + it, height = 200f + it, label = "P$it") }
        val handle = engine.open(FakeEngine.fakePdf(pages))
        val c = OrganizeController(engine, handle) { change ->
            edits++
            engine.change(handle)
        }
        c.load()
        return c to handle
    }

    private fun OrganizeController.labels() = state.value.pages.map { it.label }

    private fun OrganizeController.select(vararg pages: Int) {
        clearSelection()
        pages.forEach { toggle(it) }
    }

    @Test
    fun load_readsPagesAndUndoFlags() = runTest {
        val (c, _) = setup(3)
        assertEquals(listOf("P0", "P1", "P2"), c.labels())
        assertTrue(c.state.value.loaded)
        assertFalse(c.state.value.canUndo)
        assertTrue(c.state.value.selection.isEmpty())
    }

    @Test
    fun selection_toggleSelectOnlyAllClear_ignoreOutOfRange() = runTest {
        val (c, _) = setup(3)
        c.toggle(1); c.toggle(2)
        assertEquals(setOf(1, 2), c.state.value.selection)
        c.toggle(1)
        assertEquals(setOf(2), c.state.value.selection)
        c.selectOnly(0)
        assertEquals(setOf(0), c.state.value.selection)
        c.toggle(9); c.selectOnly(-1)
        assertEquals(setOf(0), c.state.value.selection)
        c.selectAll()
        assertEquals(setOf(0, 1, 2), c.state.value.selection)
        c.clearSelection()
        assertTrue(c.state.value.selection.isEmpty())
    }

    @Test
    fun rotateRight_turnsOnlySelectedPages_andKeepsSelection() = runTest {
        val (c, _) = setup(3)
        c.select(1)
        c.rotateRight()
        val p = c.state.value.pages
        // A quarter turn swaps width and height of page 1 only.
        assertEquals(201f, p[1].widthPt); assertEquals(101f, p[1].heightPt)
        assertEquals(100f, p[0].widthPt); assertEquals(202f, p[2].heightPt)
        assertEquals(setOf(1), c.state.value.selection)
        assertTrue(c.state.value.canUndo)
        assertEquals(1, c.state.value.revision - 1) // load bumped once, the rotation once more
    }

    @Test
    fun rotateLeft_andRightCancelOut() = runTest {
        val (c, _) = setup(2)
        c.select(0, 1)
        c.rotateLeft()
        assertEquals(200f, c.state.value.pages[0].widthPt)
        c.rotateRight()
        assertEquals(100f, c.state.value.pages[0].widthPt)
    }

    @Test
    fun actionsWithoutSelection_doNothing() = runTest {
        val (c, _) = setup(3)
        c.rotateLeft(); c.rotateRight(); c.duplicate(); c.moveEarlier(); c.moveLater(); c.moveTo(0)
        assertNull(c.delete())
        assertNull(c.extract())
        assertEquals(0, edits)
    }

    @Test
    fun delete_removesPages_clearsSelection_returnsIndices() = runTest {
        val (c, _) = setup(5)
        c.select(1, 3)
        assertEquals(listOf(1, 3), c.delete())
        assertEquals(listOf("P0", "P2", "P4"), c.labels())
        assertTrue(c.state.value.selection.isEmpty())
        assertNull(c.state.value.problem)
    }

    @Test
    fun delete_everyPage_isRefusedWithoutAskingTheEngine() = runTest {
        val (c, _) = setup(3)
        c.selectAll()
        assertNull(c.delete())
        assertEquals(OrganizeProblem.CANNOT_DELETE_ALL, c.state.value.problem)
        assertEquals(3, c.state.value.pages.size)
        assertEquals(setOf(0, 1, 2), c.state.value.selection)
        assertEquals(0, edits)
        c.dismissProblem()
        assertNull(c.state.value.problem)
    }

    @Test
    fun delete_whenEngineSaysInvalidArgument_reportsAndKeepsTheDocument() = runTest {
        val (c, _) = setup(3)
        c.select(0)
        engine.failNext(EngineError.InvalidArgument("cannot delete every page of a document"), "delete")
        assertNull(c.delete())
        assertEquals(OrganizeProblem.ACTION_FAILED, c.state.value.problem)
        assertNotNull(c.state.value.problemDetail)
        assertEquals(3, c.state.value.pages.size)
        assertFalse(c.state.value.busy)
        assertEquals(setOf(0), c.state.value.selection)
    }

    @Test
    fun engineError_leavesControllerUsable() = runTest {
        val (c, _) = setup(3)
        c.select(2)
        engine.failNext(EngineError.Io("disk"), "rotate")
        c.rotateRight()
        assertEquals(OrganizeProblem.ACTION_FAILED, c.state.value.problem)
        assertFalse(c.state.value.busy)
        c.rotateRight()
        assertNull(c.state.value.problem)
        assertEquals(202f, c.state.value.pages[2].widthPt)
    }

    @Test
    fun duplicate_putsEachCopyAfterItsOriginal_andSelectsTheCopies() = runTest {
        val (c, _) = setup(4)
        c.select(0, 2)
        c.duplicate()
        assertEquals(listOf("P0", "P0", "P1", "P2", "P2", "P3"), c.labels())
        assertEquals(setOf(1, 4), c.state.value.selection)
    }

    @Test
    fun moveEarlier_singlePage() = runTest {
        val (c, _) = setup(4)
        c.select(2)
        c.moveEarlier()
        assertEquals(listOf("P0", "P2", "P1", "P3"), c.labels())
        assertEquals(setOf(1), c.state.value.selection)
    }

    @Test
    fun moveLater_singlePage() = runTest {
        val (c, _) = setup(4)
        c.select(1)
        c.moveLater()
        assertEquals(listOf("P0", "P2", "P1", "P3"), c.labels())
        assertEquals(setOf(2), c.state.value.selection)
    }

    @Test
    fun moveEarlier_atTheStart_andMoveLater_atTheEnd_doNothing() = runTest {
        val (c, _) = setup(4)
        c.select(0)
        assertFalse(c.state.value.canMoveEarlier)
        c.moveEarlier()
        c.select(3)
        assertFalse(c.state.value.canMoveLater)
        c.moveLater()
        c.select(2, 3)
        c.moveLater()
        c.select(0, 1)
        c.moveEarlier()
        assertEquals(0, edits)
        assertEquals(listOf("P0", "P1", "P2", "P3"), c.labels())
    }

    @Test
    fun moveBlock_contiguousSelectionMovesTogether() = runTest {
        val (c, _) = setup(6)
        c.select(1, 2)
        c.moveLater()
        assertEquals(listOf("P0", "P3", "P1", "P2", "P4", "P5"), c.labels())
        assertEquals(setOf(2, 3), c.state.value.selection)
        c.moveLater(); c.moveLater() // lands at the end, 4..5 is the last position
        assertEquals(listOf("P0", "P3", "P4", "P5", "P1", "P2"), c.labels())
        assertEquals(setOf(4, 5), c.state.value.selection)
        assertFalse(c.state.value.canMoveLater)
        c.moveEarlier()
        assertEquals(setOf(3, 4), c.state.value.selection)
    }

    @Test
    fun moveEarlier_gathersScatteredSelectionAtTheFirstPageMinusOne() = runTest {
        val (c, _) = setup(6)
        c.select(2, 4)
        c.moveEarlier()
        assertEquals(listOf("P0", "P2", "P4", "P1", "P3", "P5"), c.labels().let { it })
        assertEquals(setOf(1, 2), c.state.value.selection)
    }

    @Test
    fun moveLater_scatteredSelectionIsClampedToTheLastValidIndex() = runTest {
        val (c, _) = setup(5)
        c.select(0, 4)
        c.moveLater()
        // Valid toIndex is 0..3; the block lands at 1.
        assertEquals(listOf("P1", "P0", "P4", "P2", "P3"), c.labels())
        assertEquals(setOf(1, 2), c.state.value.selection)
    }

    @Test
    fun moveTo_landsFirstSelectedPageAtTheIndex_clamped() = runTest {
        val (c, _) = setup(5)
        c.select(0)
        c.moveTo(3)
        assertEquals(listOf("P1", "P2", "P3", "P0", "P4"), c.labels())
        assertEquals(setOf(3), c.state.value.selection)
        c.moveTo(99)
        assertEquals(listOf("P1", "P2", "P3", "P4", "P0"), c.labels())
        assertEquals(setOf(4), c.state.value.selection)
        c.moveTo(-5)
        assertEquals(listOf("P0", "P1", "P2", "P3", "P4"), c.labels())
        assertEquals(setOf(0), c.state.value.selection)
        // Block of two: the largest valid index is count - 2 = 3.
        c.select(0, 1)
        c.moveTo(99)
        assertEquals(listOf("P2", "P3", "P4", "P0", "P1"), c.labels())
        assertEquals(setOf(3, 4), c.state.value.selection)
    }

    @Test
    fun moveTo_theSamePlace_doesNothing() = runTest {
        val (c, _) = setup(4)
        c.select(2)
        c.moveTo(2)
        assertEquals(0, edits)
    }

    @Test
    fun moveBlockMaths() {
        assertEquals(0 to setOf(0), moveBlock(listOf(3), -1, 5))
        assertEquals(4 to setOf(4), moveBlock(listOf(3), 9, 5))
        assertEquals(3 to setOf(3, 4), moveBlock(listOf(0, 1), 3, 5))
        assertEquals(2 to setOf(2, 3, 4), moveBlock(listOf(0, 2, 4), 9, 5))
        assertEquals(0 to setOf(0, 1, 2, 3, 4), moveBlock((0..4).toList(), 1, 5))
    }

    @Test
    fun insertBlank_afterTheLastSelectedPage_sizedLikeIt() = runTest {
        val (c, _) = setup(3)
        c.select(0, 1)
        c.insertBlank()
        assertEquals(4, c.state.value.pages.size)
        val blank = c.state.value.pages[2]
        assertEquals(101f, blank.widthPt); assertEquals(201f, blank.heightPt)
        assertEquals("P2", c.state.value.pages[3].label)
        assertEquals(setOf(2), c.state.value.selection)
    }

    @Test
    fun insertBlank_withNoSelection_goesAtTheEnd_sizedLikeTheLastPage() = runTest {
        val (c, _) = setup(3)
        c.insertBlank()
        assertEquals(4, c.state.value.pages.size)
        assertEquals(102f, c.state.value.pages[3].widthPt)
        assertEquals(setOf(3), c.state.value.selection)
    }

    @Test
    fun insertBlank_afterARotatedPage_usesItsOnScreenSize() = runTest {
        val (c, _) = setup(2)
        c.select(0)
        c.rotateRight()
        c.insertBlank()
        assertEquals(200f, c.state.value.pages[1].widthPt)
        assertEquals(100f, c.state.value.pages[1].heightPt)
    }

    @Test
    fun undoRedo_restoreThePages() = runTest {
        val (c, _) = setup(4)
        c.select(1)
        c.delete()
        assertEquals(3, c.state.value.pages.size)
        assertTrue(c.state.value.canUndo)
        c.undo()
        assertEquals(listOf("P0", "P1", "P2", "P3"), c.labels())
        assertFalse(c.state.value.canUndo)
        assertTrue(c.state.value.canRedo)
        c.redo()
        assertEquals(listOf("P0", "P2", "P3"), c.labels())
        assertFalse(c.state.value.canRedo)
    }

    @Test
    fun undoAfterDelete_restoresPagesAndClearsSelection() = runTest {
        val (c, _) = setup(3)
        c.select(0)
        c.duplicate()
        c.select(0, 1)
        c.delete()
        assertEquals(2, c.state.value.pages.size)
        c.undo()
        assertEquals(4, c.state.value.pages.size)
        assertTrue(c.state.value.selection.isEmpty())
    }

    @Test
    fun extract_returnsTheChosenPagesAsADocument_andChangesNothing() = runTest {
        val (c, _) = setup(5)
        c.select(3, 1)
        val bytes = assertNotNull(c.extract())
        val copy = engine.open(bytes)
        assertEquals(listOf("P1", "P3"), engine.pageInfos(copy).map { it.label })
        assertEquals(0, edits)
        assertEquals(5, c.state.value.pages.size)
        assertEquals(setOf(1, 3), c.state.value.selection)
    }

    @Test
    fun extract_failureIsReportedNotThrown() = runTest {
        val (c, _) = setup(3)
        c.select(0)
        engine.failNext(EngineError.Io("disk"), "extract")
        assertNull(c.extract())
        assertEquals(OrganizeProblem.ACTION_FAILED, c.state.value.problem)
        assertFalse(c.state.value.busy)
    }

    @Test
    fun anActionWhileAnotherRuns_isIgnored() = runTest(UnconfinedTestDispatcher()) {
        val pages = List(3) { FakePage(100f + it, 200f + it, label = "P$it") }
        val handle = engine.open(FakeEngine.fakePdf(pages))
        val gate = CompletableDeferred<Unit>()
        val c = OrganizeController(engine, handle) { change ->
            edits++
            gate.await()
            engine.change(handle)
        }
        c.load()
        c.select(0)
        val first = launch { c.rotateRight() }
        assertTrue(c.state.value.busy)
        c.rotateLeft() // ignored: busy
        assertNull(c.delete())
        gate.complete(Unit)
        first.join()
        assertEquals(1, edits)
        assertFalse(c.state.value.busy)
        assertEquals(200f, c.state.value.pages[0].widthPt)
    }
}
