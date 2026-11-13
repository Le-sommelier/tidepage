package app.pdfa.storage

import java.io.File
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Outcome of removing autosave files. Only `autosave.json`, `autosave.tmp` and `autosave.<gen>.pdf` files are ever
 * removed. Staging, the pending marker, `original.pdf` and `manifest.json` are never touched.
 */
sealed interface RemovalResult {
    data class Removed(val files: Int) : RemovalResult

    /** A copy is pending: `staging.pdf` may be the only full copy. Nothing was removed. */
    data object RefusedPendingCopy : RemovalResult

    /** The pending state could not be read (or the manifest is unreadable). Nothing was removed. */
    data class RefusedUnknownStatus(val detail: String) : RemovalResult

    data class Failed(val detail: String) : RemovalResult
}

/**
 * Restore, discard and cleanup of autosaves. Every method takes the document's journal lock, so it never races a save.
 *
 * Call order for a save: call `SafWriter.saveBack` (or `finishPendingCopy`) first. Call [afterSuccessfulSave] only
 * when it returned `SaveResult.Saved`. For `SavedButManifestStale`, `CopyFailed`, `Unverifiable`, `Conflict` or
 * `VerificationFailed`, keep the autosave. [afterSuccessfulSave] first cancels any debounced autosave for the document,
 * so bytes taken before the save cannot come back afterwards.
 *
 * Document ids are validated before any file is touched.
 */
class RecoveryActions internal constructor(
    private val rootDir: File,
    private val store: WorkspaceStore,
    private val journal: AutosaveJournal,
    private val pendingStatus: (ImportedDoc) -> PendingStatus,
    private val clock: () -> Long,
    private val ioDispatcher: CoroutineDispatcher,
    private val fs: StorageFs,
) {

    constructor(rootDir: File, store: WorkspaceStore, journal: AutosaveJournal, writer: SafWriter) : this(
        rootDir,
        store,
        journal,
        writer::pendingCopyStatus,
        System::currentTimeMillis,
        Dispatchers.IO,
        RealFs,
    )

    private val workDir = File(rootDir, "work")

    /** The verified autosave bytes. Throws [RecoveryException] when no generation verifies. */
    suspend fun restore(docId: String): ByteArray {
        if (!isDocId(docId)) throw RecoveryException(NotRecoverableReason.MANIFEST_UNREADABLE)
        return journal.withDocument(docId) {
            withContext(ioDispatcher) {
                val doc = loadOrNull(docId) ?: throw RecoveryException(NotRecoverableReason.MANIFEST_UNREADABLE)
                readVerifiedAutosave(doc.dir)
            }
        }
    }

    /**
     * Deletes every autosave generation and the record of a document the user chose to drop. Refuses while a copy is
     * pending or the pending state is unknown, because then `staging.pdf` may be the only full copy.
     */
    suspend fun discard(docId: String): RemovalResult {
        if (!isDocId(docId)) return RemovalResult.Failed("invalid document id")
        return journal.withDocument(docId) {
            withContext(ioDispatcher) {
                val doc = loadOrNull(docId) ?: return@withContext RemovalResult.RefusedUnknownStatus("manifest unreadable")
                when (val status = pendingStatus(doc)) {
                    PendingStatus.Pending -> RemovalResult.RefusedPendingCopy
                    is PendingStatus.Unknown -> RemovalResult.RefusedUnknownStatus(status.detail)
                    PendingStatus.NotPending -> {
                        journal.dropPending(docId)
                        removeAutosave(File(workDir, docId))
                    }
                }
            }
        }
    }

    /**
     * Deletes all autosave generations and the record once a save has been verified in the source. See the class note
     * for the call order. Refuses if a copy is still pending.
     */
    suspend fun afterSuccessfulSave(docId: String): RemovalResult {
        if (!isDocId(docId)) return RemovalResult.Failed("invalid document id")
        journal.dropPending(docId)
        return journal.withDocument(docId) {
            withContext(ioDispatcher) {
                val doc = loadOrNull(docId) ?: return@withContext RemovalResult.Failed("manifest unreadable")
                when (val status = pendingStatus(doc)) {
                    PendingStatus.NotPending -> removeAutosave(doc.dir)
                    PendingStatus.Pending -> RemovalResult.RefusedPendingCopy
                    is PendingStatus.Unknown -> RemovalResult.RefusedUnknownStatus(status.detail)
                }
            }
        }
    }

    /**
     * Two kinds of cleanup, both under the document's lock. (1) Autosaves whose record is older than [maxAgeMs] are
     * removed entirely. (2) Orphan generations, which the record names as neither current nor previous, are removed
     * whatever their age. Documents with a pending or unknown copy are kept unless [keepPending] is false. Even then only
     * autosave files are removed. Returns the number of documents where something was removed.
     */
    suspend fun sweepOldAutosaves(maxAgeMs: Long, keepPending: Boolean = true): Int {
        require(maxAgeMs >= 0) { "negative age" }
        val now = clock()
        val dirs = withContext(ioDispatcher) {
            workDir.listFiles().orEmpty().filter { it.isDirectory && isDocId(it.name) }.sortedBy { it.name }
        }
        var swept = 0
        for (dir in dirs) {
            val changed = journal.withDocument(dir.name) {
                withContext(ioDispatcher) {
                    if (keepPending) {
                        val status = loadOrNull(dir.name)?.let { pendingStatus(it) }
                        if (status != PendingStatus.NotPending) return@withContext false
                    }
                    val record = readRecord(dir)
                    val age = record?.current?.savedAtEpochMs
                        ?: generationNumbers(dir).maxOfOrNull { generationFile(dir, it).lastModified() }?.takeIf { it > 0 }
                    val aged = age != null && now - age >= maxAgeMs
                    if (aged) {
                        removeAutosave(dir) is RemovalResult.Removed
                    } else if (record != null) {
                        val named = setOfNotNull(record.current.generation, record.previous?.generation)
                        val orphans = generationNumbers(dir).filter { it !in named }
                        orphans.isNotEmpty() && orphans.all { fs.delete(generationFile(dir, it)) }
                    } else {
                        false
                    }
                }
            }
            if (changed) swept++
        }
        return swept
    }

    private fun loadOrNull(docId: String): ImportedDoc? = try {
        store.load(docId)
    } catch (e: Exception) {
        null
    }

    /** Removes the record, every generation file and the temp file, record first. */
    private fun removeAutosave(dir: File): RemovalResult {
        val targets = buildList {
            add(File(dir, AutosaveLayout.RECORD))
            generationNumbers(dir).forEach { add(generationFile(dir, it)) }
            add(File(dir, AutosaveLayout.TMP))
        }
        var removed = 0
        for (file in targets) {
            if (!file.exists()) continue
            if (!fs.delete(file)) return RemovalResult.Failed("autosave file could not be removed")
            removed++
        }
        return RemovalResult.Removed(removed)
    }
}
