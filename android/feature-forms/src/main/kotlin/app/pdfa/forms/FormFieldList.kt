package app.pdfa.forms

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.tooling.preview.Preview
import app.pdfa.design.PdfaSpacing
import app.pdfa.design.PdfaTheme
import app.pdfa.engine.FieldInfo
import app.pdfa.engine.FieldKind
import app.pdfa.engine.FieldOption
import app.pdfa.engine.FieldWidget
import app.pdfa.engine.Rect
import app.pdfa.feature.forms.R

/**
 * Every field of the form, one row each: name, kind, a short summary of the value, and Required
 * or Read only markers (words, not only colours). Tapping a row calls [onSelect]. "Clear form"
 * asks for confirmation, then calls [onClearForm]. [fields] should be in reading order
 * (`FormsState.fields` is).
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun FormFieldList(
    fields: List<FieldInfo>,
    onSelect: (fieldName: String) -> Unit,
    onClearForm: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var confirming by rememberSaveable { mutableStateOf(false) }
    val missing = fields.count { it.isMissingRequired() }
    LazyColumn(modifier.fillMaxWidth()) {
        item {
            Column(Modifier.padding(horizontal = PdfaSpacing.GutterPhone, vertical = PdfaSpacing.S2)) {
                Text(stringResource(R.string.forms_list_title), style = MaterialTheme.typography.titleLarge)
                if (missing > 0) {
                    Text(
                        pluralStringResource(R.plurals.forms_list_missing_count, missing, missing),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
                if (fields.isEmpty()) {
                    Text(stringResource(R.string.forms_list_empty), style = MaterialTheme.typography.bodyMedium)
                } else {
                    TextButton(
                        onClick = { confirming = true },
                        modifier = Modifier.heightIn(min = PdfaSpacing.MinTouchTarget),
                    ) { Text(stringResource(R.string.forms_list_clear_form)) }
                }
            }
        }
        items(fields, key = { it.name }) { field ->
            FieldRow(field, onClick = { onSelect(field.name) })
            HorizontalDivider()
        }
    }
    if (confirming) {
        AlertDialog(
            onDismissRequest = { confirming = false },
            title = { Text(stringResource(R.string.forms_clear_title)) },
            text = { Text(stringResource(R.string.forms_clear_body)) },
            confirmButton = {
                TextButton(
                    onClick = { confirming = false; onClearForm() },
                    modifier = Modifier.heightIn(min = PdfaSpacing.MinTouchTarget),
                ) { Text(stringResource(R.string.forms_clear_confirm)) }
            },
            dismissButton = {
                TextButton(
                    onClick = { confirming = false },
                    modifier = Modifier.heightIn(min = PdfaSpacing.MinTouchTarget),
                ) { Text(stringResource(R.string.forms_clear_cancel)) }
            },
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun FieldRow(field: FieldInfo, onClick: () -> Unit) {
    val description = field.spokenDescription()
    val summary = field.valueSummary().ifEmpty { stringResource(R.string.forms_list_value_empty) }
    Column(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .heightIn(min = PdfaSpacing.MinTouchTarget)
            .padding(horizontal = PdfaSpacing.GutterPhone, vertical = PdfaSpacing.S2)
            .clearAndSetSemantics {
                contentDescription = description
                role = Role.Button
            },
        verticalArrangement = Arrangement.spacedBy(PdfaSpacing.S1),
    ) {
        Text(field.shortName, style = MaterialTheme.typography.titleMedium)
        Text(
            stringResource(field.kind.labelRes()) + pageSuffix(field),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (field.isEditable || field.readOnly) {
            Text(summary, style = MaterialTheme.typography.bodyMedium)
        }
        if (field.required || field.readOnly) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(PdfaSpacing.S3)) {
                if (field.required) Text(stringResource(R.string.forms_list_required), style = MaterialTheme.typography.labelMedium)
                if (field.readOnly) Text(stringResource(R.string.forms_list_read_only), style = MaterialTheme.typography.labelMedium)
            }
        }
    }
}

@Composable
private fun pageSuffix(field: FieldInfo): String {
    val page = field.widgets.firstOrNull()?.page ?: return ""
    return ", " + stringResource(R.string.forms_list_page, page + 1)
}

@Composable
private fun ListPreview(dark: Boolean) = PdfaTheme(darkTheme = dark) {
    androidx.compose.material3.Surface {
        val w = listOf(FieldWidget(0, Rect(0f, 0f, 10f, 10f), null))
        FormFieldList(
            fields = listOf(
                FieldInfo("name", FieldKind.TEXT, emptyList(), emptyList(), false, true, false, null, w),
                FieldInfo("subscribe", FieldKind.CHECK_BOX, listOf("Yes"), emptyList(), false, false, false, null, w),
                FieldInfo("country", FieldKind.COMBO, listOf("fr"), listOf(FieldOption("fr", "France")), false, false, false, null, w),
                FieldInfo("id", FieldKind.TEXT, listOf("A-1234"), emptyList(), true, false, false, null, w),
                FieldInfo("sig", FieldKind.SIGNATURE, emptyList(), emptyList(), false, false, false, null, w),
            ),
            onSelect = {},
            onClearForm = {},
        )
    }
}

@Preview(name = "List light", showBackground = true)
@Composable
private fun ListLightPreview() = ListPreview(false)

@Preview(name = "List dark, large font", showBackground = true, backgroundColor = 0xFF101418, fontScale = 2f)
@Composable
private fun ListDarkPreview() = ListPreview(true)
