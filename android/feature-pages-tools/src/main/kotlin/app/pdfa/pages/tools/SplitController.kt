package app.pdfa.pages.tools

import app.pdfa.engine.DocHandle
import app.pdfa.engine.EngineApi
import app.pdfa.engine.EngineError
import app.pdfa.engine.SplitSpec
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

enum class SplitMode { EXTRACT, EVERY_N, BEFORE }

enum class SplitPhase { EDITING, WORKING, READY, SAVING, DONE }

/** How a save ended. */
sealed interface SaveReport {
    data object FileSaved : SaveReport
    data object FileFailed : SaveReport

    /** All [total] parts were written. */
    data class PartsSaved(val total: Int) : SaveReport

    /** Writing stopped at part number [failedPart] (1-based); [written] parts before it are complete. */
    data class PartsFailed(val written: Int, val total: Int, val failedPart: Int) : SaveReport
}

data class SplitState(
    val mode: SplitMode = SplitMode.EXTRACT,
    val input: String = "",
    /** Null when [input] is valid or still empty. */
    val inputError: InputError? = null,
    /** What the current input would make: pages for extract, parts for a split. Null when invalid or empty. */
    val outcomeCount: Int? = null,
    val pageCount: Int = 0,
    val phase: SplitPhase = SplitPhase.EDITING,
    val problem: ToolProblem? = null,
    val problemDetail: String? = null,
    val cancelled: Boolean = false,
    /** Parts written so far while saving. */
    val written: Int = 0,
    /** For READY: how many parts wait to be saved (0 for an extracted file). */
    val readyParts: Int = 0,
    val report: SaveReport? = null,
) {
    val canRun: Boolean get() = phase == SplitPhase.EDITING && outcomeCount != null
}

/** Writes one part somewhere and says whether it worked. [index] is 0-based. */
fun interface PartSink {
    suspend fun write(index: Int, name: String, bytes: ByteArray): Boolean
}

/**
 * Extract and split for one open document. No Android types.
 *
 * [prepare] runs before every engine call; the app uses it to write a pending autosave first, because
 * stopping the work restarts the engine and the open document is then reopened from disk.
 */
class SplitController(
    private val engine: EngineApi,
    private val handle: DocHandle,
    pageCount: Int,
    private val prepare: suspend () -> Unit = {},
) {
    private val _state = MutableStateFlow(SplitState(pageCount = pageCount))
    val state: StateFlow<SplitState> = _state.asStateFlow()

    private var extracted: ByteArray? = null
    private var parts: List<ByteArray> = emptyList()

    fun setMode(mode: SplitMode) {
        if (_state.value.phase != SplitPhase.EDITING) return
        _state.update { evaluate(it.copy(mode = mode, input = "", problem = null, problemDetail = null, cancelled = false, report = null)) }
    }

    fun setInput(text: String) {
        if (_state.value.phase != SplitPhase.EDITING) return
        _state.update { evaluate(it.copy(input = text.take(MAX_INPUT), problem = null, problemDetail = null, cancelled = false, report = null)) }
    }

    fun dismissProblem() = _state.update { it.copy(problem = null, problemDetail = null, cancelled = false) }

    /** Makes the file or the parts, held in memory until [saveFile] or [saveParts]. True when they are ready. */
    suspend fun run(): Boolean {
        val s = _state.value
        if (!s.canRun) return false
        _state.update { it.copy(phase = SplitPhase.WORKING, problem = null, problemDetail = null, cancelled = false, report = null) }
        try {
            prepare()
            val ready: Int
            when (s.mode) {
                SplitMode.EXTRACT -> {
                    val ranges = (parseRanges(s.input, s.pageCount) as RangeResult.Ok).ranges
                    // Pages keep their order in the document, however they were typed.
                    extracted = engine.extract(handle, ranges.pageIndices().sorted())
                    parts = emptyList()
                    ready = 0
                }
                SplitMode.EVERY_N, SplitMode.BEFORE -> {
                    val spec = specFor(s)
                    val made = engine.split(handle, spec).map { it.bytes }
                    if (made.size > MAX_PARTS) {
                        throw EngineError.TooLarge("the split would make ${made.size} parts")
                    }
                    parts = made
                    extracted = null
                    ready = made.size
                }
            }
            _state.update { it.copy(phase = SplitPhase.READY, readyParts = ready) }
            return true
        } catch (c: CancellationException) {
            _state.update { it.copy(phase = SplitPhase.EDITING, cancelled = true) }
            throw c
        } catch (e: EngineError.Cancelled) {
            _state.update { it.copy(phase = SplitPhase.EDITING, cancelled = true) }
            return false
        } catch (e: EngineError) {
            _state.update { it.copy(phase = SplitPhase.EDITING, problem = e.toProblem(), problemDetail = e.detail) }
            return false
        }
    }

    /** Writes the extracted file with [write]. A failure keeps the result for another try. */
    suspend fun saveFile(write: suspend (bytes: ByteArray) -> Boolean): Boolean {
        val out = extracted
        if (out == null || _state.value.phase != SplitPhase.READY) return false
        _state.update { it.copy(phase = SplitPhase.SAVING, written = 0, report = null) }
        var ok = false
        try {
            ok = try { write(out) } catch (c: CancellationException) { throw c } catch (e: Exception) { false }
        } finally {
            _state.update {
                if (ok) it.copy(phase = SplitPhase.DONE, report = SaveReport.FileSaved)
                else it.copy(phase = SplitPhase.READY, report = SaveReport.FileFailed)
            }
        }
        if (ok) extracted = null
        return ok
    }

    /**
     * Writes the parts one by one, named part-01.pdf, part-02.pdf ..., stopping at the first failure.
     * The report names the part that failed; the parts stay in memory so the user can try another folder.
     */
    suspend fun saveParts(sink: PartSink): SaveReport? {
        val all = parts
        if (all.isEmpty() || _state.value.phase != SplitPhase.READY) return null
        _state.update { it.copy(phase = SplitPhase.SAVING, written = 0, report = null) }
        var report: SaveReport? = null
        try {
            var written = 0
            for ((i, bytes) in all.withIndex()) {
                val ok = try {
                    sink.write(i, partName(i, all.size), bytes)
                } catch (c: CancellationException) {
                    throw c
                } catch (e: Exception) {
                    false
                }
                if (!ok) {
                    report = SaveReport.PartsFailed(written, all.size, i + 1)
                    return report
                }
                written++
                _state.update { it.copy(written = written) }
            }
            report = SaveReport.PartsSaved(all.size)
            return report
        } finally {
            val done = report is SaveReport.PartsSaved
            _state.update { it.copy(phase = if (done) SplitPhase.DONE else SplitPhase.READY, report = report) }
            if (done) parts = emptyList()
        }
    }

    /** Back to the form (after a result), keeping the input. */
    fun again() {
        extracted = null
        parts = emptyList()
        _state.update { evaluate(it.copy(phase = SplitPhase.EDITING, report = null, written = 0, readyParts = 0)) }
    }

    /** Drops the held result. Call when the screen is left. */
    fun release() {
        extracted = null
        parts = emptyList()
    }

    // ---- plumbing ----

    private fun specFor(s: SplitState): SplitSpec = when (val p = plan(s)) {
        is SplitPlan.Ok -> p.spec
        is SplitPlan.Invalid -> error("checked before")
    }

    private fun plan(s: SplitState): SplitPlan =
        if (s.mode == SplitMode.EVERY_N) planEveryN(s.input, s.pageCount) else planBefore(s.input, s.pageCount)

    /** Recomputes [SplitState.inputError] and [SplitState.outcomeCount] from mode and input. */
    private fun evaluate(s: SplitState): SplitState {
        if (s.input.isBlank()) return s.copy(inputError = null, outcomeCount = null)
        return when (s.mode) {
            SplitMode.EXTRACT -> when (val r = parseRanges(s.input, s.pageCount)) {
                RangeResult.Empty -> s.copy(inputError = null, outcomeCount = null)
                is RangeResult.Ok -> s.copy(inputError = null, outcomeCount = r.ranges.pageTotal())
                is RangeResult.Invalid -> s.copy(inputError = r.error, outcomeCount = null)
            }
            else -> when (val p = plan(s)) {
                is SplitPlan.Ok -> s.copy(inputError = null, outcomeCount = p.parts)
                is SplitPlan.Invalid -> s.copy(inputError = p.error, outcomeCount = null)
            }
        }
    }

    private companion object {
        const val MAX_INPUT = 2000
    }
}
