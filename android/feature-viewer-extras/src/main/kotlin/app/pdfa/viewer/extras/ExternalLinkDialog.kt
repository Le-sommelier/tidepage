package app.pdfa.viewer.extras

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import app.pdfa.design.PdfaSpacing
import app.pdfa.design.PdfaTheme
import app.pdfa.feature.viewer.extras.R

/**
 * Asks before an external link opens. The destination (host, mail address or number) is shown
 * large and bold, the full URI small underneath. Only [onConfirm] may lead to opening it.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ExternalLinkDialog(
    link: ParsedLink,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    val model = LinkDialogModels.of(link)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.link_dialog_title), style = MaterialTheme.typography.titleLarge) },
        text = {
            Column {
                Text(
                    text = stringResource(R.string.link_dialog_body),
                    style = MaterialTheme.typography.bodyMedium,
                )
                Text(
                    text = model.headline,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.padding(top = PdfaSpacing.S3),
                )
                Text(
                    text = model.fullUri,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = PdfaSpacing.S1),
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onConfirm, modifier = Modifier.heightIn(min = PdfaSpacing.MinTouchTarget)) {
                Text(stringResource(R.string.link_dialog_open))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, modifier = Modifier.heightIn(min = PdfaSpacing.MinTouchTarget)) {
                Text(stringResource(R.string.link_dialog_cancel))
            }
        },
    )
}

@Preview(showBackground = true, name = "External link")
@Composable
private fun ExternalLinkDialogPreview() {
    PdfaTheme(darkTheme = false) {
        val link = (ExternalLinkPolicy.check("https://example.org/report") as LinkCheck.Allowed).link
        ExternalLinkDialog(link = link, onConfirm = {}, onDismiss = {})
    }
}
