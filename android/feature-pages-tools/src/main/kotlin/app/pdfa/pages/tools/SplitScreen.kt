package app.pdfa.pages.tools

import app.pdfa.feature.pages.tools.R
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import app.pdfa.design.PdfaSpacing
import app.pdfa.design.PdfaTopBar
import app.pdfa.design.ProgressCard

class SplitActions(
    val onClose: () -> Unit,
    val onRun: () -> Unit,
    val onCancel: () -> Unit,
    /** Opens the create-document picker for the extracted file. */
    val onSaveFile: () -> Unit,
    /** Opens the folder picker for the parts. */
    val onSaveParts: () -> Unit,
)

/** Full-screen options for extracting pages or splitting the open document. */
@Composable
fun SplitScreen(controller: SplitController, actions: SplitActions, modifier: Modifier = Modifier) {
    val state by controller.state.collectAsState()
    val busy = state.phase == SplitPhase.WORKING || state.phase == SplitPhase.SAVING
    Surface(modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(Modifier.fillMaxSize()) {
            PdfaTopBar(
                title = stringResource(R.string.split_title),
                onBack = if (busy) null else actions.onClose,
                backContentDescription = stringResource(R.string.tools_back),
            )
            Column(
                Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(PdfaSpacing.S4).navigationBarsPadding(),
                verticalArrangement = Arrangement.spacedBy(PdfaSpacing.S3),
            ) {
                state.problem?.let { MessageCard(problemText(it, state.problemDetail), onDismiss = controller::dismissProblem) }
                if (state.cancelled) MessageCard(stringResource(R.string.split_cancelled), onDismiss = controller::dismissProblem, error = false)
                when (state.phase) {
                    SplitPhase.EDITING -> Form(state, controller, actions)
                    SplitPhase.WORKING -> ProgressCard(
                        title = stringResource(R.string.split_working),
                        detail = stringResource(R.string.merge_working_detail),
                        cancelLabel = stringResource(R.string.merge_cancel),
                        onCancel = actions.onCancel,
                    )
                    SplitPhase.READY, SplitPhase.SAVING -> Ready(state, controller, actions)
                    SplitPhase.DONE -> Done(state, controller, actions)
                }
            }
        }
    }
}

@Composable
private fun Form(state: SplitState, controller: SplitController, actions: SplitActions) {
    Column(Modifier.selectableGroup(), verticalArrangement = Arrangement.spacedBy(PdfaSpacing.S1)) {
        for ((mode, label) in listOf(
            SplitMode.EXTRACT to R.string.split_mode_extract,
            SplitMode.EVERY_N to R.string.split_mode_every,
            SplitMode.BEFORE to R.string.split_mode_before,
        )) {
            Row(
                Modifier.fillMaxWidth().defaultMinSize(minHeight = 48.dp)
                    .selectable(selected = state.mode == mode, role = Role.RadioButton, onClick = { controller.setMode(mode) }),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                RadioButton(selected = state.mode == mode, onClick = null)
                Text(stringResource(label), Modifier.padding(start = PdfaSpacing.S3), style = MaterialTheme.typography.bodyLarge)
            }
        }
    }
    val label = when (state.mode) {
        SplitMode.EXTRACT -> R.string.split_pages_label
        SplitMode.EVERY_N -> R.string.split_every_label
        SplitMode.BEFORE -> R.string.split_before_label
    }
    OutlinedTextField(
        value = state.input,
        onValueChange = controller::setInput,
        label = { Text(stringResource(label)) },
        singleLine = true,
        isError = state.inputError != null,
        supportingText = state.inputError?.let { e -> { Text(inputErrorText(e, state.pageCount)) } },
        keyboardOptions = KeyboardOptions(keyboardType = if (state.mode == SplitMode.EVERY_N) KeyboardType.Number else KeyboardType.Text),
        modifier = Modifier.fillMaxWidth(),
    )
    Text(
        stringResource(
            when (state.mode) {
                SplitMode.EXTRACT -> R.string.split_hint_extract
                SplitMode.EVERY_N -> R.string.split_hint_every
                SplitMode.BEFORE -> R.string.split_hint_before
            },
        ),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    state.outcomeCount?.let { n ->
        Text(
            if (state.mode == SplitMode.EXTRACT) stringResource(R.string.split_preview_extract, n)
            else stringResource(R.string.split_preview_parts, n, partName(0, n), partName(n - 1, n)),
            style = MaterialTheme.typography.titleSmall,
        )
    }
    Button(onClick = actions.onRun, enabled = state.canRun, modifier = Modifier.fillMaxWidth()) {
        Text(stringResource(if (state.mode == SplitMode.EXTRACT) R.string.split_action_extract else R.string.split_action_split))
    }
}

@Composable
private fun Ready(state: SplitState, controller: SplitController, actions: SplitActions) {
    val saving = state.phase == SplitPhase.SAVING
    val parts = state.readyParts
    Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh)) {
        Text(
            if (parts == 0) stringResource(R.string.split_ready_file) else stringResource(R.string.split_ready_parts, parts),
            Modifier.padding(PdfaSpacing.S4),
            style = MaterialTheme.typography.bodyLarge,
        )
    }
    when (state.report) {
        SaveReport.FileFailed -> MessageCard(stringResource(R.string.split_file_failed), onDismiss = null)
        is SaveReport.PartsFailed -> (state.report as SaveReport.PartsFailed).let {
            MessageCard(stringResource(R.string.split_parts_failed, it.written, it.total, it.failedPart), onDismiss = null)
        }
        else -> Unit
    }
    if (saving) {
        Text(
            if (parts == 0) stringResource(R.string.split_saving_file) else stringResource(R.string.split_saving_parts, state.written + 1, parts),
            style = MaterialTheme.typography.bodyMedium,
        )
        if (parts == 0) LinearProgressIndicator(Modifier.fillMaxWidth())
        else LinearProgressIndicator(progress = { state.written.toFloat() / parts }, modifier = Modifier.fillMaxWidth())
    }
    Button(onClick = if (parts == 0) actions.onSaveFile else actions.onSaveParts, enabled = !saving, modifier = Modifier.fillMaxWidth()) {
        Text(stringResource(if (parts == 0) R.string.split_save_file else R.string.split_save_parts))
    }
    OutlinedButton(onClick = controller::again, enabled = !saving, modifier = Modifier.fillMaxWidth()) {
        Text(stringResource(R.string.split_again))
    }
}

@Composable
private fun Done(state: SplitState, controller: SplitController, actions: SplitActions) {
    val text = when (val r = state.report) {
        is SaveReport.PartsSaved -> stringResource(R.string.split_parts_saved, r.total)
        else -> stringResource(R.string.split_file_saved)
    }
    Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh)) {
        Text(text, Modifier.padding(PdfaSpacing.S4), style = MaterialTheme.typography.bodyLarge)
    }
    Button(onClick = actions.onClose, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.split_close)) }
    OutlinedButton(onClick = controller::again, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.split_again)) }
}
