package app.pdfa.annotate

import app.pdfa.engine.MarkupKind
import app.pdfa.engine.NewMarkup
import app.pdfa.engine.Pt
import app.pdfa.engine.Rect
import app.pdfa.engine.Stroke
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/*
 * Annotation logic without Android types. Every coordinate here is page VIEW space (PDF points,
 * origin top-left, y down, /Rotate applied), the space the engine contract takes. A page rotated by
 * 90 or 270 degrees is simply wider than tall in this space: nothing here depends on the rotation.
 */

enum class AnnotateTool { PEN, HIGHLIGHTER, NOTE }

/** Pen line widths, in points. */
enum class LineWidth(val pt: Float) { THIN(1f), MEDIUM(2.5f), THICK(5f) }

/** A colour choice: 0xRRGGBB and the opacity it is written with. [name] is for TalkBack. */
data class Swatch(val rgb: Int, val opacity: Float, val name: String)

/**
 * Colours from the design tokens (docs/design/tokens.json). They are document colours: the same in
 * the light and the dark theme, because they are written into the PDF.
 */
object AnnotatePalette {
    /** `color.annotationInk`. */
    val ink: List<Swatch> = listOf(
        Swatch(0x14201F, 1f, "Black"),
        Swatch(0x0B6E6B, 1f, "Teal"),
        Swatch(0xB3261E, 1f, "Red"),
        Swatch(0x1F5FBF, 1f, "Blue"),
    )

    /** `color.light.highlight` at full strength, then the ink colours as light see-through tints. */
    val highlight: List<Swatch> = listOf(
        Swatch(0xFFF3B0, 1f, "Yellow"),
        Swatch(0x0B6E6B, 0.35f, "Teal"),
        Swatch(0xB3261E, 0.35f, "Red"),
        Swatch(0x1F5FBF, 0.35f, "Blue"),
    )

    fun forTool(tool: AnnotateTool): List<Swatch> = if (tool == AnnotateTool.HIGHLIGHTER) highlight else ink
}

/** Limits the engine enforces (rust/pdfa-ffi `add_markup`), kept here so a request never fails on them. */
object MarkupLimits {
    /** Points in one markup, all strokes together. The engine refuses more than 100 000. */
    const val MAX_POINTS_PER_GROUP: Int = 100_000

    /** Note text, in UTF-8 bytes. The engine refuses more than 100 000. */
    const val MAX_NOTE_BYTES: Int = 100_000

    /** What the note field accepts, in characters (4 bytes each at most: well under the byte limit). */
    const val MAX_NOTE_CHARS: Int = 10_000

    /** The note icon is drawn this many points wide and tall from its anchor. */
    const val NOTE_ICON_PT: Float = 20f

    /** Line width sent with a highlight or a note: the engine ignores it, the contract wants it positive. */
    const val NEUTRAL_WIDTH_PT: Float = 1f
}

// ---- screen to page ----

/**
 * Where one page sits on the surface right now: screen = [left] + x * [scale], [top] + y * [scale].
 * [widthPt] x [heightPt] is the page in view space.
 */
data class PageFrame(val page: Int, val left: Float, val top: Float, val scale: Float, val widthPt: Float, val heightPt: Float) {
    val valid: Boolean
        get() = scale > 0f && scale.isFinite() && left.isFinite() && top.isFinite() && widthPt > 0f && heightPt > 0f

    fun toScreenX(xPt: Float): Float = left + xPt * scale
    fun toScreenY(yPt: Float): Float = top + yPt * scale
}

/** The viewer's geometry, as the annotation layer needs it. The app implements it with the viewer's layout. */
interface PageGeometry {
    /** The page under surface pixel ([px], [py]), or null for a gap, a margin or empty space. */
    fun pageAt(px: Float, py: Float): Int?

    /** Where [page] is on the surface now, or null when there is no layout. */
    fun frame(page: Int): PageFrame?
}

/**
 * Maps the surface pixels of ONE stroke to its starting page. A stroke that leaves the page (into
 * the gap or onto the next page) is clamped to the page's edges: it never jumps to another page.
 */
class StrokeMapper private constructor(val frame: PageFrame) {
    val page: Int get() = frame.page

    fun map(px: Float, py: Float): Pt = Pt(
        ((px - frame.left) / frame.scale).coerceIn(0f, frame.widthPt),
        ((py - frame.top) / frame.scale).coerceIn(0f, frame.heightPt),
    )

    companion object {
        /** Null when the stroke does not start on a page. */
        fun start(geometry: PageGeometry, px: Float, py: Float): StrokeMapper? {
            if (!px.isFinite() || !py.isFinite()) return null
            val page = geometry.pageAt(px, py) ?: return null
            val frame = geometry.frame(page) ?: return null
            return if (frame.valid && frame.page == page) StrokeMapper(frame) else null
        }
    }
}

// ---- simplification ----

object StrokeSimplifier {
    /** Surface pixels a simplified stroke may stray from the drawn one. */
    const val TOLERANCE_PX: Float = 0.75f

    /** The tolerance in points for a page drawn at [scale] px per point, kept within sane bounds. */
    fun tolerancePt(scale: Float): Float =
        if (scale > 0f && scale.isFinite()) (TOLERANCE_PX / scale).coerceIn(0.02f, 2f) else 0.25f

    /**
     * Drops points closer than half the tolerance to the last kept one, then Ramer-Douglas-Peucker
     * with [tolerance] points. The first and last points are always kept. Iterative: a 100 000 point
     * stroke does not overflow the stack.
     */
    fun simplify(points: List<Pt>, tolerance: Float): List<Pt> {
        val clean = points.filter { it.x.isFinite() && it.y.isFinite() }
        if (clean.size <= 2) return clean
        val dedup = ArrayList<Pt>(clean.size)
        val minStep = tolerance / 2f
        for (p in clean) {
            val last = dedup.lastOrNull()
            if (last == null || hypot(p.x - last.x, p.y - last.y) >= minStep) dedup += p
        }
        // Keep the real end point even when it was within minStep of the previous one.
        if (dedup.last() != clean.last()) {
            if (dedup.size > 1) dedup[dedup.lastIndex] = clean.last() else dedup += clean.last()
        }
        if (dedup.size <= 2) return dedup
        val keep = BooleanArray(dedup.size)
        keep[0] = true
        keep[dedup.lastIndex] = true
        val stack = ArrayDeque<IntArray>()
        stack.addLast(intArrayOf(0, dedup.lastIndex))
        while (stack.isNotEmpty()) {
            val (a, b) = stack.removeLast().let { it[0] to it[1] }
            if (b - a < 2) continue
            var worst = -1f
            var at = -1
            for (i in a + 1 until b) {
                val d = distanceToSegment(dedup[i], dedup[a], dedup[b])
                if (d > worst) {
                    worst = d
                    at = i
                }
            }
            if (worst > tolerance) {
                keep[at] = true
                stack.addLast(intArrayOf(a, at))
                stack.addLast(intArrayOf(at, b))
            }
        }
        return dedup.filterIndexed { i, _ -> keep[i] }
    }

    fun distanceToSegment(p: Pt, a: Pt, b: Pt): Float {
        val dx = b.x - a.x
        val dy = b.y - a.y
        val len2 = dx * dx + dy * dy
        if (len2 == 0f) return hypot(p.x - a.x, p.y - a.y)
        val t = (((p.x - a.x) * dx + (p.y - a.y) * dy) / len2).coerceIn(0f, 1f)
        return hypot(p.x - (a.x + t * dx), p.y - (a.y + t * dy))
    }
}

/**
 * The points one stroke sends: simplified, at least two (the engine refuses a one-point stroke, so
 * a tap becomes a short dot inside the page), and split into pieces of at most [maxPoints] that
 * share their joining point. Empty when there was nothing to draw.
 */
fun prepareStroke(raw: List<Pt>, tolerance: Float, pageWidth: Float, pageHeight: Float, maxPoints: Int = MarkupLimits.MAX_POINTS_PER_GROUP): List<List<Pt>> {
    require(maxPoints >= 2)
    val simple = StrokeSimplifier.simplify(raw, tolerance)
    if (simple.isEmpty()) return emptyList()
    val pts = if (simple.size == 1 || simple.all { it == simple[0] }) {
        val p = simple[0]
        val dx = if (p.x + DOT_PT <= pageWidth) DOT_PT else -DOT_PT
        listOf(p, Pt(p.x + dx, p.y))
    } else {
        simple
    }
    if (pts.size <= maxPoints) return listOf(pts)
    val out = ArrayList<List<Pt>>()
    var start = 0
    while (start < pts.lastIndex) {
        val end = min(start + maxPoints, pts.size)
        out += pts.subList(start, end).toList()
        start = end - 1 // the next piece starts where this one ended
    }
    return out
}

private const val DOT_PT = 0.5f

// ---- grouping ----

/** How a group of strokes is drawn. */
data class InkStyle(val rgb: Int, val opacity: Float, val widthPt: Float)

/** Strokes on one page in one style, sent as ONE ink markup: one undo step removes them all. */
data class StrokeGroup(val page: Int, val style: InkStyle, val strokes: List<List<Pt>>) {
    val pointCount: Int get() = strokes.sumOf { it.size }

    fun canTake(page: Int, style: InkStyle, points: Int, maxPoints: Int = MarkupLimits.MAX_POINTS_PER_GROUP): Boolean =
        page == this.page && style == this.style && pointCount + points <= maxPoints

    fun plus(stroke: List<Pt>): StrokeGroup = copy(strokes = strokes + listOf(stroke))

    fun toMarkup(): NewMarkup = NewMarkup(
        page = page,
        kind = MarkupKind.INK,
        rgb = style.rgb,
        opacity = style.opacity,
        widthPt = style.widthPt,
        strokes = strokes.map { Stroke(it) },
    )
}

// ---- palm rejection ----

/**
 * While a stylus is down, or was seen in the last [windowMs], finger touches do not draw: they are
 * most likely the hand resting on the screen.
 */
class PalmRejection(private val windowMs: Long = 500L) {
    private var stylusDown = false
    private var lastStylusMs: Long? = null

    /** Any stylus event: [pressed] is whether it touches the screen now. */
    fun onStylus(timeMs: Long, pressed: Boolean) {
        stylusDown = pressed
        lastStylusMs = timeMs
    }

    fun fingerMayDraw(nowMs: Long): Boolean {
        if (stylusDown) return false
        val last = lastStylusMs ?: return true
        return nowMs - last > windowMs
    }
}

// ---- highlight and note ----

/**
 * One rect per line of a text selection, clamped to the page, degenerate ones dropped, top to
 * bottom. The engine turns each into a quad.
 */
fun highlightRects(rects: List<Rect>, pageWidth: Float, pageHeight: Float): List<Rect> =
    rects.mapNotNull { r ->
        if (!(r.left.isFinite() && r.top.isFinite() && r.right.isFinite() && r.bottom.isFinite())) return@mapNotNull null
        val l = min(r.left, r.right).coerceIn(0f, pageWidth)
        val rr = max(r.left, r.right).coerceIn(0f, pageWidth)
        val t = min(r.top, r.bottom).coerceIn(0f, pageHeight)
        val b = max(r.top, r.bottom).coerceIn(0f, pageHeight)
        if (rr - l < MIN_RECT_PT || b - t < MIN_RECT_PT) null else Rect(l, t, rr, b)
    }.sortedWith(compareBy({ it.top }, { it.left }))

private const val MIN_RECT_PT = 0.25f

fun highlightMarkup(page: Int, rects: List<Rect>, swatch: Swatch): NewMarkup = NewMarkup(
    page = page,
    kind = MarkupKind.HIGHLIGHT,
    rgb = swatch.rgb,
    opacity = swatch.opacity,
    widthPt = MarkupLimits.NEUTRAL_WIDTH_PT,
    rects = rects,
)

/** The note's anchor rect: its top-left at the tapped point, moved in so the icon stays on the page. */
fun noteRect(at: Pt, pageWidth: Float, pageHeight: Float, icon: Float = MarkupLimits.NOTE_ICON_PT): Rect {
    val w = min(icon, pageWidth)
    val h = min(icon, pageHeight)
    val x = at.x.coerceIn(0f, max(0f, pageWidth - w))
    val y = at.y.coerceIn(0f, max(0f, pageHeight - h))
    return Rect(x, y, x + w, y + h)
}

/** Note text the engine accepts: at most [MarkupLimits.MAX_NOTE_CHARS] and [MarkupLimits.MAX_NOTE_BYTES]. */
fun clampNoteText(text: String): String {
    var t = text.take(MarkupLimits.MAX_NOTE_CHARS)
    while (t.encodeToByteArray().size > MarkupLimits.MAX_NOTE_BYTES) t = t.dropLast(1)
    // Never end on half a surrogate pair.
    if (t.isNotEmpty() && t.last().isHighSurrogate()) t = t.dropLast(1)
    return t
}

fun noteMarkup(page: Int, rect: Rect, text: String, swatch: Swatch): NewMarkup = NewMarkup(
    page = page,
    kind = MarkupKind.NOTE,
    rgb = swatch.rgb,
    opacity = 1f,
    widthPt = MarkupLimits.NEUTRAL_WIDTH_PT,
    rects = listOf(rect),
    contents = clampNoteText(text),
)
