package app.pdfa.organize

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.ArrowForward
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import app.pdfa.design.PdfaSize
import app.pdfa.design.PdfaSpacing
import app.pdfa.design.PdfaTopBar
import app.pdfa.design.UndoSnackbarHost
import app.pdfa.design.rememberUndoSnackbarState
import app.pdfa.engine.DocHandle
import app.pdfa.engine.EngineApi
import app.pdfa.engine.PageInfo
import app.pdfa.feature.organize.R
import app.pdfa.viewer.extras.ThumbnailLoader
import app.pdfa.viewer.extras.ThumbnailState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import java.nio.ByteBuffer

private const val THUMB_WIDTH_PX = 240
private val EdgeScrollZone = 72.dp
private const val EdgeScrollStepPx = 28f

/**
 * Full-screen page organizer. Tap selects, press-and-hold-then-drag moves one page, the bar at the
 * bottom acts on the selection. [onExtract] gets the selected pages (0-based); the host saves them.
 * [notice] is a message from the host (for example the result of an extract); [onNoticeDismiss]
 * clears it.
 */
@Composable
fun OrganizeScreen(
    controller: OrganizeController,
    engine: EngineApi,
    handle: DocHandle,
    onDone: () -> Unit,
    onExtract: (List<Int>) -> Unit,
    notice: String?,
    onNoticeDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val state by controller.state.collectAsState()
    val scope = rememberCoroutineScope()
    val undoSnackbar = rememberUndoSnackbarState()
    LaunchedEffect(controller) { controller.load() }

    // New previews whenever the content changed; the old loader is closed with its composition key.
    val loader = remember(state.revision, engine, handle) {
        ThumbnailLoader(engine, handle, state.pages, THUMB_WIDTH_PX, Dispatchers.Default, scope)
    }
    DisposableEffect(loader) { onDispose { loader.close() } }

    val res = LocalContext.current.resources

    Column(
        modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .pointerInput(Unit) { // nothing leaks through to the viewer underneath
                awaitPointerEventScope { while (true) awaitPointerEvent().changes.forEach { it.consume() } }
            },
    ) {
        PdfaTopBar(
            title = stringResource(R.string.organize_title),
            actions = {
                if (state.canUndo) {
                    IconButton(onClick = { scope.launch { controller.undo() } }, enabled = !state.busy) {
                        Icon(UndoIcon, contentDescription = stringResource(R.string.organize_undo))
                    }
                }
                if (state.canRedo) {
                    IconButton(onClick = { scope.launch { controller.redo() } }, enabled = !state.busy) {
                        Icon(RedoIcon, contentDescription = stringResource(R.string.organize_redo))
                    }
                }
                TextButton(
                    onClick = onDone,
                    enabled = !state.busy,
                    modifier = Modifier.defaultMinSize(minHeight = PdfaSpacing.MinTouchTarget),
                ) { Text(stringResource(R.string.organize_done)) }
            },
        )
        if (state.busy) {
            LinearProgressIndicator(Modifier.fillMaxWidth())
        }
        SelectionHeader(state, controller)
        state.problem?.let { problem ->
            NoticeRow(
                text = stringResource(
                    if (problem == OrganizeProblem.CANNOT_DELETE_ALL) R.string.organize_problem_delete_all
                    else R.string.organize_problem_failed,
                ),
                isError = true,
                onDismiss = controller::dismissProblem,
            )
        }
        notice?.let { NoticeRow(text = it, isError = false, onDismiss = onNoticeDismiss) }

        Box(Modifier.weight(1f).fillMaxWidth()) {
            PageGrid(state = state, controller = controller, loader = loader)
            UndoSnackbarHost(undoSnackbar, Modifier.align(Alignment.BottomCenter))
        }
        ActionBar(
            state = state,
            onRotateLeft = { scope.launch { controller.rotateLeft() } },
            onRotateRight = { scope.launch { controller.rotateRight() } },
            onDelete = {
                scope.launch {
                    val gone = controller.delete()
                    if (gone != null) {
                        val message = if (gone.size == 1) {
                            res.getString(R.string.organize_deleted_one, gone.first() + 1)
                        } else {
                            res.getString(R.string.organize_deleted_many, gone.size)
                        }
                        undoSnackbar.show(message) { scope.launch { controller.undo() } }
                    }
                }
            },
            onDuplicate = { scope.launch { controller.duplicate() } },
            onEarlier = { scope.launch { controller.moveEarlier() } },
            onLater = { scope.launch { controller.moveLater() } },
            onInsert = { scope.launch { controller.insertBlank() } },
            onExtract = { onExtract(state.selection.sorted()) },
        )
    }
}

@Composable
private fun SelectionHeader(state: OrganizeState, controller: OrganizeController) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = PdfaSpacing.GutterPhone).defaultMinSize(minHeight = PdfaSpacing.MinTouchTarget),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val none = state.selection.isEmpty()
        Text(
            text = if (none) stringResource(R.string.organize_hint) else stringResource(R.string.organize_selected, state.selection.size),
            style = if (none) MaterialTheme.typography.bodySmall else MaterialTheme.typography.titleMedium,
            color = if (none) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onBackground,
            modifier = Modifier.weight(1f).semantics { liveRegion = LiveRegionMode.Polite },
        )
        if (!none) {
            TextButton(onClick = controller::clearSelection) { Text(stringResource(R.string.organize_clear)) }
        }
        if (state.selection.size < state.pages.size) {
            TextButton(onClick = controller::selectAll) { Text(stringResource(R.string.organize_select_all)) }
        }
    }
}

@Composable
private fun NoticeRow(text: String, isError: Boolean, onDismiss: () -> Unit) {
    Surface(
        modifier = Modifier.fillMaxWidth().semantics { liveRegion = LiveRegionMode.Polite },
        color = if (isError) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.surfaceVariant,
        contentColor = if (isError) MaterialTheme.colorScheme.onErrorContainer else MaterialTheme.colorScheme.onSurfaceVariant,
    ) {
        Row(Modifier.padding(start = PdfaSpacing.GutterPhone), verticalAlignment = Alignment.CenterVertically) {
            Text(text, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f).padding(vertical = 8.dp))
            IconButton(onClick = onDismiss) {
                Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.organize_dismiss))
            }
        }
    }
}

// ---- the grid ----

@Composable
private fun PageGrid(state: OrganizeState, controller: OrganizeController, loader: ThumbnailLoader) {
    val gridState: LazyGridState = rememberLazyGridState()
    val thumbs by loader.states.collectAsState()
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current
    val edgePx = with(density) { EdgeScrollZone.toPx() }

    LaunchedEffect(gridState, loader) {
        snapshotFlow { gridState.layoutInfo.visibleItemsInfo.map { it.index }.toSet() }
            .distinctUntilChanged()
            .collect { loader.setVisible(it) }
    }

    var dragFrom by remember { mutableStateOf<Int?>(null) }
    var dragTarget by remember { mutableStateOf<Int?>(null) }
    var pointer by remember { mutableStateOf(Offset.Zero) }
    var gridSize by remember { mutableStateOf(IntSize.Zero) }
    val currentState by rememberUpdatedState(state)

    fun itemAt(p: Offset): Int? = gridState.layoutInfo.visibleItemsInfo.firstOrNull {
        IntRect(it.offset, it.size).contains(IntOffset(p.x.toInt(), p.y.toInt()))
    }?.index

    // Scrolls while a dragged page is held near the top or bottom edge.
    LaunchedEffect(dragFrom != null) {
        while (dragFrom != null) {
            val y = pointer.y
            val step = when {
                y < edgePx -> -EdgeScrollStepPx
                y > gridSize.height - edgePx -> EdgeScrollStepPx
                else -> 0f
            }
            if (step != 0f) {
                gridState.scrollBy(step)
                itemAt(pointer)?.let { dragTarget = it }
            }
            delay(16)
        }
    }

    Box(Modifier.fillMaxSize()) {
        LazyVerticalGrid(
            columns = GridCells.Adaptive(PdfaSize.ThumbnailMinWidth),
            state = gridState,
            // The drag must not also scroll the list; the edge scroll above does that by itself.
            userScrollEnabled = dragFrom == null,
            modifier = Modifier
                .fillMaxSize()
                .onSizeChanged { gridSize = it }
                .pointerInput(Unit) {
                    detectDragGesturesAfterLongPress(
                        onDragStart = { start ->
                            val index = itemAt(start)
                            if (index != null) {
                                if (index !in currentState.selection) controller.selectOnly(index)
                                dragFrom = index
                                dragTarget = index
                                pointer = start
                            }
                        },
                        onDrag = { change, _ ->
                            change.consume()
                            pointer = change.position
                            itemAt(pointer)?.let { dragTarget = it }
                        },
                        onDragEnd = {
                            val from = dragFrom
                            val to = dragTarget
                            dragFrom = null
                            dragTarget = null
                            if (from != null && to != null && from != to) {
                                scope.launch {
                                    controller.selectOnly(from)
                                    controller.moveTo(to)
                                }
                            }
                        },
                        onDragCancel = {
                            dragFrom = null
                            dragTarget = null
                        },
                    )
                },
            contentPadding = PaddingValues(PdfaSpacing.S2),
            verticalArrangement = Arrangement.spacedBy(PdfaSpacing.S2),
            horizontalArrangement = Arrangement.spacedBy(PdfaSpacing.S2),
        ) {
            items(state.pages.size, key = { it }) { page ->
                PageTile(
                    page = page,
                    pageCount = state.pages.size,
                    info = state.pages[page],
                    thumb = thumbs[page],
                    selected = page in state.selection,
                    dropTarget = dragFrom != null && dragTarget == page && dragFrom != page,
                    dragging = dragFrom == page,
                    onToggle = { controller.toggle(page) },
                    onEarlier = { scope.launch { controller.selectOnly(page); controller.moveEarlier() } },
                    onLater = { scope.launch { controller.selectOnly(page); controller.moveLater() } },
                    canEarlier = page > 0,
                    canLater = page < state.pages.lastIndex,
                )
            }
        }
        val from = dragFrom
        if (from != null && from in state.pages.indices) {
            DragGhost(
                info = state.pages[from],
                thumb = thumbs[from],
                number = from + 1,
                at = pointer,
            )
        }
    }
}

@Composable
private fun BoxScope.DragGhost(info: PageInfo, thumb: ThumbnailState?, number: Int, at: Offset) {
    val ghostWidth = 88.dp
    val density = LocalDensity.current
    val w = with(density) { ghostWidth.toPx() }
    Box(
        Modifier
            .align(Alignment.TopStart)
            .offset { IntOffset((at.x - w / 2).toInt(), (at.y - w / 2).toInt()) }
            .width(ghostWidth)
            .alpha(0.9f)
            .shadow(8.dp, MaterialTheme.shapes.small)
            .clip(MaterialTheme.shapes.small)
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .aspectRatio(pageRatio(info))
            .border(2.dp, MaterialTheme.colorScheme.primary, MaterialTheme.shapes.small)
            .clearAndSetSemantics { },
        contentAlignment = Alignment.Center,
    ) {
        PageImage(thumb)
        Text(number.toString(), color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.labelLarge)
    }
}

private fun pageRatio(info: PageInfo): Float = if (info.heightPt > 0f && info.widthPt > 0f) info.widthPt / info.heightPt else 0.75f

@Composable
private fun PageImage(thumb: ThumbnailState?) {
    if (thumb is ThumbnailState.Ready) {
        val bitmap = remember(thumb) {
            Bitmap.createBitmap(thumb.width, thumb.height, Bitmap.Config.ARGB_8888)
                .also { it.copyPixelsFromBuffer(ByteBuffer.wrap(thumb.rgba)) }
                .asImageBitmap()
        }
        Image(bitmap = bitmap, contentDescription = null, contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize())
    }
}

@Composable
private fun PageTile(
    page: Int,
    pageCount: Int,
    info: PageInfo,
    thumb: ThumbnailState?,
    selected: Boolean,
    dropTarget: Boolean,
    dragging: Boolean,
    onToggle: () -> Unit,
    onEarlier: () -> Unit,
    onLater: () -> Unit,
    canEarlier: Boolean,
    canLater: Boolean,
) {
    val label = stringResource(R.string.organize_tile, page + 1, pageCount)
    val stateText = stringResource(if (selected) R.string.organize_tile_selected else R.string.organize_tile_not_selected)
    val earlier = stringResource(R.string.organize_move_earlier)
    val later = stringResource(R.string.organize_move_later)
    val colors = MaterialTheme.colorScheme
    val frame = when {
        dropTarget -> colors.tertiary
        selected -> colors.primary
        else -> colors.outline
    }
    Column(
        Modifier
            .alpha(if (dragging) 0.4f else 1f)
            .toggleable(value = selected, role = Role.Checkbox, onValueChange = { onToggle() })
            .semantics {
                contentDescription = label
                stateDescription = stateText
                val actions = ArrayList<CustomAccessibilityAction>()
                if (canEarlier) actions += CustomAccessibilityAction(earlier) { onEarlier(); true }
                if (canLater) actions += CustomAccessibilityAction(later) { onLater(); true }
                customActions = actions
            },
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            Modifier
                .fillMaxWidth()
                .aspectRatio(pageRatio(info))
                .clip(MaterialTheme.shapes.small)
                .background(colors.surfaceVariant)
                .border(if (selected || dropTarget) 3.dp else 1.dp, frame, MaterialTheme.shapes.small),
            contentAlignment = Alignment.Center,
        ) {
            PageImage(thumb)
            if (thumb is ThumbnailState.Failed) {
                Text(
                    stringResource(R.string.organize_tile_failed),
                    style = MaterialTheme.typography.labelSmall,
                    color = colors.error,
                    textAlign = TextAlign.Center,
                )
            }
            if (selected) {
                Box(Modifier.fillMaxSize().background(colors.primary.copy(alpha = 0.14f)))
                Box(
                    Modifier
                        .align(Alignment.TopEnd)
                        .padding(4.dp)
                        .size(24.dp)
                        .background(colors.primary, CircleShape),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(Icons.Filled.Check, contentDescription = null, tint = colors.onPrimary, modifier = Modifier.size(16.dp))
                }
            }
        }
        Text(
            text = (page + 1).toString(),
            style = MaterialTheme.typography.labelMedium,
            color = if (selected) colors.primary else colors.onSurfaceVariant,
            modifier = Modifier.padding(top = PdfaSpacing.S1),
        )
    }
}

// ---- the action bar ----

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ActionBar(
    state: OrganizeState,
    onRotateLeft: () -> Unit,
    onRotateRight: () -> Unit,
    onDelete: () -> Unit,
    onDuplicate: () -> Unit,
    onEarlier: () -> Unit,
    onLater: () -> Unit,
    onInsert: () -> Unit,
    onExtract: () -> Unit,
) {
    val sel = state.selection.sorted()
    val n = sel.size
    val has = n > 0 && !state.busy
    val first = sel.firstOrNull()?.plus(1) ?: 0
    @Composable fun cd(one: Int, many: Int): String =
        if (n == 1) stringResource(one, first) else stringResource(many, n)

    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant,
        contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
        tonalElevation = 3.dp,
        modifier = Modifier.fillMaxWidth(),
    ) {
        // Wraps onto as many rows as the width and the font size need: every action stays visible.
        FlowRow(
            Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = PdfaSpacing.S1),
            horizontalArrangement = Arrangement.spacedBy(2.dp, Alignment.CenterHorizontally),
        ) {
            BarButton(stringResource(R.string.organize_rotate_left), cd(R.string.organize_cd_rotate_left_one, R.string.organize_cd_rotate_left_many), has, onRotateLeft) {
                Icon(RotateLeftIcon, null)
            }
            BarButton(stringResource(R.string.organize_rotate_right), cd(R.string.organize_cd_rotate_right_one, R.string.organize_cd_rotate_right_many), has, onRotateRight) {
                Icon(RotateLeftIcon, null, Modifier.scale(scaleX = -1f, scaleY = 1f))
            }
            BarButton(stringResource(R.string.organize_delete), cd(R.string.organize_cd_delete_one, R.string.organize_cd_delete_many), has, onDelete) {
                Icon(Icons.Filled.Delete, null)
            }
            BarButton(stringResource(R.string.organize_duplicate), cd(R.string.organize_cd_duplicate_one, R.string.organize_cd_duplicate_many), has, onDuplicate) {
                Icon(DuplicateIcon, null)
            }
            BarButton(stringResource(R.string.organize_move_earlier), cd(R.string.organize_cd_earlier_one, R.string.organize_cd_earlier_many), has && state.canMoveEarlier, onEarlier) {
                Icon(Icons.Filled.ArrowBack, null)
            }
            BarButton(stringResource(R.string.organize_move_later), cd(R.string.organize_cd_later_one, R.string.organize_cd_later_many), has && state.canMoveLater, onLater) {
                Icon(Icons.Filled.ArrowForward, null)
            }
            BarButton(
                stringResource(R.string.organize_insert_blank),
                if (n == 0) stringResource(R.string.organize_cd_insert_end) else stringResource(R.string.organize_cd_insert_after, sel.last() + 1),
                !state.busy && state.pages.isNotEmpty(),
                onInsert,
            ) { Icon(Icons.Filled.Add, null) }
            BarButton(stringResource(R.string.organize_extract), cd(R.string.organize_cd_extract_one, R.string.organize_cd_extract_many), has, onExtract) {
                Icon(ExtractIcon, null)
            }
        }
    }
}

/** An icon over an always-visible label, at least 64 x 56 dp. The spoken name is [description]. */
@Composable
private fun BarButton(
    label: String,
    description: String,
    enabled: Boolean,
    onClick: () -> Unit,
    icon: @Composable () -> Unit,
) {
    Column(
        Modifier
            .defaultMinSize(minWidth = 72.dp, minHeight = 56.dp)
            .clip(MaterialTheme.shapes.small)
            .alpha(if (enabled) 1f else 0.38f)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .padding(horizontal = 6.dp, vertical = 6.dp)
            .clearAndSetSemantics {
                contentDescription = description
                role = Role.Button
            },
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        icon()
        Text(label, style = MaterialTheme.typography.labelSmall, maxLines = 2, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
    }
}
