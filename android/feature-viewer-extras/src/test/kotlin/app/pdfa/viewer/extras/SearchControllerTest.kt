package app.pdfa.viewer.extras

import app.pdfa.engine.DocHandle
import app.pdfa.engine.EngineError
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class SearchControllerTest {

    private fun TestScope.controller(
        engine: ScriptedEngine,
        handle: DocHandle,
        pageCount: Int,
        maxHits: Int = SearchController.MAX_HITS,
    ) = SearchController(
        engine = engine,
        handle = handle,
        pageCount = pageCount,
        dispatcher = StandardTestDispatcher(testScheduler),
        scope = this,
        maxHits = maxHits,
    )

    @Test
    fun `searches in order from the start page and wraps around`() = runTest {
        val engine = ScriptedEngine()
        val h = engine.openText(listOf("a foo", "foo", "", "x foo"))
        val c = controller(engine, h, 4)

        c.submit("foo", currentPage = 2)
        advanceUntilIdle()

        assertEquals(listOf(2, 3, 0, 1), engine.searchedPages)
        assertEquals(listOf(3, 0, 1), c.state.value.hits.map { it.page })
        assertEquals(SearchPhase.DONE, c.state.value.phase)
    }

    @Test
    fun `the cold search emits after every page and ends with done`() = runTest {
        val engine = ScriptedEngine()
        val h = engine.openText(listOf("foo", "foo", "foo"))
        val c = controller(engine, h, 3)

        val snaps = c.search("foo", SearchOptions(), 0).toList()

        assertEquals(listOf(0, 1, 2, 3, 3), snaps.map { it.scannedPages })
        assertEquals(listOf(0, 1, 2, 3, 3), snaps.map { it.hits.size })
        assertEquals(SearchPhase.DONE, snaps.last().phase)
        assertEquals(listOf(SearchPhase.SCANNING, SearchPhase.SCANNING, SearchPhase.SCANNING, SearchPhase.SCANNING, SearchPhase.DONE), snaps.map { it.phase })
    }

    @Test
    fun `nothing runs before the debounce delay and everything runs after it`() = runTest {
        val engine = ScriptedEngine()
        val h = engine.openText(listOf("foo"))
        val c = controller(engine, h, 1)

        c.submit("foo")
        advanceTimeBy(SearchController.DEBOUNCE_MS - 1)
        runCurrent()
        assertTrue(engine.searchedPages.isEmpty())

        advanceTimeBy(1)
        runCurrent()
        advanceUntilIdle()
        assertEquals(listOf(0), engine.searchedPages)
    }

    @Test
    fun `typing again within the debounce replaces the pending query`() = runTest {
        val engine = ScriptedEngine()
        val h = engine.openText(listOf("foo bar"))
        val c = controller(engine, h, 1)

        c.submit("fo")
        advanceTimeBy(100)
        c.submit("bar")
        advanceUntilIdle()

        assertEquals(listOf("bar"), engine.searchQueries)
        assertEquals("bar", c.state.value.query)
        assertEquals(1, c.state.value.hits.size)
    }

    @Test
    fun `a new query cancels a search that is already running`() = runTest {
        val engine = ScriptedEngine()
        val h = engine.openText(listOf("foo", "foo", "foo"))
        val c = controller(engine, h, 3)
        var blockOnce = true
        engine.searchPageHook = { page ->
            if (page == 1 && blockOnce) {
                blockOnce = false
                delay(10_000)
            }
        }

        c.submit("foo")
        advanceTimeBy(SearchController.DEBOUNCE_MS + 1)
        runCurrent()
        c.submit("bar")
        advanceUntilIdle()

        assertEquals(listOf("foo", "foo", "bar", "bar", "bar"), engine.searchQueries)
        assertEquals(listOf(0, 1, 0, 1, 2), engine.searchedPages)
        assertEquals("bar", c.state.value.query)
        assertEquals(SearchPhase.DONE, c.state.value.phase)
    }

    @Test
    fun `case sensitive option is passed through`() = runTest {
        val engine = ScriptedEngine()
        val h = engine.openText(listOf("Foo foo"))
        val c = controller(engine, h, 1)

        c.submit("foo")
        advanceUntilIdle()
        assertEquals(2, c.state.value.hits.size)

        c.submit("foo", SearchOptions(caseSensitive = true))
        advanceUntilIdle()
        assertEquals(1, c.state.value.hits.size)
    }

    @Test
    fun `whole word option is passed through`() = runTest {
        val engine = ScriptedEngine()
        val h = engine.openText(listOf("foobar foo"))
        val c = controller(engine, h, 1)

        c.submit("foo")
        advanceUntilIdle()
        assertEquals(2, c.state.value.hits.size)

        c.submit("foo", SearchOptions(wholeWord = true))
        advanceUntilIdle()
        assertEquals(1, c.state.value.hits.size)
    }

    @Test
    fun `an empty query clears the results and runs nothing`() = runTest {
        val engine = ScriptedEngine()
        val h = engine.openText(listOf("foo"))
        val c = controller(engine, h, 1)
        c.submit("foo")
        advanceUntilIdle()
        val before = engine.searchedPages.size

        c.submit("")
        advanceUntilIdle()

        assertEquals(SearchPhase.IDLE, c.state.value.phase)
        assertTrue(c.state.value.hits.isEmpty())
        assertNull(c.state.value.counter())
        assertEquals(before, engine.searchedPages.size)
    }

    @Test
    fun `the hit cap stops the search and marks it truncated`() = runTest {
        val engine = ScriptedEngine()
        val h = engine.openText(listOf("a a a a", "a a a a"))
        val c = controller(engine, h, 2, maxHits = 3)

        c.submit("a")
        advanceUntilIdle()

        assertEquals(3, c.state.value.hits.size)
        assertTrue(c.state.value.truncated)
        assertTrue(c.state.value.counter()!!.truncated)
    }

    @Test
    fun `reaching the cap without more matches is not truncated`() = runTest {
        val engine = ScriptedEngine()
        val h = engine.openText(listOf("a a", ""))
        val c = controller(engine, h, 2, maxHits = 2)

        c.submit("a")
        advanceUntilIdle()

        assertEquals(2, c.state.value.hits.size)
        assertFalse(c.state.value.truncated)
    }

    @Test
    fun `a per-page engine error is counted and the search goes on`() = runTest {
        val engine = ScriptedEngine()
        val h = engine.openText(listOf("foo", "foo", "foo"))
        val c = controller(engine, h, 3)
        engine.searchErrors[1] = EngineError.Io("disk read failed")

        c.submit("foo")
        advanceUntilIdle()

        assertEquals(listOf(0, 2), c.state.value.hits.map { it.page })
        assertEquals(1, c.state.value.errorPages)
        assertEquals(SearchPhase.DONE, c.state.value.phase)
        assertNull(c.state.value.failure)
    }

    @Test
    fun `engine death aborts the search, clears the hits and reports engine loss`() = runTest {
        val engine = ScriptedEngine()
        val h = engine.openText(listOf("foo", "foo", "foo", "foo"))
        val c = controller(engine, h, 4)
        engine.searchErrors[1] = EngineError.EngineDied("oom")

        c.submit("foo")
        advanceUntilIdle()

        assertEquals(SearchPhase.FAILED, c.state.value.phase)
        assertEquals(SearchFailure.ENGINE_LOST, c.state.value.failure)
        assertTrue(c.state.value.hits.isEmpty())
        assertEquals(listOf(0, 1), engine.searchedPages)
    }

    @Test
    fun `a timeout aborts the search like engine death`() = runTest {
        val engine = ScriptedEngine()
        val h = engine.openText(listOf("foo", "foo", "foo"))
        val c = controller(engine, h, 3)
        engine.searchErrors[0] = EngineError.Timeout("watchdog")

        c.submit("foo")
        advanceUntilIdle()

        assertEquals(SearchPhase.FAILED, c.state.value.phase)
        assertEquals(SearchFailure.ENGINE_LOST, c.state.value.failure)
        assertEquals(listOf(0), engine.searchedPages)
    }

    @Test
    fun `next and previous wrap around and return the page and rect`() = runTest {
        val engine = ScriptedEngine()
        val h = engine.openText(listOf("foo", "", "foo foo"))
        val c = controller(engine, h, 3)
        c.submit("foo")
        advanceUntilIdle()
        val hits = c.state.value.hits
        assertEquals(listOf(0, 2, 2), hits.map { it.page })

        // The first hit is selected when results arrive, but nothing has scrolled to it yet: the
        // first next() goes to that hit, the second one advances.
        assertEquals(0, c.state.value.current)

        val first = c.next()!!
        assertEquals(0, first.index)
        assertEquals(0, first.page)
        assertEquals(hits[0].rect, first.rect)

        val a = c.next()!!
        assertEquals(1, a.index)
        assertEquals(2, a.page)
        assertEquals(hits[1].rect, a.rect)
        assertEquals(2, c.next()!!.index)
        assertEquals(0, c.next()!!.index, "next wraps from the last hit to the first")

        assertEquals(2, c.previous()!!.index, "previous wraps from the first hit to the last")
        assertEquals(1, c.previous()!!.index)
    }

    @Test
    fun `a new query makes the first next go to hit 1 again`() = runTest {
        val engine = ScriptedEngine()
        val h = engine.openText(listOf("foo bar", "foo bar"))
        val c = controller(engine, h, 2)
        c.submit("foo")
        advanceUntilIdle()
        c.next()
        assertEquals(1, c.next()!!.index)

        c.submit("bar")
        advanceUntilIdle()
        assertEquals(0, c.next()!!.index)
        assertEquals(1, c.next()!!.index)
    }

    @Test
    fun `next and previous return null when there are no hits`() = runTest {
        val engine = ScriptedEngine()
        val h = engine.openText(listOf("nothing here"))
        val c = controller(engine, h, 1)
        c.submit("zzz")
        advanceUntilIdle()

        assertNull(c.next())
        assertNull(c.previous())
    }

    @Test
    fun `the counter reads position of total`() = runTest {
        val engine = ScriptedEngine()
        val h = engine.openText(listOf("foo foo foo"))
        val c = controller(engine, h, 1)
        c.submit("foo")
        advanceUntilIdle()
        assertEquals(SearchCounter(position = 1, total = 3, truncated = false), c.state.value.counter())

        c.next()
        assertEquals(SearchCounter(position = 1, total = 3, truncated = false), c.state.value.counter(), "first press stays on hit 1")
        c.next()
        assertEquals(SearchCounter(position = 2, total = 3, truncated = false), c.state.value.counter())
        c.next()
        assertEquals(SearchCounter(position = 3, total = 3, truncated = false), c.state.value.counter())
    }

    @Test
    fun `retry re-runs the last query after a failure`() = runTest {
        val engine = ScriptedEngine()
        val h = engine.openText(listOf("foo", "foo", "foo"))
        val c = controller(engine, h, 3)
        engine.searchErrorsOnce[1] = EngineError.Timeout("slow")

        c.submit("foo")
        advanceUntilIdle()
        assertEquals(SearchPhase.FAILED, c.state.value.phase)

        assertTrue(c.retry())
        advanceUntilIdle()

        assertEquals(SearchPhase.DONE, c.state.value.phase)
        assertEquals(3, c.state.value.hits.size)
    }

    @Test
    fun `retry does nothing when the last search did not fail`() = runTest {
        val engine = ScriptedEngine()
        val h = engine.openText(listOf("foo"))
        val c = controller(engine, h, 1)
        c.submit("foo")
        advanceUntilIdle()

        assertFalse(c.retry())
        assertEquals(1, engine.searchedPages.size)
    }

    @Test
    fun `an engine restart adopts the new handle and re-runs the query once`() = runTest {
        val engine = ScriptedEngine()
        val h1 = engine.openText(listOf("foo", "foo"))
        val c = controller(engine, h1, 2)
        c.submit("foo")
        advanceUntilIdle()
        assertEquals(2, c.state.value.hits.size)

        val h2 = engine.openText(listOf("foo", "foo"))
        c.onEngineRestarted(h2)
        advanceUntilIdle()

        assertEquals(listOf(h1.id, h1.id, h2.id, h2.id), engine.searchHandles)
        assertEquals(SearchPhase.DONE, c.state.value.phase)
        assertEquals(2, c.state.value.hits.size)
    }

    @Test
    fun `an engine restart with no earlier query does nothing`() = runTest {
        val engine = ScriptedEngine()
        val h1 = engine.openText(listOf("foo"))
        val c = controller(engine, h1, 1)

        c.onEngineRestarted(engine.openText(listOf("foo")))
        advanceUntilIdle()

        assertTrue(engine.searchedPages.isEmpty())
        assertEquals(SearchPhase.IDLE, c.state.value.phase)
    }

    @Test
    fun `real engine death through the fake invalidates the handle and the search reports it`() = runTest {
        val engine = ScriptedEngine()
        val h = engine.openText(listOf("foo", "foo"))
        val c = controller(engine, h, 2)
        engine.fake.failNext(EngineError.EngineDied("fake crash"), "searchPage")

        c.submit("foo")
        advanceUntilIdle()

        assertEquals(SearchFailure.ENGINE_LOST, c.state.value.failure)
        assertTrue(c.state.value.hits.isEmpty())
    }

    @Test
    fun `a search that is cleared mid-flight never reports stale hits`() = runTest {
        val engine = ScriptedEngine()
        val h = engine.openText(listOf("foo", "foo"))
        val c = controller(engine, h, 2)
        engine.searchPageHook = { delay(1_000) }

        c.submit("foo")
        advanceTimeBy(SearchController.DEBOUNCE_MS + 1)
        runCurrent()
        c.clear()
        advanceUntilIdle()

        assertEquals(SearchPhase.IDLE, c.state.value.phase)
        assertTrue(c.state.value.hits.isEmpty())
    }
}
