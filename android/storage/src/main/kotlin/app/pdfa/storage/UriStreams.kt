package app.pdfa.storage

import java.io.InputStream

/**
 * Metadata of a SAF document. Providers may report a stale or missing [size] and [lastModified], so the
 * module never trusts them over the bytes it reads. [displayName] is informational.
 */
data class SourceStat(val size: Long, val lastModified: Long?, val displayName: String? = null)

/**
 * The only way this module touches `content://` URIs. The Android implementation is
 * [ContentResolverStreams]; tests use an in-memory fake.
 */
interface UriStreams {
    fun openInput(uri: String): InputStream

    /** Opens for writing and truncates first, like ContentResolver mode `"wt"`. */
    fun openOutputTruncating(uri: String): OutputStreamWithSync

    fun stat(uri: String): SourceStat

    /**
     * A key equal for two URIs that name the same document, or null when that cannot be determined.
     * SafWriter refuses a Save a copy only onto a known-same document; with a null key it allows the copy
     * and checks afterwards whether the source was the target. The default compares strings.
     */
    fun documentKey(uri: String): String? = uri
}

/** An output stream that can force its bytes to stable storage before it is closed. */
abstract class OutputStreamWithSync : java.io.OutputStream() {
    abstract fun sync()
}
