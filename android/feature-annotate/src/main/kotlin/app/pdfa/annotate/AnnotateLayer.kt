package app.pdfa.annotate

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.geometry.isSpecified
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.AwaitPointerEventScope
import androidx.compose.ui.input.pointer.PointerEvent
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.input.pointer.PointerInputScope
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.input.pointer.SuspendingPointerInputModifierNode
import androidx.compose.ui.node.DelegatingNode
import androidx.compose.ui.node.ModifierNodeElement
import androidx.compose.ui.node.PointerInputModifierNode
import androidx.compose.ui.platform.InspectorInfo
import androidx.compose.ui.unit.IntSize
import app.pdfa.engine.MarkupKind
import app.pdfa.engine.Pt
import kotlin.math.hypot
import kotlin.math.max

/**
 * The annotation layer, drawn above the pages (the viewer's overlay slot).
 *
 * Input: a stylus always uses the current tool (ink with the Pen, a text selection with the
 * Highlighter, a note with Note); a finger draws only with the Pen. Two fingers move and zoom the
 * page ([onTransform]). Every other finger gesture is left to the viewer underneath: this layer
 * shares its input with its siblings, and only consumes the gestures it takes. A finger is ignored
 * while a stylus is down or was seen in the last 500 ms (palm rejection).
 *
 * Drawing: the stroke in progress, and every [PendingMark] (not yet, or not, in the document), in
 * the page's place given by [geometry].
 */
@Composable
fun AnnotateLayer(
    controller: AnnotateController,
    geometry: PageGeometry,
    onTransform: (focusX: Float, focusY: Float, zoom: Float, panX: Float, panY: Float) -> Unit,
    onSelectText: (page: Int, from: Pt, to: Pt) -> Unit,
    modifier: Modifier = Modifier,
) {
    val state by controller.state.collectAsState()
    val live = remember { LiveStroke() }
    val env = remember(controller) { GestureEnv(controller, live) }
    env.geometry = rememberUpdatedState(geometry)
    env.onTransform = rememberUpdatedState(onTransform)
    env.onSelectText = rememberUpdatedState(onSelectText)
    val handler: suspend PointerInputScope.() -> Unit = remember(env) { { annotateGestures(env) } }
    Box(modifier.fillMaxSize().then(SharedPointerInputElement(handler))) {
        Canvas(Modifier.fillMaxSize().clipToBounds()) {
            for (mark in state.pending) {
                val frame = geometry.frame(mark.markup.page) ?: continue
                drawMark(mark, frame)
            }
            val f = live.frame.value
            if (f != null && live.points.isNotEmpty()) {
                val style = state.inkStyle
                drawInk(listOf(live.points), frame = f, color = rgbColor(style.rgb, style.opacity), widthPt = style.widthPt)
            }
        }
    }
}

/** The stroke being drawn, in page view space of [frame]'s page. */
internal class LiveStroke {
    val points: SnapshotStateList<Pt> = mutableStateListOf()
    val frame = mutableStateOf<PageFrame?>(null)

    fun clear() {
        points.clear()
        frame.value = null
    }
}

private class GestureEnv(val controller: AnnotateController, val live: LiveStroke) {
    val palm = PalmRejection()
    lateinit var geometry: State<PageGeometry>
    lateinit var onTransform: State<(Float, Float, Float, Float, Float) -> Unit>
    lateinit var onSelectText: State<(Int, Pt, Pt) -> Unit>
}

private fun PointerInputChange.isStylus(): Boolean = type == PointerType.Stylus || type == PointerType.Eraser

private suspend fun PointerInputScope.annotateGestures(env: GestureEnv) {
    awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
        val stylus = down.isStylus()
        if (stylus) env.palm.onStylus(down.uptimeMillis, true)
        val tool = env.controller.state.value.tool
        when {
            // The stylus' eraser end: the contract has no call to delete a markup.
            down.type == PointerType.Eraser -> swallow(env, down)
            stylus && tool == AnnotateTool.PEN -> ink(env, down)
            stylus && tool == AnnotateTool.HIGHLIGHTER -> selectText(env, down)
            stylus -> noteTap(env, down)
            !env.palm.fingerMayDraw(down.uptimeMillis) -> swallow(env, down)
            tool == AnnotateTool.PEN -> ink(env, down)
            else -> Unit // the viewer pans, taps and long-presses
        }
    }
}

private fun PointerEvent.consumeAll() = changes.forEach { it.consume() }

private fun GestureEnv.trackStylus(event: PointerEvent) {
    val s = event.changes.firstOrNull { it.isStylus() } ?: return
    palm.onStylus(s.uptimeMillis, s.pressed)
}

/** Consumes the gesture without doing anything with it. */
private suspend fun AwaitPointerEventScope.swallow(env: GestureEnv, down: PointerInputChange) {
    down.consume()
    while (true) {
        val e = awaitPointerEvent(PointerEventPass.Initial)
        env.trackStylus(e)
        e.consumeAll()
        if (e.changes.none { it.pressed }) return
    }
}

/** Two or more fingers: move and zoom the page until every finger is up. */
private suspend fun AwaitPointerEventScope.transform(env: GestureEnv, first: PointerEvent) {
    var e = first
    while (true) {
        val zoom = e.calculateZoom()
        val pan = e.calculatePan()
        val c = e.calculateCentroid(useCurrent = false)
        if (c.isSpecified && (zoom != 1f || pan != Offset.Zero)) env.onTransform.value(c.x, c.y, zoom, pan.x, pan.y)
        e.consumeAll()
        if (e.changes.none { it.pressed }) return
        e = awaitPointerEvent(PointerEventPass.Initial)
    }
}

@OptIn(ExperimentalComposeUiApi::class) // historical points: smoother strokes
private suspend fun AwaitPointerEventScope.ink(env: GestureEnv, down: PointerInputChange) {
    down.consume()
    val mapper = StrokeMapper.start(env.geometry.value, down.position.x, down.position.y)
    if (mapper == null) {
        swallow(env, down)
        return
    }
    val frame = mapper.frame
    val stylus = down.isStylus()
    val points = ArrayList<Pt>()
    env.controller.strokeStarted()
    env.live.points.clear()
    env.live.frame.value = frame

    fun add(o: Offset) {
        val p = mapper.map(o.x, o.y)
        points += p
        env.live.points.add(p)
    }

    fun finish() {
        env.controller.addStroke(frame.page, points.toList(), frame.scale, frame.widthPt, frame.heightPt)
        points.clear()
    }

    fun cancel() {
        env.live.clear()
        env.controller.strokeCancelled()
    }

    add(down.position)
    while (true) {
        val e = awaitPointerEvent(PointerEventPass.Initial)
        env.trackStylus(e)
        if (!stylus && e.changes.any { it.isStylus() }) {
            // A pen landed while a finger was drawing: the finger was the hand. Drop its stroke.
            cancel()
            swallow(env, down)
            return
        }
        if (!stylus && e.changes.count { it.pressed } > 1) {
            cancel()
            transform(env, e)
            return
        }
        val c = e.changes.firstOrNull { it.id == down.id }
        e.consumeAll()
        if (c == null) break
        for (h in c.historical) add(h.position)
        add(c.position)
        if (points.size >= MarkupLimits.MAX_POINTS_PER_GROUP) {
            // A very long stroke goes on as a new one from where this one stopped.
            val last = points.last()
            finish()
            points += last
        }
        if (!c.pressed) break
    }
    finish()
    env.live.clear()
    // Anything still down (a resting hand) is ignored until it lifts.
    if (currentEvent.changes.any { it.pressed }) swallow(env, down)
}

/** Stylus with the Highlighter: the text between the first and the last point is selected. */
private suspend fun AwaitPointerEventScope.selectText(env: GestureEnv, down: PointerInputChange) {
    down.consume()
    val mapper = StrokeMapper.start(env.geometry.value, down.position.x, down.position.y)
    if (mapper == null) {
        swallow(env, down)
        return
    }
    val from = mapper.map(down.position.x, down.position.y)
    var to = from
    while (true) {
        val e = awaitPointerEvent(PointerEventPass.Initial)
        env.trackStylus(e)
        val c = e.changes.firstOrNull { it.id == down.id }
        e.consumeAll()
        if (c == null) break
        to = mapper.map(c.position.x, c.position.y)
        if (!c.pressed) break
    }
    env.onSelectText.value(mapper.page, from, to)
    if (currentEvent.changes.any { it.pressed }) swallow(env, down)
}

/** Stylus with Note: a tap places the note. */
private suspend fun AwaitPointerEventScope.noteTap(env: GestureEnv, down: PointerInputChange) {
    down.consume()
    val mapper = StrokeMapper.start(env.geometry.value, down.position.x, down.position.y)
    var moved = false
    while (true) {
        val e = awaitPointerEvent(PointerEventPass.Initial)
        env.trackStylus(e)
        val c = e.changes.firstOrNull { it.id == down.id }
        e.consumeAll()
        if (c == null) break
        if (hypot(c.position.x - down.position.x, c.position.y - down.position.y) > viewConfiguration.touchSlop) moved = true
        if (!c.pressed) break
    }
    if (mapper != null && !moved) {
        val at = mapper.map(down.position.x, down.position.y)
        env.controller.placeNote(mapper.page, at, mapper.frame.widthPt, mapper.frame.heightPt)
    }
    if (currentEvent.changes.any { it.pressed }) swallow(env, down)
}

// ---- drawing ----

private fun DrawScope.drawMark(mark: PendingMark, frame: PageFrame) {
    val m = mark.markup
    val fade = if (mark.status == MarkStatus.FAILED) 0.5f else 1f
    when (m.kind) {
        MarkupKind.INK -> drawInk(m.strokes.map { it.points }, frame, rgbColor(m.rgb, m.opacity * fade), m.widthPt)
        MarkupKind.HIGHLIGHT -> {
            val color = rgbColor(m.rgb, (if (m.opacity >= 1f) 0.6f else m.opacity) * fade)
            for (r in m.rects) {
                drawRect(
                    color,
                    Offset(frame.toScreenX(r.left), frame.toScreenY(r.top)),
                    Size((r.right - r.left) * frame.scale, (r.bottom - r.top) * frame.scale),
                )
            }
        }
        MarkupKind.NOTE -> {
            val r = m.rects.firstOrNull() ?: return
            val topLeft = Offset(frame.toScreenX(r.left), frame.toScreenY(r.top))
            val size = Size((r.right - r.left) * frame.scale, (r.bottom - r.top) * frame.scale)
            drawRect(rgbColor(m.rgb, fade), topLeft, size)
            drawRect(Color.White.copy(alpha = 0.9f * fade), topLeft + Offset(size.width * 0.2f, size.height * 0.3f), Size(size.width * 0.6f, max(1f, size.height * 0.1f)))
            drawRect(Color.White.copy(alpha = 0.9f * fade), topLeft + Offset(size.width * 0.2f, size.height * 0.55f), Size(size.width * 0.4f, max(1f, size.height * 0.1f)))
        }
    }
}

private fun DrawScope.drawInk(strokes: List<List<Pt>>, frame: PageFrame, color: Color, widthPt: Float) {
    val width = max(1f, widthPt * frame.scale)
    for (s in strokes) {
        if (s.isEmpty()) continue
        val path = Path()
        path.moveTo(frame.toScreenX(s[0].x), frame.toScreenY(s[0].y))
        if (s.size == 1) path.lineTo(frame.toScreenX(s[0].x) + 0.1f, frame.toScreenY(s[0].y))
        for (i in 1 until s.size) path.lineTo(frame.toScreenX(s[i].x), frame.toScreenY(s[i].y))
        drawPath(path, color, style = Stroke(width = width, cap = StrokeCap.Round, join = StrokeJoin.Round))
    }
}

// ---- input shared with the viewer underneath ----

/**
 * `pointerInput` that does not hide the pages' own gestures: hit testing goes on to the siblings
 * below, so the viewer still pans and taps unless this layer consumed the event first (it does so in
 * the Initial pass, before the viewer's Main pass).
 */
private class SharedPointerInputElement(private val handler: suspend PointerInputScope.() -> Unit) :
    ModifierNodeElement<SharedPointerInputNode>() {
    override fun create() = SharedPointerInputNode(handler)

    override fun update(node: SharedPointerInputNode) {
        node.setHandler(handler)
    }

    override fun equals(other: Any?): Boolean = other is SharedPointerInputElement && other.handler === handler

    override fun hashCode(): Int = handler.hashCode()

    override fun InspectorInfo.inspectableProperties() {
        name = "sharedPointerInput"
    }
}

private class SharedPointerInputNode(handler: suspend PointerInputScope.() -> Unit) : DelegatingNode(), PointerInputModifierNode {
    private val input = delegate(SuspendingPointerInputModifierNode(handler))

    fun setHandler(handler: suspend PointerInputScope.() -> Unit) {
        if (input.pointerInputHandler !== handler) input.pointerInputHandler = handler
    }

    override fun onPointerEvent(pointerEvent: PointerEvent, pass: PointerEventPass, bounds: IntSize) =
        input.onPointerEvent(pointerEvent, pass, bounds)

    override fun onCancelPointerInput() = input.onCancelPointerInput()

    override fun sharePointerInputWithSiblings(): Boolean = true
}
