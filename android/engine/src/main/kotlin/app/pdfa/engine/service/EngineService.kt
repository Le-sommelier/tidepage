package app.pdfa.engine.service

import android.app.Service
import android.content.Intent
import android.os.IBinder
import android.os.ParcelFileDescriptor
import android.os.Process
import android.os.SystemClock
import android.util.Log
import app.pdfa.engine.EngineError
import app.pdfa.engine.impl.BlobPipe
import app.pdfa.engine.impl.binder.BlobPipes
import app.pdfa.engine.impl.binder.IEngineCallback
import app.pdfa.engine.impl.binder.IEngineService
import app.pdfa.engine.protocol.EngineServer
import app.pdfa.engine.protocol.EngineWire
import app.pdfa.engine.protocol.ErrorCodec
import app.pdfa.engine.protocol.Op
import app.pdfa.engine.protocol.PROTOCOL_VERSION
import app.pdfa.engine.protocol.Request
import app.pdfa.engine.protocol.Response
import app.pdfa.engine.protocol.ServerReply
import app.pdfa.engine.protocol.ServiceWatchdog
import app.pdfa.engine.protocol.WireWriter
import kotlinx.coroutines.runBlocking
import java.io.OutputStream
import java.nio.ByteBuffer
import java.security.SecureRandom
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * The engine process: `android:process=":engine"`, `isolatedProcess="true"`, not exported, no
 * permissions. It hosts the native PDF engine behind the wire protocol of `:engine-protocol`.
 *
 * Rules it keeps (engine-protocol/README.md, "What the server must do"):
 *  - a Binder thread never runs engine code and never reads or writes a pipe: [call] only decodes an
 *    inline frame (at most 64 KiB) and queues the request, so `cancel` and PING are never blocked;
 *  - every request id is answered exactly once ([Job.answered]);
 *  - large frames and tile pixels travel through socket pairs with a deadline, and every descriptor
 *    is closed on every path;
 *  - the [ServiceWatchdog] is armed on receipt and disarmed only once the reply was written. A hung
 *    engine call can only be ended by ending the process: [killSelf].
 */
class EngineService : Service() {

    private class Job(val request: Request, val callback: IEngineCallback) {
        val answered = AtomicBoolean(false)
        @Volatile var cancelled = false
    }

    private val pid = Process.myPid()
    private val bootId = SecureRandom().nextLong()
    private val jobs = ConcurrentHashMap<Long, Job>()
    private val dying = AtomicBoolean(false)

    private val watchdog = ServiceWatchdog(clock = SystemClock::elapsedRealtime, killSelf = { killSelf("watchdog") })
    private val engine = UniffiEngine(BackendFactory.create())
    private val server = EngineServer(engine, watchdog, bootId = bootId, pid = pid)

    /** Engine calls. A hung call keeps its thread until the watchdog ends the process. */
    private val workers = pool("engine-worker", WORKERS, QUEUE, ENGINE_STACK_BYTES)

    /** PING and SHUTDOWN never wait behind engine work. */
    private val control = pool("engine-control", 1, 16, 0)

    private val binder = object : IEngineService.Stub() {
        override fun call(frame: ByteArray?, frameBlob: ParcelFileDescriptor?, callback: IEngineCallback?) =
            onCall(frame, frameBlob, callback)

        override fun cancel(requestId: Long) {
            jobs[requestId]?.cancelled = true
            server.cancel(requestId) // arms the watchdog fuse for a non-cooperative op
        }
    }

    override fun onCreate() {
        super.onCreate()
        watchdog.start()
        Log.i(TAG, "engine process up pid=$pid bootId=$bootId protocol=$PROTOCOL_VERSION")
        // Load the native library off the main thread, before the first document arrives.
        try {
            workers.execute {
                try {
                    app.pdfa.engine.service.ffi.UniffiBackend.warmUp()
                    Log.i(TAG, "native engine loaded pid=$pid")
                } catch (t: Throwable) {
                    Log.e(TAG, "native engine failed to load: ${t.javaClass.simpleName}")
                }
            }
        } catch (_: RejectedExecutionException) {
        }
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onUnbind(intent: Intent?): Boolean = false

    /** The last client unbound: this process holds documents in memory and must not linger. */
    override fun onDestroy() {
        super.onDestroy()
        killSelf("service destroyed")
    }

    /** Never returns in production: SIGKILL to ourselves, then a hard halt if we are still here. */
    private fun killSelf(reason: String) {
        if (dying.compareAndSet(false, true)) Log.w(TAG, "engine process pid=$pid bootId=$bootId killing itself: $reason")
        Process.killProcess(pid)
        try {
            Thread.sleep(KILL_FALLBACK_MS)
        } catch (_: InterruptedException) {
        }
        Runtime.getRuntime().halt(137)
    }

    // ---- receive (Binder thread: decode and queue only) ----

    private fun onCall(frame: ByteArray?, frameBlob: ParcelFileDescriptor?, callback: IEngineCallback?) {
        if (callback == null) {
            BlobPipes.closeQuietly(frameBlob)
            return
        }
        if (frameBlob == null) {
            val request = try {
                Request.decode(frame ?: ByteArray(0))
            } catch (e: Exception) {
                rejectMalformed(frame, callback)
                return
            }
            accept(request, callback)
            return
        }
        // A large request: the frame is read on the blob reader pool, with a deadline.
        try {
            BlobPipes.reader.readAsync(
                abort = { BlobPipes.abort(frameBlob) },
                deadlineMs = BLOB_READ_DEADLINE_MS,
                task = { BlobPipes.input(frameBlob).use { Request.readFrom(it) } },
            ) { result ->
                BlobPipes.closeQuietly(frameBlob)
                // An unreadable stream has no request id to answer: the client's deadline handles it.
                result.getOrNull()?.let { accept(it, callback) }
            }
        } catch (e: RejectedExecutionException) {
            BlobPipes.closeQuietly(frameBlob)
        }
    }

    /** Answers a frame that does not decode, when it is at least long enough to carry its id. */
    private fun rejectMalformed(frame: ByteArray?, callback: IEngineCallback) {
        if (frame == null || frame.size < 9) return
        var id = 0L
        for (i in 8 downTo 1) id = (id shl 8) or (frame[i].toLong() and 0xFF)
        val error = Response.error(id, ErrorCodec.encode(EngineError.InvalidArgument("malformed request")))
        try {
            callback.onResponse(error.encode(), null, null)
        } catch (_: Exception) {
        }
    }

    private fun accept(request: Request, callback: IEngineCallback) {
        val id = request.requestId
        val job = Job(request, callback)
        if (jobs.putIfAbsent(id, job) != null) return // a repeated id is answered once, by the first
        watchdog.onRequest(id, request.deadlineMs)
        val executor = if (request.op == Op.PING || request.op == Op.SHUTDOWN) control else workers
        try {
            executor.execute { run(job) }
        } catch (e: RejectedExecutionException) {
            finish(job, error(id, EngineError.Io("engine busy")))
        }
    }

    // ---- work (worker thread) ----

    private fun run(job: Job) {
        val id = job.request.requestId
        val reply = if (job.cancelled) {
            error(id, EngineError.Cancelled)
        } else {
            try {
                runBlocking { server.handle(job.request) }
            } catch (t: Throwable) {
                error(id, EngineError.Panic("engine failure: ${t.javaClass.simpleName}"))
            }
        }
        finish(job, reply)
        if (job.request.op == Op.SHUTDOWN) killSelf("shutdown requested")
    }

    private fun error(id: Long, e: EngineError) = ServerReply(Response.error(id, ErrorCodec.encode(e)))

    /** The one place a reply leaves from. Disarms the watchdog only after the bytes were handed over. */
    private fun finish(job: Job, reply: ServerReply) {
        if (!job.answered.compareAndSet(false, true)) return
        val id = job.request.requestId
        try {
            send(job, reply)
        } catch (t: Throwable) {
            Log.w(TAG, "reply $id not delivered: ${t.javaClass.simpleName}")
        } finally {
            jobs.remove(id)
            server.responseWritten(id)
        }
    }

    // ---- send ----

    private fun send(job: Job, reply: ServerReply) {
        val response = reply.response
        val tile = reply.tilePixels
        val large = Response.HEADER_BYTES.toLong() + response.payload.size > BlobPipes.INLINE_LIMIT
        if (!large && tile == null) {
            job.callback.onResponse(response.encode(), null, null)
            return
        }
        val deadlineMs = if (job.request.deadlineMs > 0) job.request.deadlineMs.toLong() else BLOB_WRITE_DEADLINE_MS
        val pipes = ArrayList<BlobPipe<ParcelFileDescriptor>>(2)
        val written = CountDownLatch((if (large) 1 else 0) + (if (tile != null) 1 else 0))
        var sent = false
        try {
            val framePipe = if (!large) null else feed(pipes, deadlineMs, written) { out ->
                // Streamed: header, then the payload array itself (no second full-size copy).
                out.write(
                    WireWriter(Response.HEADER_BYTES).u8(PROTOCOL_VERSION).i64(response.requestId)
                        .u8(response.status.code).i32(response.payload.size).toByteArray(),
                )
                out.write(response.payload)
            }
            val tilePipe = if (tile == null) null else feed(pipes, deadlineMs, written) { out -> writeBuffer(out, tile) }
            job.callback.onResponse(if (large) EMPTY else response.encode(), framePipe?.reader, tilePipe?.reader)
            sent = true
        } catch (e: RejectedExecutionException) {
            for (p in pipes) p.abort()
            // Still exactly one answer: a small error instead of the reply we cannot stream.
            job.callback.onResponse(error(response.requestId, EngineError.Io("engine busy")).response.encode(), null, null)
        } catch (t: Throwable) {
            for (p in pipes) p.abort()
            throw t
        } finally {
            // The client holds its own duplicate now (or the transaction failed): drop ours.
            for (p in pipes) p.closeLocalReader()
        }
        // The writers end by themselves: done, peer closed, or aborted at the deadline.
        if (sent) written.await(deadlineMs + 2_000, TimeUnit.MILLISECONDS)
    }

    private fun feed(
        pipes: MutableList<BlobPipe<ParcelFileDescriptor>>,
        deadlineMs: Long,
        written: CountDownLatch,
        body: (OutputStream) -> Unit,
    ): BlobPipe<ParcelFileDescriptor> {
        val pipe = BlobPipes.feeder.feed(deadlineMs) { out ->
            try {
                body(out)
            } finally {
                written.countDown()
            }
        }
        pipes += pipe
        return pipe
    }

    private fun writeBuffer(out: OutputStream, pixels: ByteBuffer) {
        val src = pixels.duplicate()
        val chunk = ByteArray(minOf(src.remaining(), 64 * 1024).coerceAtLeast(1))
        while (src.hasRemaining()) {
            val n = minOf(chunk.size, src.remaining())
            src.get(chunk, 0, n)
            out.write(chunk, 0, n)
        }
    }

    private companion object {
        const val TAG = "PdfaEngine"
        const val WORKERS = 4
        const val QUEUE = 64
        const val KILL_FALLBACK_MS = 1_000L
        const val BLOB_READ_DEADLINE_MS = 30_000L
        const val BLOB_WRITE_DEADLINE_MS = 30_000L

        /** Hostile documents recurse deeply in the native parser; the default 1 MiB is too little. */
        const val ENGINE_STACK_BYTES = 8L * 1024 * 1024
        val EMPTY = ByteArray(0)

        fun pool(name: String, threads: Int, queue: Int, stackBytes: Long): ThreadPoolExecutor {
            val n = AtomicInteger(1)
            return ThreadPoolExecutor(
                threads, threads, 30, TimeUnit.SECONDS, LinkedBlockingQueue(queue),
                { r -> Thread(null, r, "$name-${n.getAndIncrement()}", stackBytes).also { it.isDaemon = true } },
            ).also { it.allowCoreThreadTimeOut(true) }
        }
    }
}
