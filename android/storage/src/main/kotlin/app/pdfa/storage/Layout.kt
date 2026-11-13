package app.pdfa.storage

import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/**
 * On-disk layout of one document directory `work/<docId>/`. Every file name the module writes is
 * listed here.
 */
internal object Layout {
    const val ORIGINAL = "original.pdf"
    const val MANIFEST = "manifest.json"

    /** Full copy of the new bytes. Exists during a pending copy-back. */
    const val STAGING = "staging.pdf"

    /** New bytes awaiting verification. Promoted to [STAGING] only after verify and conflict pass. */
    const val STAGING_NEXT = "staging.next"

    /** Holds the target URI of a copy-back that started. Pending = this plus [STAGING]. */
    const val PENDING = "copy.pending"

    /** Staging for Save a copy. Separate from [STAGING]. */
    const val SAVE_COPY_STAGING = "save_copy.pdf"

    /** Reserved for the autosave journal (2 s debounce). Not written here. */
    const val AUTOSAVE_TMP = "autosave.tmp"

    /**
     * Writes [bytes] to `<target>.part`, fsyncs it, then renames it over [target]. A crash leaves
     * the old file or the new one, never a mix. A failed `.part` removal is recorded on the
     * exception; [sweepTmp] removes leftovers. Directory fsync is not possible on Android.
     */
    fun writeDurably(target: File, bytes: ByteArray, check: Check = NO_CHECK) {
        val part = File(target.path + ".part")
        try {
            FileOutputStream(part).use { out ->
                var off = 0
                while (off < bytes.size) {
                    check()
                    val n = minOf(COPY_BUFFER, bytes.size - off)
                    out.write(bytes, off, n)
                    off += n
                }
                out.flush()
                out.fd.sync()
            }
            Files.move(part.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } catch (e: Exception) {
            if (!part.delete() && part.exists()) e.addSuppressed(IOException("partial file not removed"))
            throw e
        }
    }

    fun readManifest(dir: File): Manifest =
        Manifest.fromJson(File(dir, MANIFEST).readText(Charsets.UTF_8))

    fun writeManifest(dir: File, manifest: Manifest) {
        writeDurably(File(dir, MANIFEST), manifest.toJson().toByteArray(Charsets.UTF_8))
    }

    /**
     * Marker body. Line 1 is the target URI. Line 2 is the SHA-256 of the source as observed right before
     * truncation, so a retry can tell an interrupted copy from an external edit.
     */
    fun markerBody(uri: String, observedSha: String): ByteArray =
        "$uri\n$observedSha".toByteArray(Charsets.UTF_8)

    fun markerUri(text: String): String = text.substringBefore('\n')

    fun markerObserved(text: String): String? =
        text.substringAfter('\n', "").takeIf { SHA256.matches(it) }

    private val SHA256 = Regex("[0-9a-f]{64}")
}
