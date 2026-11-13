package app.pdfa.app.engine

import app.pdfa.engine.impl.RecoveryProvider
import app.pdfa.storage.AutosaveRecoverySource
import app.pdfa.storage.WorkspaceStore
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap

/**
 * What the engine client reopens a document from after the engine process restarted. The key is
 * the workspace document id (`ImportedDoc.docId`), which the app passes as `docKey` when it opens.
 *
 * Order: the verified autosave if there is one, else the working copy of the original
 * (`original.pdf`, checked against the size in its manifest). Passwords are never stored: the
 * engine client reuses the one given at open, in memory.
 */
class WorkspaceRecovery(
    private val store: WorkspaceStore,
    private val autosave: AutosaveRecoverySource,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) : RecoveryProvider {
    private val fromOriginal = ConcurrentHashMap<String, Boolean>()

    override suspend fun latestBytes(docKey: String): ByteArray? {
        autosave.latestBytes(docKey)?.let {
            fromOriginal[docKey] = false
            return it
        }
        val original = withContext(io) {
            try {
                val doc = store.load(docKey)
                doc.file.readBytes().takeIf { it.size.toLong() == doc.manifest.size }
            } catch (e: Exception) {
                // Missing or unreadable working copy: the document is not recoverable.
                null
            }
        }
        if (original != null) fromOriginal[docKey] = true
        return original
    }

    override fun passwordFor(docKey: String): String? = null

    override fun restoredFromOriginal(docKey: String): Boolean = fromOriginal[docKey] ?: false

    /** Forget a closed document. */
    fun forget(docKey: String) {
        fromOriginal.remove(docKey)
    }
}
