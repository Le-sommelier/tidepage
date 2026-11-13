package app.pdfa.app.viewer

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import androidx.activity.ComponentActivity
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import app.pdfa.app.AppContainer
import app.pdfa.app.intent.DocRef
import app.pdfa.app.intent.OpenRequest
import app.pdfa.app.settings.SaveBehaviour

/**
 * The slot the viewer fills. The shell calls [Content] for the Viewer route and never looks
 * inside. [onBack] returns to Home (or the screen below in the stack).
 */
interface ViewerEntry {
    @Composable
    fun Content(request: OpenRequest, onBack: () -> Unit)
}

/**
 * Holds the open document across rotation. Scoped to the activity: one [ViewerSession] at a time,
 * closed when the Viewer route leaves the screen for good or the activity is destroyed. After
 * process death nothing of it survives: the route's document URI is the only thing restored, and
 * the document is imported and opened again (the password, if any, is asked again).
 */
internal class ViewerSessions : ViewModel() {
    private var current: ViewerSession? = null

    fun obtain(doc: DocRef, restoreDocId: String?, create: () -> ViewerSession): ViewerSession {
        current?.let { if (it.doc == doc && it.restoreDocId == restoreDocId) return it else it.close() }
        return create().also { current = it }
    }

    fun peek(): ViewerSession? = current

    fun close(doc: DocRef) {
        val session = current ?: return
        if (session.doc != doc) return
        current = null
        session.close()
    }

    override fun onCleared() {
        current?.close()
        current = null
    }
}

/** The real viewer: :feature-viewer on the real engine, with the add-ons of :feature-viewer-extras. */
class RealViewerEntry(private val container: AppContainer) : ViewerEntry {
    @Composable
    override fun Content(request: OpenRequest, onBack: () -> Unit) {
        val doc = request.refs.first()
        val activity = LocalContext.current.findComponentActivity()
        val sessions = remember(activity) { ViewModelProvider(activity)[ViewerSessions::class.java] }
        val session = remember(doc, request.restoreDocId, sessions) {
            sessions.obtain(doc, request.restoreDocId) {
                ViewerSession(doc, container, activity.applicationContext, request.restoreDocId)
            }
        }
        DisposableEffect(doc, sessions) {
            onDispose {
                // Rotation keeps the document open. Leaving the screen closes the handle.
                if (!activity.isChangingConfigurations) sessions.close(doc)
            }
        }
        val settings by container.settings.settings.collectAsState()
        ViewerHost(
            session = session,
            onBack = onBack,
            replaceOriginal = settings.saveBehaviour == SaveBehaviour.REPLACE,
        )
    }
}

internal fun Context.findComponentActivity(): ComponentActivity {
    var c: Context? = this
    while (c is ContextWrapper) {
        if (c is ComponentActivity) return c
        c = c.baseContext
    }
    error("the viewer needs a ComponentActivity, got ${(this as? Activity)?.javaClass?.simpleName ?: "a plain context"}")
}
