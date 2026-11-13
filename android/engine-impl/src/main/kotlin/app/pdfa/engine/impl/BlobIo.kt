package app.pdfa.engine.impl

import app.pdfa.engine.EngineError
import app.pdfa.engine.protocol.EngineWire
import app.pdfa.engine.protocol.HeapTileBuffer
import app.pdfa.engine.protocol.Op
import app.pdfa.engine.protocol.Response
import app.pdfa.engine.protocol.ResponseStatus
import app.pdfa.engine.protocol.WireException
import app.pdfa.engine.protocol.readExactly
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.Executor
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.TimeUnit

/*
 * Pure-Kotlin logic around out-of-band byte transfers, written against small interfaces so the
 * leak, deadline and hostile-size rules are unit-tested on the JVM. The Android classes in `binder/`
 * only implement these interfaces with ParcelFileDescriptor sockets.
 */

fun interface Cancelable { fun cancel() }

interface BlobTimer {
    fun schedule(delayMs: Long, task: Runnable): Cancelable
}

/** One daemon thread; cancelled tasks are removed immediately. */
class ScheduledBlobTimer : BlobTimer {
    private val pool = ScheduledThreadPoolExecutor(1) { r -> Thread(r, "engine-blob-timer").also { it.isDaemon = true } }
        .also { it.removeOnCancelPolicy = true }

    override fun schedule(delayMs: Long, task: Runnable): Cancelable {
        val f = pool.schedule(task, delayMs.coerceAtLeast(0), TimeUnit.MILLISECONDS)
        return Cancelable { f.cancel(false) }
    }
}

/** A pipe. [R] is the transferable read end (a ParcelFileDescriptor on Android). */
interface BlobPipe<R> {
    /** The end that is sent to the peer. */
    val reader: R
    fun openWriter(): OutputStream

    /** Close OUR copy of the read end (call right after the transaction that carried it). */
    fun closeLocalReader()
    fun closeWriter()

    /** Unblock a writer stuck on a full pipe and close every local end. Idempotent. */
    fun abort()
}

fun interface BlobPipeFactory<R> { fun create(): BlobPipe<R> }

/**
 * Writes large payloads into pipes on a bounded executor. Guarantees: the writer thread always ends
 * (the pipe is aborted at [deadlineMs] if the peer never reads), the local read end is closed right
 * after the transaction, and nothing leaks when the executor rejects the task or the send throws.
 */
class BlobFeeder<R>(
    private val factory: BlobPipeFactory<R>,
    private val executor: Executor,
    private val timer: BlobTimer,
) {
    fun feed(deadlineMs: Long, body: (OutputStream) -> Unit): BlobPipe<R> {
        val pipe = factory.create()
        val timeout = timer.schedule(deadlineMs) { pipe.abort() }
        try {
            executor.execute {
                try {
                    pipe.openWriter().use(body)
                } catch (_: IOException) {
                    // peer went away or the pipe was aborted
                } catch (_: RuntimeException) {
                } finally {
                    timeout.cancel()
                    pipe.closeWriter()
                }
            }
        } catch (t: Throwable) {
            timeout.cancel()
            pipe.abort()
            throw t
        }
        return pipe
    }

    /** Feeds [body] into a pipe, hands the read end to [send], and always releases the local end. */
    fun <T> feedAndSend(deadlineMs: Long, body: (OutputStream) -> Unit, send: (R) -> T): T {
        val pipe = feed(deadlineMs, body)
        try {
            return send(pipe.reader)
        } catch (t: Throwable) {
            pipe.abort()
            throw t
        } finally {
            pipe.closeLocalReader()
        }
    }
}

/** Runs blocking pipe reads off the caller's (Binder) thread, with a deadline that aborts the read. */
class BlobReader(private val executor: Executor, private val timer: BlobTimer) {
    /** @throws java.util.concurrent.RejectedExecutionException (after calling [abort]) when saturated. */
    fun <T> readAsync(abort: () -> Unit, deadlineMs: Long, task: () -> T, done: (Result<T>) -> Unit) {
        val timeout = timer.schedule(deadlineMs) { abort() }
        try {
            executor.execute {
                val r = try { Result.success(task()) } catch (t: Throwable) { Result.failure(t) }
                timeout.cancel()
                done(r)
            }
        } catch (t: Throwable) {
            timeout.cancel()
            abort()
            throw t
        }
    }
}

/** What the transport knows about a request that is awaiting its reply. */
class PendingInfo(val op: Op, val expectedTileBytes: Long)

sealed interface ReplyResult {
    /** A usable reply for request [id]. */
    class Reply(val id: Long, val response: TransportResponse) : ReplyResult

    /** A reply nobody waits for (late or from a zombie): ignore it. */
    object Orphan : ReplyResult

    /** The reply is malformed or lies about sizes: the process misbehaves. */
    class Violation(val id: Long?, val error: EngineError) : ReplyResult
}

object ReplyReader {
    /**
     * Turns a server reply into a [ReplyResult]. [frame] is the inline frame, or null when
     * [frameStream] carries it. Nothing is allocated from an unvalidated length: the frame payload is
     * allocated after its length check, and the tile only after its announced size equals the size the
     * request implies ([PendingInfo.expectedTileBytes]).
     */
    fun read(frame: ByteArray?, frameStream: InputStream?, tileStream: InputStream?, lookup: (Long) -> PendingInfo?): ReplyResult {
        val response = try {
            if (frameStream != null) Response.readFrom(frameStream) else Response.decode(frame ?: ByteArray(0))
        } catch (e: WireException) {
            return ReplyResult.Violation(null, EngineError.Panic("protocol error: malformed reply"))
        } catch (e: IOException) {
            return ReplyResult.Violation(null, EngineError.Panic("protocol error: unreadable reply"))
        }
        val p = lookup(response.requestId) ?: return ReplyResult.Orphan
        if (tileStream == null) return ReplyResult.Reply(response.requestId, TransportResponse(response, null))
        if (p.op != Op.RENDER_TILE || response.status != ResponseStatus.OK) {
            return ReplyResult.Reply(response.requestId, TransportResponse(response, null))
        }
        return try {
            val info = EngineWire.parseRenderTileResult(response.payload)
            if (info.size.toLong() != p.expectedTileBytes) {
                return ReplyResult.Violation(response.requestId, EngineError.Render("protocol error: tile size differs from the request"))
            }
            val pixels = readExactly(tileStream, info.size)
            ReplyResult.Reply(response.requestId, TransportResponse(response, HeapTileBuffer(info.transferId, java.nio.ByteBuffer.wrap(pixels))))
        } catch (e: WireException) {
            ReplyResult.Violation(response.requestId, EngineError.Render("protocol error: bad tile"))
        } catch (e: IOException) {
            ReplyResult.Violation(response.requestId, EngineError.Render("protocol error: tile unreadable"))
        }
    }
}
