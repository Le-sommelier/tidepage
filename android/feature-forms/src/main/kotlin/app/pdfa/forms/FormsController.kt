package app.pdfa.forms

import app.pdfa.engine.DocHandle
import app.pdfa.engine.EditState
import app.pdfa.engine.EngineApi
import app.pdfa.engine.EngineError
import app.pdfa.engine.FieldInfo
import app.pdfa.engine.FieldKind
import app.pdfa.engine.FieldValue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** Why the last action did not happen. A fixed value: the UI turns it into a sentence. */
enum class FormProblem {
    /** The field is locked by the document. */
    READ_ONLY,

    /** Signature and button fields cannot be filled in this app. */
    NOT_FILLABLE,

    /** The field is not in the form any more (for example after an undo). */
    NO_SUCH_FIELD,

    /** The value is of the wrong kind for the field (a text for a check box...). */
    WRONG_KIND,

    /** The value was refused: see [FormsState.validation] for the reason when it is a field issue. */
    INVALID_VALUE,

    /** The document does not allow changing the form. */
    NOT_PERMITTED,

    /** The engine restarted or stopped answering: the app must reopen the document. */
    ENGINE_RESTARTED,

    /** Any other engine failure. */
    ACTION_FAILED,
}

/** A reason a typed value was not accepted. Shown under the field. */
sealed interface FieldIssue {
    data class TooLong(val maxLen: Int) : FieldIssue
    data object MultipleValues : FieldIssue
    data object UnknownOption : FieldIssue
}

/** Everything the forms UI shows. */
data class FormsState(
    /** In reading order: page, then top, then left of the first widget. Fields with no widget come last. */
    val fields: List<FieldInfo> = emptyList(),
    val loaded: Boolean = false,
    /** Name of the field being filled in, or null. Tracked by name so a reload cannot move it. */
    val currentName: String? = null,
    val busy: Boolean = false,
    val problem: FormProblem? = null,
    /** Per field, why its last value was refused. Cleared when a value is accepted. */
    val validation: Map<String, FieldIssue> = emptyMap(),
    val canUndo: Boolean = false,
    val canRedo: Boolean = false,
    /** Bumped whenever the form content changed: the page must be drawn again. */
    val revision: Int = 0,
) {
    val current: FieldInfo? get() = currentName?.let { n -> fields.firstOrNull { it.name == n } }

    /** Position of [current] in [fields], or -1. */
    val currentIndex: Int get() = fields.indexOfFirst { it.name == currentName }

    /** Required fields the user can fill in and has left empty. */
    val missingRequired: List<String> get() = fields.filter { it.isMissingRequired() }.map { it.name }

    val hasNext: Boolean get() = neighbour(+1) != null
    val hasPrevious: Boolean get() = neighbour(-1) != null

    fun field(name: String): FieldInfo? = fields.firstOrNull { it.name == name }

    internal fun neighbour(step: Int): FieldInfo? {
        val from = if (currentIndex >= 0) currentIndex else if (step > 0) -1 else fields.size
        var i = from + step
        while (i in fields.indices) {
            if (fields[i].isNavigable()) return fields[i]
            i += step
        }
        return null
    }
}

// ---- field facts, shared with the UI ----

/** Can the user change this field here? Read-only, signature and button fields cannot be. */
val FieldInfo.isEditable: Boolean
    get() = !readOnly && kind != FieldKind.SIGNATURE && kind != FieldKind.PUSH_BUTTON

/** Next/previous visit only fields that can be filled and are drawn on a page. */
internal fun FieldInfo.isNavigable(): Boolean = isEditable && widgets.isNotEmpty()

/** A value other than nothing and the PDF "Off" name. */
private fun FieldInfo.hasRealValue(): Boolean = value.any { it.isNotEmpty() && it != "Off" }

val FieldInfo.isChecked: Boolean get() = kind == FieldKind.CHECK_BOX && hasRealValue()

/** The on-state of the selected radio button, or null. */
val FieldInfo.selectedRadio: String?
    get() = if (kind == FieldKind.RADIO) value.firstOrNull { it.isNotEmpty() && it != "Off" } else null

/** True when nothing has been entered or chosen. Blank text counts as empty. */
val FieldInfo.isEmpty: Boolean
    get() = when (kind) {
        FieldKind.TEXT -> value.all { it.isBlank() }
        FieldKind.SIGNATURE, FieldKind.PUSH_BUTTON -> true
        else -> !hasRealValue()
    }

internal fun FieldInfo.isMissingRequired(): Boolean = required && isEditable && isEmpty

/** The name shown and spoken: the last segment of the qualified name (`form.address.city` is `city`). */
val FieldInfo.shortName: String get() = name.substringAfterLast('.').ifEmpty { name }

/** The on-states of a radio group, in widget order, without duplicates. */
val FieldInfo.radioStates: List<String> get() = widgets.mapNotNull { it.onState }.distinct()

private val LINE_BREAKS = Regex("\r\n|\r|\n")

/**
 * Form logic: no Android types. Every change goes through [edit] (in the app, the document
 * session, which owns undo state and autosave); [engine] only reads the fields.
 *
 * Calls run one at a time (a second call waits for the first), so quick edits are never dropped.
 * Edits are suspend functions returning true when the value was written. A refusal or a failure
 * is reported through [FormsState.problem] and [FormsState.validation], never thrown, and never
 * puts engine text in the state (it can contain what the user typed).
 *
 * Call [load] again after every undo or redo made elsewhere in the app.
 */
class FormsController(
    private val engine: EngineApi,
    private val handle: DocHandle,
    private val edit: suspend (suspend EngineApi.(DocHandle) -> EditState) -> EditState,
    private val dispatcher: CoroutineDispatcher = Dispatchers.Default,
) {
    private val _state = MutableStateFlow(FormsState())
    val state: StateFlow<FormsState> = _state.asStateFlow()
    private val lock = Mutex()

    /** Reads the fields and the undo flags. Call when the form opens and after every undo or redo. */
    suspend fun load(): Boolean = serialized(resync = false) { readAll() }

    // ---- navigation (state only; nothing is written) ----

    /** Makes [fieldName] the current field. False when there is no such field. */
    fun select(fieldName: String): Boolean {
        if (_state.value.field(fieldName) == null) return false
        _state.update { it.copy(currentName = fieldName, problem = null) }
        return true
    }

    fun deselect() = _state.update { it.copy(currentName = null) }

    /** Moves to the next fillable field in reading order. False (and stays) at the end. */
    fun next(): Boolean = move(+1)

    /** Moves to the previous fillable field. False (and stays) at the start. */
    fun previous(): Boolean = move(-1)

    private fun move(step: Int): Boolean {
        val target = _state.value.neighbour(step) ?: return false
        _state.update { it.copy(currentName = target.name, problem = null) }
        return true
    }

    fun dismissProblem() = _state.update { it.copy(problem = null) }

    /** The field under a point of [page] (0-based, PDF points, origin top-left), or null. See [hitTestIn]. */
    fun hitTest(page: Int, xPt: Float, yPt: Float): String? = hitTestIn(_state.value.fields, page, xPt, yPt)

    // ---- edits ----

    /**
     * Writes a text. Too long is refused ([FieldIssue.TooLong]); line breaks are replaced by a space
     * in a single-line field and kept in a multiline one.
     */
    suspend fun setText(fieldName: String, text: String): Boolean {
        val f = fillable(fieldName, FieldKind.TEXT) ?: return false
        val clean = if (f.multiline) text else text.replace(LINE_BREAKS, " ")
        val max = f.maxLen
        if (max != null && clean.length > max) return refuse(fieldName, FieldIssue.TooLong(max))
        return write(fieldName) { setField(it, fieldName, FieldValue.Text(clean)) }
    }

    suspend fun setChecked(fieldName: String, checked: Boolean): Boolean {
        fillable(fieldName, FieldKind.CHECK_BOX) ?: return false
        return write(fieldName) { setField(it, fieldName, FieldValue.Check(checked)) }
    }

    /** Selects the radio button with [onState], or none for null. */
    suspend fun setRadio(fieldName: String, onState: String?): Boolean {
        val f = fillable(fieldName, FieldKind.RADIO) ?: return false
        if (onState != null && onState !in f.radioStates) return refuse(fieldName, FieldIssue.UnknownOption)
        return write(fieldName) { setField(it, fieldName, FieldValue.Radio(onState)) }
    }

    /**
     * Chooses options by export value. A combo box takes one at most; a list box may take several
     * (the engine does not tell us whether a list allows it, so the engine has the last word).
     */
    suspend fun setChoice(fieldName: String, values: List<String>): Boolean {
        val f = fillable(fieldName, FieldKind.COMBO, FieldKind.LIST) ?: return false
        if (f.kind == FieldKind.COMBO && values.size > 1) return refuse(fieldName, FieldIssue.MultipleValues)
        if (f.options.isNotEmpty() && values.any { v -> f.options.none { it.export == v } }) {
            return refuse(fieldName, FieldIssue.UnknownOption)
        }
        return write(fieldName) { setField(it, fieldName, FieldValue.Choice(values.distinct())) }
    }

    /** Empties one field: text to empty, check box off, radio and choices to none. */
    suspend fun clearField(fieldName: String): Boolean {
        val f = fillable(fieldName) ?: return false
        val empty = when (f.kind) {
            FieldKind.TEXT -> FieldValue.Text("")
            FieldKind.CHECK_BOX -> FieldValue.Check(false)
            FieldKind.RADIO -> FieldValue.Radio(null)
            else -> FieldValue.Choice(emptyList())
        }
        return write(fieldName) { setField(it, fieldName, empty) }
    }

    /**
     * Restores the form's default values; read-only fields are never changed. "Default" is what
     * the document says (usually empty), not always blank.
     */
    suspend fun clearForm(): Boolean {
        if (!_state.value.loaded) return false
        return write(null) { resetForm(it, null) }.also { done ->
            if (done) _state.update { it.copy(validation = emptyMap()) }
        }
    }

    /** Restores the named fields. Read-only, signature, button and unknown names are left out. */
    suspend fun clearFields(names: List<String>): Boolean {
        val s = _state.value
        val known = names.distinct().mapNotNull { s.field(it) }
        val ok = known.filter { it.isEditable }.map { it.name }
        if (ok.isEmpty()) {
            val why = when {
                known.isEmpty() -> FormProblem.NO_SUCH_FIELD
                known.any { it.readOnly } -> FormProblem.READ_ONLY
                else -> FormProblem.NOT_FILLABLE
            }
            _state.update { it.copy(problem = why) }
            return false
        }
        return write(null) { resetForm(it, ok) }.also { done ->
            if (done) _state.update { st -> st.copy(validation = st.validation - ok.toSet()) }
        }
    }

    // ---- plumbing ----

    /** The field when it can take a value of one of [kinds] (any kind when empty), else sets the refusal and null. */
    private fun fillable(name: String, vararg kinds: FieldKind): FieldInfo? {
        val f = _state.value.field(name)
        val problem = when {
            f == null -> FormProblem.NO_SUCH_FIELD
            f.readOnly -> FormProblem.READ_ONLY
            f.kind == FieldKind.SIGNATURE || f.kind == FieldKind.PUSH_BUTTON -> FormProblem.NOT_FILLABLE
            kinds.isNotEmpty() && f.kind !in kinds -> FormProblem.WRONG_KIND
            else -> return f
        }
        _state.update { it.copy(problem = problem) }
        return null
    }

    private fun refuse(name: String, issue: FieldIssue): Boolean {
        _state.update { it.copy(problem = FormProblem.INVALID_VALUE, validation = it.validation + (name to issue)) }
        return false
    }

    /** One engine edit, then a re-read of the fields. [field] is the one whose validation message to clear. */
    private suspend fun write(field: String?, block: suspend EngineApi.(DocHandle) -> EditState): Boolean =
        serialized(resync = true) {
            val e = edit(block)
            readAll(e)
            if (field != null) _state.update { it.copy(validation = it.validation - field) }
        }

    private suspend fun readAll(known: EditState? = null) {
        val list = engine.listFields(handle)
        val e = known ?: engine.state(handle)
        val ordered = inReadingOrder(list)
        val names = ordered.map { it.name }.toSet()
        _state.update {
            it.copy(
                fields = ordered,
                loaded = true,
                currentName = it.currentName?.takeIf { n -> n in names },
                validation = it.validation.filterKeys { n -> n in names },
                canUndo = e.canUndo,
                canRedo = e.canRedo,
                revision = it.revision + 1,
            )
        }
    }

    private suspend fun serialized(resync: Boolean, block: suspend () -> Unit): Boolean = lock.withLock {
        _state.update { it.copy(busy = true, problem = null) }
        try {
            withContext(dispatcher) { block() }
            true
        } catch (c: CancellationException) {
            throw c
        } catch (e: EngineError) {
            val died = e is EngineError.EngineDied || e is EngineError.Timeout
            val problem = when (e) {
                is EngineError.EngineDied, is EngineError.Timeout -> FormProblem.ENGINE_RESTARTED
                is EngineError.InvalidArgument -> FormProblem.INVALID_VALUE
                is EngineError.NotPermitted -> FormProblem.NOT_PERMITTED
                else -> FormProblem.ACTION_FAILED
            }
            _state.update { it.copy(problem = problem) }
            // The document may differ from what is shown: read it again, best effort. Not after a
            // death: every handle is invalid then.
            if (resync && !died) {
                try {
                    withContext(dispatcher) { readAll() }
                } catch (c: CancellationException) {
                    throw c
                } catch (_: EngineError) {
                    // Keep what is shown; the problem is already set.
                }
            }
            false
        } finally {
            _state.update { it.copy(busy = false) }
        }
    }

    companion object {
        /** Page, then top, then left of the first widget; no widget last; name breaks ties. */
        fun inReadingOrder(fields: List<FieldInfo>): List<FieldInfo> =
            fields.sortedWith(
                compareBy<FieldInfo>({ it.widgets.isEmpty() })
                    .thenBy { it.widgets.firstOrNull()?.page ?: 0 }
                    .thenBy { it.widgets.firstOrNull()?.rect?.top ?: 0f }
                    .thenBy { it.widgets.firstOrNull()?.rect?.left ?: 0f }
                    .thenBy { it.name },
            )

        /**
         * The field whose widget contains the point. Left and top edges are inside, right and bottom
         * are outside, so touching widgets never both match. When widgets overlap, the smaller one wins
         * (a button on top of a big field), then reading order. Read-only and signature fields match too,
         * so the UI can say why they cannot be filled.
         */
        fun hitTestIn(fields: List<FieldInfo>, page: Int, xPt: Float, yPt: Float): String? {
            var best: String? = null
            var bestArea = Float.MAX_VALUE
            for (f in fields) for (w in f.widgets) {
                val r = w.rect
                if (w.page != page || xPt < r.left || xPt >= r.right || yPt < r.top || yPt >= r.bottom) continue
                val area = (r.right - r.left) * (r.bottom - r.top)
                if (area < bestArea) {
                    best = f.name
                    bestArea = area
                }
            }
            return best
        }
    }
}
