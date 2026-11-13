package app.pdfa.app.viewer

import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import app.pdfa.annotate.AnnotateBar
import app.pdfa.annotate.AnnotateController
import app.pdfa.annotate.AnnotateLayer
import app.pdfa.annotate.NoteSheet
import app.pdfa.annotate.PageFrame
import app.pdfa.annotate.PageGeometry
import app.pdfa.engine.Pt
import app.pdfa.viewer.ViewerViewModel
import app.pdfa.viewer.ViewportState
import app.pdfa.viewer.extras.SelectionController
import kotlinx.coroutines.launch

/** The viewer's page layout as the annotation layer sees it. Reads [view] on every call (Compose tracks it). */
internal class LayoutPageGeometry(private val view: () -> ViewportState) : PageGeometry {
    override fun pageAt(px: Float, py: Float): Int? {
        val v = view()
        return v.layout?.pagePointAt(v.pos, px, py)?.page
    }

    override fun frame(page: Int): PageFrame? {
        val v = view()
        val layout = v.layout ?: return null
        if (page !in 0 until layout.count) return null
        val info = layout.pages[page]
        return PageFrame(
            page = page,
            left = layout.pageScreenLeft(v.pos),
            top = layout.pageScreenTop(page, v.pos),
            scale = layout.fitScale(page) * v.pos.zoom,
            widthPt = info.widthPt,
            heightPt = info.heightPt,
        )
    }
}

/** Finger tap while annotating with Note: the note goes there. True when the tap was used. */
internal fun placeNoteAt(controller: AnnotateController, geometry: PageGeometry, px: Float, py: Float): Boolean {
    val page = geometry.pageAt(px, py) ?: return false
    val f = geometry.frame(page) ?: return false
    controller.placeNote(page, Pt((px - f.left) / f.scale, (py - f.top) / f.scale), f.widthPt, f.heightPt)
    return true
}

/** The drawing layer, in the viewer's overlay slot. Two fingers move the pages as the viewer would. */
@Composable
internal fun AnnotateOverlayLayer(
    controller: AnnotateController,
    geometry: PageGeometry,
    vm: ViewerViewModel,
    selection: SelectionController?,
) {
    AnnotateLayer(
        controller = controller,
        geometry = geometry,
        onTransform = { x, y, zoom, panX, panY -> vm.transformBy(x, y, zoom, panX, panY) },
        onSelectText = { page, from, to -> selection?.dragSelection(page, from, to) },
    )
}

/** The annotation bar (in place of the bottom tool tray) and the note sheet. */
@Composable
internal fun AnnotateTools(
    controller: AnnotateController,
    geometry: PageGeometry,
    selection: SelectionController?,
    onFinish: () -> Unit,
) {
    val state by controller.state.collectAsState()
    val selected = selection?.state?.collectAsState()
    val scope = rememberCoroutineScope()
    val sel = selected?.value
    AnnotateBar(
        state = state,
        canHighlight = sel?.page != null && sel.rects.isNotEmpty(),
        onTool = controller::selectTool,
        onSwatch = controller::selectSwatch,
        onWidth = controller::selectWidth,
        onHighlight = {
            val s = selection?.state?.value
            val page = s?.page
            val f = page?.let { geometry.frame(it) }
            if (s != null && page != null && f != null) {
                scope.launch {
                    if (controller.highlight(page, s.rects, f.widthPt, f.heightPt)) selection.clear()
                }
            }
        },
        onUndo = { scope.launch { controller.undo() } },
        onDone = onFinish,
        onRetry = { scope.launch { controller.retryAll() } },
        onDiscard = controller::discardFailed,
        onDismissProblem = controller::dismissProblem,
    )
    if (state.noteDraft != null) {
        NoteSheet(
            onSave = { text -> scope.launch { controller.saveNote(text) } },
            onCancel = controller::cancelNote,
        )
    }
}

@Composable
internal fun rememberGeometry(view: State<ViewportState>): PageGeometry = remember(view) { LayoutPageGeometry { view.value } }
