package app.pdfa.pages.tools

import app.pdfa.feature.pages.tools.R
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.pdfa.design.PdfaSpacing
import app.pdfa.design.PdfaTopBar
import app.pdfa.design.ProgressCard

/** What the merge screen asks the app to do. The app owns the pickers and the long-running coroutine. */
class MergeActions(
    val onBack: () -> Unit,
    val onAddFiles: () -> Unit,
    val onSubmitPassword: (id: Long, password: String) -> Unit,
    val onMerge: () -> Unit,
    val onCancel: () -> Unit,
    val onSave: () -> Unit,
    val onOpenResult: () -> Unit,
    val onStartOver: () -> Unit,
)

@Composable
fun MergeScreen(controller: MergeController, actions: MergeActions, modifier: Modifier = Modifier) {
    val state by controller.state.collectAsState()
    Scaffold(
        modifier = modifier,
        topBar = {
            PdfaTopBar(
                title = stringResource(R.string.merge_title),
                onBack = actions.onBack,
                backContentDescription = stringResource(R.string.tools_back),
            )
        },
        bottomBar = {
            if (state.phase == MergePhase.EDITING) {
                MergeBottomBar(state, actions)
            }
        },
    ) { inner ->
        Column(Modifier.padding(inner).fillMaxSize()) {
            when (state.phase) {
                MergePhase.EDITING -> MergeList(state, controller, actions)
                MergePhase.MERGING -> Column(Modifier.padding(PdfaSpacing.S4)) {
                    ProgressCard(
                        title = stringResource(R.string.merge_working),
                        detail = stringResource(R.string.merge_working_detail),
                        cancelLabel = stringResource(R.string.merge_cancel),
                        onCancel = actions.onCancel,
                    )
                }
                MergePhase.MERGED, MergePhase.SAVING -> MergeResult(state, actions, saved = false)
                MergePhase.SAVED -> MergeResult(state, actions, saved = true)
            }
        }
    }
}

@Composable
private fun MergeBottomBar(state: MergeState, actions: MergeActions) {
    Surface(color = MaterialTheme.colorScheme.surfaceVariant, tonalElevation = 3.dp, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.navigationBarsPadding().padding(PdfaSpacing.S3), verticalArrangement = Arrangement.spacedBy(PdfaSpacing.S2)) {
            if (state.items.isNotEmpty() && !state.canMerge && !state.adding) {
                val hint = if (state.items.size < 2 && state.items.all { it.status == SourceStatus.READY }) R.string.merge_need_two else R.string.merge_fix_first
                Text(stringResource(hint), style = MaterialTheme.typography.bodySmall)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(PdfaSpacing.S2), modifier = Modifier.fillMaxWidth()) {
                OutlinedButton(onClick = actions.onAddFiles, enabled = !state.adding, modifier = Modifier.weight(1f)) {
                    Text(stringResource(R.string.merge_add))
                }
                Button(onClick = actions.onMerge, enabled = state.canMerge, modifier = Modifier.weight(1f)) {
                    Text(stringResource(R.string.merge_action))
                }
            }
        }
    }
}

@Composable
private fun MergeList(state: MergeState, controller: MergeController, actions: MergeActions) {
    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(PdfaSpacing.S4),
        verticalArrangement = Arrangement.spacedBy(PdfaSpacing.S3),
    ) {
        state.problem?.let { p ->
            item(key = "problem") { MessageCard(problemText(p, state.problemDetail), onDismiss = controller::dismissProblem) }
        }
        if (state.cancelled) {
            item(key = "cancelled") { MessageCard(stringResource(R.string.merge_cancelled), onDismiss = controller::dismissProblem, error = false) }
        }
        if (state.skipped > 0) {
            item(key = "skipped") { MessageCard(stringResource(R.string.merge_skipped), onDismiss = controller::dismissProblem) }
        }
        if (state.adding) {
            item(key = "adding") {
                Column(verticalArrangement = Arrangement.spacedBy(PdfaSpacing.S1)) {
                    Text(stringResource(R.string.merge_adding), style = MaterialTheme.typography.bodyMedium)
                    LinearProgressIndicator(Modifier.fillMaxWidth())
                }
            }
        }
        if (state.items.isEmpty() && !state.adding) {
            item(key = "empty") { Text(stringResource(R.string.merge_empty), style = MaterialTheme.typography.bodyLarge) }
        }
        itemsIndexed(state.items, key = { _, item -> item.id }) { index, item ->
            MergeRow(index, state.items.size, item, controller, actions)
        }
    }
}

@Composable
private fun MergeRow(index: Int, count: Int, item: MergeItem, controller: MergeController, actions: MergeActions) {
    val position = index + 1
    Card(
        Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
    ) {
        Column(Modifier.padding(PdfaSpacing.S3), verticalArrangement = Arrangement.spacedBy(PdfaSpacing.S2)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("$position. ${item.name}", style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    val used = item.pagesUsed
                    val status = when (item.status) {
                        SourceStatus.CHECKING -> stringResource(R.string.merge_status_checking)
                        SourceStatus.READY -> when {
                            item.pageCount == 1 -> stringResource(R.string.merge_pages_one)
                            used != null && used != item.pageCount -> stringResource(R.string.merge_pages_used, used, item.pageCount ?: 0)
                            else -> stringResource(R.string.merge_pages, item.pageCount ?: 0)
                        }
                        SourceStatus.NEEDS_PASSWORD -> stringResource(R.string.merge_status_password)
                        SourceStatus.WRONG_PASSWORD -> stringResource(R.string.merge_status_wrong)
                        SourceStatus.NOT_PDF -> stringResource(R.string.merge_status_not_pdf)
                        SourceStatus.TOO_LARGE -> stringResource(R.string.merge_status_too_large)
                        SourceStatus.UNREADABLE -> stringResource(R.string.merge_status_unreadable)
                        SourceStatus.DAMAGED -> stringResource(R.string.merge_status_damaged)
                    }
                    val bad = item.status !in setOf(SourceStatus.READY, SourceStatus.CHECKING, SourceStatus.NEEDS_PASSWORD)
                    Text(
                        status,
                        style = MaterialTheme.typography.bodySmall,
                        color = if (bad) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                IconButton(onClick = { controller.moveUp(item.id) }, enabled = index > 0) {
                    Icon(Icons.Filled.KeyboardArrowUp, contentDescription = stringResource(R.string.merge_move_up, position))
                }
                IconButton(onClick = { controller.moveDown(item.id) }, enabled = index < count - 1) {
                    Icon(Icons.Filled.KeyboardArrowDown, contentDescription = stringResource(R.string.merge_move_down, position))
                }
                IconButton(onClick = { controller.remove(item.id) }) {
                    Icon(Icons.Filled.Delete, contentDescription = stringResource(R.string.merge_remove, position))
                }
            }
            when (item.status) {
                SourceStatus.READY -> OutlinedTextField(
                    value = item.rangeText,
                    onValueChange = { controller.setRange(item.id, it) },
                    label = { Text(stringResource(R.string.merge_range_label)) },
                    singleLine = true,
                    isError = item.rangeError != null,
                    supportingText = item.rangeError?.let { e -> { Text(inputErrorText(e, item.pageCount)) } },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Text),
                    modifier = Modifier.fillMaxWidth(),
                )
                SourceStatus.NEEDS_PASSWORD, SourceStatus.WRONG_PASSWORD -> {
                    // Typed text stays in this composable only: never in the controller's state or saved state.
                    var password by remember(item.id) { mutableStateOf("") }
                    OutlinedTextField(
                        value = password,
                        onValueChange = { password = it },
                        label = { Text(stringResource(R.string.merge_password_label)) },
                        singleLine = true,
                        isError = item.status == SourceStatus.WRONG_PASSWORD,
                        visualTransformation = PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Button(onClick = { actions.onSubmitPassword(item.id, password); password = "" }, enabled = password.isNotEmpty()) {
                        Text(stringResource(R.string.merge_unlock))
                    }
                }
                else -> Unit
            }
        }
    }
}

@Composable
private fun MergeResult(state: MergeState, actions: MergeActions, saved: Boolean) {
    Column(Modifier.padding(PdfaSpacing.S4), verticalArrangement = Arrangement.spacedBy(PdfaSpacing.S3)) {
        Card(
            Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
        ) {
            Column(Modifier.padding(PdfaSpacing.S4), verticalArrangement = Arrangement.spacedBy(PdfaSpacing.S2)) {
                Text(stringResource(R.string.merge_done, state.mergedPages ?: 0), style = MaterialTheme.typography.titleMedium)
                Text(
                    stringResource(if (saved) R.string.merge_saved else R.string.merge_done_detail),
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }
        if (state.saveFailed) MessageCard(stringResource(R.string.merge_save_failed), onDismiss = null)
        if (state.phase == MergePhase.SAVING) {
            Text(stringResource(R.string.merge_saving), style = MaterialTheme.typography.bodyMedium)
            LinearProgressIndicator(Modifier.fillMaxWidth())
        }
        if (saved) {
            Button(onClick = actions.onOpenResult, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.merge_open)) }
            OutlinedButton(onClick = actions.onStartOver, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.merge_start_over)) }
        } else {
            Button(onClick = actions.onSave, enabled = state.phase == MergePhase.MERGED, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.merge_save))
            }
        }
    }
}
