package app.pdfa.viewer.extras

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.PaddingValues
import app.pdfa.design.PdfaSize
import app.pdfa.design.PdfaSpacing
import app.pdfa.engine.PageInfo
import app.pdfa.feature.viewer.extras.R
import kotlinx.coroutines.flow.distinctUntilChanged

/** Width of one strip thumbnail. */
private val StripWidth = 72.dp

/**
 * A grid of page previews (min width [PdfaSize.ThumbnailMinWidth]). Tapping a page calls
 * [onPageClick] with its 0-based index. Only the visible cells are handed to [loader].
 */
@Composable
fun ThumbnailGrid(
    loader: ThumbnailLoader,
    pages: List<PageInfo>,
    currentPage: Int,
    onPageClick: (Int) -> Unit,
    onRetry: (Int) -> Unit,
    modifier: Modifier = Modifier,
    gridState: LazyGridState = rememberLazyGridState(),
) {
    val states by loader.states.collectAsState()
    LaunchedEffect(gridState, loader) {
        snapshotFlow { gridState.layoutInfo.visibleItemsInfo.map { it.index }.toSet() }
            .distinctUntilChanged()
            .collect { loader.setVisible(it) }
    }
    LazyVerticalGrid(
        columns = GridCells.Adaptive(PdfaSize.ThumbnailMinWidth),
        state = gridState,
        modifier = modifier,
        contentPadding = PaddingValues(PdfaSpacing.S2),
        verticalArrangement = Arrangement.spacedBy(PdfaSpacing.S2),
        horizontalArrangement = Arrangement.spacedBy(PdfaSpacing.S2),
    ) {
        items(pages.size) { page ->
            ThumbnailTile(
                page = page,
                pageCount = pages.size,
                info = pages[page],
                state = states[page],
                selected = page == currentPage,
                onClick = { onPageClick(page) },
                onRetry = { onRetry(page) },
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

/**
 * A horizontal strip of page previews for the bottom of the viewer. Same loading rules as
 * [ThumbnailGrid].
 */
@Composable
fun ThumbnailStrip(
    loader: ThumbnailLoader,
    pages: List<PageInfo>,
    currentPage: Int,
    onPageClick: (Int) -> Unit,
    onRetry: (Int) -> Unit,
    modifier: Modifier = Modifier,
    listState: LazyListState = rememberLazyListState(),
) {
    val states by loader.states.collectAsState()
    LaunchedEffect(listState, loader) {
        snapshotFlow { listState.layoutInfo.visibleItemsInfo.map { it.index }.toSet() }
            .distinctUntilChanged()
            .collect { loader.setVisible(it) }
    }
    LazyRow(
        state = listState,
        modifier = modifier,
        contentPadding = PaddingValues(horizontal = PdfaSpacing.GutterPhone, vertical = PdfaSpacing.S2),
        horizontalArrangement = Arrangement.spacedBy(PdfaSpacing.S2),
    ) {
        items(pages.size) { page ->
            ThumbnailTile(
                page = page,
                pageCount = pages.size,
                info = pages[page],
                state = states[page],
                selected = page == currentPage,
                onClick = { onPageClick(page) },
                onRetry = { onRetry(page) },
                modifier = Modifier.width(StripWidth),
            )
        }
    }
}

/**
 * One preview cell. Shows the bitmap when ready, a flat placeholder while loading, and a
 * "Preview failed" line with a retry button when it failed. The whole cell is one button for
 * TalkBack, labelled "Page n of m".
 */
@Composable
private fun ThumbnailTile(
    page: Int,
    pageCount: Int,
    info: PageInfo,
    state: ThumbnailState?,
    selected: Boolean,
    onClick: () -> Unit,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val label = stringResource(R.string.thumbnail_page, page + 1, pageCount)
    val ratio = if (info.heightPt > 0f) info.widthPt / info.heightPt else 0.75f
    val frame = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline
    Column(
        modifier = modifier.semantics { contentDescription = label },
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(ratio)
                .clip(MaterialTheme.shapes.small)
                .border(width = if (selected) 2.dp else 1.dp, color = frame, shape = MaterialTheme.shapes.small)
                .background(MaterialTheme.colorScheme.surfaceVariant)
                .clickable(role = Role.Button, onClick = onClick),
            contentAlignment = Alignment.Center,
        ) {
            when (state) {
                is ThumbnailState.Ready -> {
                    val bitmap = remember(state) { state.toImageBitmap() }
                    Image(
                        bitmap = bitmap,
                        contentDescription = null,
                        contentScale = ContentScale.Fit,
                        modifier = Modifier.fillMaxSize(),
                    )
                }
                is ThumbnailState.Failed -> Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier.padding(PdfaSpacing.S1),
                ) {
                    Text(
                        text = stringResource(R.string.thumbnail_failed),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                    if (!state.engineLost) {
                        TextButton(
                            onClick = onRetry,
                            modifier = Modifier.heightIn(min = PdfaSpacing.MinTouchTarget),
                        ) {
                            Text(stringResource(R.string.thumbnail_retry), style = MaterialTheme.typography.labelSmall)
                        }
                    }
                }
                ThumbnailState.Loading, null -> Unit
            }
        }
        Text(
            text = (page + 1).toString(),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = PdfaSpacing.S1),
        )
    }
}
