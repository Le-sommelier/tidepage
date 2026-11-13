package app.pdfa.annotate

import app.pdfa.engine.MarkupKind
import app.pdfa.engine.Pt
import app.pdfa.engine.Rect
import org.junit.Test
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AnnotateModelTest {

    // ---- simplification ----

    @Test
    fun aStraightLineCollapsesToItsTwoEnds() {
        val line = (0..1000).map { Pt(it * 0.1f, 50f + it * 0.05f) }
        val out = StrokeSimplifier.simplify(line, 0.25f)
        assertEquals(listOf(line.first(), line.last()), out)
    }

    @Test
    fun aCurveKeepsItsShapeWithinTheTolerance() {
        val tol = 0.3f
        val circle = (0..2000).map { i ->
            val a = 2 * PI * i / 2000
            Pt((100 + 50 * cos(a)).toFloat(), (100 + 50 * sin(a)).toFloat())
        }
        val out = StrokeSimplifier.simplify(circle, tol)
        assertTrue(out.size in 10 until 500, "far fewer points, still a curve: ${out.size}")
        assertEquals(circle.first(), out.first())
        assertEquals(circle.last(), out.last())
        // Every original point lies within the tolerance of the simplified polyline.
        for (p in circle) {
            val d = out.zipWithNext().minOf { (a, b) -> StrokeSimplifier.distanceToSegment(p, a, b) }
            assertTrue(d <= tol + 1e-3f, "point $p is $d from the simplified stroke")
        }
    }

    @Test
    fun nearDuplicatePointsAndNonFiniteOnesAreDropped() {
        val pts = listOf(Pt(10f, 10f), Pt(10.01f, 10f), Pt(Float.NaN, 3f), Pt(10.02f, 10.01f), Pt(30f, 10f))
        val out = StrokeSimplifier.simplify(pts, 0.25f)
        assertEquals(listOf(Pt(10f, 10f), Pt(30f, 10f)), out)
    }

    @Test
    fun aHundredThousandPointZigzagDoesNotOverflowTheStack() {
        val zig = (0 until 100_000).map { Pt(it * 0.01f, if (it % 2 == 0) 0f else 5f) }
        val out = StrokeSimplifier.simplify(zig, 0.25f)
        assertTrue(out.size > 1000)
    }

    @Test
    fun toleranceFollowsTheZoom() {
        assertEquals(0.75f, StrokeSimplifier.tolerancePt(1f), 1e-6f)
        assertTrue(StrokeSimplifier.tolerancePt(10f) < StrokeSimplifier.tolerancePt(2f))
        assertEquals(0.25f, StrokeSimplifier.tolerancePt(Float.NaN))
    }

    // ---- prepareStroke: what the engine accepts ----

    @Test
    fun aTapBecomesATwoPointDotInsideThePage() {
        val dot = prepareStroke(listOf(Pt(50f, 50f)), 0.25f, 100f, 100f).single()
        assertEquals(2, dot.size)
        assertTrue(dot[0] != dot[1])
        val atEdge = prepareStroke(listOf(Pt(100f, 50f), Pt(100f, 50f)), 0.25f, 100f, 100f).single()
        assertTrue(atEdge.all { it.x in 0f..100f }, "$atEdge")
        assertTrue(prepareStroke(emptyList(), 0.25f, 100f, 100f).isEmpty())
    }

    @Test
    fun aStrokeLongerThanTheCapIsSplitIntoJoinedPieces() {
        val zig = (0 until 25).map { Pt(it.toFloat(), if (it % 2 == 0) 0f else 10f) }
        val pieces = prepareStroke(zig, 0.1f, 100f, 100f, maxPoints = 10)
        assertTrue(pieces.all { it.size in 2..10 })
        for ((a, b) in pieces.zipWithNext()) assertEquals(a.last(), b.first())
        assertEquals(zig, pieces.flatMapIndexed { i, p -> if (i == 0) p else p.drop(1) })
    }

    // ---- mapping and clipping ----

    /** Two pages stacked, the second landscape: like a rotated page in view space. */
    private val geometry = object : PageGeometry {
        val frames = listOf(
            PageFrame(0, left = 0f, top = 8f, scale = 2f, widthPt = 100f, heightPt = 150f),
            PageFrame(1, left = 0f, top = 316f, scale = 2f, widthPt = 100f, heightPt = 60f),
        )

        override fun pageAt(px: Float, py: Float): Int? =
            frames.firstOrNull { px in it.left..(it.left + it.widthPt * it.scale) && py in it.top..(it.top + it.heightPt * it.scale) }?.page

        override fun frame(page: Int): PageFrame? = frames.getOrNull(page)
    }

    @Test
    fun aStrokeMapsToItsStartingPageAndIsClampedThere() {
        val m = assertNotNull(StrokeMapper.start(geometry, 20f, 28f))
        assertEquals(0, m.page)
        assertEquals(Pt(10f, 10f), m.map(20f, 28f))
        // Into the gap and onto page 2: clamped to the bottom of page 1.
        assertEquals(Pt(50f, 150f), m.map(100f, 320f))
        // Off the left and right edges.
        assertEquals(Pt(0f, 10f), m.map(-50f, 28f))
        assertEquals(Pt(100f, 10f), m.map(900f, 28f))
    }

    @Test
    fun aStrokeStartingInAGapOrOffThePagesIsNotMapped() {
        assertNull(StrokeMapper.start(geometry, 20f, 4f))
        assertNull(StrokeMapper.start(geometry, 20f, 312f))
        assertNull(StrokeMapper.start(geometry, Float.NaN, 20f))
        val second = assertNotNull(StrokeMapper.start(geometry, 20f, 326f))
        assertEquals(1, second.page)
        assertEquals(Pt(10f, 5f), second.map(20f, 326f))
        assertEquals(Pt(10f, 0f), second.map(20f, 100f), "a stroke going up stays on page 2")
    }

    // ---- grouping ----

    @Test
    fun aGroupTakesStrokesOfItsPageAndStyleUpToTheCap() {
        val style = InkStyle(0xB3261E, 1f, 2.5f)
        val g = StrokeGroup(0, style, listOf(listOf(Pt(0f, 0f), Pt(1f, 1f))))
        assertTrue(g.canTake(0, style, 10))
        assertFalse(g.canTake(1, style, 10), "another page")
        assertFalse(g.canTake(0, style.copy(widthPt = 5f), 10), "another width")
        assertFalse(g.canTake(0, style, MarkupLimits.MAX_POINTS_PER_GROUP - 1), "over the point cap")
        val m = g.plus(listOf(Pt(2f, 2f), Pt(3f, 3f))).toMarkup()
        assertEquals(MarkupKind.INK, m.kind)
        assertEquals(2, m.strokes.size)
        assertEquals(0xB3261E, m.rgb)
        assertEquals(2.5f, m.widthPt)
    }

    // ---- palm rejection ----

    @Test
    fun fingersDoNotDrawWhileAStylusIsDownOrWasJustSeen() {
        val palm = PalmRejection(windowMs = 500)
        assertTrue(palm.fingerMayDraw(0))
        palm.onStylus(1_000, pressed = true)
        assertFalse(palm.fingerMayDraw(5_000), "stylus still down")
        palm.onStylus(6_000, pressed = false)
        assertFalse(palm.fingerMayDraw(6_400))
        assertFalse(palm.fingerMayDraw(6_500))
        assertTrue(palm.fingerMayDraw(6_501))
    }

    // ---- highlight and note ----

    @Test
    fun highlightKeepsOneRectPerLineClampedAndSorted() {
        val rects = listOf(
            Rect(10f, 40f, 90f, 52f),
            Rect(10f, 20f, 300f, 32f), // runs off the right edge
            Rect(5f, 60f, 5.1f, 70f), // degenerate
            Rect(90f, 80f, 10f, 70f), // inverted
        )
        val out = highlightRects(rects, 200f, 100f)
        assertEquals(listOf(Rect(10f, 20f, 200f, 32f), Rect(10f, 40f, 90f, 52f), Rect(10f, 70f, 90f, 80f)), out)
        val m = highlightMarkup(2, out, AnnotatePalette.highlight[0])
        assertEquals(MarkupKind.HIGHLIGHT, m.kind)
        assertEquals(3, m.rects.size)
        assertTrue(m.widthPt > 0f)
    }

    @Test
    fun aNoteStaysOnThePage() {
        assertEquals(Rect(30f, 40f, 50f, 60f), noteRect(Pt(30f, 40f), 200f, 100f))
        assertEquals(Rect(180f, 80f, 200f, 100f), noteRect(Pt(199f, 99f), 200f, 100f))
        assertEquals(Rect(0f, 0f, 10f, 10f), noteRect(Pt(-5f, -5f), 10f, 10f))
    }

    @Test
    fun noteTextIsCappedBelowTheEngineLimits() {
        val long = "😀".repeat(60_000) // 4 bytes each
        val t = clampNoteText(long)
        assertTrue(t.length <= MarkupLimits.MAX_NOTE_CHARS)
        assertTrue(t.encodeToByteArray().size <= MarkupLimits.MAX_NOTE_BYTES)
        assertFalse(t.last().isHighSurrogate())
        assertEquals("hello", noteMarkup(0, Rect(0f, 0f, 20f, 20f), "hello", AnnotatePalette.ink[0]).contents)
    }

    @Test
    fun paletteMatchesTheDesignTokens() {
        val tokens = app.pdfa.design.PdfaColors.annotationInk.map { (it.value shr 32).toInt() and 0xFFFFFF }
        assertEquals(tokens, AnnotatePalette.ink.map { it.rgb })
        assertEquals(0xFFF3B0, AnnotatePalette.highlight[0].rgb)
    }
}
