package app.pdfa.design

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Snackbar
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** How long an Undo snackbar stays on screen (DESIGN.md: 8 s). */
const val UNDO_SNACKBAR_TIMEOUT_MS: Long = 8_000L

/** Action label shown on the snackbar. */
const val UNDO_ACTION_LABEL: String = "Undo"

/**
 * Holds the snackbar host and shows one Undo snackbar at a time. A new [show] call replaces
 * the snackbar on screen (queue of one); the old one's Undo is dropped.
 */
class UndoSnackbarState internal constructor(
    val hostState: SnackbarHostState,
    private val scope: CoroutineScope,
) {
    private var job: Job? = null

    /** Shows [message] with an Undo action. [onUndo] runs only if the user taps Undo. */
    fun show(message: String, onUndo: () -> Unit) {
        job?.cancel()
        job = scope.launch {
            val timeout = launch {
                delay(UNDO_SNACKBAR_TIMEOUT_MS)
                hostState.currentSnackbarData?.dismiss()
            }
            val result = hostState.showSnackbar(
                message = message,
                actionLabel = UNDO_ACTION_LABEL,
                withDismissAction = false,
                duration = SnackbarDuration.Indefinite,
            )
            timeout.cancel()
            if (result == SnackbarResult.ActionPerformed) onUndo()
        }
    }
}

/** Creates an [UndoSnackbarState] tied to the composition. */
@Composable
fun rememberUndoSnackbarState(): UndoSnackbarState {
    val hostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    return remember(hostState, scope) { UndoSnackbarState(hostState, scope) }
}

/** Renders the snackbar. Place it inside a Scaffold or above the tool tray. */
@Composable
fun UndoSnackbarHost(state: UndoSnackbarState, modifier: Modifier = Modifier) {
    SnackbarHost(hostState = state.hostState, modifier = modifier) { data ->
        Snackbar(snackbarData = data)
    }
}

@Preview(showBackground = true)
@Composable
private fun UndoSnackbarPreview() {
    PdfaTheme(darkTheme = false) {
        // Static preview of the snackbar look; the live timer needs a running composition.
        Snackbar(
            modifier = Modifier.padding(PdfaSpacing.GutterPhone),
            action = {
                Button(onClick = {}) { Text(UNDO_ACTION_LABEL) }
            },
        ) {
            Text("Page 3 deleted")
        }
    }
}

@Preview(showBackground = true, name = "Undo snackbar dark")
@Composable
private fun UndoSnackbarDarkPreview() {
    PdfaTheme(darkTheme = true) {
        Column(modifier = Modifier.padding(PdfaSpacing.GutterPhone)) {
            Snackbar(
                action = { Text(UNDO_ACTION_LABEL, color = MaterialTheme.colorScheme.inversePrimary) },
            ) {
                Text("Saved")
            }
        }
    }
}
