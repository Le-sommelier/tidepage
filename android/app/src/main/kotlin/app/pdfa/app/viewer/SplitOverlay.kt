package app.pdfa.app.viewer

import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import app.pdfa.app.save.DocumentSession
import app.pdfa.app.tools.treePartSink
import app.pdfa.pages.tools.SplitActions
import app.pdfa.pages.tools.SplitController
import app.pdfa.pages.tools.SplitPhase
import app.pdfa.pages.tools.SplitScreen
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * Extract pages or split the open document. Works on the document as it is now, including unsaved
 * edits, and never changes it. A pending autosave is written first, because cancelling restarts the
 * engine and the open document is then reopened from disk.
 */
@Composable
internal fun SplitOverlay(
    session: ViewerSession,
    document: DocumentSession,
    title: String,
    onDone: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val controller = remember(document) {
        SplitController(session.engine, document.handle, document.state.value.pageCount) { document.flush() }
    }
    val state by controller.state.collectAsState()
    val job = remember { arrayOfNulls<Job>(1) }

    val saveFile = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/pdf")) { uri: Uri? ->
        if (uri != null) scope.launch { controller.saveFile { bytes -> session.writeNewFile(uri.toString(), bytes) } }
    }
    val pickFolder = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { tree: Uri? ->
        if (tree != null) {
            scope.launch {
                controller.saveParts(treePartSink(context, tree) { target, bytes -> session.writeNewFile(target, bytes) })
            }
        }
    }

    val busy = state.phase == SplitPhase.WORKING || state.phase == SplitPhase.SAVING
    BackHandler(enabled = true) { if (!busy) onDone() }

    SplitScreen(
        controller = controller,
        actions = SplitActions(
            onClose = onDone,
            onRun = { job[0] = scope.launch { controller.run() } },
            onCancel = { job[0]?.cancel() },
            onSaveFile = { saveFile.launch(extractName(title)) },
            onSaveParts = { pickFolder.launch(null) },
        ),
        modifier = modifier,
    )
}
