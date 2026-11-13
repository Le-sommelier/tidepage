package app.pdfa.scan

import app.pdfa.engine.Pt
import app.pdfa.engine.Quad
import kotlin.math.abs
import kotlin.math.atan2

/**
 * Corner maths for the crop step. Pure Kotlin. Image space: origin top-left, y down, so a quad
 * listed top-left, top-right, bottom-right, bottom-left runs clockwise on screen and every
 * consecutive cross product is positive.
 */
object QuadGeometry {
    /** A quad smaller than this share of the frame is not a document, it is noise. */
    const val MIN_AREA_FRACTION = 0.05

    /** Every edge must be at least this many pixels long. */
    const val MIN_EDGE_PX = 8f

    fun fullFrame(width: Int, height: Int): Quad {
        val w = width.toFloat()
        val h = height.toFloat()
        return Quad(Pt(0f, 0f), Pt(w, 0f), Pt(w, h), Pt(0f, h))
    }

    /**
     * Puts any four points in the order top-left, top-right, bottom-right, bottom-left: sorted
     * clockwise around their centre, then started at the point nearest the frame's top-left
     * (smallest x + y; ties go to the smaller y, then the smaller x). A square turned 45 degrees
     * therefore starts at its top vertex.
     */
    fun orderCorners(points: List<Pt>): Quad {
        require(points.size == 4) { "a quad has four corners, got ${points.size}" }
        val cx = points.sumOf { it.x.toDouble() } / 4.0
        val cy = points.sumOf { it.y.toDouble() } / 4.0
        // With y down, increasing atan2 is clockwise on screen: left, top, right, bottom.
        val clockwise = points.sortedWith(
            compareBy<Pt> { atan2(it.y - cy, it.x - cx) }
                .thenBy { (it.x - cx) * (it.x - cx) + (it.y - cy) * (it.y - cy) },
        )
        val start = clockwise.indices.minWithOrNull(
            compareBy<Int> { clockwise[it].x + clockwise[it].y }
                .thenBy { clockwise[it].y }
                .thenBy { clockwise[it].x },
        ) ?: 0
        val o = List(4) { clockwise[(start + it) % 4] }
        return Quad(o[0], o[1], o[2], o[3])
    }

    fun corners(q: Quad): List<Pt> = listOf(q.topLeft, q.topRight, q.bottomRight, q.bottomLeft)

    /** Twice the signed area (shoelace); positive for the clockwise on-screen order. */
    fun signedArea2(q: Quad): Double {
        val c = corners(q)
        var sum = 0.0
        for (i in 0 until 4) {
            val a = c[i]
            val b = c[(i + 1) % 4]
            sum += a.x.toDouble() * b.y - b.x.toDouble() * a.y
        }
        return sum
    }

    fun area(q: Quad): Double = abs(signedArea2(q)) / 2.0

    /** Strictly convex and clockwise: no collinear or repeated corners, no bow-tie. */
    fun isConvexClockwise(q: Quad): Boolean {
        val c = corners(q)
        if (c.any { !it.x.isFinite() || !it.y.isFinite() }) return false
        for (i in 0 until 4) {
            val a = c[i]
            val b = c[(i + 1) % 4]
            val d = c[(i + 2) % 4]
            val cross = (b.x - a.x).toDouble() * (d.y - b.y) - (b.y - a.y).toDouble() * (d.x - b.x)
            if (cross <= 0.0) return false
        }
        return true
    }

    fun clamp(q: Quad, width: Int, height: Int): Quad {
        fun c(p: Pt) = Pt(p.x.coerceIn(0f, width.toFloat()), p.y.coerceIn(0f, height.toFloat()))
        return Quad(c(q.topLeft), c(q.topRight), c(q.bottomRight), c(q.bottomLeft))
    }

    /**
     * The quad clamped into the frame, or null when it is not usable: not convex, an edge shorter
     * than [MIN_EDGE_PX], or smaller than [MIN_AREA_FRACTION] of the frame. Expects corners
     * already in order (see [orderCorners]).
     */
    fun validate(q: Quad, width: Int, height: Int): Quad? {
        if (width <= 0 || height <= 0) return null
        if (corners(q).any { !it.x.isFinite() || !it.y.isFinite() }) return null
        val clamped = clamp(q, width, height)
        if (!isConvexClockwise(clamped)) return null
        val c = corners(clamped)
        for (i in 0 until 4) {
            val a = c[i]
            val b = c[(i + 1) % 4]
            if (kotlin.math.hypot((b.x - a.x).toDouble(), (b.y - a.y).toDouble()) < MIN_EDGE_PX) return null
        }
        if (area(clamped) < MIN_AREA_FRACTION * width.toDouble() * height) return null
        return clamped
    }

    /** Orders, then validates. Null when [q] is null or unusable. */
    fun sanitize(q: Quad?, width: Int, height: Int): Quad? =
        q?.let { validate(orderCorners(corners(it)), width, height) }

    /** The detected quad when usable, else the whole frame. */
    fun orFullFrame(detected: Quad?, width: Int, height: Int): Quad =
        sanitize(detected, width, height) ?: fullFrame(width, height)

    /** Scales a quad from a frame of ([fromW], [fromH]) to one of ([toW], [toH]). */
    fun scale(q: Quad, fromW: Int, fromH: Int, toW: Int, toH: Int): Quad {
        val sx = toW.toFloat() / fromW
        val sy = toH.toFloat() / fromH
        fun s(p: Pt) = Pt(p.x * sx, p.y * sy)
        return Quad(s(q.topLeft), s(q.topRight), s(q.bottomRight), s(q.bottomLeft))
    }
}

/** Dragging one corner of the crop, in image pixels. Pure Kotlin. */
object CropEditing {
    const val TOP_LEFT = 0
    const val TOP_RIGHT = 1
    const val BOTTOM_RIGHT = 2
    const val BOTTOM_LEFT = 3

    /** [quad] with corner [index] moved by ([dx], [dy]) and kept inside the frame. */
    fun moveCorner(quad: Quad, index: Int, dx: Float, dy: Float, width: Int, height: Int): Quad {
        require(index in 0..3) { "corner index $index" }
        val c = QuadGeometry.corners(quad).toMutableList()
        val p = c[index]
        c[index] = Pt((p.x + dx).coerceIn(0f, width.toFloat()), (p.y + dy).coerceIn(0f, height.toFloat()))
        return Quad(c[0], c[1], c[2], c[3])
    }

    /** One keyboard or TalkBack nudge: about 0.5% of the longer side, at least one pixel. */
    fun nudgeStep(width: Int, height: Int): Float = maxOf(1f, maxOf(width, height) * 0.005f)

    /** True when the corners as dragged outline a usable page (see [QuadGeometry.validate]). */
    fun isUsable(quad: Quad, width: Int, height: Int): Boolean = QuadGeometry.validate(quad, width, height) != null
}
