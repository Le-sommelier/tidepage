package app.pdfa.engine.impl.binder

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Build
import android.os.IBinder
import android.os.ParcelFileDescriptor
import android.os.RemoteException
import android.os.TransactionTooLargeException
import app.pdfa.engine.EngineError
import app.pdfa.engine.EngineEvent
import app.pdfa.engine.impl.DeathEvent
import app.pdfa.engine.impl.EngineTransport
import app.pdfa.engine.impl.PendingInfo
import app.pdfa.engine.impl.ReplyReader
import app.pdfa.engine.impl.ReplyResult
import app.pdfa.engine.impl.TransportResponse
import app.pdfa.engine.protocol.EngineWire
import app.pdfa.engine.protocol.Op
import app.pdfa.engine.protocol.Request
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException

/**
 * Binder implementation of [EngineTransport]. THIN BY DESIGN: it moves bytes and reports process
 * death; policy (deadlines, restart, recovery, boot-id check) lives in RemoteEngine, and the
 * pipe/size/deadline rules live in the JVM-tested `BlobIo.kt`. It cannot be unit-tested on the JVM;
 * see engine-impl/README.md for what is unverified.
 *
 *  - Binds [service] (an `android:process=":engine"` isolated service) lazily, a NEW Connection per
 *    generation. API 29+: `bindIsolatedService` with instance name "engine_<generation>" (Android only accepts [A-Za-z0-9_.]), so every
 *    generation gets a fresh process. API 26-28: plain `bindService`; RemoteEngine's PING boot-id
 *    check detects an attach to the dying process.
 *  - Requests go inline (<= [BlobPipes.INLINE_LIMIT]) or streamed straight into a socket pair
 *    (`Request.writeTo`, no intermediate copy); the local read end is closed right after the
 *    oneway transaction and the writer is aborted at the call's deadline.
 *  - Replies: the callback is per Connection and never blocks the binder thread: big frames and
 *    tile pixels are read on a bounded executor with a deadline. A reply is accepted only from the
 *    Connection the request was sent on.
 *  - Death (disconnect, binding died, null binding, linkToDeath) fails pending calls and `ready`
 *    at once and emits a [DeathEvent]. [kill] unbinds but leaves the death recipient linked and does
 *    NOT trust unbind to end the process.
 */
class BinderEngineTransport(
    private val context: Context,
    private val service: ComponentName,
    private val bindTimeoutMs: Long = 3_000,
    private val blobReadDeadlineMs: Long = 30_000,
) : EngineTransport {

    private class Pending(val conn: Connection, val info: PendingInfo, val result: CompletableDeferred<TransportResponse>)

    private val lock = Any()
    private var conn: Connection? = null
    private val pending = ConcurrentHashMap<Long, Pending>()
    private val callbackExecutor = Executors.newSingleThreadExecutor { r -> Thread(r, "engine-binder-cb").also { it.isDaemon = true } }

    @Volatile
    override var generation: Long = 0L
        private set

    private val deathFlow = MutableSharedFlow<DeathEvent>(extraBufferCapacity = 8)
    private val progressFlow = MutableSharedFlow<EngineEvent.Progress>(extraBufferCapacity = 64)
    override val deaths: SharedFlow<DeathEvent> = deathFlow.asSharedFlow()
    override val progress: SharedFlow<EngineEvent.Progress> = progressFlow.asSharedFlow()

    private inner class Connection(val gen: Long) : ServiceConnection {
        val ready = CompletableDeferred<IEngineService>()
        @Volatile var binder: IBinder? = null
        val deathRecipient = IBinder.DeathRecipient { onDied(this, "binder died") }

        val callback = object : IEngineCallback.Stub() {
            override fun onResponse(frame: ByteArray, frameBlob: ParcelFileDescriptor?, tile: ParcelFileDescriptor?) =
                handleResponse(this@Connection, frame, frameBlob, tile)

            override fun onProgress(frame: ByteArray) {
                try { progressFlow.tryEmit(EngineWire.parseProgress(frame)) } catch (_: Exception) { }
            }
        }

        override fun onServiceConnected(name: ComponentName, service: IBinder) {
            binder = service
            try { service.linkToDeath(deathRecipient, 0) } catch (_: RemoteException) { onDied(this, "already dead"); return }
            ready.complete(IEngineService.Stub.asInterface(service))
        }

        override fun onServiceDisconnected(name: ComponentName) = onDied(this, "service disconnected")
        override fun onBindingDied(name: ComponentName) = onDied(this, "binding died")
        override fun onNullBinding(name: ComponentName) = onDied(this, "null binding")
    }

    private fun lookup(c: Connection): (Long) -> PendingInfo? = { id -> pending[id]?.takeIf { it.conn === c }?.info }

    private fun handleResponse(c: Connection, frame: ByteArray, frameBlob: ParcelFileDescriptor?, tile: ParcelFileDescriptor?) {
        if (frameBlob == null && tile == null) {
            deliver(c, ReplyReader.read(frame, null, null, lookup(c)))
            return
        }
        // Never read pipes on the binder thread: oneway calls on this node are serialised.
        try {
            BlobPipes.reader.readAsync(
                abort = { BlobPipes.abort(frameBlob); BlobPipes.abort(tile) },
                deadlineMs = blobReadDeadlineMs,
                task = {
                    ReplyReader.read(
                        if (frameBlob == null) frame else null,
                        frameBlob?.let(BlobPipes::input), tile?.let(BlobPipes::input), lookup(c),
                    )
                },
            ) { r ->
                BlobPipes.closeQuietly(frameBlob, tile)
                deliver(c, r.getOrElse { ReplyResult.Violation(null, EngineError.Panic("protocol error: reply unreadable")) })
            }
        } catch (e: RejectedExecutionException) {
            BlobPipes.closeQuietly(frameBlob, tile)
            deliver(c, ReplyResult.Violation(null, EngineError.Panic("protocol error: reply reader saturated")))
        }
    }

    private fun deliver(c: Connection, r: ReplyResult) {
        when (r) {
            is ReplyResult.Reply -> pending.remove(r.id)?.takeIf { it.conn === c }?.result?.complete(r.response)
            ReplyResult.Orphan -> Unit
            is ReplyResult.Violation -> {
                if (r.id != null) {
                    pending.remove(r.id)?.takeIf { it.conn === c }?.result?.completeExceptionally(r.error)
                } else {
                    // The request id is unknown: every call awaiting this process is suspect.
                    for ((id, p) in pending) {
                        if (p.conn !== c || !pending.remove(id, p)) continue
                        val e = if (p.info.op == Op.RENDER_TILE) EngineError.Render("protocol error: malformed reply") else r.error
                        p.result.completeExceptionally(e)
                    }
                }
            }
        }
    }

    private fun onDied(c: Connection, reason: String) {
        c.ready.completeExceptionally(EngineError.EngineDied("engine process died: $reason"))
        val current = synchronized(lock) { if (conn === c) { conn = null; true } else false }
        if (!current) return // a killed (or replaced) process finally exited: nothing to report
        failPending(c, "engine process died: $reason")
        try { context.unbindService(c) } catch (_: IllegalArgumentException) { }
        deathFlow.tryEmit(DeathEvent(c.gen, reason))
    }

    private fun failPending(c: Connection, why: String) {
        for ((id, p) in pending) {
            if (p.conn === c && pending.remove(id, p)) p.result.completeExceptionally(EngineError.EngineDied(why))
        }
    }

    /**
     * Returns the live connection, binding a new one only when [expected] is -1 (any). A call tied
     * to a generation never starts a process: it fails if that generation is no longer running.
     */
    private fun current(expected: Long): Connection {
        synchronized(lock) {
            val existing = conn
            if (existing != null) {
                if (expected != -1L && existing.gen != expected) throw EngineError.EngineDied("engine generation changed")
                return existing
            }
            if (expected != -1L) throw EngineError.EngineDied("engine is not running")
            return bind()
        }
    }

    /** Must hold [lock]. The generation is incremented here, atomically with creating the Connection. */
    private fun bind(): Connection {
        val fresh = Connection(++generation)
        conn = fresh
        val intent = Intent().setComponent(service)
        val ok = try {
            if (Build.VERSION.SDK_INT >= 29) {
                context.bindIsolatedService(intent, Context.BIND_AUTO_CREATE, "engine_${fresh.gen}", callbackExecutor, fresh)
            } else {
                context.bindService(intent, fresh, Context.BIND_AUTO_CREATE)
            }
        } catch (e: SecurityException) {
            false
        }
        if (!ok) {
            conn = null
            try { context.unbindService(fresh) } catch (_: IllegalArgumentException) { }
            throw EngineError.EngineDied("cannot bind the engine service")
        }
        return fresh
    }

    private suspend fun connected(c: Connection): IEngineService {
        try {
            return withTimeout(bindTimeoutMs) { c.ready.await() }
        } catch (e: TimeoutCancellationException) {
            kill("bind timed out")
            throw EngineError.EngineDied("engine service did not connect")
        }
    }

    override suspend fun call(request: Request, deadlineMs: Long, expectedGeneration: Long): TransportResponse {
        val c = current(expectedGeneration)
        val svc = connected(c)
        val result = CompletableDeferred<TransportResponse>()
        val expectedTile = if (request.op == Op.RENDER_TILE) {
            val t = EngineWire.parseRenderTileArgs(request.payload).request
            t.width.toLong() * t.height * 4
        } else 0L
        pending[request.requestId] = Pending(c, PendingInfo(request.op, expectedTile), result)
        try {
            // kill() may have run before we registered: then nobody will fail this call.
            synchronized(lock) { if (conn !== c) throw EngineError.EngineDied("engine was killed") }
            withContext(Dispatchers.IO) {
                if (request.encodedSize() <= BlobPipes.INLINE_LIMIT) {
                    svc.call(request.encode(), null, c.callback)
                } else {
                    BlobPipes.feeder.feedAndSend(deadlineMs, { out -> request.writeTo(out) }) { read ->
                        svc.call(ByteArray(0), read, c.callback)
                    }
                }
            }
            return result.await()
        } catch (e: TransactionTooLargeException) {
            throw EngineError.TooLarge("request exceeds what the engine transport can carry")
        } catch (e: RemoteException) {
            throw EngineError.EngineDied("engine connection lost")
        } catch (e: RejectedExecutionException) {
            throw EngineError.Io("transport busy")
        } finally {
            pending.remove(request.requestId)
        }
    }

    override fun cancel(requestId: Long) {
        val p = pending[requestId] ?: return
        val svc = p.conn.ready.takeIf { it.isCompleted && !it.isCancelled }?.let {
            try { it.getCompleted() } catch (_: Exception) { null }
        } ?: return
        try { svc.cancel(requestId) } catch (_: RemoteException) { }
    }

    /**
     * Fails pending calls and unbinds. The death recipient stays linked on purpose: when the old
     * process really exits, [onDied] sees it is no longer current and stays silent. Unbind alone is
     * NOT trusted to end the process; RemoteEngine verifies the next process by its boot id.
     */
    override fun kill(reason: String) {
        val c = synchronized(lock) { conn.also { conn = null } } ?: return
        failPending(c, "engine killed: $reason")
        c.ready.completeExceptionally(EngineError.EngineDied("engine killed"))
        try { context.unbindService(c) } catch (_: IllegalArgumentException) { }
    }

    override suspend fun restart() {
        kill("restart")
        val c = synchronized(lock) { bind() }
        connected(c)
    }
}
