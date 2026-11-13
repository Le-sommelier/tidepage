package app.pdfa.engine.service

import app.pdfa.engine.DocHandle
import app.pdfa.engine.DocMeta
import app.pdfa.engine.EditState
import app.pdfa.engine.EngineApi
import app.pdfa.engine.EngineError
import app.pdfa.engine.EngineEvent
import app.pdfa.engine.FeatureFlags
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
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import java.nio.ByteBuffer
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * The in-process engine: [EngineApi] over a [NativeEngineBackend]. It owns the handle table,
 * argument validation, the encryption gate and the error funnel; the backend does the native work.
 *
 * Calls BLOCK the calling thread for as long as the native engine takes. This class runs only inside
 * the isolated engine process, on the service's worker threads; a call that never returns is ended
 * by the service watchdog killing the process. Calls on one handle are serialised here.
 *
 * Handles are a counter (1, 2, ...) and are never reused within a process; the per-boot nonce that
 * makes them unique across processes is added by `EngineServer`.
 */
class UniffiEngine(
    private val backend: NativeEngineBackend,
    private val flags: FeatureFlags = FeatureFlags(),
) : EngineApi {

    private class Entry(val session: NativeSession) {
        var closed = false
    }

    private val sessions = ConcurrentHashMap<Long, Entry>()
    private val nextHandle = AtomicLong(1)

    /** The native engine reports no progress yet; restart events belong to the client. */
    override val events: Flow<EngineEvent> = emptyFlow()

    /** Number of documents currently open (diagnostics and tests). */
    fun openCount(): Int = sessions.size

    /** Closes every open document. Used when the service is destroyed. */
    fun closeAll() {
        for (id in sessions.keys.toList()) release(id)
    }

    // ---- plumbing ----

    /** Every failure leaves as an [EngineError]; nothing else, and never a client-only one. */
    private inline fun <T> guard(block: () -> T): T =
        try {
            block()
        } catch (e: EngineError.Timeout) {
            throw EngineError.Panic("engine reported Timeout")
        } catch (e: EngineError.EngineDied) {
            throw EngineError.Panic("engine reported EngineDied")
        } catch (e: EngineError) {
            throw e
        } catch (e: OutOfMemoryError) {
            throw EngineError.TooLarge("out of memory")
        } catch (e: Throwable) {
            // Class name only: a message could carry document content.
            throw EngineError.Panic("engine failure: ${e.javaClass.simpleName}")
        }

    private inline fun <T> on(handle: DocHandle, block: NativeSession.() -> T): T = guard {
        val entry = sessions[handle.id] ?: throw closed()
        synchronized(entry) {
            if (entry.closed) throw closed()
            entry.session.block()
        }
    }

    private fun closed() = EngineError.InvalidArgument("unknown or closed document handle")

    private fun release(id: Long) {
        val entry = sessions.remove(id) ?: return
        synchronized(entry) {
            if (entry.closed) return
            entry.closed = true
            try {
                entry.session.close()
            } catch (_: Throwable) {
                // The handle is gone either way.
            }
        }
    }

    private fun index(v: Int, what: String): Int {
        if (v < 0) throw EngineError.InvalidArgument("$what must not be negative")
        return v
    }

    private fun indices(v: List<Int>, what: String): List<Int> {
        if (v.any { it < 0 }) throw EngineError.InvalidArgument("$what must not be negative")
        return v
    }

    private fun checkInput(size: Int) {
        if (size.toLong() > MAX_INPUT_BYTES) throw EngineError.TooLarge("input exceeds $MAX_INPUT_BYTES bytes")
    }

    private fun checkFrame(frame: ImageFrame) {
        val w = frame.width
        val h = frame.height
        if (w <= 0 || h <= 0) throw EngineError.InvalidArgument("frame size must be positive")
        if (w.toLong() * h > MAX_RENDER_PIXELS) throw EngineError.TooLarge("frame exceeds $MAX_RENDER_PIXELS pixels")
        if (frame.rgba.size.toLong() != w.toLong() * h * 4) throw EngineError.InvalidArgument("frame is not width*height*4 bytes")
    }

    private fun requireEncryption() {
        if (!flags.encrypt) throw EngineError.Unsupported("encryption is switched off in this build")
        if (!guard { backend.encryptionAvailable() }) throw EngineError.Unsupported("the engine was built without encryption")
    }

    // ---- lifecycle ----

    override suspend fun open(bytes: ByteArray, password: String?): DocHandle = guard {
        checkInput(bytes.size)
        val session = backend.open(bytes, password)
        val id = nextHandle.getAndIncrement()
        sessions[id] = Entry(session)
        DocHandle(id)
    }

    /** Idempotent: closing an unknown or already closed handle does nothing. */
    override suspend fun close(handle: DocHandle) = release(handle.id)

    // ---- read ----

    override suspend fun meta(handle: DocHandle): DocMeta = on(handle) { meta() }
    override suspend fun pageInfos(handle: DocHandle): List<PageInfo> = on(handle) { pageInfos() }

    override suspend fun renderTile(handle: DocHandle, request: TileRequest): TileBitmap = guard {
        index(request.page, "page")
        if (request.x < 0 || request.y < 0) throw EngineError.InvalidArgument("tile origin must not be negative")
        if (request.width <= 0 || request.height <= 0) throw EngineError.InvalidArgument("tile size must be positive")
        if (!(request.scale > 0f) || request.scale.isInfinite()) throw EngineError.InvalidArgument("scale must be positive and finite")
        val pixels = request.width.toLong() * request.height
        if (pixels > MAX_RENDER_PIXELS) throw EngineError.TooLarge("tile exceeds $MAX_RENDER_PIXELS pixels")
        val raw = on(handle) { renderTile(request) }
        val size = (pixels * 4).toInt()
        if (raw.width != request.width || raw.height != request.height || raw.rgba.size != size) {
            throw EngineError.Render("engine returned a tile of unexpected size")
        }
        val buffer = ByteBuffer.allocateDirect(size)
        buffer.put(raw.rgba)
        buffer.flip()
        TileBitmap(raw.width, raw.height, buffer)
    }

    override suspend fun searchPage(handle: DocHandle, page: Int, query: String, caseSensitive: Boolean, wholeWord: Boolean): List<Rect> =
        on(handle) { searchPage(index(page, "page"), query, caseSensitive, wholeWord) }

    override suspend fun selectText(handle: DocHandle, page: Int, from: Pt, to: Pt): TextSelection? =
        on(handle) { selectText(index(page, "page"), from, to) }

    override suspend fun outline(handle: DocHandle, offset: Int, limit: Int): OutlinePage =
        on(handle) { outline(index(offset, "offset"), index(limit, "limit")) }

    override suspend fun links(handle: DocHandle, page: Int): List<PageLink> = on(handle) { links(index(page, "page")) }

    // ---- edit ----

    override suspend fun state(handle: DocHandle): EditState = on(handle) { state() }

    override suspend fun rotate(handle: DocHandle, pages: List<Int>, by: Rotation): EditState =
        on(handle) { rotate(indices(pages, "pages"), by) }

    override suspend fun delete(handle: DocHandle, pages: List<Int>): EditState = on(handle) { delete(indices(pages, "pages")) }

    override suspend fun move(handle: DocHandle, pages: List<Int>, toIndex: Int): EditState =
        on(handle) { move(indices(pages, "pages"), index(toIndex, "toIndex")) }

    override suspend fun duplicate(handle: DocHandle, pages: List<Int>): EditState =
        on(handle) { duplicate(indices(pages, "pages")) }

    override suspend fun insertBlank(handle: DocHandle, at: Int, widthPt: Float, heightPt: Float): EditState =
        on(handle) { insertBlank(index(at, "at"), widthPt, heightPt) }

    override suspend fun undo(handle: DocHandle): EditState = on(handle) { undo() }
    override suspend fun redo(handle: DocHandle): EditState = on(handle) { redo() }

    override suspend fun addMarkup(handle: DocHandle, markup: NewMarkup): EditState = on(handle) {
        index(markup.page, "page")
        addMarkup(markup)
    }

    override suspend fun listFields(handle: DocHandle): List<FieldInfo> = on(handle) { listFields() }
    override suspend fun setField(handle: DocHandle, name: String, value: FieldValue): EditState = on(handle) { setField(name, value) }
    override suspend fun resetForm(handle: DocHandle, names: List<String>?): EditState = on(handle) { resetForm(names) }

    // ---- save ----

    override suspend fun saveIncremental(handle: DocHandle): ByteArray = on(handle) { saveIncremental() }
    override suspend fun saveFull(handle: DocHandle): ByteArray = on(handle) { saveFull() }
    override suspend fun confirmSaved(handle: DocHandle): EditState = on(handle) { confirmSaved() }

    override suspend fun saveProtected(handle: DocHandle, spec: ProtectionSpec): ByteArray {
        requireEncryption()
        return on(handle) { saveProtected(spec) }
    }

    override suspend fun removeProtection(handle: DocHandle): EditState {
        requireEncryption()
        return on(handle) { removeProtection() }
    }

    // ---- files ----

    override suspend fun extract(handle: DocHandle, pages: List<Int>): ByteArray = on(handle) { extract(indices(pages, "pages")) }

    override suspend fun split(handle: DocHandle, spec: SplitSpec): List<SplitPart> = on(handle) {
        when (spec) {
            is SplitSpec.EveryN -> if (spec.n <= 0) throw EngineError.InvalidArgument("n must be positive")
            is SplitSpec.Before -> indices(spec.pages, "pages")
        }
        split(spec)
    }

    override suspend fun merge(sources: List<MergeSource>): ByteArray = guard {
        var total = 0L
        for (s in sources) {
            total += s.bytes.size
            for (r in s.ranges) {
                if (r.start < 0 || r.endInclusive < r.start) throw EngineError.InvalidArgument("bad page range")
            }
        }
        if (total > MAX_INPUT_BYTES) throw EngineError.TooLarge("inputs exceed $MAX_INPUT_BYTES bytes")
        backend.merge(sources)
    }

    override suspend fun imagesToPdf(images: List<ImageInput>, dpi: Double?): ByteArray = guard {
        if (images.sumOf { it.bytes.size.toLong() } > MAX_INPUT_BYTES) throw EngineError.TooLarge("inputs exceed $MAX_INPUT_BYTES bytes")
        if (dpi != null && !(dpi > 0.0 && dpi.isFinite())) throw EngineError.InvalidArgument("dpi must be positive and finite")
        backend.imagesToPdf(images, dpi)
    }

    // ---- scan ----

    override suspend fun detectDocumentCorners(frame: ImageFrame): Quad? = guard {
        checkFrame(frame)
        backend.detectDocumentCorners(frame)
    }

    override suspend fun rectifyAndClean(frame: ImageFrame, quad: Quad, look: ScanLook, level: Int): ImageFrame = guard {
        checkFrame(frame)
        if (level !in 0..255) throw EngineError.InvalidArgument("level must be 0..255")
        backend.rectifyAndClean(frame, quad, look, level)
    }

    companion object {
        /** Same caps as the client (`WireLimits`): refused here too, the client is not trusted. */
        const val MAX_INPUT_BYTES: Long = 256L * 1024 * 1024
        const val MAX_RENDER_PIXELS: Long = 64L * 1024 * 1024
    }
}
