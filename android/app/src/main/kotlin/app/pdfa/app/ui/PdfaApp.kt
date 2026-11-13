package app.pdfa.app.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.pdfa.app.AppContainer
import app.pdfa.app.R
import app.pdfa.app.intent.DocRef
import app.pdfa.app.intent.Kind
import app.pdfa.app.intent.OpenRequest
import app.pdfa.app.nav.NavState
import app.pdfa.app.nav.Navigator
import app.pdfa.app.nav.Route
import app.pdfa.app.tools.MergeRoute
import app.pdfa.app.tools.ScanHolder
import app.pdfa.app.tools.ScanRoute
import app.pdfa.design.PdfaTheme

/** Saved state keeps the route keys only (document URIs, never bytes or passwords). */
private val navSaver = listSaver<NavState, String>(
    save = { Navigator.encode(it) },
    restore = { Navigator.decode(it) },
)

/**
 * Root of the app: theme, navigation, and the wiring from intents and the picker into routes.
 *
 * @param incoming a request from MainActivity (intent). Consumed once through [onIncomingHandled].
 */
@Composable
fun PdfaApp(
    container: AppContainer,
    incoming: OpenRequest?,
    onIncomingHandled: () -> Unit,
) {
    val settings by container.settings.settings.collectAsState()
    var nav by rememberSaveable(stateSaver = navSaver) { mutableStateOf(NavState()) }
    val snackbar = remember { SnackbarHostState() }
    val context = LocalContext.current

    // Recents are written by the viewer once the document really opened, not here.
    fun openDocument(doc: DocRef) {
        nav = Navigator.open(Route.Viewer(doc))
    }

    LaunchedEffect(incoming) {
        val request = incoming ?: return@LaunchedEffect
        onIncomingHandled()
        when (request.kind) {
            Kind.PDF -> openDocument(request.refs.first())
            Kind.IMAGES -> {
                // The shared pictures become pages right away; the route only shows them.
                ScanHolder.of(context).addShared(container, context.applicationContext, request.refs)
                nav = Navigator.open(Route.Tools("images"))
            }
        }
    }

    PdfaTheme(dynamicColor = settings.dynamicColour) {
        BackHandler(enabled = nav.stack.size > 1) {
            nav = Navigator.back(nav) ?: nav
        }

        when (val route = nav.current) {
            Route.Home -> HomeScreen(
                recents = container.recents,
                snackbar = snackbar,
                onOpen = ::openDocument,
                recoverable = { container.recoverable() },
                onRestore = { work -> nav = Navigator.open(Route.Viewer(work.doc, work.docId)) },
                onDiscardRecovered = { work -> container.discardRecoverable(work.docId) },
                onSettings = { nav = Navigator.push(nav, Route.Settings) },
                onLicences = { nav = Navigator.push(nav, Route.Licences) },
                onMerge = { nav = Navigator.push(nav, Route.Tools("merge")) },
                onScan = { nav = Navigator.push(nav, Route.Tools("scan")) },
                onImagesToPdf = { nav = Navigator.push(nav, Route.Tools("images")) },
            )

            is Route.Viewer -> container.viewer.Content(
                request = OpenRequest(Kind.PDF, listOf(route.doc), route.restoreDocId),
                onBack = { nav = Navigator.back(nav) ?: nav },
            )

            Route.Settings -> SettingsScreen(
                settings = settings,
                onChange = { change -> container.settings.update(change) },
                onBack = { nav = Navigator.back(nav) ?: nav },
                onLicences = { nav = Navigator.push(nav, Route.Licences) },
            )

            Route.Licences -> LicencesScreen(onBack = { nav = Navigator.back(nav) ?: nav })

            is Route.Tools -> when (route.kind) {
                "merge" -> MergeRoute(container, onBack = { nav = Navigator.back(nav) ?: nav }, onOpen = ::openDocument)
                "scan", "images" -> ScanRoute(
                    container,
                    startOnCamera = route.kind == "scan",
                    onBack = { nav = Navigator.back(nav) ?: nav },
                    onOpen = ::openDocument,
                )
                else -> ToolsPlaceholder(onBack = { nav = Navigator.back(nav) ?: nav })
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ToolsPlaceholder(onBack: () -> Unit) {
    Scaffold(
        topBar = { TopAppBar(title = { Text(stringResource(R.string.back)) }, navigationIcon = { BackButton(onBack) }) },
    ) { inner ->
        Column(Modifier.fillMaxSize().padding(inner).padding(16.dp)) {
            Text(stringResource(R.string.tools_placeholder), style = MaterialTheme.typography.bodyMedium)
        }
    }
}

/** Shared back affordance for pushed screens. */
@Composable
internal fun BackButton(onBack: () -> Unit) {
    IconButton(onClick = onBack) {
        Icon(
            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
            contentDescription = stringResource(R.string.back),
        )
    }
}
