package app.pdfa.app.save

import app.pdfa.storage.CopyResult
import app.pdfa.storage.SaveResult

/** What the user may do after a save attempt. The UI shows exactly these, nothing inferred. */
enum class SaveAction { RETRY, SAVE_COPY, OVERWRITE, EXPORT_PENDING }

/**
 * The result of a save, in the terms the UI needs. Built from :storage's results by [from]; one
 * case per row of the failure matrix in android/storage/README.md.
 *
 * Only [Saved] and [CopySaved] may ever be shown as "saved", and the session produces them only
 * after the engine confirmed (`confirmSaved`).
 */
sealed interface SaveOutcome {
    /** Actions offered, most recommended first. Cancel is always possible and not listed. */
    val actions: List<SaveAction> get() = emptyList()

    /** True when the user's own file may now be incomplete. The UI must say so. */
    val sourceMayBeTruncated: Boolean get() = false

    /** True when the app holds the full new bytes in its private staging file. */
    val stagingKept: Boolean get() = false

    /** The original file now holds the changes. [snapshotKept]: the version changed elsewhere was kept in the app. */
    data class Saved(val snapshotKept: Boolean, val warning: Boolean) : SaveOutcome

    /** A new file holds the changes. The original is untouched. */
    data object CopySaved : SaveOutcome

    /** The file holds the new bytes but the app's record of it could not be written. Retry records it without copying. */
    data object SavedRecordStale : SaveOutcome {
        override val actions get() = listOf(SaveAction.RETRY)
        override val stagingKept get() = true
    }

    /** The bytes are written, but the engine could not confirm: the document stays marked unsaved. */
    data object SavedUnconfirmed : SaveOutcome {
        override val actions get() = listOf(SaveAction.RETRY)
    }

    /** The file changed outside the app since it was opened. Nothing was written. */
    data class Conflict(override val sourceMayBeTruncated: Boolean, override val stagingKept: Boolean) : SaveOutcome {
        override val actions get() = listOf(SaveAction.SAVE_COPY, SaveAction.OVERWRITE)
    }

    /** The write started and did not verify. The user's file may be partial; the app kept the full new bytes. */
    data class CopyFailed(override val sourceMayBeTruncated: Boolean, override val stagingKept: Boolean) : SaveOutcome {
        override val actions get() = if (stagingKept) listOf(SaveAction.RETRY, SaveAction.EXPORT_PENDING) else listOf(SaveAction.RETRY, SaveAction.SAVE_COPY)
    }

    /** Written, but reading it back to check was not possible. Never "saved". */
    data class Unverifiable(override val sourceMayBeTruncated: Boolean, override val stagingKept: Boolean) : SaveOutcome {
        override val actions get() = if (stagingKept) listOf(SaveAction.RETRY, SaveAction.EXPORT_PENDING) else listOf(SaveAction.RETRY, SaveAction.SAVE_COPY)
    }

    /** The engine could not reopen what it produced. Nothing was written; the edits are kept. */
    data class VerificationFailed(override val sourceMayBeTruncated: Boolean, override val stagingKept: Boolean) : SaveOutcome {
        override val actions get() = if (stagingKept) listOf(SaveAction.RETRY, SaveAction.EXPORT_PENDING) else listOf(SaveAction.SAVE_COPY)
    }

    /** Above the 128 MiB in-memory save limit. */
    data class TooLarge(override val sourceMayBeTruncated: Boolean, override val stagingKept: Boolean) : SaveOutcome {
        override val actions get() = if (stagingKept) listOf(SaveAction.EXPORT_PENDING) else emptyList()
    }

    /** Failed before the file was written. Retry is safe. */
    data class Io(override val sourceMayBeTruncated: Boolean, override val stagingKept: Boolean) : SaveOutcome {
        override val actions get() = if (stagingKept) listOf(SaveAction.RETRY, SaveAction.EXPORT_PENDING) else listOf(SaveAction.RETRY, SaveAction.SAVE_COPY)
    }

    /** Save a copy: the new file may be incomplete, or could not be confirmed. Another location is the way out. */
    data class CopyTargetFailed(val targetMayBeTruncated: Boolean) : SaveOutcome {
        override val actions get() = listOf(SaveAction.SAVE_COPY)
    }

    /** Save a copy: the place cannot be used (it is the document itself, or it cannot be opened). */
    data object CopyTargetRefused : SaveOutcome {
        override val actions get() = listOf(SaveAction.SAVE_COPY)
    }

    /** The engine could not produce the bytes (it restarted, or the document does not allow it). Nothing was written. */
    data class EngineFailed(val notPermitted: Boolean) : SaveOutcome {
        override val actions get() = if (notPermitted) emptyList() else listOf(SaveAction.RETRY)
    }

    companion object {
        /** [confirmed] is false when the bytes were written but `confirmSaved` failed. */
        fun from(result: SaveResult, confirmed: Boolean = true): SaveOutcome = when (result) {
            is SaveResult.Saved ->
                if (confirmed) Saved(result.snapshot != null, result.warning != null) else SavedUnconfirmed
            is SaveResult.SavedButManifestStale -> SavedRecordStale
            is SaveResult.VerificationFailed -> VerificationFailed(result.sourceMayBeTruncated, result.stagingKept)
            is SaveResult.Conflict -> Conflict(result.sourceMayBeTruncated, result.stagingKept)
            is SaveResult.CopyFailed -> CopyFailed(result.sourceMayBeTruncated, result.stagingKept)
            is SaveResult.Unverifiable -> Unverifiable(result.sourceMayBeTruncated, result.stagingKept)
            is SaveResult.TooLarge -> TooLarge(result.sourceMayBeTruncated, result.stagingKept)
            is SaveResult.Io -> Io(result.sourceMayBeTruncated, result.stagingKept)
        }

        fun from(result: CopyResult, confirmed: Boolean = true): SaveOutcome = when (result) {
            is CopyResult.Copied -> when {
                !confirmed -> SavedUnconfirmed
                // The chosen place was the original itself (under another address): say so, not "unchanged".
                result.replacedSource != null -> Saved(snapshotKept = false, warning = result.warning != null)
                else -> CopySaved
            }
            is CopyResult.CopyFailed -> CopyTargetFailed(result.targetMayBeTruncated)
            is CopyResult.Unverifiable -> CopyTargetFailed(result.targetMayBeTruncated)
            is CopyResult.VerificationFailed -> VerificationFailed(sourceMayBeTruncated = false, stagingKept = false)
            is CopyResult.TooLarge -> TooLarge(sourceMayBeTruncated = false, stagingKept = false)
            is CopyResult.Io -> CopyTargetRefused
        }
    }
}
