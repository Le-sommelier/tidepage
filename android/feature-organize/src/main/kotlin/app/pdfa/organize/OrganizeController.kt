package app.pdfa.organize

import app.pdfa.engine.DocHandle
import app.pdfa.engine.EditState
import app.pdfa.engine.EngineApi
import app.pdfa.engine.EngineError
import app.pdfa.engine.PageInfo
import app.pdfa.engine.Rotation
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/** Why the last action did not happen. The screen turns this into a sentence. */
enum class OrganizeProblem {
    /** Deleting every page would leave no document. */
    CANNOT_DELETE_ALL,

    /** The engine refused or failed; [OrganizeState.problemDetail] has its text. */
    ACTION_FAILED,
}

/** Everything the organize screen shows. */
data class OrganizeState(
    val pages: List<PageInfo> = emptyList(),
    /** 0-based indices of the selected pages. Always inside [pages]. */
    val selection: Set<Int> = emptySet(),
    val busy: Boolean = false,
    val loaded: Boolean = false,
    val canUndo: Boolean = false,
    val canRedo: Boolean = false,
    /** Bumped whenever the content changed: previews must be rendered again. */
    val revision: Int = 0,
    val problem: OrganizeProblem? = null,
    val problemDetail: String? = null,
) {
    val canMoveEarlier: Boolean get() = selection.isNotEmpty() && moveBlock(selection.sorted(), selection.min() - 1, pages.size).second != selection
    val canMoveLater: Boolean get() = selection.isNotEmpty() && moveBlock(selection.sorted(), selection.min() + 1, pages.size).second != selection
}

/**
 * Where a move lands: the engine takes `toIndex` in the RESULT, valid 0..(count - picked.size), and
 * gathers the pages into one block there. Returns that clamped index and the indices the block ends up on.
 */
internal fun moveBlock(picked: List<Int>, wanted: Int, count: Int): Pair<Int, Set<Int>> {
    val to = wanted.coerceIn(0, count - picked.size)
    return to to (to until to + picked.size).toSet()
}

/**
 * Page organizer logic: no Android types. Every change goes through [edit] (in the app, the
 * document session, which owns undo state and autosave); [engine] is used only to read the page
 * list and to extract.
 *
 * Actions are suspend functions and do nothing while another action runs ([OrganizeState.busy]).
 * A failure is reported through [OrganizeState.problem], never thrown.
 */
class OrganizeController(
    private val engine: EngineApi,
    private val handle: DocHandle,
    private val edit: suspend (suspend EngineApi.(DocHandle) -> EditState) -> EditState,
) {
    private val _state = MutableStateFlow(OrganizeState())
    val state: StateFlow<OrganizeState> = _state.asStateFlow()

    /** Reads the pages and the undo flags. Call once when the screen opens. */
    suspend fun load() {
        guarded {
            val pages = engine.pageInfos(handle)
            val e = engine.state(handle)
            _state.update { it.copy(pages = pages, loaded = true, canUndo = e.canUndo, canRedo = e.canRedo, selection = emptySet(), revision = it.revision + 1) }
        }
    }

    // ---- selection ----

    fun toggle(page: Int) = _state.update {
        if (page !in it.pages.indices) it
        else it.copy(selection = if (page in it.selection) it.selection - page else it.selection + page, problem = null)
    }

    fun selectOnly(page: Int) = _state.update {
        if (page !in it.pages.indices) it else it.copy(selection = setOf(page), problem = null)
    }

    fun selectAll() = _state.update { it.copy(selection = it.pages.indices.toSet(), problem = null) }

    fun clearSelection() = _state.update { it.copy(selection = emptySet()) }

    fun dismissProblem() = _state.update { it.copy(problem = null, problemDetail = null) }

    // ---- changes ----

    suspend fun rotateRight() = rotate(Rotation.CW90)

    suspend fun rotateLeft() = rotate(Rotation.CCW90)

    private suspend fun rotate(by: Rotation) {
        val picked = selected()
        if (picked.isEmpty()) return
        change(keepSelection = picked.toSet()) { rotate(it, picked, by) }
    }

    /** Deletes the selection. Returns the deleted indices (for the "deleted" message), or null when nothing was deleted. */
    suspend fun delete(): List<Int>? {
        val s = _state.value
        val picked = selected()
        if (picked.isEmpty() || s.busy) return null
        if (picked.size >= s.pages.size) {
            _state.update { it.copy(problem = OrganizeProblem.CANNOT_DELETE_ALL, problemDetail = null) }
            return null
        }
        return if (change(keepSelection = emptySet()) { delete(it, picked) }) picked else null
    }

    /** Duplicates the selection; each copy follows its original, and the copies become the selection. */
    suspend fun duplicate() {
        val picked = selected()
        if (picked.isEmpty()) return
        val copies = picked.mapIndexed { i, p -> p + i + 1 }.toSet()
        change(keepSelection = copies) { duplicate(it, picked) }
    }

    suspend fun moveEarlier() {
        val picked = selected()
        if (picked.isEmpty()) return
        moveSelection(picked, picked.first() - 1)
    }

    suspend fun moveLater() {
        val picked = selected()
        if (picked.isEmpty()) return
        moveSelection(picked, picked.first() + 1)
    }

    /** Moves the selection so its first page lands at [targetIndex] (an index in the result, clamped to the valid range). */
    suspend fun moveTo(targetIndex: Int) {
        val picked = selected()
        if (picked.isEmpty()) return
        moveSelection(picked, targetIndex)
    }

    private suspend fun moveSelection(picked: List<Int>, wanted: Int) {
        val (to, result) = moveBlock(picked, wanted, _state.value.pages.size)
        if (result == picked.toSet()) return // already there: the end of the document
        change(keepSelection = result) { move(it, picked, to) }
    }

    /** Inserts a blank page after the last selected page (at the end when nothing is selected), sized like its neighbour. */
    suspend fun insertBlank() {
        val s = _state.value
        if (s.pages.isEmpty()) return
        val before = (s.selection.maxOrNull() ?: s.pages.lastIndex).coerceIn(0, s.pages.lastIndex)
        val size = s.pages[before]
        val at = before + 1
        change(keepSelection = setOf(at)) { insertBlank(it, at, size.widthPt, size.heightPt) }
    }

    suspend fun undo() = change(keepSelection = emptySet()) { undo(it) }

    suspend fun redo() = change(keepSelection = emptySet()) { redo(it) }

    /** The selected pages (or [pages]) as a new document, for the caller to save. Null on failure. */
    suspend fun extract(pages: List<Int> = selected()): ByteArray? {
        if (pages.isEmpty()) return null
        var bytes: ByteArray? = null
        guarded(resync = false) { bytes = engine.extract(handle, pages.sorted()) }
        return bytes
    }

    // ---- plumbing ----

    private fun selected(): List<Int> = _state.value.selection.sorted()

    /** Runs one edit, then re-reads the pages. True when it worked. */
    private suspend fun change(keepSelection: Set<Int>, block: suspend EngineApi.(DocHandle) -> EditState): Boolean =
        guarded {
            val e = edit(block)
            val pages = engine.pageInfos(handle)
            _state.update {
                it.copy(
                    pages = pages,
                    selection = keepSelection.filter { i -> i in pages.indices }.toSet(),
                    canUndo = e.canUndo,
                    canRedo = e.canRedo,
                    revision = it.revision + 1,
                )
            }
        }

    /** After a failure the document may differ from what is shown: read it again, best effort. */
    private suspend fun resync() {
        try {
            val pages = engine.pageInfos(handle)
            val e = engine.state(handle)
            _state.update {
                it.copy(
                    pages = pages,
                    selection = it.selection.filter { i -> i in pages.indices }.toSet(),
                    canUndo = e.canUndo,
                    canRedo = e.canRedo,
                    revision = it.revision + 1,
                )
            }
        } catch (c: CancellationException) {
            throw c
        } catch (e: EngineError) {
            // Keep what is shown; the problem message is already set.
        }
    }

    private suspend fun guarded(resync: Boolean = true, block: suspend () -> Unit): Boolean {
        if (_state.value.busy) return false
        _state.update { it.copy(busy = true, problem = null, problemDetail = null) }
        try {
            block()
            return true
        } catch (c: CancellationException) {
            throw c
        } catch (e: EngineError) {
            _state.update { it.copy(problem = OrganizeProblem.ACTION_FAILED, problemDetail = e.detail) }
            if (resync) resync()
            return false
        } finally {
            _state.update { it.copy(busy = false) }
        }
    }
}
