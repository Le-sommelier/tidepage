package app.pdfa.storage

import java.io.File

/** Same three states as [PendingStatus]. [PendingCopyStatus.Unknown] means the source may be partial. */
typealias PendingCopyStatus = PendingStatus

/** Why an autosave cannot be restored. Reasons are fixed names; they never include paths or content. */
enum class NotRecoverableReason {
    MANIFEST_UNREADABLE,
    RECORD_MISSING,
    RECORD_INVALID,
    PDF_MISSING,
    PDF_SIZE_MISMATCH,
    PDF_HASH_MISMATCH,
    PDF_UNREADABLE,
}

/**
 * A verified autosave. [generation] is the generation file that verified. [usedFallback] is true when the newest
 * generation failed verification and the previous one was chosen. [sourceChanged] compares the record's manifest hash
 * with the manifest on disk. It never reads the SAF file, so an external edit is not detected here. Null means unknown.
 */
data class RecoverableDoc(
    val docId: String,
    val displayName: String?,
    val autosaveSavedAt: Long,
    val autosaveSize: Long,
    val generation: Long,
    val usedFallback: Boolean,
    val sourceChanged: Boolean?,
    val hasPendingCopy: PendingCopyStatus,
)

/** Per-document verdict from [RecoveryScanner.scanAll]. */
sealed interface RecoveryCandidate {
    val docId: String

    data class Recoverable(val doc: RecoverableDoc) : RecoveryCandidate {
        override val docId: String get() = doc.docId
    }

    data class NotRecoverable(override val docId: String, val reason: NotRecoverableReason) : RecoveryCandidate
}

/** Thrown by restore paths when no autosave generation verifies. [reason] is the fixed verdict. */
class RecoveryException(val reason: NotRecoverableReason) : Exception(reason.name)

/**
 * Finds autosaves under `work/`. Read-only: it never deletes, renames or writes. Directories whose names are not
 * document ids, and directories without autosave files, are skipped silently. Blocking I/O: call off the main thread.
 */
class RecoveryScanner internal constructor(
    rootDir: File,
    private val pendingStatus: (ImportedDoc) -> PendingStatus,
) {

    constructor(rootDir: File, writer: SafWriter) : this(rootDir, writer::pendingCopyStatus)

    private val workDir = File(rootDir, "work")

    /** Verified autosaves only. */
    fun scan(): List<RecoverableDoc> =
        scanAll().filterIsInstance<RecoveryCandidate.Recoverable>().map { it.doc }

    /** Every document that has autosave files, verified or not, in document-id order. */
    fun scanAll(): List<RecoveryCandidate> {
        val dirs = workDir.listFiles().orEmpty()
            .filter { it.isDirectory && isDocId(it.name) }
            .sortedBy { it.name }
        return dirs.mapNotNull { inspect(it.name, it) }
    }

    /** Verdict for one document, or null when it has no autosave files or the id is not valid. */
    internal fun inspectOne(docId: String): RecoveryCandidate? {
        if (!isDocId(docId)) return null
        return inspect(docId, File(workDir, docId))
    }

    private fun inspect(docId: String, dir: File): RecoveryCandidate? {
        if (!File(dir, AutosaveLayout.RECORD).exists() && generationNumbers(dir).isEmpty()) return null
        val manifest = try {
            Layout.readManifest(dir)
        } catch (e: Exception) {
            return RecoveryCandidate.NotRecoverable(docId, NotRecoverableReason.MANIFEST_UNREADABLE)
        }
        return when (val check = checkAutosave(dir)) {
            is AutosaveCheck.Invalid -> RecoveryCandidate.NotRecoverable(docId, check.reason)
            is AutosaveCheck.Valid -> RecoveryCandidate.Recoverable(
                RecoverableDoc(
                    docId = docId,
                    displayName = manifest.displayName,
                    autosaveSavedAt = check.slot.savedAtEpochMs,
                    autosaveSize = check.slot.size,
                    generation = check.slot.generation,
                    usedFallback = check.fallback,
                    sourceChanged = check.slot.sourceManifestSha256 != manifest.sha256,
                    hasPendingCopy = pendingStatus(ImportedDoc(docId, dir, manifest)),
                ),
            )
        }
    }
}
