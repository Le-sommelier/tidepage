package app.pdfa.app.tools

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import app.pdfa.app.AppContainer
import app.pdfa.app.R
import app.pdfa.app.intent.DocRef
import app.pdfa.app.intent.IntentRouter
import app.pdfa.app.viewer.findComponentActivity
import app.pdfa.pages.tools.MergeActions
import app.pdfa.pages.tools.MergeController
import app.pdfa.pages.tools.MergePhase
import app.pdfa.pages.tools.MergeScreen
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Keeps the merge list and a running merge across rotation. The picked bytes and typed passwords live
 * inside the controller's memory only; nothing here is saved state.
 */
internal class MergeHolder : ViewModel() {
    private var made: Pair<MergeController, ToolsIo>? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var job: Job? = null

    val io: ToolsIo get() = made!!.second

    fun controller(container: AppContainer): MergeController {
        made?.let { return it.first }
        val io = ToolsIo(container.streams)
        val c = MergeController(container.engineProvider.engine) { io.readSource(it) }
        made = c to io
        return c
    }

    /** The files the multi-select picker returned. */
    fun add(uris: List<String>) {
        val c = made?.first ?: return
        scope.launch { c.addSources(uris) }
    }

    fun submitPassword(id: Long, password: String) {
        val c = made?.first ?: return
        scope.launch { c.submitPassword(id, password) }
    }

    fun merge() {
        val c = made?.first ?: return
        job = scope.launch { c.merge() }
    }

    /** Cancelling makes the engine client restart the engine process (by design). */
    fun cancel() {
        job?.cancel()
    }

    /** The create-document picker's result. */
    fun saveTo(uri: String) {
        val (c, io) = made ?: return
        scope.launch { c.save(uri) { target, bytes -> io.writeFile(target, bytes) } }
    }

    fun reset() {
        job?.cancel()
        made?.first?.reset()
    }

    override fun onCleared() {
        scope.cancel()
        made?.first?.release()
    }
}

/** The Merge PDFs screen: the pickers, the merge coroutine and the way into the viewer. */
@Composable
internal fun MergeRoute(container: AppContainer, onBack: () -> Unit, onOpen: (DocRef) -> Unit) {
    val activity = LocalContext.current.findComponentActivity()
    val holder = remember(activity) { ViewModelProvider(activity)[MergeHolder::class.java] }
    val controller = remember(holder) { holder.controller(container) }
    val state by controller.state.collectAsState()
    var leaveAsk by remember { mutableStateOf(false) }

    val pick = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isNotEmpty()) holder.add(uris.map { it.toString() })
    }
    val create = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/pdf")) { uri ->
        if (uri != null) holder.saveTo(uri.toString())
    }
    DisposableEffect(holder) {
        onDispose { if (!activity.isChangingConfigurations) holder.reset() }
    }

    val busy = state.phase == MergePhase.MERGING || state.phase == MergePhase.SAVING
    val leave = { if (state.phase == MergePhase.MERGED) leaveAsk = true else onBack() }
    BackHandler(enabled = true) { if (!busy) leave() }

    MergeScreen(
        controller = controller,
        actions = MergeActions(
            onBack = { if (!busy) leave() },
            onAddFiles = { pick.launch(arrayOf("application/pdf")) },
            onSubmitPassword = holder::submitPassword,
            onMerge = holder::merge,
            onCancel = holder::cancel,
            onSave = { create.launch("Merged.pdf") },
            onOpenResult = { IntentRouter.acceptDocUri(state.savedTo)?.let(onOpen) },
            onStartOver = controller::reset,
        ),
    )
    if (leaveAsk) {
        AlertDialog(
            onDismissRequest = { leaveAsk = false },
            title = { Text(stringResource(R.string.merge_leave_title)) },
            text = { Text(stringResource(R.string.merge_leave_body)) },
            confirmButton = { TextButton(onClick = { leaveAsk = false; onBack() }) { Text(stringResource(R.string.merge_leave_discard)) } },
            dismissButton = { TextButton(onClick = { leaveAsk = false }) { Text(stringResource(R.string.merge_leave_stay)) } },
        )
    }
}
