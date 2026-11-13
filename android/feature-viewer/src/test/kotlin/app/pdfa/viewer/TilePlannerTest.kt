package app.pdfa.viewer

import app.pdfa.engine.PageInfo
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TilePlannerTest {
    private fun layout(n: Int = 50, vw: Float = 1000f, vh: Float = 1600f) =
        PageLayout(List(n) { PageInfo(500f, 700f, "") }, vw, vh, gap = 10f)

    private val planner = TilePlanner(marginPx = 0f)

    @Test fun bucketsArePowersOfTwo() {
        assertEquals(0, TileMath.bucketFor(1f))
        assertEquals(1, TileMath.bucketFor(2f))
        assertEquals(1, TileMath.bucketFor(1.76f))
        assertEquals(-1, TileMath.bucketFor(0.5f))
        assertEquals(4, TileMath.bucketFor(14f))
        assertEquals(2f, TileMath.scaleOf(1))
        assertEquals(0.25f, TileMath.scaleOf(-2))
    }

    @Test fun bucketIsClamped() {
        assertEquals(TileMath.MIN_BUCKET, TileMath.bucketFor(0f))
        assertEquals(TileMath.MAX_BUCKET, TileMath.bucketFor(1e9f))
    }

    @Test fun zoomWithinABucketKeepsTheSameKeys() {
        val l = layout()
        val a = planner.plan(l, ViewPos(1.0f)).tiles.filter { it.kind == TileKind.SHARP }.map { it.key.bucket }.toSet()
        val b = planner.plan(l, ViewPos(1.2f)).tiles.filter { it.kind == TileKind.SHARP }.map { it.key.bucket }.toSet()
        assertEquals(a, b)
    }

    @Test fun crossingABucketChangesTheBucket() {
        val l = layout() // fit scale 2.0
        val low = planner.plan(l, ViewPos(1f)).tiles.first { it.kind == TileKind.SHARP }.key.bucket
        val high = planner.plan(l, ViewPos(4f)).tiles.first { it.kind == TileKind.SHARP }.key.bucket
        assertEquals(1, low)
        assertEquals(3, high)
    }

    @Test fun tilesAre512PixelsWithSmallerEdges() {
        val l = layout()
        val sharp = planner.plan(l, ViewPos()).tiles.filter { it.kind == TileKind.SHARP && it.key.page == 0 }
        assertTrue(sharp.all { it.request.width <= 512 && it.request.height <= 512 })
        // page at bucket 1: 1000 x 1400 px -> 2 x 3 tiles, last column 488 wide, last row 376 tall
        assertEquals(6, sharp.size)
        assertEquals(488, sharp.first { it.key.x == 1 }.request.width)
        assertEquals(376, sharp.first { it.key.y == 2 }.request.height)
    }

    @Test fun tileRequestsStayInsideThePage() {
        val l = layout()
        for (t in planner.plan(l, ViewPos(3f, 500.0, 4000.0)).tiles) {
            val s = t.request.scale
            val pw = TileMath.pagePx(500f, s)
            val ph = TileMath.pagePx(700f, s)
            assertTrue(t.request.x + t.request.width <= pw && t.request.y + t.request.height <= ph, "$t")
        }
    }

    @Test fun lowResLayerComesFirstAndIsPreview() {
        val tiles = planner.plan(layout(), ViewPos()).tiles
        val firstSharp = tiles.indexOfFirst { it.kind == TileKind.SHARP }
        val lows = tiles.take(firstSharp)
        assertTrue(lows.isNotEmpty())
        assertTrue(lows.all { it.kind == TileKind.LOW_RES && it.request.preview && it.key.lowRes })
        assertTrue(tiles.filter { it.kind == TileKind.SHARP }.none { it.request.preview })
    }

    @Test fun lowResIsOneRequestPerWholePage() {
        val low = planner.plan(layout(), ViewPos()).tiles.first { it.kind == TileKind.LOW_RES }
        assertEquals(0, low.request.x)
        assertEquals(0, low.request.y)
        assertEquals(TileMath.pagePx(500f, low.request.scale), low.request.width)
        assertEquals(TileMath.pagePx(700f, low.request.scale), low.request.height)
    }

    @Test fun lowResIsAboutAQuarterOfTheTargetScale() {
        val l = layout() // target 2.0 -> 0.5 wanted
        val low = planner.plan(l, ViewPos()).tiles.first { it.kind == TileKind.LOW_RES }
        assertEquals(0.5f, low.request.scale)
    }

    @Test fun lowResNeverExceeds1024PixelsOnTheLongSide() {
        val l = PageLayout(listOf(PageInfo(100f, 4000f, "")), 1000f, 1000f)
        for (zoom in listOf(1f, 4f, 8f)) {
            val low = planner.plan(l, ViewPos(zoom)).tiles.first { it.kind == TileKind.LOW_RES }
            assertTrue(maxOf(low.request.width, low.request.height) <= 1024, "zoom $zoom -> ${low.request}")
        }
    }

    @Test fun sharpTilesAreOrderedByDistanceFromTheCentre() {
        val sharp = planner.plan(layout(), ViewPos(2f, 300.0, 1000.0)).tiles.filter { it.kind == TileKind.SHARP }
        val d = sharp.map { it.distancePx }
        assertEquals(d.sorted(), d)
    }

    @Test fun lowResOfThePageUnderTheCentreComesFirst() {
        // viewport 1600 tall: pages 0 (10..1410) and 1 (1420..2820) visible; centre y=800 lies on page 0
        val tiles = planner.plan(layout(), ViewPos()).tiles
        assertEquals(0, tiles.first().key.page)
        assertEquals(0f, tiles.first().distancePx)
    }

    @Test fun prefetchIsOnePageEachSideAtLowResOnly() {
        val l = layout()
        val plan = planner.plan(l, ViewPos(1f, 0.0, 3000.0))
        val pre = plan.tiles.filter { it.kind == TileKind.PREFETCH }
        assertEquals(setOf(plan.visiblePages.first - 1, plan.visiblePages.last + 1), pre.map { it.key.page }.toSet())
        assertTrue(pre.all { it.request.preview && it.key.lowRes })
        assertTrue(plan.tiles.filter { it.kind == TileKind.SHARP }.none { it.key.page !in plan.visiblePages })
    }

    @Test fun prefetchComesAfterVisibleWork() {
        val tiles = planner.plan(layout(), ViewPos(1f, 0.0, 3000.0)).tiles
        val lastVisible = tiles.indexOfLast { it.kind != TileKind.PREFETCH }
        val firstPrefetch = tiles.indexOfFirst { it.kind == TileKind.PREFETCH }
        assertTrue(firstPrefetch > lastVisible)
    }

    @Test fun noPrefetchBeforeFirstOrAfterLastPage() {
        val l = layout(3)
        val top = planner.plan(l, ViewPos()).tiles.filter { it.kind == TileKind.PREFETCH }
        assertTrue(top.none { it.key.page < 0 })
        val end = planner.plan(l, l.clamp(ViewPos(scrollY = 1e9)))
        assertTrue(end.tiles.filter { it.kind == TileKind.PREFETCH }.none { it.key.page >= 3 })
    }

    @Test fun zoomedInPlansOnlyTheVisibleTiles() {
        val l = layout()
        val plan = planner.plan(l, ViewPos(8f, 2000.0, 20000.0))
        val sharp = plan.tiles.filter { it.kind == TileKind.SHARP }
        assertTrue(sharp.size in 1..12, "${sharp.size}")
        assertTrue(sharp.all { it.key.bucket == TileMath.bucketFor(2f * 8f) })
    }

    @Test fun keysAreUnique() {
        val tiles = planner.plan(layout(), ViewPos(2f, 100.0, 700.0)).tiles
        assertEquals(tiles.size, tiles.map { it.key }.toSet().size)
    }

    @Test fun marginPlansTilesJustOutsideTheViewport() {
        val l = layout()
        val pos = ViewPos(1f, 0.0, 0.0)
        val none = TilePlanner(0f).plan(l, pos).tiles.count { it.kind == TileKind.SHARP }
        val wide = TilePlanner(600f).plan(l, pos).tiles.count { it.kind == TileKind.SHARP }
        assertTrue(wide >= none)
    }

    @Test fun planIsEmptyForEmptyDocument() {
        val l = PageLayout(emptyList(), 600f, 800f)
        assertEquals(TilePlan.Empty, planner.plan(l, ViewPos()))
        assertFalse(planner.plan(layout(), ViewPos()).tiles.isEmpty())
    }
}
