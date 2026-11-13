package app.pdfa.pages.tools

import app.pdfa.engine.DocHandle
import app.pdfa.engine.EngineApi
import app.pdfa.engine.EngineError
import app.pdfa.engine.MergeSource
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withContext

/** What reading a picked file gave. The app implements the reading (size cap, `%PDF-` check). */
sealed interface SourceLoad {
    data class Loaded(val name: String, val bytes: ByteArray) : SourceLoad
    data class Failed(val name: String, val reason: Reason) : SourceLoad

    enum class Reason { NOT_PDF, TOO_LARGE, UNREADABLE }
}

enum class SourceStatus {
    CHECKING,
    READY,
    NEEDS_PASSWORD,
    WRONG_PASSWORD,
    NOT_PDF,
    TOO_LARGE,
    UNREADABLE,
    DAMAGED,
}

/** One file in the merge list. Holds no bytes and no password. */
data class MergeItem(
    val id: Long,
    val name: String,
    val status: SourceStatus,
    /** Known once the engine could open the file. */
    val pageCount: Int? = null,
    /** What the user typed, 1-based. Empty means every page. */
    val rangeText: String = "",
    val rangeError: InputError? = null,
) {
    /** Pages this file adds to the result, when known and valid. */
    val pagesUsed: Int?
        get() {
            val count = pageCount ?: return null
            return when (val r = parseRanges(rangeText, count)) {
                RangeResult.Empty -> count
                is RangeResult.Ok -> r.ranges.pageTotal()
                is RangeResult.Invalid -> null
            }
        }
}

enum class MergePhase { EDITING, MERGING, MERGED, SAVING, SAVED }

data class MergeState(
    val items: List<MergeItem> = emptyList(),
    val phase: MergePhase = MergePhase.EDITING,
    /** True while picked files are being read and checked. */
    val adding: Boolean = false,
    val problem: ToolProblem? = null,
    val problemDetail: String? = null,
    /** The last merge was cancelled by the user. */
    val cancelled: Boolean = false,
    /** Some picked files were left out because the list is full or too big together. */
    val skipped: Int = 0,
    val saveFailed: Boolean = false,
    val mergedPages: Int? = null,
    /** Where the merged file was written, once [MergePhase.SAVED]. */
    val savedTo: String? = null,
) {
    val canMerge: Boolean
        get() = phase == MergePhase.EDITING && !adding && items.size >= 2 &&
            items.all { it.status == SourceStatus.READY && it.rangeError == null }
}

/**
 * The merge list and the merge itself. No Android types.
 *
 * Source bytes and passwords live in memory only, inside this object: never in [MergeState] (which
 * the UI may keep), never in a log. They are dropped after a successful merge, on [reset] and on [release].
 *
 * Actions that call the engine are suspend functions. A failure is reported through the state, never
 * thrown; cancelling the coroutine of [merge] ends in [MergePhase.EDITING] with [MergeState.cancelled].
 */
class MergeController(
    private val engine: EngineApi,
    private val load: suspend (uri: String) -> SourceLoad,
) {
    private val _state = MutableStateFlow(MergeState())
    val state: StateFlow<MergeState> = _state.asStateFlow()

    private val bytes = HashMap<Long, ByteArray>()
    private val passwords = HashMap<Long, String>()
    private var merged: ByteArray? = null
    private var nextId = 1L

    // ---- the list ----

    /** Reads and checks the files the user picked and appends them in the order picked. */
    suspend fun addSources(uris: List<String>) {
        if (_state.value.adding || _state.value.phase != MergePhase.EDITING) return
        _state.update { it.copy(adding = true, problem = null, problemDetail = null, cancelled = false, skipped = 0) }
        try {
            var skipped = 0
            for (uri in uris) {
                if (_state.value.items.size >= MAX_SOURCES) { skipped++; continue }
                when (val got = load(uri)) {
                    is SourceLoad.Failed -> append(got.name, when (got.reason) {
                        SourceLoad.Reason.NOT_PDF -> SourceStatus.NOT_PDF
                        SourceLoad.Reason.TOO_LARGE -> SourceStatus.TOO_LARGE
                        SourceLoad.Reason.UNREADABLE -> SourceStatus.UNREADABLE
                    })
                    is SourceLoad.Loaded -> {
                        if (bytes.values.sumOf { it.size.toLong() } + got.bytes.size > MAX_TOTAL_BYTES) {
                            skipped++
                            continue
                        }
                        val id = append(got.name, SourceStatus.CHECKING)
                        bytes[id] = got.bytes
                        probeInto(id, null)
                    }
                }
            }
            _state.update { it.copy(skipped = skipped) }
        } finally {
            _state.update { it.copy(adding = false) }
        }
    }

    /** Opens a protected file with [password]. A wrong one asks again; a right one is kept in memory only. */
    suspend fun submitPassword(id: Long, password: String) {
        val item = _state.value.items.firstOrNull { it.id == id } ?: return
        if (item.status != SourceStatus.NEEDS_PASSWORD && item.status != SourceStatus.WRONG_PASSWORD) return
        if (_state.value.adding || _state.value.phase != MergePhase.EDITING) return
        _state.update { it.copy(adding = true, problem = null, problemDetail = null) }
        try {
            probeInto(id, password)
        } finally {
            _state.update { it.copy(adding = false) }
        }
    }

    fun setRange(id: Long, text: String) = updateItem(id) { item ->
        item.copy(rangeText = text, rangeError = rangeErrorFor(text, item.pageCount))
    }

    fun moveUp(id: Long) = move(id, -1)

    fun moveDown(id: Long) = move(id, +1)

    fun remove(id: Long) {
        if (_state.value.phase != MergePhase.EDITING) return
        bytes.remove(id)
        passwords.remove(id)
        _state.update { s -> s.copy(items = s.items.filterNot { it.id == id }) }
    }

    fun dismissProblem() = _state.update { it.copy(problem = null, problemDetail = null, cancelled = false, skipped = 0, saveFailed = false) }

    // ---- merge and save ----

    /** Merges the list. True when the result is ready to save. */
    suspend fun merge(): Boolean {
        val s = _state.value
        if (!s.canMerge) return false
        val sources = s.items.mapIndexed { i, item ->
            val ranges = (parseRanges(item.rangeText, item.pageCount ?: 0) as? RangeResult.Ok)?.ranges.orEmpty()
            // A neutral name: the engine may quote it in an error, and file names stay out of there.
            MergeSource("${i + 1}.pdf", bytes.getValue(item.id), passwords[item.id], ranges)
        }
        val pages = s.items.sumOf { it.pagesUsed ?: 0 }
        _state.update { it.copy(phase = MergePhase.MERGING, problem = null, problemDetail = null, cancelled = false, skipped = 0, saveFailed = false) }
        try {
            merged = engine.merge(sources)
            // The sources are no longer needed, and the passwords must not outlive their use.
            bytes.clear()
            passwords.clear()
            _state.update { it.copy(phase = MergePhase.MERGED, mergedPages = pages) }
            return true
        } catch (c: CancellationException) {
            _state.update { it.copy(phase = MergePhase.EDITING, cancelled = true) }
            throw c
        } catch (e: EngineError.Cancelled) {
            _state.update { it.copy(phase = MergePhase.EDITING, cancelled = true) }
            return false
        } catch (e: EngineError) {
            _state.update { it.copy(phase = MergePhase.EDITING, problem = e.toProblem(), problemDetail = e.detail) }
            return false
        }
    }

    /**
     * Writes the merged file with [write] (which gets [target] and the bytes and says whether it worked).
     * A failed or cancelled write keeps the merged result, so the user can choose another place.
     */
    suspend fun save(target: String, write: suspend (target: String, bytes: ByteArray) -> Boolean): Boolean {
        val s = _state.value
        val out = merged
        if (out == null || (s.phase != MergePhase.MERGED)) return false
        _state.update { it.copy(phase = MergePhase.SAVING, saveFailed = false) }
        var ok = false
        try {
            ok = try {
                write(target, out)
            } catch (c: CancellationException) {
                throw c
            } catch (e: Exception) {
                false
            }
        } finally {
            _state.update {
                if (ok) it.copy(phase = MergePhase.SAVED, savedTo = target)
                else it.copy(phase = MergePhase.MERGED, saveFailed = true)
            }
        }
        if (ok) merged = null
        return ok
    }

    /** Back to an empty list. Drops everything held in memory. */
    fun reset() {
        release()
        _state.value = MergeState()
    }

    /** Drops source bytes, passwords and the merged result. Call when the screen is left for good. */
    fun release() {
        bytes.clear()
        passwords.clear()
        merged = null
    }

    // ---- plumbing ----

    private fun append(name: String, status: SourceStatus): Long {
        val id = nextId++
        _state.update { it.copy(items = it.items + MergeItem(id, name, status)) }
        return id
    }

    private fun updateItem(id: Long, change: (MergeItem) -> MergeItem) {
        if (_state.value.phase != MergePhase.EDITING) return
        _state.update { s -> s.copy(items = s.items.map { if (it.id == id) change(it) else it }) }
    }

    private fun move(id: Long, by: Int) {
        if (_state.value.phase != MergePhase.EDITING) return
        _state.update { s ->
            val from = s.items.indexOfFirst { it.id == id }
            val to = from + by
            if (from < 0 || to !in s.items.indices) s
            else s.copy(items = s.items.toMutableList().also { java.util.Collections.swap(it, from, to) })
        }
    }

    private fun rangeErrorFor(text: String, pageCount: Int?): InputError? {
        if (pageCount == null) return null
        return (parseRanges(text, pageCount) as? RangeResult.Invalid)?.error
    }

    /** Opens [id]'s bytes with the engine, only to learn the page count or that a password is needed. */
    private suspend fun probeInto(id: Long, password: String?) {
        val data = bytes[id] ?: return
        var handle: DocHandle? = null
        try {
            handle = engine.open(data, password)
            val count = engine.pageInfos(handle).size
            if (password != null) passwords[id] = password
            setItem(id) { it.copy(status = SourceStatus.READY, pageCount = count, rangeError = rangeErrorFor(it.rangeText, count)) }
        } catch (c: CancellationException) {
            throw c
        } catch (e: EngineError.PasswordRequired) {
            setItem(id) { it.copy(status = SourceStatus.NEEDS_PASSWORD) }
        } catch (e: EngineError.WrongPassword) {
            setItem(id) { it.copy(status = SourceStatus.WRONG_PASSWORD) }
        } catch (e: EngineError.InvalidPdf) {
            setItem(id) { it.copy(status = SourceStatus.DAMAGED) }
        } catch (e: EngineError) {
            setItem(id) { it.copy(status = SourceStatus.UNREADABLE) }
            _state.update { it.copy(problem = e.toProblem(), problemDetail = e.detail) }
        } finally {
            if (handle != null) {
                withContext(NonCancellable) {
                    try { engine.close(handle) } catch (e: EngineError) { /* the engine restarted; nothing to close */ }
                }
            }
        }
    }

    private fun setItem(id: Long, change: (MergeItem) -> MergeItem) =
        _state.update { s -> s.copy(items = s.items.map { if (it.id == id) change(it) else it }) }

    companion object {
        const val MAX_SOURCES = 50
        const val MAX_TOTAL_BYTES = 256L * 1024 * 1024
    }
}
