package app.pdfa.engine.impl

import app.pdfa.engine.DocHandle
import app.pdfa.engine.DocMeta
import app.pdfa.engine.EditState
import app.pdfa.engine.EngineApi
import app.pdfa.engine.EngineError
import app.pdfa.engine.EngineEvent
import app.pdfa.engine.FieldInfo
import app.pdfa.engine.FieldValue
import app.pdfa.engine.ImageFrame
import app.pdfa.engine.ImageInput
import app.pdfa.engine.MergeSource
import app.pdfa.engine.NewMarkup
import app.pdfa.engine.OutlinePage
import app.pdfa.engine.PageInfo
import app.pdfa.engine.PageLink
import app.pdfa.engine.ProtectionSpec
import app.pdfa.engine.Pt
import app.pdfa.engine.Quad
import app.pdfa.engine.Rect
import app.pdfa.engine.Rotation
import app.pdfa.engine.ScanLook
import app.pdfa.engine.SplitPart
import app.pdfa.engine.SplitSpec
import app.pdfa.engine.TextSelection
import app.pdfa.engine.TileBitmap
import app.pdfa.engine.TileRequest
import app.pdfa.engine.protocol.EngineWire
import app.pdfa.engine.protocol.ErrorCodec
import app.pdfa.engine.protocol.Op
import app.pdfa.engine.protocol.PingInfo
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.merge
import app.pdfa.engine.protocol.PROTOCOL_VERSION
import app.pdfa.engine.protocol.Request
import app.pdfa.engine.protocol.ResponseStatus
import app.pdfa.engine.protocol.WireException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * [EngineApi] client for an engine running in another process. See README.md for the supervision
 * policy. Summary: deadlines enforced here; on deadline, process death or protocol violation the
 * process is killed and restarted and every open document is reopened from [RecoveryProvider], so
 * [DocHandle]s stay valid; more than [EngineConfig.maxRestartsPerWindow] restarts per window put the
 * engine in a degraded state that fails fast until a cool-down passes.
 *
 * Uses no android.* API, so it runs on the plain JVM.
 *
 * @param clock wall-clock milliseconds, used for event timestamps only.
 *
 * Recovery runs non-cancellable: a cancelled caller of a kill-on-cancel op is released at once (the
 * recovery runs in the engine scope), but a caller whose own deadline fired waits for it. Total
 * recovery time is capped by [EngineConfig.maxRecoveryMs].
 * @param scope hosts the death/progress collectors, shared tile fetches and cancel-recovery. Pass a
 *   test scope in tests. [close] cancels everything started in it.
 */
class RemoteEngine(
    private val transport: EngineTransport,
    private val config: EngineConfig = EngineConfig(),
    private val recovery: RecoveryProvider,
    private val clock: () -> Long = System::currentTimeMillis,
    /** Monotonic milliseconds (SystemClock.elapsedRealtime on Android) for the storm window and cool-down. */
    private val monotonicMs: () -> Long = { System.nanoTime() / 1_000_000 },
    scope: CoroutineScope = CoroutineScope(Dispatchers.Default),
) : EngineApi {

    // ---- state ----

    private class Binding(val epoch: Long, val remote: Long, val generation: Long)

    private class Doc(
        val id: Long,
        val docKey: String?,
        val password: String?,
        val original: ByteArray?,
        @Volatile var size: Long,
    ) {
        val mutex = Mutex()
        @Volatile var binding: Binding? = null
        @Volatile var dead: String? = null
        @Volatile var forceDirty = false
    }

    private class ProcessFailure(val error: EngineError, val reason: String) : Exception(reason)

    private class Flight(val key: Pair<Long, TileRequest>) {
        lateinit var deferred: Deferred<TileData>
        var waiters = 0
    }

    private class TileData(val width: Int, val height: Int, val pixels: ByteBuffer)

    private val lock = Any()
    private val docs = HashMap<Long, Doc>()
    private val flights = HashMap<Pair<Long, TileRequest>, Flight>()
    @Volatile private var epoch = 0L
    @Volatile private var degradedUntil = 0L
    @Volatile private var needsRestart = false
    private val restartTimes = ArrayDeque<Long>()
    private val restartMutex = Mutex()
    private val closed = AtomicBoolean(false)
    private val nextDocId = AtomicLong(1)
    private val nextRequestId = AtomicLong(1)

    private val job = SupervisorJob(scope.coroutineContext[Job])
    private val supervisor = CoroutineScope(scope.coroutineContext + job)

    /** Learned by the first PING and after every restart. */
    @Volatile private var knownGeneration = -1L
    @Volatile private var lastBootId: Long? = null
    private val bootMutex = Mutex()

    /**
     * Critical events (SafeModeRestarted, EditsLost) are replayed to late collectors (last 32) and
     * live in their own flow, so a Progress flood cannot push them out. Progress may be dropped.
     */
    private val criticalBus = MutableSharedFlow<EngineEvent>(
        replay = CRITICAL_REPLAY,
        extraBufferCapacity = 64,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    private val progressBus = MutableSharedFlow<EngineEvent>(
        extraBufferCapacity = 64,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )

    override val events: Flow<EngineEvent> = merge(criticalBus, progressBus)

    init {
        supervisor.launch(start = CoroutineStart.UNDISPATCHED) {
            transport.deaths.collect { ev ->
                recover("engine process died: ${ev.reason}", null, ev.generation)
            }
        }
        supervisor.launch(start = CoroutineStart.UNDISPATCHED) {
            transport.progress.collect { progressBus.tryEmit(it) }
        }
    }

    /** Stops supervision and kills the engine. Never blocks; in-flight calls fail with EngineDied. */
    fun close() {
        if (!closed.compareAndSet(false, true)) return
        job.cancel()
        transport.kill("engine closed")
    }

    // ---- lifecycle ----

    override suspend fun open(bytes: ByteArray, password: String?): DocHandle = openWithKey(bytes, password, null)

    /**
     * Opens a document. With a [docKey], recovery asks [RecoveryProvider] for the latest bytes and no
     * copy is kept here. Without one, the original [bytes] reference is kept (do not mutate the array)
     * if it is at most [EngineConfig.maxInMemoryRecoveryBytes]; larger documents are not recoverable.
     */
    suspend fun openWithKey(bytes: ByteArray, password: String?, docKey: String?): DocHandle {
        checkInput(bytes.size.toLong())
        awaitUsable()
        val epoch0 = epoch
        val gen0 = knownGeneration
        val payload = wireBuild { EngineWire.openArgs(bytes, password) }
        val remote = execute(Op.OPEN, payload, epoch0, gen0, false) { EngineWire.parseOpenResult(it.response.payload) }
        val keep = if (docKey == null && bytes.size <= config.maxInMemoryRecoveryBytes) bytes else null
        val doc = Doc(nextDocId.getAndIncrement(), docKey, password, keep, bytes.size.toLong())
        synchronized(lock) {
            if (epoch != epoch0) throw EngineError.EngineDied("engine restarted while opening")
            doc.binding = Binding(epoch, remote, gen0)
            docs[doc.id] = doc
        }
        return DocHandle(doc.id)
    }

    override suspend fun close(handle: DocHandle) {
        val doc = synchronized(lock) { docs.remove(handle.id) }
            ?: throw EngineError.InvalidArgument("handle ${handle.id} is closed or unknown")
        if (closed.get()) return
        // Only the wait for the handle's lock is bounded here; the call itself has its own deadline.
        val locked = withTimeoutOrNull(config.editMs) { doc.mutex.lock(); true } ?: false
        if (!locked) return // a hung call holds the handle; the restart it triggers drops the document
        try {
            val b = doc.binding
            if (b != null && !closed.get()) {
                try {
                    execute(Op.CLOSE, EngineWire.handleArgs(b.remote), b.epoch, b.generation, false) { }
                } catch (_: EngineError) {
                    // The document is gone from our side either way.
                }
            }
        } finally {
            doc.mutex.unlock()
        }
    }

    /** Health check; returns the server's protocol version, boot id and pid. */
    suspend fun ping(): PingInfo {
        awaitUsable()
        return execute(Op.PING, EngineWire.emptyArgs(), epoch, knownGeneration, false) { EngineWire.parsePingResult(it.response.payload) }
    }

    /** Learns the first process's boot id and generation, once. Failures recover like any call. */
    private suspend fun ensureBooted() {
        if (lastBootId != null) return
        bootMutex.withLock {
            if (lastBootId != null) return
            val info = execute(Op.PING, EngineWire.emptyArgs(), epoch, -1L, false) { EngineWire.parsePingResult(it.response.payload) }
            lastBootId = info.bootId
            knownGeneration = transport.generation
        }
    }

    // ---- read: viewer ----

    override suspend fun meta(handle: DocHandle): DocMeta =
        docCall(handle, Op.META, { EngineWire.handleArgs(it) }) { r, _ -> EngineWire.parseMetaResult(r.response.payload) }

    override suspend fun pageInfos(handle: DocHandle): List<PageInfo> =
        docCall(handle, Op.PAGE_INFOS, { EngineWire.handleArgs(it) }) { r, _ -> EngineWire.parsePageInfosResult(r.response.payload) }

    override suspend fun renderTile(handle: DocHandle, request: TileRequest): TileBitmap {
        if (request.width <= 0 || request.height <= 0) throw EngineError.InvalidArgument("tile must have a positive size")
        if (request.width.toLong() * request.height > config.maxRenderPixels) {
            throw EngineError.TooLarge("render of ${request.width.toLong() * request.height} px exceeds ${config.maxRenderPixels}")
        }
        val key = handle.id to request
        val flight = synchronized(lock) {
            val existing = flights[key]
            val f = if (existing != null && existing.deferred.isActive) existing else newFlight(key, handle, request)
            f.waiters++
            f
        }
        try {
            val data = flight.deferred.await()
            return TileBitmap(data.width, data.height, data.pixels.duplicate().also { it.position(0) })
        } catch (e: CancellationException) {
            if (!currentCoroutineContext().isActive) throw e
            throw EngineError.EngineDied("engine closed during the call")
        } finally {
            synchronized(lock) {
                flight.waiters--
                if (flight.waiters == 0) {
                    if (flights[key] === flight) flights.remove(key)
                    if (flight.deferred.isActive) flight.deferred.cancel()
                }
            }
        }
    }

    private fun newFlight(key: Pair<Long, TileRequest>, handle: DocHandle, request: TileRequest): Flight {
        val f = Flight(key)
        f.deferred = supervisor.async {
            docCall(handle, Op.RENDER_TILE, { EngineWire.renderTileArgs(it, request) }) { r, _ ->
                val info = EngineWire.parseRenderTileResult(r.response.payload)
                val expected = request.width.toLong() * request.height * 4
                val tile = r.tile
                try {
                    if (info.width != request.width || info.height != request.height) throw TileProtocol("tile geometry differs from the request")
                    if (info.size.toLong() != expected) throw TileProtocol("tile size ${info.size} != $expected")
                    if (tile == null) throw TileProtocol("tile pixels missing")
                    if (tile.size != info.size || tile.transferId != info.transferId) throw TileProtocol("tile transfer mismatch")
                    val src = tile.asByteBuffer()
                    val copy = ByteBuffer.allocate(info.size)
                    copy.put(src)
                    copy.flip()
                    TileData(info.width, info.height, copy)
                } finally {
                    tile?.close()
                }
            }
        }
        f.deferred.invokeOnCompletion {
            synchronized(lock) { if (flights[key] === f) flights.remove(key) }
        }
        flights[key] = f
        return f
    }

    private class TileProtocol(detail: String) : Exception(detail)

    override suspend fun searchPage(handle: DocHandle, page: Int, query: String, caseSensitive: Boolean, wholeWord: Boolean): List<Rect> =
        docCall(handle, Op.SEARCH_PAGE, { EngineWire.searchPageArgs(it, page, query, caseSensitive, wholeWord) }) { r, _ ->
            EngineWire.parseSearchPageResult(r.response.payload)
        }

    override suspend fun selectText(handle: DocHandle, page: Int, from: Pt, to: Pt): TextSelection? =
        docCall(handle, Op.SELECT_TEXT, { EngineWire.selectTextArgs(it, page, from, to) }) { r, _ ->
            EngineWire.parseSelectTextResult(r.response.payload)
        }

    override suspend fun outline(handle: DocHandle, offset: Int, limit: Int): OutlinePage =
        docCall(handle, Op.OUTLINE, { EngineWire.outlineArgs(it, offset, limit) }) { r, _ -> EngineWire.parseOutlineResult(r.response.payload) }

    override suspend fun links(handle: DocHandle, page: Int): List<PageLink> =
        docCall(handle, Op.LINKS, { EngineWire.linksArgs(it, page) }) { r, _ -> EngineWire.parseLinksResult(r.response.payload) }

    // ---- edit ----

    private suspend fun stateCall(handle: DocHandle, op: Op, build: (Long) -> ByteArray): EditState =
        docCall(handle, op, build) { r, d -> d.fix(EngineWire.parseEditStateResult(r.response.payload)) }

    override suspend fun state(handle: DocHandle) = stateCall(handle, Op.STATE) { EngineWire.handleArgs(it) }
    override suspend fun rotate(handle: DocHandle, pages: List<Int>, by: Rotation) =
        stateCall(handle, Op.ROTATE) { EngineWire.rotateArgs(it, pages, by) }
    override suspend fun delete(handle: DocHandle, pages: List<Int>) =
        stateCall(handle, Op.DELETE) { EngineWire.pagesArgs(it, pages) }
    override suspend fun move(handle: DocHandle, pages: List<Int>, toIndex: Int) =
        stateCall(handle, Op.MOVE) { EngineWire.moveArgs(it, pages, toIndex) }
    override suspend fun duplicate(handle: DocHandle, pages: List<Int>) =
        stateCall(handle, Op.DUPLICATE) { EngineWire.pagesArgs(it, pages) }
    override suspend fun insertBlank(handle: DocHandle, at: Int, widthPt: Float, heightPt: Float) =
        stateCall(handle, Op.INSERT_BLANK) { EngineWire.insertBlankArgs(it, at, widthPt, heightPt) }
    override suspend fun undo(handle: DocHandle) = stateCall(handle, Op.UNDO) { EngineWire.handleArgs(it) }
    override suspend fun redo(handle: DocHandle) = stateCall(handle, Op.REDO) { EngineWire.handleArgs(it) }
    override suspend fun addMarkup(handle: DocHandle, markup: NewMarkup) =
        stateCall(handle, Op.ADD_MARKUP) { EngineWire.addMarkupArgs(it, markup) }

    override suspend fun listFields(handle: DocHandle): List<FieldInfo> =
        docCall(handle, Op.LIST_FIELDS, { EngineWire.handleArgs(it) }) { r, _ -> EngineWire.parseListFieldsResult(r.response.payload) }

    override suspend fun setField(handle: DocHandle, name: String, value: FieldValue) =
        stateCall(handle, Op.SET_FIELD) { EngineWire.setFieldArgs(it, name, value) }

    override suspend fun resetForm(handle: DocHandle, names: List<String>?) =
        stateCall(handle, Op.RESET_FORM) { EngineWire.resetFormArgs(it, names) }

    // ---- save ----

    override suspend fun saveIncremental(handle: DocHandle): ByteArray =
        docCall(handle, Op.SAVE_INCREMENTAL, { EngineWire.handleArgs(it) }) { r, _ -> EngineWire.parseBytesResult(r.response.payload) }

    override suspend fun saveFull(handle: DocHandle): ByteArray =
        docCall(handle, Op.SAVE_FULL, { EngineWire.handleArgs(it) }, { it.size >= config.killSaveFullOnCancelAboveBytes }) { r, _ ->
            EngineWire.parseBytesResult(r.response.payload)
        }

    override suspend fun confirmSaved(handle: DocHandle): EditState =
        docCall(handle, Op.CONFIRM_SAVED, { EngineWire.handleArgs(it) }) { r, d ->
            val s = EngineWire.parseEditStateResult(r.response.payload)
            d.forceDirty = false
            s
        }

    override suspend fun saveProtected(handle: DocHandle, spec: ProtectionSpec): ByteArray =
        docCall(handle, Op.SAVE_PROTECTED, { EngineWire.saveProtectedArgs(it, spec) }) { r, _ -> EngineWire.parseBytesResult(r.response.payload) }

    override suspend fun removeProtection(handle: DocHandle): EditState =
        stateCall(handle, Op.REMOVE_PROTECTION) { EngineWire.handleArgs(it) }

    // ---- files ----

    override suspend fun extract(handle: DocHandle, pages: List<Int>): ByteArray =
        docCall(handle, Op.EXTRACT, { EngineWire.pagesArgs(it, pages) }) { r, _ -> EngineWire.parseBytesResult(r.response.payload) }

    override suspend fun split(handle: DocHandle, spec: SplitSpec): List<SplitPart> =
        docCall(handle, Op.SPLIT, { EngineWire.splitArgs(it, spec) }, { Op.SPLIT in config.killOnCancel }) { r, _ ->
            EngineWire.parseSplitResult(r.response.payload)
        }

    override suspend fun merge(sources: List<MergeSource>): ByteArray {
        var total = 0L
        for (s in sources) { checkInput(s.bytes.size.toLong()); total += s.bytes.size }
        checkRequest(total)
        return stateless(Op.MERGE, { EngineWire.mergeArgs(sources) }) { EngineWire.parseBytesResult(it.response.payload) }
    }

    override suspend fun imagesToPdf(images: List<ImageInput>, dpi: Double?): ByteArray {
        var total = 0L
        for (i in images) { checkInput(i.bytes.size.toLong()); total += i.bytes.size }
        checkRequest(total)
        return stateless(Op.IMAGES_TO_PDF, { EngineWire.imagesToPdfArgs(images, dpi) }) { EngineWire.parseBytesResult(it.response.payload) }
    }

    // ---- scan ----

    override suspend fun detectDocumentCorners(frame: ImageFrame): Quad? {
        checkInput(frame.rgba.size.toLong())
        return stateless(Op.DETECT_DOCUMENT_CORNERS, { EngineWire.detectDocumentCornersArgs(frame) }) {
            EngineWire.parseDetectDocumentCornersResult(it.response.payload)
        }
    }

    override suspend fun rectifyAndClean(frame: ImageFrame, quad: Quad, look: ScanLook, level: Int): ImageFrame {
        checkInput(frame.rgba.size.toLong())
        return stateless(Op.RECTIFY_AND_CLEAN, { EngineWire.rectifyAndCleanArgs(frame, quad, look, level) }) {
            EngineWire.parseRectifyAndCleanResult(it.response.payload)
        }
    }

    // ---- request plumbing ----

    private fun Doc.fix(s: EditState) = if (forceDirty) s.copy(dirty = true) else s

    private fun checkInput(bytes: Long) {
        if (bytes > config.maxInputBytes) throw EngineError.TooLarge("input of $bytes bytes exceeds ${config.maxInputBytes}")
    }

    private fun checkRequest(total: Long) {
        if (total > config.maxRequestBytes) throw EngineError.TooLarge("request of $total bytes exceeds ${config.maxRequestBytes}")
    }

    private inline fun wireBuild(f: () -> ByteArray): ByteArray =
        try { f() } catch (e: WireException) { throw EngineError.TooLarge("request exceeds wire limits") }

    private suspend fun <T> docCall(
        handle: DocHandle,
        op: Op,
        build: (Long) -> ByteArray,
        killOnCancel: (Doc) -> Boolean = { false },
        onOk: (app.pdfa.engine.impl.TransportResponse, Doc) -> T,
    ): T {
        val doc = synchronized(lock) { docs[handle.id] }
            ?: throw EngineError.InvalidArgument("handle ${handle.id} is closed or unknown")
        return doc.mutex.withLock {
            awaitUsable()
            val b = doc.binding ?: throw EngineError.EngineDied(doc.dead ?: "document is not open in the engine")
            val payload = wireBuild { build(b.remote) }
            val result = execute(op, payload, b.epoch, b.generation, killOnCancel(doc)) { onOk(it, doc) }
            // A restart during the call means the handle may have met a different document in the
            // new process: never hand such a result to the caller.
            if (b.epoch != epoch) throw EngineError.EngineDied("engine restarted during the call")
            result
        }
    }

    private suspend fun <T> stateless(op: Op, build: () -> ByteArray, onOk: (TransportResponse) -> T): T {
        awaitUsable()
        val payload = wireBuild(build)
        return execute(op, payload, epoch, knownGeneration, op in config.killOnCancel, onOk)
    }

    private suspend fun <T> execute(
        op: Op, payload: ByteArray, epochAtCall: Long, expectedGeneration: Long, killOnCancel: Boolean,
        onOk: (TransportResponse) -> T,
    ): T {
        try {
            return rawCall(op, payload, config.deadlineFor(op), expectedGeneration, onOk)
        } catch (pf: ProcessFailure) {
            recover(pf.reason, epochAtCall, null)
            throw pf.error
        } catch (e: CancellationException) {
            if (killOnCancel && !closed.get()) startCancelRecovery(op, epochAtCall)
            throw e
        } catch (e: EngineError) {
            if (epochAtCall != epoch) throw EngineError.EngineDied("engine restarted during the call")
            throw e
        }
    }

    /**
     * One transport round trip with deadline and validation. Returns the parsed OK payload, throws the
     * server's [EngineError] for an ERROR response, or [ProcessFailure] when the process or the
     * protocol misbehaved. Has no side effects on supervision state.
     */
    private suspend fun <T> rawCall(
        op: Op, payload: ByteArray, deadlineMs: Long, expectedGeneration: Long, onOk: (TransportResponse) -> T,
    ): T {
        val id = nextRequestId.getAndIncrement()
        val deadline = deadlineMs.coerceAtLeast(1)
        val tr = try {
            withTimeout(deadline) {
                transport.call(Request(id, op, payload, deadline.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()), deadline, expectedGeneration)
            }
        } catch (e: TimeoutCancellationException) {
            transport.cancel(id)
            if (!currentCoroutineContext().isActive) throw e
            throw ProcessFailure(EngineError.Timeout("$op exceeded ${deadline} ms"), "deadline exceeded: $op")
        } catch (e: CancellationException) {
            transport.cancel(id)
            throw e
        } catch (e: EngineError) {
            // Client-side size errors (e.g. TransactionTooLarge) are not a process failure.
            if (e is EngineError.TooLarge || e is EngineError.Io) throw e
            val mapped = if (e is EngineError.Timeout || e is EngineError.EngineDied ||
                e is EngineError.Render || e is EngineError.Panic) e else EngineError.EngineDied("transport failed")
            throw ProcessFailure(mapped, "transport error during $op")
        } catch (e: Exception) {
            throw ProcessFailure(EngineError.EngineDied("engine connection failed: ${e.javaClass.simpleName}"), "transport failure during $op")
        }
        try {
            val resp = tr.response
            if (resp.requestId != id) throw ProcessFailure(violation(op, "response id mismatch"), "protocol: id mismatch")
            if (resp.status == ResponseStatus.ERROR) {
                val err = try {
                    ErrorCodec.decode(resp.payload)
                } catch (e: WireException) {
                    throw ProcessFailure(violation(op, "undecodable error"), "protocol: bad error payload")
                }
                if (ErrorCodec.isClientOnly(err)) throw ProcessFailure(violation(op, "client-only error from server"), "protocol: client-only error")
                tr.tile?.close()
                throw err
            }
            return onOk(tr)
        } catch (e: WireException) {
            throw ProcessFailure(violation(op, "malformed response"), "protocol: malformed response to $op")
        } catch (e: TileProtocol) {
            throw ProcessFailure(violation(op, e.message ?: "bad tile"), "protocol: bad tile")
        } finally {
            // tiles are closed by their consumer on success; make sure a failure never leaks one
            if (tr.response.status == ResponseStatus.ERROR) tr.tile?.close()
        }
    }

    private fun violation(op: Op, detail: String): EngineError =
        if (op == Op.RENDER_TILE) EngineError.Render("protocol error: $detail") else EngineError.Panic("protocol error: $detail")

    // ---- supervision ----

    /** Waits for any recovery in progress; fails fast when closed or degraded. */
    private suspend fun awaitUsable() {
        if (closed.get()) throw EngineError.EngineDied("engine closed")
        if (needsRestart && monotonicMs() >= degradedUntil) recoverAfterCooldown()
        if (restartMutex.isLocked) restartMutex.withLock { }
        if (closed.get()) throw EngineError.EngineDied("engine closed")
        if (monotonicMs() < degradedUntil) throw EngineError.EngineDied("engine degraded after repeated restarts; retry later")
        ensureBooted()
    }

    private suspend fun recoverAfterCooldown() {
        withContext(NonCancellable) {
            restartMutex.withLock {
                if (!needsRestart || closed.get() || monotonicMs() < degradedUntil) return@withLock
                needsRestart = false
                restartTimes.clear()
                runRecovery("engine restarted after cool-down", false)
            }
        }
    }

    private suspend fun recover(reason: String, expectEpoch: Long?, expectGeneration: Long?) {
        withContext(NonCancellable) {
            restartMutex.withLock { recoverLocked(reason, expectEpoch, expectGeneration) }
        }
    }

    /** Kill-on-cancel: the lock is taken now so later calls wait; the work runs in our own scope. */
    private fun startCancelRecovery(op: Op, epochAtCall: Long) {
        val owner = Any()
        val reason = "cancelled long operation: $op"
        if (restartMutex.tryLock(owner)) {
            supervisor.launch(start = CoroutineStart.ATOMIC) {
                try {
                    withContext(NonCancellable) { recoverLocked(reason, epochAtCall, null) }
                } finally {
                    restartMutex.unlock(owner)
                }
            }
        } else {
            supervisor.launch(start = CoroutineStart.ATOMIC) { recover(reason, epochAtCall, null) }
        }
    }

    private suspend fun recoverLocked(reason: String, expectEpoch: Long?, expectGeneration: Long?) {
        if (closed.get()) return
        if (expectEpoch != null && expectEpoch != epoch) return
        if (expectGeneration != null && expectGeneration != transport.generation) return
        runRecovery(reason, true)
    }

    private suspend fun runRecovery(reason: String, kill: Boolean) {
        var why = reason
        var doKill = kill
        val startedAt = monotonicMs()
        while (true) {
            if (!restartProcess(why, doKill)) return
            doKill = true
            if (!reopenPass(startedAt)) break
            why = "engine failed while reopening a document"
        }
        criticalBus.tryEmit(EngineEvent.SafeModeRestarted(clock(), reason))
    }

    /** Returns false when the engine went (or stays) degraded and no process was started. */
    private suspend fun restartProcess(why: String, kill: Boolean): Boolean {
        val now = monotonicMs()
        while (restartTimes.isNotEmpty() && now - restartTimes.first() >= config.stormWindowMs) restartTimes.removeFirst()
        if (restartTimes.size >= config.maxRestartsPerWindow) {
            degrade(now, why)
            return false
        }
        restartTimes.addLast(now)
        // kill() leaves the death recipient linked (BinderEngineTransport), so the old process's real
        // exit is still observed; unbind alone is not trusted to end it (the boot id check is).
        if (kill) transport.kill(why)
        for (attempt in 1..2) {
            try {
                withTimeout(config.recoveryStepMs) { transport.restart() }
            } catch (e: Exception) {
                transport.kill("restart failed")
                degrade(now, "restart failed")
                return false
            }
            synchronized(lock) {
                epoch++
                docs.values.forEach { it.binding = null }
            }
            knownGeneration = transport.generation
            // The new process must prove it is not the old one (same boot id = we attached to a zombie).
            val boot = try {
                rawCall(Op.PING, EngineWire.emptyArgs(), config.pingMs, knownGeneration) { EngineWire.parsePingResult(it.response.payload) }
            } catch (e: ProcessFailure) {
                null
            } catch (e: EngineError) {
                null
            }
            val previous = lastBootId
            if (boot != null && (previous == null || boot.bootId != previous)) {
                lastBootId = boot.bootId
                return true
            }
            if (attempt == 2) break
            transport.kill("restart gave the same or no boot id")
        }
        transport.kill("engine did not restart")
        degrade(now, "engine did not restart with a new boot id")
        return false
    }

    private fun degrade(now: Long, why: String) {
        transport.kill(why)
        synchronized(lock) {
            epoch++
            docs.values.forEach { it.binding = null }
        }
        degradedUntil = now + config.degradedCooldownMs
        needsRestart = true
        criticalBus.tryEmit(EngineEvent.SafeModeRestarted(clock(), "degraded: $why"))
    }

    private fun safeOriginal(key: String): Boolean = try { recovery.restoredFromOriginal(key) } catch (e: Exception) { false }

    /** Reopens every recoverable document. Returns true if the process failed again meanwhile. */
    private suspend fun reopenPass(startedAt: Long): Boolean {
        val todo = synchronized(lock) { docs.values.filter { it.dead == null && it.binding == null } }
        for (d in todo) {
            if (monotonicMs() - startedAt > config.maxRecoveryMs) {
                d.dead = "document cannot be recovered: recovery time limit reached"
                continue
            }
            val bytes: ByteArray? = if (d.docKey != null) {
                try {
                    withTimeoutOrNull(config.recoveryStepMs) { recovery.latestBytes(d.docKey) }
                } catch (e: Exception) {
                    null
                }
            } else {
                d.original
            }
            if (bytes == null) {
                d.dead = "document cannot be recovered: no recovery bytes"
                continue
            }
            val pw = (d.docKey?.let { k -> try { recovery.passwordFor(k) } catch (e: Exception) { null } }) ?: d.password
            try {
                val payload = EngineWire.openArgs(bytes, pw)
                val remote = rawCall(Op.OPEN, payload, config.openMs, knownGeneration) { EngineWire.parseOpenResult(it.response.payload) }
                val stillOpen = synchronized(lock) { docs.containsKey(d.id) }
                if (stillOpen) {
                    d.size = bytes.size.toLong()
                    d.forceDirty = true
                    d.binding = Binding(epoch, remote, knownGeneration)
                    val original = d.docKey == null || safeOriginal(d.docKey)
                    criticalBus.tryEmit(EngineEvent.EditsLost(DocHandle(d.id), clock(), original))
                } else {
                    try {
                        rawCall(Op.CLOSE, EngineWire.handleArgs(remote), config.editMs, knownGeneration) { }
                    } catch (_: Exception) {
                    }
                }
            } catch (pf: ProcessFailure) {
                d.dead = "document could not be reopened: it stopped the engine"
                return true
            } catch (e: EngineError) {
                d.dead = "document could not be reopened: ${e.javaClass.simpleName}"
            } catch (e: WireException) {
                d.dead = "document could not be reopened: request too large"
            }
        }
        return false
    }
}

private const val CRITICAL_REPLAY = 32

/** Opens a document with a recovery key. See [RemoteEngine.openWithKey]. */
suspend fun RemoteEngine.open(bytes: ByteArray, password: String?, docKey: String? = null): DocHandle =
    openWithKey(bytes, password, docKey)
