package app.pdfa.app.intent

import android.content.Intent
import android.net.Uri
import android.os.Build

/**
 * A reference to a document the app may open. [uri] is a `content://` URI string, the only kind
 * accepted. It is what recents and saved state keep, never bytes and never a file path.
 */
data class DocRef(val uri: String) {
    override fun toString(): String = "DocRef(redacted)"
}

enum class Kind { PDF, IMAGES }

/** Validated input for the shell. [refs] is never empty. */
data class OpenRequest(
    val kind: Kind,
    val refs: List<DocRef>,
    /** Set only by the app itself (never from an intent): restore this working copy's autosave. */
    val restoreDocId: String? = null,
) {
    init {
        require(refs.isNotEmpty()) { "an open request needs at least one document" }
    }
}

/**
 * The fields of an incoming intent that matter, copied out of the Intent. Kept as plain values so
 * [IntentRouter.parse] runs on the JVM without Android stubs.
 *
 * @param streams the EXTRA_STREAM values as strings: null when the extra is absent or unreadable.
 */
data class IntentFacts(
    val action: String?,
    val type: String?,
    val data: String?,
    val streams: List<String>?,
)

/**
 * Decides whether an incoming intent asks the app to open something, and what. Pure, unit tested.
 *
 * Trust model: MainActivity is exported, so an explicit intent can reach it without the filters.
 * This router is the only guard. It accepts `content://` only and never reads the document: the
 * MIME type is a hint, and the real PDF check (`%PDF-`) happens in :storage.
 */
object IntentRouter {
    const val MAX_URI_LENGTH = 2048
    const val MAX_STREAMS = 50

    private const val PDF = "application/pdf"
    private const val OCTET = "application/octet-stream"
    private const val IMAGE_PREFIX = "image/"
    private val AUTHORITY = Regex("[A-Za-z0-9._-]{1,255}")

    /** Returns the request for [facts], or null when the intent must be ignored. */
    fun parse(facts: IntentFacts): OpenRequest? {
        val mime = normaliseMime(facts.type)
        return when (facts.action) {
            Intent.ACTION_VIEW -> parseView(facts.data, mime)
            Intent.ACTION_SEND -> parseStreams(facts.streams?.take(1), mime, single = true)
            Intent.ACTION_SEND_MULTIPLE -> parseStreams(facts.streams, mime, single = false)
            else -> null
        }
    }

    /** Reads the fields of [intent] and decides. Bad parcels or wrong extra types count as absent. */
    fun parse(intent: Intent): OpenRequest? {
        // Opened from the task history (Recents): the original intent is redelivered. Ignore it, so
        // the app shows Home instead of re-opening a document the user already closed.
        if ((intent.flags and Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY) != 0) return null
        return parse(
            IntentFacts(
                action = intent.action,
                type = intent.type,
                data = intent.dataString,
                streams = readStreams(intent),
            ),
        )
    }

    /**
     * Validates a single document URI (intent data, stream, or the picker result). Returns null
     * unless it is a `content://` URI with a non-empty authority and path and only printable ASCII
     * without whitespace or userinfo or port. Lower-case scheme only.
     */
    fun acceptDocUri(raw: String?): DocRef? {
        if (raw == null || raw.length > MAX_URI_LENGTH) return null
        if (raw.any { it < '!' || it > '~' }) return null
        if (!raw.startsWith("content://")) return null
        val rest = raw.removePrefix("content://")
        val slash = rest.indexOf('/')
        if (slash <= 0) return null // no authority, or no path
        val authority = rest.substring(0, slash)
        val path = rest.substring(slash)
        if (!AUTHORITY.matches(authority)) return null
        if (path.length < 2) return null
        return DocRef(raw)
    }

    private fun parseView(data: String?, mime: String?): OpenRequest? {
        // The intent filter only matches PDFs, but explicit intents are not filtered. A declared MIME
        // of another type is rejected. A missing or generic type is accepted: many file managers send
        // application/octet-stream for PDFs, and :storage checks the bytes anyway.
        if (mime != null && mime != PDF && mime != OCTET) return null
        val ref = acceptDocUri(data) ?: return null
        return OpenRequest(Kind.PDF, listOf(ref))
    }

    private fun parseStreams(streams: List<String>?, mime: String?, single: Boolean): OpenRequest? {
        if (streams.isNullOrEmpty()) return null
        if (!single && streams.size > MAX_STREAMS) return null
        val kind = when {
            mime == PDF -> Kind.PDF
            mime != null && mime.startsWith(IMAGE_PREFIX) -> Kind.IMAGES
            else -> return null
        }
        // Bad items are dropped, not fatal: a share of 3 files where one is hostile still opens 2.
        val refs = streams.mapNotNull { acceptDocUri(it) }
        if (refs.isEmpty()) return null
        return OpenRequest(kind, refs)
    }

    /** `Application/PDF; charset=x` becomes `application/pdf`. Blank becomes null. */
    internal fun normaliseMime(raw: String?): String? {
        val base = raw?.substringBefore(';')?.trim()?.lowercase()
        return if (base.isNullOrEmpty()) null else base
    }

    private fun readStreams(intent: Intent): List<String>? {
        val uris: List<Uri> = try {
            if (intent.action == Intent.ACTION_SEND_MULTIPLE) {
                readUriList(intent)
            } else {
                listOfNotNull(readSingleUri(intent))
            }
        } catch (e: Exception) {
            // BadParcelableException, ClassCastException and friends: treat the extra as absent.
            emptyList()
        }
        return if (uris.isEmpty()) null else uris.map { it.toString() }
    }

    private fun readSingleUri(intent: Intent): Uri? =
        if (Build.VERSION.SDK_INT >= 33) {
            intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableExtra(Intent.EXTRA_STREAM) as? Uri
        }

    private fun readUriList(intent: Intent): List<Uri> {
        val list: List<*>? = if (Build.VERSION.SDK_INT >= 33) {
            intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM, Uri::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableArrayListExtra<Uri>(Intent.EXTRA_STREAM)
        }
        return list.orEmpty().filterIsInstance<Uri>()
    }
}
