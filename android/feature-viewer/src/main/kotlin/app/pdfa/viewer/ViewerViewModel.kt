package app.pdfa.viewer

import androidx.lifecycle.ViewModel
import app.pdfa.engine.DocHandle
import app.pdfa.engine.DocMeta
import app.pdfa.engine.EngineApi
import app.pdfa.engine.EngineError
import app.pdfa.engine.EngineEvent
import app.pdfa.engine.PageInfo
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Where the document bytes come from. Implementations must not log the name or content. */
interface DocumentSource {
    suspend fun read(): DocumentBytes
}

class DocumentBytes(val bytes: ByteArray, val displayName: String) {
    override fun toString(): String = "DocumentBytes(${bytes.size} bytes)"
}

fun interface Clock {
    fun nowMs(): Long

    companion object {
        val System = Clock { java.lang.System.currentTimeMillis() }
    }
}

data class ViewerDispatchers(
    val main: CoroutineDispatcher = Dispatchers.Main.immediate,
    val io: CoroutineDispatcher = Dispatchers.IO,
    val default: CoroutineDispatcher = Dispatchers.Default,
)

sealed interface ViewerState {
    data object Loading : ViewerState
    /**
     * Asks for the password. [attemptsLeft] counts down from 3; [submitting] is true while the engine checks it.
     */
    data class PasswordRequired(
        val attemptsLeft: Int,
        val lastAttemptWrong: Boolean,
        val submitting: Boolean = false,
    ) : ViewerState

    /**
     * Three wrong passwords. UX only: this is NOT persisted and is no security control; opening the file
     * again starts a fresh count (the engine verifies every password, there is nothing to brute-force offline).
     */
    data object Locked : ViewerState
    data class Corrupted(val detail: String) : ViewerState
    data object TooLarge : ViewerState

    /** Any other failure to open (engine died, I/O). */
    data class Failed(val detail: String) : ViewerState
    data class Ready(val pages: List<PageInfo>, val meta: DocMeta) : ViewerState
}

data class SafeModeBanner(val atEpochMs: Long)

/** Everything the page surface needs for one frame. Immutable, replaced as a whole. */
data class ViewportState(
    val layout: PageLayout? = null,
    val pos: ViewPos = ViewPos(),
    val currentPage: Int = 0,
    val plan: TilePlan = TilePlan.Empty,
    val pageBadge: String = "",
    val scrollFraction: Float = 0f,
)

class ViewerViewModel(
    private val engine: EngineApi,
    private val source: DocumentSource,
    private val clock: Clock = Clock.System,
    private val dispatchers: ViewerDispatchers = ViewerDispatchers(),
    val budget: TileBudget = TileBudget.forMemoryClass(256),
    val tiles: TileCache = TileCache(budget.cacheBytes),
    private val planner: TilePlanner = TilePlanner(),
    maxTilesInFlight: Int = 2,
    tileBackoffMs: Long = 250L,
) : ViewModel() {

    private val scope = CoroutineScope(SupervisorJob() + dispatchers.main)
    private val renderScope = CoroutineScope(SupervisorJob() + dispatchers.default)
    private val closeScope = CoroutineScope(SupervisorJob() + dispatchers.default)

    private val _state = MutableStateFlow<ViewerState>(ViewerState.Loading)
    val state: StateFlow<ViewerState> = _state.asStateFlow()

    private val _title = MutableStateFlow("")
    val title: StateFlow<String> = _title.asStateFlow()

    private val _banner = MutableStateFlow<SafeModeBanner?>(null)
    val safeModeBanner: StateFlow<SafeModeBanner?> = _banner.asStateFlow()

    private val _view = MutableStateFlow(ViewportState())
    val view: StateFlow<ViewportState> = _view.asStateFlow()

    private val _contentGeneration = MutableStateFlow(0)

    /** Bumped by [onDocumentChanged]. The page surface drops its bitmaps when it changes. */
    val contentGeneration: StateFlow<Int> = _contentGeneration.asStateFlow()

    private val _tileReady = MutableSharedFlow<TileKey>(extraBufferCapacity = 256, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    val tileReady: SharedFlow<TileKey> = _tileReady.asSharedFlow()

    private var bytes: ByteArray? = null
    private var attemptsLeft = MAX_PASSWORD_ATTEMPTS
    private var submitting = false
    private var released = false
    private var viewportW = 0
    private var viewportH = 0
    private var density = 1f

    @Volatile
    internal var docHandle: DocHandle? = null
        private set

    /** The open document's handle once the state is [ViewerState.Ready], else null. For viewer add-ons (search, outline). */
    val documentHandle: DocHandle? get() = docHandle

    private var openJob: Job? = null

    /** Set by the UI: true when a tile is already resident as a bitmap, so the scheduler need not re-render it. */
    @Volatile
    var residentCheck: (TileKey) -> Boolean = { false }

    private val scheduler = TileScheduler(
        scope = renderScope,
        isCached = { tiles.peek(it) || residentCheck(it) },
        render = { req ->
            val h = docHandle ?: throw EngineError.InvalidArgument("no document")
            val generation = _contentGeneration.value
            val tile = engine.renderTile(h, req)
            // A tile rendered from the document as it was before an edit must not reach the cache.
            if (generation != _contentGeneration.value) throw CancellationException("document changed")
            tile
        },
        onReady = { key, bmp ->
            if (!released) tiles.put(key, CachedTile(bmp.width, bmp.height, bmp.premultipliedRgba))
            _tileReady.tryEmit(key)
        },
        maxInFlight = maxTilesInFlight,
        backoffBaseMs = tileBackoffMs,
    )

    val failedTiles: StateFlow<Set<TileKey>> get() = scheduler.failedKeys
    val poisonedTiles: StateFlow<Set<TileKey>> get() = scheduler.poisonedKeys

    /** Manual retry of a failed or poisoned tile. */
    fun retryTile(key: TileKey) = scheduler.retry(key)
    internal val tileScheduler: TileScheduler get() = scheduler

    init {
        scope.launch(start = CoroutineStart.UNDISPATCHED) {
            engine.events.collect { e ->
                if (e is EngineEvent.SafeModeRestarted) {
                    _banner.value = SafeModeBanner(clock.nowMs())
                    scheduler.onEngineRestarted()
                }
            }
        }
        start()
    }

    // ---- opening ----

    /** Loads the document and opens it. Also the "Try again" action. */
    fun start() {
        if (released || openJob?.isActive == true || _state.value is ViewerState.Ready) return
        openJob = scope.launch {
            _state.value = ViewerState.Loading
            attemptsLeft = MAX_PASSWORD_ATTEMPTS
            val doc = try {
                withContext(dispatchers.io) { source.read() }
            } catch (c: CancellationException) {
                throw c
            } catch (e: EngineError.TooLarge) {
                // The source may refuse a file before the engine sees it (import cap, not a PDF).
                _state.value = ViewerState.TooLarge
                return@launch
            } catch (e: EngineError.InvalidPdf) {
                _state.value = ViewerState.Corrupted(e.detail)
                return@launch
            } catch (t: Throwable) {
                _state.value = ViewerState.Failed("The file could not be read")
                return@launch
            }
            _title.value = doc.displayName
            bytes = doc.bytes
            tryOpen(null)
        }
    }

    /** The CharArray is wiped as soon as it has been copied for the engine call. */
    fun submitPassword(password: CharArray) {
        val current = _state.value
        if (current !is ViewerState.PasswordRequired || submitting) {
            password.fill('\u0000')
            return
        }
        val secret = String(password)
        password.fill('\u0000')
        submitting = true
        _state.value = current.copy(submitting = true)
        scope.launch {
            try {
                tryOpen(secret)
            } finally {
                submitting = false
            }
        }
    }

    private suspend fun tryOpen(password: String?) {
        val data = bytes ?: return
        var opened: DocHandle? = null
        var adopted = false
        try {
            // Not cancellable: if the engine finishes opening while we are being torn down, we must receive the
            // handle in order to close it (a cancelled open would otherwise leak up to 256 MB in the engine process).
            val h = withContext(NonCancellable) { engine.open(data, password) }
            opened = h
            val meta = engine.meta(h)
            val pages = engine.pageInfos(h)
            if (pages.isEmpty()) {
                _state.value = ViewerState.Corrupted("no pages")
                return
            }
            if (released) return
            docHandle?.let { closeHandle(it) } // never overwrite a live handle
            docHandle = h
            adopted = true
            bytes = null
            _state.value = ViewerState.Ready(pages, meta)
            rebuildLayout()
        } catch (c: CancellationException) {
            throw c
        } catch (e: EngineError.PasswordRequired) {
            onPasswordNeeded(wrong = password != null)
        } catch (e: EngineError.WrongPassword) {
            onPasswordNeeded(wrong = true)
        } catch (e: EngineError.InvalidPdf) {
            bytes = null
            _state.value = ViewerState.Corrupted(e.detail)
        } catch (e: EngineError.TooLarge) {
            bytes = null
            _state.value = ViewerState.TooLarge
        } catch (e: EngineError) {
            bytes = null
            _state.value = ViewerState.Failed(e.detail)
        } finally {
            val h = opened
            if (h != null && !adopted) closeHandle(h)
        }
    }

    /** Closes even when the calling coroutine is already cancelled. */
    private suspend fun closeHandle(h: DocHandle) {
        withContext(NonCancellable) {
            try { engine.close(h) } catch (_: Throwable) { }
        }
    }

    private fun onPasswordNeeded(wrong: Boolean) {
        if (wrong) attemptsLeft--
        if (attemptsLeft <= 0) {
            bytes = null
            _state.value = ViewerState.Locked
        } else {
            _state.value = ViewerState.PasswordRequired(attemptsLeft, lastAttemptWrong = wrong)
        }
    }

    /**
     * The document was edited (pages rotated, deleted, moved, annotated, undo...). Reads the page list
     * again, forgets every rendered tile and lays the pages out afresh, keeping the reading position.
     * Call after each successful edit. Failures leave the old view; the next call tries again.
     */
    fun onDocumentChanged() {
        val h = docHandle ?: return
        if (released || _state.value !is ViewerState.Ready) return
        scope.launch {
            val pages: List<PageInfo>
            val meta: DocMeta
            try {
                pages = engine.pageInfos(h)
                meta = engine.meta(h)
            } catch (e: EngineError) {
                return@launch
            }
            if (released || pages.isEmpty()) return@launch
            _contentGeneration.value++
            scheduler.cancelAll()
            tiles.clear()
            _state.value = ViewerState.Ready(pages, meta)
            rebuildLayout()
        }
    }

    /** Plans the visible tiles again (the page surface calls it once it dropped its bitmaps). */
    fun refreshTiles() = withLayout { l, v -> setPos(l, v.pos, null) }

    fun dismissSafeModeBanner() {
        _banner.value = null
    }

    // ---- viewport ----

    fun onViewportChanged(widthPx: Int, heightPx: Int, density: Float = 1f) {
        if (widthPx <= 0 || heightPx <= 0) return
        if (widthPx == viewportW && heightPx == viewportH && density == this.density) return
        viewportW = widthPx
        viewportH = heightPx
        this.density = density
        rebuildLayout()
    }

    private fun rebuildLayout() {
        val ready = _state.value as? ViewerState.Ready ?: return
        if (viewportW <= 0) return
        val old = _view.value
        val layout = PageLayout(
            ready.pages, viewportW.toFloat(), viewportH.toFloat(),
            gap = GAP_DP * density, sideMargin = SIDE_MARGIN_DP * density,
        )
        var pos = old.pos
        val oldLayout = old.layout
        if (oldLayout != null && oldLayout.contentHeight > 0f) {
            val fraction = old.pos.scrollY / old.pos.zoom / oldLayout.contentHeight
            pos = pos.copy(scrollY = fraction * layout.contentHeight * pos.zoom)
        }
        setPos(layout, pos, forcePage = null)
    }

    private fun setPos(layout: PageLayout, requested: ViewPos, forcePage: Int?) {
        val pos = layout.clamp(requested)
        val page = forcePage ?: layout.currentPage(pos)
        val plan = planner.plan(layout, pos)
        _view.value = ViewportState(layout, pos, page, plan, layout.badgeText(page), layout.scrollFraction(pos))
        scheduler.submit(plan.tiles)
    }

    private inline fun withLayout(block: (PageLayout, ViewportState) -> Unit) {
        val v = _view.value
        val l = v.layout ?: return
        block(l, v)
    }

    /** Pinch and drag: the content under the focus follows the fingers. Pan is in screen px (content moves with the finger). */
    fun transformBy(focusX: Float, focusY: Float, zoomChange: Float, panX: Float, panY: Float) = withLayout { l, v ->
        val next = l.transform(v.pos, v.pos.zoom * zoomChange, focusX, focusY, panX, panY)
        setPos(l, next, null)
    }

    /** Drag or fling step. Returns false when nothing moved (an edge was hit). */
    fun panBy(dx: Float, dy: Float): Boolean {
        val v = _view.value
        val l = v.layout ?: return false
        val next = l.clamp(v.pos.copy(scrollX = v.pos.scrollX - dx, scrollY = v.pos.scrollY - dy))
        if (next == v.pos) return false
        setPos(l, next, null)
        return true
    }

    fun zoomTo(zoom: Float, focusX: Float, focusY: Float) = withLayout { l, v ->
        setPos(l, l.transform(v.pos, zoom, focusX, focusY), null)
    }

    /** Fit width when zoomed in, otherwise [DOUBLE_TAP_ZOOM]. */
    fun doubleTapTarget(): Float = if (_view.value.pos.zoom > FIT_EPSILON) 1f else DOUBLE_TAP_ZOOM

    fun onDoubleTap(x: Float, y: Float) = zoomTo(doubleTapTarget(), x, y)

    fun jumpToPage(index: Int) = withLayout { l, v ->
        val i = index.coerceIn(0, l.count - 1)
        val y = l.scrollYForPage(i, v.pos.zoom)
        setPos(l, v.pos.copy(scrollY = y), forcePage = i)
    }

    /** Thumb position 0..1 to scroll position. */
    fun onFastScroll(fraction: Float) = withLayout { l, v ->
        setPos(l, v.pos.copy(scrollY = l.scrollYForFraction(fraction, v.pos.zoom)), null)
    }

    fun pageForFraction(fraction: Float): Int = _view.value.layout?.pageForFraction(fraction) ?: 0
    fun fractionForPage(page: Int): Float = _view.value.layout?.fractionForPage(page) ?: 0f

    /** onTrimMemory: shrink the tile cache to [bytes]. */
    fun trimTiles(bytes: Long) = tiles.trimTo(bytes)

    // ---- teardown ----

    override fun onCleared() = release()

    internal fun release() {
        if (released) return
        released = true
        scope.cancel()
        scheduler.cancelAll()
        renderScope.cancel()
        tiles.clear()
        bytes = null
        val h = docHandle
        docHandle = null
        if (h != null) {
            closeScope.launch {
                try { engine.close(h) } catch (_: Throwable) { }
            }
        }
    }

    companion object {
        const val MAX_PASSWORD_ATTEMPTS = 3
        const val DOUBLE_TAP_ZOOM = 2.5f
        const val GAP_DP = 8f
        const val SIDE_MARGIN_DP = 8f
        private const val FIT_EPSILON = 1.01f
    }
}
