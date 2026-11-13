package app.pdfa.viewer

import app.pdfa.engine.PageInfo
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Zoom and scroll of the viewport. Scroll is the offset of the viewport's top-left inside the ZOOMED
 * content, in px. It is a Double: at 20 000 pages and 8x the content is ~10^8 px tall, beyond what a Float resolves.
 */
data class ViewPos(val zoom: Float = 1f, val scrollX: Double = 0.0, val scrollY: Double = 0.0)

/** A point inside a page, in PDF points (page view space, origin top-left). */
data class PagePoint(val page: Int, val xPt: Float, val yPt: Float)

/**
 * Continuous vertical stack of pages, in pixels. Pure arithmetic: no Android, no Compose.
 *
 * Content space ("zoom 1") is [viewportWidth] wide. Every page is scaled to fit the width between
 * two [sideMargin]s, separated by [gap]. Zoom multiplies the whole content, margins and gaps
 * included. On screen: `screen = content * zoom - scroll`. Page tops and scroll are Doubles; they are
 * converted to Float only AFTER subtracting, so the result is small and exact ([pageScreenTop]).
 */
class PageLayout(
    val pages: List<PageInfo>,
    val viewportWidth: Float,
    val viewportHeight: Float,
    val gap: Float = 8f,
    val sideMargin: Float = 0f,
) {
    val count: Int = pages.size

    /** Width every page gets at zoom 1. */
    val pageWidth: Float = max(1f, viewportWidth - 2f * sideMargin)

    private val tops = DoubleArray(count)
    private val heights = DoubleArray(count)

    /** Height of the whole stack at zoom 1, gaps included. */
    val contentHeight: Double

    init {
        var y = gap.toDouble()
        for (i in 0 until count) {
            val w = max(1f, pages[i].widthPt).toDouble()
            val h = max(1f, pages[i].heightPt).toDouble()
            tops[i] = y
            heights[i] = pageWidth * h / w
            y += heights[i] + gap
        }
        contentHeight = if (count == 0) 0.0 else y
    }

    fun pageTop(i: Int): Double = tops[i]
    fun pageHeight(i: Int): Double = heights[i]
    fun pageBottom(i: Int): Double = tops[i] + heights[i]

    /** Device pixels per PDF point at zoom 1 (fit width). */
    fun fitScale(i: Int): Float = pageWidth / max(1f, pages[i].widthPt)

    /** Screen y of the top edge of page [i]. Subtracts in Double, then narrows: exact near the viewport. */
    fun pageScreenTop(i: Int, pos: ViewPos): Float = (tops[i] * pos.zoom - pos.scrollY).toFloat()

    /** Screen x of the left edge of the pages. */
    fun pageScreenLeft(pos: ViewPos): Float = (sideMargin.toDouble() * pos.zoom - pos.scrollX).toFloat()

    // ---- limits ----

    fun clampZoom(zoom: Float): Float = zoom.coerceIn(MIN_ZOOM, MAX_ZOOM)

    fun maxScrollX(zoom: Float): Double = max(0.0, viewportWidth.toDouble() * zoom - viewportWidth)
    fun maxScrollY(zoom: Float): Double = max(0.0, contentHeight * zoom - viewportHeight)

    /** Zoom into [MIN_ZOOM]..[MAX_ZOOM] and scroll into the content. */
    fun clamp(pos: ViewPos): ViewPos {
        val z = clampZoom(pos.zoom)
        return ViewPos(z, pos.scrollX.coerceIn(0.0, maxScrollX(z)), pos.scrollY.coerceIn(0.0, maxScrollY(z)))
    }

    /**
     * New position after a pinch/pan gesture: the content point under [focusX],[focusY] (screen px)
     * moves to `focus + pan`, and the zoom becomes [newZoom]. The result is clamped.
     */
    fun transform(pos: ViewPos, newZoom: Float, focusX: Float, focusY: Float, panX: Float = 0f, panY: Float = 0f): ViewPos {
        val z = clampZoom(newZoom)
        val cx = (pos.scrollX + focusX) / pos.zoom
        val cy = (pos.scrollY + focusY) / pos.zoom
        return clamp(ViewPos(z, cx * z - (focusX + panX), cy * z - (focusY + panY)))
    }

    // ---- page lookup ----

    /** Pages whose rectangle meets the viewport. Empty when the viewport is wholly inside a gap. */
    fun visibleRange(pos: ViewPos): IntRange {
        if (count == 0) return IntRange.EMPTY
        val yTop = pos.scrollY / pos.zoom
        val yBottom = (pos.scrollY + viewportHeight) / pos.zoom
        // First page whose bottom is below the top edge; last page whose top is above the bottom edge.
        var lo = 0
        var hi = count
        while (lo < hi) {
            val mid = (lo + hi) ushr 1
            if (pageBottom(mid) > yTop) hi = mid else lo = mid + 1
        }
        val first = lo
        lo = 0
        hi = count
        while (lo < hi) {
            val mid = (lo + hi) ushr 1
            if (tops[mid] < yBottom) lo = mid + 1 else hi = mid
        }
        val last = lo - 1
        return if (first > last || first >= count || last < 0) IntRange.EMPTY else first..last
    }

    /** The page whose rectangle contains content row [y] (zoom 1), or null inside a gap or outside the stack. */
    fun pageAtContentY(y: Double): Int? {
        if (count == 0) return null
        val i = lastPageStartingAtOrBefore(y)
        return if (i >= 0 && y >= tops[i] && y < pageBottom(i)) i else null
    }

    /** The page under screen point [px],[py], or null for a gap, a side margin or empty space. */
    fun pageAtPoint(pos: ViewPos, px: Float, py: Float): Int? = pagePointAt(pos, px, py)?.page

    /** Like [pageAtPoint] with the position inside the page in PDF points. */
    fun pagePointAt(pos: ViewPos, px: Float, py: Float): PagePoint? {
        val cx = (pos.scrollX + px) / pos.zoom
        val cy = (pos.scrollY + py) / pos.zoom
        val i = pageAtContentY(cy) ?: return null
        if (cx < sideMargin || cx > sideMargin + pageWidth) return null
        val s = fitScale(i)
        return PagePoint(i, ((cx - sideMargin) / s).toFloat(), ((cy - tops[i]) / s).toFloat())
    }

    /**
     * The page the reader is on: the one under the viewport's centre line (or the nearest one above
     * a gap). The first and last page win at the two ends of the scroll range.
     */
    fun currentPage(pos: ViewPos): Int {
        if (count == 0) return 0
        if (pos.scrollY <= 0.0) return 0
        if (pos.scrollY >= maxScrollY(pos.zoom) - 0.5) return count - 1
        val y = (pos.scrollY + viewportHeight / 2.0) / pos.zoom
        return lastPageStartingAtOrBefore(y).coerceIn(0, count - 1)
    }

    private fun lastPageStartingAtOrBefore(y: Double): Int {
        var lo = 0
        var hi = count
        while (lo < hi) {
            val mid = (lo + hi) ushr 1
            if (tops[mid] <= y) lo = mid + 1 else hi = mid
        }
        return lo - 1
    }

    // ---- navigation ----

    /** Scroll that puts [page] at the top, with one gap above it, at [zoom]. Clamped. */
    fun scrollYForPage(page: Int, zoom: Float): Double {
        val p = page.coerceIn(0, max(0, count - 1))
        if (count == 0) return 0.0
        return ((tops[p] - gap) * zoom).coerceIn(0.0, maxScrollY(zoom))
    }

    /** "12 / 340" for the 0-based [page]. */
    fun badgeText(page: Int): String = "${page.coerceIn(0, max(0, count - 1)) + 1} / $count"

    // ---- fast scroll ----

    /** 0..1 position of the viewport in the scroll range. */
    fun scrollFraction(pos: ViewPos): Float {
        val m = maxScrollY(pos.zoom)
        return if (m <= 0.0) 0f else (pos.scrollY / m).coerceIn(0.0, 1.0).toFloat()
    }

    fun scrollYForFraction(fraction: Float, zoom: Float): Double = fraction.coerceIn(0f, 1f) * maxScrollY(zoom)

    /** Page the thumb stands for at [fraction]. */
    fun pageForFraction(fraction: Float): Int =
        if (count <= 1) 0 else (fraction.coerceIn(0f, 1f) * (count - 1)).roundToInt()

    fun fractionForPage(page: Int): Float =
        if (count <= 1) 0f else page.coerceIn(0, count - 1).toFloat() / (count - 1)

    companion object {
        const val MIN_ZOOM = 1f
        const val MAX_ZOOM = 8f
    }
}
