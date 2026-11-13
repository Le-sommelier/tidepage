package app.pdfa.viewer

import app.pdfa.engine.EngineError
import app.pdfa.engine.fake.FakeEngine
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class ViewerViewModelTest {
    private fun source(bytes: ByteArray, name: String = "doc.pdf") = object : DocumentSource {
        override suspend fun read() = DocumentBytes(bytes, name)
    }

    private class Rig(
        ts: TestScope,
        val engine: FakeEngine,
        bytes: ByteArray,
        src: DocumentSource? = null,
        cache: TileCache = TileCache(32L * 1024 * 1024),
    ) {
        val d = StandardTestDispatcher(ts.testScheduler)
        val vm = ViewerViewModel(
            engine = engine,
            source = src ?: object : DocumentSource {
                override suspend fun read() = DocumentBytes(bytes, "doc.pdf")
            },
            clock = Clock { 1234L },
            dispatchers = ViewerDispatchers(d, d, d),
            tiles = cache,
        )
    }

    private fun TestScope.rig(
        pages: Int = 40,
        password: String? = null,
        engine: FakeEngine = FakeEngine(),
    ) = Rig(this, engine, FakeEngine.fakePdf(pageCount = pages, width = 500f, height = 700f, password = password))

    private fun Rig.ready(vw: Int = 1000, vh: Int = 1600) {
        vm.onViewportChanged(vw, vh, 1f)
    }

    // ---- opening ----

    @Test fun opensAndBecomesReady() = runTest {
        val r = rig(pages = 12)
        assertEquals(ViewerState.Loading, r.vm.state.value)
        advanceUntilIdle()
        val s = assertIs<ViewerState.Ready>(r.vm.state.value)
        assertEquals(12, s.pages.size)
        assertEquals(12, s.meta.pageCount)
        assertEquals("doc.pdf", r.vm.title.value)
    }

    @Test fun passwordRequiredStartsWithThreeTries() = runTest {
        val r = rig(password = "pw")
        advanceUntilIdle()
        assertEquals(ViewerState.PasswordRequired(3, lastAttemptWrong = false), r.vm.state.value)
    }

    @Test fun wrongThenRightPasswordOpens() = runTest {
        val r = rig(password = "pw")
        advanceUntilIdle()
        r.vm.submitPassword("nope".toCharArray())
        advanceUntilIdle()
        assertEquals(ViewerState.PasswordRequired(2, lastAttemptWrong = true), r.vm.state.value)
        r.vm.submitPassword("pw".toCharArray())
        advanceUntilIdle()
        assertIs<ViewerState.Ready>(r.vm.state.value)
    }

    @Test fun threeWrongPasswordsLockTheDocument() = runTest {
        val r = rig(password = "pw")
        advanceUntilIdle()
        repeat(2) {
            r.vm.submitPassword("x".toCharArray()); advanceUntilIdle()
            assertIs<ViewerState.PasswordRequired>(r.vm.state.value)
        }
        assertEquals(1, (r.vm.state.value as ViewerState.PasswordRequired).attemptsLeft)
        r.vm.submitPassword("x".toCharArray()); advanceUntilIdle()
        assertEquals(ViewerState.Locked, r.vm.state.value)
    }

    @Test fun lockedDocumentIgnoresFurtherPasswords() = runTest {
        val r = rig(password = "pw")
        advanceUntilIdle()
        repeat(3) { r.vm.submitPassword("x".toCharArray()); advanceUntilIdle() }
        r.vm.submitPassword("pw".toCharArray()); advanceUntilIdle()
        assertEquals(ViewerState.Locked, r.vm.state.value)
    }

    @Test fun passwordArrayIsWipedAfterUse() = runTest {
        val r = rig(password = "pw")
        advanceUntilIdle()
        val chars = "pw".toCharArray()
        r.vm.submitPassword(chars)
        assertTrue(chars.all { it == '\u0000' })
        advanceUntilIdle()
        assertIs<ViewerState.Ready>(r.vm.state.value)
    }

    @Test fun passwordIsIgnoredWhenNoneIsAsked() = runTest {
        val r = rig()
        advanceUntilIdle()
        val chars = "pw".toCharArray()
        r.vm.submitPassword(chars)
        assertTrue(chars.all { it == '\u0000' })
        advanceUntilIdle()
        assertIs<ViewerState.Ready>(r.vm.state.value)
    }

    @Test fun garbageBytesAreCorrupted() = runTest {
        val r = Rig(this, FakeEngine(), ByteArray(64) { 7 })
        advanceUntilIdle()
        assertIs<ViewerState.Corrupted>(r.vm.state.value)
    }

    @Test fun oversizedInputIsTooLarge() = runTest {
        val engine = FakeEngine(limits = FakeEngine.Limits(maxInputBytes = 10))
        val r = Rig(this, engine, FakeEngine.fakePdf(pageCount = 2))
        advanceUntilIdle()
        assertEquals(ViewerState.TooLarge, r.vm.state.value)
    }

    @Test fun unreadableSourceFails() = runTest {
        val src = object : DocumentSource {
            override suspend fun read(): DocumentBytes = throw IOException("gone")
        }
        val r = Rig(this, FakeEngine(), ByteArray(0), src)
        advanceUntilIdle()
        assertIs<ViewerState.Failed>(r.vm.state.value)
    }

    @Test fun otherEngineErrorsAreFailed() = runTest {
        val engine = FakeEngine()
        engine.failNext(EngineError.Io("disk"), "open")
        val r = Rig(this, engine, FakeEngine.fakePdf(pageCount = 2))
        advanceUntilIdle()
        assertIs<ViewerState.Failed>(r.vm.state.value)
    }

    @Test fun tryAgainReopens() = runTest {
        val engine = FakeEngine()
        engine.failNext(EngineError.Io("disk"), "open")
        val r = Rig(this, engine, FakeEngine.fakePdf(pageCount = 2))
        advanceUntilIdle()
        r.vm.start()
        advanceUntilIdle()
        assertIs<ViewerState.Ready>(r.vm.state.value)
    }

    @Test fun stateDoesNotLeakPasswordOrName() = runTest {
        val r = rig(password = "hunter2")
        advanceUntilIdle()
        r.vm.submitPassword("hunter2".toCharArray())
        advanceUntilIdle()
        assertFalse(r.vm.state.value.toString().contains("hunter2"))
        assertFalse(DocumentBytes(ByteArray(3), "secret-name.pdf").toString().contains("secret-name"))
    }

    // ---- tiles ----

    @Test fun viewportTriggersRenderingIntoTheCache() = runTest {
        val r = rig()
        advanceUntilIdle()
        r.ready()
        advanceUntilIdle()
        val plan = r.vm.view.value.plan
        assertTrue(plan.tiles.isNotEmpty())
        assertTrue(plan.tiles.all { r.vm.tiles.peek(it.key) })
    }

    @Test fun lowResIsRenderedBeforeSharpTiles() = runTest {
        val r = rig()
        advanceUntilIdle()
        val order = mutableListOf<TileKey>()
        val job = launch(kotlinx.coroutines.Dispatchers.Unconfined) { r.vm.tileReady.collect { order += it } }
        r.ready()
        advanceUntilIdle()
        job.cancel()
        assertTrue(order.first().lowRes)
    }

    @Test fun cacheIsFilledWithDistinctColoursPerPage() = runTest {
        val r = rig()
        advanceUntilIdle()
        r.ready()
        advanceUntilIdle()
        val keys = r.vm.view.value.plan.tiles.filter { it.kind == TileKind.SHARP }.map { it.key }
        val colours = keys.groupBy { it.page }.mapValues { (_, ks) -> r.vm.tiles.get(ks.first())!!.pixels().get(0) }
        assertTrue(colours.size >= 2)
        assertEquals(colours.size, colours.values.toSet().size)
    }

    @Test fun scrollingDropsRequestsForPagesThatLeftTheScreen() = runTest {
        val r = rig(pages = 200)
        advanceUntilIdle()
        r.ready()
        r.vm.onFastScroll(0.9f)
        advanceUntilIdle()
        assertTrue(r.vm.tileScheduler.droppedCount > 0)
    }

    // ---- scrolling and zoom ----

    @Test fun jumpToPageSetsCurrentPageAndBadge() = runTest {
        val r = rig(pages = 340)
        advanceUntilIdle()
        r.ready()
        r.vm.jumpToPage(11)
        assertEquals(11, r.vm.view.value.currentPage)
        assertEquals("12 / 340", r.vm.view.value.pageBadge)
    }

    @Test fun jumpToLastPageWorks() = runTest {
        val r = rig(pages = 30)
        advanceUntilIdle()
        r.ready()
        r.vm.jumpToPage(29)
        assertEquals(29, r.vm.view.value.currentPage)
        r.vm.jumpToPage(999)
        assertEquals(29, r.vm.view.value.currentPage)
        r.vm.jumpToPage(-5)
        assertEquals(0, r.vm.view.value.currentPage)
    }

    @Test fun doubleTapTogglesBetweenFitWidthAndTwoPointFive() = runTest {
        val r = rig()
        advanceUntilIdle()
        r.ready()
        r.vm.onDoubleTap(500f, 800f)
        assertEquals(2.5f, r.vm.view.value.pos.zoom)
        r.vm.onDoubleTap(500f, 800f)
        assertEquals(1f, r.vm.view.value.pos.zoom)
    }

    @Test fun doubleTapKeepsTheTappedPointUnderTheFinger() = runTest {
        val r = rig()
        advanceUntilIdle()
        r.ready()
        r.vm.transformBy(0f, 0f, 1f, 0f, -3000f)
        val before = r.vm.view.value
        val layout = before.layout!!
        val pp = layout.pagePointAt(before.pos, 400f, 600f)!!
        r.vm.onDoubleTap(400f, 600f)
        val after = r.vm.view.value
        val pp2 = layout.pagePointAt(after.pos, 400f, 600f)!!
        assertEquals(pp.page, pp2.page)
        assertEquals(pp.xPt, pp2.xPt, 0.5f)
        assertEquals(pp.yPt, pp2.yPt, 0.5f)
    }

    @Test fun pinchIsLimitedToEightTimes() = runTest {
        val r = rig()
        advanceUntilIdle()
        r.ready()
        r.vm.transformBy(500f, 800f, 100f, 0f, 0f)
        assertEquals(8f, r.vm.view.value.pos.zoom)
        r.vm.transformBy(500f, 800f, 0.001f, 0f, 0f)
        assertEquals(1f, r.vm.view.value.pos.zoom)
    }

    @Test fun panStopsAtTheTopAndReportsIt() = runTest {
        val r = rig()
        advanceUntilIdle()
        r.ready()
        assertFalse(r.vm.panBy(0f, 50f)) // already at the top
        assertTrue(r.vm.panBy(0f, -50f))
        assertEquals(50.0, r.vm.view.value.pos.scrollY)
    }

    @Test fun noHorizontalPanAtFitWidth() = runTest {
        val r = rig()
        advanceUntilIdle()
        r.ready()
        assertFalse(r.vm.panBy(-40f, 0f))
        r.vm.zoomTo(3f, 500f, 800f)
        assertTrue(r.vm.panBy(-40f, 0f))
    }

    @Test fun fastScrollMapsFractionToPage() = runTest {
        val r = rig(pages = 100)
        advanceUntilIdle()
        r.ready()
        r.vm.onFastScroll(0.5f)
        val page = r.vm.view.value.currentPage
        assertTrue(page in 45..55, "page $page")
        assertEquals(0.5f, r.vm.view.value.scrollFraction, 0.01f)
        assertEquals(50, r.vm.pageForFraction(0.5f).coerceIn(49, 51))
        assertEquals(0.5f, r.vm.fractionForPage(50), 0.01f)
        r.vm.onFastScroll(1f)
        assertEquals(99, r.vm.view.value.currentPage)
    }

    @Test fun resizingKeepsTheReadingPosition() = runTest {
        val r = rig(pages = 100)
        advanceUntilIdle()
        r.ready()
        r.vm.onFastScroll(0.5f)
        val page = r.vm.view.value.currentPage
        r.vm.onViewportChanged(1600, 1000, 1f)
        assertTrue(kotlin.math.abs(r.vm.view.value.currentPage - page) <= 2)
    }

    @Test fun gesturesBeforeReadyAreIgnored() = runTest {
        val r = rig()
        r.vm.jumpToPage(3)
        r.vm.onDoubleTap(1f, 1f)
        assertNull(r.vm.view.value.layout)
        advanceUntilIdle()
    }

    // ---- safe mode ----

    @Test fun safeModeBannerAppearsOnEngineRestartAndCanBeDismissed() = runTest {
        val engine = FakeEngine()
        engine.crashAfter(3) // open, meta, pageInfos succeed; the first render dies
        val r = Rig(this, engine, FakeEngine.fakePdf(pageCount = 10))
        advanceUntilIdle()
        assertIs<ViewerState.Ready>(r.vm.state.value)
        assertNull(r.vm.safeModeBanner.value)
        r.ready()
        advanceUntilIdle()
        assertEquals(SafeModeBanner(1234L), r.vm.safeModeBanner.value)
        assertIs<ViewerState.Ready>(r.vm.state.value) // the viewer keeps working
        r.vm.dismissSafeModeBanner()
        assertNull(r.vm.safeModeBanner.value)
    }

    @Test fun failingTilesDoNotSpinForever() = runTest {
        val engine = FakeEngine()
        val r = Rig(this, engine, FakeEngine.fakePdf(pageCount = 10, width = 500f, height = 700f))
        advanceUntilIdle()
        repeat(60) { engine.failNext(EngineError.Render("bad"), "renderTile") }
        r.ready()
        advanceUntilIdle()
        advanceTimeBy(120_000)
        assertTrue(r.vm.failedTiles.value.isNotEmpty())
        assertEquals(0, r.vm.tileScheduler.inFlightCount)
        assertEquals(0, r.vm.tileScheduler.backoffCount)
    }

    // ---- teardown ----

    @Test fun clearingTheViewModelClosesTheHandle() = runTest {
        val engine = FakeEngine()
        val r = Rig(this, engine, FakeEngine.fakePdf(pageCount = 5))
        advanceUntilIdle()
        val handle = assertNotNull(r.vm.docHandle)
        engine.meta(handle) // valid while open
        r.vm.release()
        advanceUntilIdle()
        assertFailsWith<EngineError.InvalidArgument> { engine.meta(handle) }
    }

    @Test fun clearingCancelsPendingTileRequests() = runTest {
        val engine = FakeEngine()
        val r = Rig(this, engine, FakeEngine.fakePdf(pageCount = 50, width = 500f, height = 700f))
        advanceUntilIdle()
        engine.hangFor("renderTile", 10_000)
        r.ready()
        advanceTimeBy(100)
        assertTrue(r.vm.tileScheduler.inFlightCount > 0)
        r.vm.release()
        assertEquals(0, r.vm.tileScheduler.inFlightCount)
        assertEquals(0, r.vm.tileScheduler.pendingCount)
        advanceUntilIdle()
        assertEquals(0, r.vm.tiles.count)
    }

    @Test fun clearingWhileLoadingDoesNotCrash() = runTest {
        val r = rig()
        r.vm.release()
        advanceUntilIdle()
        assertNull(r.vm.docHandle)
    }

    @Test fun releaseTwiceIsHarmless() = runTest {
        val r = rig()
        advanceUntilIdle()
        r.vm.release()
        r.vm.release()
        advanceUntilIdle()
    }

    @Test fun trimTilesShrinksTheCache() = runTest {
        val r = rig()
        advanceUntilIdle()
        r.ready()
        advanceUntilIdle()
        assertTrue(r.vm.tiles.sizeBytes > 0)
        r.vm.trimTiles(0)
        assertEquals(0L, r.vm.tiles.sizeBytes)
    }
}
