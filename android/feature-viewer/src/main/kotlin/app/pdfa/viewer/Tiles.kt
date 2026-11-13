package app.pdfa.viewer

import app.pdfa.engine.TileRequest
import kotlin.math.ceil
import kotlin.math.log2
import kotlin.math.pow
import kotlin.math.roundToInt

/**
 * Identity of one cached tile. [bucket] is the power-of-two exponent of the render scale
 * (scale = 2^bucket device px per PDF point); [x] and [y] are tile indices, not pixels.
 * [lowRes] separates the whole-page preview layer from sharp tiles that share a bucket.
 */
data class TileKey(val page: Int, val bucket: Int, val x: Int, val y: Int, val lowRes: Boolean = false)

enum class TileKind { LOW_RES, SHARP, PREFETCH }

/**
 * One tile the viewer wants. The rectangle is in PDF points (page view space), clipped to the page;
 * [distancePx] is how far its centre is from the viewport centre on screen (0 = under the centre).
 */
data class PlannedTile(
    val key: TileKey,
    val request: TileRequest,
    val kind: TileKind,
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float,
    val distancePx: Float,
)

/**
 * What to draw for one visible page, grouped once per plan so the draw loop allocates nothing.
 * [fallback] (tiles of neighbouring buckets, shown while the sharp ones render) is computed on first use.
 */
class PagePlan(
    val page: Int,
    val low: PlannedTile?,
    val sharp: List<PlannedTile>,
    val bucket: Int,
    fallbackProvider: () -> List<PlannedTile>,
) {
    val fallback: List<PlannedTile> by lazy(LazyThreadSafetyMode.NONE, fallbackProvider)
}

/** The tiles for one viewer position, in the order they should be requested; [pages] groups them for drawing. */
data class TilePlan(
    val visiblePages: IntRange,
    val tiles: List<PlannedTile>,
    val pages: List<PagePlan> = emptyList(),
) {
    companion object {
        val Empty = TilePlan(IntRange.EMPTY, emptyList())
    }
}

/**
 * ONE memory budget for tiles: min(64 MiB, memoryClass / 8), split between the byte cache of engine
 * output ([cacheBytes], a third) and the bitmaps being drawn ([bitmapBytes], the rest).
 */
class TileBudget(val totalBytes: Long) {
    val cacheBytes: Long = totalBytes / 3
    val bitmapBytes: Long = totalBytes - cacheBytes

    companion object {
        fun forMemoryClass(memoryClassMb: Int): TileBudget = TileBudget(TileCache.defaultMaxBytes(memoryClassMb))
    }
}

/** Tile size and scale bucket arithmetic shared by the planner and the drawing code. */
object TileMath {
    const val TILE_PX = 512
    const val MIN_BUCKET = -6
    const val MAX_BUCKET = 8

    /** The preview layer is requested at this fraction of the target scale. */
    const val LOW_RES_FACTOR = 0.25f

    /** The preview layer never exceeds this many pixels on its longest side, nor falls under [LOW_RES_MIN_PX]. */
    const val LOW_RES_MAX_PX = 1024
    const val LOW_RES_MIN_PX = 256

    /** Nearest power of two to [targetScale] (device px per point), on a log scale. */
    fun bucketFor(targetScale: Float): Int {
        if (!(targetScale > 0f)) return MIN_BUCKET
        return log2(targetScale).roundToInt().coerceIn(MIN_BUCKET, MAX_BUCKET)
    }

    fun scaleOf(bucket: Int): Float = 2f.pow(bucket)

    /** Pixels along a page side of [sizePt] points at [scale]. At least 1. */
    fun pagePx(sizePt: Float, scale: Float): Int = ceil(sizePt * scale).toInt().coerceAtLeast(1)

    /** Bucket of the whole-page preview: ~0.25x the target, clamped to 256..1024 px on the long side. */
    fun lowBucketFor(targetScale: Float, widthPt: Float, heightPt: Float): Int {
        val longSide = maxOf(widthPt, heightPt, 1f)
        val wanted = (targetScale * LOW_RES_FACTOR).coerceIn(LOW_RES_MIN_PX / longSide, LOW_RES_MAX_PX / longSide)
        var bucket = bucketFor(wanted)
        while (bucket > MIN_BUCKET && pagePx(longSide, scaleOf(bucket)) > LOW_RES_MAX_PX) bucket--
        return bucket
    }
}
