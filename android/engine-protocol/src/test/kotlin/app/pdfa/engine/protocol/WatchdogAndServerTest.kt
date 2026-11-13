package app.pdfa.engine.protocol

import app.pdfa.engine.EngineError
import app.pdfa.engine.fake.FakeEngine
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import kotlin.test.assertFailsWith

class ServiceWatchdogTest {
    private var now = 0L
    private var kills = 0
    private val dog = ServiceWatchdog({ now }, { kills++ }, graceMs = 2_000, cancelFuseMs = 500, ticker = object : WatchdogTicker {
        override fun start(periodMs: Long, task: Runnable) {}
        override fun stop() {}
    })

    @Test fun overdueKillsExactlyOnce() {
        dog.onRequest(1, 1_000)
        now = 3_000; dog.tick(); assertEquals(0, kills)          // at deadline + grace: not yet
        now = 3_001; dog.tick(); dog.tick(); now = 9_000; dog.tick()
        assertEquals(1, kills)
    }

    @Test fun normalCompletionNeverKills() {
        dog.onRequest(1, 1_000); now = 500; dog.onResponseWritten(1)
        now = 100_000; dog.tick()
        assertEquals(0, kills); assertEquals(0, dog.watched())
    }

    @Test fun graceIsRespected() {
        dog.onRequest(1, 1_000)
        now = 1_999; dog.tick(); now = 2_999; dog.tick()
        assertEquals(0, kills)
    }

    @Test fun responseWrittenAtTheLimitWinsTheRace() {
        dog.onRequest(1, 1_000)
        now = 3_000
        dog.onResponseWritten(1)
        dog.tick()
        assertEquals(0, kills)
    }

    @Test fun noDeadlineIsNotWatched() {
        dog.onRequest(1, 0); now = 1_000_000; dog.tick()
        assertEquals(0, kills); assertEquals(0, dog.watched())
    }

    @Test fun nonCooperativeCancelArmsShortFuse() {
        dog.onRequest(1, 60_000)
        now = 100; dog.onCancel(1, cooperative = false)
        now = 600; dog.tick(); assertEquals(0, kills)
        now = 601; dog.tick(); assertEquals(1, kills)
    }

    @Test fun cooperativeCancelDoesNothing() {
        dog.onRequest(1, 60_000); now = 100; dog.onCancel(1, cooperative = true)
        now = 5_000; dog.tick(); assertEquals(0, kills)
    }

    @Test fun cancelThenWriteDisarmsTheFuse() {
        dog.onRequest(1, 60_000); dog.onCancel(1, false); now = 400; dog.onResponseWritten(1)
        now = 5_000; dog.tick(); assertEquals(0, kills)
    }

    @Test fun cancelNeverExtendsAnEarlierLimit() {
        dog.onRequest(1, 100); now = 50; dog.onCancel(1, false)
        now = 551; dog.tick(); assertEquals(1, kills)
    }

    @Test fun oneOverdueAmongManyKills() {
        dog.onRequest(1, 1_000); dog.onRequest(2, 100_000); now = 500; dog.onResponseWritten(1)
        now = 50_000; dog.tick(); assertEquals(0, kills)
        now = 110_000; dog.tick(); assertEquals(1, kills)
    }

    @Test fun realTickerKillsAHungRequest() {
        val killed = java.util.concurrent.CountDownLatch(1)
        val d = ServiceWatchdog({ System.nanoTime() / 1_000_000 }, { killed.countDown() }, graceMs = 10, tickMs = 10)
        d.start(); d.onRequest(1, 20)
        assertTrue(killed.await(2, java.util.concurrent.TimeUnit.SECONDS)); d.stop()
    }
}

class FramingV2Test {
    @Test fun deadlineRoundTripsAndVersionIs2() {
        assertEquals(2, PROTOCOL_VERSION)
        val r = Request.decode(Request(9, Op.MERGE, byteArrayOf(1), 1234).encode())
        assertEquals(1234, r.deadlineMs)
        assertEquals(2, Request(1, Op.PING, ByteArray(0)).encode()[0].toInt())
    }

    @Test fun negativeDeadlineIsRejected() {
        val f = WireWriter().u8(PROTOCOL_VERSION).i64(1).i32(Op.PING.code).i32(-1).bytes(ByteArray(0)).toByteArray()
        assertFailsWith<WireException> { Request.decode(f) }
    }

    @Test fun writeToMatchesEncodeAndReadFromParses() {
        val req = Request(5, Op.OPEN, ByteArray(70_000) { it.toByte() }, 15_000)
        val out = ByteArrayOutputStream(); req.writeTo(out)
        assertArrayEquals(req.encode(), out.toByteArray())
        assertEquals(req.encodedSize(), out.size().toLong())
        val back = Request.readFrom(ByteArrayInputStream(out.toByteArray()))
        assertArrayEquals(req.payload, back.payload); assertEquals(15_000, back.deadlineMs)
    }

    @Test fun responseReadFromParsesAndRejectsHostile() {
        val resp = Response.ok(3, ByteArray(1000) { 7 })
        val back = Response.readFrom(ByteArrayInputStream(resp.encode()))
        assertArrayEquals(resp.payload, back.payload)
        val hostile = WireWriter().u8(PROTOCOL_VERSION).i64(1).u8(0).i32(0x7FFFFFFF).toByteArray()
        assertFailsWith<WireException> { Response.readFrom(ByteArrayInputStream(hostile)) }
        assertFailsWith<WireException> { Response.readFrom(ByteArrayInputStream(resp.encode().copyOf(30))) }
        assertFailsWith<WireException> { Request.readFrom(ByteArrayInputStream(ByteArray(5))) }
    }
}

class EngineServerTest {
    private fun req(id: Long, op: Op, payload: ByteArray, deadline: Int = 0) = Request(id, op, payload, deadline)

    @Test fun pingReportsBootIdAndPid() = runBlocking {
        val s = EngineServer(FakeEngine(), bootId = 42L, pid = 99)
        val info = EngineWire.parsePingResult(s.handle(req(1, Op.PING, ByteArray(0))).response.payload)
        assertEquals(PingInfo(PROTOCOL_VERSION, 42L, 99), info)
    }

    @Test fun bootIdsDifferByDefault() {
        assertNotEquals(EngineServer(FakeEngine()).bootId, EngineServer(FakeEngine()).bootId)
    }

    private suspend fun open(s: EngineServer): Long =
        EngineWire.parseOpenResult(s.handle(req(1, Op.OPEN, EngineWire.openArgs(FakeEngine.fakePdf(2), null))).response.payload)

    @Test fun handlesCarryTheBootNonce() = runBlocking {
        val s = EngineServer(FakeEngine(), handleNonce = 77)
        val h = open(s)
        assertEquals(77, (h ushr 32).toInt())
        val meta = s.handle(req(2, Op.META, EngineWire.handleArgs(h)))
        assertEquals(ResponseStatus.OK, meta.response.status)
    }

    @Test fun staleHandleFromAnotherBootIsRejectedNotServedByAnotherDocument() = runBlocking {
        val old = EngineServer(FakeEngine(), handleNonce = 5)
        val stale = open(old)
        val fresh = EngineServer(FakeEngine(), handleNonce = 6)
        val live = open(fresh)                                   // same local id 1 as `stale`
        assertEquals(stale and 0xFFFFFFFFL, live and 0xFFFFFFFFL)
        val reply = fresh.handle(req(3, Op.ROTATE, EngineWire.rotateArgs(stale, listOf(0), app.pdfa.engine.Rotation.HALF)))
        assertEquals(ResponseStatus.ERROR, reply.response.status)
        assertTrue(ErrorCodec.decode(reply.response.payload) is EngineError.InvalidArgument)
    }

    @Test fun serverCallsTheWatchdogForEveryRequest() = runBlocking {
        var now = 0L; var kills = 0
        val dog = ServiceWatchdog({ now }, { kills++ }, ticker = object : WatchdogTicker {
            override fun start(periodMs: Long, task: Runnable) {}
            override fun stop() {}
        })
        val s = EngineServer(FakeEngine(), dog)
        s.handle(req(1, Op.PING, ByteArray(0), 1_000))
        assertEquals(1, dog.watched())
        now = 10_000; dog.tick(); assertEquals(1, kills)          // never "written": overdue
        s.responseWritten(1); assertEquals(0, dog.watched())
    }

    @Test fun cancelOfNonCooperativeOpArmsFuse() = runBlocking {
        var now = 0L; var kills = 0
        val dog = ServiceWatchdog({ now }, { kills++ }, ticker = object : WatchdogTicker {
            override fun start(periodMs: Long, task: Runnable) {}
            override fun stop() {}
        })
        val s = EngineServer(FakeEngine(), dog)
        s.handle(req(7, Op.MERGE, EngineWire.mergeArgs(emptyList()), 100_000))
        s.cancel(7); now = 501; dog.tick()
        assertEquals(1, kills)
    }
}
