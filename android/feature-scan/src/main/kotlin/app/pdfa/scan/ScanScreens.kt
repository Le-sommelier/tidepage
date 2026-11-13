package app.pdfa.scan

import app.pdfa.feature.scan.R
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import app.pdfa.design.PdfaSpacing
import app.pdfa.design.PdfaTheme
import app.pdfa.design.PdfaTopBar
import app.pdfa.design.ProgressCard
import app.pdfa.engine.Quad
import app.pdfa.engine.ScanLook

// ---------------------------------------------------------------- shared

/** Progress card while the controller works, and the last problem as a dismissable line. */
@Composable
internal fun StatusArea(state: ScanState, onCancel: () -> Unit, onDismissProblem: () -> Unit, modifier: Modifier = Modifier) {
    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(PdfaSpacing.S2)) {
        state.busy?.let { busy ->
            ProgressCard(
                title = busy.text,
                progress = state.progress,
                cancelLabel = stringResource(R.string.scan_cancel),
                onCancel = onCancel,
            )
        }
        state.problem?.let { problem ->
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(PdfaSpacing.S3)) {
                    Text(problem.text, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.error)
                    TextButton(onClick = onDismissProblem) { Text(stringResource(R.string.scan_dismiss)) }
                }
            }
        }
        if (state.skippedImports > 0 && state.busy == null) {
            Text(stringResource(R.string.scan_skipped, state.skippedImports), style = MaterialTheme.typography.bodyMedium)
        }
    }
}

@Composable
private fun PageThumb(page: ScanPage, modifier: Modifier = Modifier) {
    val bitmap = rememberJpegBitmap(page.result, maxEdge = 320)
    Box(modifier = modifier.clip(MaterialTheme.shapes.small).background(MaterialTheme.colorScheme.surfaceVariant)) {
        if (bitmap != null) {
            Image(bitmap, contentDescription = null, contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize())
        }
    }
}

// ---------------------------------------------------------------- capture

/**
 * Camera area, shutter and the batch strip. [camera] is the preview (see [CameraCapture]); it is
 * a slot so previews and tests can leave the real camera out.
 */
@Composable
internal fun CaptureScreen(
    state: ScanState,
    shutterReady: Boolean,
    onShutter: () -> Unit,
    onPickImages: () -> Unit,
    onOpenPage: (Int) -> Unit,
    onDone: () -> Unit,
    onClose: () -> Unit,
    onCancel: () -> Unit,
    onDismissProblem: () -> Unit,
    onCancelRetake: () -> Unit,
    cameraMessage: String?,
    modifier: Modifier = Modifier,
    camera: @Composable () -> Unit,
) {
    Scaffold(
        modifier = modifier,
        topBar = {
            PdfaTopBar(
                title = stringResource(R.string.scan_title),
                onBack = onClose,
                backIcon = Icons.Default.Close,
                backContentDescription = stringResource(R.string.scan_close),
                actions = {
                    TextButton(onClick = onDone, enabled = state.pages.isNotEmpty() && state.busy == null) {
                        Text(stringResource(R.string.scan_done))
                    }
                },
            )
        },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            Box(Modifier.weight(1f).fillMaxWidth()) {
                camera()
                StatusArea(
                    state,
                    onCancel,
                    onDismissProblem,
                    Modifier.align(Alignment.TopCenter).padding(PdfaSpacing.GutterPhone),
                )
            }
            state.retakeIndex?.let { idx ->
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = PdfaSpacing.GutterPhone),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text(stringResource(R.string.scan_retaking, idx + 1), style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                    TextButton(onClick = onCancelRetake) { Text(stringResource(R.string.scan_retake_cancel)) }
                }
            }
            if (cameraMessage != null) {
                Text(cameraMessage, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(horizontal = PdfaSpacing.GutterPhone))
            }
            ThumbnailStrip(state.pages, onOpenPage)
            Row(
                Modifier.fillMaxWidth().padding(PdfaSpacing.S4),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                IconButton(onClick = onPickImages) {
                    Icon(PhotoLibraryIcon, contentDescription = stringResource(R.string.scan_add_images))
                }
                // Drawn only while a camera is showing: in the ask, denied and no-camera states there is nothing to shoot.
                if (shutterReady) Shutter(enabled = state.busy == null, onClick = onShutter) else Spacer(Modifier.size(76.dp))
                // Keeps the shutter centred.
                Spacer(Modifier.size(PdfaSpacing.MinTouchTarget))
            }
        }
    }
}

@Composable
private fun Shutter(enabled: Boolean, onClick: () -> Unit) {
    val label = stringResource(if (enabled) R.string.scan_shutter else R.string.scan_shutter_wait)
    val ring = MaterialTheme.colorScheme.onBackground
    Box(
        modifier = Modifier
            .size(76.dp)
            .border(3.dp, ring, CircleShape)
            .padding(6.dp)
            .clip(CircleShape)
            .background(if (enabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .semantics { contentDescription = label },
    )
}

@Composable
private fun ThumbnailStrip(pages: List<ScanPage>, onOpenPage: (Int) -> Unit) {
    if (pages.isEmpty()) return
    val count = stringResource(R.string.scan_pages_count, pages.size)
    Column {
        Text(count, style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(horizontal = PdfaSpacing.GutterPhone))
        LazyRow(
            contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = PdfaSpacing.GutterPhone, vertical = PdfaSpacing.S2),
            horizontalArrangement = Arrangement.spacedBy(PdfaSpacing.S2),
        ) {
            itemsIndexed(pages, key = { _, p -> p.id }) { i, page ->
                val label = stringResource(R.string.scan_strip_page, i + 1, pages.size)
                PageThumb(
                    page,
                    Modifier
                        .size(width = 56.dp, height = 72.dp)
                        .clickable(role = Role.Button) { onOpenPage(i) }
                        .semantics { contentDescription = label },
                )
            }
        }
    }
}

// ---------------------------------------------------------------- page list

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun PageListScreen(
    state: ScanState,
    onBack: () -> Unit,
    onEdit: (Int) -> Unit,
    onMoveEarlier: (Int) -> Unit,
    onMoveLater: (Int) -> Unit,
    onRetake: (Int) -> Unit,
    onDelete: (Int) -> Unit,
    onScanMore: () -> Unit,
    onPickImages: () -> Unit,
    onDone: () -> Unit,
    onCancel: () -> Unit,
    onDismissProblem: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Scaffold(
        modifier = modifier,
        topBar = {
            PdfaTopBar(
                title = stringResource(R.string.scan_review_title),
                onBack = onBack,
                backContentDescription = stringResource(R.string.scan_back),
            )
        },
        bottomBar = {
            Column(Modifier.padding(PdfaSpacing.S4), verticalArrangement = Arrangement.spacedBy(PdfaSpacing.S2)) {
                Button(onClick = onDone, enabled = state.pages.isNotEmpty() && state.busy == null, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.scan_done))
                }
                FlowRow(horizontalArrangement = Arrangement.spacedBy(PdfaSpacing.S2), verticalArrangement = Arrangement.spacedBy(PdfaSpacing.S2)) {
                    OutlinedButton(onClick = onScanMore) { Text(stringResource(R.string.scan_add_more)) }
                    OutlinedButton(onClick = onPickImages) { Text(stringResource(R.string.scan_add_images)) }
                }
            }
        },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            StatusArea(state, onCancel, onDismissProblem, Modifier.padding(horizontal = PdfaSpacing.GutterPhone))
            if (state.pages.isEmpty()) {
                Text(
                    stringResource(R.string.scan_review_empty),
                    style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier.padding(PdfaSpacing.GutterPhone),
                )
            }
            LazyColumn(
                contentPadding = androidx.compose.foundation.layout.PaddingValues(PdfaSpacing.GutterPhone),
                verticalArrangement = Arrangement.spacedBy(PdfaSpacing.S3),
            ) {
                itemsIndexed(state.pages, key = { _, p -> p.id }) { i, page ->
                    PageRow(
                        page, i, state.pages.size,
                        onEdit = { onEdit(i) }, onEarlier = { onMoveEarlier(i) }, onLater = { onMoveLater(i) },
                        onRetake = { onRetake(i) }, onDelete = { onDelete(i) },
                        enabled = state.busy == null,
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun PageRow(
    page: ScanPage, index: Int, count: Int,
    onEdit: () -> Unit, onEarlier: () -> Unit, onLater: () -> Unit, onRetake: () -> Unit, onDelete: () -> Unit,
    enabled: Boolean,
) {
    val n = index + 1
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(PdfaSpacing.S3), verticalArrangement = Arrangement.spacedBy(PdfaSpacing.S2)) {
            Row(horizontalArrangement = Arrangement.spacedBy(PdfaSpacing.S3), verticalAlignment = Alignment.CenterVertically) {
                PageThumb(page, Modifier.size(width = 64.dp, height = 88.dp).clickable(enabled = enabled, role = Role.Button, onClick = onEdit))
                Text(stringResource(R.string.scan_page_label, n, count), style = MaterialTheme.typography.titleMedium)
            }
            FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                IconButton(onClick = onEdit, enabled = enabled) { Icon(Icons.Default.Edit, stringResource(R.string.scan_page_edit, n)) }
                IconButton(onClick = onEarlier, enabled = enabled && index > 0) {
                    Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, stringResource(R.string.scan_move_earlier, n))
                }
                IconButton(onClick = onLater, enabled = enabled && index < count - 1) {
                    Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, stringResource(R.string.scan_move_later, n))
                }
                IconButton(onClick = onRetake, enabled = enabled) { Icon(CameraIcon, stringResource(R.string.scan_retake, n)) }
                IconButton(onClick = onDelete, enabled = enabled) { Icon(Icons.Default.Delete, stringResource(R.string.scan_delete, n)) }
            }
        }
    }
}

// ---------------------------------------------------------------- edit page

internal enum class EditTab { CROP, LOOK }

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun EditPageScreen(
    state: ScanState,
    index: Int,
    onBack: () -> Unit,
    onApplyQuad: (Quad) -> Unit,
    onLook: (ScanLook) -> Unit,
    onLevel: (Int) -> Unit,
    onLookAll: () -> Unit,
    onCancel: () -> Unit,
    onDismissProblem: () -> Unit,
    modifier: Modifier = Modifier,
    initialTab: EditTab = EditTab.CROP,
) {
    val page = state.pages.getOrNull(index)
    var tab by remember { mutableStateOf(initialTab) }
    Scaffold(
        modifier = modifier,
        topBar = {
            PdfaTopBar(
                title = stringResource(R.string.scan_edit_title, index + 1),
                onBack = onBack,
                backContentDescription = stringResource(R.string.scan_back),
            )
        },
    ) { padding ->
        if (page == null) return@Scaffold
        Column(Modifier.padding(padding).fillMaxSize()) {
            TabRow(selectedTabIndex = tab.ordinal) {
                Tab(selected = tab == EditTab.CROP, onClick = { tab = EditTab.CROP }, modifier = Modifier.height(PdfaSpacing.MinTouchTarget)) {
                    Text(stringResource(R.string.scan_tab_crop))
                }
                Tab(selected = tab == EditTab.LOOK, onClick = { tab = EditTab.LOOK }, modifier = Modifier.height(PdfaSpacing.MinTouchTarget)) {
                    Text(stringResource(R.string.scan_tab_look))
                }
            }
            Column(
                Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(PdfaSpacing.GutterPhone),
                verticalArrangement = Arrangement.spacedBy(PdfaSpacing.S3),
            ) {
                StatusArea(state, onCancel, onDismissProblem)
                when (tab) {
                    EditTab.CROP -> CropTab(page, onApplyQuad, enabled = state.busy == null)
                    EditTab.LOOK -> LookTab(page, state.levels, onLook, onLevel, onLookAll, enabled = state.busy == null)
                }
            }
        }
    }
}

@Composable
private fun CropTab(page: ScanPage, onApply: (Quad) -> Unit, enabled: Boolean) {
    var draft by remember(page.id, page.quad) { mutableStateOf(page.quad) }
    val picture = rememberJpegBitmap(page.source, maxEdge = 1600)
    val usable = CropEditing.isUsable(draft, page.source.width, page.source.height)
    Text(stringResource(R.string.scan_crop_hint), style = MaterialTheme.typography.bodyLarge)
    CropEditor(picture, page.source.width, page.source.height, draft, { draft = it })
    if (!usable) {
        Text(stringResource(R.string.scan_crop_invalid), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
    }
    FlowRow2 {
        OutlinedButton(onClick = { draft = QuadGeometry.fullFrame(page.source.width, page.source.height) }, enabled = enabled) {
            Text(stringResource(R.string.scan_crop_whole))
        }
        Button(onClick = { onApply(draft) }, enabled = enabled && usable) { Text(stringResource(R.string.scan_crop_apply)) }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun FlowRow2(content: @Composable () -> Unit) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(PdfaSpacing.S2), verticalArrangement = Arrangement.spacedBy(PdfaSpacing.S2)) { content() }
}

@Composable
private fun LookTab(page: ScanPage, levels: ScanLevels, onLook: (ScanLook) -> Unit, onLevel: (Int) -> Unit, onLookAll: () -> Unit, enabled: Boolean) {
    val picture = rememberJpegBitmap(page.result, maxEdge = 1600)
    Box(
        // A fixed height: a full-width preview of a tall page pushed the look buttons and the slider off the screen.
        Modifier.fillMaxWidth().height(280.dp).background(MaterialTheme.colorScheme.surfaceVariant),
    ) {
        if (picture != null) {
            Image(picture, stringResource(R.string.scan_image_description), Modifier.fillMaxSize(), contentScale = ContentScale.Fit)
        }
    }
    LookPicker(page.look, page.level, levels, onLook, onLevel, enabled)
    TextButton(onClick = onLookAll, enabled = enabled) { Text(stringResource(R.string.scan_apply_all)) }
}

/** Original / Colour / Greyscale / Black and white, and the cleanup level. Stateless. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun LookPicker(
    look: ScanLook,
    level: Int,
    levels: ScanLevels,
    onLook: (ScanLook) -> Unit,
    onLevel: (Int) -> Unit,
    enabled: Boolean = true,
    modifier: Modifier = Modifier,
) {
    val group = stringResource(R.string.scan_look_group)
    Column(modifier, verticalArrangement = Arrangement.spacedBy(PdfaSpacing.S2)) {
        Text(group, style = MaterialTheme.typography.titleMedium)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(PdfaSpacing.S2), verticalArrangement = Arrangement.spacedBy(PdfaSpacing.S2)) {
            ScanLook.entries.forEach { option ->
                FilterChip(
                    selected = option == look,
                    onClick = { onLook(option) },
                    enabled = enabled,
                    label = { Text(lookName(option)) },
                    modifier = Modifier.height(PdfaSpacing.MinTouchTarget),
                )
            }
        }
        var slider by remember(level) { mutableFloatStateOf(level.toFloat()) }
        val levelText = stringResource(R.string.scan_level_value, slider.toInt(), levels.max)
        Text(stringResource(R.string.scan_level), style = MaterialTheme.typography.titleMedium)
        Slider(
            value = slider,
            onValueChange = { slider = it },
            onValueChangeFinished = { onLevel(slider.toInt()) },
            valueRange = levels.min.toFloat()..levels.max.toFloat(),
            steps = 0,
            enabled = enabled,
            modifier = Modifier.semantics { stateDescription = levelText },
        )
        Text(levelText, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun lookName(look: ScanLook): String = stringResource(
    when (look) {
        ScanLook.ORIGINAL -> R.string.scan_look_original
        ScanLook.COLOUR -> R.string.scan_look_colour
        ScanLook.GREYSCALE -> R.string.scan_look_greyscale
        ScanLook.BLACK_WHITE -> R.string.scan_look_bw
    },
)

// ---------------------------------------------------------------- previews

private fun previewPage(id: Long, look: ScanLook = ScanLook.COLOUR): ScanPage {
    val bmp = android.graphics.Bitmap.createBitmap(300, 400, android.graphics.Bitmap.Config.ARGB_8888)
    android.graphics.Canvas(bmp).apply {
        drawColor(android.graphics.Color.rgb(244, 241, 232))
        val p = android.graphics.Paint().apply { color = android.graphics.Color.rgb(48, 54, 59) }
        for (i in 0 until 9) drawRect(60f, 80f + i * 30f, 240f - (i % 3) * 30f, 90f + i * 30f, p)
    }
    val bytes = bmp.compressJpeg(80)
    val img = JpegImage(bytes, 300, 400)
    return ScanPage(id, img, QuadGeometry.fullFrame(300, 400), look, 50, img, true)
}

private val previewState get() = ScanState(pages = listOf(previewPage(1), previewPage(2), previewPage(3)))

@Composable
private fun CapturePreviewBody() {
    CaptureScreen(
        state = previewState, shutterReady = true, onShutter = {}, onPickImages = {}, onOpenPage = {}, onDone = {},
        onClose = {}, onCancel = {}, onDismissProblem = {}, onCancelRetake = {}, cameraMessage = null,
    ) { Box(Modifier.fillMaxSize().background(Color.DarkGray)) }
}

@Preview(showBackground = true, widthDp = 360, heightDp = 720)
@Composable
private fun CaptureScreenPreview() = PdfaTheme(darkTheme = false) { CapturePreviewBody() }

@Preview(showBackground = true, widthDp = 360, heightDp = 720, name = "Capture dark")
@Composable
private fun CaptureScreenDarkPreview() = PdfaTheme(darkTheme = true) { CapturePreviewBody() }

@Composable
private fun PageListPreviewBody() {
    PageListScreen(previewState, {}, {}, {}, {}, {}, {}, {}, {}, {}, {}, {})
}

@Preview(showBackground = true, widthDp = 360, heightDp = 720)
@Composable
private fun PageListPreview() = PdfaTheme(darkTheme = false) { PageListPreviewBody() }

@Preview(showBackground = true, widthDp = 360, heightDp = 720, name = "Pages dark")
@Composable
private fun PageListDarkPreview() = PdfaTheme(darkTheme = true) { PageListPreviewBody() }

@Preview(showBackground = true, widthDp = 360, heightDp = 720, fontScale = 2f, name = "Pages 200% font")
@Composable
private fun PageListLargeFontPreview() = PdfaTheme(darkTheme = false) { PageListPreviewBody() }

@Preview(showBackground = true, widthDp = 360, heightDp = 720, name = "Edit crop")
@Composable
private fun EditCropPreview() = PdfaTheme(darkTheme = false) {
    EditPageScreen(previewState, 0, {}, {}, {}, {}, {}, {}, {})
}

@Preview(showBackground = true, widthDp = 360, heightDp = 720, fontScale = 2f, name = "Edit look dark 200% font")
@Composable
private fun EditLookPreview() = PdfaTheme(darkTheme = true) {
    EditPageScreen(previewState, 1, {}, {}, {}, {}, {}, {}, {}, initialTab = EditTab.LOOK)
}

@Preview(showBackground = true, widthDp = 360, heightDp = 640, name = "Camera rationale")
@Composable
private fun CameraAskPreview() = PdfaTheme(darkTheme = false) { CameraGateMessage(CameraGate.ASK, {}, {}, {}) }

@Preview(showBackground = true, widthDp = 360, heightDp = 640, name = "Camera denied dark")
@Composable
private fun CameraDeniedPreview() = PdfaTheme(darkTheme = true) { CameraGateMessage(CameraGate.DENIED, {}, {}, {}) }

@Preview(showBackground = true, widthDp = 360, heightDp = 640, fontScale = 2f, name = "Camera denied 200% font")
@Composable
private fun CameraDeniedLargePreview() = PdfaTheme(darkTheme = false) { CameraGateMessage(CameraGate.DENIED, {}, {}, {}) }
