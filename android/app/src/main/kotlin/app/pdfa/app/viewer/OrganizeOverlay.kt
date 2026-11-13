package app.pdfa.app.viewer

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import app.pdfa.app.R
import app.pdfa.app.save.DocumentSession
import app.pdfa.organize.OrganizeController
import app.pdfa.organize.OrganizeScreen
import kotlinx.coroutines.launch

/** "Report.pdf" becomes "Report extract.pdf": the picker starts on a name that is not the original's. */
internal fun extractName(title: String): String {
    val base = title.removeSuffix(".pdf").removeSuffix(".PDF").ifBlank { "Document" }
    return "$base extract.pdf"
}

/**
 * The page organizer on top of the viewer. Every change goes through the document session, so undo,
 * dirty state and autosave behave as everywhere else. Extract writes the chosen pages to a new file
 * picked with the system's create-document screen.
 */
@Composable
internal fun OrganizeOverlay(
    session: ViewerSession,
    document: DocumentSession,
    title: String,
    onDone: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val controller = remember(document) {
        OrganizeController(session.engine, document.handle) { change -> document.edit(change) }
    }
    val state by controller.state.collectAsState()
    val scope = rememberCoroutineScope()
    val resources = LocalContext.current.resources
    var notice by remember { mutableStateOf<String?>(null) }
    // The pages the picker was opened for. Survives the activity being recreated meanwhile.
    var pending by rememberSaveable { mutableStateOf<ArrayList<Int>?>(null) }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/pdf")) { uri ->
        val pages = pending
        pending = null
        if (uri != null && pages != null) {
            scope.launch {
                // A failed extract is reported by the screen itself (controller.problem).
                val bytes = controller.extract(pages) ?: return@launch
                notice = if (session.writeNewFile(uri.toString(), bytes)) {
                    resources.getQuantityString(R.plurals.organize_extract_done, pages.size, pages.size)
                } else {
                    resources.getString(R.string.organize_extract_failed)
                }
            }
        }
    }

    BackHandler(enabled = true) { if (!state.busy) onDone() }

    OrganizeScreen(
        controller = controller,
        engine = session.engine,
        handle = document.handle,
        onDone = onDone,
        onExtract = { pages ->
            notice = null
            pending = ArrayList(pages)
            picker.launch(extractName(title))
        },
        notice = notice,
        onNoticeDismiss = { notice = null },
        modifier = modifier,
    )
}
