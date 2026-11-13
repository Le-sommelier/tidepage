package app.pdfa.storage

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Bridge for the app to offer recovered bytes to the engine client later. Passwords are never persisted, so
 * [passwordFor] is always null.
 *
 * Identifiers: the workspace uses `docId` (the directory name under `work/`, from `ImportedDoc.docId`). The recents
 * index uses `docKey` (`UriStreams.documentKey` of the uri, or the raw uri). The two are different strings. Recents
 * rows do not store a docId, so the app must keep the link when it imports: store `ImportedDoc.docId` next to the
 * recents row (or look it up through the manifest uri). Then pass the docId here. Never pass a docKey.
 */
class AutosaveRecoverySource internal constructor(
    private val store: WorkspaceStore,
    private val scanner: RecoveryScanner,
    private val ioDispatcher: CoroutineDispatcher,
) {

    constructor(store: WorkspaceStore, scanner: RecoveryScanner) : this(store, scanner, Dispatchers.IO)

    /**
     * The verified autosave bytes for workspace document [docId] (see the class note; not a recents docKey), or null
     * when it is not recoverable. Never throws.
     */
    suspend fun latestBytes(docId: String): ByteArray? = withContext(ioDispatcher) {
        if (!isDocId(docId)) return@withContext null
        if (scanner.inspectOne(docId) !is RecoveryCandidate.Recoverable) return@withContext null
        try {
            readVerifiedAutosave(store.load(docId).dir)
        } catch (e: Exception) {
            null
        }
    }

    /** Always null. Passwords are never written to disk, so none can be recovered. */
    @Suppress("UNUSED_PARAMETER")
    fun passwordFor(docId: String): String? = null
}
