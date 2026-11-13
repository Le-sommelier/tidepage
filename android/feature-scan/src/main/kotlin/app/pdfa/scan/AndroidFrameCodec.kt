package app.pdfa.scan

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import app.pdfa.engine.ImageFrame
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * [FrameCodec] on Android bitmaps. ARGB_8888 is stored as the bytes R, G, B, A, which is the
 * RGBA8 the engine takes, so frames move with a plain buffer copy. Every bitmap made here is
 * recycled before the call returns, so only the RGBA array of one frame is alive.
 */
class AndroidFrameCodec(private val dispatcher: CoroutineDispatcher = Dispatchers.Default) : FrameCodec {

    override suspend fun encode(frame: ImageFrame, quality: Int): ByteArray = withContext(dispatcher) {
        val bitmap = frame.toBitmap()
        try {
            bitmap.compressJpeg(quality)
        } finally {
            bitmap.recycle()
        }
    }

    override suspend fun <T> withDecoded(jpeg: ByteArray, maxPixels: Long, block: suspend (ImageFrame) -> T): T {
        val frame = withContext(dispatcher) { decode(jpeg, maxPixels) }
        return block(frame)
    }

    override suspend fun downscale(frame: ImageFrame, to: PixelSize): ImageFrame = withContext(dispatcher) {
        val source = frame.toBitmap()
        try {
            val scaled = Bitmap.createScaledBitmap(source, to.width, to.height, true)
            try {
                scaled.toFrame()
            } finally {
                if (scaled !== source) scaled.recycle()
            }
        } finally {
            source.recycle()
        }
    }

    private fun decode(jpeg: ByteArray, maxPixels: Long): ImageFrame {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) throw IllegalArgumentException("not a picture")
        val target = Sizing.fit(bounds.outWidth, bounds.outHeight, Int.MAX_VALUE, maxPixels)
        val opts = BitmapFactory.Options().apply {
            inPreferredConfig = Bitmap.Config.ARGB_8888
            inSampleSize = Sizing.sampleSize(bounds.outWidth, bounds.outHeight, Int.MAX_VALUE, maxPixels)
        }
        val decoded = BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size, opts)
            ?: throw IllegalArgumentException("could not decode")
        var bitmap = decoded
        try {
            if (bitmap.width.toLong() * bitmap.height > maxPixels) {
                val scaled = Bitmap.createScaledBitmap(bitmap, target.width, target.height, true)
                if (scaled !== bitmap) {
                    bitmap.recycle()
                    bitmap = scaled
                }
            }
            return bitmap.toFrame()
        } finally {
            bitmap.recycle()
        }
    }
}

/** RGBA frame of this bitmap. Copies row by row when the bitmap has padding at the end of a row. */
fun Bitmap.toFrame(): ImageFrame {
    val src = if (config == Bitmap.Config.ARGB_8888) this else copy(Bitmap.Config.ARGB_8888, false)
    try {
        val w = src.width
        val h = src.height
        val out = ByteArray(w * h * 4)
        if (src.rowBytes == w * 4) {
            src.copyPixelsToBuffer(ByteBuffer.wrap(out))
        } else {
            val padded = ByteBuffer.allocate(src.rowBytes * h)
            src.copyPixelsToBuffer(padded)
            val raw = padded.array()
            for (y in 0 until h) System.arraycopy(raw, y * src.rowBytes, out, y * w * 4, w * 4)
        }
        return ImageFrame(w, h, out)
    } finally {
        if (src !== this) src.recycle()
    }
}

/** A new bitmap with the pixels of [this] frame; the caller recycles it. */
fun ImageFrame.toBitmap(): Bitmap {
    val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
    bitmap.copyPixelsFromBuffer(ByteBuffer.wrap(rgba))
    bitmap.setHasAlpha(false)
    return bitmap
}

internal fun Bitmap.compressJpeg(quality: Int): ByteArray {
    val out = ByteArrayOutputStream(maxOf(64 * 1024, width * height / 8))
    check(compress(Bitmap.CompressFormat.JPEG, quality.coerceIn(1, 100), out)) { "JPEG encoding failed" }
    return out.toByteArray()
}
