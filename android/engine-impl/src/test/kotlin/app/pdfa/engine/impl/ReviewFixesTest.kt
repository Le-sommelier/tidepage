package app.pdfa.engine.impl

import app.pdfa.engine.*
import app.pdfa.engine.protocol.Op
import app.pdfa.engine.protocol.Request
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.test.assertFailsWith

@OptIn(ExperimentalCoroutinesApi::class)
class ReviewFixesTest {

    // ---- deadline travels to the service ----

    @Test fun requestsCarryTheEnforcedDeadline() = runTest {
        val h = harness()
        val a = h.engine.open(pdf())
        h.engine.meta(a); h.engine.renderTile(a, TileRequest(0, 1f, 0, 0, 8, 8)); h.engine.rotate(a, listOf(0), Rotation.HALF)
        val d = h.transport.deadlines.toMap()
        assertEquals(10_000, d[Op.META]); assertEquals(2_000, d[Op.RENDER_TILE]); assertEquals(5_000, d[Op.ROTATE])
        assertEquals(15_000, d[Op.OPEN])
    }

    // ---- boot id check ----

    @Test fun firstCallLearnsTheBootIdWithAPing() = runTest {
        val h = harness()
        h.engine.open(pdf())
        assertEquals(Op.PING, h.transport.log.first().op)
    }

    @Test fun sameBootIdAfterRestartIsRetriedOnceThenAccepted() = runTest {
        val h = harness()
        val a = h.openKeyed()
        h.transport.reuseBootIdCount = 1
        h.transport.hangNext(Op.META)
        assertFailsWith<EngineError.Timeout> { h.engine.meta(a) }
        assertEquals(2, h.transport.restartCount)               // first restart looked like the old process
        assertEquals(3, h.engine.meta(a).pageCount)
    }

    @Test fun bootIdThatNeverChangesDegradesTheEngine() = runTest {
        val h = harness()
        val a = h.openKeyed()
        h.transport.reuseBootIdCount = 5
        h.transport.hangNext(Op.META)
        assertFailsWith<EngineError.Timeout> { h.engine.meta(a) }
        assertEquals(2, h.transport.restartCount)
        val e = assertFailsWith<EngineError.EngineDied> { h.engine.meta(a) }
        assertTrue(e.detail.contains("degraded"))
    }

    private suspend fun Harness.openKeyed(key: String = "doc", bytes: ByteArray = pdf(), pw: String? = null): DocHandle {
        recovery.bytes[key] = bytes
        return engine.open(bytes, pw, key)
    }

    // ---- expected generation ----

    @Test fun transportRejectsAStaleGenerationBeforeSending() = runTest {
        val h = harness()
        val before = h.transport.log.size
        assertFailsWith<EngineError.EngineDied> { h.transport.call(Request(1, Op.META, ByteArray(8)), 1000, 99L) }
        assertEquals(before, h.transport.log.size)
        assertEquals(1, h.transport.rejectedStale)
    }

    @Test fun callsCarryTheGenerationOfTheirBinding() = runTest {
        val h = harness()
        val a = h.openKeyed()
        h.transport.hangNext(Op.META)
        assertFailsWith<EngineError.Timeout> { h.engine.meta(a) }
        h.engine.meta(a)
        assertEquals(0, h.transport.rejectedStale)               // rebinding moved the document to generation 2
        assertEquals(2L, h.transport.generation)
    }

    // ---- transport error classes ----

    @Test fun transportTooLargeIsNotAProcessFailure() = runTest {
        val h = harness()
        val a = h.engine.open(pdf())
        h.transport.tamperNext(Op.META) { throw EngineError.TooLarge("binder") }
        assertFailsWith<EngineError.TooLarge> { h.engine.meta(a) }
        assertEquals(0, h.transport.restartCount)
    }

    @Test fun transportProtocolErrorRestarts() = runTest {
        val h = harness()
        val a = h.openKeyed()
        h.transport.tamperNext(Op.RENDER_TILE) { throw EngineError.Render("protocol error: bad tile") }
        assertFailsWith<EngineError.Render> { h.engine.renderTile(a, TileRequest(0, 1f, 0, 0, 8, 8)) }
        assertEquals(1, h.transport.restartCount)
    }

    // ---- EditsLost and critical event replay ----

    @Test fun editsLostIsEmittedPerReopenedDocument() = runTest {
        val h = harness()
        val a = h.openKeyed("a"); val b = h.engine.open(pdf())
        h.transport.hangNext(Op.META)
        assertFailsWith<EngineError.Timeout> { h.engine.meta(a) }
        val lost = h.engine.events.take(3).toList().filterIsInstance<EngineEvent.EditsLost>()
        assertEquals(setOf(a, b), lost.map { it.handle }.toSet())
        assertEquals(false, lost.first { it.handle == a }.originalRestored)   // keyed: journal bytes
        assertEquals(true, lost.first { it.handle == b }.originalRestored)    // unkeyed: original bytes
        assertTrue(lost.all { it.atEpochMs == 10_000L })
    }

    @Test fun originalRestoredComesFromTheProviderForKeyedDocs() = runTest {
        val h = harness()
        val provider = object : RecoveryProvider {
            override suspend fun latestBytes(docKey: String) = pdf()
            override fun passwordFor(docKey: String): String? = null
            override fun restoredFromOriginal(docKey: String) = true
        }
        val engine = RemoteEngine(h.transport, EngineConfig(), provider, { currentTime }, { currentTime }, backgroundScope)
        val a = engine.open(pdf(), null, "k")
        h.transport.hangNext(Op.META)
        assertFailsWith<EngineError.Timeout> { engine.meta(a) }
        val lost = engine.events.first { it is EngineEvent.EditsLost } as EngineEvent.EditsLost
        assertTrue(lost.originalRestored)
    }

    @Test fun criticalEventsAreReplayedToLateCollectors() = runTest {
        val h = harness()
        val a = h.openKeyed()
        h.transport.hangNext(Op.META)
        assertFailsWith<EngineError.Timeout> { h.engine.meta(a) }   // nobody was collecting
        val got = h.engine.events.take(2).toList()
        assertTrue(got.any { it is EngineEvent.SafeModeRestarted })
        assertTrue(got.any { it is EngineEvent.EditsLost })
    }

    @Test fun progressFloodDoesNotEvictCriticalEvents() = runTest {
        val h = harness()
        val a = h.openKeyed()
        h.transport.hangNext(Op.META)
        assertFailsWith<EngineError.Timeout> { h.engine.meta(a) }
        repeat(5) { h.engine.split(a, SplitSpec.EveryN(1)) }          // many Progress events, no collector
        val got = mutableListOf<EngineEvent>()
        val job = launch(Dispatchers.Unconfined) { h.engine.events.collect { got += it } }
        runCurrent(); job.cancel()
        assertTrue(got.any { it is EngineEvent.SafeModeRestarted })
        assertTrue(got.any { it is EngineEvent.EditsLost })
    }

    // ---- monotonic time, recovery cap ----

    @Test fun stormAndCooldownUseTheMonotonicClockNotTheWallClock() = runTest {
        val h = harness(EngineConfig())
        val engine = RemoteEngine(h.transport, EngineConfig(), h.recovery, clock = { 0L }, monotonicMs = { currentTime }, scope = backgroundScope)
        h.recovery.bytes["k"] = pdf()
        val d = engine.open(pdf(), null, "k")
        repeat(3) { h.transport.hangNext(Op.STATE); assertFailsWith<EngineError.Timeout> { engine.state(d) } }
        h.transport.hangNext(Op.STATE)
        assertFailsWith<EngineError.Timeout> { engine.state(d) }
        assertFailsWith<EngineError.EngineDied> { engine.state(d) }    // degraded
        advanceTimeBy(30_001)
        assertEquals(3, engine.state(d).pageCount)
    }

    @Test fun totalRecoveryTimeIsCapped() = runTest {
        val h = harness(EngineConfig(maxRecoveryMs = 10, maxRestartsPerWindow = 99))
        val a = h.openKeyed("a"); val b = h.openKeyed("b")
        h.transport.hangNext(Op.META)
        h.transport.delayNext(Op.OPEN, 50)                          // reopening the first document is slow
        assertFailsWith<EngineError.Timeout> { h.engine.meta(b) }
        assertEquals(3, h.engine.meta(a).pageCount)
        val e = assertFailsWith<EngineError.EngineDied> { h.engine.meta(b) }
        assertTrue(e.detail.contains("time limit"))
    }
}
