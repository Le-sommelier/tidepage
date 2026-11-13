package app.pdfa.engine.impl

import app.pdfa.engine.*
import app.pdfa.engine.fake.FakeEngine
import app.pdfa.engine.protocol.EngineWire
import app.pdfa.engine.protocol.ErrorCodec
import app.pdfa.engine.protocol.Op
import app.pdfa.engine.protocol.Response
import app.pdfa.engine.protocol.ResponseStatus
import app.pdfa.engine.protocol.TileInfo
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.test.assertFailsWith

@OptIn(ExperimentalCoroutinesApi::class)
class SupervisionTest {

    private val tile = TileRequest(0, 1f, 0, 0, 16, 16)

    private suspend fun Harness.openKeyed(key: String = "doc", bytes: ByteArray = pdf(), pw: String? = null): DocHandle {
        recovery.bytes[key] = bytes
        if (pw != null) recovery.passwords[key] = pw
        return engine.open(bytes, pw, key)
    }

    private fun TestScope.collectEvents(h: Harness): MutableList<EngineEvent> {
        val got = mutableListOf<EngineEvent>()
        backgroundScope.launch(Dispatchers.Unconfined) { h.engine.events.collect { got += it } }
        return got
    }

    // ---- deadlines per call class ----

    @Test fun deadlinesPerCallClass() = runTest {
        val frame = ImageFrame(2, 2, ByteArray(16))
        val quad = Quad(Pt(0f, 0f), Pt(1f, 0f), Pt(1f, 1f), Pt(0f, 1f))
        val cases: List<Triple<Op, Long, suspend (RemoteEngine, DocHandle) -> Unit>> = listOf(
            Triple(Op.RENDER_TILE, 2_000) { e, d -> e.renderTile(d, tile) },
            Triple(Op.META, 10_000) { e, d -> e.meta(d) },
            Triple(Op.PAGE_INFOS, 10_000) { e, d -> e.pageInfos(d) },
            Triple(Op.SEARCH_PAGE, 10_000) { e, d -> e.searchPage(d, 0, "a") },
            Triple(Op.SELECT_TEXT, 10_000) { e, d -> e.selectText(d, 0, Pt(0f, 0f), Pt(1f, 1f)) },
            Triple(Op.OUTLINE, 10_000) { e, d -> e.outline(d) },
            Triple(Op.LINKS, 10_000) { e, d -> e.links(d, 0) },
            Triple(Op.LIST_FIELDS, 10_000) { e, d -> e.listFields(d) },
            Triple(Op.STATE, 5_000) { e, d -> e.state(d) },
            Triple(Op.ROTATE, 5_000) { e, d -> e.rotate(d, listOf(0), Rotation.HALF) },
            Triple(Op.DELETE, 5_000) { e, d -> e.delete(d, listOf(0)) },
            Triple(Op.MOVE, 5_000) { e, d -> e.move(d, listOf(0), 1) },
            Triple(Op.DUPLICATE, 5_000) { e, d -> e.duplicate(d, listOf(0)) },
            Triple(Op.INSERT_BLANK, 5_000) { e, d -> e.insertBlank(d, 0, 1f, 1f) },
            Triple(Op.UNDO, 5_000) { e, d -> e.undo(d) },
            Triple(Op.REDO, 5_000) { e, d -> e.redo(d) },
            Triple(Op.ADD_MARKUP, 5_000) { e, d -> e.addMarkup(d, NewMarkup(0, MarkupKind.INK, 0, 1f, 1f)) },
            Triple(Op.SET_FIELD, 5_000) { e, d -> e.setField(d, "x", FieldValue.Text("")) },
            Triple(Op.RESET_FORM, 5_000) { e, d -> e.resetForm(d, null) },
            Triple(Op.CONFIRM_SAVED, 5_000) { e, d -> e.confirmSaved(d) },
            Triple(Op.REMOVE_PROTECTION, 5_000) { e, d -> e.removeProtection(d) },
            Triple(Op.SAVE_INCREMENTAL, 60_000) { e, d -> e.saveIncremental(d) },
            Triple(Op.SAVE_FULL, 60_000) { e, d -> e.saveFull(d) },
            Triple(Op.SAVE_PROTECTED, 60_000) { e, d -> e.saveProtected(d, ProtectionSpec("a", "b")) },
            Triple(Op.EXTRACT, 60_000) { e, d -> e.extract(d, listOf(0)) },
            Triple(Op.SPLIT, 120_000) { e, d -> e.split(d, SplitSpec.EveryN(1)) },
            Triple(Op.MERGE, 120_000) { e, _ -> e.merge(listOf(MergeSource("a", pdf()))) },
            Triple(Op.IMAGES_TO_PDF, 120_000) { e, _ -> e.imagesToPdf(listOf(ImageInput("a", ByteArray(1))), null) },
            Triple(Op.DETECT_DOCUMENT_CORNERS, 10_000) { e, _ -> e.detectDocumentCorners(frame) },
            Triple(Op.RECTIFY_AND_CLEAN, 10_000) { e, _ -> e.rectifyAndClean(frame, quad, ScanLook.COLOUR, 1) },
        )
        for ((op, ms, call) in cases) {
            val h = harness()
            val d = h.engine.open(pdf())
            h.transport.hangNext(op)
            val t0 = currentTime
            assertFailsWith<EngineError.Timeout>("$op") { call(h.engine, d) }
            assertEquals("$op", ms, currentTime - t0)
            assertEquals("$op kills", 1, h.transport.kills.size)
            assertEquals("$op restarts", 1, h.transport.restartCount)
        }
    }

    @Test fun openDeadline() = runTest {
        val h = harness(); h.transport.hangNext(Op.OPEN)
        val t0 = currentTime
        assertFailsWith<EngineError.Timeout> { h.engine.open(pdf()) }
        assertEquals(15_000, currentTime - t0)
    }

    @Test fun deadlineOverrides() = runTest {
        val h = harness(EngineConfig(openMs = 100, maxRestartsPerWindow = 99, deadlineOverrides = mapOf(Op.META to 250)))
        h.transport.hangNext(Op.OPEN)
        var t0 = currentTime
        assertFailsWith<EngineError.Timeout> { h.engine.open(pdf()) }
        assertEquals(100, currentTime - t0)
        val d = h.engine.open(pdf())
        h.transport.hangNext(Op.META)
        t0 = currentTime
        assertFailsWith<EngineError.Timeout> { h.engine.meta(d) }
        assertEquals(250, currentTime - t0)
    }

    @Test fun deadlineIsPassedToTransport() = runTest {
        val h = harness()
        val d = h.engine.open(pdf())
        h.engine.renderTile(d, tile)
        // the Request log has no deadline; assert through behaviour: a 1999 ms delay still succeeds
        h.transport.delayNext(Op.RENDER_TILE, 1_999)
        h.engine.renderTile(d, tile.copy(x = 1))
        h.transport.delayNext(Op.RENDER_TILE, 2_001)
        assertFailsWith<EngineError.Timeout> { h.engine.renderTile(d, tile.copy(x = 2)) }
    }

    // ---- restart and recovery ----

    @Test fun timeoutKillsRestartsAndReopensKeyedDocument() = runTest {
        val h = harness()
        val events = collectEvents(h)
        val d = h.openKeyed("k1", pdf(3))
        h.transport.hangNext(Op.META)
        assertFailsWith<EngineError.Timeout> { h.engine.meta(d) }
        assertEquals(1, h.transport.kills.size); assertEquals(1, h.transport.restartCount)
        assertEquals(listOf("k1"), h.recovery.asked)
        assertEquals(2L, h.transport.generation)
        assertEquals(3, h.engine.meta(d).pageCount)            // same DocHandle still valid
        assertEquals(1, events.filterIsInstance<EngineEvent.SafeModeRestarted>().size)
    }

    @Test fun reopenUsesLatestRecoveryBytes() = runTest {
        val h = harness()
        val d = h.openKeyed("k", pdf(2))
        h.recovery.bytes["k"] = pdf(5)                          // the journal moved on
        h.transport.hangNext(Op.META)
        assertFailsWith<EngineError.Timeout> { h.engine.meta(d) }
        assertEquals(5, h.engine.meta(d).pageCount)
    }

    @Test fun unkeyedDocumentIsReopenedFromOriginalBytes() = runTest {
        val h = harness()
        val d = h.engine.open(pdf(4))
        h.transport.hangNext(Op.META)
        assertFailsWith<EngineError.Timeout> { h.engine.meta(d) }
        assertTrue(h.recovery.asked.isEmpty())
        assertEquals(4, h.engine.meta(d).pageCount)
    }

    @Test fun passwordFromRecoveryProviderIsUsedOnReopen() = runTest {
        val h = harness()
        val d = h.openKeyed("enc", pdf(2, "pw"), "pw")
        h.transport.hangNext(Op.META)
        assertFailsWith<EngineError.Timeout> { h.engine.meta(d) }
        assertEquals(2, h.engine.meta(d).pageCount)
    }

    @Test fun passwordFromOpenIsUsedForUnkeyedReopen() = runTest {
        val h = harness()
        val d = h.engine.open(pdf(2, "pw"), "pw")
        h.transport.hangNext(Op.META)
        assertFailsWith<EngineError.Timeout> { h.engine.meta(d) }
        assertEquals(2, h.engine.meta(d).pageCount)
    }

    @Test fun noRecoveryBytesMakesDocumentDead() = runTest {
        val h = harness()
        val a = h.openKeyed("a"); h.recovery.bytes["a"] = null
        val b = h.openKeyed("b")
        h.transport.hangNext(Op.META)
        assertFailsWith<EngineError.Timeout> { h.engine.meta(b) }
        assertFailsWith<EngineError.EngineDied> { h.engine.meta(a) }
        assertEquals(3, h.engine.meta(b).pageCount)
    }

    @Test fun unkeyedDocumentAboveMemoryLimitIsNotRecoverable() = runTest {
        val h = harness(EngineConfig(maxRestartsPerWindow = 99, maxInMemoryRecoveryBytes = 10))
        val d = h.engine.open(pdf())
        h.transport.hangNext(Op.META)
        assertFailsWith<EngineError.Timeout> { h.engine.meta(d) }
        val e = assertFailsWith<EngineError.EngineDied> { h.engine.meta(d) }
        assertTrue(e.detail.contains("recover"))
    }

    @Test fun reopenedDocumentReportsDirtyUntilConfirmed() = runTest {
        val h = harness()
        val d = h.openKeyed()
        assertFalse(h.engine.state(d).dirty)
        h.transport.hangNext(Op.META)
        assertFailsWith<EngineError.Timeout> { h.engine.meta(d) }
        assertTrue(h.engine.state(d).dirty)
        assertTrue(h.engine.rotate(d, listOf(0), Rotation.HALF).dirty)
        assertFalse(h.engine.confirmSaved(d).dirty)
        assertFalse(h.engine.state(d).dirty)
    }

    @Test fun culpritDocumentIsPoisonedAndOthersRecovered() = runTest {
        val h = harness()
        val events = collectEvents(h)
        val a = h.openKeyed("a"); val b = h.openKeyed("b")
        h.transport.hangNext(Op.META)
        h.transport.hangNext(Op.OPEN)                           // reopening A hangs
        assertFailsWith<EngineError.Timeout> { h.engine.meta(b) }
        assertEquals(2, h.transport.restartCount)
        assertFailsWith<EngineError.EngineDied> { h.engine.meta(a) }
        assertEquals(3, h.engine.meta(b).pageCount)
        assertEquals(1, events.filterIsInstance<EngineEvent.SafeModeRestarted>().size)
    }

    @Test fun reopenFailureWithEngineErrorOnlyKillsThatDocument() = runTest {
        val h = harness()
        val a = h.openKeyed("a"); val b = h.openKeyed("b")
        h.recovery.bytes["a"] = byteArrayOf(1, 2, 3)            // journal corrupted
        h.transport.hangNext(Op.META)
        assertFailsWith<EngineError.Timeout> { h.engine.meta(b) }
        assertEquals(1, h.transport.restartCount)
        assertFailsWith<EngineError.EngineDied> { h.engine.meta(a) }
        assertEquals(3, h.engine.meta(b).pageCount)
    }

    @Test fun safeModeEventCarriesReasonAndClock() = runTest {
        val h = harness()
        val events = collectEvents(h)
        val d = h.openKeyed()
        advanceTimeBy(777)
        h.transport.hangNext(Op.META)
        assertFailsWith<EngineError.Timeout> { h.engine.meta(d) }
        val ev = events.filterIsInstance<EngineEvent.SafeModeRestarted>().single()
        assertEquals(777 + 10_000, ev.atEpochMs)
        assertTrue(ev.reason.contains("deadline"))
    }

    // ---- process death ----

    @Test fun deathMidCallFailsWithEngineDiedAndRecoversOnce() = runTest {
        val h = harness()
        val d = h.openKeyed()
        h.transport.dieNext(Op.META)
        assertFailsWith<EngineError.EngineDied> { h.engine.meta(d) }
        runCurrent()
        assertEquals(1, h.transport.restartCount)               // call path and death event: one restart
        assertEquals(3, h.engine.meta(d).pageCount)
    }

    @Test fun idleDeathIsRecoveredByTheCollector() = runTest {
        val h = harness()
        val events = collectEvents(h)
        val d = h.openKeyed()
        h.transport.dieNow("oom")
        runCurrent()
        assertEquals(1, h.transport.restartCount)
        assertEquals(3, h.engine.meta(d).pageCount)
        assertTrue(events.filterIsInstance<EngineEvent.SafeModeRestarted>().single().reason.contains("oom"))
    }

    @Test fun deathEventOfAnOlderGenerationIsIgnored() = runTest {
        val h = harness()
        val d = h.openKeyed()
        h.transport.hangNext(Op.META)
        assertFailsWith<EngineError.Timeout> { h.engine.meta(d) }
        h.transport.emitDeathOf(1)
        runCurrent()
        assertEquals(1, h.transport.restartCount)
    }

    @Test fun transportExceptionIsTreatedAsEngineDied() = runTest {
        val h = harness()
        val d = h.openKeyed()
        h.transport.tamperNext(Op.META) { throw java.io.IOException("pipe broke") }
        assertFailsWith<EngineError.EngineDied> { h.engine.meta(d) }
        assertEquals(1, h.transport.restartCount)
    }

    @Test fun killDuringOtherCallFailsItWithEngineDied() = runTest {
        val h = harness()
        val a = h.openKeyed("a"); val b = h.openKeyed("b")
        h.transport.delayNext(Op.META, 7_000)                   // A: within its own 10 s
        h.transport.hangNext(Op.ROTATE)                         // B: times out at 5 s and restarts
        val ra = async { runCatching { h.engine.meta(a) } }
        val rb = async { runCatching { h.engine.rotate(b, listOf(0), Rotation.HALF) } }
        assertTrue(rb.await().exceptionOrNull() is EngineError.Timeout)
        assertTrue(ra.await().exceptionOrNull() is EngineError.EngineDied)
        assertEquals(1, h.transport.restartCount)
        assertEquals(3, h.engine.meta(a).pageCount)
    }

    @Test fun staleHandleResultAfterRestartIsNeverReturned() = runTest {
        val h = harness()
        val a = h.openKeyed("a"); val b = h.openKeyed("b")
        h.transport.immuneDelayNext(Op.ROTATE, 3_000)           // A's request is already in flight
        val ra = async { runCatching { h.engine.rotate(a, listOf(0), Rotation.HALF) } }
        runCurrent()
        h.transport.hangNext(Op.MERGE)
        val j = launch { h.engine.merge(listOf(MergeSource("x", pdf()))) }
        runCurrent()
        j.cancel(); runCurrent()                                // cancelling merge kills and restarts
        assertEquals(1, h.transport.restartCount)
        assertTrue(ra.await().exceptionOrNull() is EngineError.EngineDied)
        assertEquals(3, h.engine.meta(b).pageCount)
    }

    // ---- restart storm ----

    @Test fun restartStormDegradesThenRecoversAfterCooldown() = runTest {
        val h = harness(EngineConfig())                         // 3 restarts per minute
        val events = collectEvents(h)
        val d = h.openKeyed()
        repeat(3) {
            h.transport.hangNext(Op.STATE)
            assertFailsWith<EngineError.Timeout> { h.engine.state(d) }
        }
        assertEquals(3, h.transport.restartCount)
        h.transport.hangNext(Op.STATE)
        assertFailsWith<EngineError.Timeout> { h.engine.state(d) }   // 4th within the minute
        assertEquals(3, h.transport.restartCount)               // no restart: degraded
        val seen = h.transport.log.size
        val e = assertFailsWith<EngineError.EngineDied> { h.engine.state(d) }
        assertTrue(e.detail.contains("degraded"))
        assertFailsWith<EngineError.EngineDied> { h.engine.open(pdf()) }
        assertEquals(seen, h.transport.log.size)                // failed fast, nothing sent
        assertTrue(events.filterIsInstance<EngineEvent.SafeModeRestarted>().any { it.reason.startsWith("degraded") })
        advanceTimeBy(30_001)
        assertEquals(3, h.engine.state(d).pageCount)            // cool-down over: restart + reopen
        assertEquals(4, h.transport.restartCount)
        assertEquals(3, h.engine.meta(d).pageCount)
    }

    @Test fun restartsOutsideTheWindowDoNotDegrade() = runTest {
        val h = harness(EngineConfig(maxRestartsPerWindow = 2, stormWindowMs = 20_000))
        val d = h.openKeyed()
        repeat(5) {
            h.transport.hangNext(Op.STATE)
            assertFailsWith<EngineError.Timeout> { h.engine.state(d) }
            advanceTimeBy(30_000)
        }
        assertEquals(5, h.transport.restartCount)
        assertEquals(3, h.engine.state(d).pageCount)
    }

    @Test fun failedRestartDegrades() = runTest {
        val h = harness()
        val d = h.openKeyed()
        h.transport.failRestart = true
        h.transport.hangNext(Op.META)
        assertFailsWith<EngineError.Timeout> { h.engine.meta(d) }
        assertFailsWith<EngineError.EngineDied> { h.engine.meta(d) }
        h.transport.failRestart = false
        advanceTimeBy(30_001)
        assertEquals(3, h.engine.meta(d).pageCount)
    }

    @Test fun hungRestartIsBoundedByRecoveryStepDeadline() = runTest {
        val h = harness(EngineConfig(recoveryStepMs = 1_000))
        val d = h.openKeyed()
        h.transport.restartDelayMs = 1_000_000
        h.transport.hangNext(Op.META)
        val t0 = currentTime
        assertFailsWith<EngineError.Timeout> { h.engine.meta(d) }
        assertEquals(10_000 + 1_000, currentTime - t0)
        assertFailsWith<EngineError.EngineDied> { h.engine.meta(d) }
    }

    @Test fun hungRecoveryProviderIsBounded() = runTest {
        val h = harness(EngineConfig(recoveryStepMs = 500, maxRestartsPerWindow = 99))
        val slow = object : RecoveryProvider {
            override suspend fun latestBytes(docKey: String): ByteArray? { kotlinx.coroutines.delay(1_000_000); return null }
            override fun passwordFor(docKey: String): String? = null
        }
        val engine = RemoteEngine(h.transport, EngineConfig(recoveryStepMs = 500, maxRestartsPerWindow = 99), slow, { currentTime }, { currentTime }, backgroundScope)
        val d = engine.open(pdf(), null, "k")
        h.transport.hangNext(Op.META)
        assertFailsWith<EngineError.Timeout> { engine.meta(d) }
        assertFailsWith<EngineError.EngineDied> { engine.meta(d) }
    }

    // ---- concurrency ----

    @Test fun slowTileOnOneHandleDoesNotBlockAnotherHandle() = runTest {
        val h = harness()
        val a = h.engine.open(pdf()); val b = h.engine.open(pdf())
        h.transport.delayNext(Op.RENDER_TILE, 1_500)
        val slow = async { h.engine.renderTile(a, tile) }
        runCurrent()
        val t0 = currentTime
        h.engine.meta(b)
        assertEquals(0, currentTime - t0)
        slow.await()
        assertEquals(1_500, currentTime)
    }

    @Test fun callsOnOneHandleAreSerialised() = runTest {
        val h = harness()
        val a = h.engine.open(pdf())
        h.transport.delayNext(Op.META, 1_000)
        val one = async { h.engine.meta(a) }
        val two = async { h.engine.pageInfos(a) }
        awaitAll(one, two)
        assertEquals(1, h.transport.maxInFlight)
        val metaEnd = h.transport.endTimes.first { it.first == Op.META }.second
        val infosStart = h.transport.startTimes.first { it.first == Op.PAGE_INFOS }.second
        assertTrue(infosStart >= metaEnd)
    }

    @Test fun differentHandlesOverlap() = runTest {
        val h = harness()
        val a = h.engine.open(pdf()); val b = h.engine.open(pdf())
        h.transport.delayNext(Op.META, 1_000); h.transport.delayNext(Op.META, 1_000)
        awaitAll(async { h.engine.meta(a) }, async { h.engine.meta(b) })
        assertEquals(2, h.transport.maxInFlight)
        assertEquals(1_000, currentTime)
    }

    @Test fun statelessCallsMayOverlap() = runTest {
        val h = harness()
        h.transport.delayNext(Op.MERGE, 1_000); h.transport.delayNext(Op.MERGE, 1_000)
        val src = listOf(MergeSource("a", pdf()))
        awaitAll(async { h.engine.merge(src) }, async { h.engine.merge(src) })
        assertEquals(2, h.transport.maxInFlight)
        assertEquals(1_000, currentTime)
    }

    // ---- tile coalescing ----

    @Test fun identicalTileRequestsShareOneTransportCall() = runTest {
        val h = harness()
        val a = h.engine.open(pdf())
        h.transport.delayNext(Op.RENDER_TILE, 500)
        val r1 = async { h.engine.renderTile(a, tile) }
        val r2 = async { h.engine.renderTile(a, tile) }
        val r3 = async { h.engine.renderTile(a, tile) }
        val all = awaitAll(r1, r2, r3)
        assertEquals(1, h.transport.log.count { it.op == Op.RENDER_TILE })
        val bytes = all.map { t -> ByteArray(t.premultipliedRgba.remaining()).also { t.premultipliedRgba.duplicate().get(it) } }
        assertTrue(bytes[0].contentEquals(bytes[1]) && bytes[1].contentEquals(bytes[2]))
        assertEquals(0, all[0].premultipliedRgba.position())
        assertEquals(16 * 16 * 4, all[0].premultipliedRgba.remaining())
        all[0].premultipliedRgba.get()                           // consuming one does not affect another
        assertEquals(0, all[1].premultipliedRgba.position())
    }

    @Test fun differentTileRectsAreNotCoalesced() = runTest {
        val h = harness()
        val a = h.engine.open(pdf())
        h.transport.delayNext(Op.RENDER_TILE, 500); h.transport.delayNext(Op.RENDER_TILE, 500)
        awaitAll(async { h.engine.renderTile(a, tile) }, async { h.engine.renderTile(a, tile.copy(x = 4)) })
        assertEquals(2, h.transport.log.count { it.op == Op.RENDER_TILE })
    }

    @Test fun sequentialIdenticalTilesAreNotCached() = runTest {
        val h = harness()
        val a = h.engine.open(pdf())
        h.engine.renderTile(a, tile); h.engine.renderTile(a, tile)
        assertEquals(2, h.transport.log.count { it.op == Op.RENDER_TILE })
    }

    @Test fun oneCancelledWaiterDoesNotCancelTheSharedTile() = runTest {
        val h = harness()
        val a = h.engine.open(pdf())
        h.transport.delayNext(Op.RENDER_TILE, 500)
        val r1 = async { h.engine.renderTile(a, tile) }
        val r2 = async { h.engine.renderTile(a, tile) }
        runCurrent()
        r1.cancel(); runCurrent()
        assertEquals(16, r2.await().width)
        assertTrue(h.transport.kills.isEmpty())
        assertTrue(h.transport.cancelled.isEmpty())
    }

    @Test fun allWaitersCancelledDropsTheTileWithoutKilling() = runTest {
        val h = harness()
        val a = h.engine.open(pdf())
        h.transport.hangNext(Op.RENDER_TILE)
        val r1 = async { h.engine.renderTile(a, tile) }
        val r2 = async { h.engine.renderTile(a, tile) }
        runCurrent()
        r1.cancel(); r2.cancel(); runCurrent()
        assertTrue(h.transport.kills.isEmpty())
        assertEquals(0, h.transport.restartCount)
        assertEquals(1, h.transport.cancelled.size)             // best-effort cancel reached the transport
        assertEquals(3, h.engine.meta(a).pageCount)
    }

    @Test fun tileTimeoutReachesEveryWaiter() = runTest {
        val h = harness()
        val a = h.engine.open(pdf())
        h.transport.hangNext(Op.RENDER_TILE)
        val r1 = async { runCatching { h.engine.renderTile(a, tile) } }
        val r2 = async { runCatching { h.engine.renderTile(a, tile) } }
        assertTrue(r1.await().exceptionOrNull() is EngineError.Timeout)
        assertTrue(r2.await().exceptionOrNull() is EngineError.Timeout)
        assertEquals(1, h.transport.restartCount)
    }

    // ---- cancellation ----

    @Test fun cancellingATileNeverKillsTheEngine() = runTest {
        val h = harness()
        val a = h.engine.open(pdf())
        h.transport.hangNext(Op.RENDER_TILE)
        val j = launch { h.engine.renderTile(a, tile) }
        runCurrent(); j.cancel(); runCurrent()
        assertTrue(h.transport.kills.isEmpty())
    }

    @Test fun cancellingMergeKillsAndRestartsAndRecovers() = runTest {
        val h = harness()
        val a = h.openKeyed()
        h.transport.hangNext(Op.MERGE)
        val j = launch { h.engine.merge(listOf(MergeSource("a", pdf()))) }
        runCurrent(); j.cancel(); runCurrent()
        assertEquals(1, h.transport.kills.size); assertEquals(1, h.transport.restartCount)
        assertEquals(1, h.transport.cancelled.size)
        assertEquals(3, h.engine.meta(a).pageCount)
    }

    @Test fun callsAfterCancelKillWaitForRecovery() = runTest {
        val h = harness()
        val a = h.openKeyed()
        h.transport.hangNext(Op.SPLIT)
        h.transport.restartDelayMs = 200
        val j = launch { h.engine.split(a, SplitSpec.EveryN(1)) }
        runCurrent(); j.cancel(); runCurrent()
        val t0 = currentTime
        assertEquals(3, h.engine.meta(a).pageCount)             // had to wait for the restart
        assertTrue(currentTime - t0 >= 100)
    }

    @Test fun cancellingImagesToPdfKills() = runTest {
        val h = harness()
        h.transport.hangNext(Op.IMAGES_TO_PDF)
        val j = launch { h.engine.imagesToPdf(listOf(ImageInput("a", FakeEngine.fakeImage(2, 2))), null) }
        runCurrent(); j.cancel(); runCurrent()
        assertEquals(1, h.transport.kills.size)
    }

    @Test fun killOnCancelIsConfigurable() = runTest {
        val h = harness(EngineConfig(killOnCancel = emptySet(), maxRestartsPerWindow = 99))
        h.transport.hangNext(Op.MERGE)
        val j = launch { h.engine.merge(listOf(MergeSource("a", pdf()))) }
        runCurrent(); j.cancel(); runCurrent()
        assertTrue(h.transport.kills.isEmpty())
    }

    @Test fun cancellingSaveFullKillsOnlyForHugeDocuments() = runTest {
        val h = harness(EngineConfig(killSaveFullOnCancelAboveBytes = 1, maxRestartsPerWindow = 99))
        val a = h.engine.open(pdf())
        h.transport.hangNext(Op.SAVE_FULL)
        val j = launch { h.engine.saveFull(a) }
        runCurrent(); j.cancel(); runCurrent()
        assertEquals(1, h.transport.kills.size)
        val h2 = harness()
        val b = h2.engine.open(pdf())
        h2.transport.hangNext(Op.SAVE_FULL)
        val j2 = launch { h2.engine.saveFull(b) }
        runCurrent(); j2.cancel(); runCurrent()
        assertTrue(h2.transport.kills.isEmpty())
    }

    @Test fun cancellingAnEditDoesNotKill() = runTest {
        val h = harness()
        val a = h.engine.open(pdf())
        h.transport.hangNext(Op.ROTATE)
        val j = launch { h.engine.rotate(a, listOf(0), Rotation.HALF) }
        runCurrent(); j.cancel(); runCurrent()
        assertTrue(h.transport.kills.isEmpty())
        assertEquals(1, h.transport.cancelled.size)
    }

    @Test fun callerOwnTimeoutIsNotMistakenForOurDeadline() = runTest {
        val h = harness()
        val a = h.engine.open(pdf())
        h.transport.hangNext(Op.META)
        assertFailsWith<kotlinx.coroutines.TimeoutCancellationException> {
            kotlinx.coroutines.withTimeout(1_000) { h.engine.meta(a) }
        }
        assertTrue(h.transport.kills.isEmpty())
    }

    // ---- protocol violations ----

    @Test fun garbagePayloadRestartsAndFails() = runTest {
        val h = harness()
        val a = h.openKeyed()
        h.transport.garbageNext(Op.META)
        val e = assertFailsWith<EngineError.Panic> { h.engine.meta(a) }
        assertTrue(e.detail.contains("protocol"))
        assertEquals(1, h.transport.restartCount)
        assertEquals(3, h.engine.meta(a).pageCount)
    }

    @Test fun tileWithWrongPixelCountIsRenderErrorAndRestart() = runTest {
        val h = harness()
        val a = h.openKeyed()
        h.transport.tamperNext(Op.RENDER_TILE) { r ->
            val info = EngineWire.parseRenderTileResult(r.response.payload)
            TransportResponse(Response.ok(r.response.requestId, EngineWire.renderTileResult(info.copy(size = info.size - 4))), r.tile)
        }
        assertFailsWith<EngineError.Render> { h.engine.renderTile(a, tile) }
        assertEquals(1, h.transport.restartCount)
    }

    @Test fun tileBufferShorterThanAnnouncedIsRenderError() = runTest {
        val h = harness()
        val a = h.openKeyed()
        h.transport.tamperNext(Op.RENDER_TILE) { r ->
            val info = EngineWire.parseRenderTileResult(r.response.payload)
            TransportResponse(r.response, app.pdfa.engine.protocol.HeapTileBuffer(info.transferId, java.nio.ByteBuffer.allocate(8)))
        }
        assertFailsWith<EngineError.Render> { h.engine.renderTile(a, tile) }
        assertEquals(1, h.transport.restartCount)
    }

    @Test fun tileGeometryMismatchIsRenderError() = runTest {
        val h = harness()
        val a = h.openKeyed()
        h.transport.tamperNext(Op.RENDER_TILE) { r ->
            val info = EngineWire.parseRenderTileResult(r.response.payload)
            TransportResponse(Response.ok(r.response.requestId, EngineWire.renderTileResult(info.copy(width = 8, height = 32))), r.tile)
        }
        assertFailsWith<EngineError.Render> { h.engine.renderTile(a, tile) }
    }

    @Test fun missingTileBufferIsRenderError() = runTest {
        val h = harness()
        val a = h.openKeyed()
        h.transport.tamperNext(Op.RENDER_TILE) { r -> TransportResponse(r.response, null) }
        assertFailsWith<EngineError.Render> { h.engine.renderTile(a, tile) }
    }

    @Test fun transferIdMismatchIsRenderError() = runTest {
        val h = harness()
        val a = h.openKeyed()
        h.transport.tamperNext(Op.RENDER_TILE) { r ->
            val info = EngineWire.parseRenderTileResult(r.response.payload)
            TransportResponse(Response.ok(r.response.requestId, EngineWire.renderTileResult(info.copy(transferId = info.transferId + 1))), r.tile)
        }
        assertFailsWith<EngineError.Render> { h.engine.renderTile(a, tile) }
    }

    @Test fun wrongRequestIdIsProtocolError() = runTest {
        val h = harness()
        val a = h.openKeyed()
        h.transport.tamperNext(Op.META) { r -> TransportResponse(Response(r.response.requestId + 99, r.response.status, r.response.payload)) }
        assertFailsWith<EngineError.Panic> { h.engine.meta(a) }
        assertEquals(1, h.transport.restartCount)
    }

    @Test fun clientOnlyErrorFromServerIsProtocolError() = runTest {
        val h = harness()
        val a = h.openKeyed()
        h.transport.tamperNext(Op.META) { r ->
            TransportResponse(Response(r.response.requestId, ResponseStatus.ERROR, ErrorCodec.encode(EngineError.EngineDied("lie"))))
        }
        assertFailsWith<EngineError.Panic> { h.engine.meta(a) }
    }

    @Test fun undecodableErrorPayloadIsProtocolError() = runTest {
        val h = harness()
        val a = h.openKeyed()
        h.transport.tamperNext(Op.META) { r -> TransportResponse(Response(r.response.requestId, ResponseStatus.ERROR, byteArrayOf(99))) }
        assertFailsWith<EngineError.Panic> { h.engine.meta(a) }
    }

    @Test fun serverErrorsDoNotRestartTheEngine() = runTest {
        val h = harness()
        val a = h.engine.open(pdf())
        assertFailsWith<EngineError.InvalidArgument> { h.engine.move(a, listOf(0), 99) }
        assertEquals(0, h.transport.restartCount)
    }

    // ---- shutdown ----

    @Test fun closeWithCallsInFlightDoesNotHang() = runTest {
        val h = harness()
        val a = h.engine.open(pdf())
        h.transport.hangNext(Op.META)
        val call = async { runCatching { h.engine.meta(a) } }
        runCurrent()
        h.engine.close()
        assertTrue(call.await().exceptionOrNull() is EngineError.EngineDied)
        assertEquals(0, currentTime)
        assertEquals(listOf("engine closed"), h.transport.kills)
        h.engine.close()                                         // idempotent
        assertEquals(1, h.transport.kills.size)
    }

    @Test fun callsAfterCloseFailFast() = runTest {
        val h = harness()
        val a = h.engine.open(pdf())
        h.engine.close()
        assertFailsWith<EngineError.EngineDied> { h.engine.meta(a) }
        assertFailsWith<EngineError.EngineDied> { h.engine.open(pdf()) }
        assertFailsWith<EngineError.EngineDied> { h.engine.merge(listOf(MergeSource("a", pdf()))) }
    }

    @Test fun closeDuringRecoveryDoesNotRestartAgain() = runTest {
        val h = harness()
        val a = h.openKeyed()
        h.transport.restartDelayMs = 500
        h.transport.hangNext(Op.META)
        val call = async { runCatching { h.engine.meta(a) } }
        advanceTimeBy(10_100)
        h.engine.close()
        call.await()
        runCurrent()
        assertEquals(1, h.transport.restartCount)
    }

    @Test fun closeHandleWithHungServerGivesUpAfterEditDeadline() = runTest {
        val h = harness()
        val a = h.engine.open(pdf())
        h.transport.hangNext(Op.CLOSE)
        val t0 = currentTime
        h.engine.close(a)
        assertTrue(currentTime - t0 <= 5_000)
        assertFailsWith<EngineError.InvalidArgument> { h.engine.meta(a) }
    }

    @Test fun closedDocumentIsNotReopenedByRecovery() = runTest {
        val h = harness()
        val a = h.openKeyed("a"); val b = h.openKeyed("b")
        h.engine.close(a)
        h.transport.hangNext(Op.META)
        assertFailsWith<EngineError.Timeout> { h.engine.meta(b) }
        assertEquals(listOf("b"), h.recovery.asked)
    }

    // ---- guards ----

    @Test fun unknownHandleIsInvalidArgument() = runTest {
        val h = harness()
        assertFailsWith<EngineError.InvalidArgument> { h.engine.meta(DocHandle(12345)) }
        assertTrue(h.transport.log.isEmpty())
    }

    @Test fun requestIdsAreUniqueAndIncreasing() = runTest {
        val h = harness()
        val a = h.engine.open(pdf())
        repeat(5) { h.engine.meta(a) }
        val ids = h.transport.log.map { it.requestId }
        assertEquals(ids.sorted(), ids)
        assertEquals(ids.size, ids.toSet().size)
    }

    @Test fun tooLargeInputsAreRefusedBeforeSending() = runTest {
        val h = harness(EngineConfig(maxInputBytes = 100, maxRequestBytes = 150, maxRenderPixels = 100))
        assertFailsWith<EngineError.TooLarge> { h.engine.open(ByteArray(101)) }
        assertFailsWith<EngineError.TooLarge> { h.engine.merge(listOf(MergeSource("a", ByteArray(101)))) }
        assertFailsWith<EngineError.TooLarge> { h.engine.merge(listOf(MergeSource("a", ByteArray(90)), MergeSource("b", ByteArray(90)))) }
        assertFailsWith<EngineError.TooLarge> { h.engine.imagesToPdf(listOf(ImageInput("a", ByteArray(101))), null) }
        assertFailsWith<EngineError.TooLarge> { h.engine.detectDocumentCorners(ImageFrame(10, 11, ByteArray(101))) }
        assertFailsWith<EngineError.TooLarge> { h.engine.rectifyAndClean(ImageFrame(10, 11, ByteArray(101)), Quad(Pt(0f, 0f), Pt(0f, 0f), Pt(0f, 0f), Pt(0f, 0f)), ScanLook.COLOUR, 1) }
        assertTrue(h.transport.log.isEmpty())
    }

    @Test fun configurableRenderPixelLimit() = runTest {
        val h = harness(EngineConfig(maxRenderPixels = 100))
        val a = h.engine.open(pdf())
        assertFailsWith<EngineError.TooLarge> { h.engine.renderTile(a, TileRequest(0, 1f, 0, 0, 11, 10)) }
        assertEquals(10, h.engine.renderTile(a, TileRequest(0, 1f, 0, 0, 10, 10)).width)
    }

    @Test fun renderOverPixelLimitIsTooLarge() = runTest {
        val h = harness()
        val a = h.engine.open(pdf())
        assertFailsWith<EngineError.TooLarge> { h.engine.renderTile(a, TileRequest(0, 1f, 0, 0, 9000, 9000)) }
        assertFailsWith<EngineError.TooLarge> { h.engine.renderTile(a, TileRequest(0, 1f, 0, 0, Int.MAX_VALUE, Int.MAX_VALUE)) }
        assertFailsWith<EngineError.InvalidArgument> { h.engine.renderTile(a, TileRequest(0, 1f, 0, 0, 0, 5)) }
    }

    // ---- secrets ----

    @Test fun passwordsNeverAppearInExceptionMessages() = runTest {
        val secret = "s3cret-pw-9981"
        val h = harness()
        val seen = mutableListOf<Throwable>()
        suspend fun attempt(f: suspend () -> Unit) { try { f() } catch (e: Throwable) { seen += e } }
        h.transport.hangNext(Op.OPEN)
        attempt { h.engine.open(pdf(2, secret), secret, "k") }                       // timeout on open
        attempt { h.engine.open(pdf(2, secret), "wrong-$secret") }                   // wrong password
        h.recovery.bytes["k"] = pdf(2, secret); h.recovery.passwords["k"] = secret
        val d = h.engine.open(pdf(2, secret), secret, "k")
        h.recovery.bytes["k"] = byteArrayOf(1)                                       // reopen will fail
        h.transport.hangNext(Op.META)
        attempt { h.engine.meta(d) }
        attempt { h.engine.meta(d) }                                                 // dead document
        attempt { h.engine.saveProtected(d, ProtectionSpec(secret, secret)) }
        h.transport.garbageNext(null)
        attempt { h.engine.saveProtected(d, ProtectionSpec(secret, secret)) }
        assertTrue(seen.size >= 4)
        for (e in seen) {
            var c: Throwable? = e
            while (c != null) {
                assertFalse("leak in $c", c.toString().contains(secret))
                c = c.cause
            }
        }
    }

    @Test fun engineErrorsKeepServerDetailOnly() = runTest {
        val h = harness()
        val e = assertFailsWith<EngineError.InvalidPdf> { h.engine.open(byteArrayOf(0, 1)) }
        assertNotEquals("", e.detail)
    }

    // ---- misc ----

    @Test fun pingReportsProtocolVersion() = runTest {
        val h = harness()
        assertEquals(app.pdfa.engine.protocol.PROTOCOL_VERSION, h.engine.ping().protocolVersion)
    }

    @Test fun openDuringRecoveryWaitsForIt() = runTest {
        val h = harness()
        val a = h.openKeyed()
        h.transport.restartDelayMs = 300
        h.transport.hangNext(Op.META)
        val failing = async { runCatching { h.engine.meta(a) } }
        advanceTimeBy(10_050)                                    // recovery is mid-restart now
        val opened = async { h.engine.open(pdf(2)) }
        runCurrent()
        assertFalse(opened.isCompleted)
        val d2 = opened.await()
        assertEquals(2, h.engine.meta(d2).pageCount)
        failing.await()
    }
}
