package app.pdfa.scan

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.produceState
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Decodes [image] for display, at most [maxEdge] pixels on the long side. Null while loading or if unreadable. */
@Composable
internal fun rememberJpegBitmap(image: JpegImage?, maxEdge: Int): ImageBitmap? =
    produceState<ImageBitmap?>(initialValue = null, image, maxEdge) {
        value = if (image == null) null else withContext(Dispatchers.Default) { decodeForDisplay(image.bytes, maxEdge) }
    }.value

internal fun decodeForDisplay(bytes: ByteArray, maxEdge: Int): ImageBitmap? {
    return try {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        val opts = BitmapFactory.Options().apply {
            inSampleSize = Sizing.sampleSize(bounds.outWidth, bounds.outHeight, maxEdge, Long.MAX_VALUE)
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)?.asImageBitmap()
    } catch (e: RuntimeException) {
        null
    } catch (e: OutOfMemoryError) {
        null
    }
}

/** Adds pictures the person picked or another app shared. They are re-saved as JPEG without metadata. */
suspend fun ScanController.addPickedImages(importer: ImageImporter, uris: List<Uri>): Boolean =
    addImages(uris) { importer.import(it) }

/**
 * Returns a function that opens the system photo picker (several pictures, no storage permission
 * needed) and adds the result to [controller]. Pass it as `onPickImages` to [ScanFlow].
 */
@Composable
fun rememberScanImagePicker(controller: ScanController): () -> Unit {
    val context = LocalContext.current
    val importer = remember(context) { ImageImporter(context) }
    val scope = rememberCoroutineScope()
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia()) { uris ->
        if (uris.isNotEmpty()) scope.launch { controller.addPickedImages(importer, uris) }
    }
    return remember(launcher) {
        { launcher.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }
    }
}
