package app.pdfa.scan

import app.pdfa.engine.Pt
import app.pdfa.engine.Quad
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CropEditingTest {
    private val q = Quad(Pt(10f, 10f), Pt(90f, 10f), Pt(90f, 90f), Pt(10f, 90f))

    @Test
    fun movesOnlyTheChosenCorner() {
        val m = CropEditing.moveCorner(q, CropEditing.BOTTOM_RIGHT, -5f, 3f, 100, 100)
        assertEquals(Pt(85f, 93f), m.bottomRight)
        assertEquals(q.topLeft, m.topLeft)
        assertEquals(q.topRight, m.topRight)
        assertEquals(q.bottomLeft, m.bottomLeft)
    }

    @Test
    fun cornersStayInsideTheFrame() {
        val m = CropEditing.moveCorner(q, CropEditing.TOP_LEFT, -50f, -50f, 100, 80)
        assertEquals(Pt(0f, 0f), m.topLeft)
        val n = CropEditing.moveCorner(q, CropEditing.BOTTOM_RIGHT, 50f, 50f, 100, 80)
        assertEquals(Pt(100f, 80f), n.bottomRight)
    }

    @Test
    fun nudgeIsAtLeastOnePixelAndGrowsWithTheImage() {
        assertEquals(1f, CropEditing.nudgeStep(100, 100))
        assertEquals(10f, CropEditing.nudgeStep(2000, 1500), 0.001f)
    }

    @Test
    fun draggingIntoAFoldMakesTheCropUnusable() {
        assertTrue(CropEditing.isUsable(q, 100, 100))
        // Top-left dragged past the bottom-right: the shape folds over itself.
        val folded = CropEditing.moveCorner(q, CropEditing.TOP_LEFT, 90f, 90f, 100, 100)
        assertFalse(CropEditing.isUsable(folded, 100, 100))
    }

    @Test(expected = IllegalArgumentException::class)
    fun badCornerIndexIsRejected() {
        CropEditing.moveCorner(q, 4, 0f, 0f, 100, 100)
    }
}
