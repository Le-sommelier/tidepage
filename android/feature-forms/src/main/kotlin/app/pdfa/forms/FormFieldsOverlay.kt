package app.pdfa.forms

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import app.pdfa.design.PdfaSpacing
import app.pdfa.design.PdfaTheme
import app.pdfa.engine.FieldInfo
import app.pdfa.engine.FieldKind
import app.pdfa.engine.FieldWidget
import app.pdfa.engine.Rect
import kotlin.math.max
import kotlin.math.roundToInt
import androidx.compose.ui.geometry.Rect as ScreenRect

/**
 * Draws the form widgets over the page and makes each one a tap target.
 *
 * [pageRectToScreen] maps a widget rectangle (page view space, PDF points) to pixels in this
 * overlay's own coordinate space, or null when that page is not on screen. Place the overlay over
 * the page area with the same size. Each target is at least 48 dp square, centred on the widget,
 * so tiny check boxes stay reachable; neighbouring tiny widgets can then overlap, in which case
 * the one drawn last gets the tap. Use [FormsController.hitTest] when you handle taps yourself.
 */
@Composable
fun FormFieldsOverlay(
    fields: List<FieldInfo>,
    currentField: String?,
    pageRectToScreen: (page: Int, rect: Rect) -> ScreenRect?,
    onFieldClick: (fieldName: String) -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(modifier.fillMaxSize()) {
        // The current field is drawn last so it is on top and gets the tap.
        val ordered = fields.sortedBy { it.name == currentField }
        for (field in ordered) {
            for (widget in field.widgets) {
                val r = pageRectToScreen(widget.page, widget.rect) ?: continue
                FieldTarget(field, widget, r, current = field.name == currentField, onClick = { onFieldClick(field.name) })
            }
        }
    }
}

@Composable
private fun FieldTarget(field: FieldInfo, widget: FieldWidget, rect: ScreenRect, current: Boolean, onClick: () -> Unit) {
    val density = LocalDensity.current
    val minPx = with(density) { PdfaSpacing.MinTouchTarget.toPx() }
    val w = max(rect.width, minPx)
    val h = max(rect.height, minPx)
    val center = rect.center
    val description = field.spokenDescription(current)
    val colors = MaterialTheme.colorScheme
    val inert = !field.isEditable
    val outline = if (inert) colors.outline else colors.primary
    val missing = field.isMissingRequired()
    val markerColor = colors.error
    Box(
        Modifier
            .offset { IntOffset((center.x - w / 2f).roundToInt(), (center.y - h / 2f).roundToInt()) }
            .size(with(density) { w.toDp() }, with(density) { h.toDp() })
            .clearAndSetSemantics {
                contentDescription = description
                role = if (field.kind == FieldKind.CHECK_BOX) Role.Checkbox else Role.Button
                // Read-only and signature fields stay clickable: the editor explains why they cannot be filled.
                onClick { onClick(); true }
            }
            .clickable(onClick = onClick),
    ) {
        Canvas(Modifier.fillMaxSize()) {
            val topLeft = Offset((size.width - rect.width) / 2f, (size.height - rect.height) / 2f)
            val box = Size(rect.width, rect.height)
            val fillAlpha = if (current) 0.22f else 0.10f
            drawRect(outline.copy(alpha = fillAlpha), topLeft, box)
            drawRect(
                outline.copy(alpha = if (current) 1f else 0.55f),
                topLeft,
                box,
                style = Stroke(width = (if (current) 3.dp else 1.5.dp).toPx()),
            )
            if (missing) {
                // A filled corner flag: a shape, not only a colour.
                val s = 10.dp.toPx()
                val right = topLeft.x + box.width
                val path = androidx.compose.ui.graphics.Path().apply {
                    moveTo(right - s, topLeft.y)
                    lineTo(right, topLeft.y)
                    lineTo(right, topLeft.y + s)
                    close()
                }
                drawPath(path, markerColor)
            }
        }
    }
}

// ---- previews ----

private val previewFields = listOf(
    FieldInfo("name", FieldKind.TEXT, emptyList(), emptyList(), false, true, false, null, listOf(FieldWidget(0, Rect(40f, 40f, 240f, 64f), null))),
    FieldInfo("subscribe", FieldKind.CHECK_BOX, listOf("Yes"), emptyList(), false, false, false, null, listOf(FieldWidget(0, Rect(40f, 90f, 56f, 106f), "Yes"))),
    FieldInfo("id", FieldKind.TEXT, listOf("A-1"), emptyList(), true, false, false, null, listOf(FieldWidget(0, Rect(40f, 130f, 240f, 154f), null))),
    FieldInfo("sig", FieldKind.SIGNATURE, emptyList(), emptyList(), false, false, false, null, listOf(FieldWidget(0, Rect(40f, 180f, 240f, 220f), null))),
)

@Composable
private fun OverlayPreview(dark: Boolean) = PdfaTheme(darkTheme = dark) {
    Box(Modifier.size(280.dp, 260.dp).background(MaterialTheme.colorScheme.surface)) {
        FormFieldsOverlay(
            fields = previewFields,
            currentField = "name",
            pageRectToScreen = { _, r -> ScreenRect(r.left * 2f, r.top * 2f, r.right * 2f, r.bottom * 2f) },
            onFieldClick = {},
        )
    }
}

@Preview(name = "Overlay light", showBackground = true)
@Composable
private fun OverlayLightPreview() = OverlayPreview(false)

@Preview(name = "Overlay dark", showBackground = true, backgroundColor = 0xFF101418)
@Composable
private fun OverlayDarkPreview() = OverlayPreview(true)
