package app.pdfa.storage

/**
 * One size limit for import and save in v1. Import cap and in-memory save cap are the same, so every
 * imported document can be saved. Revisit together with a streaming save path.
 */
const val MAX_DOCUMENT_BYTES: Long = 128L * 1024 * 1024

/**
 * Failures raised while importing or loading the workspace. [detail] is fixed text or a sanitised
 * summary (exception class names only). It never contains URIs, paths, file names or content.
 */
sealed interface StorageError {
    val detail: String

    /** The source exceeds the import cap (declared size or bytes actually read). */
    data class TooLarge(override val detail: String) : StorageError

    /** The first 1024 bytes do not contain `%PDF-`. The declared MIME type is ignored. */
    data class NotPdf(override val detail: String) : StorageError

    /** A read, write or query on the source or the app storage failed, or sizes disagree. */
    data class Io(override val detail: String) : StorageError

    /** `manifest.json` is missing, malformed or has out-of-range fields. */
    data class ManifestInvalid(override val detail: String) : StorageError
}

/** Carrier for [StorageError] on the throwing paths (import and load). */
class StorageException(val error: StorageError) : Exception(error.detail)

/**
 * Outcome of [SafWriter.saveBack]. Every failure carries two flags so the UI never infers them:
 * [sourceMayBeTruncated] (the user's file may now be partial) and [stagingKept] (`staging.pdf` holds the
 * full new bytes and the pending state is in place). See README for what the UI must do.
 */
sealed interface SaveResult {
    val sourceMayBeTruncated: Boolean
    val stagingKept: Boolean

    /**
     * The source holds the new bytes and the manifest matches them. [snapshot] names the external
     * version kept in the workspace when overwrite was forced. [warning] reports a non-fatal cleanup or
     * sync problem.
     */
    data class Saved(val doc: ImportedDoc, val snapshot: String? = null, val warning: String? = null) : SaveResult {
        override val sourceMayBeTruncated: Boolean get() = false
        override val stagingKept: Boolean get() = false
    }

    /**
     * The source holds the new bytes (read back and hashed) but the manifest write failed. [doc] carries
     * the updated manifest in memory; the one on disk is stale. Pending state is kept:
     * [SafWriter.finishPendingCopy] records the manifest without copying again.
     */
    data class SavedButManifestStale(val doc: ImportedDoc, val detail: String) : SaveResult {
        override val sourceMayBeTruncated: Boolean get() = false
        override val stagingKept: Boolean get() = true
    }

    /** The new bytes were rejected by verify. Nothing was written to the source. */
    data class VerificationFailed(
        val detail: String,
        override val sourceMayBeTruncated: Boolean,
        override val stagingKept: Boolean,
    ) : SaveResult

    /**
     * The source differs from what this document last had. Nothing was written to the source. Staging is
     * kept only when a copy was already pending (then it is the only full copy).
     */
    data class Conflict(
        val current: SourceStat,
        val expected: SourceStat,
        val detail: String,
        override val sourceMayBeTruncated: Boolean,
        override val stagingKept: Boolean,
    ) : SaveResult

    /**
     * The copy started and did not verify (write error, length or hash mismatch, timeout). The source may
     * be partial. `staging.pdf` and the pending marker are kept.
     */
    data class CopyFailed(
        val retryable: Boolean,
        override val stagingKept: Boolean,
        override val sourceMayBeTruncated: Boolean,
        val detail: String,
    ) : SaveResult

    /**
     * The copy wrote the source, but the read-back could not be completed (or timed out). The source is
     * unproven and may be partial. Staging and the marker are kept.
     */
    data class Unverifiable(
        val detail: String,
        override val sourceMayBeTruncated: Boolean,
        override val stagingKept: Boolean,
    ) : SaveResult

    /** The bytes exceed [SafWriter] maxInMemoryBytes. Refused before any allocation or truncation. */
    data class TooLarge(
        val detail: String,
        override val sourceMayBeTruncated: Boolean,
        override val stagingKept: Boolean,
    ) : SaveResult

    /** Failed before the source was written. Retryable. */
    data class Io(
        val detail: String,
        override val sourceMayBeTruncated: Boolean,
        override val stagingKept: Boolean,
    ) : SaveResult
}

/** Outcome of Save a copy and [SafWriter.exportStaging]. The original document is never modified. */
sealed interface CopyResult {
    /**
     * [replacedSource] is set when the target turned out to be the source document under another URI: the
     * original now holds the new bytes, and this is the document with its updated manifest. Never report
     * "original unchanged" then.
     */
    data class Copied(
        val size: Long,
        val sha256: String,
        val warning: String? = null,
        val replacedSource: ImportedDoc? = null,
    ) : CopyResult

    /** The target may be partial. `save_copy.pdf` is kept (exportStaging never discards its source). */
    data class CopyFailed(
        val retryable: Boolean,
        val stagingKept: Boolean,
        val targetMayBeTruncated: Boolean,
        val detail: String,
    ) : CopyResult

    /** Written, but the read-back could not be completed or timed out. The target is unproven. */
    data class Unverifiable(val detail: String, val targetMayBeTruncated: Boolean, val stagingKept: Boolean) : CopyResult

    data class VerificationFailed(val detail: String) : CopyResult

    data class TooLarge(val detail: String) : CopyResult

    /** Failed before the target was opened, or the target is known to be the source document itself. */
    data class Io(val detail: String) : CopyResult
}

/**
 * Whether a copy-back is pending. Pending means `staging.pdf` exists AND `copy.pending` names this
 * document's URI. [Unknown] means the state could not be read, so the source may be partial.
 */
sealed interface PendingStatus {
    data object Pending : PendingStatus
    data object NotPending : PendingStatus
    data class Unknown(val detail: String) : PendingStatus
}
