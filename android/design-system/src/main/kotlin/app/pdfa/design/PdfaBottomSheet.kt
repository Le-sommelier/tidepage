package app.pdfa.design

import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SheetState
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp

/** Modal bottom sheet with the design-system container colour and gutters. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PdfaBottomSheet(
    onDismissRequest: () -> Unit,
    modifier: Modifier = Modifier,
    sheetState: SheetState = rememberModalBottomSheetState(),
    content: @Composable ColumnScope.() -> Unit,
) {
    ModalBottomSheet(
        onDismissRequest = onDismissRequest,
        modifier = modifier,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.background,
        content = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = PdfaSpacing.GutterPhone)
                    .padding(bottom = PdfaSpacing.S5),
                content = content,
            )
        },
    )
}

@Preview(showBackground = true)
@Composable
private fun PdfaBottomSheetContentPreview() {
    // The sheet itself needs a window; the preview shows the content column only.
    PdfaTheme(darkTheme = false) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(PdfaSpacing.GutterPhone),
        ) {
            Text("Bookmarks", style = MaterialTheme.typography.titleLarge)
            Text("No bookmarks yet", style = MaterialTheme.typography.bodyLarge)
        }
    }
}
