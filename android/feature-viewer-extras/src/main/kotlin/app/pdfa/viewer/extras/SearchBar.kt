package app.pdfa.viewer.extras

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.tooling.preview.Preview
import app.pdfa.design.PdfaSpacing
import app.pdfa.design.PdfaTheme
import app.pdfa.engine.Rect
import app.pdfa.feature.viewer.extras.R

/**
 * The in-document search bar: query field, options, hit counter, previous/next and close.
 * The counter is a polite live region, so TalkBack announces "3 of 128" as the user steps.
 */
@Composable
fun SearchBar(
    snapshot: SearchSnapshot,
    onQueryChange: (String) -> Unit,
    onOptionsChange: (SearchOptions) -> Unit,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
    onRetry: () -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val hasHits = snapshot.hits.isNotEmpty()
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = PdfaSpacing.GutterPhone, vertical = PdfaSpacing.S2),
        verticalArrangement = Arrangement.spacedBy(PdfaSpacing.S1),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = snapshot.query,
                onValueChange = onQueryChange,
                singleLine = true,
                label = { Text(stringResource(R.string.search_field_label)) },
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { if (hasHits) onNext() }),
                modifier = Modifier.weight(1f),
            )
            IconButton(onClick = onPrevious, enabled = hasHits) {
                Icon(Icons.Filled.KeyboardArrowUp, contentDescription = stringResource(R.string.search_previous))
            }
            IconButton(onClick = onNext, enabled = hasHits) {
                Icon(Icons.Filled.KeyboardArrowDown, contentDescription = stringResource(R.string.search_next))
            }
            IconButton(onClick = onClose) {
                Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.search_close))
            }
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(PdfaSpacing.S2),
        ) {
            FilterChip(
                selected = snapshot.options.caseSensitive,
                onClick = { onOptionsChange(snapshot.options.copy(caseSensitive = !snapshot.options.caseSensitive)) },
                label = { Text(stringResource(R.string.search_case_sensitive)) },
                modifier = Modifier.heightIn(min = PdfaSpacing.MinTouchTarget),
            )
            FilterChip(
                selected = snapshot.options.wholeWord,
                onClick = { onOptionsChange(snapshot.options.copy(wholeWord = !snapshot.options.wholeWord)) },
                label = { Text(stringResource(R.string.search_whole_word)) },
                modifier = Modifier.heightIn(min = PdfaSpacing.MinTouchTarget),
            )
            Text(
                text = counterText(snapshot),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onBackground,
                modifier = Modifier
                    .weight(1f)
                    .semantics { liveRegion = LiveRegionMode.Polite },
            )
        }
        if (snapshot.failure != null) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = stringResource(R.string.search_failed),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = onRetry) { Text(stringResource(R.string.search_retry)) }
            }
        }
    }
}

/** What the reader or TalkBack hears: progress while scanning, then the counter. */
@Composable
private fun counterText(snapshot: SearchSnapshot): String {
    val counter = snapshot.counter() ?: return ""
    val scanning = snapshot.phase == SearchPhase.WAITING || snapshot.phase == SearchPhase.SCANNING
    return when {
        scanning && counter.total == 0 ->
            if (snapshot.pageCount > 0) {
                stringResource(
                    R.string.search_scanning,
                    minOf(snapshot.scannedPages + 1, snapshot.pageCount),
                    snapshot.pageCount,
                )
            } else {
                ""
            }
        counter.total == 0 -> stringResource(R.string.search_no_matches)
        counter.position > 0 && counter.truncated ->
            stringResource(R.string.search_counter_truncated, counter.position, counter.total)
        counter.position > 0 -> stringResource(R.string.search_counter, counter.position, counter.total)
        counter.truncated -> stringResource(R.string.search_counter_truncated, 0, counter.total)
        else -> LocalContext.current.resources.getQuantityString(R.plurals.search_counter_total, counter.total, counter.total)
    }
}

@Preview(showBackground = true, name = "Search, 3 of 128")
@Composable
private fun SearchBarPreview() {
    PdfaTheme(darkTheme = false) {
        SearchBar(
            snapshot = SearchSnapshot(
                query = "invoice",
                hits = List(128) { SearchMatch(0, Rect(0f, 0f, 1f, 1f)) },
                current = 2,
                pageCount = 12,
                scannedPages = 12,
                phase = SearchPhase.DONE,
            ),
            onQueryChange = {},
            onOptionsChange = {},
            onPrevious = {},
            onNext = {},
            onRetry = {},
            onClose = {},
        )
    }
}

@Preview(showBackground = true, name = "Search, no matches")
@Composable
private fun SearchBarEmptyPreview() {
    PdfaTheme(darkTheme = true) {
        SearchBar(
            snapshot = SearchSnapshot(query = "zzz", pageCount = 3, scannedPages = 3, phase = SearchPhase.DONE),
            onQueryChange = {},
            onOptionsChange = {},
            onPrevious = {},
            onNext = {},
            onRetry = {},
            onClose = {},
        )
    }
}
