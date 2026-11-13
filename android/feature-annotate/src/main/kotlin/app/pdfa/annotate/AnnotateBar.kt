package app.pdfa.annotate

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Done
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import app.pdfa.design.PdfaBottomSheet
import app.pdfa.design.PdfaSpacing
import app.pdfa.feature.annotate.R

/** 0xRRGGBB as an opaque Compose colour. */
internal fun rgbColor(rgb: Int, alpha: Float = 1f): Color = Color(0xFF000000.toInt() or (rgb and 0xFFFFFF)).copy(alpha = alpha)

@Composable
private fun swatchName(s: Swatch): String = stringResource(
    when (s.name) {
        "Black" -> R.string.annotate_colour_black
        "Teal" -> R.string.annotate_colour_teal
        "Red" -> R.string.annotate_colour_red
        "Blue" -> R.string.annotate_colour_blue
        else -> R.string.annotate_colour_yellow
    },
)

@Composable
private fun widthName(w: LineWidth): String = stringResource(
    when (w) {
        LineWidth.THIN -> R.string.annotate_width_thin
        LineWidth.MEDIUM -> R.string.annotate_width_medium
        LineWidth.THICK -> R.string.annotate_width_thick
    },
)

/**
 * The contextual bar shown instead of the bottom tool tray while annotating: the tools, the colours
 * and width of the current tool, Undo and Done. Every control is labelled and at least 48 dp; the
 * rows wrap, so nothing leaves the screen at large font sizes.
 *
 * [canHighlight]: a text selection exists, so the Highlight action is offered.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun AnnotateBar(
    state: AnnotateState,
    canHighlight: Boolean,
    onTool: (AnnotateTool) -> Unit,
    onSwatch: (Swatch) -> Unit,
    onWidth: (LineWidth) -> Unit,
    onHighlight: () -> Unit,
    onUndo: () -> Unit,
    onDone: () -> Unit,
    onRetry: () -> Unit,
    onDiscard: () -> Unit,
    onDismissProblem: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val title = stringResource(R.string.annotate_bar)
    Surface(
        modifier = modifier.fillMaxWidth().semantics { paneTitle = title },
        color = MaterialTheme.colorScheme.surface,
        contentColor = MaterialTheme.colorScheme.onSurface,
        tonalElevation = 3.dp,
    ) {
        Column(Modifier.padding(horizontal = PdfaSpacing.S2, vertical = PdfaSpacing.S1)) {
            when (state.problem) {
                AnnotateProblem.NOT_ADDED -> ProblemRow(
                    text = stringResource(R.string.annotate_not_added),
                    actions = {
                        TextButton(onClick = onRetry) { Text(stringResource(R.string.annotate_retry)) }
                        TextButton(onClick = onDiscard) { Text(stringResource(R.string.annotate_discard)) }
                    },
                )
                AnnotateProblem.UNDO_FAILED -> ProblemRow(
                    text = stringResource(R.string.annotate_undo_failed),
                    actions = { TextButton(onClick = onDismissProblem) { Text(stringResource(R.string.annotate_dismiss)) } },
                )
                null -> if (state.failed.isNotEmpty()) {
                    ProblemRow(
                        text = stringResource(R.string.annotate_not_added),
                        actions = {
                            TextButton(onClick = onRetry) { Text(stringResource(R.string.annotate_retry)) }
                            TextButton(onClick = onDiscard) { Text(stringResource(R.string.annotate_discard)) }
                        },
                    )
                }
            }
            FlowRow(
                modifier = Modifier.fillMaxWidth().selectableGroup(),
                horizontalArrangement = Arrangement.spacedBy(PdfaSpacing.S1),
                verticalArrangement = Arrangement.Center,
            ) {
                ToolButton(stringResource(R.string.annotate_pen), Icons.Filled.Edit, state.tool == AnnotateTool.PEN) { onTool(AnnotateTool.PEN) }
                ToolButton(stringResource(R.string.annotate_highlighter), HighlighterIcon, state.tool == AnnotateTool.HIGHLIGHTER) { onTool(AnnotateTool.HIGHLIGHTER) }
                ToolButton(stringResource(R.string.annotate_note), NoteIcon, state.tool == AnnotateTool.NOTE) { onTool(AnnotateTool.NOTE) }
                ActionButton(stringResource(R.string.annotate_undo), UndoIcon, onUndo)
                ActionButton(stringResource(R.string.annotate_done), Icons.Filled.Done, onDone)
            }
            FlowRow(
                modifier = Modifier.fillMaxWidth().selectableGroup(),
                horizontalArrangement = Arrangement.spacedBy(PdfaSpacing.S1),
                verticalArrangement = Arrangement.Center,
            ) {
                val current = state.swatch
                for (s in AnnotatePalette.forTool(state.tool)) {
                    SwatchButton(s, selected = s == current, label = stringResource(R.string.annotate_colour, swatchName(s))) { onSwatch(s) }
                }
                if (state.tool == AnnotateTool.PEN) {
                    val next = LineWidth.entries[(state.width.ordinal + 1) % LineWidth.entries.size]
                    OutlinedButton(
                        onClick = { onWidth(next) },
                        modifier = Modifier.defaultMinSize(minHeight = PdfaSpacing.MinTouchTarget).align(Alignment.CenterVertically),
                    ) {
                        Text(stringResource(R.string.annotate_width, widthName(state.width)))
                    }
                }
                if (state.tool == AnnotateTool.HIGHLIGHTER) {
                    Button(
                        onClick = onHighlight,
                        enabled = canHighlight,
                        modifier = Modifier.defaultMinSize(minHeight = PdfaSpacing.MinTouchTarget).align(Alignment.CenterVertically),
                    ) {
                        Text(stringResource(R.string.annotate_highlight_action))
                    }
                }
            }
            val hint = when {
                state.busy -> R.string.annotate_saving
                state.tool == AnnotateTool.PEN -> R.string.annotate_hint_pen
                state.tool == AnnotateTool.HIGHLIGHTER -> R.string.annotate_hint_highlighter
                else -> R.string.annotate_hint_note
            }
            Text(
                stringResource(hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = PdfaSpacing.S2, vertical = PdfaSpacing.S1).semantics { liveRegion = LiveRegionMode.Polite },
            )
        }
    }
}

@Composable
private fun ProblemRow(text: String, actions: @Composable () -> Unit) {
    Surface(
        color = MaterialTheme.colorScheme.errorContainer,
        contentColor = MaterialTheme.colorScheme.onErrorContainer,
        shape = MaterialTheme.shapes.small,
        modifier = Modifier.fillMaxWidth().padding(vertical = PdfaSpacing.S1).semantics { liveRegion = LiveRegionMode.Assertive },
    ) {
        Column(Modifier.padding(start = PdfaSpacing.S3, top = PdfaSpacing.S2)) {
            Text(text, style = MaterialTheme.typography.bodyMedium)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) { actions() }
        }
    }
}

@Composable
private fun ToolButton(label: String, icon: ImageVector, selected: Boolean, onClick: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    val tint = if (selected) colors.onSecondaryContainer else colors.onSurfaceVariant
    Column(
        modifier = Modifier
            .defaultMinSize(minWidth = PdfaSpacing.MinTouchTarget, minHeight = PdfaSpacing.MinTouchTarget)
            .background(if (selected) colors.secondaryContainer else Color.Transparent, MaterialTheme.shapes.small)
            .selectable(selected = selected, onClick = onClick, role = Role.RadioButton)
            .padding(horizontal = PdfaSpacing.S2, vertical = PdfaSpacing.S1),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(icon, contentDescription = null, tint = tint)
        Text(label, style = MaterialTheme.typography.labelMedium, color = tint)
    }
}

@Composable
private fun ActionButton(label: String, icon: ImageVector, onClick: () -> Unit) {
    TextButton(onClick = onClick, modifier = Modifier.defaultMinSize(minWidth = PdfaSpacing.MinTouchTarget, minHeight = PdfaSpacing.MinTouchTarget)) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(icon, contentDescription = null)
            Text(label, style = MaterialTheme.typography.labelMedium)
        }
    }
}

@Composable
private fun SwatchButton(s: Swatch, selected: Boolean, label: String, onClick: () -> Unit) {
    val ring = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline
    // A see-through tint is shown as it looks on the white page.
    val fill = rgbColor(s.rgb, s.opacity).compositeOver(Color.White)
    Box(
        modifier = Modifier
            .size(PdfaSpacing.MinTouchTarget)
            .selectable(selected = selected, onClick = onClick, role = Role.RadioButton)
            .semantics { contentDescription = label },
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier
                .size(if (selected) 34.dp else 28.dp)
                .border(if (selected) 3.dp else 1.dp, ring, CircleShape)
                .padding(if (selected) 5.dp else 2.dp)
                .background(fill, CircleShape),
        )
    }
}

/** The text sheet of a new note. The text never leaves this sheet except into the document. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NoteSheet(onSave: (String) -> Unit, onCancel: () -> Unit) {
    var text by rememberSaveable { mutableStateOf("") }
    PdfaBottomSheet(onDismissRequest = onCancel) {
        Text(stringResource(R.string.annotate_note_title), style = MaterialTheme.typography.titleLarge)
        Spacer(Modifier.height(PdfaSpacing.S3))
        OutlinedTextField(
            value = text,
            onValueChange = { text = clampNoteText(it) },
            label = { Text(stringResource(R.string.annotate_note_label)) },
            minLines = 3,
            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(PdfaSpacing.S3))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onCancel, modifier = Modifier.defaultMinSize(minHeight = PdfaSpacing.MinTouchTarget)) {
                Text(stringResource(R.string.annotate_note_cancel))
            }
            Spacer(Modifier.width(PdfaSpacing.S2))
            Button(
                onClick = { onSave(text) },
                enabled = text.isNotBlank(),
                modifier = Modifier.defaultMinSize(minHeight = PdfaSpacing.MinTouchTarget),
            ) {
                Text(stringResource(R.string.annotate_note_save))
            }
        }
        Spacer(Modifier.height(PdfaSpacing.S4))
    }
}
