package app.pdfa.design

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview

/** Clamps a determinate progress value to 0..1. NaN becomes 0. */
fun clampProgress(progress: Float): Float =
    if (progress.isNaN()) 0f else progress.coerceIn(0f, 1f)

/**
 * Progress card for operations that take longer than about 400 ms. Pass [progress] for a
 * determinate bar (0..1) or null for an indeterminate one. [onCancel] adds a Cancel button.
 */
@Composable
fun ProgressCard(
    title: String,
    modifier: Modifier = Modifier,
    progress: Float? = null,
    detail: String? = null,
    cancelLabel: String = "Cancel",
    onCancel: (() -> Unit)? = null,
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        shape = PdfaShapes.medium,
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
            contentColor = MaterialTheme.colorScheme.onSurface,
        ),
    ) {
        Column(modifier = Modifier.padding(PdfaSpacing.S4)) {
            Text(text = title, style = MaterialTheme.typography.titleMedium)
            if (detail != null) {
                Text(
                    text = detail,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(modifier = Modifier.height(PdfaSpacing.S2))
            if (progress == null) {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            } else {
                LinearProgressIndicator(
                    progress = { clampProgress(progress) },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            if (onCancel != null) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                ) {
                    TextButton(onClick = onCancel) { Text(cancelLabel) }
                }
            }
        }
    }
}

@Preview(showBackground = true)
@Composable
private fun ProgressCardPreview() {
    PdfaTheme(darkTheme = false) {
        Column(modifier = Modifier.padding(PdfaSpacing.S4)) {
            ProgressCard(
                title = "Merging 2 of 3…",
                progress = 0.4f,
                onCancel = {},
            )
            Spacer(modifier = Modifier.height(PdfaSpacing.S2))
            ProgressCard(title = "Opening file", detail = "Working offline", onCancel = {})
        }
    }
}

@Preview(showBackground = true, name = "Progress card dark")
@Composable
private fun ProgressCardDarkPreview() {
    PdfaTheme(darkTheme = true) {
        Column(modifier = Modifier.padding(PdfaSpacing.S4)) {
            ProgressCard(title = "Saving", progress = 0.75f)
        }
    }
}
