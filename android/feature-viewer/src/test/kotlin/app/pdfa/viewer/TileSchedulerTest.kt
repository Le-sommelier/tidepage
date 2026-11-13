package app.pdfa.viewer

import app.pdfa.engine.EngineError
import app.pdfa.engine.TileBitmap
import app.pdfa.engine.TileRequest
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runTest
import java.nio.ByteBuffer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class TileSchedulerTest {
    private fun planned(page: Int, x: Int = 0): PlannedTile {
        val key = TileKey(page, 0, x, 0)
        return PlannedTile(key, TileRequest(page, 1f, x * 512, 0, 16, 16), TileKind.SHARP, 0f, 0f, 1f, 1f, 0f)
    }

    private fun bitmap() = TileBitmap(1, 1, ByteBuffer.allocate(4))

    private class Rig(val ts: TestScope, renderTime: Long = 10, val behaviour: suspend (TileRequest, Int) -> Unit = { _, _ -> }) {
        val rendered = mutableListOf<Int>()
        val calls = HashMap<Int, Int>()
        val cached = HashSet<TileKey>()
        var running = 0
        var maxRunning = 0
        val scheduler = TileScheduler(
            scope = CoroutineScope(StandardTestDispatcher(ts.testScheduler)),
            isCached = { it in cached },
            render = { req ->
                val n = (calls[req.page] ?: 0) + 1
                calls[req.page] = n
                running++; maxRunning = maxOf(maxRunning, running)
                try {
                    delay(renderTime)
                    behaviour(req, n)
                } finally {
                    running--
                }
                TileBitmap(1, 1, ByteBuffer.allocate(4))
            },
            onReady = { key, _ -> cached += key; rendered += key.page },
        )
    }

    @Test fun duplicateRequestsRenderOnce() = runTest {
        val r = Rig(this)
        r.scheduler.submit(listOf(planned(1), planned(1)))
        r.scheduler.submit(listOf(planned(1)))
        advanceUntilIdle()
        assertEquals(1, r.calls[1])
    }

    @Test fun inFlightTileIsNotStartedTwice() = runTest {
        val r = Rig(this, renderTime = 100)
        r.scheduler.submit(listOf(planned(1)))
        advanceTimeBy(10)
        r.scheduler.submit(listOf(planned(1)))
        advanceUntilIdle()
        assertEquals(1, r.calls[1])
    }

    @Test fun cachedTilesAreNeverRequested() = runTest {
        val r = Rig(this)
        r.cached += planned(1).key
        r.scheduler.submit(listOf(planned(1), planned(2)))
        advanceUntilIdle()
        assertEquals(listOf(2), r.rendered)
    }

    @Test fun staleQueuedRequestsAreDroppedBeforeTheyStart() = runTest {
        val r = Rig(this)
        r.scheduler.submit((1..6).map { planned(it) })
        advanceTimeBy(1) // 1 and 2 are in flight, 3..6 queued
        r.scheduler.submit(listOf(planned(6)))
        advanceUntilIdle()
        assertEquals(setOf(1, 2, 6), r.calls.keys)
        assertEquals(3, r.scheduler.droppedCount)
    }

    @Test fun requestsRunInPlanOrder() = runTest {
        val r = Rig(this)
        r.scheduler.submit(listOf(planned(3), planned(1), planned(2)))
        advanceUntilIdle()
        assertEquals(listOf(3, 1, 2), r.rendered)
    }

    @Test fun reorderingMovesQueuedRequestsUp() = runTest {
        val r = Rig(this)
        r.scheduler.submit((1..5).map { planned(it) })
        advanceTimeBy(1)
        // 1 and 2 left the plan: their renders are cancelled; the rest run in the new order.
        r.scheduler.submit(listOf(planned(5), planned(4), planned(3)))
        advanceUntilIdle()
        assertEquals(listOf(5, 4, 3), r.rendered)
    }

    @Test fun reorderKeepsRunningTilesThatAreStillWanted() = runTest {
        val r = Rig(this)
        r.scheduler.submit((1..5).map { planned(it) })
        advanceTimeBy(1)
        r.scheduler.submit(listOf(planned(5), planned(4), planned(3), planned(2), planned(1)))
        advanceUntilIdle()
        assertEquals(listOf(1, 2, 5, 4, 3), r.rendered)
    }

    @Test fun staleInFlightRenderIsCancelledAndNeverCached() = runTest {
        val r = Rig(this, renderTime = 100)
        r.scheduler.submit(listOf(planned(1), planned(2)))
        advanceTimeBy(10)
        assertEquals(2, r.scheduler.inFlightCount)
        r.scheduler.submit(listOf(planned(9)))
        assertEquals(2, r.scheduler.cancelledCount)
        advanceUntilIdle()
        assertEquals(listOf(9), r.rendered)
        assertTrue(planned(1).key !in r.cached && planned(2).key !in r.cached)
        assertEquals(0, r.running)
    }

    @Test fun flingOverThreeHundredPagesNeverExceedsTheLimitNorRendersStalePages() = runTest {
        val r = Rig(this, renderTime = 100)
        for (page in 0 until 300 step 3) {
            r.scheduler.submit(listOf(planned(page), planned(page + 1), planned(page + 2)))
            advanceTimeBy(20)
            assertTrue(r.scheduler.inFlightCount <= 2)
        }
        val last = (297 until 300).toSet()
        advanceUntilIdle()
        assertTrue(r.maxRunning <= 2)
        assertTrue(r.rendered.all { it in last }, "stale pages were rendered: ${r.rendered}")
        assertEquals(last, r.rendered.toSet())
    }

    @Test fun cancelledRenderOfAReenteredTileDoesNotBreakTheNewJob() = runTest {
        val r = Rig(this, renderTime = 100)
        r.scheduler.submit(listOf(planned(1)))
        advanceTimeBy(10)
        r.scheduler.submit(listOf(planned(2)))
        r.scheduler.submit(listOf(planned(1)))
        advanceUntilIdle()
        assertEquals(listOf(1), r.rendered)
        assertEquals(0, r.scheduler.inFlightCount)
    }

    // ---- engine-killing tiles ----

    @Test fun tileThatKillsTheEngineIsRetriedOnceThenPoisoned() = runTest {
        val r = Rig(this, renderTime = 0) { req, _ -> if (req.page == 1) throw EngineError.EngineDied("boom") }
        r.scheduler.submit(listOf(planned(1), planned(2)))
        advanceUntilIdle()
        assertEquals(2, r.calls[1])
        assertEquals(setOf(planned(1).key), r.scheduler.poisonedKeys.value)
        assertEquals(setOf(planned(1).key), r.scheduler.failedKeys.value)
        assertEquals(listOf(2), r.rendered)
        advanceTimeBy(600_000)
        assertEquals(2, r.calls[1])
    }

    @Test fun engineRestartNeverRevivesAPoisonedTile() = runTest {
        val r = Rig(this, renderTime = 0) { req, _ -> if (req.page == 1) throw EngineError.Timeout("slow") }
        r.scheduler.submit(listOf(planned(1)))
        advanceUntilIdle()
        repeat(5) { r.scheduler.onEngineRestarted(); advanceUntilIdle() }
        assertEquals(2, r.calls[1])
        assertEquals(1, r.scheduler.poisonedKeys.value.size)
    }

    @Test fun engineRestartRevivesATimeoutFailureExactlyOnce() = runTest {
        // Two plain render errors, then one timeout: failed after 3 attempts with one crash on record.
        val r = Rig(this, renderTime = 0) { _, n ->
            when {
                n < 3 -> throw EngineError.Render("x")
                n == 3 -> throw EngineError.Timeout("slow")
            }
        }
        r.scheduler.submit(listOf(planned(1)))
        advanceUntilIdle()
        assertEquals(3, r.calls[1])
        assertEquals(1, r.scheduler.failedKeys.value.size)
        r.scheduler.onEngineRestarted()
        advanceUntilIdle()
        assertEquals(listOf(1), r.rendered)
    }

    @Test fun engineRestartLeavesPlainRenderFailuresFailed() = runTest {
        val r = Rig(this, renderTime = 0) { _, _ -> throw EngineError.Render("x") }
        r.scheduler.submit(listOf(planned(1)))
        advanceUntilIdle()
        r.scheduler.onEngineRestarted()
        advanceUntilIdle()
        assertEquals(3, r.calls[1])
        assertEquals(0, r.scheduler.poisonedKeys.value.size)
    }

    @Test fun poisonedTileStaysMarkedWhenItLeavesThePlanAndManualRetryClearsIt() = runTest {
        var killer = true
        val r = Rig(this, renderTime = 0) { _, _ -> if (killer) throw EngineError.EngineDied("boom") }
        r.scheduler.submit(listOf(planned(1)))
        advanceUntilIdle()
        r.scheduler.submit(listOf(planned(2)))
        assertEquals(setOf(planned(1).key), r.scheduler.poisonedKeys.value)
        r.scheduler.submit(listOf(planned(1)))
        advanceUntilIdle()
        assertEquals(2, r.calls[1]) // not retried automatically
        killer = false
        r.scheduler.retry(planned(1).key)
        advanceUntilIdle()
        assertTrue(1 in r.rendered)
        assertEquals(0, r.scheduler.poisonedKeys.value.size)
    }

    @Test fun concurrencyIsLimitedToTwo() = runTest {
        val r = Rig(this)
        r.scheduler.submit((1..10).map { planned(it) })
        advanceUntilIdle()
        assertEquals(2, r.maxRunning)
        assertEquals(10, r.rendered.size)
    }

    @Test fun failedTileBacksOffExponentiallyThenSucceeds() = runTest {
        val r = Rig(this, renderTime = 0) { _, n -> if (n < 3) throw EngineError.Render("boom") }
        r.scheduler.submit(listOf(planned(1)))
        advanceUntilIdle()
        assertEquals(3, r.calls[1])
        assertEquals(listOf(1), r.rendered)
        assertTrue(currentTime >= 250 + 500, "waited $currentTime ms")
    }

    @Test fun retryDoesNotHappenBeforeTheBackoffEnds() = runTest {
        val r = Rig(this, renderTime = 0) { _, _ -> throw EngineError.Timeout("slow") }
        r.scheduler.submit(listOf(planned(1)))
        advanceTimeBy(200)
        assertEquals(1, r.calls[1])
        advanceTimeBy(100)
        assertEquals(2, r.calls[1])
    }

    @Test fun afterThreeAttemptsTheTileIsMarkedFailedAndStopsSpinning() = runTest {
        val r = Rig(this, renderTime = 0) { _, _ -> throw EngineError.Render("always") }
        r.scheduler.submit(listOf(planned(1)))
        advanceUntilIdle()
        assertEquals(3, r.calls[1])
        assertEquals(setOf(planned(1).key), r.scheduler.failedKeys.value)
        r.scheduler.submit(listOf(planned(1)))
        advanceTimeBy(60_000)
        assertEquals(3, r.calls[1])
    }

    @Test fun nonRetryableErrorFailsAtOnce() = runTest {
        val r = Rig(this, renderTime = 0) { _, _ -> throw EngineError.InvalidArgument("bad tile") }
        r.scheduler.submit(listOf(planned(1)))
        advanceUntilIdle()
        assertEquals(1, r.calls[1])
        assertEquals(1, r.scheduler.failedKeys.value.size)
    }

    @Test fun failingTileDoesNotBlockOthers() = runTest {
        val r = Rig(this, renderTime = 5) { req, _ -> if (req.page == 1) throw EngineError.Render("x") }
        r.scheduler.submit(listOf(planned(1), planned(2), planned(3)))
        advanceUntilIdle()
        assertEquals(setOf(2, 3), r.rendered.toSet())
    }

    @Test fun failureIsForgottenWhenTheTileLeavesThePlan() = runTest {
        val r = Rig(this, renderTime = 0) { _, n -> if (n <= 3) throw EngineError.Render("x") }
        r.scheduler.submit(listOf(planned(1)))
        advanceUntilIdle()
        assertEquals(1, r.scheduler.failedKeys.value.size)
        r.scheduler.submit(listOf(planned(2)))
        assertEquals(0, r.scheduler.failedKeys.value.size)
        r.scheduler.submit(listOf(planned(1)))
        advanceUntilIdle()
        assertTrue(1 in r.rendered)
    }

    @Test fun tileLeavingThePlanDuringBackoffIsNotRetried() = runTest {
        val r = Rig(this, renderTime = 0) { _, _ -> throw EngineError.Render("x") }
        r.scheduler.submit(listOf(planned(1)))
        advanceTimeBy(100)
        r.scheduler.submit(listOf(planned(2)))
        advanceUntilIdle()
        assertEquals(1, r.calls[1])
    }

    @Test fun clearFailuresGivesAFreshStart() = runTest {
        val r = Rig(this, renderTime = 0) { _, n -> if (n <= 3) throw EngineError.Render("x") }
        r.scheduler.submit(listOf(planned(1)))
        advanceUntilIdle()
        r.scheduler.clearFailures()
        advanceUntilIdle()
        assertEquals(listOf(1), r.rendered)
    }

    @Test fun cancelAllStopsRunningAndQueuedWork() = runTest {
        val r = Rig(this, renderTime = 100)
        r.scheduler.submit((1..6).map { planned(it) })
        advanceTimeBy(10)
        r.scheduler.cancelAll()
        advanceUntilIdle()
        assertEquals(0, r.rendered.size)
        assertEquals(0, r.running)
        assertEquals(0, r.scheduler.inFlightCount)
        assertEquals(0, r.scheduler.pendingCount)
    }
}
