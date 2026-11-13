package app.pdfa.storage

import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.UUID
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

/** An imported document: id, its directory `work/<docId>/`, and its manifest. */
data class ImportedDoc(val docId: String, val dir: File, val manifest: Manifest) {
    val file: File get() = File(dir, Layout.ORIGINAL)
}

/**
 * App-private workspace. Layout: `rootDir/work/<docId>/{original.pdf, manifest.json, ...}` and
 * `rootDir/tmp/<docId>/` for imports in progress.
 */
class WorkspaceStore(
    private val rootDir: File,
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
) {

    private val workDir = File(rootDir, "work")
    private val tmpDir = File(rootDir, "tmp")

    /**
     * Copies the bytes behind [uri] into a new document directory. The copy is cut off at
     * [maxBytes], must start with `%PDF-` inside the first 1024 bytes, and must match the size
     * the provider reported. The declared MIME type is never consulted.
     *
     * Nothing appears under `work/` until the directory is complete; it is then renamed into place.
     * [beforeCommit] runs after the files are written and before the rename (crash-simulation seam).
     * Cancellation is honoured between chunks; no partial document is left behind.
     */
    suspend fun importFrom(
        uri: String,
        streams: UriStreams,
        maxBytes: Long = MAX_IMPORT_BYTES,
        beforeCommit: () -> Unit = {},
    ): ImportedDoc {
        val caller: CoroutineContext = currentCoroutineContext()
        val check: Check = { caller.ensureActive() }
        // Cancellation is checked only before the commit point (inside importBlocking). The rename and
        // the result are delivered even if the caller was cancelled meanwhile, so a finished document
        // is never left orphaned in work/ with its id lost.
        return withContext(NonCancellable + dispatcher) { importBlocking(uri, streams, maxBytes, beforeCommit, check) }
    }

    /** Re-opens an imported document from its manifest. */
    fun load(docId: String): ImportedDoc {
        if (!DOC_ID.matches(docId)) throw StorageException(StorageError.Io("invalid document id"))
        val dir = File(workDir, docId)
        val manifest = try {
            Layout.readManifest(dir)
        } catch (e: StorageException) {
            throw e
        } catch (e: Exception) {
            throw StorageException(StorageError.Io("manifest unreadable"))
        }
        return ImportedDoc(docId, dir, manifest)
    }

    /**
     * Removes import leftovers (`tmp/`) and temp files (`*.part`, `staging.next`) inside document
     * directories. Call once at startup. Returns the number of entries that could not be removed.
     *
     * Complete documents in `work/` are never reaped here: this module has no index of the document ids
     * the app still references, and a reap could delete the only copy a user can open. Deleting a
     * document belongs to the app, which owns the recents index.
     */
    fun sweepTmp(): Int {
        var failures = 0
        if (tmpDir.exists() && !tmpDir.deleteRecursively()) failures++
        workDir.listFiles()?.forEach { docDir ->
            docDir.listFiles()?.forEach { f ->
                val sweepable = f.name.endsWith(".part") || f.name == Layout.STAGING_NEXT
                if (sweepable && !f.delete()) failures++
            }
        }
        return failures
    }

    private fun importBlocking(
        uri: String,
        streams: UriStreams,
        maxBytes: Long,
        beforeCommit: () -> Unit,
        check: Check,
    ): ImportedDoc {
        val stat = guard("source unavailable") { streams.stat(uri) }
        if (stat.size > maxBytes) throw StorageException(StorageError.TooLarge("source exceeds import cap"))

        val docId = UUID.randomUUID().toString()
        val tmp = File(tmpDir, docId)
        if (!tmp.mkdirs() && !tmp.isDirectory) throw StorageException(StorageError.Io("workspace unavailable"))
        var committed = false
        try {
            val hashed = copyWithChecks(uri, streams, File(tmp, Layout.ORIGINAL), maxBytes, check)
            if (hashed.count != stat.size) {
                throw StorageException(StorageError.Io("source size differs from provider report"))
            }
            val manifest = Manifest(
                uri = uri,
                displayName = stat.displayName,
                size = hashed.count,
                lastModified = stat.lastModified,
                sha256 = hashed.sha,
            )
            Layout.writeManifest(tmp, manifest)
            beforeCommit()

            if (!workDir.mkdirs() && !workDir.isDirectory) {
                throw StorageException(StorageError.Io("workspace unavailable"))
            }
            Files.move(tmp.toPath(), File(workDir, docId).toPath(), StandardCopyOption.ATOMIC_MOVE)
            committed = true
            return ImportedDoc(docId, File(workDir, docId), manifest)
        } catch (e: StorageException) {
            throw e
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            throw StorageException(StorageError.Io("import failed: ${e.javaClass.simpleName}"))
        } finally {
            if (!committed) tmp.deleteRecursively()
        }
    }

    private fun copyWithChecks(
        uri: String,
        streams: UriStreams,
        target: File,
        maxBytes: Long,
        check: Check,
    ): Hashed {
        val md = MessageDigest.getInstance("SHA-256")
        return guard("read failed") {
            streams.openInput(uri).use { input ->
                val header = ByteArray(HEADER_BYTES)
                var filled = 0
                while (filled < HEADER_BYTES) {
                    check()
                    val n = input.read(header, filled, HEADER_BYTES - filled)
                    if (n < 0) break
                    filled += n
                }
                if (!hasMagic(header, filled)) {
                    throw StorageException(StorageError.NotPdf("no %PDF- header in first 1024 bytes"))
                }
                if (filled > maxBytes) throw StorageException(StorageError.TooLarge("source exceeds import cap"))
                var total = filled.toLong()
                FileOutputStream(target).use { out ->
                    out.write(header, 0, filled)
                    md.update(header, 0, filled)
                    val buffer = ByteArray(COPY_BUFFER)
                    while (true) {
                        check()
                        val n = input.read(buffer)
                        if (n < 0) break
                        total += n
                        if (total > maxBytes) throw StorageException(StorageError.TooLarge("source exceeds import cap"))
                        out.write(buffer, 0, n)
                        md.update(buffer, 0, n)
                    }
                    out.flush()
                    out.fd.sync()
                }
                Hashed(total, hex(md.digest()))
            }
        }
    }

    private inline fun <T> guard(what: String, block: () -> T): T =
        try {
            block()
        } catch (e: StorageException) {
            throw e
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            throw StorageException(StorageError.Io(what))
        }

    companion object {
        const val MAX_IMPORT_BYTES: Long = MAX_DOCUMENT_BYTES
        const val HEADER_BYTES = 1024
        private val MAGIC = "%PDF-".toByteArray(Charsets.US_ASCII)
        private val DOC_ID = Regex("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")

        /** True when `%PDF-` starts at some offset `k` with `k + 5 <= length`. */
        internal fun hasMagic(header: ByteArray, length: Int): Boolean {
            for (k in 0..(length - MAGIC.size)) {
                var match = true
                for (j in MAGIC.indices) {
                    if (header[k + j] != MAGIC[j]) {
                        match = false
                        break
                    }
                }
                if (match) return true
            }
            return false
        }
    }
}
