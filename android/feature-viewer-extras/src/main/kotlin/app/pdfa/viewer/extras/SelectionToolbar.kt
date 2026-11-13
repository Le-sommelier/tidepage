package app.pdfa.viewer.extras

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import app.pdfa.design.PdfaSpacing
import app.pdfa.design.PdfaTheme
import app.pdfa.feature.viewer.extras.R

/**
 * The bar shown over a text selection: Copy, or the reason copying is not allowed, plus a
 * button to clear the selection. Only Copy for now; no select-all.
 */
@Composable
fun SelectionToolbar(
    state: SelectionState,
    onCopy: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    if (!state.hasText && state.message == null) return
    Surface(
        modifier = modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 0.dp,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = PdfaSpacing.GutterPhone, vertical = PdfaSpacing.S1),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(PdfaSpacing.S2),
        ) {
            if (state.copyForbidden) {
                Text(
                    text = stringResource(R.string.selection_copy_forbidden),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier
                        .weight(1f)
                        .semantics { liveRegion = LiveRegionMode.Polite },
                )
            } else if (state.failed) {
                Text(
                    text = stringResource(R.string.selection_failed),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier
                        .weight(1f)
                        .semantics { liveRegion = LiveRegionMode.Polite },
                )
            } else {
                TextButton(
                    onClick = onCopy,
                    modifier = Modifier.heightIn(min = PdfaSpacing.MinTouchTarget),
                ) {
                    Text(stringResource(R.string.selection_copy))
                }
                val note = when {
                    state.copyFailed -> R.string.selection_copy_failed
                    state.truncated -> R.string.selection_truncated
                    else -> null
                }
                if (note != null) {
                    Text(
                        text = stringResource(note),
                        style = MaterialTheme.typography.bodySmall,
                        color = if (state.copyFailed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier
                            .weight(1f)
                            .semantics { liveRegion = LiveRegionMode.Polite },
                    )
                } else {
                    Spacer(Modifier.weight(1f))
                }
            }
            IconButton(onClick = onDismiss) {
                Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.search_close))
            }
        }
    }
}

@Preview(showBackground = true, name = "Selection, copy")
@Composable
private fun SelectionToolbarPreview() {
    PdfaTheme(darkTheme = false) {
        SelectionToolbar(
            state = SelectionState(page = 0, text = "invoice", copyForbidden = false),
            onCopy = {},
            onDismiss = {},
        )
    }
}

@Preview(showBackground = true, name = "Selection, copy forbidden")
@Composable
private fun SelectionToolbarForbiddenPreview() {
    PdfaTheme(darkTheme = true) {
        SelectionToolbar(
            state = SelectionState(page = 0, copyForbidden = true),
            onCopy = {},
            onDismiss = {},
        )
    }
}
