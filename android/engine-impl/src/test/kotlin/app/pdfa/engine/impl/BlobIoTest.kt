package app.pdfa.engine.impl

import app.pdfa.engine.EngineError
import app.pdfa.engine.protocol.EngineWire
import app.pdfa.engine.protocol.Op
import app.pdfa.engine.protocol.Response
import app.pdfa.engine.protocol.TileInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executor
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.TimeUnit
import kotlin.test.assertFailsWith

/** Pipe-leak, deadline and hostile-size rules, against fake pipes (no Android). */
class BlobIoTest {

    private class FakeTimer : BlobTimer {
        class Task(val task: Runnable) : Cancelable { var cancelled = false; override fun cancel() { cancelled = true } }
        val tasks = mutableListOf<Task>()
        override fun schedule(delayMs: Long, task: Runnable): Cancelable = Task(task).also { tasks += it }
        fun fireAll() = tasks.filter { !it.cancelled }.forEach { it.task.run() }
    }

    /** A pipe whose writer blocks until [abort] (a peer that never reads). */
    private class FakePipe(val blockWriter: Boolean) : BlobPipe<String> {
        override val reader = "read-end"
        val aborted = CountDownLatch(1)
        var abortCalls = 0
        var writerClosed = false
        var localReaderClosed = false
        override fun openWriter(): OutputStream = object : OutputStream() {
            override fun write(b: Int) {
                if (blockWriter) { aborted.await(); throw IOException("aborted") }
            }
        }
        override fun closeLocalReader() { localReaderClosed = true }
        override fun closeWriter() { writerClosed = true }
        override fun abort() { abortCalls++; aborted.countDown() }
    }

    private val threadExecutor = Executor { r -> Thread(r).also { it.isDaemon = true }.start() }

    @Test fun writerBlockedOnAFullPipeIsReleasedAtTheDeadline() {
        val pipe = FakePipe(blockWriter = true)
        val timer = FakeTimer()
        val done = CountDownLatch(1)
        val feeder = BlobFeeder(BlobPipeFactory { pipe }, Executor { r -> Thread { r.run(); done.countDown() }.start() }, timer)
        feeder.feed(1_000) { it.write(1) }
        assertFalse(done.await(100, TimeUnit.MILLISECONDS))      // stuck: nobody reads
        timer.fireAll()                                           // the deadline passes
        assertTrue(done.await(2, TimeUnit.SECONDS))              // the writer thread ended
        assertTrue(pipe.writerClosed); assertEquals(1, pipe.abortCalls)
    }

    @Test fun normalWriteCancelsTheTimerAndClosesTheWriter() {
        val pipe = FakePipe(blockWriter = false)
        val timer = FakeTimer()
        val feeder = BlobFeeder(BlobPipeFactory { pipe }, Executor { it.run() }, timer)
        feeder.feed(1_000) { it.write(1) }
        assertTrue(timer.tasks.single().cancelled); assertTrue(pipe.writerClosed); assertEquals(0, pipe.abortCalls)
    }

    @Test fun rejectedExecutorAbortsThePipeAndRethrows() {
        val pipe = FakePipe(false); val timer = FakeTimer()
        val feeder = BlobFeeder(BlobPipeFactory { pipe }, Executor { throw RejectedExecutionException() }, timer)
        assertFailsWith<RejectedExecutionException> { feeder.feed(1_000) { } }
        assertEquals(1, pipe.abortCalls); assertTrue(timer.tasks.single().cancelled)
    }

    @Test fun localReadEndIsClosedAfterTheTransaction() {
        val pipe = FakePipe(false)
        val feeder = BlobFeeder(BlobPipeFactory { pipe }, Executor { it.run() }, FakeTimer())
        val r = feeder.feedAndSend(1_000, { it.write(1) }) { read -> "sent:$read" }
        assertEquals("sent:read-end", r); assertTrue(pipe.localReaderClosed); assertEquals(0, pipe.abortCalls)
    }

    @Test fun localReadEndIsClosedAndPipeAbortedWhenTheTransactionFails() {
        val pipe = FakePipe(false)
        val feeder = BlobFeeder(BlobPipeFactory { pipe }, Executor { it.run() }, FakeTimer())
        assertFailsWith<IllegalStateException> { feeder.feedAndSend(1_000, { }) { error("dead object") } }
        assertTrue(pipe.localReaderClosed); assertEquals(1, pipe.abortCalls)
    }

    @Test fun feederThatThrowsInsideTheWriterBodyStillClosesTheWriter() {
        val pipe = FakePipe(false)
        BlobFeeder(BlobPipeFactory { pipe }, Executor { it.run() }, FakeTimer()).feed(10) { throw IllegalStateException("x") }
        assertTrue(pipe.writerClosed)
    }

    // ---- reader ----

    @Test fun readerRunsOffTheCallerThreadAndReportsTheResult() {
        val latch = CountDownLatch(1); var thread: Thread? = null; var value: Result<Int>? = null
        BlobReader(threadExecutor, FakeTimer()).readAsync({ }, 1_000, { Thread.currentThread().also { thread = it }; 7 }) { value = it; latch.countDown() }
        assertTrue(latch.await(2, TimeUnit.SECONDS))
        assertEquals(7, value!!.getOrThrow()); assertTrue(thread !== Thread.currentThread())
    }

    @Test fun readerDeadlineAbortsAStalledRead() {
        val stalled = CountDownLatch(1); val done = CountDownLatch(1); val timer = FakeTimer()
        var result: Result<Int>? = null
        BlobReader(threadExecutor, timer).readAsync({ stalled.countDown() }, 1_000, { stalled.await(); throw IOException("aborted") }) { result = it; done.countDown() }
        assertFalse(done.await(100, TimeUnit.MILLISECONDS))
        timer.fireAll()
        assertTrue(done.await(2, TimeUnit.SECONDS)); assertTrue(result!!.isFailure)
    }

    @Test fun readerSaturationAbortsAndRethrows() {
        var aborted = false
        assertFailsWith<RejectedExecutionException> {
            BlobReader(Executor { throw RejectedExecutionException() }, FakeTimer()).readAsync({ aborted = true }, 10, { 1 }) { }
        }
        assertTrue(aborted)
    }

    // ---- reply parsing ----

    private val tileOp = PendingInfo(Op.RENDER_TILE, expectedTileBytes = 4 * 2 * 2)

    private fun tileFrame(size: Int, id: Long = 5) =
        Response.ok(id, EngineWire.renderTileResult(TileInfo(2, 2, size, 9))).encode()

    private class BombStream : InputStream() {
        var read = false
        override fun read(): Int { read = true; throw AssertionError("must not read") }
    }

    @Test fun validTileReplyIsAssembled() {
        val r = ReplyReader.read(tileFrame(16), null, ByteArrayInputStream(ByteArray(16) { 3 })) { tileOp } as ReplyResult.Reply
        assertEquals(16, r.response.tile!!.size); assertEquals(9L, r.response.tile!!.transferId)
    }

    @Test fun hostileTileSizeIsRejectedBeforeAnyAllocationOrRead() {
        val bomb = BombStream()
        val r = ReplyReader.read(tileFrame(Int.MAX_VALUE), null, bomb) { tileOp }
        assertTrue(r is ReplyResult.Violation && r.error is EngineError.Render)
        assertFalse(bomb.read)
    }

    @Test fun shortTileStreamIsAViolation() {
        val r = ReplyReader.read(tileFrame(16), null, ByteArrayInputStream(ByteArray(4))) { tileOp }
        assertTrue(r is ReplyResult.Violation && r.error is EngineError.Render)
    }

    @Test fun malformedFrameIsAViolationNotSilentlyDropped() {
        val r = ReplyReader.read(byteArrayOf(1, 2, 3), null, null) { tileOp }
        assertTrue(r is ReplyResult.Violation); assertNull((r as ReplyResult.Violation).id)
        val r2 = ReplyReader.read(null, ByteArrayInputStream(ByteArray(3)), null) { tileOp }
        assertTrue(r2 is ReplyResult.Violation)
    }

    @Test fun unknownRequestIdIsAnOrphan() {
        assertTrue(ReplyReader.read(Response.ok(77, ByteArray(0)).encode(), null, null) { null } === ReplyResult.Orphan)
    }

    @Test fun largeFrameStreamIsReadExactly() {
        val big = Response.ok(5, ByteArray(300_000) { 1 }).encode()
        val r = ReplyReader.read(null, ByteArrayInputStream(big), null) { PendingInfo(Op.SAVE_FULL, 0) } as ReplyResult.Reply
        assertEquals(300_000 + 0, r.response.response.payload.size)
    }

    @Test fun tileStreamForANonTileOpIsIgnored() {
        val r = ReplyReader.read(Response.ok(5, ByteArray(0)).encode(), null, BombStream()) { PendingInfo(Op.META, 0) }
        assertTrue(r is ReplyResult.Reply && r.response.tile == null)
    }
}
