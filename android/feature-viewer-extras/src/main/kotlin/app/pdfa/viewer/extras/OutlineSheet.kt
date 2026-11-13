package app.pdfa.viewer.extras

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import app.pdfa.design.PdfaBottomSheet
import app.pdfa.design.PdfaSpacing
import app.pdfa.design.PdfaTheme
import app.pdfa.feature.viewer.extras.R

/**
 * The contents sheet: an expandable tree of outline rows. Rows without a page are shown
 * but cannot be tapped. [currentPage] (0-based) is highlighted.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OutlineSheet(
    state: OutlineState,
    currentPage: Int?,
    onToggle: (List<Int>) -> Unit,
    onOpenPage: (Int) -> Unit,
    onRetry: () -> Unit,
    onDismiss: () -> Unit,
) {
    PdfaBottomSheet(onDismissRequest = onDismiss) {
        Text(
            text = stringResource(R.string.outline_title),
            style = MaterialTheme.typography.titleLarge,
            modifier = Modifier.padding(bottom = PdfaSpacing.S2),
        )
        when (state) {
            OutlineState.Idle, OutlineState.Loading -> CircularProgressIndicator()
            is OutlineState.Failed -> Column(verticalArrangement = Arrangement.spacedBy(PdfaSpacing.S2)) {
                Text(stringResource(R.string.outline_failed), style = MaterialTheme.typography.bodyLarge)
                if (!state.engineLost) {
                    Button(onClick = onRetry, modifier = Modifier.heightIn(min = PdfaSpacing.MinTouchTarget)) {
                        Text(stringResource(R.string.search_retry))
                    }
                }
            }
            is OutlineState.Ready -> if (state.rows.isEmpty()) {
                Text(stringResource(R.string.outline_empty), style = MaterialTheme.typography.bodyLarge)
            } else {
                LazyColumn(modifier = Modifier.heightIn(max = 480.dp)) {
                    items(state.rows, key = { row -> row.path.joinToString(".") }) { row ->
                        OutlineRowItem(
                            row = row,
                            current = row.page != null && row.page == currentPage,
                            onToggle = { onToggle(row.path) },
                            onOpen = { row.page?.let(onOpenPage) },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun OutlineRowItem(
    row: VisibleOutlineRow,
    current: Boolean,
    onToggle: () -> Unit,
    onOpen: () -> Unit,
) {
    val color = if (current) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onBackground
    val pageText = row.page?.let { stringResource(R.string.outline_page_label, it + 1) }
        ?: stringResource(R.string.outline_no_page)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = PdfaSpacing.MinTouchTarget)
            .padding(start = (row.depth * 16).dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(
            modifier = Modifier
                .weight(1f)
                .clickable(
                    enabled = row.navigable,
                    role = Role.Button,
                    onClickLabel = pageText,
                    onClick = onOpen,
                )
                .padding(vertical = PdfaSpacing.S2),
        ) {
            Text(text = row.title, style = MaterialTheme.typography.bodyLarge, color = color)
            Text(
                text = pageText,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (row.expandable) {
            val label = stringResource(if (row.expanded) R.string.outline_collapse else R.string.outline_expand)
            IconButton(
                onClick = onToggle,
                modifier = Modifier.semantics {
                    contentDescription = "$label ${row.title}"
                    stateDescription = if (row.expanded) "Expanded" else "Collapsed"
                },
            ) {
                Icon(
                    imageVector = if (row.expanded) Icons.Filled.KeyboardArrowUp else Icons.Filled.KeyboardArrowDown,
                    contentDescription = null,
                )
            }
        }
    }
}

@Preview(showBackground = true, name = "Outline")
@Composable
private fun OutlineSheetPreview() {
    PdfaTheme(darkTheme = false) {
        Column(modifier = Modifier.fillMaxWidth()) {
            OutlineRowItem(
                row = VisibleOutlineRow(listOf(0), "1. Introduction", 0, 0, expandable = true, expanded = true),
                current = true,
                onToggle = {},
                onOpen = {},
            )
            OutlineRowItem(
                row = VisibleOutlineRow(listOf(0, 0), "1.1 Scope", 2, 1, expandable = false, expanded = false),
                current = false,
                onToggle = {},
                onOpen = {},
            )
            OutlineRowItem(
                row = VisibleOutlineRow(listOf(1), "Appendix (no page)", null, 0, expandable = false, expanded = false),
                current = false,
                onToggle = {},
                onOpen = {},
            )
        }
    }
}
