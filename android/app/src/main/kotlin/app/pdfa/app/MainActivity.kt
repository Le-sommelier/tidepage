package app.pdfa.app

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import app.pdfa.app.intent.IntentRouter
import app.pdfa.app.intent.Kind
import app.pdfa.app.intent.OpenRequest
import app.pdfa.app.ui.PdfaApp

/**
 * The only exported activity. Launcher, PDF view and share targets all land here.
 *
 * The launch intent is read only on a fresh start (no saved state). After process death or rotation
 * the navigation comes back from saved state, and the intent is not processed again.
 */
class MainActivity : ComponentActivity() {

    private val container: AppContainer
        get() = (application as PdfaApplication).container

    /** One pending request from an intent, consumed by the root composable. */
    private var incoming by mutableStateOf<OpenRequest?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        // Edge to edge, with status and navigation bar icons that follow light or dark mode.
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        liveInstances++
        if (savedInstanceState == null) {
            acceptIntent(intent)
        } else {
            // A request that was not consumed yet when the activity was recreated (rotation, theme change) is not lost.
            restorePending(savedInstanceState)
        }
        setContent {
            PdfaApp(
                container = container,
                incoming = incoming,
                onIncomingHandled = { incoming = null },
            )
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        incoming?.let { request ->
            outState.putString(KEY_KIND, request.kind.name)
            outState.putStringArrayList(KEY_URIS, ArrayList(request.refs.map { it.uri }))
        }
    }

    private fun restorePending(saved: Bundle) {
        val kind = saved.getString(KEY_KIND)?.let { name -> Kind.entries.firstOrNull { it.name == name } } ?: return
        val refs = saved.getStringArrayList(KEY_URIS).orEmpty().mapNotNull { IntentRouter.acceptDocUri(it) }
        if (refs.isNotEmpty()) incoming = OpenRequest(kind, refs)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        acceptIntent(intent)
    }

    override fun onDestroy() {
        // The user left the app (Back from Home, task removed): end the engine process too. Rotation and
        // other recreations keep it. A later start builds a fresh engine client.
        // Only when no other instance exists: a new intent that clears the task creates the new activity
        // BEFORE this one is destroyed, and the new one is already using the engine.
        liveInstances--
        if (isFinishing && liveInstances == 0) container.engineProvider.shutdown()
        super.onDestroy()
    }

    private companion object {
        const val KEY_KIND = "pending_kind"
        const val KEY_URIS = "pending_uris"

        /** Created and not yet destroyed instances. Main thread only. */
        var liveInstances = 0
    }

    private fun acceptIntent(intent: Intent) {
        // Parsing reads only intent fields. The document itself is read later, off the main thread.
        IntentRouter.parse(intent)?.let { incoming = it }
    }
}
