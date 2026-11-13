package app.pdfa.viewer.extras

import app.pdfa.engine.DocHandle
import app.pdfa.engine.EngineApi
import app.pdfa.engine.EngineError
import app.pdfa.engine.OutlineRow
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext

sealed interface OutlineState {
    object Idle : OutlineState
    object Loading : OutlineState

    /** [rows] is what the LazyColumn shows; empty means the document has no contents. */
    data class Ready(val rows: List<VisibleOutlineRow>, val truncated: Boolean, val rowCount: Int) : OutlineState

    /** [engineLost] tells the UI a retry needs the document reopened first. No engine text is kept. */
    data class Failed(val engineLost: Boolean) : OutlineState
}

/**
 * Loads the outline in pages and keeps the expand/collapse state. Rows start with the engine's
 * `open` flag; [toggle] overrides that per row. Every change re-publishes [state].
 */
class OutlineController(
    private val engine: EngineApi,
    private val handle: DocHandle,
    private val pageCount: Int,
    private val dispatcher: CoroutineDispatcher,
    private val pageSize: Int = PAGE_SIZE,
    private val maxRows: Int = MAX_ROWS,
) {
    companion object {
        const val PAGE_SIZE: Int = 200
        const val MAX_ROWS: Int = 50_000
    }

    private val _state = MutableStateFlow<OutlineState>(OutlineState.Idle)
    val state: StateFlow<OutlineState> = _state.asStateFlow()

    private var tree: OutlineTree = OutlineTree.EMPTY
    private var truncated = false
    private val overrides = HashMap<List<Int>, Boolean>()

    /**
     * Loads every page until the engine reports `next == null`. A page that is empty but still
     * points somewhere, or an offset that does not move forward, fails instead of looping.
     */
    suspend fun load(): OutlineState {
        _state.value = OutlineState.Loading
        truncated = false
        val rows = ArrayList<OutlineRow>()
        var offset = 0
        try {
            while (true) {
                val page = withContext(dispatcher) { engine.outline(handle, offset, pageSize) }
                val room = maxRows - rows.size
                if (page.rows.size > room) {
                    rows.addAll(page.rows.subList(0, room))
                    truncated = true
                    break
                }
                rows.addAll(page.rows)
                if (rows.size >= maxRows) {
                    truncated = page.next != null
                    break
                }
                val next = page.next ?: break
                if (page.rows.isEmpty() || next <= offset) {
                    _state.value = OutlineState.Failed(engineLost = false)
                    return _state.value
                }
                offset = next
            }
        } catch (e: EngineError) {
            tree = OutlineTree.EMPTY
            _state.value = OutlineState.Failed(engineLost = e.isEngineLost())
            return _state.value
        }
        tree = OutlineTree.build(rows)
        overrides.clear()
        publish()
        return _state.value
    }

    /** Flips one row between expanded and collapsed. Unknown paths are ignored. */
    fun toggle(path: List<Int>) {
        val node = tree.nodeAt(path) ?: return
        if (node.children.isEmpty()) return
        overrides[path] = !isExpanded(node)
        publish()
    }

    /** The page a row jumps to, or null when the row is not navigable. */
    fun pageOf(path: List<Int>): Int? = tree.nodeAt(path)?.page?.takeIf { it in 0 until pageCount }

    /** The current expansion state of a row (false for rows without children). */
    fun isExpanded(path: List<Int>): Boolean = tree.nodeAt(path)?.let { isExpanded(it) } ?: false

    private fun isExpanded(node: OutlineNode): Boolean {
        if (node.children.isEmpty()) return false
        return overrides[node.path] ?: node.defaultOpen
    }

    private fun publish() {
        val rows = tree.visible { isExpanded(it) }
        _state.value = OutlineState.Ready(rows, truncated, tree.nodeCount)
    }
}
