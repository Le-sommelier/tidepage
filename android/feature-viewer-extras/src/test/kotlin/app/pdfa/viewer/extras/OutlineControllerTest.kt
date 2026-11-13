package app.pdfa.viewer.extras

import app.pdfa.engine.DocHandle
import app.pdfa.engine.EngineError
import app.pdfa.engine.OutlinePage
import app.pdfa.engine.OutlineRow
import app.pdfa.engine.fake.FakeEngine
import app.pdfa.engine.fake.FakeOutline
import app.pdfa.engine.fake.FakePage
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class OutlineControllerTest {

    private fun TestScope.controller(
        engine: ScriptedEngine,
        handle: DocHandle,
        pageCount: Int,
        pageSize: Int = OutlineController.PAGE_SIZE,
        maxRows: Int = OutlineController.MAX_ROWS,
    ) = OutlineController(
        engine = engine,
        handle = handle,
        pageCount = pageCount,
        dispatcher = StandardTestDispatcher(testScheduler),
        pageSize = pageSize,
        maxRows = maxRows,
    )

    private fun flatOutline(n: Int, pages: Int = 5): List<FakeOutline> =
        (0 until n).map { FakeOutline(path = listOf(it), title = "T$it", page = it % pages) }

    private fun pdf(pages: Int, outline: List<FakeOutline>): ByteArray =
        FakeEngine.fakePdf(pages = List(pages) { FakePage(612f, 792f) }, outline = outline)

    @Test
    fun `load pages through the outline until next is null`() = runTest {
        val engine = ScriptedEngine()
        val h = engine.open(pdf(5, flatOutline(450)))
        val c = controller(engine, h, 5)

        val state = c.load()

        assertEquals(listOf(0 to 200, 200 to 200, 400 to 200), engine.outlineCalls)
        assertIs<OutlineState.Ready>(state)
        assertEquals(450, state.rowCount)
        assertEquals(450, state.rows.size)
        assertFalse(state.truncated)
    }

    @Test
    fun `an empty outline loads as ready with no rows`() = runTest {
        val engine = ScriptedEngine()
        val h = engine.open(pdf(1, emptyList()))
        val c = controller(engine, h, 1)

        val state = c.load()

        assertIs<OutlineState.Ready>(state)
        assertTrue(state.rows.isEmpty())
    }

    @Test
    fun `a row without a page is not navigable and pageOf returns null`() = runTest {
        val engine = ScriptedEngine()
        val h = engine.open(pdf(3, listOf(FakeOutline(listOf(0), "no page", null), FakeOutline(listOf(1), "page two", 2))))
        val c = controller(engine, h, 3)
        c.load()

        assertNull(c.pageOf(listOf(0)))
        assertEquals(2, c.pageOf(listOf(1)))
        assertNull(c.pageOf(listOf(7)), "an unknown path has no page")
    }

    @Test
    fun `toggle collapses and expands and republishes the visible rows`() = runTest {
        val engine = ScriptedEngine()
        val outline = listOf(
            FakeOutline(listOf(0), "Chapter", 0, open = true),
            FakeOutline(listOf(0, 0), "Section a", 0),
            FakeOutline(listOf(0, 1), "Section b", 1),
        )
        val h = engine.open(pdf(2, outline))
        val c = controller(engine, h, 2)
        assertEquals(3, (c.load() as OutlineState.Ready).rows.size)

        c.toggle(listOf(0))
        var ready = c.state.value as OutlineState.Ready
        assertEquals(1, ready.rows.size)
        assertFalse(ready.rows[0].expanded)

        c.toggle(listOf(0))
        ready = c.state.value as OutlineState.Ready
        assertEquals(3, ready.rows.size)
        assertTrue(ready.rows[0].expanded)
    }

    @Test
    fun `the engine open flag is the starting expansion`() = runTest {
        val engine = ScriptedEngine()
        val outline = listOf(
            FakeOutline(listOf(0), "open", 0, open = true),
            FakeOutline(listOf(0, 0), "child", 0),
            FakeOutline(listOf(1), "closed", 0, open = false),
            FakeOutline(listOf(1, 0), "hidden child", 0),
        )
        val h = engine.open(pdf(1, outline))
        val c = controller(engine, h, 1)

        val rows = (c.load() as OutlineState.Ready).rows

        assertEquals(listOf("open", "child", "closed"), rows.map { it.title })
        assertTrue(c.isExpanded(listOf(0)))
        assertFalse(c.isExpanded(listOf(1)))
    }

    @Test
    fun `toggling a row without children changes nothing`() = runTest {
        val engine = ScriptedEngine()
        val h = engine.open(pdf(1, flatOutline(2, pages = 1)))
        val c = controller(engine, h, 1)
        val before = c.load()

        c.toggle(listOf(0))

        assertEquals(before, c.state.value)
        assertFalse(c.isExpanded(listOf(0)))
    }

    @Test
    fun `an outline page that does not advance fails instead of looping`() = runTest {
        val engine = ScriptedEngine()
        val h = engine.openBlank(1)
        engine.outlineOverride = { _, _ -> OutlinePage(listOf(OutlineRow(listOf(0), "x", 0, false)), next = 0) }
        val c = controller(engine, h, 1)

        val state = c.load()

        assertIs<OutlineState.Failed>(state)
        assertFalse(state.engineLost)
        assertEquals(1, engine.outlineCalls.size)
    }

    @Test
    fun `an empty page that still points onward fails instead of looping`() = runTest {
        val engine = ScriptedEngine()
        val h = engine.openBlank(1)
        engine.outlineOverride = { offset, _ -> OutlinePage(emptyList(), next = offset + 1) }
        val c = controller(engine, h, 1)

        val state = c.load()

        assertIs<OutlineState.Failed>(state)
        assertFalse(state.engineLost)
    }

    @Test
    fun `engine death during the load reports engine loss`() = runTest {
        val engine = ScriptedEngine()
        val h = engine.openBlank(1)
        engine.outlineOverride = { _, _ -> throw EngineError.EngineDied("gone") }
        val c = controller(engine, h, 1)

        val state = c.load()

        assertIs<OutlineState.Failed>(state)
        assertTrue(state.engineLost)
    }

    @Test
    fun `a timeout during the load also reports engine loss`() = runTest {
        val engine = ScriptedEngine()
        val h = engine.openBlank(1)
        engine.outlineOverride = { _, _ -> throw EngineError.Timeout("slow") }
        val c = controller(engine, h, 1)

        assertTrue((c.load() as OutlineState.Failed).engineLost)
    }

    @Test
    fun `an ordinary engine error during the load is a failure the engine survived`() = runTest {
        val engine = ScriptedEngine()
        val h = engine.openBlank(1)
        engine.outlineOverride = { _, _ -> throw EngineError.Io("read") }
        val c = controller(engine, h, 1)

        val state = c.load()

        assertIs<OutlineState.Failed>(state)
        assertFalse(state.engineLost)
    }

    @Test
    fun `rows past the cap stop the load and mark it truncated`() = runTest {
        val engine = ScriptedEngine()
        val h = engine.open(pdf(5, flatOutline(450)))
        val c = controller(engine, h, 5, pageSize = 100, maxRows = 200)

        val state = c.load() as OutlineState.Ready

        assertEquals(200, state.rows.size)
        assertTrue(state.truncated)
        assertEquals(2, engine.outlineCalls.size)
    }

    @Test
    fun `a page with a page number past the document is not navigable`() = runTest {
        val engine = ScriptedEngine()
        val h = engine.openBlank(2)
        engine.outlineOverride = { _, _ -> OutlinePage(listOf(OutlineRow(listOf(0), "ghost", 9, false)), next = null) }
        val c = controller(engine, h, 2)
        c.load()

        assertNull(c.pageOf(listOf(0)))
    }

    @Test
    fun `a ten thousand row outline loads and toggles`() = runTest {
        val engine = ScriptedEngine()
        val h = engine.open(pdf(5, flatOutline(10_000)))
        val c = controller(engine, h, 5)

        val state = c.load() as OutlineState.Ready

        assertEquals(10_000, state.rowCount)
        assertEquals(10_000, state.rows.size)
        assertEquals(50, engine.outlineCalls.size)
        c.toggle(listOf(0))
        assertEquals(10_000, (c.state.value as OutlineState.Ready).rows.size, "flat rows have nothing to collapse")
    }

    @Test
    fun `a reload forgets the earlier toggles`() = runTest {
        val engine = ScriptedEngine()
        val outline = listOf(FakeOutline(listOf(0), "c", 0, open = true), FakeOutline(listOf(0, 0), "s", 0))
        val h = engine.open(pdf(1, outline))
        val c = controller(engine, h, 1)
        c.load()
        c.toggle(listOf(0))
        assertFalse(c.isExpanded(listOf(0)))

        c.load()

        assertTrue(c.isExpanded(listOf(0)))
    }

    @Test
    fun `a single page of 100k rows is capped before it is added`() = runTest {
        val engine = ScriptedEngine()
        val h = engine.openBlank(1)
        val rows = List(100_000) { OutlineRow(listOf(it), "t$it", 0, false) }
        engine.outlineOverride = { _, _ -> OutlinePage(rows, next = 100_000) }
        val c = controller(engine, h, 1)

        val state = c.load() as OutlineState.Ready

        assertEquals(OutlineController.MAX_ROWS, state.rowCount)
        assertEquals(OutlineController.MAX_ROWS, state.rows.size)
        assertTrue(state.truncated)
        assertEquals(1, engine.outlineCalls.size)
    }
}
