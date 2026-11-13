package app.pdfa.app.viewer

import app.pdfa.annotate.StrokeMapper
import app.pdfa.engine.PageInfo
import app.pdfa.engine.Pt
import app.pdfa.viewer.PageLayout
import app.pdfa.viewer.ViewPos
import app.pdfa.viewer.ViewportState
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The annotation layer's screen-to-page mapping on the viewer's real [PageLayout].
 *
 * Page sizes are VIEW sizes, as the engine reports them: /Rotate is already applied, so a page
 * rotated by 90 or 270 degrees is 792 x 612 here and one at 0 or 180 is 612 x 792. The mapping
 * itself never sees the rotation; the engine turns view space into user space. These cases make
 * sure each page's own fit scale and top are used (they differ between portrait and landscape).
 */
class AnnotateGeometryTest {
    private val portrait = PageInfo(612f, 792f, "1") // /Rotate 0 or 180
    private val landscape = PageInfo(792f, 612f, "2") // /Rotate 90 or 270
    private val pages = listOf(portrait, landscape, portrait, landscape)
    private val layout = PageLayout(pages, viewportWidth = 1080f, viewportHeight = 1800f, gap = 8f)

    private fun geometry(pos: ViewPos) = LayoutPageGeometry { ViewportState(layout = layout, pos = layout.clamp(pos)) }

    /** Screen pixel of page point ([x], [y]) on [page], from the layout's own formulas. */
    private fun screen(page: Int, pos: ViewPos, x: Float, y: Float): Pair<Float, Float> {
        val p = layout.clamp(pos)
        val s = layout.fitScale(page) * p.zoom
        return (layout.pageScreenLeft(p) + x * s) to (layout.pageScreenTop(page, p) + y * s)
    }

    private val positions = listOf(1f, 2.5f, 8f).flatMap { z ->
        listOf(0.0, 300.0, 1234.5).flatMap { sx -> listOf(0.0, 900.0, 4_000.0).map { sy -> ViewPos(z, sx, sy) } }
    }

    @Test
    fun everyPageMapsBackToTheSamePointAtEveryZoomAndScroll() {
        var checked = 0
        for (pos in positions) {
            val g = geometry(pos)
            for (page in pages.indices) {
                val info = pages[page]
                for ((x, y) in listOf(10f to 20f, info.widthPt / 2 to info.heightPt / 3, info.widthPt - 5f to info.heightPt - 7f)) {
                    val (px, py) = screen(page, pos, x, y)
                    if (px !in 0f..1080f || py !in 0f..1800f) continue // off screen at this position
                    val m = assertNotNull(StrokeMapper.start(g, px, py), "page $page at $pos")
                    assertEquals(page, m.page)
                    val p = m.map(px, py)
                    assertEquals(x, p.x, 0.05f, "x on page $page at $pos")
                    assertEquals(y, p.y, 0.05f, "y on page $page at $pos")
                    // And the frame puts it back on the same pixel.
                    assertEquals(px, m.frame.toScreenX(p.x), 0.05f)
                    assertEquals(py, m.frame.toScreenY(p.y), 0.05f)
                    checked++
                }
            }
        }
        assertTrue(checked >= 40, "enough on-screen cases: $checked")
    }

    @Test
    fun aStrokeStartingOnTheLandscapePageAndRunningOntoTheNextIsClampedToIt() {
        // Scrolled so page 2 (landscape) is on screen.
        val pos = ViewPos(1f, 0.0, layout.pageTop(1) - 100.0)
        val g = geometry(pos)
        val (sx, sy) = screen(1, pos, 100f, 300f)
        val m = assertNotNull(StrokeMapper.start(g, sx, sy))
        assertEquals(1, m.page)
        // Down past the bottom of page 2, through the gap and into page 3.
        val (_, below) = screen(2, pos, 100f, 50f)
        assertEquals(Pt(100f, 612f), m.map(sx, below).let { Pt(Math.round(it.x * 10) / 10f, it.y) })
        // Up into page 1.
        assertEquals(0f, m.map(sx, sy - 2_000f).y)
        // Off the right edge.
        assertEquals(792f, m.map(5_000f, sy).x)
    }

    @Test
    fun aStrokeCannotStartInTheGapBetweenPages() {
        val pos = ViewPos(1f, 0.0, 0.0)
        val g = geometry(pos)
        val gapY = (layout.pageBottom(0) + 4.0 - pos.scrollY).toFloat()
        assertNull(StrokeMapper.start(g, 100f, gapY))
    }

    @Test
    fun aNoteTapUsesThePageUnderTheFinger() {
        val pos = ViewPos(2.5f, 300.0, 900.0)
        val g = geometry(pos)
        val page = assertNotNull(g.pageAt(540f, 900f))
        val f = assertNotNull(g.frame(page))
        assertEquals(pages[page].widthPt, f.widthPt)
        assertEquals(layout.fitScale(page) * 2.5f, f.scale)
        assertNull(g.frame(99))
    }
}
