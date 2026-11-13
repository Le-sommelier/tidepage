package app.pdfa.forms

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import app.pdfa.design.PdfaSpacing
import app.pdfa.design.PdfaTheme
import app.pdfa.engine.FieldInfo
import app.pdfa.engine.FieldKind
import app.pdfa.engine.FieldOption
import app.pdfa.engine.FieldWidget
import app.pdfa.engine.Rect
import app.pdfa.feature.forms.R

/**
 * Content for a bottom bar or sheet (put it in `PdfaBottomSheet` or a surface) that edits [field].
 *
 * Text is typed into a local draft and sent with [onText] only when the user moves on (Next,
 * Previous, Done, the keyboard's Done key) or leaves the box, so one edit is one undo step. Check
 * boxes, radio buttons and choices send their change at once. [validation] is the message for the
 * last refused value. Read-only, signature and button fields show a calm explanation instead of
 * an input. Nothing here has a fixed height, so it grows with the font size.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun FormFieldEditor(
    field: FieldInfo,
    validation: FieldIssue?,
    onText: (String) -> Unit,
    onChecked: (Boolean) -> Unit,
    onRadio: (String?) -> Unit,
    onChoice: (List<String>) -> Unit,
    onClear: () -> Unit,
    onNext: () -> Unit,
    onPrevious: () -> Unit,
    onDone: () -> Unit,
    modifier: Modifier = Modifier,
    hasNext: Boolean = true,
    hasPrevious: Boolean = true,
) {
    // The draft restarts when the field or its stored value changes (another field, an undo).
    val stored = field.value.joinToString("\n")
    var draft by remember(field.name, stored) { mutableStateOf(stored) }
    val isText = field.kind == FieldKind.TEXT && field.isEditable
    fun commit() {
        if (isText && draft != stored) onText(draft)
    }

    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(PdfaSpacing.S2)) {
        Text(
            text = field.shortName + if (field.required) " *" else "",
            style = MaterialTheme.typography.titleMedium,
        )
        if (field.required) {
            Text(stringResource(R.string.forms_editor_required_note), style = MaterialTheme.typography.bodySmall)
        }
        when {
            field.kind == FieldKind.SIGNATURE -> Locked(R.string.forms_editor_locked_signature)
            field.kind == FieldKind.PUSH_BUTTON -> Locked(R.string.forms_editor_locked_button)
            field.readOnly -> {
                Locked(R.string.forms_editor_locked_read_only)
                val v = field.valueSummary()
                if (v.isNotEmpty()) Text(v, style = MaterialTheme.typography.bodyLarge)
            }
            field.kind == FieldKind.TEXT -> TextInput(field, draft, validation, { draft = it }, ::commit)
            field.kind == FieldKind.CHECK_BOX -> CheckRow(field, onChecked)
            field.kind == FieldKind.RADIO -> RadioOptions(field, validation, onRadio)
            else -> ChoiceOptions(field, validation, onChoice)
        }
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(PdfaSpacing.S2),
            verticalArrangement = Arrangement.spacedBy(PdfaSpacing.S1),
            modifier = Modifier.fillMaxWidth(),
        ) {
            OutlinedButton(
                onClick = { commit(); onPrevious() },
                enabled = hasPrevious,
                modifier = Modifier.heightIn(min = PdfaSpacing.MinTouchTarget),
            ) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = null)
                Text(stringResource(R.string.forms_editor_previous), Modifier.padding(start = PdfaSpacing.S1))
            }
            OutlinedButton(
                onClick = { commit(); onNext() },
                enabled = hasNext,
                modifier = Modifier.heightIn(min = PdfaSpacing.MinTouchTarget),
            ) {
                Text(stringResource(R.string.forms_editor_next), Modifier.padding(end = PdfaSpacing.S1))
                Icon(Icons.AutoMirrored.Filled.ArrowForward, contentDescription = null)
            }
            if (field.isEditable) {
                TextButton(
                    onClick = { onClear(); draft = "" },
                    modifier = Modifier.heightIn(min = PdfaSpacing.MinTouchTarget),
                ) { Text(stringResource(R.string.forms_editor_clear)) }
            }
            Button(
                onClick = { commit(); onDone() },
                modifier = Modifier.heightIn(min = PdfaSpacing.MinTouchTarget),
            ) { Text(stringResource(R.string.forms_editor_done)) }
        }
    }
}

@Composable
private fun Locked(res: Int) {
    Text(
        text = stringResource(res),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun TextInput(
    field: FieldInfo,
    draft: String,
    validation: FieldIssue?,
    onDraft: (String) -> Unit,
    commit: () -> Unit,
) {
    var hadFocus by remember { mutableStateOf(false) }
    val max = field.maxLen
    OutlinedTextField(
        value = draft,
        onValueChange = { raw ->
            // A single-line field takes no line breaks; the controller enforces it too.
            var v = if (field.multiline) raw else raw.replace(Regex("\r\n|\r|\n"), " ")
            if (max != null && v.length > max) v = v.take(max)
            onDraft(v)
        },
        label = { Text(field.shortName) },
        singleLine = !field.multiline,
        minLines = if (field.multiline) 3 else 1,
        isError = validation != null,
        supportingText = {
            Column {
                validation?.let { Text(it.message()) }
                if (max != null) Text(stringResource(R.string.forms_editor_counter, draft.length, max))
            }
        },
        keyboardOptions = KeyboardOptions(imeAction = if (field.multiline) ImeAction.Default else ImeAction.Done),
        keyboardActions = KeyboardActions(onDone = { commit() }),
        modifier = Modifier
            .fillMaxWidth()
            .onFocusChanged {
                if (hadFocus && !it.isFocused) commit()
                hadFocus = it.isFocused
            },
    )
}

@Composable
private fun CheckRow(field: FieldInfo, onChecked: (Boolean) -> Unit) {
    val checked = field.isChecked
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = PdfaSpacing.MinTouchTarget)
            .toggleable(value = checked, role = Role.Switch, onValueChange = onChecked),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(PdfaSpacing.S3),
    ) {
        Text(stringResource(R.string.forms_editor_checkbox, field.shortName), Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = null)
    }
}

@Composable
private fun RadioOptions(field: FieldInfo, validation: FieldIssue?, onRadio: (String?) -> Unit) {
    val selected = field.selectedRadio
    Text(stringResource(R.string.forms_editor_choose_one), style = MaterialTheme.typography.labelLarge)
    field.radioStates.forEach { state ->
        OptionRow(label = field.displayOf(state), selected = state == selected, role = Role.RadioButton) {
            onRadio(if (state == selected) selected else state)
        }
    }
    validation?.let { Text(it.message(), color = MaterialTheme.colorScheme.error) }
}

@Composable
private fun ChoiceOptions(field: FieldInfo, validation: FieldIssue?, onChoice: (List<String>) -> Unit) {
    val multi = field.kind == FieldKind.LIST
    val current = field.value.filter { it.isNotEmpty() }
    Text(
        stringResource(if (multi) R.string.forms_editor_choose_any else R.string.forms_editor_choose_one),
        style = MaterialTheme.typography.labelLarge,
    )
    if (field.options.isEmpty()) {
        Locked(R.string.forms_editor_no_options)
    }
    field.options.forEach { option ->
        val on = option.export in current
        OptionRow(
            label = option.display.ifBlank { option.export },
            selected = on,
            role = if (multi) Role.Checkbox else Role.RadioButton,
            multi = multi,
        ) {
            onChoice(
                when {
                    !multi -> listOf(option.export)
                    on -> current - option.export
                    else -> current + option.export
                },
            )
        }
    }
    validation?.let { Text(it.message(), color = MaterialTheme.colorScheme.error) }
}

@Composable
private fun OptionRow(label: String, selected: Boolean, role: Role, multi: Boolean = false, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = PdfaSpacing.MinTouchTarget)
            .selectable(selected = selected, role = role, onClick = onClick),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (multi) Checkbox(checked = selected, onCheckedChange = null) else RadioButton(selected = selected, onClick = null)
        Text(label, Modifier.padding(start = PdfaSpacing.S3).widthIn(min = 0.dp))
    }
}

// ---- previews ----

private fun preview(kind: FieldKind, name: String, value: List<String> = emptyList(), options: List<FieldOption> = emptyList(), readOnly: Boolean = false, required: Boolean = false, multiline: Boolean = false, maxLen: Int? = null, states: List<String> = emptyList()) =
    FieldInfo(name, kind, value, options, readOnly, required, multiline, maxLen, if (states.isEmpty()) listOf(FieldWidget(0, Rect(0f, 0f, 10f, 10f), null)) else states.map { FieldWidget(0, Rect(0f, 0f, 10f, 10f), it) })

@Composable
private fun EditorPreview(field: FieldInfo, dark: Boolean, issue: FieldIssue? = null) = PdfaTheme(darkTheme = dark) {
    androidx.compose.material3.Surface {
        FormFieldEditor(field, issue, {}, {}, {}, {}, {}, {}, {}, {}, Modifier.padding(PdfaSpacing.GutterPhone))
    }
}

@Preview(name = "Text light", showBackground = true)
@Composable
private fun TextLightPreview() = EditorPreview(preview(FieldKind.TEXT, "name", listOf("Ada"), required = true, maxLen = 20), false)

@Preview(name = "Text dark, error", showBackground = true, backgroundColor = 0xFF101418)
@Composable
private fun TextDarkPreview() = EditorPreview(preview(FieldKind.TEXT, "name", listOf("Ada"), maxLen = 20), true, FieldIssue.TooLong(20))

@Preview(name = "Check light", showBackground = true)
@Composable
private fun CheckLightPreview() = EditorPreview(preview(FieldKind.CHECK_BOX, "subscribe", listOf("Yes"), states = listOf("Yes")), false)

@Preview(name = "Radio dark", showBackground = true, backgroundColor = 0xFF101418)
@Composable
private fun RadioDarkPreview() = EditorPreview(preview(FieldKind.RADIO, "plan", listOf("pro"), states = listOf("basic", "pro")), true)

@Preview(name = "List light", showBackground = true)
@Composable
private fun ListLightPreview() = EditorPreview(
    preview(FieldKind.LIST, "tags", listOf("a"), options = listOf(FieldOption("a", "Alpha"), FieldOption("b", "Beta"))),
    false,
)

@Preview(name = "Signature dark", showBackground = true, backgroundColor = 0xFF101418, fontScale = 2f)
@Composable
private fun SignatureDarkPreview() = EditorPreview(preview(FieldKind.SIGNATURE, "sig"), true)

@Preview(name = "Read only light", showBackground = true)
@Composable
private fun ReadOnlyLightPreview() = EditorPreview(preview(FieldKind.TEXT, "id", listOf("A-1234"), readOnly = true), false)
