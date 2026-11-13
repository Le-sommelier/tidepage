package app.pdfa.engine.service

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.pdfa.engine.EngineError
import app.pdfa.engine.EngineEvent
import app.pdfa.engine.ImageFrame
import app.pdfa.engine.MergeSource
import app.pdfa.engine.Pt
import app.pdfa.engine.Quad
import app.pdfa.engine.ScanLook
import app.pdfa.engine.TileRequest
import app.pdfa.engine.impl.DeathEvent
import app.pdfa.engine.impl.EngineConfig
import app.pdfa.engine.impl.RecoveryProvider
import app.pdfa.engine.impl.RemoteEngine
import app.pdfa.engine.impl.binder.BinderEngineTransport
import app.pdfa.engine.protocol.EngineWire
import app.pdfa.engine.protocol.Op
import app.pdfa.engine.protocol.PROTOCOL_VERSION
import app.pdfa.engine.protocol.PingInfo
import app.pdfa.engine.protocol.Request
import app.pdfa.engine.protocol.ResponseStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.Executor

/**
 * Acceptance tests against the REAL isolated service (`:engine`, isolatedProcess) and the real
 * native library, driven through `:engine-impl`'s BinderEngineTransport and RemoteEngine.
 *
 * Other processes are inspected as root through the instrumentation's shell (`su 0`), which a
 * userdebug emulator image allows: liveness of a pid, its open descriptors, and `kill -9`.
 * Every line tagged ENGINE-PROOF in logcat is evidence quoted in the work-package report.
 */
@RunWith(AndroidJUnit4::class)
class EngineServiceDeviceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()

    private val context: Context = instrumentation.targetContext
    private val component = ComponentName(context, EngineService::class.java)
    private val hello: ByteArray by lazy { instrumentation.context.assets.open("hello.pdf").use { it.readBytes() } }
    private val scope = CoroutineScope(Dispatchers.Default)
    private val engines = ArrayList<RemoteEngine>()
    private val transports = ArrayList<BinderEngineTransport>()

    private val noRecovery = object : RecoveryProvider {
        override suspend fun latestBytes(docKey: String): ByteArray? = null
        override fun passwordFor(docKey: String): String? = null
    }

    @After fun tearDown() {
        engines.forEach { it.close() }
        transports.forEach { it.kill("test over") }
        scope.cancel()
    }

    private fun transport() = BinderEngineTransport(context, component).also { transports += it }

    private fun engine(config: EngineConfig = EngineConfig(), transport: BinderEngineTransport = transport()) =
        RemoteEngine(transport, config, noRecovery, monotonicMs = SystemClock::elapsedRealtime).also { engines += it }

    private fun proof(line: String) = Log.i("ENGINE-PROOF", line)

    private fun shell(command: String): String {
        val pfd = instrumentation.uiAutomation.executeShellCommand(command)
        return ParcelFileDescriptor.AutoCloseInputStream(pfd).use { it.readBytes().toString(Charsets.UTF_8) }
    }

    /** True while /proc/<pid> exists (asked as root: an app cannot see other UIDs' processes). */
    private fun alive(pid: Int): Boolean = shell("su 0 ls -d /proc/$pid").trim() == "/proc/$pid"

    private fun processName(pid: Int): String = shell("su 0 cat /proc/$pid/cmdline").trim { it <= ' ' }

    private fun fdCount(pid: Int): Int = shell("su 0 ls /proc/$pid/fd").lines().count { it.isNotBlank() }

    private fun ownFdCount(): Int = File("/proc/self/fd").list()?.size ?: -1

    private fun awaitGone(pid: Int, withinMs: Long): Long {
        val start = SystemClock.elapsedRealtime()
        while (SystemClock.elapsedRealtime() - start < withinMs) {
            if (!alive(pid)) return SystemClock.elapsedRealtime() - start
            Thread.sleep(50)
        }
        fail("pid $pid is still alive after $withinMs ms")
        return -1
    }

    private inline fun <reified E : EngineError> expect(block: () -> Unit): E {
        try {
            block()
        } catch (e: Throwable) {
            if (e is E) return e
            throw AssertionError("expected ${E::class.simpleName}, got $e", e)
        }
        throw AssertionError("expected ${E::class.simpleName}, nothing was thrown")
    }

    // ---- a) the real engine works inside the isolated process ----

    @Test fun a_open_meta_render_search() = runBlocking {
        val engine = engine()
        val ping = engine.ping()
        assertEquals(PROTOCOL_VERSION, ping.protocolVersion)
        assertNotEquals(android.os.Process.myPid(), ping.pid)
        val name = processName(ping.pid)
        proof("a) service pid=${ping.pid} bootId=${ping.bootId} cmdline=$name testPid=${android.os.Process.myPid()}")
        assertTrue("engine runs in the :engine process, was '$name'", name.contains(":engine"))
        val uid = shell("su 0 stat -c %u /proc/${ping.pid}").trim().toInt()
        proof("a) service uid=$uid (isolated uids are 90000..99999 per user)")
        assertTrue("isolated uid expected, was $uid", uid % 100_000 in 90_000..99_999)

        val h = engine.open(hello)
        val meta = engine.meta(h)
        val pages = engine.pageInfos(h)
        assertTrue(meta.pageCount >= 1)
        assertEquals(meta.pageCount, pages.size)
        assertTrue(pages[0].widthPt > 0f && pages[0].heightPt > 0f)

        val w = 256
        val ht = 256
        val tile = engine.renderTile(h, TileRequest(page = 0, scale = 1f, x = 0, y = 0, width = w, height = ht))
        assertEquals(w, tile.width)
        assertEquals(ht, tile.height)
        val px = tile.premultipliedRgba.duplicate()
        assertEquals(w * ht * 4, px.remaining())
        val distinct = HashSet<Int>()
        var opaque = 0
        while (px.remaining() >= 4) {
            val v = px.int
            distinct += v
            if (v != 0) opaque++
        }
        proof("a) pages=${meta.pageCount} page0=${pages[0].widthPt}x${pages[0].heightPt} tileBytes=${w * ht * 4} distinctPixels=${distinct.size} nonZero=$opaque")
        assertTrue("tile must not be blank (distinct=${distinct.size})", distinct.size > 1)
        assertTrue("tile must have painted pixels", opaque > 0)

        val hits = engine.searchPage(h, 0, "Hello")
        proof("a) search 'Hello' -> ${hits.size} rect(s) ${hits.firstOrNull()}")
        assertTrue(hits.isNotEmpty())
        assertTrue(hits[0].left < hits[0].right && hits[0].top < hits[0].bottom)
        engine.close(h)
    }

    // ---- b) hostile bytes are an error, not a crash ----

    @Test fun b_garbage_and_truncated_are_InvalidPdf_and_the_engine_survives() = runBlocking {
        val engine = engine()
        val before = engine.ping()
        val garbage = ByteArray(4096) { (it * 31 + 7).toByte() }
        expect<EngineError.InvalidPdf> { engine.open(garbage) }
        expect<EngineError.InvalidPdf> { engine.open(ByteArray(0)) }
        // Truncation at every eighth of the file. The engine may REPAIR a file whose tail is missing
        // (it rebuilds the cross-reference table), so the outcome of each cut is recorded; what is
        // required is a typed answer for every cut, InvalidPdf for the cuts that leave no page, and
        // the same process still alive afterwards.
        val outcomes = ArrayList<String>()
        for (eighth in 1..7) {
            val cut = hello.size * eighth / 8
            val outcome = try {
                val h = engine.open(hello.copyOf(cut))
                val pages = engine.meta(h).pageCount
                engine.close(h)
                "repaired($pages page)"
            } catch (e: EngineError) {
                e.javaClass.simpleName
            }
            outcomes += "$cut:$outcome"
            assertTrue("cut at $cut gave $outcome", outcome == "InvalidPdf" || outcome.startsWith("repaired"))
        }
        val headerOnly = hello.copyOf(64)
        expect<EngineError.InvalidPdf> { engine.open(headerOnly) }
        val after = engine.ping()
        proof("b) garbage, empty, header-only(64 of ${hello.size}) -> InvalidPdf; cuts $outcomes; pid ${before.pid} -> ${after.pid}, bootId ${before.bootId} -> ${after.bootId}")
        assertTrue("at least one truncation must be refused", outcomes.any { it.endsWith("InvalidPdf") })
        assertEquals(before.pid, after.pid)
        assertEquals(before.bootId, after.bootId)
        val h = engine.open(hello)
        assertTrue(engine.meta(h).pageCount >= 1)
        engine.close(h)
    }

    // ---- c) a hung engine call: typed Timeout, old process dead, new process serves ----

    @Test fun c_hang_times_out_kills_the_process_and_recovers() = runBlocking {
        val engine = engine(EngineConfig(deadlineOverrides = mapOf(Op.OPEN to 1_500L)))
        val before = engine.ping()
        assertTrue(alive(before.pid))
        proof("c) BEFORE pid=${before.pid} bootId=${before.bootId}")

        val t0 = SystemClock.elapsedRealtime()
        val error = expect<EngineError.Timeout> { engine.open(hello, HANG) }
        val elapsed = SystemClock.elapsedRealtime() - t0
        val goneAfter = awaitGone(before.pid, 5_000)
        proof("c) open(__hang__) -> ${error.javaClass.simpleName} after $elapsed ms; old pid ${before.pid} gone (+$goneAfter ms)")
        assertTrue("the deadline was 1.5 s, the call took $elapsed ms", elapsed >= 1_400)

        val event = withTimeout(10_000) { engine.events.filterIsInstance<EngineEvent.SafeModeRestarted>().first() }
        proof("c) event SafeModeRestarted(reason='${event.reason}')")

        val after = engine.ping()
        proof("c) AFTER pid=${after.pid} bootId=${after.bootId}")
        assertNotEquals(before.pid, after.pid)
        assertNotEquals(before.bootId, after.bootId)
        assertTrue(alive(after.pid))
        assertFalse(alive(before.pid))
        val h = engine.open(hello)
        assertTrue(engine.meta(h).pageCount >= 1)
        engine.close(h)
    }

    // ---- d) the service-side watchdog alone (the client never kills) ----

    private suspend fun rawPing(t: BinderEngineTransport, id: Long): PingInfo {
        val r = t.call(Request(id, Op.PING, EngineWire.emptyArgs(), 2_000), 2_000)
        assertEquals(ResponseStatus.OK, r.response.status)
        return EngineWire.parsePingResult(r.response.payload)
    }

    @Test fun d_service_watchdog_kills_a_hung_engine_by_itself() = runBlocking {
        val t = transport()
        val deaths = scope.async { t.deaths.first() }
        val before = rawPing(t, 1)
        proof("d) BEFORE pid=${before.pid} bootId=${before.bootId}")

        // Service deadline 500 ms; the client would wait 60 s and never unbinds or kills.
        val serviceDeadlineMs = 500
        val t0 = SystemClock.elapsedRealtime()
        val failure = try {
            withTimeout(60_000) { t.call(Request(2, Op.OPEN, EngineWire.openArgs(hello, HANG), serviceDeadlineMs), 60_000) }
            null
        } catch (e: EngineError.EngineDied) {
            e
        }
        val elapsed = SystemClock.elapsedRealtime() - t0
        assertTrue("the hung call must end with EngineDied", failure != null)
        val goneAfter = awaitGone(before.pid, 2_000)
        val death: DeathEvent = withTimeout(5_000) { deaths.await() }
        proof("d) hung call (service deadline $serviceDeadlineMs ms) ended with EngineDied after $elapsed ms; pid ${before.pid} gone (+$goneAfter ms); death='${death.reason}' gen=${death.generation}")
        // deadline 500 + grace 2000 = 2500 ms at the earliest; + 250 ms tick + ~1 s to die and be noticed.
        assertTrue("killed too early ($elapsed ms): not the watchdog", elapsed >= 2_500)
        assertTrue("watchdog too slow: $elapsed ms", elapsed <= serviceDeadlineMs + 2_000 + 1_250)

        t.restart()
        val after = rawPing(t, 3)
        proof("d) AFTER rebind pid=${after.pid} bootId=${after.bootId}")
        assertNotEquals(before.pid, after.pid)
        assertNotEquals(before.bootId, after.bootId)
    }

    // ---- e) the process is killed from outside in the middle of a call ----

    @Test fun e_external_kill_mid_call_is_EngineDied_and_recovers() = runBlocking {
        val engine = engine(EngineConfig(deadlineOverrides = mapOf(Op.OPEN to 60_000L)))
        val doc = engine.open(hello)
        val before = engine.ping()
        proof("e) BEFORE pid=${before.pid} bootId=${before.bootId}")

        val call = scope.async { runCatching { engine.open(hello, HANG) } }
        delay(700) // the call is now parked inside the engine
        assertTrue(alive(before.pid))
        val out = shell("su 0 kill -9 ${before.pid}")
        val result = withTimeout(20_000) { call.await() }
        proof("e) kill -9 ${before.pid} (output='${out.trim()}') -> ${result.exceptionOrNull()}")
        assertTrue("expected EngineDied, got ${result.exceptionOrNull()}", result.exceptionOrNull() is EngineError.EngineDied)
        assertFalse(alive(before.pid))

        val event = withTimeout(10_000) { engine.events.filterIsInstance<EngineEvent.SafeModeRestarted>().first() }
        val after = engine.ping()
        proof("e) AFTER pid=${after.pid} bootId=${after.bootId} event='${event.reason}'")
        assertNotEquals(before.pid, after.pid)
        assertNotEquals(before.bootId, after.bootId)
        // The document opened before the kill was reopened in the new process under the same handle.
        assertTrue(engine.meta(doc).pageCount >= 1)
        assertTrue(engine.searchPage(doc, 0, "Hello").isNotEmpty())
    }

    // ---- f) no descriptor leak over many documents and tiles ----

    @Test fun f_no_fd_growth_over_200_documents_and_600_tiles() = runBlocking {
        val engine = engine()
        val request = TileRequest(page = 0, scale = 1.5f, x = 0, y = 0, width = 256, height = 256)
        // Warm up: thread pools, the native library and the pipe helpers open their descriptors once.
        repeat(8) {
            val h = engine.open(hello)
            engine.renderTile(h, request)
            engine.close(h)
        }
        val pid = engine.ping().pid
        delay(500)
        val serviceBefore = fdCount(pid)
        val clientBefore = ownFdCount()

        var tiles = 0
        repeat(200) { i ->
            val h = engine.open(hello)
            repeat(3) {
                val tile = engine.renderTile(h, request.copy(x = (i % 4) * 64, preview = it == 0))
                assertEquals(256 * 256 * 4, tile.premultipliedRgba.remaining())
                tiles++
            }
            engine.close(h)
        }
        delay(1_000) // idle pool threads and late pipe closes settle
        Runtime.getRuntime().gc()
        val serviceAfter = fdCount(pid)
        val clientAfter = ownFdCount()
        proof("f) 200 open/close, $tiles tiles, pid=$pid: service fds $serviceBefore -> $serviceAfter, client fds $clientBefore -> $clientAfter")
        assertEquals("the same process served everything", pid, engine.ping().pid)
        assertTrue("service fd count must be measurable", serviceBefore > 0)
        assertTrue("service leaked descriptors: $serviceBefore -> $serviceAfter", serviceAfter <= serviceBefore + FD_SLACK)
        assertTrue("client leaked descriptors: $clientBefore -> $clientAfter", clientAfter <= clientBefore + FD_SLACK)
    }

    // ---- g) frames over the 64 KiB inline limit travel through pipes, both ways ----

    @Test fun g_large_request_and_large_reply_use_pipes() = runBlocking {
        val engine = engine()
        val w = 320
        val h = 240
        val rgba = ByteArray(w * h * 4) { i -> if (i % 4 == 3) 0xFF.toByte() else ((i / 4 % w) * 255 / w).toByte() }
        val quad = Quad(Pt(8f, 8f), Pt(w - 8f, 8f), Pt(w - 8f, h - 8f), Pt(8f, h - 8f))
        val out = engine.rectifyAndClean(ImageFrame(w, h, rgba), quad, ScanLook.ORIGINAL, 0)
        proof("g) request ${rgba.size} bytes -> reply frame ${out.width}x${out.height} = ${out.rgba.size} bytes")
        assertEquals(out.width.toLong() * out.height * 4, out.rgba.size.toLong())
        assertTrue("the reply must exceed the inline limit to prove the pipe path", out.rgba.size > 64 * 1024)
        // A large request the engine refuses still gets its one typed answer.
        expect<EngineError.InvalidPdf> { engine.open(ByteArray(300_000) { 0x41 }) }
        Unit
    }

    // ---- h) stale handles and malformed payloads are answered, not fatal ----

    @Test fun h_malformed_frames_get_one_typed_answer() = runBlocking {
        val t = transport()
        val before = rawPing(t, 1)
        // A handle from "another boot": the nonce does not match.
        val stale = t.call(Request(2, Op.META, EngineWire.handleArgs(0x1234_0000_0001L), 2_000), 2_000)
        assertEquals(ResponseStatus.ERROR, stale.response.status)
        // A payload that does not parse.
        val bad = t.call(Request(3, Op.OPEN, byteArrayOf(1, 2, 3), 2_000), 2_000)
        assertEquals(ResponseStatus.ERROR, bad.response.status)
        val after = rawPing(t, 4)
        proof("h) stale handle and malformed payload -> ERROR replies; pid ${before.pid} == ${after.pid}")
        assertEquals(before.bootId, after.bootId)
    }

    // ---- i) cancelling an operation the engine cannot stop arms the 500 ms fuse ----

    @Test fun i_cancel_of_a_non_cooperative_op_kills_the_process_within_the_fuse() = runBlocking {
        val t = transport()
        val before = rawPing(t, 1)
        // Service deadline 60 s: only the cancel fuse can end this process early.
        val request = Request(2, Op.MERGE, EngineWire.mergeArgs(listOf(MergeSource(HANG, hello))), 60_000)
        val call = scope.async { runCatching { t.call(request, 60_000) } }
        delay(700)
        assertTrue("the hung merge must not kill by itself", alive(before.pid))
        val t0 = SystemClock.elapsedRealtime()
        t.cancel(2)
        val result = withTimeout(10_000) { call.await() }
        val elapsed = SystemClock.elapsedRealtime() - t0
        val goneAfter = awaitGone(before.pid, 2_000)
        proof("i) cancel(MERGE) -> ${result.exceptionOrNull()?.javaClass?.simpleName} after $elapsed ms; pid ${before.pid} gone (+$goneAfter ms)")
        assertTrue(result.exceptionOrNull() is EngineError.EngineDied)
        assertTrue("fuse is 500 ms, took $elapsed ms", elapsed in 400..1_750)
        t.restart()
        val after = rawPing(t, 3)
        proof("i) AFTER rebind pid=${after.pid} bootId=${after.bootId} (was pid=${before.pid} bootId=${before.bootId})")
        assertNotEquals(before.bootId, after.bootId)
    }

    private companion object {
        /** Debug-only: HangingBackend parks `open` forever on this password. */
        const val HANG = "__hang__"

        /** Thread pools with idle timeouts make the count wobble by a few; a leak would be hundreds. */
        const val FD_SLACK = 8
    }
}
