package app.pdfa.viewer.extras

import java.util.Locale

/** Android constants, copied as plain values so the plan can be tested on the JVM. */
object IntentNames {
    const val ACTION_VIEW = "android.intent.action.VIEW"
    const val ACTION_SENDTO = "android.intent.action.SENDTO"
    const val ACTION_DIAL = "android.intent.action.DIAL"
    const val CATEGORY_BROWSABLE = "android.intent.category.BROWSABLE"

    /** Intent.FLAG_ACTIVITY_NEW_TASK. */
    const val FLAG_NEW_TASK = 0x10000000
}

/**
 * What an external open is allowed to look like. Pure data; the Android glue turns it into an
 * Intent and adds nothing else (no extras, no clip data, no grant flags).
 */
data class IntentPlan(
    val action: String,
    val data: String,
    val categories: Set<String> = emptySet(),
    /** Selector intent: only the browser handlers can match when this is set. */
    val selectorAction: String? = null,
    val selectorData: String? = null,
    val selectorCategories: Set<String> = emptySet(),
    val flags: Int = IntentNames.FLAG_NEW_TASK,
    val hasExtras: Boolean = false,
    val hasClipData: Boolean = false,
    val grantFlags: Int = 0,
)

object IntentPlanner {
    /** The plan for an accepted link, or null when the scheme has no plan. */
    fun planFor(link: ParsedLink): IntentPlan? = when (link.scheme) {
        "http", "https" -> IntentPlan(
            action = IntentNames.ACTION_VIEW,
            data = link.uri,
            categories = setOf(IntentNames.CATEGORY_BROWSABLE),
            selectorAction = IntentNames.ACTION_VIEW,
            selectorData = "http://",
            selectorCategories = setOf(IntentNames.CATEGORY_BROWSABLE),
        )
        // The dialer only, never ACTION_CALL. The data is the number with spaces removed.
        "tel" -> IntentPlan(
            action = IntentNames.ACTION_DIAL,
            data = "tel:" + link.display.replace(" ", ""),
        )
        "mailto" -> IntentPlan(action = IntentNames.ACTION_SENDTO, data = link.uri)
        else -> null
    }

    /**
     * True when the host Android re-parses from the URI is the host the user was shown.
     * Non-web links have no host to compare, so they always pass this check.
     */
    fun reparsedHostMatches(link: ParsedLink, reparsedHost: String?): Boolean {
        if (link.host == null) return true
        return reparsedHost != null && reparsedHost.lowercase(Locale.ROOT) == link.host
    }
}

/** What the confirmation dialog shows for a link. */
data class LinkDialogModel(
    /** Large, bold: the host (and non-default port), the mail address, or the phone number. */
    val headline: String,
    /** Small: the full URI, middle-truncated. The scheme and host part is never cut. */
    val fullUri: String,
)

object LinkDialogModels {
    const val MAX_URI_CHARS: Int = 160

    fun of(link: ParsedLink): LinkDialogModel = LinkDialogModel(
        headline = link.display,
        fullUri = middleTruncate(link),
    )

    /**
     * The URI, shortened in the middle when longer than [MAX_URI_CHARS]. For web links the
     * `scheme://authority` part is kept whole, so the host shown is always the full host.
     */
    internal fun middleTruncate(link: ParsedLink, max: Int = MAX_URI_CHARS): String {
        val uri = link.uri
        if (uri.length <= max) return uri
        val head = if (link.host != null) {
            val start = uri.indexOf("//") + 2
            val end = uri.indexOfAny(charArrayOf('/', '?', '#'), start).let { if (it < 0) uri.length else it }
            uri.substring(0, end)
        } else {
            ""
        }
        val rest = uri.substring(head.length)
        val budget = maxOf(0, max - head.length)
        val left = budget / 2
        val right = budget - left
        if (rest.length <= budget) return head + rest
        return head + rest.take(left) + "…" + rest.takeLast(right)
    }
}
