package app.pdfa.viewer.ui

import androidx.compose.animation.core.AnimationState
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.animateDecay
import androidx.compose.animation.core.tween
import androidx.compose.animation.splineBasedDecay
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculateCentroidSize
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.isSpecified
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.PointerEvent
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import app.pdfa.design.PdfaColors
import app.pdfa.feature.viewer.R
import app.pdfa.viewer.PlannedTile
import app.pdfa.viewer.ViewerViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.roundToInt

private const val MIN_FLING_SPEED = 200f

/** The reading surface: white sheets with tiles, pinch, pan, fling and double tap. */
@Composable
fun PageSurface(
    viewModel: ViewerViewModel,
    modifier: Modifier = Modifier,
    onTap: (Offset) -> Unit = {},
    onLongPress: (Offset) -> Unit = {},
) {
    val onTapNow by rememberUpdatedState(onTap)
    val onLongPressNow by rememberUpdatedState(onLongPress)
    val view = viewModel.view.collectAsState()
    val failed = viewModel.failedTiles.collectAsState()
    val store = remember(viewModel) { BitmapStore(viewModel.budget.bitmapBytes) }
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current
    var frame by remember { mutableIntStateOf(0) }
    val converting = remember { HashSet<app.pdfa.viewer.TileKey>() }
    val sheet = PdfaColors.readingSheet
    val hatch = MaterialTheme.colorScheme.outline
    val page by remember { derivedStateOf { view.value.currentPage } }
    val count by remember { derivedStateOf { view.value.layout?.count ?: 0 } }

    fun requestConvert(key: app.pdfa.viewer.TileKey) {
        if (key in converting || store.get(key) != null) return
        val tile = viewModel.tiles.get(key) ?: return
        converting += key
        scope.launch {
            try {
                val entry = withContext(Dispatchers.Default) { store.convert(tile) }
                store.put(key, entry)
                frame++
            } finally {
                converting -= key
            }
        }
    }

    LaunchedEffect(viewModel) { viewModel.tileReady.collect { requestConvert(it) } }
    // After an edit every bitmap shows the old document: drop them, the scheduler renders again.
    val generation by viewModel.contentGeneration.collectAsState()
    LaunchedEffect(generation) {
        if (generation > 0) {
            store.clear()
            // The plan made by the view-model ran while the old bitmaps still counted as resident.
            viewModel.refreshTiles()
            frame++
        }
    }
    androidx.compose.runtime.DisposableEffect(store) {
        // The scheduler must not re-render tiles that are already resident as bitmaps.
        viewModel.residentCheck = { store.contains(it) }
        onDispose {
            viewModel.residentCheck = { false }
            store.clear()
        }
    }
    val convertFn: (app.pdfa.viewer.TileKey) -> Unit = remember(store) { { key -> requestConvert(key) } }

    val fling = remember { arrayOfNulls<Job>(1) }
    val desc = stringResource(R.string.viewer_page_description, page + 1, count)
    val nextLabel = stringResource(R.string.viewer_next_page)
    val prevLabel = stringResource(R.string.viewer_previous_page)

    Box(
        modifier
            .fillMaxSize()
            // Pages above and below the viewport are drawn too: without the clip they paint over the top bar.
            .clipToBounds()
            .onSizeChanged { viewModel.onViewportChanged(it.width, it.height, density.density) }
            .semantics {
                contentDescription = desc
                customActions = listOf(
                    CustomAccessibilityAction(nextLabel) { viewModel.jumpToPage(page + 1); true },
                    CustomAccessibilityAction(prevLabel) { viewModel.jumpToPage(page - 1); true },
                )
            }
            .pointerInput(viewModel) {
                detectTapGestures(onTap = { onTapNow(it) }, onLongPress = { onLongPressNow(it) }, onDoubleTap = { p ->
                    fling[0]?.cancel()
                    val start = viewModel.view.value.pos.zoom
                    val target = viewModel.doubleTapTarget()
                    fling[0] = scope.launch {
                        animate(0f, 1f, animationSpec = tween(150)) { f, _ ->
                            viewModel.zoomTo(start + (target - start) * f, p.x, p.y)
                        }
                    }
                })
            }
            .pointerInput(viewModel) {
                val slop = viewConfiguration.touchSlop
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false)
                    fling[0]?.cancel()
                    var zoom = 1f
                    var pan = Offset.Zero
                    var pastSlop = false
                    var multi = false
                    val tracker = VelocityTracker()
                    do {
                        val event: PointerEvent = awaitPointerEvent()
                        if (event.changes.count { it.pressed } > 1) multi = true
                        val cancelled = event.changes.any { it.isConsumed }
                        if (!cancelled) {
                            val zoomChange = event.calculateZoom()
                            val panChange = event.calculatePan()
                            if (!pastSlop) {
                                zoom *= zoomChange
                                pan += panChange
                                val size = event.calculateCentroidSize(useCurrent = false)
                                if (abs(1 - zoom) * size > slop || pan.getDistance() > slop) pastSlop = true
                            }
                            if (pastSlop) {
                                val c = event.calculateCentroid(useCurrent = false)
                                if (zoomChange != 1f || panChange != Offset.Zero) {
                                    viewModel.transformBy(c.x, c.y, zoomChange, panChange.x, panChange.y)
                                }
                                val cur = event.calculateCentroid(useCurrent = true)
                                if (cur.isSpecified) tracker.addPosition(event.changes[0].uptimeMillis, cur)
                                event.changes.forEach { if (it.positionChanged()) it.consume() }
                            }
                        }
                    } while (!cancelled && event.changes.any { it.pressed })
                    if (pastSlop && !multi) {
                        val v = tracker.calculateVelocity()
                        val speed = hypot(v.x, v.y)
                        if (speed > MIN_FLING_SPEED) {
                            fling[0] = scope.launch {
                                var last = 0f
                                val dx = v.x / speed
                                val dy = v.y / speed
                                AnimationState(0f, speed).animateDecay(splineBasedDecay<Float>(density)) {
                                    val d = value - last
                                    last = value
                                    if (!viewModel.panBy(dx * d, dy * d)) cancelAnimation()
                                }
                            }
                        }
                    }
                }
            },
    ) {
        Canvas(Modifier.fillMaxSize()) {
            frame // redraw when a bitmap arrives
            store.beginFrame()
            val v = view.value
            val layout = v.layout ?: return@Canvas
            val z = v.pos.zoom
            val ox = layout.pageScreenLeft(v.pos)
            val pages = v.plan.pages
            val failedNow = failed.value
            for (i in pages.indices) {
                val pp = pages[i]
                val oy = layout.pageScreenTop(pp.page, v.pos)
                val target = layout.fitScale(pp.page) * z
                drawRect(sheet, Offset(ox, oy), Size(layout.pageWidth * z, (layout.pageHeight(pp.page) * z).toFloat()))

                val low = pp.low
                if (low != null) drawTile(store, low, ox, oy, target, convertFn)

                val sharp = pp.sharp
                var missing = false
                for (j in sharp.indices) {
                    if (store.get(sharp[j].key) == null) { missing = true; break }
                }
                if (missing) {
                    val fb = pp.fallback
                    for (j in fb.indices) drawTile(store, fb[j], ox, oy, target, null)
                }
                for (j in sharp.indices) {
                    val t = sharp[j]
                    if (!drawTile(store, t, ox, oy, target, convertFn) && t.key in failedNow) {
                        drawPlaceholder(t, ox, oy, target, hatch)
                    }
                }
            }
        }
    }
}

private fun DrawScope.drawTile(
    store: BitmapStore,
    t: PlannedTile,
    ox: Float,
    oy: Float,
    target: Float,
    convert: ((app.pdfa.viewer.TileKey) -> Unit)?,
): Boolean {
    val entry = store.get(t.key)
    if (entry == null) {
        convert?.invoke(t.key)
        return false
    }
    val l = (ox + t.left * target).roundToInt()
    val tp = (oy + t.top * target).roundToInt()
    val r = (ox + t.right * target).roundToInt()
    val b = (oy + t.bottom * target).roundToInt()
    drawImage(
        image = entry.image,
        srcOffset = IntOffset.Zero,
        srcSize = IntSize(entry.bitmap.width, entry.bitmap.height),
        dstOffset = IntOffset(l, tp),
        dstSize = IntSize((r - l).coerceAtLeast(1), (b - tp).coerceAtLeast(1)),
        filterQuality = FilterQuality.Medium,
    )
    return true
}

private fun DrawScope.drawPlaceholder(t: PlannedTile, ox: Float, oy: Float, target: Float, color: androidx.compose.ui.graphics.Color) {
    val l = ox + t.left * target
    val tp = oy + t.top * target
    val w = (t.right - t.left) * target
    val h = (t.bottom - t.top) * target
    drawRect(color.copy(alpha = 0.12f), Offset(l, tp), Size(w, h))
    drawRect(color.copy(alpha = 0.5f), Offset(l, tp), Size(w, h), style = Stroke(2f))
    drawLine(color.copy(alpha = 0.5f), Offset(l, tp), Offset(l + w, tp + h), 2f)
    drawLine(color.copy(alpha = 0.5f), Offset(l + w, tp), Offset(l, tp + h), 2f)
}
