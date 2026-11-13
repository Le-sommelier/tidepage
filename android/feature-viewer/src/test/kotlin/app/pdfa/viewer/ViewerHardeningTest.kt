package app.pdfa.viewer

import app.pdfa.engine.DocHandle
import app.pdfa.engine.EngineApi
import app.pdfa.engine.EngineError
import app.pdfa.engine.EngineEvent
import app.pdfa.engine.PageInfo
import app.pdfa.engine.TileBitmap
import app.pdfa.engine.TileRequest
import app.pdfa.engine.fake.FakeEngine
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** An engine that records opens and closes, can return an empty page list, and can kill itself on chosen tiles. */
private class TrackingEngine(val fake: FakeEngine = FakeEngine()) : EngineApi by fake {
    val opened = ArrayList<DocHandle>()
    val closeCalls = HashMap<Long, Int>()
    var openCalls = 0
    var emptyPages = false
    var killTile: (TileRequest) -> Boolean = { false }
    var crashes = 0
    val renders = ArrayList<TileRequest>()
    private val bus = MutableSharedFlow<EngineEvent>(extraBufferCapacity = 64)
    override val events: Flow<EngineEvent> = bus

    override suspend fun open(bytes: ByteArray, password: String?): DocHandle {
        openCalls++
        return fake.open(bytes, password).also { opened += it }
    }

    override suspend fun close(handle: DocHandle) {
        closeCalls.merge(handle.id, 1, Int::plus)
        fake.close(handle)
    }

    override suspend fun pageInfos(handle: DocHandle): List<PageInfo> =
        if (emptyPages) emptyList() else fake.pageInfos(handle)

    override suspend fun renderTile(handle: DocHandle, request: TileRequest): TileBitmap {
        renders += request
        if (killTile(request)) {
            crashes++
            bus.emit(EngineEvent.SafeModeRestarted(0L, "killer tile"))
            throw EngineError.EngineDied("killer tile")
        }
        return fake.renderTile(handle, request)
    }

    fun assertNoLeaks() {
        for (h in opened) assertEquals(1, closeCalls[h.id], "handle ${h.id} closed ${closeCalls[h.id]} times")
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
class ViewerHardeningTest {
    private fun TestScope.vm(engine: EngineApi, pages: Int = 20, password: String? = null, bytes: ByteArray? = null): ViewerViewModel {
        val d = StandardTestDispatcher(testScheduler)
        val data = bytes ?: FakeEngine.fakePdf(pageCount = pages, width = 500f, height = 700f, password = password)
        return ViewerViewModel(
            engine,
            object : DocumentSource {
                override suspend fun read() = DocumentBytes(data, "doc.pdf")
            },
            Clock { 1L },
            ViewerDispatchers(d, d, d),
        )
    }

    // ---- handle leaks ----

    @Test fun releaseWhileMetaIsRunningClosesTheHandleOnce() = runTest {
        val e = TrackingEngine()
        e.fake.hangFor("meta", 10_000)
        val vm = vm(e)
        advanceTimeBy(100)
        assertEquals(1, e.opened.size)
        vm.release()
        advanceUntilIdle()
        e.assertNoLeaks()
    }

    @Test fun releaseWhilePageInfosIsRunningClosesTheHandleOnce() = runTest {
        val e = TrackingEngine()
        e.fake.hangFor("pageInfos", 10_000)
        val vm = vm(e)
        advanceTimeBy(100)
        vm.release()
        advanceUntilIdle()
        assertEquals(1, e.opened.size)
        e.assertNoLeaks()
    }

    @Test fun releaseWhileOpenIsRunningStillClosesTheHandle() = runTest {
        val e = TrackingEngine()
        e.fake.hangFor("open", 1_000)
        val vm = vm(e)
        advanceTimeBy(100)
        vm.release()
        advanceUntilIdle()
        assertEquals(1, e.opened.size)
        e.assertNoLeaks()
    }

    @Test fun wrongPasswordPathLeavesNoHandleBehind() = runTest {
        val e = TrackingEngine()
        val vm = vm(e, password = "pw")
        advanceUntilIdle()
        vm.submitPassword("nope".toCharArray())
        advanceUntilIdle()
        assertTrue(e.opened.isEmpty())
        vm.submitPassword("pw".toCharArray())
        advanceUntilIdle()
        assertIs<ViewerState.Ready>(vm.state.value)
        assertEquals(1, e.opened.size)
        vm.release()
        advanceUntilIdle()
        e.assertNoLeaks()
    }

    @Test fun corruptedPathClosesTheOpenedHandleOnce() = runTest {
        val e = TrackingEngine().apply { emptyPages = true }
        val vm = vm(e)
        advanceUntilIdle()
        assertIs<ViewerState.Corrupted>(vm.state.value)
        assertEquals(1, e.opened.size)
        e.assertNoLeaks()
        vm.release()
        advanceUntilIdle()
        e.assertNoLeaks()
    }

    @Test fun garbageFilePathNeverOpensAHandle() = runTest {
        val e = TrackingEngine()
        val vm = vm(e, bytes = ByteArray(40) { 3 })
        advanceUntilIdle()
        assertIs<ViewerState.Corrupted>(vm.state.value)
        assertTrue(e.opened.isEmpty())
    }

    @Test fun doubleReleaseClosesOnce() = runTest {
        val e = TrackingEngine()
        val vm = vm(e)
        advanceUntilIdle()
        vm.release()
        vm.release()
        advanceUntilIdle()
        assertEquals(1, e.opened.size)
        e.assertNoLeaks()
    }

    // ---- double start ----

    @Test fun startWhileOpeningIsIgnored() = runTest {
        val e = TrackingEngine()
        e.fake.hangFor("open", 500)
        val vm = vm(e)
        advanceTimeBy(10)
        vm.start()
        vm.start()
        advanceUntilIdle()
        assertEquals(1, e.openCalls)
        assertIs<ViewerState.Ready>(vm.state.value)
    }

    @Test fun doubleTapOnTryAgainOpensOnce() = runTest {
        val e = TrackingEngine()
        e.fake.failNext(EngineError.Io("disk"), "open")
        val vm = vm(e)
        advanceUntilIdle()
        assertIs<ViewerState.Failed>(vm.state.value)
        assertEquals(1, e.openCalls)
        vm.start()
        vm.start()
        advanceUntilIdle()
        assertEquals(2, e.openCalls)
        assertEquals(1, e.opened.size)
        vm.release()
        advanceUntilIdle()
        e.assertNoLeaks()
    }

    @Test fun startWhenReadyDoesNotReopen() = runTest {
        val e = TrackingEngine()
        val vm = vm(e)
        advanceUntilIdle()
        vm.start()
        advanceUntilIdle()
        assertEquals(1, e.openCalls)
    }

    // ---- crash loop ----

    @Test fun tileThatKillsTheEngineIsRetriedOnceThenPoisonedAndTheRestRenders() = runTest {
        val e = TrackingEngine()
        e.killTile = { it.page == 0 && !it.preview && it.x == 0 && it.y == 0 }
        val vm = vm(e)
        advanceUntilIdle()
        vm.onViewportChanged(1000, 1600, 1f)
        advanceUntilIdle()
        advanceTimeBy(600_000)
        assertEquals(2, e.crashes, "one try plus exactly one retry")
        assertEquals(1, vm.poisonedTiles.value.size)
        val poisoned = vm.poisonedTiles.value.single()
        assertEquals(poisoned, vm.failedTiles.value.single())
        assertNotNull(vm.safeModeBanner.value)
        assertIs<ViewerState.Ready>(vm.state.value)
        val others = vm.view.value.plan.tiles.filter { it.key != poisoned }
        assertTrue(others.isNotEmpty() && others.all { vm.tiles.peek(it.key) }, "the rest of the page renders")
        // Restarts after the poisoning change nothing.
        advanceTimeBy(600_000)
        assertEquals(2, e.crashes)
    }

    @Test fun manualRetryRetriesAPoisonedTile() = runTest {
        val e = TrackingEngine()
        var killer = true
        e.killTile = { killer && it.page == 0 && !it.preview && it.x == 0 && it.y == 0 }
        val vm = vm(e)
        advanceUntilIdle()
        vm.onViewportChanged(1000, 1600, 1f)
        advanceUntilIdle()
        val key = vm.poisonedTiles.value.single()
        killer = false
        vm.retryTile(key)
        advanceUntilIdle()
        assertTrue(vm.tiles.peek(key))
        assertTrue(vm.poisonedTiles.value.isEmpty())
    }

    // ---- misc ----

    @Test fun submittingStateShowsWhileThePasswordIsChecked() = runTest {
        val e = TrackingEngine()
        val vm = vm(e, password = "pw")
        advanceUntilIdle()
        vm.submitPassword("nope".toCharArray())
        assertEquals(ViewerState.PasswordRequired(3, lastAttemptWrong = false, submitting = true), vm.state.value)
        advanceUntilIdle()
        assertEquals(ViewerState.PasswordRequired(2, lastAttemptWrong = true, submitting = false), vm.state.value)
    }

    @Test fun noTilesAreCachedAfterRelease() = runTest {
        val e = TrackingEngine()
        val vm = vm(e, pages = 50)
        advanceUntilIdle()
        e.fake.hangFor("renderTile", 5_000)
        vm.onViewportChanged(1000, 1600, 1f)
        advanceTimeBy(100)
        vm.release()
        advanceUntilIdle()
        assertEquals(0, vm.tiles.count)
    }
}
