package app.pdfa.scan

import app.pdfa.feature.scan.R
import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Matrix
import android.net.Uri
import android.provider.Settings
import android.util.Size
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.core.resolutionselector.AspectRatioStrategy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import app.pdfa.design.PdfaSpacing
import app.pdfa.engine.ImageFrame
import java.util.concurrent.Executors
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.suspendCancellableCoroutine

/** What the camera area shows before there is a picture to take. */
internal enum class CameraGate { ASK, DENIED, UNAVAILABLE, READY }

/** Handle the capture screen uses to take a picture. */
class CameraShutter internal constructor() {
    internal var action: (() -> Unit)? = null

    /** True once the camera is showing a preview and a picture can be taken. */
    var ready by mutableStateOf(false)
        internal set

    fun capture() {
        action?.invoke()
    }
}

/**
 * Camera preview that takes one picture at a time into memory.
 *
 * The CAMERA permission is asked for only when this composable is on screen and the person taps
 * "Allow camera": first a rationale ("Camera is only used to scan. Nothing leaves your phone."),
 * and if refused an explanation with [onPickImagesInstead]. Pictures go straight to [onFrame] as
 * RGBA, never to a file, and are limited to about 8 megapixels. [onFrame] is called on a
 * background thread; the frame is the callee's to drop.
 *
 * Needs a real camera: not covered by JVM tests.
 */
@Composable
fun CameraCapture(
    shutter: CameraShutter,
    onFrame: (ImageFrame) -> Unit,
    onPickImagesInstead: () -> Unit,
    onCameraError: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    var gate by remember {
        mutableStateOf(
            when {
                !context.packageManager.hasSystemFeature(PackageManager.FEATURE_CAMERA_ANY) -> CameraGate.UNAVAILABLE
                hasCameraPermission(context) -> CameraGate.READY
                else -> CameraGate.ASK
            },
        )
    }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        gate = if (granted) CameraGate.READY else CameraGate.DENIED
    }
    when (gate) {
        CameraGate.READY -> CameraPreview(shutter, onFrame, onCameraError, modifier)
        else -> CameraGateMessage(
            gate = gate,
            onAllow = { launcher.launch(Manifest.permission.CAMERA) },
            onOpenSettings = { openAppSettings(context) },
            onPickImagesInstead = onPickImagesInstead,
            modifier = modifier,
        )
    }
}

private fun hasCameraPermission(context: Context) =
    ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED

private fun openAppSettings(context: Context) {
    val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null))
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    try {
        context.startActivity(intent)
    } catch (e: android.content.ActivityNotFoundException) {
        // No settings screen to open: the other button still works.
    }
}

/** The rationale, denied and no-camera states. Stateless so previews can show each. */
@Composable
internal fun CameraGateMessage(
    gate: CameraGate,
    onAllow: () -> Unit,
    onOpenSettings: () -> Unit,
    onPickImagesInstead: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(PdfaSpacing.GutterPhone),
        verticalArrangement = Arrangement.spacedBy(PdfaSpacing.S3, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        val (title, body) = when (gate) {
            CameraGate.DENIED -> stringResource(R.string.scan_camera_denied_title) to stringResource(R.string.scan_camera_denied_body)
            CameraGate.UNAVAILABLE -> stringResource(R.string.scan_camera_none_title) to stringResource(R.string.scan_camera_none_body)
            else -> stringResource(R.string.scan_camera_ask_title) to stringResource(R.string.scan_camera_privacy)
        }
        Text(title, style = MaterialTheme.typography.titleLarge)
        Text(body, style = MaterialTheme.typography.bodyLarge)
        if (gate == CameraGate.ASK || gate == CameraGate.DENIED) {
            Button(onClick = onAllow, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(if (gate == CameraGate.ASK) R.string.scan_camera_allow else R.string.scan_camera_try_again))
            }
        }
        if (gate == CameraGate.DENIED) {
            OutlinedButton(onClick = onOpenSettings, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.scan_camera_open_settings))
            }
        }
        OutlinedButton(onClick = onPickImagesInstead, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.scan_pick_images))
        }
    }
}

@Composable
private fun CameraPreview(
    shutter: CameraShutter,
    onFrame: (ImageFrame) -> Unit,
    onCameraError: () -> Unit,
    modifier: Modifier,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val currentOnFrame by rememberUpdatedState(onFrame)
    val currentOnError by rememberUpdatedState(onCameraError)
    val worker = remember { Executors.newSingleThreadExecutor() }
    val previewView = remember {
        PreviewView(context).apply { implementationMode = PreviewView.ImplementationMode.COMPATIBLE }
    }
    val imageCapture = remember {
        // About 8 Mpx at 4:3: far under the 40 Mpx cap, and 32 MB as RGBA.
        val selector = ResolutionSelector.Builder()
            .setAspectRatioStrategy(AspectRatioStrategy.RATIO_4_3_FALLBACK_AUTO_STRATEGY)
            .setResolutionStrategy(ResolutionStrategy(Size(3264, 2448), ResolutionStrategy.FALLBACK_RULE_CLOSEST_LOWER_THEN_HIGHER))
            .build()
        ImageCapture.Builder()
            .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
            .setResolutionSelector(selector)
            .build()
    }

    LaunchedEffect(lifecycleOwner) {
        try {
            val provider = ProcessCameraProvider.getInstance(context).awaitResult(ContextCompat.getMainExecutor(context))
            val preview = Preview.Builder().build().also { it.surfaceProvider = previewView.surfaceProvider }
            provider.unbindAll()
            provider.bindToLifecycle(lifecycleOwner, CameraSelector.DEFAULT_BACK_CAMERA, preview, imageCapture)
            shutter.ready = true
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            currentOnError()
        }
    }

    DisposableEffect(shutter) {
        shutter.action = {
            // Into memory only: no file, no MediaStore.
            imageCapture.takePicture(
                worker,
                object : ImageCapture.OnImageCapturedCallback() {
                    override fun onCaptureSuccess(image: ImageProxy) {
                        try {
                            val frame = image.toUprightFrame()
                            currentOnFrame(frame)
                        } catch (e: RuntimeException) {
                            currentOnError()
                        } catch (e: OutOfMemoryError) {
                            currentOnError()
                        } finally {
                            image.close()
                        }
                    }

                    override fun onError(exception: ImageCaptureException) {
                        currentOnError()
                    }
                },
            )
        }
        onDispose {
            shutter.action = null
            shutter.ready = false
            try {
                ProcessCameraProvider.getInstance(context).get().unbindAll()
            } catch (e: Exception) {
                // Nothing bound.
            }
            worker.shutdown()
        }
    }

    val label = stringResource(R.string.scan_preview_description)
    AndroidView(
        factory = { previewView },
        modifier = modifier.semantics { contentDescription = label },
    )
}

/** The picture turned upright by the camera's rotation, as RGBA. The proxy is closed by the caller. */
private fun ImageProxy.toUprightFrame(): ImageFrame {
    val raw = toBitmap()
    val turn = imageInfo.rotationDegrees
    val upright = if (turn == 0) raw else {
        Bitmap.createBitmap(raw, 0, 0, raw.width, raw.height, Matrix().apply { postRotate(turn.toFloat()) }, true)
    }
    try {
        return upright.toFrame()
    } finally {
        if (upright !== raw) upright.recycle()
        raw.recycle()
    }
}

private suspend fun <T> com.google.common.util.concurrent.ListenableFuture<T>.awaitResult(executor: java.util.concurrent.Executor): T =
    suspendCancellableCoroutine { cont ->
        addListener({
            try {
                cont.resume(get())
            } catch (e: Exception) {
                cont.resumeWithException(e.cause ?: e)
            }
        }, executor)
        cont.invokeOnCancellation { cancel(false) }
    }
