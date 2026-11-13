package app.pdfa.viewer.extras

import app.pdfa.engine.DocHandle
import app.pdfa.engine.EngineApi
import app.pdfa.engine.EngineError
import app.pdfa.engine.Rect
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class SearchOptions(val caseSensitive: Boolean = false, val wholeWord: Boolean = false)

/** One match. [page] is 0-based; [rect] is in page view space. The engine reports one rect per match. */
data class SearchMatch(val page: Int, val rect: Rect)

enum class SearchPhase { IDLE, WAITING, SCANNING, DONE, FAILED }

/** Why a search stopped before the last page. */
enum class SearchFailure { ENGINE_LOST }

/**
 * Everything the search UI shows. [hits] are in search order (the start page first, then
 * wrapping), so an index never changes while the search runs.
 */
data class SearchSnapshot(
    val query: String = "",
    val options: SearchOptions = SearchOptions(),
    val hits: List<SearchMatch> = emptyList(),
    /** Index into [hits] of the current match, or -1 when none is selected. */
    val current: Int = -1,
    val scannedPages: Int = 0,
    val pageCount: Int = 0,
    /** Pages whose search raised a per-page [EngineError]. The rest of the search went on. */
    val errorPages: Int = 0,
    /** True when the hit cap stopped the search while more matches existed. */
    val truncated: Boolean = false,
    val phase: SearchPhase = SearchPhase.IDLE,
    val failure: SearchFailure? = null,
) {
    /** Null when there is no query. Position is 0 until a match is selected. */
    fun counter(): SearchCounter? =
        if (query.isEmpty()) null else SearchCounter(current + 1, hits.size, truncated)
}

/** The hit counter, "3 of 128". [position] is 0 when no match is selected yet. */
data class SearchCounter(val position: Int, val total: Int, val truncated: Boolean)

/** Where the viewer should scroll after [SearchController.next] or [SearchController.previous]. */
data class SearchJump(val page: Int, val rect: Rect, val index: Int)

/**
 * Searches the document page by page, in order from the current page and wrapping around.
 *
 * - [search] is the cold core: it emits a snapshot after every page, is cancellable between
 *   pages, counts per-page engine errors, and stops on engine loss.
 * - [submit] is the stateful layer on top: typing debounces by [debounceMs], a new query
 *   cancels the running one, and an empty query clears everything.
 *
 * Call from one thread (the UI thread in the app). Engine calls run on [dispatcher].
 */
class SearchController(
    private val engine: EngineApi,
    handle: DocHandle,
    private val pageCount: Int,
    private val dispatcher: CoroutineDispatcher,
    private val scope: CoroutineScope,
    private val debounceMs: Long = DEBOUNCE_MS,
    private val maxHits: Int = MAX_HITS,
) {
    companion object {
        const val DEBOUNCE_MS: Long = 300L
        const val MAX_HITS: Int = 5000
    }

    private var handle: DocHandle = handle
    private val _state = MutableStateFlow(SearchSnapshot(pageCount = pageCount))
    val state: StateFlow<SearchSnapshot> = _state.asStateFlow()

    private var job: Job? = null
    private var lastRun: Run? = null

    /** False until the user stepped in the current run: the first [next] then goes to the auto-selected first hit. */
    private var stepped = false

    private class Run(val query: String, val options: SearchOptions, val startPage: Int)

    /**
     * Starts a search for [query] after the debounce delay, cancelling any earlier one. An empty
     * query clears the results at once and runs nothing.
     */
    fun submit(query: String, options: SearchOptions = SearchOptions(), currentPage: Int = 0) {
        job?.cancel()
        job = null
        stepped = false
        if (query.isEmpty()) {
            lastRun = null
            _state.value = SearchSnapshot(pageCount = pageCount)
            return
        }
        val run = Run(query, options, currentPage)
        lastRun = run
        _state.value = SearchSnapshot(query = query, options = options, pageCount = pageCount, phase = SearchPhase.WAITING)
        job = scope.launch {
            delay(debounceMs)
            collectRun(run)
        }
    }

    /**
     * The cold search. Emits a snapshot before the first page and after every page, then a final
     * DONE or FAILED snapshot. Per-page [EngineError]s (other than engine loss) are counted in
     * [SearchSnapshot.errorPages] and the search continues. Engine loss clears the hits and
     * ends the flow with [SearchFailure.ENGINE_LOST].
     */
    fun search(query: String, options: SearchOptions, startPage: Int): Flow<SearchSnapshot> = flow {
        require(query.isNotEmpty()) { "search needs a query" }
        val n = pageCount
        val order: List<Int> = if (n <= 0) {
            emptyList()
        } else {
            val start = startPage.coerceIn(0, n - 1)
            (0 until n).map { (start + it) % n }
        }
        val hits = ArrayList<SearchMatch>()
        var scanned = 0
        var errorPages = 0
        var truncated = false

        fun snapshot(phase: SearchPhase, failure: SearchFailure? = null) = SearchSnapshot(
            query = query,
            options = options,
            hits = hits.toList(),
            current = -1,
            scannedPages = scanned,
            pageCount = n,
            errorPages = errorPages,
            truncated = truncated,
            phase = phase,
            failure = failure,
        )

        emit(snapshot(SearchPhase.SCANNING))
        for (page in order) {
            val rects: List<Rect> = try {
                withContext(dispatcher) {
                    engine.searchPage(handle, page, query, options.caseSensitive, options.wholeWord)
                }
            } catch (e: EngineError) {
                if (e.isEngineLost()) {
                    hits.clear()
                    emit(snapshot(SearchPhase.FAILED, SearchFailure.ENGINE_LOST))
                    return@flow
                }
                errorPages++
                emptyList()
            }
            for (rect in rects) {
                if (hits.size >= maxHits) {
                    truncated = true
                    break
                }
                hits += SearchMatch(page, rect)
            }
            scanned++
            if (truncated) break
            emit(snapshot(SearchPhase.SCANNING))
        }
        emit(snapshot(SearchPhase.DONE))
    }

    /** Next match, wrapping from the last hit to the first. Null when there are no hits. */
    fun next(): SearchJump? = move(forward = true)

    /** Previous match, wrapping from the first hit to the last. Null when there are no hits. */
    fun previous(): SearchJump? = move(forward = false)

    /**
     * Re-runs the last query after a failure, with the current handle. Returns false when there
     * is nothing to re-run or the last search did not fail.
     */
    fun retry(): Boolean {
        if (_state.value.failure == null) return false
        val run = lastRun ?: return false
        rerun(run)
        return true
    }

    /**
     * The engine process restarted and the caller reopened the document. Adopts [newHandle],
     * drops the results and re-runs the last query exactly once.
     */
    fun onEngineRestarted(newHandle: DocHandle) {
        handle = newHandle
        val run = lastRun ?: return
        rerun(run)
    }

    /** Cancels any search and forgets the query. */
    fun clear() {
        job?.cancel()
        job = null
        lastRun = null
        stepped = false
        _state.value = SearchSnapshot(pageCount = pageCount)
    }

    private fun move(forward: Boolean): SearchJump? {
        val s = _state.value
        val size = s.hits.size
        if (size == 0) return null
        val index = if (forward) {
            // Hit 1 is selected automatically when results arrive, but the viewer has not scrolled to
            // it yet: the first press goes to that hit, later presses advance.
            if (s.current < 0) 0 else if (!stepped) s.current else (s.current + 1) % size
        } else {
            if (s.current <= 0) size - 1 else s.current - 1
        }
        stepped = true
        _state.update { it.copy(current = index) }
        val hit = s.hits[index]
        return SearchJump(hit.page, hit.rect, index)
    }

    private fun rerun(run: Run) {
        job?.cancel()
        stepped = false
        _state.value = SearchSnapshot(
            query = run.query,
            options = run.options,
            pageCount = pageCount,
            phase = SearchPhase.WAITING,
        )
        job = scope.launch { collectRun(run) }
    }

    private suspend fun collectRun(run: Run) {
        search(run.query, run.options, run.startPage).collect { snap ->
            _state.update { prev ->
                val keep = prev.current in snap.hits.indices
                snap.copy(current = if (keep) prev.current else if (snap.hits.isNotEmpty()) 0 else -1)
            }
        }
    }
}
