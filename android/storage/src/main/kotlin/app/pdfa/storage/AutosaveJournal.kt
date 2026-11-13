package app.pdfa.storage

import java.io.File
import java.io.IOException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Why an autosave did not complete. [AutosaveEvent.Failed.detail] is an exception class name, never a message.
 * [RECORD_NOT_COMMITTED]: the new generation file is in place, but the record still names the previous one. The
 * previous autosave stays current, and the new file is an orphan that sweep removes.
 */
enum class AutosaveFailure { NO_SPACE, WRITE_FAILED, RECORD_NOT_COMMITTED, TOO_LARGE, PROVIDER_FAILED, DOCUMENT_UNAVAILABLE }

/** Result of one autosave attempt, delivered on [AutosaveJournal.events] and [AutosaveJournal.lastEvent]. */
sealed interface AutosaveEvent {
    val docId: String

    data class Saved(override val docId: String, val size: Long, val savedAtEpochMs: Long, val sha256: String) : AutosaveEvent

    data class Failed(override val docId: String, val reason: AutosaveFailure, val detail: String) : AutosaveEvent
}

/**
 * Per-document autosave into `work/<docId>/`. Each save writes a new generation file `autosave.<gen>.pdf`, then the
 * record `autosave.json`, then removes generations the record no longer names. Commit sequence, with the hook
 * name that fires after each step:
 *
 * 1. write `autosave.tmp`, fsync                 hook `gen-temp-written`
 * 2. rename it to `autosave.<gen>.pdf`           hook `gen-renamed`
 * 3. write `autosave.tmp` (the record), fsync    hook `record-temp-written`
 * 4. rename it to `autosave.json`                hook `record-renamed`
 * 5. delete generations that neither slot names  hook `old-generation-deleted`
 *
 * The record names a current and a previous generation, so the previous autosave survives until the next save.
 * A crash before step 4 leaves the old record and its files; the new generation is an orphan. A crash after step 4
 * leaves the new record and its files; any leftover older generation is an orphan. Orphans are never recovered.
 * Sweep removes them.
 *
 * - [onEdited] starts a debounce window ([debounceMs]). Only the last provider in a window runs.
 * - At most one save per document is in flight. A request that arrives during a save runs after it.
 * - A save that has started always finishes (NonCancellable). A debounced request that has not started is cancelled.
 * - Failures are never thrown to the caller of [onEdited]. They go to [events] and [lastEvent].
 *
 * Use one journal per process. The scope must use a supervisor job. [ioDispatcher] runs the blocking file work.
 */
class AutosaveJournal internal constructor(
    private val store: WorkspaceStore,
    private val scope: CoroutineScope,
    private val ioDispatcher: CoroutineDispatcher,
    private val clock: () -> Long,
    private val debounceMs: Long,
    private val maxBytes: Long,
    private val fs: StorageFs,
    private val hook: (String) -> Unit,
) {

    constructor(
        store: WorkspaceStore,
        scope: CoroutineScope,
        ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
        clock: () -> Long = System::currentTimeMillis,
        debounceMs: Long = DEFAULT_DEBOUNCE_MS,
    ) : this(store, scope, ioDispatcher, clock, debounceMs, MAX_DOCUMENT_BYTES, RealFs, {})

    private class Slot {
        val mutex = Mutex()
        /** Guarded by the slot monitor. */
        var pending: (suspend () -> ByteArray)? = null
        /** Guarded by the slot monitor. */
        var debounce: Job? = null
        /** Guarded by the slot monitor. */
        var last: AutosaveEvent? = null
    }

    private val registry = HashMap<String, Slot>()
    private val registryLock = Any()

    private val eventFlow = MutableSharedFlow<AutosaveEvent>(extraBufferCapacity = EVENT_BUFFER)

    /** Every finished attempt. Subscribers that come late miss earlier events; use [lastEvent] for the latest. */
    val events: SharedFlow<AutosaveEvent> = eventFlow.asSharedFlow()

    /**
     * Records that the document changed. [bytesProvider] runs after the debounce window, once, and only if it is
     * still the latest request. It must return the engine's current bytes (saveIncremental or saveFull).
     */
    fun onEdited(docId: String, bytesProvider: suspend () -> ByteArray) {
        require(isDocId(docId)) { "invalid document id" }
        val slot = slotFor(docId)
        synchronized(slot) {
            slot.pending = bytesProvider
            slot.debounce?.cancel()
            slot.debounce = scope.launch {
                delay(debounceMs)
                runPending(docId, slot)
            }
        }
    }

    /**
     * Writes the pending bytes now, without waiting for the debounce window (use on stop or background). Returns
     * the attempt's result, or null when nothing was pending. A save already in flight is awaited first.
     */
    suspend fun flush(docId: String): AutosaveEvent? {
        require(isDocId(docId)) { "invalid document id" }
        val slot = slotFor(docId)
        synchronized(slot) {
            slot.debounce?.cancel()
            slot.debounce = null
        }
        return runPending(docId, slot)
    }

    /** The latest attempt for [docId] in this process, or null when none ran. */
    fun lastEvent(docId: String): AutosaveEvent? {
        val slot = slotFor(docId)
        return synchronized(slot) { slot.last }
    }

    /** Cancels the debounced request and drops its bytes. A save already in flight still finishes. */
    internal fun dropPending(docId: String) {
        val slot = slotFor(docId)
        synchronized(slot) {
            slot.pending = null
            slot.debounce?.cancel()
            slot.debounce = null
        }
    }

    /** Runs [block] while no autosave for [docId] is in flight. Saves wait for it to finish. */
    internal suspend fun <T> withDocument(docId: String, block: suspend () -> T): T =
        slotFor(docId).mutex.withLock { block() }

    private suspend fun runPending(docId: String, slot: Slot): AutosaveEvent? {
        slot.mutex.withLock {
            val provider = synchronized(slot) {
                val taken = slot.pending
                slot.pending = null
                taken
            } ?: return null
            val event = withContext(NonCancellable) { persist(docId, provider) }
            synchronized(slot) { slot.last = event }
            eventFlow.tryEmit(event)
            return event
        }
    }

    private suspend fun persist(docId: String, provider: suspend () -> ByteArray): AutosaveEvent {
        val bytes = try {
            provider()
        } catch (e: Exception) {
            return failure(docId, AutosaveFailure.PROVIDER_FAILED, e)
        }
        if (bytes.size.toLong() > maxBytes) return failure(docId, AutosaveFailure.TOO_LARGE, null)
        return withContext(ioDispatcher) { writeGeneration(docId, bytes) }
    }

    private fun writeGeneration(docId: String, bytes: ByteArray): AutosaveEvent {
        val doc = try {
            store.load(docId)
        } catch (e: Exception) {
            return failure(docId, AutosaveFailure.DOCUMENT_UNAVAILABLE, e)
        }
        val dir = doc.dir
        val old = readRecord(dir)
        // Never reuse a number that a file already carries, even one an unreadable record left behind.
        val generation = maxOf(old?.current?.generation ?: 0L, generationNumbers(dir).maxOrNull() ?: 0L) + 1
        val tmp = File(dir, AutosaveLayout.TMP)

        try {
            commit(tmp, generationFile(dir, generation), bytes, "gen")
        } catch (e: Exception) {
            return failure(docId, classify(e), e)
        }

        val slot = AutosaveSlot(
            generation = generation,
            savedAtEpochMs = clock(),
            size = bytes.size.toLong(),
            sha256 = sha256Hex(bytes),
            sourceManifestSha256 = doc.manifest.sha256,
        )
        val record = AutosaveRecord(current = slot, previous = old?.current)
        try {
            commit(tmp, File(dir, AutosaveLayout.RECORD), record.toJson().toByteArray(Charsets.UTF_8), "record")
        } catch (e: Exception) {
            return failure(docId, AutosaveFailure.RECORD_NOT_COMMITTED, e)
        }

        // Only generations named by the new record stay. Failed deletes are left for sweep.
        val keep = setOfNotNull(slot.generation, record.previous?.generation)
        for (g in generationNumbers(dir)) {
            if (g !in keep) fs.delete(generationFile(dir, g))
        }
        hook("old-generation-deleted")
        return AutosaveEvent.Saved(docId, slot.size, slot.savedAtEpochMs, slot.sha256)
    }

    /**
     * Writes [bytes] to [tmp], fsyncs, renames to [target], and fires `<point>-temp-written` and `<point>-renamed`
     * after the two steps. A hook that throws simulates a crash and leaves the state as it is.
     */
    private fun commit(tmp: File, target: File, bytes: ByteArray, point: String) {
        try {
            fs.writeSynced(tmp, bytes)
            hook("$point-temp-written")
            fs.replace(tmp, target)
        } catch (e: Exception) {
            if (!fs.delete(tmp)) e.addSuppressed(IOException("temp file not removed"))
            throw e
        }
        hook("$point-renamed")
    }

    private fun classify(e: Exception): AutosaveFailure =
        if (e is IOException && e.message?.contains("No space left", ignoreCase = true) == true) {
            AutosaveFailure.NO_SPACE
        } else {
            AutosaveFailure.WRITE_FAILED
        }

    private fun failure(docId: String, reason: AutosaveFailure, e: Exception?): AutosaveEvent.Failed =
        AutosaveEvent.Failed(docId, reason, e?.javaClass?.simpleName ?: "")

    private fun slotFor(docId: String): Slot = synchronized(registryLock) {
        registry.getOrPut(docId) { Slot() }
    }

    companion object {
        const val DEFAULT_DEBOUNCE_MS: Long = 2_000L
        private const val EVENT_BUFFER = 64
    }
}
