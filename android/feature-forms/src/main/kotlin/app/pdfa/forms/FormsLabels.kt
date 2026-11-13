package app.pdfa.forms

import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import app.pdfa.engine.FieldInfo
import app.pdfa.engine.FieldKind
import app.pdfa.feature.forms.R

@StringRes
internal fun FieldKind.labelRes(): Int = when (this) {
    FieldKind.TEXT -> R.string.forms_kind_text
    FieldKind.CHECK_BOX -> R.string.forms_kind_check
    FieldKind.RADIO -> R.string.forms_kind_radio
    FieldKind.PUSH_BUTTON -> R.string.forms_kind_button
    FieldKind.COMBO -> R.string.forms_kind_combo
    FieldKind.LIST -> R.string.forms_kind_list
    FieldKind.SIGNATURE -> R.string.forms_kind_signature
}

/** The sentence for a problem. The app shows it in a snackbar or under the editor. */
@StringRes
fun FormProblem.messageRes(): Int = when (this) {
    FormProblem.READ_ONLY -> R.string.forms_problem_read_only
    FormProblem.NOT_FILLABLE -> R.string.forms_problem_not_fillable
    FormProblem.NO_SUCH_FIELD -> R.string.forms_problem_no_such_field
    FormProblem.WRONG_KIND -> R.string.forms_problem_wrong_kind
    FormProblem.INVALID_VALUE -> R.string.forms_problem_invalid_value
    FormProblem.NOT_PERMITTED -> R.string.forms_problem_not_permitted
    FormProblem.ENGINE_RESTARTED -> R.string.forms_problem_engine_restarted
    FormProblem.ACTION_FAILED -> R.string.forms_problem_action_failed
}

@Composable
fun FieldIssue.message(): String = when (this) {
    is FieldIssue.TooLong -> pluralStringResource(R.plurals.forms_issue_too_long, maxLen, maxLen)
    FieldIssue.MultipleValues -> stringResource(R.string.forms_issue_multiple)
    FieldIssue.UnknownOption -> stringResource(R.string.forms_issue_unknown_option)
}

/** The label of an option or radio state: its display text, else the export value. */
internal fun FieldInfo.displayOf(export: String): String = options.firstOrNull { it.export == export }?.display?.ifBlank { null } ?: export

/** What the field holds, in words. Empty text for an empty field; callers add their own "empty". */
@Composable
internal fun FieldInfo.valueSummary(): String = when (kind) {
    FieldKind.TEXT -> value.joinToString(" ")
    FieldKind.CHECK_BOX ->
        stringResource(if (isChecked) R.string.forms_list_checked else R.string.forms_list_not_checked)
    FieldKind.RADIO -> selectedRadio?.let { displayOf(it) }.orEmpty()
    FieldKind.COMBO, FieldKind.LIST -> value.filter { it.isNotEmpty() }.joinToString(", ") { displayOf(it) }
    FieldKind.SIGNATURE, FieldKind.PUSH_BUTTON -> ""
}

/**
 * The spoken description: "Text field Name, required, empty", "Check box Subscribe, checked",
 * "Signature field, cannot be filled here". The name is the last segment of the qualified name.
 */
@Composable
internal fun FieldInfo.spokenDescription(current: Boolean = false): String {
    val kindText = stringResource(kind.labelRes())
    if (kind == FieldKind.SIGNATURE) return stringResource(R.string.forms_desc_signature, kindText)
    if (kind == FieldKind.PUSH_BUTTON) return stringResource(R.string.forms_desc_button, kindText + " " + shortName)
    val parts = mutableListOf(stringResource(R.string.forms_field_named, kindText, shortName))
    if (required) parts += stringResource(R.string.forms_desc_required)
    if (readOnly) parts += stringResource(R.string.forms_desc_read_only)
    val summary = valueSummary()
    parts += when {
        kind == FieldKind.CHECK_BOX -> summary
        isEmpty && (kind == FieldKind.RADIO || kind == FieldKind.COMBO || kind == FieldKind.LIST) ->
            stringResource(R.string.forms_desc_none_selected)
        isEmpty -> stringResource(R.string.forms_desc_empty)
        else -> summary
    }
    if (current) parts += stringResource(R.string.forms_desc_current)
    return parts.joinToString(", ")
}
