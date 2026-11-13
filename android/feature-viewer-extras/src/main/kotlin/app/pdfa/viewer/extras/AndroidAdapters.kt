package app.pdfa.viewer.extras

import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.os.Build
import android.os.PersistableBundle
import android.os.RemoteException
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.core.graphics.createBitmap
import androidx.core.net.toUri
import java.nio.ByteBuffer

/**
 * [TextClipboard] backed by the system clipboard. Holds no text itself. The clip has a neutral
 * label and, on API 33+, the sensitive flag, since a selection can hold passwords or personal
 * data. A failed write returns false instead of throwing.
 */
class AndroidTextClipboard(context: Context) : TextClipboard {
    private val manager = context.applicationContext.getSystemService(ClipboardManager::class.java)

    override fun setText(text: String): Boolean {
        if (text.length > SelectionController.TEXT_CAP) return false
        return try {
            val clip = ClipData.newPlainText(LABEL, text)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                clip.description.extras = PersistableBundle().apply {
                    putBoolean(ClipDescription.EXTRA_IS_SENSITIVE, true)
                }
            }
            manager.setPrimaryClip(clip)
            true
        } catch (e: RemoteException) {
            // Includes TransactionTooLargeException.
            false
        } catch (e: RuntimeException) {
            false
        }
    }

    private companion object {
        const val LABEL = "Copied text"
    }
}

/**
 * [UriOpener] for accepted links. The Intent is built from the pure [IntentPlan]; the only
 * extra step is re-parsing the URI with android.net.Uri and refusing when its host differs from
 * the host the user saw. Only called after the user confirmed the link.
 */
class AndroidUriOpener(context: Context) : UriOpener {
    private val appContext = context.applicationContext

    override fun open(link: ParsedLink): Boolean {
        val plan = IntentPlanner.planFor(link) ?: return false
        if (!IntentPlanner.reparsedHostMatches(link, link.uri.toUri().host)) return false
        val intent = plan.toIntent()
        return try {
            appContext.startActivity(intent)
            true
        } catch (e: ActivityNotFoundException) {
            false
        }
    }
}

/** Glue only: copies the plain description of an [IntentPlan] into an Intent. */
private fun IntentPlan.toIntent(): Intent {
    val intent = Intent(action, data.toUri())
    categories.forEach { intent.addCategory(it) }
    if (selectorAction != null) {
        val selector = Intent(selectorAction, selectorData.orEmpty().toUri())
        selectorCategories.forEach { selector.addCategory(it) }
        intent.selector = selector
    }
    check(flags == IntentNames.FLAG_NEW_TASK) { "the plan allows only NEW_TASK" }
    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    return intent
}

/** Copies the engine's premultiplied RGBA8 into an ARGB_8888 bitmap (same byte order). */
internal fun ThumbnailState.Ready.toImageBitmap(): ImageBitmap {
    val bitmap = createBitmap(width, height, Bitmap.Config.ARGB_8888)
    bitmap.copyPixelsFromBuffer(ByteBuffer.wrap(rgba))
    return bitmap.asImageBitmap()
}
