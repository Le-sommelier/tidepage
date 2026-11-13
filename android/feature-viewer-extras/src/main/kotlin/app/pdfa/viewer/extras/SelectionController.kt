package app.pdfa.viewer.extras

import app.pdfa.engine.DocHandle
import app.pdfa.engine.EngineApi
import app.pdfa.engine.EngineError
import app.pdfa.engine.Pt
import app.pdfa.engine.Rect
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** The copy target. The app passes an Android-backed implementation; tests pass a recorder. */
fun interface TextClipboard {
    /** Returns false when the text could not be placed on the clipboard. */
    fun setText(text: String): Boolean
}

/**
 * The current selection. [copyForbidden] is set when the document does not allow copying; the
 * UI then shows [message] and does not offer Copy. [failed] is set for any other engine error.
 */
data class SelectionState(
    val page: Int? = null,
    val text: String = "",
    val rects: List<Rect> = emptyList(),
    val copyForbidden: Boolean = false,
    val failed: Boolean = false,
    /** True when the selected text was longer than [SelectionController.TEXT_CAP] and was cut. */
    val truncated: Boolean = false,
    /** True after a Copy that the clipboard refused. Cleared by the next successful copy or selection. */
    val copyFailed: Boolean = false,
) {
    val hasText: Boolean get() = text.isNotEmpty()

    /** User-facing text for the forbidden or failed states, else null. */
    val message: String?
        get() = when {
            copyForbidden -> SelectionController.FORBIDDEN_MESSAGE
            failed -> SelectionController.FAILED_MESSAGE
            else -> null
        }
}

/** When a selection is dropped on its own. */
data class SelectionPolicy(
    val clearOnPageChange: Boolean = true,
    val clearOnZoom: Boolean = true,
)

/**
 * Word and range text selection. A long press on a point selects the word under it
 * (`selectText(page, p, p)`); dragging a handle selects the range (`selectText(page, from, to)`).
 * Text is never logged.
 */
class SelectionController(
    private val engine: EngineApi,
    handle: DocHandle,
    private val dispatcher: CoroutineDispatcher,
    private val scope: CoroutineScope,
    private val clipboard: TextClipboard,
    private val policy: SelectionPolicy = SelectionPolicy(),
) {
    companion object {
        const val FORBIDDEN_MESSAGE: String = "This file does not allow copying text."
        const val FAILED_MESSAGE: String = "Text could not be selected."

        /** Longest selection kept, in characters. Also the most text handed to the clipboard. */
        const val TEXT_CAP: Int = 100_000
    }

    private var handle: DocHandle = handle
    private val _state = MutableStateFlow(SelectionState())
    val state: StateFlow<SelectionState> = _state.asStateFlow()

    private var job: Job? = null

    /** Long press: selects the word at [point]. */
    fun longPress(page: Int, point: Pt) = select(page, point, point)

    /** Drag handles: selects the text between [from] and [to]. */
    fun dragSelection(page: Int, from: Pt, to: Pt) = select(page, from, to)

    /** Copies the selected text. Returns false when there is nothing to copy or copying is forbidden. */
    fun copyText(): Boolean {
        val s = _state.value
        if (!s.hasText || s.copyForbidden) return false
        val ok = clipboard.setText(s.text)
        _state.value = s.copy(copyFailed = !ok)
        return ok
    }

    /** Drops the selection on a page change when the policy says so. */
    fun onPageChanged(page: Int) {
        if (policy.clearOnPageChange && _state.value.page != null && _state.value.page != page) clear()
    }

    /** Drops the selection on zoom when the policy says so. */
    fun onZoomChanged() {
        if (policy.clearOnZoom) clear()
    }

    /** Drops the selection and cancels a selection still in flight. */
    fun clear() {
        job?.cancel()
        job = null
        _state.value = SelectionState()
    }

    /** The engine restarted: adopts the new handle and drops the selection. */
    fun onEngineRestarted(newHandle: DocHandle) {
        handle = newHandle
        clear()
    }

    private fun select(page: Int, from: Pt, to: Pt) {
        job?.cancel()
        job = scope.launch {
            _state.value = try {
                val sel = withContext(dispatcher) { engine.selectText(handle, page, from, to) }
                if (sel == null) {
                    SelectionState(page = page)
                } else {
                    val cut = sel.text.length > TEXT_CAP
                    SelectionState(
                        page = page,
                        text = if (cut) sel.text.take(TEXT_CAP) else sel.text,
                        rects = sel.rects,
                        truncated = cut,
                    )
                }
            } catch (e: EngineError.NotPermitted) {
                SelectionState(page = page, copyForbidden = true)
            } catch (e: EngineError) {
                SelectionState(page = page, failed = true)
            }
        }
    }
}
