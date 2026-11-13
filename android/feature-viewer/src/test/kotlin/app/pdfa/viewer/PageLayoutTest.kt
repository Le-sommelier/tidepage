package app.pdfa.viewer

import app.pdfa.engine.PageInfo
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PageLayoutTest {
    private fun pages(n: Int, w: Float = 600f, h: Float = 800f) = List(n) { PageInfo(w, h, "${it + 1}") }

    /** 600 px wide, pages 600x800 -> 800 px tall, gap 10: tops at 10, 820, 1630, ... */
    private fun layout(n: Int = 100, vh: Float = 1000f) = PageLayout(pages(n), 600f, vh, gap = 10f, sideMargin = 0f)

    @Test fun fitWidthScalesPageToViewport() {
        val l = PageLayout(pages(1, 300f, 400f), 600f, 1000f, gap = 0f)
        assertEquals(2f, l.fitScale(0))
        assertEquals(800.0, l.pageHeight(0))
    }

    @Test fun sideMarginShrinksPages() {
        val l = PageLayout(pages(1, 300f, 400f), 620f, 1000f, gap = 0f, sideMargin = 10f)
        assertEquals(600f, l.pageWidth)
        assertEquals(2f, l.fitScale(0))
    }

    @Test fun pagesAreStackedWithGaps() {
        val l = layout(3)
        assertEquals(10.0, l.pageTop(0))
        assertEquals(820.0, l.pageTop(1))
        assertEquals(1630.0, l.pageTop(2))
        assertEquals(2440.0, l.contentHeight)
    }

    @Test fun mixedPageSizesFitWidthEach() {
        val l = PageLayout(listOf(PageInfo(600f, 300f, ""), PageInfo(300f, 600f, "")), 600f, 800f, gap = 0f)
        assertEquals(300.0, l.pageHeight(0))
        assertEquals(1200.0, l.pageHeight(1))
    }

    @Test fun visibleRangeAtTop() {
        assertEquals(0..1, layout().visibleRange(ViewPos()))
    }

    @Test fun visibleRangeAfterScrolling() {
        // viewport 1000..2000: page 1 (820..1620), page 2 (1630..2430)
        assertEquals(1..2, layout().visibleRange(ViewPos(scrollY = 1000.0)))
    }

    @Test fun visibleRangeWhenZoomed() {
        val l = layout()
        // content rows 1000..1250 -> inside page 1 (820..1620) only
        assertEquals(1..1, l.visibleRange(ViewPos(zoom = 4f, scrollY = 4000.0)))
    }

    @Test fun visibleRangeNearEnd() {
        val l = layout(100)
        val pos = l.clamp(ViewPos(scrollY = 1e9))
        assertEquals(99, l.visibleRange(pos).last)
    }

    @Test fun visibleRangeEmptyInsideGap() {
        val l = PageLayout(pages(2), 600f, 5f, gap = 100f)
        // content row 802..807 lies in the gap between page 0 (100..900) and page 1 (1000..1800)
        assertTrue(l.visibleRange(ViewPos(scrollY = 905.0)).isEmpty())
    }

    @Test fun visibleRangeOfEmptyDocument() {
        assertTrue(PageLayout(emptyList(), 600f, 800f).visibleRange(ViewPos()).isEmpty())
    }

    @Test fun zoomIsClampedToOneToEight() {
        val l = layout()
        assertEquals(1f, l.clampZoom(0.2f))
        assertEquals(8f, l.clampZoom(20f))
        assertEquals(3f, l.clampZoom(3f))
    }

    @Test fun panIsClampedToContent() {
        val l = layout(3)
        val p = l.clamp(ViewPos(zoom = 2f, scrollX = 5000.0, scrollY = -50.0))
        assertEquals(600.0, p.scrollX) // content 1200 wide, viewport 600
        assertEquals(0.0, p.scrollY)
        val q = l.clamp(ViewPos(zoom = 2f, scrollY = 1e9))
        assertEquals(2440.0 * 2.0 - 1000.0, q.scrollY)
    }

    @Test fun noHorizontalPanAtFitWidth() {
        assertEquals(0.0, layout().maxScrollX(1f))
        assertEquals(0.0, layout().clamp(ViewPos(scrollX = 300.0)).scrollX)
    }

    @Test fun shortDocumentCannotScroll() {
        val l = PageLayout(pages(1, 600f, 100f), 600f, 1000f, gap = 10f)
        assertEquals(0.0, l.maxScrollY(1f))
    }

    @Test fun transformKeepsFocusPointStill() {
        val l = layout()
        val start = ViewPos(1f, 0.0, 500.0)
        val focus = 300f to 400f
        val before = (start.scrollX + focus.first) / start.zoom to (start.scrollY + focus.second) / start.zoom
        val after = l.transform(start, 3f, focus.first, focus.second)
        val now = (after.scrollX + focus.first) / after.zoom to (after.scrollY + focus.second) / after.zoom
        assertEquals(before.first, now.first, 0.01)
        assertEquals(before.second, now.second, 0.01)
    }

    @Test fun transformAppliesPan() {
        val l = layout()
        val a = l.transform(ViewPos(1f, 0.0, 500.0), 1f, 0f, 0f, panX = 0f, panY = -100f)
        assertEquals(600.0, a.scrollY, 0.01)
    }

    @Test fun pageAtPointFindsPageAndGap() {
        val l = layout()
        assertEquals(0, l.pageAtPoint(ViewPos(), 100f, 100f))
        assertEquals(1, l.pageAtPoint(ViewPos(), 100f, 900f))
        assertNull(l.pageAtPoint(ViewPos(), 100f, 5f)) // top gap
        assertNull(l.pageAtPoint(ViewPos(), 100f, 815f)) // gap between 0 and 1
    }

    @Test fun pageAtPointRespectsScrollAndZoom() {
        val l = layout()
        assertEquals(2, l.pageAtPoint(ViewPos(1f, 0.0, 1700.0), 10f, 10f))
        assertEquals(0, l.pageAtPoint(ViewPos(2f, 0.0, 0.0), 10f, 100f))
    }

    @Test fun pageAtPointOutsideSideMarginIsNull() {
        val l = PageLayout(pages(2), 620f, 1000f, gap = 10f, sideMargin = 10f)
        assertNull(l.pageAtPoint(ViewPos(), 5f, 100f))
        assertNotNull(l.pageAtPoint(ViewPos(), 15f, 100f))
    }

    @Test fun pagePointIsInPdfPoints() {
        val l = PageLayout(pages(1, 300f, 400f), 600f, 1000f, gap = 0f)
        val p = l.pagePointAt(ViewPos(), 300f, 200f)!!
        assertEquals(0, p.page)
        assertEquals(150f, p.xPt, 0.01f)
        assertEquals(100f, p.yPt, 0.01f)
    }

    @Test fun currentPageUsesViewportCentre() {
        val l = layout()
        assertEquals(0, l.currentPage(ViewPos()))
        assertEquals(1, l.currentPage(ViewPos(scrollY = 1000.0))) // centre at 1500 -> page 1
        assertEquals(2, l.currentPage(ViewPos(scrollY = 1700.0)))
    }

    @Test fun currentPageIsLastAtTheEnd() {
        val l = layout(10)
        assertEquals(9, l.currentPage(l.clamp(ViewPos(scrollY = 1e9))))
    }

    @Test fun scrollForPagePutsPageNearTop() {
        val l = layout()
        assertEquals(810.0, l.scrollYForPage(1, 1f))
        assertEquals(1620.0, l.scrollYForPage(1, 2f))
        assertEquals(0.0, l.scrollYForPage(0, 1f))
    }

    @Test fun badgeTextIsOneBased() {
        assertEquals("12 / 340", layout(340).badgeText(11))
        assertEquals("1 / 340", layout(340).badgeText(-4))
    }

    @Test fun fastScrollFractionRoundTrips() {
        val l = layout(100)
        val y = l.scrollYForFraction(0.5f, 1f)
        assertEquals(0.5f, l.scrollFraction(ViewPos(scrollY = y)), 0.001f)
        assertEquals(0.0, l.scrollYForFraction(-1f, 1f))
        assertEquals(l.maxScrollY(1f), l.scrollYForFraction(3f, 1f))
    }

    @Test fun fastScrollPageMapping() {
        val l = layout(101)
        assertEquals(0, l.pageForFraction(0f))
        assertEquals(50, l.pageForFraction(0.5f))
        assertEquals(100, l.pageForFraction(1f))
        assertEquals(0.5f, l.fractionForPage(50))
        assertEquals(0f, layout(1).fractionForPage(0))
    }

    @Test fun twentyThousandPagesAtEightTimesZoomHaveExactStableGeometry() {
        val l = PageLayout(pages(20_000), 1000f, 2000f, gap = 8f)
        val z = 8f
        assertTrue(l.maxScrollY(z) > 1e8)
        val base = l.scrollYForPage(15_000, z)
        var prev = Float.NaN
        for (i in 0 until 400) {
            val pos = ViewPos(z, 0.0, base + i * 0.5)
            val top = l.pageScreenTop(15_000, pos)
            val reference = (l.pageTop(15_000) * z - pos.scrollY).toFloat()
            assertEquals(reference, top)
            if (i > 0) assertEquals(prev - 0.5f, top, 1e-3f, "jitter at step $i")
            prev = top
        }
    }

    @Test fun planAtTheFarEndOfAHugeDocumentIsSane() {
        val l = PageLayout(pages(20_000), 1000f, 2000f, gap = 8f)
        val planner = TilePlanner(marginPx = 0f)
        val pos = l.clamp(ViewPos(8f, 123.0, 1e12))
        assertEquals(19_999, l.currentPage(pos))
        val plan = planner.plan(l, pos)
        assertTrue(plan.tiles.isNotEmpty() && plan.visiblePages.last == 19_999)
        for (t in plan.tiles) {
            assertTrue(t.distancePx.isFinite() && t.left.isFinite() && t.top.isFinite())
            assertTrue(t.request.x >= 0 && t.request.y >= 0 && t.request.width > 0 && t.request.height > 0)
        }
        assertTrue(plan.tiles.size < 60, "${plan.tiles.size} tiles")
    }

    @Test fun visibleRangeIsMonotonicAcrossAHugeDocument() {
        val l = PageLayout(pages(20_000), 1000f, 2000f, gap = 8f)
        var last = -1
        for (f in 0..1000) {
            val pos = ViewPos(8f, 0.0, l.scrollYForFraction(f / 1000f, 8f))
            val first = l.visibleRange(pos).first
            assertTrue(first >= last, "page went backwards at $f")
            last = first
        }
    }
}
