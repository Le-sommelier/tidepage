package app.pdfa.app.tools

import android.content.Context
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import app.pdfa.app.AppContainer
import app.pdfa.app.R
import app.pdfa.app.intent.DocRef
import app.pdfa.app.intent.IntentRouter
import app.pdfa.app.viewer.findComponentActivity
import app.pdfa.scan.AndroidFrameCodec
import app.pdfa.scan.ImageImporter
import app.pdfa.scan.ScanClock
import app.pdfa.scan.ScanController
import app.pdfa.scan.ScanFlow
import app.pdfa.scan.addPickedImages
import app.pdfa.scan.rememberScanImagePicker
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.time.LocalDate

/** What the save sheet's last attempt did. [uri] is the new file when it worked. */
internal sealed interface ScanSaveResult {
    data class Saved(val uri: String) : ScanSaveResult
    data object Failed : ScanSaveResult
}

/**
 * Keeps the scan pages across rotation (the pages are JPEG bytes in the controller, never saved
 * state) and does the work the pickers' results trigger. Released when the person leaves the flow.
 */
internal class ScanHolder : ViewModel() {
    private var controller: ScanController? = null
    private var importer: ImageImporter? = null
    private var io: ToolsIo? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    /** The PDF waiting for the create-document picker's answer (kept after a failure, for Retry). */
    private var pending: Pair<String, ByteArray>? = null

    private val _result = MutableStateFlow<ScanSaveResult?>(null)
    val result: StateFlow<ScanSaveResult?> = _result.asStateFlow()

    private val _capped = MutableStateFlow<Int?>(null)

    /** Set to the cap when a share held more pictures than are added; the screen says so. */
    val capped: StateFlow<Int?> = _capped.asStateFlow()

    fun dismissCapNotice() {
        _capped.value = null
    }

    val pendingName: String? get() = pending?.first

    fun controller(container: AppContainer, context: Context): ScanController {
        controller?.let { return it }
        val c = ScanController(container.engineProvider.engine, AndroidFrameCodec(), ScanClock { LocalDate.now() })
        controller = c
        importer = ImageImporter(context)
        io = ToolsIo(container.streams)
        return c
    }

    /**
     * Images shared into the app: each URI is validated, the count is capped, and they become pages.
     * Safe to call before the screen exists (the intent arrives first).
     */
    fun addShared(container: AppContainer, context: Context, refs: List<DocRef>) {
        val c = controller(container, context)
        val imp = importer ?: return
        val valid = refs.mapNotNull { IntentRouter.acceptDocUri(it.uri) }
        if (valid.size > MAX_SHARED_IMAGES) _capped.value = MAX_SHARED_IMAGES
        val uris = valid.take(MAX_SHARED_IMAGES).map { Uri.parse(it.uri) }
        if (uris.isEmpty()) return
        scope.launch { c.addPickedImages(imp, uris) }
    }

    /** The "Save" of the flow: remember the bytes until the create-document picker answers. */
    fun stage(name: String, bytes: ByteArray) {
        pending = name to bytes
        _result.value = null
    }

    /** The create-document picker's result. A null [uri] is a cancel: the pages stay, nothing is said. */
    fun saveTo(uri: String?) {
        val c = controller ?: return
        val (_, bytes) = pending ?: return
        val writer = io ?: return
        if (uri == null) return
        scope.launch {
            if (writer.writeFile(uri, bytes)) {
                c.clear()
                pending = null
                _result.value = ScanSaveResult.Saved(uri)
            } else {
                _result.value = ScanSaveResult.Failed
            }
        }
    }

    fun dismissResult() {
        _result.value = null
    }

    /** Lets go of every page and picture. A later visit starts empty. */
    fun release() {
        controller?.clear()
        controller = null
        importer = null
        io = null
        pending = null
        _result.value = null
        _capped.value = null
    }

    override fun onCleared() {
        scope.cancel()
        release()
    }

    companion object {
        /** A share of more pictures than this keeps the first ones (every page lives in memory). */
        const val MAX_SHARED_IMAGES = 30

        fun of(context: Context): ScanHolder =
            ViewModelProvider(context.findComponentActivity())[ScanHolder::class.java]
    }
}

/**
 * Scan (camera first) and Images to PDF (pictures first). [startOnCamera] is false for the second.
 * The camera permission is asked inside the flow, only when the camera screen is shown.
 */
@Composable
internal fun ScanRoute(container: AppContainer, startOnCamera: Boolean, onBack: () -> Unit, onOpen: (DocRef) -> Unit) {
    val context = LocalContext.current
    val holder = remember(context) { ScanHolder.of(context) }
    val controller = remember(holder) { holder.controller(container, context.applicationContext) }
    val result by holder.result.collectAsState()
    val capped by holder.capped.collectAsState()

    val create = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/pdf")) { uri ->
        holder.saveTo(uri?.toString())
    }
    val activity = remember(context) { context.findComponentActivity() }
    DisposableEffect(holder) {
        onDispose { if (!activity.isChangingConfigurations) holder.release() }
    }

    ScanFlow(
        controller = controller,
        onPickImages = rememberScanImagePicker(controller),
        onSavePdf = { name, bytes ->
            holder.stage(name, bytes)
            create.launch(name)
        },
        onClose = onBack,
        startOnCamera = startOnCamera,
    )

    capped?.let { max ->
        AlertDialog(
            onDismissRequest = { holder.dismissCapNotice() },
            title = { Text(stringResource(R.string.scan_capped_title)) },
            text = { Text(stringResource(R.string.scan_capped_body, max)) },
            confirmButton = { TextButton(onClick = { holder.dismissCapNotice() }) { Text(stringResource(R.string.scan_capped_ok)) } },
        )
    }

    when (val r = result) {
        is ScanSaveResult.Saved -> AlertDialog(
            onDismissRequest = { holder.dismissResult() },
            title = { Text(stringResource(R.string.scan_saved_title)) },
            text = { Text(stringResource(R.string.scan_saved_body)) },
            confirmButton = {
                TextButton(onClick = {
                    holder.dismissResult()
                    IntentRouter.acceptDocUri(r.uri)?.let(onOpen)
                }) { Text(stringResource(R.string.scan_saved_open)) }
            },
            dismissButton = {
                TextButton(onClick = { holder.dismissResult(); onBack() }) { Text(stringResource(R.string.scan_saved_done)) }
            },
        )
        ScanSaveResult.Failed -> AlertDialog(
            onDismissRequest = { holder.dismissResult() },
            title = { Text(stringResource(R.string.scan_save_failed_title)) },
            text = { Text(stringResource(R.string.scan_save_failed_body)) },
            confirmButton = {
                TextButton(onClick = {
                    holder.dismissResult()
                    holder.pendingName?.let { create.launch(it) }
                }) { Text(stringResource(R.string.scan_save_failed_retry)) }
            },
            dismissButton = {
                TextButton(onClick = { holder.dismissResult() }) { Text(stringResource(R.string.scan_save_failed_keep)) }
            },
        )
        null -> Unit
    }
}
