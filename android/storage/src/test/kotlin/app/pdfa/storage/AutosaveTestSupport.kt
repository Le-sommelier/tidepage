package app.pdfa.storage

import java.io.File
import java.io.IOException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import org.junit.Rule
import org.junit.rules.TemporaryFolder

/** A simulated process death. An Error, so no `catch (Exception)` in the module can swallow it. */
internal class AutosaveCrash(point: String) : Error("simulated crash at $point")

/**
 * [StorageFs] over the real file system with fault knobs. Knobs are read at call time.
 * [noSpaceOn] writes half the bytes and then throws, like a full disk. [failTmpWriteNumber] fails the n-th
 * write to `autosave.tmp` (1-based, counted over the whole test).
 */
internal class FaultFs(private val real: StorageFs = RealFs) : StorageFs {
    @Volatile var noSpaceOn: String? = null
    @Volatile var failWriteOn: String? = null
    @Volatile var failTmpWriteNumber: Int? = null
    @Volatile var failReplaceTo: String? = null
    @Volatile var failRenameNoReplace = false
    @Volatile var failDelete = false
    var tmpWrites = 0

    override fun writeSynced(file: File, bytes: ByteArray) {
        if (file.name == AutosaveLayout.TMP) {
            tmpWrites++
            if (tmpWrites == failTmpWriteNumber) throw IOException("injected write failure")
        }
        if (file.name == failWriteOn) throw IOException("injected write failure")
        if (file.name == noSpaceOn) {
            real.writeSynced(file, bytes.copyOf(bytes.size / 2))
            throw IOException("No space left on device")
        }
        real.writeSynced(file, bytes)
    }

    override fun replace(from: File, to: File) {
        if (to.name == failReplaceTo) throw IOException("injected rename failure")
        real.replace(from, to)
    }

    override fun renameNoReplace(from: File, to: File) {
        if (failRenameNoReplace) throw IOException("injected rename failure")
        real.renameNoReplace(from, to)
    }

    override fun delete(file: File): Boolean = if (failDelete) false else real.delete(file)
}

/** A byte array that starts with `%PDF-` and is deterministic for [seed]. */
internal fun pdfBytes(seed: Int, size: Int = 4096): ByteArray {
    val bytes = ByteArray(size) { ((it * 31 + seed * 7) % 200 + 32).toByte() }
    "%PDF-1.7".toByteArray(Charsets.US_ASCII).copyInto(bytes, 0)
    return bytes
}

/** A workspace under [root] (which holds `work/`), a fake provider, and a writer, all on [dispatcher]. */
internal class Ws(val root: File, val dispatcher: CoroutineDispatcher) {
    val streams = FakeUriStreams()
    val store = WorkspaceStore(root, dispatcher)
    val writer = SafWriter(dispatcher)

    suspend fun importDoc(uri: String, bytes: ByteArray): ImportedDoc {
        streams.put(uri, bytes, displayName = "report.pdf")
        return store.importFrom(uri, streams)
    }

    fun dirOf(docId: String): File = File(root, "work/$docId")
}

internal fun TestScope.ws(root: File): Ws = Ws(root, StandardTestDispatcher(testScheduler))

/** A journal on the test clock. Debounce is 2 s unless overridden. */
internal fun TestScope.journal(
    w: Ws,
    fs: StorageFs = RealFs,
    maxBytes: Long = app.pdfa.storage.MAX_DOCUMENT_BYTES,
    scope: CoroutineScope? = null,
    hook: (String) -> Unit = {},
): AutosaveJournal = AutosaveJournal(
    store = w.store,
    scope = scope ?: CoroutineScope(SupervisorJob() + w.dispatcher),
    ioDispatcher = w.dispatcher,
    clock = { testScheduler.currentTime },
    debounceMs = 2_000L,
    maxBytes = maxBytes,
    fs = fs,
    hook = hook,
)

/** Writes [bytes] as the autosave for [docId] and returns the result (flush, no debounce wait). */
internal suspend fun AutosaveJournal.save(docId: String, bytes: ByteArray): AutosaveEvent? {
    onEdited(docId) { bytes }
    return flush(docId)
}

/** The generation file the record currently names. A nonexistent file when there is no record. */
internal fun pdfOf(dir: File): File {
    val record = readRecord(dir) ?: return File(dir, "autosave.none.pdf")
    return generationFile(dir, record.current.generation)
}

/** Writes a generation file and a record that names it as current (no previous). */
internal fun writeCurrent(dir: File, generation: Long, bytes: ByteArray, sourceSha: String = "0".repeat(64)) {
    generationFile(dir, generation).writeBytes(bytes)
    val slot = AutosaveSlot(generation, 1L, bytes.size.toLong(), sha256Hex(bytes), sourceSha)
    File(dir, AutosaveLayout.RECORD).writeText(AutosaveRecord(slot, null).toJson())
}

internal fun TestScope.recoveryScanner(w: Ws): RecoveryScanner = RecoveryScanner(w.root, w.writer)

internal fun TestScope.recoveryActions(w: Ws, j: AutosaveJournal, fs: StorageFs = RealFs): RecoveryActions =
    RecoveryActions(
        rootDir = w.root,
        store = w.store,
        journal = j,
        pendingStatus = w.writer::pendingCopyStatus,
        clock = { testScheduler.currentTime },
        ioDispatcher = w.dispatcher,
        fs = fs,
    )

abstract class AutosaveTestBase {
    @get:Rule val tmp = TemporaryFolder()

    protected val uri = "content://provider/doc/42"

    protected fun root(): File = File(tmp.root, "ws")
}
