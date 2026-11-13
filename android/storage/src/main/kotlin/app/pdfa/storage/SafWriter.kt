package app.pdfa.storage

import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.UUID
import java.util.concurrent.Callable
import java.util.concurrent.ExecutionException
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

/**
 * Writes edited bytes back to a SAF document, or to a new target (Save a copy).
 *
 * **Pending** means `staging.pdf` exists AND `copy.pending` names this document's URI. The marker's second
 * line holds the source hash observed before truncation. While a copy is pending the source may be partial,
 * and `staging.pdf` is the only full copy.
 *
 * [saveBack] runs in three phases:
 * 1. Prepare (cancellable, nothing truncated): stage the new bytes to `staging.next`, run verify on a copy,
 *    hash the source. Unless the source is what this document last had (not pending) or is a state this
 *    writer produced (pending), return Conflict, or snapshot first when overwrite is requested. Promote
 *    `staging.next` to `staging.pdf`.
 * 2. Copy (NonCancellable): hash the source again and require it to equal the phase-1 hash; write the marker;
 *    truncate, copy, fsync and read back (under [copyTimeoutMs]); record the manifest; remove marker, then
 *    staging. Each removal is checked.
 * 3. Results carry `sourceMayBeTruncated` and `stagingKept`, so the UI never infers them.
 *
 * Calls on one document are serialised. The lock map is per instance, so use one SafWriter per process.
 * Memory: the input and one verify copy are held in memory; [maxInMemoryBytes] caps the input.
 */
class SafWriter internal constructor(
    private val dispatcher: CoroutineDispatcher,
    private val maxInMemoryBytes: Long,
    private val copyTimeoutMs: Long,
    private val fileOps: FileOps,
    private val hook: (String) -> Unit,
) {

    constructor(
        dispatcher: CoroutineDispatcher = Dispatchers.IO,
        maxInMemoryBytes: Long = MAX_DOCUMENT_BYTES,
        copyTimeoutMs: Long = DEFAULT_COPY_TIMEOUT_MS,
    ) : this(dispatcher, maxInMemoryBytes, copyTimeoutMs, DefaultFileOps, {})

    private val locks = KeyedLocks()

    suspend fun saveBack(
        doc: ImportedDoc,
        newBytes: ByteArray,
        streams: UriStreams,
        verify: (ByteArray) -> Boolean,
        overwriteIfSourceChanged: Boolean = false,
    ): SaveResult {
        if (newBytes.size > maxInMemoryBytes) {
            val maybePartial = pendingCopyStatus(doc) != PendingStatus.NotPending
            return SaveResult.TooLarge("document exceeds the in-memory save limit", maybePartial, maybePartial)
        }
        val caller = currentCoroutineContext()
        return locks.withKey(doc.docId) {
            when (val prep = withContext(dispatcher) {
                prepare(doc, newBytes, streams, verify, overwriteIfSourceChanged, caller)
            }) {
                is Prep.Done -> prep.result
                is Prep.Ready -> withContext(NonCancellable + dispatcher) { copyPhase(doc, prep, streams) }
            }
        }
    }

    /**
     * Save a copy: same verify, hash, read-back and timeout, but no conflict check.
     *
     * Refused only when [targetUri] is known to be the source document. When the identity of either URI is
     * unknown (non-document URIs from VIEW/SEND intents), the copy is allowed: it is written from a private
     * staged file, so even a target that is secretly the source ends up complete. That case is detected
     * afterwards (the source now hashes to the new bytes), recorded in the manifest and reported through
     * `Copied.replacedSource`, so the caller never claims the original is unchanged.
     */
    suspend fun saveCopyTo(
        doc: ImportedDoc,
        newBytes: ByteArray,
        targetUri: String,
        streams: UriStreams,
        verify: (ByteArray) -> Boolean,
    ): CopyResult {
        if (newBytes.size > maxInMemoryBytes) return CopyResult.TooLarge("document exceeds the in-memory save limit")
        val caller = currentCoroutineContext()
        return locks.withKey(doc.docId) {
            when (val prep = withContext(dispatcher) { prepareCopy(doc, newBytes, targetUri, streams, verify, caller) }) {
                is CopyPrep.Done -> prep.result
                is CopyPrep.Go -> withContext(NonCancellable + dispatcher) { copyToTarget(doc, prep, targetUri, streams, true) }
            }
        }
    }

    /** Pending state of [doc]. Blocking file checks, no lock. */
    fun pendingCopyStatus(doc: ImportedDoc): PendingStatus = pendingStatus(doc.dir, doc.manifest.uri)

    /**
     * Completes a pending copy. Without the in-memory bytes it uses `staging.pdf` and decides from the
     * source's state:
     * - source already equals staging: the copy is done; only the manifest and markers are updated.
     * - source is what the interrupted copy left (the marker's hash, or a prefix of staging): truncate and copy.
     * - anything else is an external edit: Conflict, nothing written. With [overwriteIfSourceChanged] the
     *   external version is snapshotted first.
     */
    suspend fun finishPendingCopy(
        doc: ImportedDoc,
        streams: UriStreams,
        overwriteIfSourceChanged: Boolean = false,
    ): SaveResult {
        val caller = currentCoroutineContext()
        return locks.withKey(doc.docId) {
            when (val prep = withContext(dispatcher) { prepareFinish(doc, streams, overwriteIfSourceChanged, caller) }) {
                is Prep.Done -> prep.result
                is Prep.Ready -> withContext(NonCancellable + dispatcher) { copyPhase(doc, prep, streams) }
            }
        }
    }

    /** Writes the full pending bytes (`staging.pdf`) to a user-chosen new target. Never discards staging. */
    suspend fun exportStaging(doc: ImportedDoc, streams: UriStreams, targetUri: String): CopyResult {
        val caller = currentCoroutineContext()
        return locks.withKey(doc.docId) {
            when (val prep = withContext(dispatcher) { prepareExport(doc, streams, targetUri, caller) }) {
                is CopyPrep.Done -> prep.result
                is CopyPrep.Go -> withContext(NonCancellable + dispatcher) { copyToTarget(doc, prep, targetUri, streams, false) }
            }
        }
    }

    internal fun lockCount(): Int = locks.size()

    // ---- phase 1: prepare (cancellable, nothing truncated) ----

    private fun prepare(
        doc: ImportedDoc,
        newBytes: ByteArray,
        streams: UriStreams,
        verify: (ByteArray) -> Boolean,
        overwrite: Boolean,
        caller: CoroutineContext,
    ): Prep {
        val check: Check = { caller.ensureActive() }
        val dir = doc.dir
        val manifest = attempt({ Layout.readManifest(dir) }) {
            return Prep.Done(SaveResult.Io("manifest unreadable", false, false))
        }
        val uri = manifest.uri
        val status = pendingStatus(dir, uri)
        if (status is PendingStatus.Unknown) return Prep.Done(SaveResult.Io(status.detail, true, true))
        val pending = status == PendingStatus.Pending
        val note = if (pending) "; previous copy still pending" else "; original untouched"
        val staging = File(dir, Layout.STAGING)
        val next = File(dir, Layout.STAGING_NEXT)
        val expected = SourceStat(manifest.size, manifest.lastModified, manifest.displayName)
        if (!pending) removeOrphans(dir)?.let { return Prep.Done(SaveResult.Io("$it$note", false, false)) }

        val sha = sha256Hex(newBytes, check)
        val staged = attempt({ Layout.writeDurably(next, newBytes, check); true }) { false }
        if (!staged) return failPrep(next, pending) { t, k -> SaveResult.Io("staging write failed$note", t, k) }
        hook("staged")

        // verify gets a private copy, so a mutating verify cannot change the staged or hashed bytes.
        if (!runVerify(verify, newBytes.copyOf())) {
            return failPrep(next, pending) { t, k -> SaveResult.VerificationFailed("verification failed$note", t, k) }
        }
        hook("verified")

        val source = attempt({ hashSource(uri, streams, check) }) {
            return failPrep(next, pending) { t, k -> SaveResult.Io("source unreadable$note", t, k) }
        }
        val ours = attempt({
            if (pending) {
                val observed = readMarkerObserved(dir)
                (observed != null && source.sha == observed) || isPrefixOf(uri, streams, staging, check)
            } else {
                source.sha == manifest.sha256
            }
        }) {
            return failPrep(next, pending) { t, k -> SaveResult.Io("source unreadable$note", t, k) }
        }
        var snapshot: String? = null
        if (!ours) {
            val detail = if (pending) "source changed while a copy was pending" else "source content changed since import"
            if (!overwrite) {
                return failPrep(next, pending) { t, k ->
                    SaveResult.Conflict(SourceStat(source.count, null), expected, detail, t, k)
                }
            }
            snapshot = attempt({ snapshotSource(uri, streams, dir, source.sha, check) }) {
                return failPrep(next, pending) { t, k -> SaveResult.Io("snapshot of changed source failed$note", t, k) }
            }
        }

        val promoted = attempt({ fileOps.move(next, staging); true }) { false }
        if (!promoted) return failPrep(next, pending) { t, k -> SaveResult.Io("staging promote failed$note", t, k) }
        hook("promoted")
        return Prep.Ready(manifest, sha, newBytes.size.toLong(), pending, snapshot, source.sha, expected, skipTruncate = false)
    }

    private fun prepareFinish(
        doc: ImportedDoc,
        streams: UriStreams,
        overwrite: Boolean,
        caller: CoroutineContext,
    ): Prep {
        val check: Check = { caller.ensureActive() }
        val dir = doc.dir
        val manifest = attempt({ Layout.readManifest(dir) }) {
            return Prep.Done(SaveResult.Io("manifest unreadable", false, false))
        }
        val status = pendingStatus(dir, manifest.uri)
        if (status is PendingStatus.Unknown) return Prep.Done(SaveResult.Io(status.detail, true, true))
        if (status != PendingStatus.Pending) return Prep.Done(SaveResult.Io("no pending copy", false, false))
        val uri = manifest.uri
        val staging = File(dir, Layout.STAGING)
        val stagingHash = attempt({ staging.inputStream().use { hashStream(it, check, null) } }) {
            return Prep.Done(SaveResult.CopyFailed(retryable = true, stagingKept = true, sourceMayBeTruncated = true, detail = "staging unreadable"))
        }
        val source = attempt({ hashSource(uri, streams, check) }) {
            return Prep.Done(SaveResult.CopyFailed(retryable = true, stagingKept = true, sourceMayBeTruncated = true, detail = "source unreadable"))
        }
        val expected = SourceStat(manifest.size, manifest.lastModified, manifest.displayName)
        if (source.sha == stagingHash.sha) {
            // The copy already landed; only the manifest and the markers remain.
            return Prep.Ready(manifest, stagingHash.sha, stagingHash.count, true, null, source.sha, expected, skipTruncate = true)
        }
        val ours = attempt({
            val observed = readMarkerObserved(dir)
            (observed != null && source.sha == observed) || isPrefixOf(uri, streams, staging, check)
        }) {
            return Prep.Done(SaveResult.CopyFailed(retryable = true, stagingKept = true, sourceMayBeTruncated = true, detail = "source unreadable"))
        }
        var snapshot: String? = null
        if (!ours) {
            if (!overwrite) {
                return Prep.Done(
                    SaveResult.Conflict(
                        SourceStat(source.count, null), expected,
                        "source changed while a copy was pending", sourceMayBeTruncated = true, stagingKept = true,
                    ),
                )
            }
            snapshot = attempt({ snapshotSource(uri, streams, dir, source.sha, check) }) {
                return Prep.Done(SaveResult.Io("snapshot of changed source failed", true, true))
            }
        }
        return Prep.Ready(manifest, stagingHash.sha, stagingHash.count, true, snapshot, source.sha, expected, skipTruncate = false)
    }

    private fun prepareCopy(
        doc: ImportedDoc,
        newBytes: ByteArray,
        targetUri: String,
        streams: UriStreams,
        verify: (ByteArray) -> Boolean,
        caller: CoroutineContext,
    ): CopyPrep {
        val check: Check = { caller.ensureActive() }
        val manifest = attempt({ Layout.readManifest(doc.dir) }) {
            return CopyPrep.Done(CopyResult.Io("manifest unreadable"))
        }
        val identity = identity(streams, targetUri, manifest.uri)
        if (identity == Identity.Same) {
            return CopyPrep.Done(CopyResult.Io("target is the source document; use saveBack"))
        }
        val staging = File(doc.dir, Layout.SAVE_COPY_STAGING)
        val sha = sha256Hex(newBytes, check)
        val staged = attempt({ Layout.writeDurably(staging, newBytes, check); true }) { false }
        if (!staged) return CopyPrep.Done(CopyResult.Io("staging write failed"))
        if (!runVerify(verify, newBytes.copyOf())) {
            discard(staging)
            return CopyPrep.Done(CopyResult.VerificationFailed("verification failed; target not written"))
        }
        if (identity == Identity.Unknown) {
            // The target may be the source under another URI. Remember what the source holds now, so the
            // copy phase can tell afterwards. An unreadable source is recorded as null.
            val before = attempt({ hashSource(manifest.uri, streams, check).sha }) { null }
            return CopyPrep.Go(staging, newBytes.size.toLong(), sha, AliasCheck(manifest, before))
        }
        return CopyPrep.Go(staging, newBytes.size.toLong(), sha)
    }

    private fun prepareExport(doc: ImportedDoc, streams: UriStreams, targetUri: String, caller: CoroutineContext): CopyPrep {
        val check: Check = { caller.ensureActive() }
        val manifest = attempt({ Layout.readManifest(doc.dir) }) {
            return CopyPrep.Done(CopyResult.Io("manifest unreadable"))
        }
        // An unknown identity is allowed: writing the full staged bytes onto the source itself is what a
        // finished copy-back does, and finishPendingCopy then finds the source already equal to staging.
        if (identity(streams, targetUri, manifest.uri) == Identity.Same) {
            return CopyPrep.Done(CopyResult.Io("target is the source document"))
        }
        val status = pendingStatus(doc.dir, manifest.uri)
        if (status is PendingStatus.Unknown) return CopyPrep.Done(CopyResult.Io(status.detail))
        if (status != PendingStatus.Pending) return CopyPrep.Done(CopyResult.Io("no pending copy"))
        val staging = File(doc.dir, Layout.STAGING)
        val hashed = attempt({ staging.inputStream().use { hashStream(it, check, null) } }) {
            return CopyPrep.Done(CopyResult.Io("staging unreadable"))
        }
        return CopyPrep.Go(staging, hashed.count, hashed.sha)
    }

    // ---- phase 2: copy (NonCancellable) ----

    private fun copyPhase(doc: ImportedDoc, r: Prep.Ready, streams: UriStreams): SaveResult {
        val dir = doc.dir
        val uri = r.manifest.uri
        val staging = File(dir, Layout.STAGING)
        val marker = File(dir, Layout.PENDING)
        val proof: Proof
        if (r.skipTruncate) {
            proof = Proof.Ok(attempt({ streams.stat(uri).lastModified }) { null }, null)
        } else {
            // Re-check right before truncation: the phase-1 decision is only valid if the source did not move.
            val again = attempt({ hashSource(uri, streams, NO_CHECK) }) {
                if (!r.pending) discard(staging)
                return SaveResult.Io("source unreadable before truncation", r.pending, r.pending)
            }
            if (again.sha != r.observed) {
                if (!r.pending) discard(staging)
                return SaveResult.Conflict(
                    SourceStat(again.count, null), r.expected, "source changed before truncation",
                    r.pending, r.pending,
                )
            }
            try {
                Layout.writeDurably(marker, Layout.markerBody(uri, r.observed))
            } catch (e: Exception) {
                if (!r.pending) discard(staging)
                return SaveResult.Io("marker write failed", r.pending, r.pending)
            }
            hook("marker")
            proof = copyWithTimeout(staging, uri, r.size, r.sha, streams)
        }
        return when (proof) {
            is Proof.Bad -> {
                proof.observedAfter?.let { after ->
                    attempt({ Layout.writeDurably(marker, Layout.markerBody(uri, after)) }) { }
                }
                SaveResult.CopyFailed(retryable = true, stagingKept = true, sourceMayBeTruncated = true, detail = proof.detail)
            }
            is Proof.Unverifiable -> SaveResult.Unverifiable(proof.detail, sourceMayBeTruncated = true, stagingKept = true)
            is Proof.Ok -> finalize(doc, r, proof)
        }
    }

    /** After a verified copy: manifest first, then marker, then staging. */
    private fun finalize(doc: ImportedDoc, r: Prep.Ready, p: Proof.Ok): SaveResult {
        val dir = doc.dir
        val marker = File(dir, Layout.PENDING)
        val staging = File(dir, Layout.STAGING)
        val updated = r.manifest.copy(size = r.size, lastModified = p.lastModified, sha256 = r.sha)
        try {
            hook("manifest")
            Layout.writeManifest(dir, updated)
        } catch (e: Exception) {
            return SaveResult.SavedButManifestStale(
                ImportedDoc(doc.docId, dir, updated),
                "manifest write failed; source verified, finish to record",
            )
        }
        hook("manifest-written")
        if (!discard(marker)) {
            return SaveResult.CopyFailed(
                retryable = true, stagingKept = true, sourceMayBeTruncated = false,
                detail = "pending marker could not be removed; source verified",
            )
        }
        hook("marker-cleared")
        val staleStaging = if (discard(staging)) null else "staging file could not be removed"
        hook("staging-cleared")
        val warning = listOfNotNull(p.notes, staleStaging).joinToString("; ").ifEmpty { null }
        return SaveResult.Saved(ImportedDoc(doc.docId, dir, updated), r.snapshot, warning)
    }

    private fun copyToTarget(
        doc: ImportedDoc,
        prep: CopyPrep.Go,
        targetUri: String,
        streams: UriStreams,
        discardStaging: Boolean,
    ): CopyResult =
        when (val p = copyWithTimeout(prep.staging, targetUri, prep.size, prep.sha, streams)) {
            is Proof.Bad -> CopyResult.CopyFailed(retryable = true, stagingKept = true, targetMayBeTruncated = true, detail = p.detail)
            is Proof.Unverifiable -> CopyResult.Unverifiable(p.detail, targetMayBeTruncated = true, stagingKept = true)
            is Proof.Ok -> {
                val replaced = prep.alias?.let { sourceReplacedBy(doc, it, prep, streams) }
                val staleStaging = if (discardStaging && !discard(prep.staging)) "staging file could not be removed" else null
                val warning = listOfNotNull(p.notes, staleStaging, replaced?.second).joinToString("; ").ifEmpty { null }
                CopyResult.Copied(prep.size, prep.sha, warning, replaced?.first)
            }
        }

    /**
     * After a verified copy to a target of unknown identity: if the source now holds the new bytes and did
     * not before, the target was the source. Returns the document with its updated manifest (and a note
     * when the manifest could not be written), or null when the source is untouched or cannot be read.
     * While a copy-back is pending the manifest is left alone: the pending state owns it.
     */
    private fun sourceReplacedBy(doc: ImportedDoc, alias: AliasCheck, prep: CopyPrep.Go, streams: UriStreams): Pair<ImportedDoc, String?>? {
        if (alias.sourceBefore == prep.sha) return null
        val uri = alias.manifest.uri
        val after = attempt({ hashSource(uri, streams, NO_CHECK).sha }) { null }
        if (after != prep.sha) return null
        val updated = alias.manifest.copy(
            size = prep.size,
            lastModified = attempt({ streams.stat(uri).lastModified }) { null },
            sha256 = prep.sha,
        )
        val note = if (pendingStatus(doc.dir, uri) != PendingStatus.NotPending) {
            null
        } else {
            attempt({ Layout.writeManifest(doc.dir, updated); null }) { "manifest write failed after replacing the source" }
        }
        return ImportedDoc(doc.docId, doc.dir, updated) to note
    }

    /**
     * Runs [copyAndProve] on a worker thread and waits at most [copyTimeoutMs]. On timeout the worker is
     * interrupted and the caller gets Bad (copy phase) or Unverifiable (read-back phase), with "timed out".
     * The worker may ignore the interrupt; the marker and staging keep the state recoverable, and a later
     * finish re-checks the source before writing again. Documented as a residual risk in the README.
     */
    private fun copyWithTimeout(source: File, uri: String, size: Long, sha: String, streams: UriStreams): Proof {
        val progress = Progress()
        if (copyTimeoutMs <= 0) return copyAndProve(source, uri, size, sha, streams, progress)
        val future = COPY_EXECUTOR.submit(Callable { copyAndProve(source, uri, size, sha, streams, progress) })
        return try {
            future.get(copyTimeoutMs, TimeUnit.MILLISECONDS)
        } catch (e: TimeoutException) {
            // Read the phase BEFORE cancelling: the interrupted worker may keep running (it can swallow the
            // interrupt in close()) and move on to the next phase, which would misreport where it hung.
            val phase = progress.phase
            future.cancel(true)
            if (phase == "readback") {
                Proof.Unverifiable("timed out during read-back", sourceMayBeTruncated = true, stagingKept = true)
            } else {
                Proof.Bad("timed out during $phase")
            }
        } catch (e: ExecutionException) {
            val cause = e.cause
            if (cause is Error) throw cause
            Proof.Bad("copy failed: ${cause?.javaClass?.simpleName ?: "unknown"}")
        } catch (e: InterruptedException) {
            future.cancel(true)
            Thread.currentThread().interrupt()
            Proof.Bad("interrupted during copy")
        }
    }

    /**
     * Truncates [uri], copies [source] into it, fsyncs, then reads it back and hashes it. Write errors stop
     * the copy. sync and close errors are recorded as notes (class names only), and the read-back decides.
     */
    private fun copyAndProve(source: File, uri: String, size: Long, sha: String, streams: UriStreams, progress: Progress): Proof {
        progress.phase = "open"
        val out = try {
            streams.openOutputTruncating(uri)
        } catch (e: Exception) {
            return Proof.Bad("open failed: ${name(e)}")
        }
        hook("truncated")
        progress.phase = "write"
        var writeError: String? = null
        try {
            source.inputStream().use { input ->
                val buffer = ByteArray(COPY_BUFFER)
                while (true) {
                    val n = input.read(buffer)
                    if (n < 0) break
                    out.write(buffer, 0, n)
                }
            }
            out.flush()
        } catch (e: Exception) {
            writeError = "write failed: ${name(e)}"
        }
        hook("copied")
        var notes: String? = null
        if (writeError == null) {
            progress.phase = "sync"
            try {
                out.sync()
            } catch (e: Exception) {
                notes = "unsynced: ${name(e)}"
            }
        }
        hook("synced")
        progress.phase = "close"
        try {
            out.close()
        } catch (e: Exception) {
            if (writeError == null) notes = listOfNotNull(notes, "close failed: ${name(e)}").joinToString("; ")
        }
        if (writeError != null) return Proof.Bad(writeError)

        progress.phase = "readback"
        val readBack = try {
            streams.openInput(uri).use { hashStream(it, NO_CHECK, null) }
        } catch (e: Exception) {
            return Proof.Unverifiable("read-back unavailable: ${name(e)}", sourceMayBeTruncated = true, stagingKept = true)
        }
        hook("readback")
        if (readBack.count != size || readBack.sha != sha) {
            // The source now holds exactly what was read back. Record that, so a retry treats it as ours.
            return Proof.Bad("mismatch after write: length or hash differs", observedAfter = readBack.sha)
        }
        // Provider metadata is advisory; the read-back bytes are the proof.
        val lastModified = attempt({ streams.stat(uri).lastModified }) { null }
        return Proof.Ok(lastModified, notes)
    }

    // ---- helpers ----

    private fun pendingStatus(dir: File, uri: String): PendingStatus = try {
        val marker = File(dir, Layout.PENDING)
        val staging = File(dir, Layout.STAGING)
        if (marker.exists() && !marker.isFile) {
            PendingStatus.Unknown("pending marker is not a file")
        } else {
            val named = if (marker.isFile) Layout.markerUri(marker.readText(Charsets.UTF_8)) else null
            if (staging.isFile && named == uri) PendingStatus.Pending else PendingStatus.NotPending
        }
    } catch (e: Exception) {
        PendingStatus.Unknown("pending state unreadable: ${name(e)}")
    }

    /** The source hash the interrupted copy recorded before truncation, or null if absent or unreadable. */
    private fun readMarkerObserved(dir: File): String? = try {
        val marker = File(dir, Layout.PENDING)
        if (marker.isFile) Layout.markerObserved(marker.readText(Charsets.UTF_8)) else null
    } catch (e: Exception) {
        null
    }

    /** Removes a marker or staging file left without its partner. Returns a detail on failure. */
    private fun removeOrphans(dir: File): String? {
        if (!discard(File(dir, Layout.PENDING))) return "cannot remove orphan pending marker"
        if (!discard(File(dir, Layout.STAGING))) return "cannot remove orphan staging file"
        return null
    }

    /** Deletes [file] if present. True when it is gone afterwards. */
    private fun discard(file: File): Boolean = !file.exists() || fileOps.delete(file)

    /** Discards staging.next and returns a Done with the caller's result. Pending flags follow the state. */
    private fun failPrep(next: File, pending: Boolean, make: (truncated: Boolean, kept: Boolean) -> SaveResult): Prep {
        val result = if (discard(next)) make(pending, pending) else SaveResult.Io("cannot remove staging.next", pending, pending)
        return Prep.Done(result)
    }

    private fun runVerify(verify: (ByteArray) -> Boolean, bytes: ByteArray): Boolean =
        attempt({ verify(bytes) }) { false }

    /** Whether two URIs name one document. Unknown when either key is null and the strings differ. */
    private fun identity(streams: UriStreams, a: String, b: String): Identity {
        if (a == b) return Identity.Same
        val ka = attempt({ streams.documentKey(a) }) { null }
        val kb = attempt({ streams.documentKey(b) }) { null }
        return when {
            ka == null || kb == null -> Identity.Unknown
            ka == kb -> Identity.Same
            else -> Identity.Different
        }
    }

    private enum class Identity { Same, Different, Unknown }

    /** Set when the target's identity is unknown: the manifest and the source hash before the copy (null if unreadable). */
    private class AliasCheck(val manifest: Manifest, val sourceBefore: String?)

    private fun hashSource(uri: String, streams: UriStreams, check: Check): Hashed =
        streams.openInput(uri).use { hashStream(it, check, null) }

    /** True when the source is a prefix of [staging]: the bytes an interrupted truncation-copy leaves. */
    private fun isPrefixOf(uri: String, streams: UriStreams, staging: File, check: Check): Boolean =
        streams.openInput(uri).use { src -> staging.inputStream().use { st -> prefixCheck(src, st, check) } }

    private fun prefixCheck(src: InputStream, st: InputStream, check: Check): Boolean {
        val a = ByteArray(COPY_BUFFER)
        val b = ByteArray(COPY_BUFFER)
        while (true) {
            check()
            val n = readFully(src, a, a.size)
            if (n == 0) return true
            if (readFully(st, b, n) < n) return false
            for (i in 0 until n) if (a[i] != b[i]) return false
        }
    }

    private fun readFully(input: InputStream, buf: ByteArray, len: Int): Int {
        var total = 0
        while (total < len) {
            val n = input.read(buf, total, len - total)
            if (n < 0) break
            total += n
        }
        return total
    }

    /**
     * Copies the external version of the source into the workspace, verified by hash. The name is unique
     * (time plus UUID) and the rename never replaces an existing file. A failed copy leaves no `.part`.
     */
    private fun snapshotSource(uri: String, streams: UriStreams, dir: File, expectedSha: String, check: Check): String {
        val fileName = "external-${System.currentTimeMillis()}-${UUID.randomUUID()}.pdf"
        val target = File(dir, fileName)
        val part = File(dir, "$fileName.part")
        try {
            FileOutputStream(part).use { out ->
                val h = streams.openInput(uri).use { hashStream(it, check, out) }
                out.flush()
                out.fd.sync()
                if (h.sha != expectedSha) throw IOException("source changed during snapshot")
            }
            if (target.exists()) throw IOException("snapshot name exists")
            fileOps.move(part, target)
        } catch (e: Exception) {
            if (!discard(part)) e.addSuppressed(IOException("snapshot part not removed"))
            throw e
        }
        return fileName
    }

    private fun name(e: Throwable): String = e.javaClass.simpleName

    private class Progress {
        @Volatile var phase: String = "open"
    }

    private sealed interface Prep {
        class Done(val result: SaveResult) : Prep
        class Ready(
            val manifest: Manifest,
            val sha: String,
            val size: Long,
            val pending: Boolean,
            val snapshot: String?,
            /** Source hash that phase 2 must still see right before truncation. */
            val observed: String,
            val expected: SourceStat,
            val skipTruncate: Boolean,
        ) : Prep
    }

    private sealed interface CopyPrep {
        class Done(val result: CopyResult) : CopyPrep
        class Go(val staging: File, val size: Long, val sha: String, val alias: AliasCheck? = null) : CopyPrep
    }

    private sealed interface Proof {
        class Ok(val lastModified: Long?, val notes: String?) : Proof
        /** [observedAfter]: the source hash read back after a failed write, when it could be read. */
        class Bad(val detail: String, val observedAfter: String? = null) : Proof
        class Unverifiable(val detail: String, val sourceMayBeTruncated: Boolean, val stagingKept: Boolean) : Proof
    }

    companion object {
        const val DEFAULT_MAX_IN_MEMORY_BYTES: Long = MAX_DOCUMENT_BYTES

        /** Generous default for one copy (write, sync, close, read-back). 0 disables the timeout. */
        const val DEFAULT_COPY_TIMEOUT_MS: Long = 5 * 60_000L

        private val COPY_EXECUTOR: ExecutorService = Executors.newCachedThreadPool { r ->
            Thread(r, "pdfa-storage-copy").apply { isDaemon = true }
        }
    }
}

/** File operations the writer relies on. Tests replace them to inject failures. */
internal interface FileOps {
    fun delete(file: File): Boolean
    fun move(from: File, to: File)
}

internal object DefaultFileOps : FileOps {
    override fun delete(file: File): Boolean = file.delete()

    override fun move(from: File, to: File) {
        Files.move(from.toPath(), to.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
    }
}

/**
 * Runs [block]. Any Exception goes to [onFail]. CancellationException is always rethrown, so cancellation is
 * never mistaken for an I/O failure. Errors (OutOfMemoryError, simulated crashes) are never caught.
 */
internal inline fun <T> attempt(block: () -> T, onFail: (Exception) -> T): T =
    try {
        block()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        onFail(e)
    }
