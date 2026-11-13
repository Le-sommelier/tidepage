package app.pdfa.scan

import android.content.ContentResolver
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.ImageDecoder
import android.graphics.Matrix
import android.net.Uri
import android.os.Build
import androidx.exifinterface.media.ExifInterface
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Reads a picked or shared image (content:// or file-less stream) and returns it as JPEG.
 *
 * Whatever the input format (JPEG, PNG, HEIC, WebP...) the output is always freshly encoded
 * JPEG, so EXIF data, including location, is not carried over, and the engine only ever sees a
 * format it reads. The picture is turned upright, flattened onto white if it has transparency,
 * and scaled down while decoding to [Sizing.IMPORT_MAX_LONG_EDGE] / [Sizing.IMPORT_MAX_PIXELS].
 * Needs no storage permission: the picker or share grants access to the one URI.
 */
class ImageImporter(context: Context) {
    private val resolver: ContentResolver = context.applicationContext.contentResolver

    /** The picture as JPEG, or null when it is too big, unreadable or not an image. */
    suspend fun import(uri: Uri): ImportedImage? = withContext(Dispatchers.IO) {
        try {
            if (declaredLength(uri) > Sizing.IMPORT_MAX_FILE_BYTES) return@withContext null
            val bitmap = if (Build.VERSION.SDK_INT >= 28) decodeWithImageDecoder(uri) else decodeWithBitmapFactory(uri)
            val flat = bitmap?.flattened() ?: return@withContext null
            try {
                ImportedImage(flat.compressJpeg(JPEG_QUALITY), flat.width, flat.height)
            } finally {
                flat.recycle()
            }
        } catch (e: IOException) {
            null
        } catch (e: RuntimeException) {
            null // includes SecurityException and a decoder rejecting the file
        } catch (e: OutOfMemoryError) {
            null
        }
    }

    private fun declaredLength(uri: Uri): Long =
        try {
            resolver.openAssetFileDescriptor(uri, "r")?.use { it.length } ?: -1L
        } catch (e: IOException) {
            -1L
        }

    @androidx.annotation.RequiresApi(28)
    private fun decodeWithImageDecoder(uri: Uri): Bitmap? {
        val source = ImageDecoder.createSource(resolver, uri)
        return ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
            val w = info.size.width
            val h = info.size.height
            if (w <= 0 || h <= 0 || w.toLong() * h > MAX_SOURCE_PIXELS) throw IOException("picture too large")
            val target = Sizing.fit(w, h, Sizing.IMPORT_MAX_LONG_EDGE, Sizing.IMPORT_MAX_PIXELS)
            decoder.setTargetSize(target.width, target.height)
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE // orientation is applied by the decoder
        }
    }

    private fun decodeWithBitmapFactory(uri: Uri): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) } ?: return null
        val w = bounds.outWidth
        val h = bounds.outHeight
        if (w <= 0 || h <= 0 || w.toLong() * h > MAX_SOURCE_PIXELS) return null
        val opts = BitmapFactory.Options().apply {
            inSampleSize = Sizing.sampleSize(w, h, Sizing.IMPORT_MAX_LONG_EDGE, Sizing.IMPORT_MAX_PIXELS)
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        val decoded = resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, opts) } ?: return null
        val orientation = try {
            resolver.openInputStream(uri)?.use { ExifInterface(it).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL) }
        } catch (e: IOException) {
            null
        } ?: ExifInterface.ORIENTATION_NORMAL
        val turned = decoded.oriented(orientation)
        val target = Sizing.fit(turned.width, turned.height, Sizing.IMPORT_MAX_LONG_EDGE, Sizing.IMPORT_MAX_PIXELS)
        if (target.width == turned.width && target.height == turned.height) return turned
        val scaled = Bitmap.createScaledBitmap(turned, target.width, target.height, true)
        if (scaled !== turned) turned.recycle()
        return scaled
    }

    private fun Bitmap.oriented(exif: Int): Bitmap {
        val m = Matrix()
        when (exif) {
            ExifInterface.ORIENTATION_ROTATE_90 -> m.postRotate(90f)
            ExifInterface.ORIENTATION_ROTATE_180 -> m.postRotate(180f)
            ExifInterface.ORIENTATION_ROTATE_270 -> m.postRotate(270f)
            ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> m.postScale(-1f, 1f)
            ExifInterface.ORIENTATION_FLIP_VERTICAL -> m.postScale(1f, -1f)
            ExifInterface.ORIENTATION_TRANSPOSE -> { m.postRotate(90f); m.postScale(-1f, 1f) }
            ExifInterface.ORIENTATION_TRANSVERSE -> { m.postRotate(-90f); m.postScale(-1f, 1f) }
            else -> return this
        }
        val out = Bitmap.createBitmap(this, 0, 0, width, height, m, true)
        if (out !== this) recycle()
        return out
    }

    /** JPEG has no alpha: a transparent picture is placed on white, not on black. */
    private fun Bitmap.flattened(): Bitmap {
        if (!hasAlpha() && config == Bitmap.Config.ARGB_8888) return this
        val out = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        Canvas(out).apply {
            drawColor(android.graphics.Color.WHITE)
            drawBitmap(this@flattened, 0f, 0f, null)
        }
        recycle()
        return out
    }

    companion object {
        /** A picture with more pixels than this is refused before decoding (decompression bombs). */
        const val MAX_SOURCE_PIXELS = 250_000_000L
        const val JPEG_QUALITY = 90
    }
}
