package app.pdfa.app.viewer

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import app.pdfa.app.R
import app.pdfa.app.save.EditsLostNotice
import app.pdfa.app.save.SaveAction
import app.pdfa.app.save.SaveOutcome

/** The two outcomes that may be shown as "saved". Everything else is a problem with choices. */
internal fun SaveOutcome.isSuccess(): Boolean = this is SaveOutcome.Saved || this is SaveOutcome.CopySaved

/** Title and body for an outcome. One entry per row of :storage's failure matrix. */
internal fun SaveOutcome.texts(): Pair<Int, Int> = when (this) {
    is SaveOutcome.Saved -> R.string.save_done_title to
        (if (snapshotKept) R.string.save_done_snapshot else R.string.save_done_body)
    SaveOutcome.CopySaved -> R.string.save_done_title to R.string.save_copy_done
    SaveOutcome.SavedRecordStale -> R.string.save_stale_title to R.string.save_stale_body
    SaveOutcome.SavedUnconfirmed -> R.string.save_unconfirmed_title to R.string.save_unconfirmed_body
    is SaveOutcome.Conflict -> R.string.save_conflict_title to
        (if (stagingKept) R.string.save_conflict_pending else R.string.save_conflict_body)
    is SaveOutcome.CopyFailed -> R.string.save_failed_title to R.string.save_copyfailed_body
    is SaveOutcome.Unverifiable -> R.string.save_unverifiable_title to R.string.save_unverifiable_body
    is SaveOutcome.VerificationFailed -> R.string.save_failed_title to R.string.save_verify_body
    is SaveOutcome.TooLarge -> R.string.save_failed_title to R.string.save_toolarge_body
    is SaveOutcome.Io -> R.string.save_failed_title to R.string.save_io_body
    is SaveOutcome.CopyTargetFailed -> R.string.save_failed_title to
        (if (targetMayBeTruncated) R.string.save_copytarget_partial else R.string.save_copytarget_unconfirmed)
    SaveOutcome.CopyTargetRefused -> R.string.save_failed_title to R.string.save_copytarget_refused
    is SaveOutcome.EngineFailed -> R.string.save_failed_title to
        (if (notPermitted) R.string.save_not_permitted else R.string.save_engine_body)
}

internal fun SaveAction.label(): Int = when (this) {
    SaveAction.RETRY -> R.string.save_action_retry
    SaveAction.SAVE_COPY -> R.string.save_action_copy
    SaveAction.OVERWRITE -> R.string.save_action_overwrite
    SaveAction.EXPORT_PENDING -> R.string.save_action_export
}

/** A save that did not end as "saved": says what happened to the file and offers exactly the safe choices. */
@Composable
internal fun SaveProblemDialog(outcome: SaveOutcome, onAction: (SaveAction) -> Unit, onDismiss: () -> Unit) {
    val (title, body) = outcome.texts()
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(title)) },
        text = {
            Column {
                Text(stringResource(body))
                if (outcome.sourceMayBeTruncated) {
                    Text(
                        stringResource(R.string.save_source_partial),
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(top = 12.dp),
                    )
                }
                if (outcome.stagingKept) {
                    Text(stringResource(R.string.save_changes_safe), modifier = Modifier.padding(top = 12.dp))
                }
            }
        },
        confirmButton = {
            Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.End) {
                outcome.actions.forEach { action ->
                    TextButton(onClick = { onAction(action) }) { Text(stringResource(action.label())) }
                }
                TextButton(onClick = onDismiss) { Text(stringResource(R.string.save_action_cancel)) }
            }
        },
        properties = DialogProperties(dismissOnClickOutside = false),
    )
}

/** Leaving with unsaved changes. Nothing is lost by cancelling. */
@Composable
internal fun LeaveDialog(
    replaceOriginal: Boolean,
    onSave: () -> Unit,
    onSaveCopy: () -> Unit,
    onDiscard: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.leave_title)) },
        text = { Text(stringResource(R.string.leave_body)) },
        confirmButton = {
            Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.End) {
                if (replaceOriginal) TextButton(onClick = onSave) { Text(stringResource(R.string.viewer_save)) }
                TextButton(onClick = onSaveCopy) { Text(stringResource(R.string.viewer_save_copy)) }
                TextButton(onClick = onDiscard) {
                    Text(stringResource(R.string.leave_discard), color = MaterialTheme.colorScheme.error)
                }
                TextButton(onClick = onDismiss) { Text(stringResource(R.string.save_action_cancel)) }
            }
        },
    )
}

/**
 * The engine restarted and this document came back without the changes made since the last
 * autosave. Says which copy was restored and that the document is still unsaved.
 */
@Composable
internal fun EditsLostDialog(notice: EditsLostNotice, onDismiss: () -> Unit) {
    val source = if (notice.originalRestored) R.string.edits_lost_original else R.string.edits_lost_autosave
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.edits_lost_title)) },
        text = {
            Text(
                stringResource(R.string.edits_lost_body) + " " + stringResource(source) + " " +
                    stringResource(R.string.edits_lost_unsaved),
            )
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.edits_lost_ok)) }
        },
        // It often appears while the reader is scrolling: a touch outside must not swallow it unread.
        properties = DialogProperties(dismissOnClickOutside = false),
    )
}

private fun icon(name: String, path: String): ImageVector =
    ImageVector.Builder(name = name, defaultWidth = 24.dp, defaultHeight = 24.dp, viewportWidth = 24f, viewportHeight = 24f)
        .addPath(pathData = addPathNodes(path), fill = SolidColor(Color.Black))
        .build()

/** Undo and redo arrows (the core icon set has none). */
internal val UndoIcon: ImageVector by lazy {
    icon("Undo", "M12.5,8c-2.65,0 -5.05,0.99 -6.9,2.6L2,7v9h9l-3.62,-3.62c1.39,-1.16 3.16,-1.88 5.12,-1.88 3.54,0 6.55,2.31 7.6,5.5l2.37,-0.78C21.08,11.03 17.15,8 12.5,8z")
}
internal val RedoIcon: ImageVector by lazy {
    icon("Redo", "M18.4,10.6C16.55,8.99 14.15,8 11.5,8c-4.65,0 -8.58,3.03 -9.96,7.22L3.9,16c1.05,-3.19 4.05,-5.5 7.6,-5.5 1.95,0 3.73,0.72 5.12,1.88L13,16h9V7l-3.6,3.6z")
}
