package app.pdfa.scan

import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

data class PixelSize(val width: Int, val height: Int) {
    val pixels: Long get() = width.toLong() * height
}

/** Size limits and the maths that applies them. Pure Kotlin. */
object Sizing {
    /** The engine refuses frames above this (40 megapixels, decimal). We refuse first. */
    const val HARD_CAP_PIXELS = 40_000_000L

    /** Camera frames are worked on at most this size: 8 Mpx is 32 MB as RGBA. */
    const val SCAN_MAX_PIXELS = 8_000_000L
    const val SCAN_MAX_LONG_EDGE = 3300

    /** Imported images are stored (as JPEG) at most this size: A4 at about 300 dpi. */
    const val IMPORT_MAX_PIXELS = 12_000_000L
    const val IMPORT_MAX_LONG_EDGE = 3508

    /** The most pixels the controller decodes at once to re-process a stored page. */
    const val DECODE_MAX_PIXELS = IMPORT_MAX_PIXELS

    /** A picked file bigger than this is refused before decoding. */
    const val IMPORT_MAX_FILE_BYTES = 64L * 1024 * 1024

    /** [PixelSize] after a downscale: aspect kept, never larger than the input, at least 1 x 1. */
    fun fit(width: Int, height: Int, maxLongEdge: Int, maxPixels: Long): PixelSize {
        require(width > 0 && height > 0) { "size must be positive" }
        require(maxLongEdge > 0 && maxPixels > 0) { "limits must be positive" }
        var scale = 1.0
        val long = max(width, height)
        if (long > maxLongEdge) scale = min(scale, maxLongEdge.toDouble() / long)
        val px = width.toLong() * height
        if (px > maxPixels) scale = min(scale, sqrt(maxPixels.toDouble() / px))
        if (scale >= 1.0) return PixelSize(width, height)
        var w = max(1, (width * scale).toInt())
        var h = max(1, (height * scale).toInt())
        // Rounding up never happens, but a 1-pixel floor on a thin image can break the pixel cap.
        while (w.toLong() * h > maxPixels && (w > 1 || h > 1)) {
            if (w >= h && w > 1) w-- else if (h > 1) h-- else break
        }
        return PixelSize(w, h)
    }

    /**
     * Power-of-two BitmapFactory sample size: the largest one whose decoded size is still at least
     * the [fit] target on both edges. The caller scales the rest exactly.
     */
    fun sampleSize(width: Int, height: Int, maxLongEdge: Int, maxPixels: Long): Int {
        val target = fit(width, height, maxLongEdge, maxPixels)
        var sample = 1
        while (width / (sample * 2) >= target.width && height / (sample * 2) >= target.height) sample *= 2
        return sample
    }

    /** True when a frame of this size must be refused outright. */
    fun exceedsHardCap(width: Int, height: Int): Boolean = width.toLong() * height > HARD_CAP_PIXELS

    // ---- page size for imagesToPdf ----

    const val A4_LONG_EDGE_INCHES = 11.693
    const val MIN_DPI = 50.0
    const val MAX_DPI = 1200.0

    /**
     * One dpi for the whole document (imagesToPdf takes one): the largest image's long edge is
     * mapped to the A4 long side, so no page is bigger than A4 and a typical scan comes out as A4.
     * Smaller images stay proportionally smaller. Clamped to 50..1200 and rounded to 0.1.
     */
    fun pdfDpi(images: List<PixelSize>): Double {
        val longest = images.maxOfOrNull { max(it.width, it.height) } ?: return 300.0
        val dpi = (longest / A4_LONG_EDGE_INCHES).coerceIn(MIN_DPI, MAX_DPI)
        return Math.round(dpi * 10.0) / 10.0
    }
}
