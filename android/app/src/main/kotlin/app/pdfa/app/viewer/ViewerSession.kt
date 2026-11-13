package app.pdfa.app.viewer

import android.content.Context
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import app.pdfa.annotate.AnnotateController
import app.pdfa.app.AppContainer
import app.pdfa.app.intent.DocRef
import app.pdfa.app.recents.DisplayNames
import app.pdfa.forms.FormsController
import app.pdfa.app.save.DocumentSession
import app.pdfa.app.save.SaveOutcome
import app.pdfa.engine.DocHandle
import app.pdfa.engine.EngineApi
import app.pdfa.engine.EngineError
import app.pdfa.engine.EngineEvent
import app.pdfa.engine.PageInfo
import app.pdfa.engine.impl.RemoteEngine
import app.pdfa.storage.StorageError
import app.pdfa.storage.StorageException
import app.pdfa.viewer.DocumentBytes
import app.pdfa.viewer.DocumentSource
import app.pdfa.viewer.ViewerState
import app.pdfa.viewer.ViewerViewModel
import app.pdfa.viewer.extras.AndroidTextClipboard
import app.pdfa.viewer.extras.AndroidUriOpener
import app.pdfa.viewer.extras.ConfirmedLinkOpener
import app.pdfa.viewer.extras.LinkResolver
import app.pdfa.viewer.extras.OutlineController
import app.pdfa.viewer.extras.SearchController
import app.pdfa.viewer.extras.SelectionController
import app.pdfa.viewer.extras.ThumbnailLoader
import app.pdfa.viewer.ui.ViewerViewModelFactory
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The engine as one viewer sees it.
 *
 *  - `open` goes through [RemoteEngine.openWithKey] with the workspace document id, so after an
 *    engine restart the document is reopened from the working copy (or autosave) on disk and the
 *    client keeps no second copy of the bytes in memory.
 *  - `events` hides what happened before this viewer existed. The engine client replays its last
 *    critical events to every new collector, which would show a stale "engine restarted" banner on
 *    each document opened after a restart.
 */
internal class DocEngine(
    private val base: EngineApi,
    private val docKey: () -> String?,
    private val sinceEpochMs: Long,
) : EngineApi by base {

    override suspend fun open(bytes: ByteArray, password: String?): DocHandle =
        if (base is RemoteEngine) base.openWithKey(bytes, password, docKey()) else base.open(bytes, password)

    override val events: Flow<EngineEvent> = base.events.filter { e ->
        when (e) {
            is EngineEvent.SafeModeRestarted -> e.atEpochMs >= sinceEpochMs
            is EngineEvent.EditsLost -> e.atEpochMs >= sinceEpochMs
            is EngineEvent.Progress -> true
        }
    }
}

/** Search, contents, page previews, text selection and links for one open document. */
internal class ViewerAddOns(
    engine: EngineApi,
    handle: DocHandle,
    val pages: List<PageInfo>,
    scope: CoroutineScope,
    context: Context,
) {
    private val calls = Dispatchers.Default
    val search = SearchController(engine, handle, pages.size, calls, scope)
    val outline = OutlineController(engine, handle, pages.size, calls)
    val thumbnails = ThumbnailLoader(engine, handle, pages, THUMB_WIDTH_PX, calls, scope)
    val selection = SelectionController(engine, handle, calls, scope, AndroidTextClipboard(context))
    val links = LinkResolver(engine, handle, pages.size, calls)
    val opener = ConfirmedLinkOpener(AndroidUriOpener(context))

    fun close() {
        search.clear()
        selection.clear()
        thumbnails.close()
    }

    private companion object {
        const val THUMB_WIDTH_PX = 240
    }
}

/**
 * One open document: the import into the workspace, the viewer view-model on the real engine and
 * the add-ons. Lives in [ViewerSessions] across rotation. Holds no bytes and no password itself.
 */
internal class ViewerSession(
    val doc: DocRef,
    private val container: AppContainer,
    private val appContext: Context,
    /** When set, the recovered autosave of this working copy is opened instead of importing the file. */
    val restoreDocId: String? = null,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val store = ViewModelStore()

    /** The workspace id of the working copy, set by the import. Also the engine recovery key. */
    @Volatile
    var docId: String? = null
        private set

    val engine: EngineApi = DocEngine(container.engineProvider.engine, { docId }, System.currentTimeMillis())

    val viewModel: ViewerViewModel = ViewModelProvider(
        store,
        ViewerViewModelFactory(engine, ImportingSource(), appContext),
    )[ViewerViewModel::class.java]

    private val _addOns = MutableStateFlow<ViewerAddOns?>(null)
    val addOns: StateFlow<ViewerAddOns?> = _addOns.asStateFlow()

    /** Edit state, autosave and saving for the open document. Null until it is open. */
    private val _document = MutableStateFlow<DocumentSession?>(null)
    val document: StateFlow<DocumentSession?> = _document.asStateFlow()

    /**
     * Annotation state for the open document. Lives here, not in the screen, so open and failed
     * strokes survive the activity being recreated. Its own scope: it is flushed before closing.
     */
    private val annotateScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val _annotate = MutableStateFlow<AnnotateController?>(null)
    val annotate: StateFlow<AnnotateController?> = _annotate.asStateFlow()

    /** Form fields of the open document. Its edits go through the document session (undo, autosave). */
    private val _forms = MutableStateFlow<FormsController?>(null)
    val forms: StateFlow<FormsController?> = _forms.asStateFlow()

    /** The result of the last save, until the UI dismissed it. */
    private val _outcome = MutableStateFlow<SaveOutcome?>(null)
    val outcome: StateFlow<SaveOutcome?> = _outcome.asStateFlow()

    /** True when the bytes given to the engine were a recovered autosave. */
    @Volatile
    private var openedFromAutosave = false

    private var closed = false

    init {
        scope.launch {
            viewModel.state.collect { state ->
                if (state is ViewerState.Ready && _document.value == null) {
                    val handle = viewModel.documentHandle ?: return@collect
                    val id = docId ?: return@collect
                    _addOns.value = ViewerAddOns(engine, handle, state.pages, scope, appContext)
                    // Recents change only once the document really opened.
                    val name = viewModel.title.value.ifBlank { DisplayNames.fromUri(doc.uri) }
                    container.appScope.launch { container.recents.add(doc, name) }
                    startDocument(handle, id)
                }
            }
        }
        scope.launch {
            engine.events.collect { e ->
                if (e is EngineEvent.EditsLost && e.handle == viewModel.documentHandle) {
                    _document.value?.onEditsLost(e.originalRestored)
                }
            }
        }
    }

    private suspend fun startDocument(handle: DocHandle, id: String) {
        val session = try {
            val imported = withContext(Dispatchers.IO) { container.workspace.load(id) }
            DocumentSession(
                engine, handle, imported, container.safWriter, container.streams, container.journal,
                container.recoveryActions, engine.state(handle), restored = openedFromAutosave,
            ).also { it.refreshPending() }
        } catch (e: Exception) {
            // Without a session the document stays read-only: no edit and no save is offered.
            return
        }
        _document.value = session
        _annotate.value = AnnotateController(edit = { change -> session.edit(change) }, scope = annotateScope)
        val formsController = FormsController(engine, handle, edit = { change -> session.edit(change) }, dispatcher = Dispatchers.Default)
        _forms.value = formsController
        scope.launch { formsController.load() }
        // Every change of content redraws the pages and rebuilds what was derived from them.
        scope.launch {
            var seen = session.state.value.revision
            session.state.collect { s ->
                if (s.revision != seen) {
                    seen = s.revision
                    viewModel.onDocumentChanged()
                    // Undo and redo change field values too.
                    launch { formsController.load() }
                }
            }
        }
        scope.launch {
            viewModel.contentGeneration.collect { generation ->
                val ready = viewModel.state.value as? ViewerState.Ready
                if (generation > 0 && ready != null) {
                    val old = _addOns.value
                    // An open search outlives the rebuild: same query and options, from the page on screen.
                    val previous = old?.search?.state?.value
                    old?.close()
                    val fresh = ViewerAddOns(engine, handle, ready.pages, scope, appContext)
                    _addOns.value = fresh
                    launch { formsController.load() }
                    if (previous != null && previous.query.isNotEmpty()) {
                        fresh.search.submit(previous.query, previous.options, viewModel.view.value.currentPage)
                    }
                }
            }
        }
    }

    /** Save a copy to [targetUri] (the result of the system's create-document picker). */
    fun saveCopyTo(targetUri: String, exportPending: Boolean = false, then: (SaveOutcome) -> Unit = {}) = save(then) {
        if (exportPending) it.exportPending(targetUri) else it.saveCopy(targetUri)
    }

    /**
     * Writes [bytes] as a new file at [targetUri] (a create-document result), off the main thread.
     * False when anything failed: the file may then be empty or incomplete, and the caller says so.
     */
    suspend fun writeNewFile(targetUri: String, bytes: ByteArray): Boolean = withContext(Dispatchers.IO) {
        try {
            container.streams.openOutputTruncating(targetUri).use { out ->
                out.write(bytes)
                out.flush()
                out.sync()
            }
            true
        } catch (e: java.io.IOException) {
            false
        } catch (e: SecurityException) {
            false
        }
    }

    /** Replace original. [overwrite] only after the user chose it in the conflict dialog. */
    fun saveToOriginal(overwrite: Boolean = false, then: (SaveOutcome) -> Unit = {}) = save(then) { it.saveToOriginal(overwrite) }

    fun retrySave(overwrite: Boolean = false, then: (SaveOutcome) -> Unit = {}) = save(then) { it.retryPending(overwrite) }

    private fun save(then: (SaveOutcome) -> Unit, block: suspend (DocumentSession) -> SaveOutcome) {
        val session = _document.value ?: return
        // In the app scope: a save that started must finish even if the screen goes away.
        container.appScope.launch {
            val result = block(session)
            _outcome.value = result
            withContext(Dispatchers.Main) { then(result) }
        }
    }

    fun dismissOutcome() {
        _outcome.value = null
    }

    fun undo() = editQuietly { it.undo() }

    fun redo() = editQuietly { it.redo() }

    private fun editQuietly(block: suspend (DocumentSession) -> Unit) {
        val session = _document.value ?: return
        scope.launch {
            try {
                block(session)
            } catch (e: EngineError) {
                // Nothing to undo any more, or the engine restarted: the state was re-read.
            }
        }
    }

    /**
     * The app went to the background: write a pending autosave now, so unsaved work survives the
     * process being killed there.
     */
    fun onStop() {
        val session = _document.value ?: return
        val annotate = _annotate.value
        container.appScope.launch(Dispatchers.Main.immediate) {
            // Strokes still in their group window go into the document first, then the autosave.
            annotate?.flush()
            session.flush()
        }
    }

    /**
     * Closes the engine handle (through the view-model). Unsaved work is autosaved first and stays on
     * disk, to be offered at the next start; a working copy without any is deleted.
     */
    fun close() {
        if (closed) return
        closed = true
        scope.cancel()
        _addOns.value?.close()
        _addOns.value = null
        val session = _document.value
        val id = docId
        val annotate = _annotate.value
        container.appScope.launch(Dispatchers.Main.immediate) {
            // Open strokes first: they are part of the unsaved work.
            annotate?.flush()
            annotateScope.cancel()
            // Before the handle closes: the autosave asks the engine for the bytes.
            if (session != null && session.state.value.dirty) session.flush()
            store.clear() // ViewerViewModel.onCleared closes the handle
            if (id != null) {
                container.openDocIds.remove(id)
                discard(id)
            }
        }
    }

    private fun discard(id: String) {
        container.recovery.forget(id)
        // Only a copy that holds nothing but the imported original is removed.
        container.appScope.launch(Dispatchers.IO) { container.workingCopies.discard(id) }
    }

    /**
     * Copies the document into the workspace (size cap, `%PDF-` check, off the main thread) and
     * hands the bytes to the viewer. The viewer drops them as soon as the engine has them.
     */
    private inner class ImportingSource : DocumentSource {
        override suspend fun read(): DocumentBytes {
            container.startupSweep.await()
            restoreDocId?.let { id -> restore(id)?.let { return it } }
            // "Try again" imports again: the earlier copy is not needed any more.
            docId?.let { docId = null; container.openDocIds.remove(it); discard(it) }
            val imported = try {
                container.workspace.importFrom(doc.uri, container.streams)
            } catch (e: StorageException) {
                when (val error = e.error) {
                    is StorageError.TooLarge -> throw EngineError.TooLarge(error.detail)
                    is StorageError.NotPdf -> throw EngineError.InvalidPdf(error.detail)
                    else -> {
                        // The provider no longer serves it (moved, deleted, permission gone).
                        container.recents.markUnavailable(doc)
                        throw e
                    }
                }
            }
            docId = imported.docId
            container.openDocIds.add(imported.docId)
            val bytes = withContext(Dispatchers.IO) { imported.file.readBytes() }
            val name = imported.manifest.displayName?.takeIf { it.isNotBlank() } ?: DisplayNames.fromUri(doc.uri)
            return DocumentBytes(bytes, name)
        }

        /** The recovered autosave of working copy [id], or null when it cannot be restored (then: a normal open). */
        private suspend fun restore(id: String): DocumentBytes? {
            val bytes = try {
                container.recoveryActions.restore(id)
            } catch (e: Exception) {
                return null
            }
            val manifest = try {
                withContext(Dispatchers.IO) { container.workspace.load(id).manifest }
            } catch (e: Exception) {
                return null
            }
            docId = id
            container.openDocIds.add(id)
            openedFromAutosave = true
            return DocumentBytes(bytes, manifest.displayName?.takeIf { it.isNotBlank() } ?: DisplayNames.fromUri(doc.uri))
        }
    }
}
