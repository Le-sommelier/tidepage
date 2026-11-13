package app.pdfa.viewer

import app.pdfa.engine.TileRequest
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/**
 * Decides which tiles to render for a viewer position. Pure: the same layout and position always
 * give the same plan.
 *
 * Order of the plan: (1) a low-res whole-page layer for every visible page, so the first paint is
 * instant; (2) the sharp 512 px tiles of the visible pages, nearest to the viewport centre first;
 * (3) low-res prefetch of [prefetchPages] pages before and after the visible ones, never sharp.
 * Sharp tiles sit on a power-of-two scale bucket, so a pinch does not change the plan every frame.
 *
 * @param marginPx extra band around the viewport that is planned too, to hide pan latency.
 */
class TilePlanner(
    private val marginPx: Float = 128f,
    private val prefetchPages: Int = 1,
) {
    fun plan(layout: PageLayout, pos: ViewPos): TilePlan {
        val visible = layout.visibleRange(pos)
        if (visible.isEmpty()) return TilePlan.Empty

        val lowByPage = HashMap<Int, PlannedTile>(visible.count() * 2)
        val lows = ArrayList<PlannedTile>(visible.count())
        for (p in visible) {
            val t = lowResTile(layout, pos, p, TileKind.LOW_RES)
            lows += t
            lowByPage[p] = t
        }
        lows.sortBy { it.distancePx }

        val sharp = ArrayList<PlannedTile>()
        val pages = ArrayList<PagePlan>(visible.count())
        for (p in visible) {
            val bucket = sharpBucket(layout, pos, p)
            val tiles = sharpTiles(layout, pos, p, bucket)
            sharp += tiles
            pages += PagePlan(p, lowByPage[p], tiles, bucket) {
                val out = ArrayList<PlannedTile>()
                for (fb in intArrayOf(bucket - 2, bucket + 2, bucket - 1, bucket + 1)) {
                    if (fb in TileMath.MIN_BUCKET..TileMath.MAX_BUCKET) out += sharpTiles(layout, pos, p, fb)
                }
                out
            }
        }
        sharp.sortBy { it.distancePx }

        val prefetch = ArrayList<PlannedTile>()
        for (d in 1..prefetchPages) {
            val before = visible.first - d
            val after = visible.last + d
            if (after < layout.count) prefetch += lowResTile(layout, pos, after, TileKind.PREFETCH)
            if (before >= 0) prefetch += lowResTile(layout, pos, before, TileKind.PREFETCH)
        }
        return TilePlan(visible, lows + sharp + prefetch, pages)
    }

    /** Scale bucket of the sharp tiles of [page] at the zoom in [pos]. */
    fun sharpBucket(layout: PageLayout, pos: ViewPos, page: Int): Int =
        TileMath.bucketFor(layout.fitScale(page) * pos.zoom)

    /** The sharp tiles of [page] at [bucket] that meet the viewport (plus the margin). */
    fun sharpTiles(layout: PageLayout, pos: ViewPos, page: Int, bucket: Int): List<PlannedTile> {
        val info = layout.pages[page]
        val wPt = max(1f, info.widthPt)
        val hPt = max(1f, info.heightPt)
        val target = layout.fitScale(page) * pos.zoom
        val originX = layout.pageScreenLeft(pos)
        val originY = layout.pageScreenTop(page, pos)

        val left = ((-marginPx - originX) / target).coerceAtLeast(0f)
        val right = ((layout.viewportWidth + marginPx - originX) / target).coerceAtMost(wPt)
        val top = ((-marginPx - originY) / target).coerceAtLeast(0f)
        val bottom = ((layout.viewportHeight + marginPx - originY) / target).coerceAtMost(hPt)
        if (right <= left || bottom <= top) return emptyList()

        val scale = TileMath.scaleOf(bucket)
        val pw = TileMath.pagePx(wPt, scale)
        val ph = TileMath.pagePx(hPt, scale)
        val size = TileMath.TILE_PX
        val cols = ceil(pw / size.toFloat()).toInt()
        val rows = ceil(ph / size.toFloat()).toInt()
        val tx0 = floor(left * scale / size).toInt().coerceIn(0, cols - 1)
        val tx1 = (ceil(right * scale / size).toInt() - 1).coerceIn(tx0, cols - 1)
        val ty0 = floor(top * scale / size).toInt().coerceIn(0, rows - 1)
        val ty1 = (ceil(bottom * scale / size).toInt() - 1).coerceIn(ty0, rows - 1)

        val cx = layout.viewportWidth / 2f
        val cy = layout.viewportHeight / 2f
        val out = ArrayList<PlannedTile>((tx1 - tx0 + 1) * (ty1 - ty0 + 1))
        for (ty in ty0..ty1) {
            for (tx in tx0..tx1) {
                val x = tx * size
                val y = ty * size
                val w = min(size, pw - x)
                val h = min(size, ph - y)
                val l = x / scale
                val t = y / scale
                val r = min((x + w) / scale, wPt)
                val b = min((y + h) / scale, hPt)
                val sx = originX + (l + r) / 2f * target
                val sy = originY + (t + b) / 2f * target
                out += PlannedTile(
                    key = TileKey(page, bucket, tx, ty),
                    request = TileRequest(page, scale, x, y, w, h, preview = false),
                    kind = TileKind.SHARP,
                    left = l, top = t, right = r, bottom = b,
                    distancePx = hypot(sx - cx, sy - cy),
                )
            }
        }
        return out
    }

    /** The whole-page preview tile of [page]: one request, `preview = true`, ~0.25x the target scale. */
    fun lowResTile(layout: PageLayout, pos: ViewPos, page: Int, kind: TileKind): PlannedTile {
        val info = layout.pages[page]
        val wPt = max(1f, info.widthPt)
        val hPt = max(1f, info.heightPt)
        val target = layout.fitScale(page) * pos.zoom
        val bucket = TileMath.lowBucketFor(target, wPt, hPt)
        val scale = TileMath.scaleOf(bucket)
        val pw = TileMath.pagePx(wPt, scale)
        val ph = TileMath.pagePx(hPt, scale)

        // Distance from the viewport centre to the page rectangle on screen (0 when the centre is on it).
        val originX = layout.pageScreenLeft(pos)
        val originY = layout.pageScreenTop(page, pos)
        val cx = layout.viewportWidth / 2f
        val cy = layout.viewportHeight / 2f
        val dx = max(originX - cx, max(0f, cx - (originX + wPt * target)))
        val dy = max(originY - cy, max(0f, cy - (originY + hPt * target)))
        return PlannedTile(
            key = TileKey(page, bucket, 0, 0, lowRes = true),
            request = TileRequest(page, scale, 0, 0, pw, ph, preview = true),
            kind = kind,
            left = 0f, top = 0f, right = wPt, bottom = hPt,
            distancePx = hypot(max(0f, dx), max(0f, dy)),
        )
    }
}
