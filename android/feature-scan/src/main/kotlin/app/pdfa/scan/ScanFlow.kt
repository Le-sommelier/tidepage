package app.pdfa.scan

import app.pdfa.feature.scan.R
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import app.pdfa.design.PdfaBottomSheet
import app.pdfa.design.PdfaSpacing
import kotlinx.coroutines.launch

private const val STEP_CAPTURE = -1
private const val STEP_PAGES = -2

/**
 * The whole scan and images-to-PDF flow: camera, page strip, crop and look, page list, name.
 *
 * - [onPickImages] opens the app's image picker (or pass [rememberScanImagePicker]); pictures it
 *   returns are added with [addPickedImages].
 * - [onSavePdf] gets the suggested name ("Scan 2026-11-10.pdf", edited by the person) and the PDF
 *   bytes. The flow does not clear itself, so a failed save can be retried: call
 *   [ScanController.clear] after a successful save or when the flow is left for good.
 * - [startOnCamera] false starts on the page list (images to PDF): the camera and its permission are only
 *   reached if the person taps "Scan more".
 * - [onClose] is called when the person leaves; if pages exist they are asked first.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ScanFlow(
    controller: ScanController,
    onPickImages: () -> Unit,
    onSavePdf: (suggestedName: String, bytes: ByteArray) -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
    startOnCamera: Boolean = true,
) {
    val state by controller.state.collectAsState()
    val scope = rememberCoroutineScope()
    val shutter = remember { CameraShutter() }
    // -1 camera, -2 page list, 0.. editing that page. Survives rotation.
    var step by rememberSaveable { mutableIntStateOf(if (startOnCamera && controller.state.value.pages.isEmpty()) STEP_CAPTURE else STEP_PAGES) }
    var showSave by rememberSaveable { mutableStateOf(false) }
    var confirmDiscard by rememberSaveable { mutableStateOf(false) }
    var cameraFailed by remember { mutableStateOf(false) }

    fun leave() {
        if (state.pages.isEmpty()) onClose() else confirmDiscard = true
    }

    // A page that no longer exists cannot be edited.
    if (step >= 0 && step !in state.pages.indices) step = STEP_PAGES

    when {
        step == STEP_CAPTURE -> {
            BackHandler { leave() }
            CaptureScreen(
                state = state,
                shutterReady = shutter.ready,
                onShutter = { cameraFailed = false; shutter.capture() },
                onPickImages = onPickImages,
                onOpenPage = { step = it },
                onDone = { step = STEP_PAGES },
                onClose = { leave() },
                onCancel = controller::cancelCurrent,
                onDismissProblem = controller::dismissProblem,
                onCancelRetake = controller::cancelRetake,
                cameraMessage = if (cameraFailed) stringResource(R.string.scan_camera_error) else null,
                modifier = modifier,
            ) {
                CameraCapture(
                    shutter = shutter,
                    onFrame = { frame -> scope.launch { controller.addCapture(frame) } },
                    onPickImagesInstead = onPickImages,
                    onCameraError = { cameraFailed = true },
                )
            }
        }

        step == STEP_PAGES -> {
            BackHandler { step = STEP_CAPTURE }
            PageListScreen(
                state = state,
                onBack = { step = STEP_CAPTURE },
                onEdit = { step = it },
                onMoveEarlier = controller::moveEarlier,
                onMoveLater = controller::moveLater,
                onRetake = { controller.retake(it); step = STEP_CAPTURE },
                onDelete = controller::delete,
                onScanMore = { step = STEP_CAPTURE },
                onPickImages = onPickImages,
                onDone = { showSave = true },
                onCancel = controller::cancelCurrent,
                onDismissProblem = controller::dismissProblem,
                modifier = modifier,
            )
        }

        else -> {
            BackHandler { step = STEP_PAGES }
            EditPageScreen(
                state = state,
                index = step,
                onBack = { step = STEP_PAGES },
                onApplyQuad = { q -> scope.launch { controller.setQuad(step, q) } },
                onLook = { look -> scope.launch { controller.setLook(step, look) } },
                onLevel = { level -> scope.launch { controller.setLevel(step, level) } },
                onLookAll = {
                    val p = state.pages.getOrNull(step)
                    if (p != null) scope.launch { controller.applyLookToAll(p.look, p.level) }
                },
                onCancel = controller::cancelCurrent,
                onDismissProblem = controller::dismissProblem,
                modifier = modifier,
            )
        }
    }

    if (showSave) {
        val suggested = remember { controller.suggestedName() }
        var name by rememberSaveable { mutableStateOf(suggested.removeSuffix(".pdf")) }
        PdfaBottomSheet(onDismissRequest = { showSave = false }) {
            Column(Modifier.padding(bottom = PdfaSpacing.S5), verticalArrangement = Arrangement.spacedBy(PdfaSpacing.S3)) {
                Text(stringResource(R.string.scan_save_title), style = MaterialTheme.typography.titleLarge)
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text(stringResource(R.string.scan_save_name)) },
                    suffix = { Text(".pdf") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Button(
                    enabled = state.busy == null,
                    onClick = {
                        scope.launch {
                            val bytes = controller.buildPdf()
                            if (bytes != null) {
                                showSave = false
                                onSavePdf(ScanNaming.sanitize(name, suggested), bytes)
                            }
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text(stringResource(R.string.scan_save)) }
                TextButton(onClick = { showSave = false }, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.scan_cancel))
                }
                StatusArea(state, controller::cancelCurrent, controller::dismissProblem)
            }
        }
    }

    if (confirmDiscard) {
        AlertDialog(
            onDismissRequest = { confirmDiscard = false },
            title = { Text(stringResource(R.string.scan_discard_title, state.pages.size)) },
            text = { Text(stringResource(R.string.scan_discard_body)) },
            confirmButton = {
                TextButton(onClick = { confirmDiscard = false; controller.clear(); onClose() }) { Text(stringResource(R.string.scan_discard)) }
            },
            dismissButton = {
                TextButton(onClick = { confirmDiscard = false }) { Text(stringResource(R.string.scan_keep)) }
            },
        )
    }
}
