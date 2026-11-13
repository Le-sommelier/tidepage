package app.pdfa.storage

/**
 * Per-document record in `manifest.json`. After a verified copy-back, [size], [lastModified] and
 * [sha256] describe the bytes now in the source. The conflict check compares the source's content
 * hash with [sha256].
 */
data class Manifest(
    val uri: String,
    val displayName: String?,
    val size: Long,
    val lastModified: Long?,
    val sha256: String,
) {
    /** Throws IllegalArgumentException if a string holds an unpaired surrogate. */
    fun toJson(): String = Json.encode(
        mapOf(
            "version" to VERSION,
            "uri" to uri,
            "displayName" to displayName,
            "size" to size,
            "lastModified" to lastModified,
            "sha256" to sha256,
        ),
    )

    companion object {
        const val VERSION = 1L
        private const val MAX_JSON_CHARS = 1 shl 20
        private val SHA256 = Regex("[0-9a-f]{64}")

        fun fromJson(text: String): Manifest {
            if (text.length > MAX_JSON_CHARS) throw invalid()
            // The manifest is flat: nested objects are rejected.
            val m = try {
                Json.decode(text, maxDepth = 1)
            } catch (e: IllegalArgumentException) {
                throw invalid()
            }
            if (m["version"] != VERSION) throw invalid()
            val uri = m["uri"] as? String ?: throw invalid()
            val displayName = when (val v = m["displayName"]) {
                null -> null
                is String -> v
                else -> throw invalid()
            }
            val size = m["size"] as? Long ?: throw invalid()
            if (size < 0) throw invalid()
            val lastModified = when (val v = m["lastModified"]) {
                null -> null
                is Long -> v
                else -> throw invalid()
            }
            val sha = m["sha256"] as? String ?: throw invalid()
            if (!SHA256.matches(sha)) throw invalid()
            return Manifest(uri, displayName, size, lastModified, sha)
        }

        private fun invalid() = StorageException(StorageError.ManifestInvalid("manifest is invalid"))
    }
}
