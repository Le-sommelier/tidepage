package app.pdfa.scan

import app.pdfa.feature.scan.R
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import app.pdfa.design.PdfaTheme
import app.pdfa.engine.Pt
import app.pdfa.engine.Quad
import kotlin.math.roundToInt

private val HandleTouch = 56.dp // above the 48 dp minimum
private val HandleDot = 22.dp
private val LoupeRadius = 52.dp
private const val LoupeZoom = 2.5f

/**
 * The picture with four draggable corners. [quad] is in picture pixels ([imageWidth] x
 * [imageHeight]); [onQuadChange] gets every change while dragging. A dragged corner keeps its
 * offset from the finger and a magnifier above it shows what is under the corner, so the finger
 * never hides it. TalkBack users get Move left/right/up/down actions on each corner.
 */
@Composable
internal fun CropEditor(
    image: ImageBitmap?,
    imageWidth: Int,
    imageHeight: Int,
    quad: Quad,
    onQuadChange: (Quad) -> Unit,
    modifier: Modifier = Modifier,
) {
    val density = LocalDensity.current
    val currentQuad by rememberUpdatedState(quad)
    val currentChange by rememberUpdatedState(onQuadChange)
    var active by remember { mutableIntStateOf(-1) }
    val outline = MaterialTheme.colorScheme.primary
    val labels = listOf(
        stringResource(R.string.scan_corner_tl), stringResource(R.string.scan_corner_tr),
        stringResource(R.string.scan_corner_br), stringResource(R.string.scan_corner_bl),
    )
    val nudgeLabels = listOf(
        stringResource(R.string.scan_nudge_left), stringResource(R.string.scan_nudge_right),
        stringResource(R.string.scan_nudge_up), stringResource(R.string.scan_nudge_down),
    )
    val pictureLabel = stringResource(R.string.scan_image_description)

    Box(modifier = modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        BoxWithConstraints(modifier = Modifier.fillMaxWidth().aspectRatio(imageWidth.toFloat() / imageHeight)) {
            val widthPx = with(density) { maxWidth.toPx() }
            val scale = widthPx / imageWidth // display pixels per picture pixel
            val corners = QuadGeometry.corners(quad)

            Canvas(modifier = Modifier.fillMaxSize().semantics { contentDescription = pictureLabel }) {
                if (image != null) {
                    drawImage(image, dstSize = IntSize(size.width.roundToInt(), size.height.roundToInt()))
                } else {
                    drawRect(Color.Gray.copy(alpha = 0.3f))
                }
                val shape = Path().apply {
                    fillType = PathFillType.EvenOdd
                    addRect(androidx.compose.ui.geometry.Rect(0f, 0f, size.width, size.height))
                    moveTo(corners[0].x * scale, corners[0].y * scale)
                    for (i in 1..3) lineTo(corners[i].x * scale, corners[i].y * scale)
                    close()
                }
                drawPath(shape, Color.Black.copy(alpha = 0.45f))
                val edge = Path().apply {
                    moveTo(corners[0].x * scale, corners[0].y * scale)
                    for (i in 1..3) lineTo(corners[i].x * scale, corners[i].y * scale)
                    close()
                }
                drawPath(edge, Color.White, style = Stroke(width = 5.dp.toPx()))
                drawPath(edge, outline, style = Stroke(width = 2.5.dp.toPx()))
                if (active in 0..3 && image != null) {
                    drawLoupe(image, scale, corners[active], outline)
                }
            }

            corners.forEachIndexed { i, c ->
                val half = with(density) { HandleTouch.toPx() / 2 }
                Box(
                    modifier = Modifier
                        .offset { IntOffset((c.x * scale - half).roundToInt(), (c.y * scale - half).roundToInt()) }
                        .size(HandleTouch)
                        .semantics {
                            contentDescription = labels[i]
                            customActions = listOf(
                                CustomAccessibilityAction(nudgeLabels[0]) { nudge(currentQuad, i, -1, 0, imageWidth, imageHeight, currentChange) },
                                CustomAccessibilityAction(nudgeLabels[1]) { nudge(currentQuad, i, 1, 0, imageWidth, imageHeight, currentChange) },
                                CustomAccessibilityAction(nudgeLabels[2]) { nudge(currentQuad, i, 0, -1, imageWidth, imageHeight, currentChange) },
                                CustomAccessibilityAction(nudgeLabels[3]) { nudge(currentQuad, i, 0, 1, imageWidth, imageHeight, currentChange) },
                            )
                        }
                        .pointerInput(i, scale) {
                            detectDragGestures(
                                onDragStart = { active = i },
                                onDragEnd = { active = -1 },
                                onDragCancel = { active = -1 },
                            ) { change, drag ->
                                change.consume()
                                currentChange(
                                    CropEditing.moveCorner(currentQuad, i, drag.x / scale, drag.y / scale, imageWidth, imageHeight),
                                )
                            }
                        },
                    contentAlignment = Alignment.Center,
                ) {
                    Canvas(modifier = Modifier.size(HandleDot)) {
                        drawCircle(Color.White, radius = size.minDimension / 2)
                        drawCircle(outline, radius = size.minDimension / 2 - 2.5.dp.toPx(), style = Stroke(width = 3.dp.toPx()))
                    }
                }
            }
        }
    }
}

private fun nudge(q: Quad, index: Int, sx: Int, sy: Int, w: Int, h: Int, change: (Quad) -> Unit): Boolean {
    val step = CropEditing.nudgeStep(w, h)
    change(CropEditing.moveCorner(q, index, sx * step, sy * step, w, h))
    return true
}

/** A round magnifier of the picture around [corner], above it (below when there is no room above). */
private fun DrawScope.drawLoupe(image: ImageBitmap, scale: Float, corner: Pt, ring: Color) {
    val r = LoupeRadius.toPx()
    val cx = (corner.x * scale).coerceIn(r, (size.width - r).coerceAtLeast(r))
    val above = corner.y * scale - r * 2.4f
    val cy = if (above >= r) above else (corner.y * scale + r * 2.4f).coerceAtMost(size.height - r)
    val circle = Path().apply { addOval(androidx.compose.ui.geometry.Rect(Offset(cx, cy), r)) }
    val bitmapPerDisplay = image.width.toFloat() / size.width
    val half = r / LoupeZoom * bitmapPerDisplay
    val bx = corner.x * scale * bitmapPerDisplay
    val by = corner.y * scale * bitmapPerDisplay
    clipPath(circle) {
        drawRect(Color.Black, topLeft = Offset(cx - r, cy - r), size = Size(2 * r, 2 * r))
        drawImage(
            image,
            srcOffset = IntOffset((bx - half).roundToInt(), (by - half).roundToInt()),
            srcSize = IntSize((2 * half).roundToInt().coerceAtLeast(1), (2 * half).roundToInt().coerceAtLeast(1)),
            dstOffset = IntOffset((cx - r).roundToInt(), (cy - r).roundToInt()),
            dstSize = IntSize((2 * r).roundToInt(), (2 * r).roundToInt()),
        )
    }
    drawCircle(Color.White, radius = r, center = Offset(cx, cy), style = Stroke(width = 5.dp.toPx()))
    drawCircle(ring, radius = r, center = Offset(cx, cy), style = Stroke(width = 2.5.dp.toPx()))
    // Crosshair on the exact corner.
    drawLine(ring, Offset(cx - 10.dp.toPx(), cy), Offset(cx + 10.dp.toPx(), cy), strokeWidth = 1.5.dp.toPx())
    drawLine(ring, Offset(cx, cy - 10.dp.toPx()), Offset(cx, cy + 10.dp.toPx()), strokeWidth = 1.5.dp.toPx())
}

internal fun previewPicture(width: Int, height: Int): ImageBitmap {
    val bmp = ImageBitmap(width, height)
    val canvas = androidx.compose.ui.graphics.Canvas(bmp)
    val paint = androidx.compose.ui.graphics.Paint()
    paint.color = Color(0xFF7A8B99)
    canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), paint)
    paint.color = Color(0xFFF4F1E8)
    canvas.drawRect(width * 0.12f, height * 0.1f, width * 0.88f, height * 0.92f, paint)
    paint.color = Color(0xFF30363B)
    for (i in 0 until 9) canvas.drawRect(width * 0.2f, height * (0.2f + i * 0.08f), width * (0.8f - (i % 3) * 0.1f), height * (0.215f + i * 0.08f), paint)
    return bmp
}

@Preview(showBackground = true, widthDp = 360)
@Composable
private fun CropEditorPreview() {
    PdfaTheme(darkTheme = false) {
        val pic = remember { previewPicture(300, 400) }
        CropEditor(pic, 300, 400, Quad(Pt(36f, 40f), Pt(264f, 44f), Pt(266f, 368f), Pt(34f, 366f)), {})
    }
}

@Preview(showBackground = true, widthDp = 360, name = "Crop editor dark")
@Composable
private fun CropEditorDarkPreview() {
    PdfaTheme(darkTheme = true) {
        val pic = remember { previewPicture(300, 400) }
        CropEditor(pic, 300, 400, QuadGeometry.fullFrame(300, 400), {})
    }
}
