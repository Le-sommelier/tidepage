package app.pdfa.storage

import java.io.File
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.nio.file.Files

/**
 * One row of the recents index. [docKey] is `UriStreams.documentKey(uri)`, or the raw uri when that is null.
 * [thumbnailRef] is an opaque name for a thumbnail the app keeps. It is never a path.
 */
data class RecentEntry(
    val docKey: String,
    val uri: String,
    val displayName: String?,
    val lastOpenedAt: Long,
    val pageCount: Int?,
    val sizeBytes: Long?,
    val thumbnailRef: String?,
    val unavailable: Boolean,
)

/** Outcome of a mutation. [Failed] means memory was rolled back to match the file on disk. */
sealed interface RecentsResult {
    data object Written : RecentsResult
    data object Unchanged : RecentsResult
    data class Failed(val detail: String) : RecentsResult
}

/**
 * File-backed MRU index of opened documents, in `recents.json` under the storage root. At most [RecentsCodec.MAX_ENTRIES]
 * rows, one per document key, newest first.
 *
 * - Writes are atomic: temp file, fsync, rename. A failed write leaves the previous file and returns [RecentsResult.Failed].
 * - A file that cannot be parsed yields an empty list with [loadedCorrupt] set. The file is renamed to
 *   `recents.json.corrupt-<ms>` and never deleted. If the rename fails, writes are refused, so nothing is overwritten.
 * - All methods are synchronized. Construct it off the main thread: the constructor reads the file.
 * - Nothing is logged. Results carry fixed text only.
 */
class RecentsStore internal constructor(
    rootDir: File,
    private val streams: UriStreams,
    private val clock: () -> Long,
    private val fs: StorageFs,
) {

    constructor(rootDir: File, streams: UriStreams, clock: () -> Long = System::currentTimeMillis) :
        this(rootDir, streams, clock, RealFs)

    private val file = File(rootDir, FILE_NAME)
    private val part = File(rootDir, "$FILE_NAME.part")
    private val lock = Any()
    private var entries: List<RecentEntry> = emptyList()
    private var corrupt = false
    private var writesBlocked = false

    init {
        entries = load()
    }

    /** True when the file on disk could not be parsed and was moved aside when the store opened. */
    val loadedCorrupt: Boolean
        get() = synchronized(lock) { corrupt }

    /** Rows, most recently opened first. */
    fun list(): List<RecentEntry> = synchronized(lock) { entries }

    fun get(docKey: String): RecentEntry? = synchronized(lock) { entries.firstOrNull { it.docKey == docKey } }

    /**
     * Moves the document to the front, or inserts it. Dedupes on the document key, so two uris for one document
     * share a row. Reopening clears [RecentEntry.unavailable].
     */
    fun recordOpened(
        uri: String,
        displayName: String?,
        pageCount: Int? = null,
        sizeBytes: Long? = null,
        thumbnailRef: String? = null,
    ): RecentsResult = synchronized(lock) {
        val key = keyFor(uri)
        val entry = RecentEntry(
            docKey = key,
            uri = uri.withoutUnpairedSurrogates(),
            displayName = displayName?.withoutUnpairedSurrogates(),
            lastOpenedAt = clock(),
            pageCount = pageCount,
            sizeBytes = sizeBytes,
            thumbnailRef = thumbnailRef?.withoutUnpairedSurrogates(),
            unavailable = false,
        )
        commit(listOf(entry) + entries.filterNot { it.docKey == key })
    }

    fun remove(docKey: String): RecentsResult = synchronized(lock) {
        if (entries.none { it.docKey == docKey }) {
            RecentsResult.Unchanged
        } else {
            commit(entries.filterNot { it.docKey == docKey })
        }
    }

    fun clear(): RecentsResult = synchronized(lock) {
        if (entries.isEmpty()) RecentsResult.Unchanged else commit(emptyList())
    }

    /** Marks a row whose permission was revoked or whose file is gone. The row stays, flagged. */
    fun markUnavailable(docKey: String): RecentsResult = synchronized(lock) {
        val target = entries.firstOrNull { it.docKey == docKey }
        if (target == null || target.unavailable) {
            RecentsResult.Unchanged
        } else {
            commit(entries.map { if (it.docKey == docKey) it.copy(unavailable = true) else it })
        }
    }

    private fun keyFor(uri: String): String {
        val key = try {
            streams.documentKey(uri)
        } catch (e: Exception) {
            null
        }
        return (key ?: uri).withoutUnpairedSurrogates()
    }

    private fun commit(next: List<RecentEntry>): RecentsResult {
        if (writesBlocked) return RecentsResult.Failed("recents cannot be written")
        val capped = next.take(RecentsCodec.MAX_ENTRIES)
        val before = entries
        entries = capped
        return try {
            atomicReplace(part, file, RecentsCodec.encode(capped).toByteArray(Charsets.UTF_8), fs)
            RecentsResult.Written
        } catch (e: Exception) {
            entries = before
            RecentsResult.Failed("write failed: ${e.javaClass.simpleName}")
        }
    }

    private fun load(): List<RecentEntry> {
        if (!file.exists()) return emptyList()
        if (file.length() > RecentsCodec.MAX_FILE_BYTES) {
            moveCorruptAside()
            return emptyList()
        }
        val raw = try {
            Files.readAllBytes(file.toPath())
        } catch (e: IOException) {
            // Unreadable is not the same as corrupt. Keep the file and refuse writes so it is not overwritten.
            writesBlocked = true
            return emptyList()
        }
        RecentsCodec.decode(raw)?.let { return it }
        corrupt = true
        moveCorruptAside()
        return emptyList()
    }

    private fun moveCorruptAside() {
        corrupt = true
        val base = "$FILE_NAME.corrupt-${clock()}"
        var target = File(file.parentFile, base)
        var n = 1
        while (target.exists() && n < MAX_ASIDE_ATTEMPTS) {
            target = File(file.parentFile, "$base-$n")
            n++
        }
        try {
            fs.renameNoReplace(file, target)
        } catch (e: Exception) {
            writesBlocked = true
        }
    }

    companion object {
        const val FILE_NAME = "recents.json"
        private const val MAX_ASIDE_ATTEMPTS = 100
    }
}

/**
 * Codec for `recents.json`. Shaped as `{"version":1,"entries":{"0":{...},"1":{...}}}` so that [Json] (which has no
 * arrays) can parse it. Each row carries `order` (0 = newest). Decoding sorts on `order`, never on the key names.
 */
internal object RecentsCodec {
    const val VERSION = 1L
    const val MAX_ENTRIES = 50
    const val MAX_FILE_BYTES = 1L shl 20
    private const val MAX_TEXT = 4096
    private const val MAX_DEPTH = 3

    fun encode(list: List<RecentEntry>): String {
        val sb = StringBuilder("{\"version\":1,\"entries\":{")
        list.forEachIndexed { i, e ->
            if (i > 0) sb.append(',')
            sb.append('"').append(i).append("\":")
            sb.append(
                Json.encode(
                    mapOf(
                        "order" to i.toLong(),
                        "docKey" to e.docKey,
                        "uri" to e.uri,
                        "displayName" to e.displayName,
                        "lastOpenedAt" to e.lastOpenedAt,
                        "pageCount" to e.pageCount,
                        "sizeBytes" to e.sizeBytes,
                        "thumbnailRef" to e.thumbnailRef,
                        "unavailable" to e.unavailable,
                    ),
                ),
            )
        }
        return sb.append("}}").toString()
    }

    /** Null when the bytes are not a valid index. Duplicate keys are dropped, and rows beyond the cap too. */
    fun decode(bytes: ByteArray): List<RecentEntry>? {
        val text = try {
            Charsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes))
                .toString()
        } catch (e: Exception) {
            return null
        }
        val root = try {
            Json.decode(text, maxDepth = MAX_DEPTH)
        } catch (e: IllegalArgumentException) {
            return null
        }
        if (root["version"] != VERSION) return null
        val rows = root["entries"] as? Map<*, *> ?: return null
        // The map keys carry no meaning. Each row's "order" field (0 = newest) decides the sequence.
        val parsed = ArrayList<Triple<Long, String, RecentEntry>>(rows.size)
        for ((key, value) in rows) {
            val name = key as? String ?: return null
            val map = value as? Map<*, *> ?: return null
            val order = (map["order"] as? Long)?.takeIf { it >= 0 } ?: return null
            parsed += Triple(order, name, entryFrom(map) ?: return null)
        }
        val seen = HashSet<String>()
        return parsed.sortedWith(compareBy<Triple<Long, String, RecentEntry>> { it.first }.thenBy { it.second })
            .map { it.third }
            .filter { seen.add(it.docKey) }
            .take(MAX_ENTRIES)
    }

    private fun entryFrom(m: Map<*, *>): RecentEntry? {
        val docKey = requiredText(m["docKey"]) ?: return null
        val uri = requiredText(m["uri"]) ?: return null
        if (!isOptionalText(m["displayName"]) || !isOptionalText(m["thumbnailRef"])) return null
        val lastOpenedAt = (m["lastOpenedAt"] as? Long)?.takeIf { it >= 0 } ?: return null
        val pageCount = when (val v = m["pageCount"]) {
            null -> null
            is Long -> if (v in 0L..Int.MAX_VALUE.toLong()) v.toInt() else return null
            else -> return null
        }
        val sizeBytes = when (val v = m["sizeBytes"]) {
            null -> null
            is Long -> v.takeIf { it >= 0 } ?: return null
            else -> return null
        }
        val unavailable = when (val v = m["unavailable"]) {
            null -> false
            is Boolean -> v
            else -> return null
        }
        return RecentEntry(
            docKey = docKey,
            uri = uri,
            displayName = m["displayName"] as String?,
            lastOpenedAt = lastOpenedAt,
            pageCount = pageCount,
            sizeBytes = sizeBytes,
            thumbnailRef = m["thumbnailRef"] as String?,
            unavailable = unavailable,
        )
    }

    private fun requiredText(v: Any?): String? =
        (v as? String)?.takeIf { it.isNotEmpty() && it.length <= MAX_TEXT }

    /** A missing value is fine. A present value must be a string within the length cap. */
    private fun isOptionalText(v: Any?): Boolean = v == null || (v is String && v.length <= MAX_TEXT)
}
