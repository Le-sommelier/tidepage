package app.pdfa.app.viewer

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import app.pdfa.app.R
import app.pdfa.app.save.SaveAction
import app.pdfa.design.PdfaBottomSheet
import app.pdfa.engine.Pt
import app.pdfa.engine.Rect
import app.pdfa.viewer.PageLayout
import app.pdfa.viewer.ViewportState
import app.pdfa.viewer.extras.ExternalLinkDialog
import app.pdfa.viewer.extras.LinkAction
import app.pdfa.viewer.extras.OutlineSheet
import app.pdfa.viewer.extras.OutlineState
import app.pdfa.viewer.extras.SearchBar
import app.pdfa.viewer.extras.SearchJump
import app.pdfa.viewer.extras.SearchSnapshot
import app.pdfa.viewer.extras.SelectionState
import app.pdfa.viewer.extras.SelectionToolbar
import app.pdfa.viewer.extras.ThumbnailGrid
import app.pdfa.viewer.ui.ViewerScreen
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch

private enum class Sheet { NONE, CONTENTS, PAGES, FIELDS }

private const val NOTICE_MS = 4_000L

/**
 * The viewer screen with its add-ons: search bar, contents sheet, page previews, text selection
 * and links. Links never open by themselves: an allowed one goes through the confirmation dialog,
 * anything else only shows a short notice.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ViewerHost(session: ViewerSession, onBack: () -> Unit, replaceOriginal: Boolean = false) {
    val vm = session.viewModel
    val addOns by session.addOns.collectAsState()
    val document by session.document.collectAsState()
    val docState = document?.state?.collectAsState()?.value
    val outcome by session.outcome.collectAsState()
    var leaveAsk by remember { mutableStateOf(false) }
    // What the create-document picker was opened for; leaving waits for the save it started.
    var exportPending by remember { mutableStateOf(false) }
    var leaveAfterSave by remember { mutableStateOf(false) }
    val title by vm.title.collectAsState()
    val discardRefused = stringResource(R.string.leave_discard_refused)
    val view = vm.view.collectAsState()
    val scope = rememberCoroutineScope()
    val context = LocalContext.current

    var searchOpen by rememberSaveable { mutableStateOf(false) }
    var sheet by rememberSaveable { mutableStateOf(Sheet.NONE) }
    var organizing by rememberSaveable { mutableStateOf(false) }
    var splitting by rememberSaveable { mutableStateOf(false) }
    var annotating by rememberSaveable { mutableStateOf(false) }
    var filling by rememberSaveable { mutableStateOf(false) }
    var areaSize by remember { mutableStateOf(androidx.compose.ui.unit.IntSize.Zero) }
    val forms by session.forms.collectAsState()
    val formsState = forms?.state?.collectAsState()?.value
    val annotate by session.annotate.collectAsState()
    val geometry = rememberGeometry(view)
    var menuOpen by remember { mutableStateOf(false) }
    var pendingLink by remember { mutableStateOf<LinkAction.ConfirmExternalLink?>(null) }
    var notice by remember { mutableStateOf<String?>(null) }
    val linkRejected = stringResource(R.string.viewer_link_rejected)
    val linkFailed = stringResource(R.string.viewer_link_failed)

    // Background: flush a pending autosave (none yet, see ViewerSession.onStop).
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle, session) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP) session.onStop()
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }

    LaunchedEffect(notice) {
        if (notice != null) {
            delay(NOTICE_MS)
            notice = null
        }
    }

    // A selection belongs to the page and zoom it was made at.
    LaunchedEffect(addOns) {
        val a = addOns ?: return@LaunchedEffect
        launch { snapshotFlow { view.value.currentPage }.distinctUntilChanged().collect { a.selection.onPageChanged(it) } }
        launch { snapshotFlow { view.value.pos.zoom }.distinctUntilChanged().collect { a.selection.onZoomChanged() } }
    }

    fun go(jump: SearchJump?) {
        if (jump != null) vm.jumpToPage(jump.page)
    }

    fun afterSave(result: app.pdfa.app.save.SaveOutcome) {
        if (leaveAfterSave && result.isSuccess()) {
            session.dismissOutcome()
            onBack()
        }
        leaveAfterSave = false
    }
    val createCopy = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/pdf")) { uri ->
        if (uri == null) {
            leaveAfterSave = false // picker cancelled: nothing happens, nothing is lost
        } else {
            session.saveCopyTo(uri.toString(), exportPending, ::afterSave)
        }
    }
    fun pickCopy(export: Boolean = false) {
        exportPending = export
        createCopy.launch(copyName(title))
    }
    fun act(action: SaveAction) {
        session.dismissOutcome()
        when (action) {
            SaveAction.RETRY -> session.retrySave(then = ::afterSave)
            SaveAction.OVERWRITE -> session.retrySave(overwrite = true, then = ::afterSave)
            SaveAction.SAVE_COPY -> pickCopy()
            SaveAction.EXPORT_PENDING -> pickCopy(export = true)
        }
    }
    // Unsaved changes: Back asks first. Nothing is discarded without the user saying so.
    val leave: () -> Unit = { if (docState?.dirty == true) leaveAsk = true else onBack() }
    BackHandler(enabled = docState?.dirty == true) { leaveAsk = true }
    // Leaving annotation sends what is still open; it stays when something could not be added.
    val finishAnnotating: () -> Unit = {
        val c = annotate
        if (c == null) annotating = false else scope.launch { if (c.flush()) annotating = false }
    }
    BackHandler(enabled = annotating && !organizing && !splitting) { finishAnnotating() }
    val finishFilling: () -> Unit = { forms?.deselect(); filling = false }
    BackHandler(enabled = filling && !annotating && !organizing && !splitting) {
        if (forms?.state?.value?.currentName != null) forms?.deselect() else finishFilling()
    }

    Box(Modifier.fillMaxSize()) {
    ViewerScreen(
        // Underneath the organizer, the viewer is invisible to TalkBack.
        modifier = if (organizing || splitting) Modifier.clearAndSetSemantics { } else Modifier,
        viewModel = vm,
        onBack = leave,
        topBarActions = {
            if (docState?.canUndo == true) {
                IconButton(onClick = {
                    val c = annotate
                    // While annotating, an open stroke group is the last change: the controller undoes it.
                    if (annotating && c != null) scope.launch { c.undo() } else session.undo()
                }) { Icon(UndoIcon, contentDescription = stringResource(R.string.viewer_undo)) }
            }
            if (docState?.canRedo == true) {
                IconButton(onClick = session::redo) { Icon(RedoIcon, contentDescription = stringResource(R.string.viewer_redo)) }
            }
            IconButton(onClick = { searchOpen = true }) {
                Icon(Icons.Filled.Search, contentDescription = stringResource(R.string.viewer_search))
            }
            Box {
                IconButton(onClick = { menuOpen = true }) {
                    Icon(Icons.Filled.MoreVert, contentDescription = stringResource(R.string.viewer_more))
                }
                DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                    if (docState != null) {
                        if (replaceOriginal) {
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.viewer_save)) },
                                enabled = (docState.dirty || docState.pendingCopy) && !docState.saving,
                                onClick = { menuOpen = false; session.saveToOriginal() },
                            )
                        }
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.viewer_save_copy)) },
                            enabled = !docState.saving,
                            onClick = { menuOpen = false; pickCopy() },
                        )
                    }
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.viewer_contents)) },
                        onClick = { menuOpen = false; sheet = Sheet.CONTENTS },
                    )
                    if (document != null && docState != null) {
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.viewer_organize)) },
                            enabled = !docState.saving,
                            onClick = { menuOpen = false; organizing = true },
                        )
                    }
                    if (document != null && docState != null && annotate != null) {
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.viewer_annotate)) },
                            enabled = !docState.saving && !annotating,
                            onClick = { menuOpen = false; annotating = true },
                        )
                    }
                    if (document != null && docState != null && formsState != null && hasFillableFields(formsState.fields)) {
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.viewer_fill_form)) },
                            enabled = !docState.saving && !annotating && !filling,
                            onClick = { menuOpen = false; filling = true },
                        )
                    }
                    if (document != null && docState != null) {
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.viewer_split)) },
                            enabled = !docState.saving,
                            onClick = { menuOpen = false; splitting = true },
                        )
                    }
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.viewer_pages)) },
                        onClick = { menuOpen = false; sheet = Sheet.PAGES },
                    )
                }
            }
        },
        header = {
            // A Surface, so icons and text in the bars take the theme's content colour (dark theme too).
            Surface(color = MaterialTheme.colorScheme.background, modifier = Modifier.fillMaxWidth()) {
                Column {
                val a = addOns
                if (a != null) {
                    if (searchOpen) {
                        val snapshot by a.search.state.collectAsState()
                        SearchBar(
                            snapshot = snapshot,
                            onQueryChange = { a.search.submit(it, snapshot.options, view.value.currentPage) },
                            onOptionsChange = { a.search.submit(snapshot.query, it, view.value.currentPage) },
                            onPrevious = { go(a.search.previous()) },
                            onNext = { go(a.search.next()) },
                            onRetry = { a.search.retry() },
                            onClose = {
                                a.search.clear()
                                searchOpen = false
                            },
                        )
                    }
                    val selection by a.selection.state.collectAsState()
                    SelectionToolbar(
                        state = selection,
                        onCopy = { a.selection.copyText() },
                        onDismiss = { a.selection.clear() },
                    )
                }
                if (docState?.saving == true) {
                    LinearProgressIndicator(Modifier.fillMaxWidth())
                    Text(
                        stringResource(R.string.save_in_progress),
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                    )
                }
                if (docState?.pendingCopy == true && docState.saving.not()) {
                    NoticeRow(
                        text = stringResource(R.string.save_pending_banner),
                        onDismiss = null,
                        actionLabel = stringResource(R.string.save_action_retry),
                        onAction = { session.retrySave() },
                    )
                }
                outcome?.takeIf { it.isSuccess() }?.let { done ->
                    NoticeRow(text = stringResource(done.texts().second), onDismiss = session::dismissOutcome)
                }
                notice?.let { NoticeRow(text = it, onDismiss = { notice = null }) }
                }
            }
        },
        overlay = {
            val a = addOns
            if (a != null) {
                val snapshot by a.search.state.collectAsState()
                val selection by a.selection.state.collectAsState()
                HighlightOverlay(view, if (searchOpen) snapshot else null, selection)
            }
            val c = annotate
            if (annotating && c != null) AnnotateOverlayLayer(c, geometry, vm, a?.selection)
            val f = forms
            if (filling && !annotating && f != null) {
                Box(Modifier.fillMaxSize().onSizeChanged { areaSize = it }) { FormsOverlayLayer(f, view, vm, areaSize) }
            }
        },
        toolTray = {
            val c = annotate
            if (annotating && c != null) AnnotateTools(c, geometry, addOns?.selection, onFinish = finishAnnotating)
            val f = forms
            if (filling && !annotating && f != null) FormsTray(f, onOpenList = { sheet = Sheet.FIELDS }, onFinish = finishFilling)
        },
        onTap = { p ->
            val c = annotate
            if (annotating && c != null && c.state.value.tool == app.pdfa.annotate.AnnotateTool.NOTE) {
                if (placeNoteAt(c, geometry, p.x, p.y)) return@ViewerScreen
            }
            val a = addOns
            val v = vm.view.value
            val point = v.layout?.pagePointAt(v.pos, p.x, p.y)
            if (a != null && point != null) {
                if (a.selection.state.value.page != null) a.selection.clear()
                scope.launch {
                    when (val action = a.links.actionAt(point.page, Pt(point.xPt, point.yPt))) {
                        is LinkAction.JumpTo -> vm.jumpToPage(action.page)
                        is LinkAction.ConfirmExternalLink -> pendingLink = action
                        is LinkAction.Rejected -> notice = linkRejected
                        LinkAction.NoLink -> Unit
                    }
                }
            }
        },
        onLongPress = { p ->
            val v = vm.view.value
            val point = v.layout?.pagePointAt(v.pos, p.x, p.y)
            if (point != null) addOns?.selection?.longPress(point.page, Pt(point.xPt, point.yPt))
        },
    )
    val doc = document
    if (organizing && doc != null) {
        OrganizeOverlay(session = session, document = doc, title = title, onDone = { organizing = false })
    }
    if (splitting && doc != null) {
        SplitOverlay(session = session, document = doc, title = title, onDone = { splitting = false })
    }
    }

    val a = addOns
    if (a != null) {
        when (sheet) {
            Sheet.CONTENTS -> {
                val outline by a.outline.state.collectAsState()
                LaunchedEffect(a) { if (a.outline.state.value is OutlineState.Idle) a.outline.load() }
                OutlineSheet(
                    state = outline,
                    currentPage = view.value.currentPage,
                    onToggle = a.outline::toggle,
                    onOpenPage = { page ->
                        vm.jumpToPage(page)
                        sheet = Sheet.NONE
                    },
                    onRetry = { scope.launch { a.outline.load() } },
                    onDismiss = { sheet = Sheet.NONE },
                )
            }

            Sheet.PAGES -> PdfaBottomSheet(onDismissRequest = { sheet = Sheet.NONE }) {
                Text(
                    stringResource(R.string.viewer_pages),
                    style = MaterialTheme.typography.titleLarge,
                    modifier = Modifier.padding(bottom = 12.dp),
                )
                ThumbnailGrid(
                    loader = a.thumbnails,
                    pages = a.pages,
                    currentPage = view.value.currentPage,
                    onPageClick = { page ->
                        vm.jumpToPage(page)
                        sheet = Sheet.NONE
                    },
                    onRetry = a.thumbnails::retry,
                    modifier = Modifier.heightIn(max = 480.dp),
                )
            }

            Sheet.FIELDS -> {
                val f = forms
                if (f == null) sheet = Sheet.NONE else PdfaBottomSheet(onDismissRequest = { sheet = Sheet.NONE }) {
                    FormsFieldListContent(f, onPicked = { sheet = Sheet.NONE })
                }
            }

            Sheet.NONE -> Unit
        }

        pendingLink?.let { action ->
            ExternalLinkDialog(
                link = action.link,
                onConfirm = {
                    pendingLink = null
                    if (!a.opener.openConfirmed(action)) notice = linkFailed
                },
                onDismiss = { pendingLink = null },
            )
        }
    }

    outcome?.takeIf { !it.isSuccess() }?.let { problem ->
        SaveProblemDialog(problem, onAction = ::act, onDismiss = session::dismissOutcome)
    }
    if (leaveAsk) {
        LeaveDialog(
            replaceOriginal = replaceOriginal,
            onSave = { leaveAsk = false; leaveAfterSave = true; session.saveToOriginal(then = ::afterSave) },
            onSaveCopy = { leaveAsk = false; leaveAfterSave = true; pickCopy() },
            onDiscard = {
                leaveAsk = false
                scope.launch {
                    // Refused while a save to the original is unfinished: the staged bytes are kept.
                    if (document?.discardChanges() == false) notice = discardRefused
                    onBack()
                }
            },
            onDismiss = { leaveAsk = false },
        )
    }
    docState?.editsLost?.let { lost -> EditsLostDialog(lost, onDismiss = { document?.dismissEditsLost() }) }
}

/** Search hits and the text selection, drawn over the pages in page view space. */
@Composable
private fun HighlightOverlay(view: State<ViewportState>, search: SearchSnapshot?, selection: SelectionState) {
    val hit = MaterialTheme.colorScheme.tertiary.copy(alpha = 0.30f)
    val currentHit = MaterialTheme.colorScheme.primary.copy(alpha = 0.45f)
    val selected = MaterialTheme.colorScheme.primary.copy(alpha = 0.30f)
    Canvas(Modifier.fillMaxSize().clipToBounds()) {
        val v = view.value
        val layout = v.layout ?: return@Canvas
        val visible = layout.visibleRange(v.pos)
        if (search != null) {
            val hits = search.hits
            for (i in hits.indices) {
                val h = hits[i]
                if (h.page in visible) drawPageRect(layout, v, h.page, h.rect, if (i == search.current) currentHit else hit)
            }
        }
        val page = selection.page
        if (page != null && page in visible) {
            for (r in selection.rects) drawPageRect(layout, v, page, r, selected)
        }
    }
}

private fun DrawScope.drawPageRect(layout: PageLayout, v: ViewportState, page: Int, r: Rect, color: Color) {
    val scale = layout.fitScale(page) * v.pos.zoom
    val ox = layout.pageScreenLeft(v.pos)
    val oy = layout.pageScreenTop(page, v.pos)
    drawRect(
        color = color,
        topLeft = Offset(ox + r.left * scale, oy + r.top * scale),
        size = Size((r.right - r.left) * scale, (r.bottom - r.top) * scale),
    )
}

@Composable
private fun NoticeRow(
    text: String,
    onDismiss: (() -> Unit)?,
    actionLabel: String? = null,
    onAction: () -> Unit = {},
) {
    Surface(
        modifier = Modifier.fillMaxWidth().semantics { liveRegion = LiveRegionMode.Polite },
        color = MaterialTheme.colorScheme.surfaceVariant,
        contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
    ) {
        Row(Modifier.padding(start = 16.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(text, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f).padding(vertical = 8.dp))
            if (actionLabel != null) TextButton(onClick = onAction) { Text(actionLabel) }
            if (onDismiss != null) {
                IconButton(onClick = onDismiss) {
                    Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.viewer_dismiss_notice))
                }
            }
        }
    }
}

/** "Report.pdf" becomes "Report copy.pdf": the picker never starts on the original's name. */
internal fun copyName(title: String): String {
    val base = title.removeSuffix(".pdf").removeSuffix(".PDF").ifBlank { "Document" }
    return "$base copy.pdf"
}
