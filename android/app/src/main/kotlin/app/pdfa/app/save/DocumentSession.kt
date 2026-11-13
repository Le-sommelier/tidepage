package app.pdfa.app.save

import app.pdfa.engine.DocHandle
import app.pdfa.engine.EditState
import app.pdfa.engine.EngineApi
import app.pdfa.engine.EngineError
import app.pdfa.storage.AutosaveJournal
import app.pdfa.storage.CopyResult
import app.pdfa.storage.ImportedDoc
import app.pdfa.storage.PendingStatus
import app.pdfa.storage.RecoveryActions
import app.pdfa.storage.RemovalResult
import app.pdfa.storage.SafWriter
import app.pdfa.storage.SaveResult
import app.pdfa.storage.UriStreams
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** What every editing screen shows about the document. */
data class SessionState(
    val canUndo: Boolean = false,
    val canRedo: Boolean = false,
    val undoLabel: String? = null,
    val pageCount: Int = 0,
    /** Unsaved changes exist. Cleared only after a verified write AND the engine's `confirmSaved`. */
    val dirty: Boolean = false,
    val saving: Boolean = false,
    /** Bumped whenever the document content may have changed: the viewer and thumbnails redraw. */
    val revision: Int = 0,
    /** A copy-back to the user's file is unfinished (or its state is unreadable): the file may be partial. */
    val pendingCopy: Boolean = false,
    /** The engine restarted and unsaved changes since the last autosave are gone. */
    val editsLost: EditsLostNotice? = null,
)

data class EditsLostNotice(val originalRestored: Boolean)

/**
 * The one place a document is changed and saved. Every feature (organize, annotate, forms...) goes
 * through [edit], so dirty/undo state, autosave and saving behave the same everywhere.
 *
 * Rules it enforces:
 *  - every change schedules an autosave (2 s debounce in [AutosaveJournal]); [flush] writes it now;
 *  - saves never clear `dirty` by themselves: `confirmSaved` runs only after :storage verified the
 *    write, and only then is the outcome a "saved" one;
 *  - "Save a copy" never touches the original; "Replace original" goes through the conflict check;
 *  - a pending copy-back is finished or exported, never discarded.
 *
 * No Android types: runs on the JVM with the fake engine.
 *
 * @param restored true when the document was opened from a recovered autosave: it is unsaved work
 *   even though the engine sees a freshly opened file.
 */
class DocumentSession(
    private val engine: EngineApi,
    val handle: DocHandle,
    doc: ImportedDoc,
    private val writer: SafWriter,
    private val streams: UriStreams,
    private val journal: AutosaveJournal,
    private val recovery: RecoveryActions,
    initial: EditState,
    private val restored: Boolean = false,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) {
    /** Replaced after a successful save: the manifest then describes the new file. */
    @Volatile
    var doc: ImportedDoc = doc
        private set

    val docId: String get() = doc.docId

    /** Unsaved until a save succeeds, whatever the engine reports (recovered autosave, lost edits). */
    @Volatile
    private var forcedDirty = restored

    private val _state = MutableStateFlow(SessionState().merge(initial))
    val state: StateFlow<SessionState> = _state.asStateFlow()

    /**
     * True while `staging.pdf` holds exactly what the engine holds now: set by a save from this
     * session, cleared by every edit. Only then may a retry finish from the staged bytes; otherwise
     * they are older than the document and a retry must write the current bytes.
     */
    @Volatile
    private var stagedIsCurrent = false

    /** One edit or save at a time: a save must see exactly the bytes of the edits before it. */
    private val gate = Mutex()

    private fun SessionState.merge(e: EditState): SessionState = copy(
        canUndo = e.canUndo,
        canRedo = e.canRedo,
        undoLabel = e.undoLabel,
        pageCount = e.pageCount,
        dirty = e.dirty || forcedDirty,
    )

    /** Reads the pending-copy state from disk. Call once after opening, off the main thread. */
    suspend fun refreshPending() {
        val pending = withContext(io) { writer.pendingCopyStatus(doc) } != PendingStatus.NotPending
        _state.update { it.copy(pendingCopy = pending) }
    }

    // ---- editing ----

    /**
     * Runs one mutating engine call. The returned [EditState] becomes the session state, the content
     * revision is bumped and an autosave is scheduled. Engine errors are thrown to the caller, which
     * shows them; the state is then re-read so the UI never shows a stale undo button.
     */
    suspend fun edit(change: suspend EngineApi.(DocHandle) -> EditState): EditState = gate.withLock {
        try {
            val next = engine.change(handle)
            stagedIsCurrent = false
            _state.update { it.merge(next).copy(revision = it.revision + 1) }
            if (next.dirty || forcedDirty) scheduleAutosave()
            next
        } catch (e: EngineError) {
            resync()
            throw e
        }
    }

    suspend fun undo(): EditState = edit { undo(it) }

    suspend fun redo(): EditState = edit { redo(it) }

    private fun scheduleAutosave() {
        journal.onEdited(docId) { engine.saveIncremental(handle) }
    }

    /** Writes a pending autosave now (the app goes to the background). Never throws. */
    suspend fun flush() {
        try {
            journal.flush(docId)
        } catch (c: CancellationException) {
            throw c
        } catch (e: Exception) {
            // The journal reports failures through its own events.
        }
    }

    private suspend fun resync() {
        try {
            val now = engine.state(handle)
            _state.update { it.merge(now) }
        } catch (e: EngineError) {
            // The engine is gone; the next event or call reports it.
        }
    }

    /**
     * The engine restarted and reopened this document without the changes made since the last
     * autosave. The document stays unsaved until a save succeeds; undo history is gone.
     */
    suspend fun onEditsLost(originalRestored: Boolean) {
        val hadWork = _state.value.dirty
        forcedDirty = forcedDirty || (hadWork && !originalRestored)
        resync()
        _state.update {
            it.copy(
                revision = it.revision + 1,
                // Nothing to report when there was no unsaved work.
                editsLost = if (hadWork) EditsLostNotice(originalRestored) else it.editsLost,
            )
        }
    }

    fun dismissEditsLost() = _state.update { it.copy(editsLost = null) }

    // ---- saving ----

    /**
     * Save a copy: writes the current document to [targetUri]. The original is not modified, unless the
     * user picked the original itself through an address :storage could not match to it; the outcome is
     * then [SaveOutcome.Saved] (original replaced), never "copy saved, original unchanged".
     */
    suspend fun saveCopy(targetUri: String): SaveOutcome = saving {
        val bytes = produce { saveFull(it) } ?: return@saving lastEngineFailure
        val result = writer.saveCopyTo(doc, bytes, targetUri, streams, verifier())
        if (result is CopyResult.Copied) {
            // The chosen place was the original under another address: it now holds the changes.
            result.replacedSource?.let { doc = it }
            // The work is safe in the new file: it is no longer unsaved, and no autosave is needed.
            if (!confirm()) return@saving SaveOutcome.SavedUnconfirmed
            dropAutosave()
        }
        SaveOutcome.from(result)
    }

    /**
     * Replace original: writes back to the file the document came from. [overwrite] true forces the
     * write when the file changed elsewhere; :storage then keeps that other version as a snapshot.
     */
    suspend fun saveToOriginal(overwrite: Boolean = false): SaveOutcome = saving { saveCurrent(overwrite) }

    /**
     * Retry after CopyFailed / Unverifiable / SavedRecordStale. Finishes the pending copy from staging
     * only when the staged bytes are still the document: staged by this session with no edit since, or
     * left by an earlier run while this session has no unsaved work. In every other case the document
     * is newer than staging (edits after the failed save, or a recovered autosave), and finishing from
     * staging would report "saved" for a file without those edits: the current bytes are written instead.
     */
    suspend fun retryPending(overwrite: Boolean = false): SaveOutcome = saving {
        val pending = withContext(io) { writer.pendingCopyStatus(doc) } != PendingStatus.NotPending
        if (pending && (stagedIsCurrent || !_state.value.dirty)) {
            finishSave(writer.finishPendingCopy(doc, streams, overwrite))
        } else {
            saveCurrent(overwrite)
        }
    }

    private suspend fun saveCurrent(overwrite: Boolean): SaveOutcome {
        // Autosave first: if the copy-back fails and the process dies, the recovered document is then
        // never older than the staged bytes, which is what retryPending relies on after a restart.
        flush()
        val bytes = produce { saveIncremental(it) } ?: return lastEngineFailure
        val result = writer.saveBack(doc, bytes, streams, verifier(), overwrite)
        stagedIsCurrent = true
        return finishSave(result)
    }

    /** "Save a copy elsewhere" while a copy-back is pending: exports the staged bytes. Staging is kept. */
    suspend fun exportPending(targetUri: String): SaveOutcome = saving {
        SaveOutcome.from(writer.exportStaging(doc, streams, targetUri), confirmed = true).let {
            // An export does not end the pending state of the original, so nothing is confirmed here.
            if (it is SaveOutcome.CopySaved) SaveOutcome.CopySaved else it
        }
    }

    private suspend fun finishSave(result: SaveResult): SaveOutcome {
        when (result) {
            is SaveResult.Saved -> {
                doc = result.doc
                if (!confirm()) return SaveOutcome.SavedUnconfirmed
                try {
                    recovery.afterSuccessfulSave(docId)
                } catch (e: Exception) {
                    // A leftover autosave is harmless: it holds the bytes just saved.
                }
            }
            is SaveResult.SavedButManifestStale -> doc = result.doc
            else -> Unit
        }
        return SaveOutcome.from(result)
    }

    /** `confirmSaved`, the only thing that clears dirty. False when the engine could not do it. */
    private suspend fun confirm(): Boolean = try {
        val next = engine.confirmSaved(handle)
        forcedDirty = false
        _state.update { it.merge(next) }
        true
    } catch (e: EngineError) {
        false
    }

    private suspend fun dropAutosave() {
        try {
            recovery.discard(docId)
        } catch (e: Exception) {
            // Kept autosaves are offered again at the next start; never an error for the user now.
        }
    }

    @Volatile
    private var lastEngineFailure: SaveOutcome = SaveOutcome.EngineFailed(notPermitted = false)

    private suspend fun produce(call: suspend EngineApi.(DocHandle) -> ByteArray): ByteArray? = try {
        engine.call(handle)
    } catch (e: EngineError.NotPermitted) {
        lastEngineFailure = SaveOutcome.EngineFailed(notPermitted = true)
        null
    } catch (e: EngineError) {
        lastEngineFailure = SaveOutcome.EngineFailed(notPermitted = false)
        null
    }

    /**
     * :storage calls this (blocking, on its I/O thread) with the bytes about to be written: the engine
     * must be able to open them and find the same number of pages. A protected file that asks for its
     * password counts as readable (the app keeps no password to try).
     */
    private fun verifier(): (ByteArray) -> Boolean {
        val expected = _state.value.pageCount
        return { bytes ->
            runBlocking {
                try {
                    val h = engine.open(bytes, null)
                    try {
                        engine.pageInfos(h).size == expected
                    } finally {
                        try { engine.close(h) } catch (e: EngineError) { }
                    }
                } catch (e: EngineError.PasswordRequired) {
                    true
                } catch (e: EngineError) {
                    false
                }
            }
        }
    }

    private suspend fun saving(block: suspend () -> SaveOutcome): SaveOutcome = gate.withLock {
        _state.update { it.copy(saving = true) }
        try {
            block()
        } finally {
            val pending = try {
                withContext(io) { writer.pendingCopyStatus(doc) } != PendingStatus.NotPending
            } catch (e: Exception) {
                true
            }
            _state.update { it.copy(saving = false, pendingCopy = pending) }
        }
    }

    // ---- leaving ----

    /**
     * The user chose Discard. Drops the autosave, so the changes are not offered again. Refused (false)
     * while a copy-back is pending or unknown: the staged bytes may be the only full copy of the file.
     */
    suspend fun discardChanges(): Boolean = gate.withLock {
        when (recovery.discard(docId)) {
            is RemovalResult.Removed -> true
            else -> false
        }
    }
}
