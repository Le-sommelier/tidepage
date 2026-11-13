package app.pdfa.viewer.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import app.pdfa.design.PdfaTopBar
import app.pdfa.feature.viewer.R
import app.pdfa.viewer.ViewerState
import app.pdfa.viewer.ViewerViewModel

/**
 * The single-document viewer. [toolTray] is a slot for the app's bottom tools; it is shown only
 * once a document is ready. The window is always secure (FLAG_SECURE) while the password sheet is up;
 * [secureContent] also secures it while reading (the app wires this to a setting).
 *
 * Slots for the app's viewer add-ons, all shown only once a document is ready: [topBarActions] in the
 * top bar, [header] between the top bar and the pages (search bar), [overlay] above the pages and
 * below the fast scroller (highlights). [onTap] and [onLongPress] get surface pixel positions.
 */
@Composable
fun ViewerScreen(
    viewModel: ViewerViewModel,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    toolTray: @Composable () -> Unit = {},
    secureContent: Boolean = false,
    topBarActions: @Composable RowScope.() -> Unit = {},
    header: @Composable () -> Unit = {},
    overlay: @Composable BoxScope.() -> Unit = {},
    onTap: (Offset) -> Unit = {},
    onLongPress: (Offset) -> Unit = {},
) {
    val state by viewModel.state.collectAsState()
    val title by viewModel.title.collectAsState()
    val banner by viewModel.safeModeBanner.collectAsState()
    // Do not read the viewport value here: it changes on every pan frame. Derive only what changes rarely.
    val view = viewModel.view.collectAsState()
    val pageBadge by remember { derivedStateOf { view.value.pageBadge } }
    SecureWindowEffect(enabled = secureContent || state is ViewerState.PasswordRequired)

    Column(modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        PdfaTopBar(title = title, onBack = onBack, actions = { if (state is ViewerState.Ready) topBarActions() })
        if (state is ViewerState.Ready) header()
        Box(Modifier.weight(1f).fillMaxWidth()) {
            when (val s = state) {
                ViewerState.Loading -> Centered {
                    CircularProgressIndicator()
                    Spacer(Modifier.height(16.dp))
                    Text(stringResource(R.string.viewer_opening), style = MaterialTheme.typography.bodyLarge)
                }
                is ViewerState.Ready -> {
                    PageSurface(viewModel, onTap = onTap, onLongPress = onLongPress)
                    overlay()
                    FastScroller(
                        fractionProvider = { view.value.scrollFraction },
                        badge = pageBadge,
                        onFraction = viewModel::onFastScroll,
                        modifier = Modifier.align(Alignment.CenterEnd),
                    )
                    if (banner != null) {
                        SafeModeBannerView(onDismiss = viewModel::dismissSafeModeBanner, modifier = Modifier.align(Alignment.TopCenter))
                    }
                }
                is ViewerState.PasswordRequired -> {
                    EmptyState(Icons.Filled.Lock, R.string.viewer_password_title, null, null, null)
                    PasswordSheet(s, onSubmit = viewModel::submitPassword, onCancel = onBack)
                }
                ViewerState.Locked -> EmptyState(
                    Icons.Filled.Lock, R.string.viewer_locked_title, R.string.viewer_locked_body, R.string.viewer_back, onBack,
                )
                is ViewerState.Corrupted -> EmptyState(
                    Icons.Filled.Warning, R.string.viewer_corrupted_title, R.string.viewer_corrupted_body, R.string.viewer_back, onBack,
                )
                ViewerState.TooLarge -> EmptyState(
                    Icons.Filled.Warning, R.string.viewer_toolarge_title, R.string.viewer_toolarge_body, R.string.viewer_back, onBack,
                )
                is ViewerState.Failed -> EmptyState(
                    Icons.Filled.Warning, R.string.viewer_failed_title, R.string.viewer_failed_body, R.string.viewer_try_again, viewModel::start,
                )
            }
        }
        if (state is ViewerState.Ready) {
            Box(Modifier.windowInsetsPadding(WindowInsets.navigationBars)) { toolTray() }
        }
    }
}

@Composable
private fun Centered(content: @Composable () -> Unit) {
    Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) { content() }
    }
}

@Composable
private fun EmptyState(
    icon: ImageVector,
    title: Int,
    body: Int?,
    actionLabel: Int?,
    onAction: (() -> Unit)?,
) {
    Centered {
        Icon(icon, contentDescription = null, modifier = Modifier.size(48.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(16.dp))
        Text(stringResource(title), style = MaterialTheme.typography.titleLarge, textAlign = TextAlign.Center)
        if (body != null) {
            Spacer(Modifier.height(8.dp))
            Text(
                stringResource(body),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
        }
        if (actionLabel != null && onAction != null) {
            Spacer(Modifier.height(24.dp))
            Button(onClick = onAction) { Text(stringResource(actionLabel)) }
        }
    }
}

@Composable
private fun SafeModeBannerView(onDismiss: () -> Unit, modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier.fillMaxWidth().padding(8.dp).semantics { liveRegion = LiveRegionMode.Polite },
        color = MaterialTheme.colorScheme.primaryContainer,
        contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
        shape = MaterialTheme.shapes.medium,
        tonalElevation = 2.dp,
    ) {
        Row(Modifier.padding(start = 16.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Icon(Icons.Filled.Info, contentDescription = null)
            Text(stringResource(R.string.viewer_safe_mode), style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
            IconButton(onClick = onDismiss) { Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.viewer_dismiss)) }
        }
    }
}
