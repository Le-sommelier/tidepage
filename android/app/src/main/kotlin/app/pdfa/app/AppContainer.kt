package app.pdfa.app

import android.content.Context
import app.pdfa.app.engine.WorkingCopies
import app.pdfa.app.engine.WorkspaceRecovery
import app.pdfa.app.intent.DocRef
import app.pdfa.app.intent.IntentRouter
import app.pdfa.app.recents.DisplayNames
import app.pdfa.app.recents.RecentsRepository
import app.pdfa.app.recents.StoreRecentsRepository
import app.pdfa.app.settings.PrefsSettingsStore
import app.pdfa.app.settings.SettingsHolder
import app.pdfa.app.viewer.RealViewerEntry
import app.pdfa.app.viewer.ViewerEntry
import app.pdfa.engine.EngineApi
import app.pdfa.storage.AutosaveJournal
import app.pdfa.storage.AutosaveRecoverySource
import app.pdfa.storage.ContentResolverStreams
import app.pdfa.storage.RecentsStore
import app.pdfa.storage.RecoveryActions
import app.pdfa.storage.RemovalResult
import app.pdfa.storage.RecoveryScanner
import app.pdfa.storage.SafWriter
import app.pdfa.storage.UriStreams
import app.pdfa.storage.WorkspaceStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.withContext
import java.io.File

/** Unsaved changes that can be restored: the working copy [docId] of the document at [doc]. */
data class RecoverableWork(val docId: String, val doc: DocRef, val name: String, val savedAtMs: Long)

/** Supplies the engine. The app code only ever sees [EngineApi]. */
interface EngineProvider {
    /** Created on first access, one per process. Never touch it on the main thread for I/O: calls are `suspend`. */
    val engine: EngineApi

    /** Ends the engine process. The next [engine] access starts a new one. */
    fun shutdown()
}

/**
 * Manual dependency injection: one object, built once in [PdfaApplication]. Feature modules plug
 * in through this container; the shell never imports their internals.
 *
 * Building it does no I/O on the calling thread: files are touched on [Dispatchers.IO] only.
 */
class AppContainer(context: Context) {
    private val appContext = context.applicationContext

    /** Work that must outlive a screen (closing a document, recents writes, autosave flush). */
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /** Only `content://` streams go through here. Nothing is read on the main thread. */
    val streams: UriStreams = ContentResolverStreams(appContext)

    /** Working copies and saves live under the app's private files directory. */
    private val workspaceRoot = File(appContext.filesDir, "workspace")
    val workspace = WorkspaceStore(workspaceRoot)
    val safWriter = SafWriter()
    val workingCopies = WorkingCopies(workspaceRoot)

    /**
     * Startup cleanup: import leftovers and working copies without user work. An import waits for it,
     * so the sweep can never delete a copy that was just made.
     */
    val startupSweep: Deferred<Unit> = appScope.async(Dispatchers.IO) {
        workspace.sweepTmp()
        workingCopies.sweepPristine()
        Unit
    }

    /**
     * Autosave: every edit goes through a DocumentSession, which records it here (2 s debounce) and
     * flushes when the app goes to the background.
     */
    val journal = AutosaveJournal(workspace, appScope)

    val recovery = WorkspaceRecovery(
        workspace,
        AutosaveRecoverySource(workspace, RecoveryScanner(workspaceRoot, safWriter)),
    )

    /** Restore and discard of autosaves; the only code that deletes them. */
    val recoveryActions = RecoveryActions(workspaceRoot, workspace, journal, safWriter)
    private val recoveryScanner = RecoveryScanner(workspaceRoot, safWriter)

    /**
     * Unsaved work found on disk (a crash, a kill, or leaving without saving), newest first. Documents
     * in [openDocIds] are being edited right now and are not offered.
     */
    suspend fun recoverable(): List<RecoverableWork> = withContext(Dispatchers.IO) {
        startupSweep.await()
        recoveryScanner.scan()
            .filter { it.docId !in openDocIds }
            .sortedByDescending { it.autosaveSavedAt }
            .mapNotNull { found ->
                val uri = try {
                    workspace.load(found.docId).manifest.uri
                } catch (e: Exception) {
                    return@mapNotNull null
                }
                val ref = IntentRouter.acceptDocUri(uri) ?: return@mapNotNull null
                RecoverableWork(found.docId, ref, found.displayName?.takeIf { it.isNotBlank() } ?: DisplayNames.fromUri(uri), found.autosaveSavedAt)
            }
    }

    /** Workspace ids of the documents open in a viewer. */
    val openDocIds: MutableSet<String> = java.util.concurrent.ConcurrentHashMap.newKeySet()

    /**
     * The user discarded recovered work. False when it was refused because a save to the original
     * file is unfinished: the staged bytes may be the only full copy, so nothing is deleted.
     */
    suspend fun discardRecoverable(docId: String): Boolean {
        if (recoveryActions.discard(docId) !is RemovalResult.Removed) return false
        withContext(Dispatchers.IO) { workingCopies.discard(docId) }
        return true
    }

    val engineProvider: EngineProvider = createEngineProvider(appContext, recovery)

    /** File backed (`recents.json` in the app's private files). Loaded off the main thread. */
    val recents: RecentsRepository = StoreRecentsRepository(
        open = { RecentsStore(File(appContext.filesDir, "recents").apply { mkdirs() }, streams) },
        scope = appScope,
    )

    val settings = SettingsHolder(
        PrefsSettingsStore(appContext.getSharedPreferences(PrefsSettingsStore.PREFS_NAME, Context.MODE_PRIVATE)),
    )

    val viewer: ViewerEntry = RealViewerEntry(this)
}
