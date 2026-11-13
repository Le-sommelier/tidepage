package app.pdfa.storage

import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/**
 * Names inside `work/<docId>/` owned by the autosave journal. Generations are `autosave.<gen>.pdf`. The record is
 * [RECORD] and the temp file is [TMP] (the reserved name). None of these names is used by any other file.
 */
internal object AutosaveLayout {
    const val RECORD = "autosave.json"
    const val TMP = Layout.AUTOSAVE_TMP
    const val RECORD_VERSION = 2L
    const val MAX_RECORD_CHARS = 8192
    private val GENERATION = Regex("autosave\\.([0-9]{1,18})\\.pdf")

    fun generationName(generation: Long): String = "autosave.$generation.pdf"

    fun generationOf(name: String): Long? = GENERATION.matchEntire(name)?.groupValues?.get(1)?.toLongOrNull()
}

internal val SHA256_HEX = Regex("[0-9a-f]{64}")
private val DOC_ID = Regex("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")

/** True for the directory names the workspace creates. Anything else under `work/` is ignored. */
internal fun isDocId(name: String): Boolean = DOC_ID.matches(name)

internal fun generationFile(dir: File, generation: Long): File = File(dir, AutosaveLayout.generationName(generation))

/** Generation numbers of every `autosave.<gen>.pdf` in [dir], in no particular order. */
internal fun generationNumbers(dir: File): List<Long> =
    dir.listFiles().orEmpty().filter { it.isFile }.mapNotNull { AutosaveLayout.generationOf(it.name) }

/**
 * File operations used by the autosave journal, the recovery code and the recents index. Tests replace
 * them to inject disk-full and rename failures.
 */
internal interface StorageFs {
    /** Creates or truncates [file], writes [bytes] and fsyncs it before returning. */
    fun writeSynced(file: File, bytes: ByteArray)

    /** Atomic rename that replaces [to]. */
    fun replace(from: File, to: File)

    /** Atomic rename that never replaces an existing [to]. Throws when [to] exists. */
    fun renameNoReplace(from: File, to: File)

    /** Deletes [file] if present. True when it is gone afterwards. */
    fun delete(file: File): Boolean
}

internal object RealFs : StorageFs {
    override fun writeSynced(file: File, bytes: ByteArray) {
        FileOutputStream(file).use { out ->
            out.write(bytes)
            out.flush()
            out.fd.sync()
        }
    }

    override fun replace(from: File, to: File) {
        Files.move(from.toPath(), to.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
    }

    override fun renameNoReplace(from: File, to: File) {
        Files.move(from.toPath(), to.toPath(), StandardCopyOption.ATOMIC_MOVE)
    }

    override fun delete(file: File): Boolean = !file.exists() || file.delete()
}

/**
 * Writes [bytes] to [tmp], fsyncs it, then renames it over [target]. Nothing reaches [target] unless the
 * temp file is complete. A failed attempt removes [tmp] and leaves [target] as it was.
 */
internal fun atomicReplace(tmp: File, target: File, bytes: ByteArray, fs: StorageFs) {
    try {
        fs.writeSynced(tmp, bytes)
        fs.replace(tmp, target)
    } catch (e: Exception) {
        if (!fs.delete(tmp)) e.addSuppressed(IOException("temp file not removed"))
        throw e
    }
}

/** Replaces every unpaired UTF-16 surrogate with U+FFFD. Provider names can carry them; the JSON codec rejects them. */
internal fun String.withoutUnpairedSurrogates(): String {
    val sb = StringBuilder(length)
    var i = 0
    while (i < length) {
        val c = this[i]
        if (Character.isHighSurrogate(c) && i + 1 < length && Character.isLowSurrogate(this[i + 1])) {
            sb.append(c).append(this[i + 1])
            i += 2
            continue
        }
        sb.append(if (Character.isSurrogate(c)) '�' else c)
        i++
    }
    return sb.toString()
}

/** One autosave generation as the record describes it. */
internal data class AutosaveSlot(
    val generation: Long,
    val savedAtEpochMs: Long,
    val size: Long,
    val sha256: String,
    /** Manifest hash of the source version the autosave was taken against. */
    val sourceManifestSha256: String,
)

/**
 * `autosave.json`: the current slot and, when there was one, the previous slot. Both name a generation file.
 * The record is replaced atomically, so a crash leaves the old record or the new one. The previous slot is what
 * a corrupt current generation falls back to.
 */
internal data class AutosaveRecord(val current: AutosaveSlot, val previous: AutosaveSlot?) {

    fun toJson(): String {
        val sb = StringBuilder("{\"version\":").append(AutosaveLayout.RECORD_VERSION).append(",\"current\":")
        sb.append(slotJson(current))
        previous?.let { sb.append(",\"previous\":").append(slotJson(it)) }
        return sb.append('}').toString()
    }

    companion object {
        /** Null for any malformed, oversized or out-of-range record. */
        fun parse(text: String): AutosaveRecord? {
            if (text.length > AutosaveLayout.MAX_RECORD_CHARS) return null
            val root = try {
                Json.decode(text, maxDepth = 2)
            } catch (e: IllegalArgumentException) {
                return null
            }
            if (root["version"] != AutosaveLayout.RECORD_VERSION) return null
            val current = (root["current"] as? Map<*, *>)?.let { slotFrom(it) } ?: return null
            val previous = when (val raw = root["previous"]) {
                null -> null
                is Map<*, *> -> slotFrom(raw) ?: return null
                else -> return null
            }
            return AutosaveRecord(current, previous)
        }

        private fun slotJson(s: AutosaveSlot): String = Json.encode(
            mapOf(
                "generation" to s.generation,
                "savedAtEpochMs" to s.savedAtEpochMs,
                "size" to s.size,
                "sha256" to s.sha256,
                "sourceManifestSha256" to s.sourceManifestSha256,
            ),
        )

        private fun slotFrom(m: Map<*, *>): AutosaveSlot? {
            val generation = m["generation"] as? Long ?: return null
            val savedAt = m["savedAtEpochMs"] as? Long ?: return null
            val size = m["size"] as? Long ?: return null
            val sha = m["sha256"] as? String ?: return null
            val source = m["sourceManifestSha256"] as? String ?: return null
            if (generation < 1 || savedAt < 0 || size < 0 || size > MAX_DOCUMENT_BYTES) return null
            if (!SHA256_HEX.matches(sha) || !SHA256_HEX.matches(source)) return null
            return AutosaveSlot(generation, savedAt, size, sha, source)
        }
    }
}

/** The parsed record, or null when it is missing, oversized or malformed. Never throws. */
internal fun readRecord(dir: File): AutosaveRecord? = try {
    val file = File(dir, AutosaveLayout.RECORD)
    if (file.isFile && file.length() <= AutosaveLayout.MAX_RECORD_CHARS) {
        AutosaveRecord.parse(file.readText(Charsets.UTF_8))
    } else {
        null
    }
} catch (e: IOException) {
    null
}

/** Null when the generation file matches [slot] (size and streamed SHA-256). Otherwise the reason. */
internal fun slotFault(dir: File, slot: AutosaveSlot): NotRecoverableReason? {
    val file = generationFile(dir, slot.generation)
    if (!file.isFile) return NotRecoverableReason.PDF_MISSING
    if (file.length() != slot.size) return NotRecoverableReason.PDF_SIZE_MISMATCH
    val actual = try {
        file.inputStream().use { hashStream(it, NO_CHECK, null) }
    } catch (e: IOException) {
        return NotRecoverableReason.PDF_UNREADABLE
    }
    return if (actual.count == slot.size && actual.sha == slot.sha256) null else NotRecoverableReason.PDF_HASH_MISMATCH
}

/** Outcome of choosing which autosave generation to recover. */
internal sealed interface AutosaveCheck {
    /** [fallback] is true when the current generation failed and the previous one verified. */
    class Valid(val slot: AutosaveSlot, val fallback: Boolean) : AutosaveCheck
    class Invalid(val reason: NotRecoverableReason) : AutosaveCheck
}

/**
 * Chooses the generation to recover: the current slot if it verifies, else the previous slot if that verifies.
 * Otherwise the reason is the current slot's fault (or the record's, when there is no record).
 */
internal fun checkAutosave(dir: File): AutosaveCheck {
    if (!File(dir, AutosaveLayout.RECORD).isFile) return AutosaveCheck.Invalid(NotRecoverableReason.RECORD_MISSING)
    val record = readRecord(dir) ?: return AutosaveCheck.Invalid(NotRecoverableReason.RECORD_INVALID)
    val currentFault = slotFault(dir, record.current) ?: return AutosaveCheck.Valid(record.current, false)
    val previous = record.previous
    if (previous != null && slotFault(dir, previous) == null) return AutosaveCheck.Valid(previous, true)
    return AutosaveCheck.Invalid(currentFault)
}

/** Reads the verified autosave bytes from [dir]. Throws [RecoveryException] when no generation verifies. */
internal fun readVerifiedAutosave(dir: File): ByteArray {
    val slot = when (val check = checkAutosave(dir)) {
        is AutosaveCheck.Invalid -> throw RecoveryException(check.reason)
        is AutosaveCheck.Valid -> check.slot
    }
    val bytes = try {
        generationFile(dir, slot.generation).readBytes()
    } catch (e: IOException) {
        throw RecoveryException(NotRecoverableReason.PDF_UNREADABLE)
    }
    if (bytes.size.toLong() != slot.size || sha256Hex(bytes) != slot.sha256) {
        throw RecoveryException(NotRecoverableReason.PDF_HASH_MISMATCH)
    }
    return bytes
}
