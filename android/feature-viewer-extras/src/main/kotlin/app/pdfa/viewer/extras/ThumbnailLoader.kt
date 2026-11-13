package app.pdfa.viewer.extras

import app.pdfa.engine.DocHandle
import app.pdfa.engine.EngineApi
import app.pdfa.engine.EngineError
import app.pdfa.engine.PageInfo
import app.pdfa.engine.TileRequest
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlin.math.floor

/** Scale and size maths for thumbnails. Pure, no engine. */
object ThumbnailMath {
    /** Tallest thumbnail in device pixels; very tall pages are scaled down to fit. */
    const val MAX_HEIGHT_PX: Int = 4096

    /**
     * Device pixels per point that make the page [targetWidthPx] wide, reduced when the page
     * would be taller than [MAX_HEIGHT_PX]. Null for a page with no usable size.
     */
    fun scaleFor(page: PageInfo, targetWidthPx: Int): Float? {
        if (targetWidthPx <= 0) return null
        val w = page.widthPt
        val h = page.heightPt
        if (!(w > 0f) || !w.isFinite() || !(h > 0f) || !h.isFinite()) return null
        val byWidth = targetWidthPx.toFloat() / w
        val byHeight = MAX_HEIGHT_PX.toFloat() / h
        return minOf(byWidth, byHeight)
    }

    /**
     * Tile size in device pixels for [page] at [scale]. Rounds down, so the tile never exceeds
     * what the engine accepts; each side is at least 1 pixel. Null for a bad scale.
     */
    fun tileSize(page: PageInfo, scale: Float): Pair<Int, Int>? {
        if (!(scale > 0f) || !scale.isFinite()) return null
        val w = floor(page.widthPt * scale).toInt().coerceAtLeast(1)
        val h = floor(page.heightPt * scale).toInt().coerceAtLeast(1)
        return w to h
    }
}

/** Cache key: a page at one pixel size. */
data class ThumbKey(val page: Int, val width: Int, val height: Int)

/**
 * Least-recently-used cache of RGBA bytes, capped by total size. An entry bigger than the cap is
 * never stored. Not thread-safe: use it from the loader's thread.
 */
class LruByteCache(val maxBytes: Long) {
    private val map = LinkedHashMap<ThumbKey, ByteArray>(16, 0.75f, true)

    var sizeBytes: Long = 0L
        private set

    val entryCount: Int get() = map.size

    fun get(key: ThumbKey): ByteArray? = map[key]

    fun contains(key: ThumbKey): Boolean = map.containsKey(key)

    /** Stores [value] and evicts the least recently used entries to fit. False when it cannot fit at all. */
    fun put(key: ThumbKey, value: ByteArray): Boolean {
        val size = value.size.toLong()
        map.remove(key)?.let { sizeBytes -= it.size.toLong() }
        if (size > maxBytes) return false
        while (sizeBytes + size > maxBytes && map.isNotEmpty()) {
            val eldest = map.keys.first()
            val evicted = map.remove(eldest) ?: break
            sizeBytes -= evicted.size.toLong()
        }
        map[key] = value
        sizeBytes += size
        return true
    }

    fun clear() {
        map.clear()
        sizeBytes = 0L
    }
}

/** What a thumbnail slot shows. Absent from the map means not requested (or scrolled away). */
sealed interface ThumbnailState {
    object Loading : ThumbnailState

    /** Premultiplied RGBA8, row-major, `width * height * 4` bytes. */
    class Ready(val width: Int, val height: Int, val rgba: ByteArray) : ThumbnailState

    /** [engineLost] means the document must be reopened before a retry can work. */
    class Failed(val engineLost: Boolean) : ThumbnailState
}

/**
 * Renders small preview tiles for the pages on screen. Only visible pages are rendered: pages
 * that scroll out of view have their job cancelled and their state dropped; their bytes stay in
 * the LRU cache (cap [cacheBytes]). At most [maxParallel] renders run at once. A failed page
 * stays failed while visible, until [retry].
 *
 * Call every method from the thread that owns [scope] (the UI thread in the app).
 */
class ThumbnailLoader(
    private val engine: EngineApi,
    handle: DocHandle,
    private val pages: List<PageInfo>,
    private val targetWidthPx: Int,
    private val dispatcher: CoroutineDispatcher,
    private val scope: CoroutineScope,
    cacheBytes: Long = CACHE_BYTES,
    maxParallel: Int = MAX_PARALLEL,
) {
    companion object {
        const val CACHE_BYTES: Long = 8L * 1024 * 1024
        const val MAX_PARALLEL: Int = 2

        /** A tile above this many bytes is never requested: it shows as a failed placeholder. */
        const val MAX_TILE_BYTES: Long = 2L * 1024 * 1024
    }

    private var handle: DocHandle = handle
    private val cache = LruByteCache(cacheBytes)
    private val jobs = HashMap<Int, Job>()
    private val gate = Semaphore(maxParallel)
    private var visible: Set<Int> = emptySet()

    private val _states = MutableStateFlow<Map<Int, ThumbnailState>>(emptyMap())
    val states: StateFlow<Map<Int, ThumbnailState>> = _states.asStateFlow()

    /** The LazyGrid calls this with the indices now on screen. */
    fun setVisible(pageIndices: Set<Int>) {
        visible = pageIndices
        jobs.keys.filter { it !in visible }.toList().forEach { cancel(it) }
        _states.update { map -> map.filterKeys { it in visible } }
        for (page in visible.sorted()) ensure(page)
    }

    /** Clears a failed slot and asks for it again. */
    fun retry(page: Int) {
        if (_states.value[page] !is ThumbnailState.Failed) return
        _states.update { it - page }
        if (page in visible) ensure(page)
    }

    /** The engine restarted: adopts the new handle, drops every render in flight and the cache. */
    fun onEngineRestarted(newHandle: DocHandle) {
        handle = newHandle
        jobs.values.forEach { it.cancel() }
        jobs.clear()
        cache.clear()
        _states.value = emptyMap()
        setVisible(visible)
    }

    /** Cancels every render. The owner's scope is not touched. */
    fun close() {
        jobs.values.forEach { it.cancel() }
        jobs.clear()
        _states.value = emptyMap()
    }

    private fun cancel(page: Int) {
        jobs.remove(page)?.cancel()
    }

    private fun ensure(page: Int) {
        if (_states.value.containsKey(page)) return
        val info = pages.getOrNull(page)
        val scale = info?.let { ThumbnailMath.scaleFor(it, targetWidthPx) }
        val size = if (info != null && scale != null) ThumbnailMath.tileSize(info, scale) else null
        if (scale == null || size == null) {
            setState(page, ThumbnailState.Failed(engineLost = false))
            return
        }
        val (w, h) = size
        if (w.toLong() * h * 4 > MAX_TILE_BYTES) {
            setState(page, ThumbnailState.Failed(engineLost = false))
            return
        }
        val key = ThumbKey(page, w, h)
        cache.get(key)?.let { bytes ->
            setState(page, ThumbnailState.Ready(w, h, bytes))
            return
        }
        setState(page, ThumbnailState.Loading)
        val job = scope.launch(start = CoroutineStart.LAZY) {
            try {
                val rgba = gate.withPermit { render(page, scale, w, h) }
                cache.put(key, rgba)
                setState(page, ThumbnailState.Ready(w, h, rgba))
            } catch (e: EngineError) {
                setState(page, ThumbnailState.Failed(engineLost = e.isEngineLost()))
            } finally {
                if (jobs[page] === this.coroutineContext[Job]) jobs.remove(page)
            }
        }
        jobs[page] = job
        job.start()
    }

    private suspend fun render(page: Int, scale: Float, w: Int, h: Int): ByteArray {
        val tile = withContext(dispatcher) {
            engine.renderTile(handle, TileRequest(page = page, scale = scale, x = 0, y = 0, width = w, height = h, preview = true))
        }
        if (tile.width != w || tile.height != h) throw EngineError.Render("thumbnail came back ${tile.width}x${tile.height}, asked ${w}x$h")
        val buffer = tile.premultipliedRgba.duplicate()
        buffer.rewind()
        val bytes = ByteArray(w * h * 4)
        if (buffer.remaining() < bytes.size) throw EngineError.Render("thumbnail is short of bytes")
        buffer.get(bytes)
        return bytes
    }

    private fun setState(page: Int, state: ThumbnailState) {
        _states.update { it + (page to state) }
    }
}
