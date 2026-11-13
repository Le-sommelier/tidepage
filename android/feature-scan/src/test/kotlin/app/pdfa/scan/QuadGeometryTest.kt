package app.pdfa.scan

import app.pdfa.engine.Pt
import app.pdfa.engine.Quad
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class QuadGeometryTest {
    private val tl = Pt(10f, 10f)
    private val tr = Pt(90f, 12f)
    private val br = Pt(92f, 70f)
    private val bl = Pt(8f, 68f)
    private val expected = Quad(tl, tr, br, bl)

    @Test
    fun everyPermutationOfFourCornersIsOrderedTheSame() {
        val pts = listOf(tl, tr, br, bl)
        val perms = permutations(pts)
        assertEquals(24, perms.size)
        perms.forEach { assertEquals(expected, QuadGeometry.orderCorners(it)) }
    }

    @Test
    fun orderedQuadIsClockwiseConvex() {
        assertTrue(QuadGeometry.isConvexClockwise(expected))
        assertTrue(QuadGeometry.signedArea2(expected) > 0)
    }

    @Test
    fun rotatedDocumentKeepsTopLeftNearestTheOrigin() {
        // Turned about 30 degrees: the corner nearest the frame's top-left is still first.
        val q = QuadGeometry.orderCorners(listOf(Pt(60f, 5f), Pt(95f, 60f), Pt(40f, 95f), Pt(5f, 40f)))
        assertEquals(Pt(5f, 40f), q.topLeft) // x + y = 45 is the smallest, ahead of (60,5) = 65
        assertEquals(Pt(60f, 5f), q.topRight)
        assertEquals(Pt(95f, 60f), q.bottomRight)
        assertEquals(Pt(40f, 95f), q.bottomLeft)
        assertTrue(QuadGeometry.isConvexClockwise(q))
    }

    @Test
    fun diamondTieStartsAtTheTopVertex() {
        val q = QuadGeometry.orderCorners(listOf(Pt(50f, 0f), Pt(100f, 50f), Pt(50f, 100f), Pt(0f, 50f)))
        // top (50,0) sum 50 and left (0,50) sum 50 tie: smaller y wins.
        assertEquals(Pt(50f, 0f), q.topLeft)
        assertEquals(Pt(100f, 50f), q.topRight)
        assertEquals(Pt(50f, 100f), q.bottomRight)
        assertEquals(Pt(0f, 50f), q.bottomLeft)
        assertTrue(QuadGeometry.isConvexClockwise(q))
    }

    @Test
    fun degenerateInputStillReturnsFourCornersButIsInvalid() {
        val same = Pt(5f, 5f)
        val q = QuadGeometry.orderCorners(listOf(same, same, same, same))
        assertEquals(listOf(same, same, same, same), QuadGeometry.corners(q))
        assertNull(QuadGeometry.validate(q, 100, 100))
        val line = QuadGeometry.orderCorners(listOf(Pt(0f, 0f), Pt(10f, 10f), Pt(20f, 20f), Pt(30f, 30f)))
        assertNull(QuadGeometry.validate(line, 100, 100))
    }

    @Test
    fun threeCollinearPointsAreInvalid() {
        val q = QuadGeometry.orderCorners(listOf(Pt(10f, 10f), Pt(50f, 10f), Pt(90f, 10f), Pt(50f, 80f)))
        assertNull(QuadGeometry.validate(q, 100, 100))
    }

    @Test
    fun wrongNumberOfPointsIsRejected() {
        var failed = false
        try { QuadGeometry.orderCorners(listOf(tl, tr, br)) } catch (e: IllegalArgumentException) { failed = true }
        assertTrue(failed)
    }

    @Test
    fun concaveShapeIsInvalid() {
        // Arrowhead: the fourth point is inside the triangle of the others.
        val q = QuadGeometry.orderCorners(listOf(Pt(0f, 0f), Pt(100f, 0f), Pt(50f, 100f), Pt(50f, 20f)))
        assertFalse(QuadGeometry.isConvexClockwise(q))
        assertNull(QuadGeometry.validate(q, 100, 100))
    }

    @Test
    fun counterClockwiseOrderIsInvalidUntilOrdered() {
        val ccw = Quad(tl, bl, br, tr)
        assertNull(QuadGeometry.validate(ccw, 100, 100))
        assertNotNull(QuadGeometry.sanitize(ccw, 100, 100))
    }

    @Test
    fun bowTieOrderIsInvalid() {
        val bow = Quad(Pt(0f, 0f), Pt(100f, 100f), Pt(100f, 0f), Pt(0f, 100f))
        assertNull(QuadGeometry.validate(bow, 100, 100))
        // After ordering the same four points it is a normal square.
        val fixed = QuadGeometry.sanitize(bow, 100, 100)
        assertEquals(Quad(Pt(0f, 0f), Pt(100f, 0f), Pt(100f, 100f), Pt(0f, 100f)), fixed)
    }

    @Test
    fun validQuadPassesUnchanged() {
        assertEquals(expected, QuadGeometry.validate(expected, 100, 80))
    }

    @Test
    fun cornersOutsideTheFrameAreClamped() {
        val q = Quad(Pt(-20f, -5f), Pt(130f, -5f), Pt(130f, 90f), Pt(-20f, 90f))
        assertEquals(Quad(Pt(0f, 0f), Pt(100f, 0f), Pt(100f, 80f), Pt(0f, 80f)), QuadGeometry.validate(q, 100, 80))
    }

    @Test
    fun nanAndInfiniteAreInvalid() {
        assertNull(QuadGeometry.validate(Quad(Pt(Float.NaN, 0f), tr, br, bl), 100, 100))
        assertNull(QuadGeometry.validate(Quad(tl, Pt(Float.POSITIVE_INFINITY, 0f), br, bl), 100, 100))
    }

    @Test
    fun minimumAreaBoundary() {
        // Frame 100 x 100 = 10000 px; 5% = 500 px.
        fun rect(w: Float, h: Float) = Quad(Pt(0f, 0f), Pt(w, 0f), Pt(w, h), Pt(0f, h))
        assertNotNull(QuadGeometry.validate(rect(50f, 10f), 100, 100)) // exactly 500
        assertNull(QuadGeometry.validate(rect(49.9f, 10f), 100, 100)) // just under
        assertNull(QuadGeometry.validate(rect(100f, 4f), 100, 100)) // thin sliver, 400
    }

    @Test
    fun tinyEdgeIsInvalid() {
        val q = Quad(Pt(0f, 0f), Pt(100f, 0f), Pt(100f, 100f), Pt(0f, 100f))
        assertNotNull(QuadGeometry.validate(q, 100, 100))
        val pinched = Quad(Pt(0f, 0f), Pt(5f, 0f), Pt(100f, 100f), Pt(0f, 100f))
        assertNull(QuadGeometry.validate(pinched, 100, 100))
    }

    @Test
    fun fullFrameIsValidAndCoversTheFrame() {
        val q = QuadGeometry.fullFrame(640, 480)
        assertEquals(Quad(Pt(0f, 0f), Pt(640f, 0f), Pt(640f, 480f), Pt(0f, 480f)), q)
        assertEquals(640.0 * 480, QuadGeometry.area(q), 0.001)
        assertEquals(q, QuadGeometry.validate(q, 640, 480))
    }

    @Test
    fun fallbackUsedForNullAndForUnusableQuads() {
        val full = QuadGeometry.fullFrame(100, 100)
        assertEquals(full, QuadGeometry.orFullFrame(null, 100, 100))
        assertEquals(full, QuadGeometry.orFullFrame(Quad(Pt(1f, 1f), Pt(1f, 1f), Pt(1f, 1f), Pt(1f, 1f)), 100, 100))
        assertEquals(expected, QuadGeometry.orFullFrame(expected, 100, 80))
    }

    @Test
    fun scaleMapsCornersProportionally() {
        val q = QuadGeometry.scale(Quad(Pt(10f, 20f), Pt(100f, 20f), Pt(100f, 80f), Pt(10f, 80f)), 200, 100, 100, 50)
        assertEquals(Quad(Pt(5f, 10f), Pt(50f, 10f), Pt(50f, 40f), Pt(5f, 40f)), q)
    }

    private fun <T> permutations(l: List<T>): List<List<T>> =
        if (l.size <= 1) listOf(l) else l.flatMap { x -> permutations(l - x).map { listOf(x) + it } }
}
