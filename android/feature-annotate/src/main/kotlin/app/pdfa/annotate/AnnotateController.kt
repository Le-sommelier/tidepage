package app.pdfa.annotate

import app.pdfa.engine.DocHandle
import app.pdfa.engine.EditState
import app.pdfa.engine.EngineApi
import app.pdfa.engine.NewMarkup
import app.pdfa.engine.Pt
import app.pdfa.engine.Rect
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

enum class MarkStatus {
    /** Ink still collecting strokes (the group window is open). */
    OPEN,

    /** Sent to the engine, waiting for the answer. */
    SAVING,

    /** The engine refused or failed: kept on screen until the user retries or discards it. */
    FAILED,

    /** In the document; shown a moment longer while the pages are drawn again. */
    SETTLING,
}

/** A markup drawn by the app itself, because the re-rendered pages do not show it (yet, or at all). */
data class PendingMark(val id: Long, val markup: NewMarkup, val status: MarkStatus)

enum class AnnotateProblem {
    /** Some marks could not be added; they are kept with Retry and Discard. */
    NOT_ADDED,

    /** Undo failed. Nothing was lost. */
    UNDO_FAILED,
}

/** A note waiting for its text. */
data class NoteDraft(val page: Int, val rect: Rect)

data class AnnotateState(
    val tool: AnnotateTool = AnnotateTool.PEN,
    val penSwatch: Swatch = AnnotatePalette.ink[0],
    val highlightSwatch: Swatch = AnnotatePalette.highlight[0],
    val noteSwatch: Swatch = AnnotatePalette.ink[1],
    val width: LineWidth = LineWidth.MEDIUM,
    val pending: List<PendingMark> = emptyList(),
    val noteDraft: NoteDraft? = null,
    val problem: AnnotateProblem? = null,
) {
    /** The colour of the current tool. */
    val swatch: Swatch
        get() = when (tool) {
            AnnotateTool.PEN -> penSwatch
            AnnotateTool.HIGHLIGHTER -> highlightSwatch
            AnnotateTool.NOTE -> noteSwatch
        }

    val inkStyle: InkStyle get() = InkStyle(penSwatch.rgb, penSwatch.opacity, width.pt)
    val busy: Boolean get() = pending.any { it.status == MarkStatus.SAVING }
    val failed: List<PendingMark> get() = pending.filter { it.status == MarkStatus.FAILED }

    /** Marks that are not in the document yet (open, saving or failed). */
    val unsaved: Boolean get() = pending.any { it.status != MarkStatus.SETTLING }
}

/**
 * Annotation logic: no Android types. Every change goes through [edit] (in the app, the document
 * session, which owns undo state and autosave), one at a time and in order.
 *
 * Ink strokes made on the same page in the same style within [groupWindowMs] of each other are sent
 * as ONE markup, so one undo removes them all. Until the engine has a mark, it is drawn from
 * [AnnotateState.pending]; a failed one stays there with Retry and Discard. A stroke is never
 * dropped without the user saying so.
 *
 * Call the non-suspending functions from one thread (the main thread); [scope] runs the commits.
 */
class AnnotateController(
    private val edit: suspend (suspend EngineApi.(DocHandle) -> EditState) -> EditState,
    private val scope: CoroutineScope,
    private val groupWindowMs: Long = GROUP_WINDOW_MS,
    private val settleMs: Long = SETTLE_MS,
) {
    private val _state = MutableStateFlow(AnnotateState())
    val state: StateFlow<AnnotateState> = _state.asStateFlow()

    /** One engine call at a time, in the order they were asked for (the mutex is fair). */
    private val lock = Mutex()
    private var nextId = 1L
    private var open: StrokeGroup? = null
    private var openId: Long? = null
    private var timer: Job? = null

    // ---- choices ----

    fun selectTool(tool: AnnotateTool) {
        if (tool == _state.value.tool) return
        closeGroup()
        _state.update { it.copy(tool = tool, noteDraft = null) }
    }

    fun selectSwatch(swatch: Swatch) {
        closeGroup()
        _state.update {
            when (it.tool) {
                AnnotateTool.PEN -> it.copy(penSwatch = swatch)
                AnnotateTool.HIGHLIGHTER -> it.copy(highlightSwatch = swatch)
                AnnotateTool.NOTE -> it.copy(noteSwatch = swatch)
            }
        }
    }

    fun selectWidth(width: LineWidth) {
        if (width == _state.value.width) return
        closeGroup()
        _state.update { it.copy(width = width) }
    }

    fun dismissProblem() = _state.update { it.copy(problem = null) }

    // ---- ink ----

    /** A stroke began: the open group must not be sent while it is drawn. */
    fun strokeStarted() {
        timer?.cancel()
    }

    /** The stroke in progress was abandoned (a second finger came down: that is a pinch). */
    fun strokeCancelled() {
        if (openId != null) restartTimer()
    }

    /**
     * A finished stroke on [page], [raw] points in view space (already clamped to the page), drawn at
     * [scale] px per point. Joins the open group when it can, else starts a new one.
     */
    fun addStroke(page: Int, raw: List<Pt>, scale: Float, pageWidth: Float, pageHeight: Float) {
        val pieces = prepareStroke(raw, StrokeSimplifier.tolerancePt(scale), pageWidth, pageHeight)
        if (pieces.isEmpty()) {
            strokeCancelled()
            return
        }
        val style = _state.value.inkStyle
        for (piece in pieces) {
            val g = open
            val id = openId
            if (g != null && id != null && g.canTake(page, style, piece.size)) {
                val next = g.plus(piece)
                open = next
                replace(id) { it.copy(markup = next.toMarkup()) }
            } else {
                closeGroup()
                val group = StrokeGroup(page, style, listOf(piece))
                val newId = nextId++
                open = group
                openId = newId
                _state.update { it.copy(pending = it.pending + PendingMark(newId, group.toMarkup(), MarkStatus.OPEN)) }
            }
        }
        restartTimer()
    }

    private fun restartTimer() {
        timer?.cancel()
        timer = scope.launch {
            delay(groupWindowMs)
            closeGroup()
        }
    }

    /** Sends the open group, if any, in the background. */
    private fun closeGroup() {
        timer?.cancel()
        timer = null
        val id = openId ?: return
        open = null
        openId = null
        markSaving(id)
        // Undispatched: the commit queues on the (fair) lock now, so calls keep their order.
        scope.launch(start = CoroutineStart.UNDISPATCHED) { commit(id) }
    }

    // ---- highlight and note ----

    /** Highlights a text selection on [page]: one rect per line. False when nothing was added. */
    suspend fun highlight(page: Int, rects: List<Rect>, pageWidth: Float, pageHeight: Float): Boolean {
        val clean = highlightRects(rects, pageWidth, pageHeight)
        if (clean.isEmpty()) return false
        closeGroup()
        return commit(add(highlightMarkup(page, clean, _state.value.highlightSwatch)))
    }

    /** Opens the note text sheet for a note at [at] on [page]. */
    fun placeNote(page: Int, at: Pt, pageWidth: Float, pageHeight: Float) {
        if (!(at.x.isFinite() && at.y.isFinite() && pageWidth > 0f && pageHeight > 0f)) return
        closeGroup()
        _state.update { it.copy(noteDraft = NoteDraft(page, noteRect(at, pageWidth, pageHeight))) }
    }

    fun cancelNote() = _state.update { it.copy(noteDraft = null) }

    /** Adds the drafted note with [text]. Blank text adds nothing. */
    suspend fun saveNote(text: String): Boolean {
        val draft = _state.value.noteDraft ?: return false
        if (text.isBlank()) return false
        _state.update { it.copy(noteDraft = null) }
        return commit(add(noteMarkup(draft.page, draft.rect, text, _state.value.noteSwatch)))
    }

    // ---- failures ----

    suspend fun retry(id: Long): Boolean {
        val mark = find(id) ?: return false
        if (mark.status != MarkStatus.FAILED) return false
        markSaving(id)
        return commit(id)
    }

    /** Retries every failed mark, in order. True when none is left failed. */
    suspend fun retryAll(): Boolean {
        for (m in _state.value.failed) retry(m.id)
        return _state.value.failed.isEmpty()
    }

    /** Drops every failed mark: the user said so. */
    fun discardFailed() = _state.update { s ->
        s.copy(pending = s.pending.filter { it.status != MarkStatus.FAILED }, problem = null)
    }

    // ---- undo and leaving ----

    /**
     * Undoes the last mark: an open ink group is simply dropped (it never reached the document),
     * anything else is the document's undo.
     */
    suspend fun undo(): Boolean {
        timer?.cancel()
        val id = openId
        if (id != null) {
            open = null
            openId = null
            _state.update { s -> s.copy(pending = s.pending.filter { it.id != id }) }
            return true
        }
        return lock.withLock {
            try {
                edit { undo(it) }
                // A settling mark may be the one just undone: stop drawing it.
                _state.update { s -> s.copy(pending = s.pending.filter { it.status != MarkStatus.SETTLING }) }
                true
            } catch (c: CancellationException) {
                throw c
            } catch (e: Exception) {
                _state.update { it.copy(problem = AnnotateProblem.UNDO_FAILED) }
                false
            }
        }
    }

    /**
     * Sends the open group now and waits for every queued call. True when nothing is left failed:
     * then annotating may end.
     */
    suspend fun flush(): Boolean {
        timer?.cancel()
        timer = null
        val id = openId
        if (id != null) {
            open = null
            openId = null
            markSaving(id)
            commit(id)
        }
        lock.withLock { }
        return _state.value.failed.isEmpty()
    }

    // ---- plumbing ----

    private fun add(markup: NewMarkup): Long {
        val id = nextId++
        _state.update { it.copy(pending = it.pending + PendingMark(id, markup, MarkStatus.SAVING)) }
        return id
    }

    private fun find(id: Long): PendingMark? = _state.value.pending.firstOrNull { it.id == id }

    private fun replace(id: Long, change: (PendingMark) -> PendingMark) = _state.update { s ->
        s.copy(pending = s.pending.map { if (it.id == id) change(it) else it })
    }

    private fun markSaving(id: Long) = replace(id) { it.copy(status = MarkStatus.SAVING) }

    /** Sends mark [id]. True when it is in the document. */
    private suspend fun commit(id: Long): Boolean = lock.withLock {
        val mark = find(id) ?: return@withLock false // discarded meanwhile
        if (mark.status != MarkStatus.SAVING) return@withLock mark.status == MarkStatus.SETTLING
        try {
            edit { addMarkup(it, mark.markup) }
        } catch (c: CancellationException) {
            // Kept, visible and retryable: never lost silently.
            replace(id) { it.copy(status = MarkStatus.FAILED) }
            throw c
        } catch (e: Exception) {
            replace(id) { it.copy(status = MarkStatus.FAILED) }
            _state.update { it.copy(problem = AnnotateProblem.NOT_ADDED) }
            return@withLock false
        }
        if (settleMs <= 0L) {
            _state.update { s -> s.copy(pending = s.pending.filter { it.id != id }) }
        } else {
            replace(id) { it.copy(status = MarkStatus.SETTLING) }
            scope.launch {
                delay(settleMs)
                _state.update { s -> s.copy(pending = s.pending.filter { it.id != id || it.status != MarkStatus.SETTLING }) }
            }
        }
        if (_state.value.failed.isEmpty()) _state.update { it.copy(problem = null) }
        true
    }

    companion object {
        /** Strokes closer in time than this go into one markup. */
        const val GROUP_WINDOW_MS: Long = 1_000L

        /** How long a mark the engine has is still drawn by the app, while the pages render again. */
        const val SETTLE_MS: Long = 700L
    }
}
