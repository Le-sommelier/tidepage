package app.pdfa.app.viewer

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect as ScreenRect
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import app.pdfa.engine.FieldInfo
import app.pdfa.engine.Rect
import app.pdfa.forms.FormFieldEditor
import app.pdfa.forms.FormFieldList
import app.pdfa.forms.FormFieldsOverlay
import app.pdfa.forms.FormsController
import app.pdfa.forms.isEditable
import app.pdfa.forms.messageRes
import app.pdfa.viewer.ViewerViewModel
import app.pdfa.viewer.ViewportState
import kotlinx.coroutines.launch

/** A widget rectangle (page view space, points) in pixels of the page area, or null when its page is not on screen. */
internal fun widgetToScreen(v: ViewportState, page: Int, r: Rect): ScreenRect? {
    val layout = v.layout ?: return null
    if (page !in 0 until layout.count || page !in layout.visibleRange(v.pos)) return null
    val scale = layout.fitScale(page) * v.pos.zoom
    val ox = layout.pageScreenLeft(v.pos)
    val oy = layout.pageScreenTop(page, v.pos)
    return ScreenRect(ox + r.left * scale, oy + r.top * scale, ox + r.right * scale, oy + r.bottom * scale)
}

/** True when the document has at least one field the user can fill in here. */
internal fun hasFillableFields(fields: List<FieldInfo>): Boolean = fields.any { it.isEditable }

/** Outlines over the widgets, and the scroll that brings the selected field into view. */
@Composable
internal fun FormsOverlayLayer(
    controller: FormsController,
    view: State<ViewportState>,
    vm: ViewerViewModel,
    areaSize: IntSize,
) {
    val state by controller.state.collectAsState()
    FormFieldsOverlay(
        fields = state.fields,
        currentField = state.currentName,
        pageRectToScreen = { page, rect -> widgetToScreen(view.value, page, rect) },
        onFieldClick = { name -> controller.select(name) },
        modifier = Modifier.fillMaxSize(),
    )
    // Selecting a field (tap, list, Next) or a change of the visible area (keyboard) brings it into view.
    val current = state.current
    LaunchedEffect(current?.name, areaSize) {
        val widget = current?.widgets?.firstOrNull() ?: return@LaunchedEffect
        if (areaSize.width <= 0 || areaSize.height <= 0) return@LaunchedEffect
        repeat(3) {
            val r = widgetToScreen(view.value, widget.page, widget.rect)
            if (r == null) {
                vm.jumpToPage(widget.page)
                withFrameNanos { }
                return@repeat
            }
            val margin = 24f
            val inside = r.left >= margin && r.right <= areaSize.width - margin &&
                r.top >= margin && r.bottom <= areaSize.height - margin
            if (!inside) {
                val dx = if (r.left < margin || r.right > areaSize.width - margin) areaSize.width / 2f - r.center.x else 0f
                val dy = areaSize.height / 2f - r.center.y
                vm.panBy(dx, dy)
            }
            return@LaunchedEffect
        }
    }
}

/**
 * In place of the bottom tool tray while filling in: the editor of the selected field (above the
 * keyboard), or a small bar with the field list and Done.
 */
@Composable
internal fun FormsTray(controller: FormsController, onOpenList: () -> Unit, onFinish: () -> Unit) {
    val state by controller.state.collectAsState()
    val scope = rememberCoroutineScope()
    Surface(color = MaterialTheme.colorScheme.surfaceContainer, modifier = Modifier.fillMaxWidth().imePadding()) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
            state.problem?.let { problem ->
                Row(horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
                    Text(
                        stringResource(problem.messageRes()),
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(onClick = controller::dismissProblem) { Text(stringResource(android.R.string.ok)) }
                }
            }
            val field = state.current
            if (field == null) {
                val missing = state.missingRequired.size
                if (missing > 0) {
                    Text(
                        pluralStringResource(app.pdfa.feature.forms.R.plurals.forms_list_missing_count, missing, missing),
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
                Row(horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
                    TextButton(onClick = onOpenList) { Text(stringResource(app.pdfa.feature.forms.R.string.forms_list_title)) }
                    TextButton(onClick = onFinish) { Text(stringResource(app.pdfa.feature.forms.R.string.forms_editor_done)) }
                }
            } else {
                FormFieldEditor(
                    field = field,
                    validation = state.validation[field.name],
                    onText = { t -> scope.launch { controller.setText(field.name, t) } },
                    onChecked = { c -> scope.launch { controller.setChecked(field.name, c) } },
                    onRadio = { r -> scope.launch { controller.setRadio(field.name, r) } },
                    onChoice = { c -> scope.launch { controller.setChoice(field.name, c) } },
                    onClear = { scope.launch { controller.clearField(field.name) } },
                    onNext = { controller.next() },
                    onPrevious = { controller.previous() },
                    onDone = { controller.deselect() },
                    hasNext = state.hasNext,
                    hasPrevious = state.hasPrevious,
                )
            }
        }
    }
}

/** The content of the field list sheet. Picking a row selects the field; "Clear form" asks first. */
@Composable
internal fun FormsFieldListContent(controller: FormsController, onPicked: () -> Unit) {
    val state by controller.state.collectAsState()
    val scope = rememberCoroutineScope()
    FormFieldList(
        fields = state.fields,
        onSelect = { name -> controller.select(name); onPicked() },
        onClearForm = { scope.launch { controller.clearForm() } },
    )
}
