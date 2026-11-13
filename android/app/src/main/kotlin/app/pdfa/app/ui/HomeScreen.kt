package app.pdfa.app.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.Surface
import androidx.compose.material3.TextButton
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import app.pdfa.app.R
import app.pdfa.app.RecoverableWork
import app.pdfa.app.intent.DocRef
import app.pdfa.app.intent.IntentRouter
import app.pdfa.app.recents.RecentItem
import app.pdfa.app.recents.RecentsRepository
import app.pdfa.design.PdfaTheme
import kotlinx.coroutines.launch
import java.text.DateFormat
import java.util.Date

/** Stateful home: recents from the repository, the system picker for Open. */
@Composable
fun HomeScreen(
    recents: RecentsRepository,
    snackbar: SnackbarHostState,
    onOpen: (DocRef) -> Unit,
    recoverable: suspend () -> List<RecoverableWork> = { emptyList() },
    onRestore: (RecoverableWork) -> Unit = {},
    onDiscardRecovered: suspend (RecoverableWork) -> Boolean = { true },
    onSettings: () -> Unit,
    onLicences: () -> Unit,
    onMerge: () -> Unit = {},
    onScan: () -> Unit = {},
    onImagesToPdf: () -> Unit = {},
) {
    val items by recents.observe().collectAsState(initial = emptyList())
    // Unsaved work left by a crash or by leaving without saving. Read from disk each time Home shows.
    var recovered by remember { mutableStateOf(emptyList<RecoverableWork>()) }
    LaunchedEffect(Unit) { recovered = recoverable() }
    val discardRefused = stringResource(R.string.recovery_discard_refused)
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val notPdf = stringResource(R.string.open_not_pdf)

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult // picker cancelled
        val ref = IntentRouter.acceptDocUri(uri.toString())
        if (ref == null) {
            scope.launch { snackbar.showSnackbar(notPdf) }
        } else {
            takePersistableRead(context, uri)
            onOpen(ref)
        }
    }

    HomeContent(
        rows = items,
        recovered = recovered,
        onRestore = onRestore,
        onDiscardRecovered = { work ->
            scope.launch {
                if (onDiscardRecovered(work)) recovered = recovered - work else snackbar.showSnackbar(discardRefused)
            }
        },
        snackbar = snackbar,
        onOpenPicker = { picker.launch(arrayOf(PDF_MIME)) },
        onOpenRecent = { onOpen(it.doc) },
        onRemove = { scope.launch { recents.remove(it) } },
        onSettings = onSettings,
        onLicences = onLicences,
        onMerge = onMerge,
        onScan = onScan,
        onImagesToPdf = onImagesToPdf,
    )
}

/**
 * Persists read access to a picked document so it can be reopened later. Only for ACTION_OPEN_DOCUMENT
 * results. Refusal is harmless: the grant then lasts for this session, and the entry may show as
 * unavailable later.
 */
private fun takePersistableRead(context: Context, uri: Uri) {
    try {
        context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
    } catch (e: SecurityException) {
        // The provider does not allow persistable grants.
    } catch (e: IllegalArgumentException) {
        // The URI does not support persistable grants.
    }
}

private const val PDF_MIME = "application/pdf"

/** Stateless home. Previews and tests use this directly. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeContent(
    rows: List<RecentItem>,
    recovered: List<RecoverableWork> = emptyList(),
    onRestore: (RecoverableWork) -> Unit = {},
    onDiscardRecovered: (RecoverableWork) -> Unit = {},
    snackbar: SnackbarHostState,
    onOpenPicker: () -> Unit,
    onOpenRecent: (RecentItem) -> Unit,
    onRemove: (DocRef) -> Unit,
    onSettings: () -> Unit,
    onLicences: () -> Unit,
    onMerge: () -> Unit = {},
    onScan: () -> Unit = {},
    onImagesToPdf: () -> Unit = {},
) {
    var menuOpen by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.app_name)) },
                actions = {
                    Box {
                        IconButton(onClick = { menuOpen = true }) {
                            Icon(Icons.Default.MoreVert, contentDescription = stringResource(R.string.home_menu))
                        }
                        DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.menu_merge)) },
                                onClick = { menuOpen = false; onMerge() },
                            )
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.menu_scan)) },
                                onClick = { menuOpen = false; onScan() },
                            )
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.menu_images)) },
                                onClick = { menuOpen = false; onImagesToPdf() },
                            )
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.menu_settings)) },
                                onClick = { menuOpen = false; onSettings() },
                            )
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.menu_licences)) },
                                onClick = { menuOpen = false; onLicences() },
                            )
                        }
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = onOpenPicker,
                icon = { Icon(Icons.Default.Add, contentDescription = null) },
                text = { Text(stringResource(R.string.home_open)) },
            )
        },
    ) { inner ->
        if (rows.isEmpty() && recovered.isEmpty()) {
            Column(
                modifier = Modifier.fillMaxSize().padding(inner).padding(24.dp),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    text = stringResource(R.string.home_tagline),
                    style = MaterialTheme.typography.bodyLarge,
                    textAlign = TextAlign.Center,
                )
                Text(
                    text = stringResource(R.string.home_empty),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
        } else {
            LazyColumn(modifier = Modifier.fillMaxSize().padding(inner)) {
                items(recovered, key = { "recovered:" + it.docId }) { work ->
                    RecoveredRow(work, onRestore, onDiscardRecovered)
                    HorizontalDivider()
                }
                items(rows, key = { it.doc.uri }) { item ->
                    RecentRow(item, onOpenRecent, onRemove)
                    HorizontalDivider()
                }
            }
        }
    }
}

/** Unsaved changes found at start. Restore opens them; Discard deletes the autosave only. */
@Composable
private fun RecoveredRow(work: RecoverableWork, onRestore: (RecoverableWork) -> Unit, onDiscard: (RecoverableWork) -> Unit) {
    Surface(color = MaterialTheme.colorScheme.primaryContainer, contentColor = MaterialTheme.colorScheme.onPrimaryContainer) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp)) {
            Text(stringResource(R.string.recovery_title, work.name), style = MaterialTheme.typography.titleSmall)
            Text(stringResource(R.string.recovery_body), style = MaterialTheme.typography.bodySmall)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                TextButton(onClick = { onDiscard(work) }) { Text(stringResource(R.string.recovery_discard)) }
                Button(onClick = { onRestore(work) }) { Text(stringResource(R.string.recovery_restore)) }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun RecentRow(item: RecentItem, onOpen: (RecentItem) -> Unit, onRemove: (DocRef) -> Unit) {
    var menuOpen by remember(item.doc) { mutableStateOf(false) }
    val opened = remember(item.lastOpenedMs) {
        DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(item.lastOpenedMs))
    }
    val rowDescription = stringResource(R.string.recent_open_action, item.name)

    ListItem(
        modifier = Modifier.clickable(
            enabled = item.available,
            role = Role.Button,
            onClickLabel = rowDescription,
        ) { onOpen(item) },
        headlineContent = {
            Text(item.name, maxLines = 1, overflow = TextOverflow.Ellipsis)
        },
        supportingContent = {
            Text(
                text = if (item.available) opened else stringResource(R.string.home_unavailable),
                color = if (item.available) {
                    MaterialTheme.colorScheme.onSurfaceVariant
                } else {
                    MaterialTheme.colorScheme.error
                },
            )
        },
        trailingContent = {
            Box {
                IconButton(onClick = { menuOpen = true }) {
                    Icon(
                        Icons.Default.MoreVert,
                        contentDescription = stringResource(R.string.recent_remove_action, item.name),
                    )
                }
                DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.recent_remove)) },
                        onClick = {
                            menuOpen = false
                            onRemove(item.doc)
                        },
                    )
                }
            }
        },
    )
}

@Preview(showBackground = true)
@Composable
private fun HomeEmptyPreview() {
    PdfaTheme {
        HomeContent(
            rows = emptyList(),
            snackbar = remember { SnackbarHostState() },
            onOpenPicker = {},
            onOpenRecent = {},
            onRemove = {},
            onSettings = {},
            onLicences = {},
        )
    }
}

@Preview(showBackground = true)
@Composable
private fun HomeListPreview() {
    PdfaTheme {
        HomeContent(
            rows = listOf(
                RecentItem(DocRef("content://preview/document/1"), "Lease.pdf", 0L, available = true),
                RecentItem(DocRef("content://preview/document/2"), "Scan.pdf", 0L, available = false),
            ),
            snackbar = remember { SnackbarHostState() },
            onOpenPicker = {},
            onOpenRecent = {},
            onRemove = {},
            onSettings = {},
            onLicences = {},
        )
    }
}
