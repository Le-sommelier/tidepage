package app.pdfa.viewer.extras

import java.util.Locale

/**
 * A link that passed [ExternalLinkPolicy]. Built by the policy only.
 *
 * @property scheme lowercase: `http`, `https`, `mailto` or `tel`.
 * @property host lowercase ASCII host (web links only), else null.
 * @property port explicit port (web links only), else null.
 * @property display what the dialog shows large: `host` or `host:port` (non-default port only),
 *   the mail address, or the phone number.
 * @property uri the full checked URI, ASCII, trimmed of spaces.
 */
data class ParsedLink(
    val scheme: String,
    val host: String?,
    val port: Int?,
    val display: String,
    val uri: String,
)

sealed interface LinkCheck {
    data class Allowed(val link: ParsedLink) : LinkCheck

    /** [reason] is one of the fixed [LinkReasons] strings; it never echoes the link. */
    data class Rejected(val reason: String) : LinkCheck
}

/** Fixed rejection reasons. They never contain text taken from the link. */
object LinkReasons {
    const val TOO_LONG = "The link is too long."
    const val EMPTY = "The link is empty."
    const val CHARACTERS = "The link contains spaces, control or non-ASCII characters."
    const val BACKSLASH = "The link contains a backslash."
    const val NO_SCHEME = "The link has no scheme."
    const val SCHEME_INVALID = "The link scheme is not valid."
    const val SCHEME_NOT_ALLOWED = "This kind of link cannot be opened."
    const val NO_HOST = "The web link has no host."
    const val USERINFO = "The web link contains user information before the host."
    const val PERCENT = "The web link host contains a percent sign."
    const val IPV6 = "IPv6 web links are not supported."
    const val HOST_CHARS = "The web link host contains characters that are not allowed."
    const val EMPTY_LABEL = "The web link host has an empty label."
    const val HOST_TOO_LONG = "The web link host is longer than 253 characters."
    const val LABEL_TOO_LONG = "A label in the web link host is longer than 63 characters."
    const val IDN = "Internationalised (xn--) web hosts are not supported."
    const val IPV4 = "The web link IPv4 address is not valid."
    const val PORT = "The web link port is not valid."
    const val MAIL_FORM = "Mail links must be mailto: with an address and at most a subject."
    const val MAIL_PARAM = "This mail link has a parameter that is not allowed."
    const val MAIL_SUBJECT = "The mail subject contains characters that are not allowed."
    const val MAIL_ADDRESS = "The mail address is not valid."
    const val PHONE = "The phone number is not valid."
    const val ADDRESS_MISSING = "The link has no address."
}

/**
 * The allow-list and parser for links from a PDF. Pure Kotlin, no Android types.
 *
 * Accepted kinds:
 *  - `http` and `https`: a host of ASCII letters, digits, hyphens and dots (lowercased), an
 *    optional port 1..65535, a plain hostname or a dotted-decimal IPv4 address. No user info,
 *    no IPv6, no `xn--` labels, no backslash, no percent sign in the authority.
 *  - `mailto`: `mailto:<address>` with an optional single `subject=` parameter. Any other
 *    parameter is rejected, not stripped.
 *  - `tel`: a plain number matching `^\+?[0-9 .()-]{3,20}$`. USSD codes such as `*21*1#` are rejected.
 *
 * A space is allowed only inside a tel number.
 * Everything else is rejected, including `javascript`, `file`, `content`, `intent`, `market`,
 * `android-app`, `data`, `sms` and `smsto`. Only spaces are trimmed from the ends. Any other
 * control character, a tab or a newline anywhere, or any non-ASCII character rejects the link.
 */
object ExternalLinkPolicy {
    const val MAX_LENGTH: Int = 2048

    val ALLOWED_SCHEMES: Set<String> = setOf("http", "https", "mailto", "tel")

    private val schemePattern = Regex("[A-Za-z][A-Za-z0-9+.-]*")
    private val hostPattern = Regex("[a-z0-9.-]+")
    private val digits = Regex("[0-9]+")
    private val octet = Regex("0|[1-9][0-9]{0,2}")
    private val telPattern = Regex("\\+?[0-9 .()-]{3,20}")
    private val mailLocal = Regex("[A-Za-z0-9._%+-]+")
    private val mailLabel = Regex("[A-Za-z0-9](?:[A-Za-z0-9-]{0,61}[A-Za-z0-9])?")

    fun check(raw: String): LinkCheck {
        if (raw.length > MAX_LENGTH) return reject(LinkReasons.TOO_LONG)
        val uri = raw.trim { it == ' ' }
        if (uri.isEmpty()) return reject(LinkReasons.EMPTY)
        if (uri.any { it.code < 0x20 || it.code > 0x7E }) return reject(LinkReasons.CHARACTERS)
        if (uri.indexOf('\\') >= 0) return reject(LinkReasons.BACKSLASH)
        val colon = uri.indexOf(':')
        if (colon <= 0) return reject(LinkReasons.NO_SCHEME)
        val rawScheme = uri.substring(0, colon)
        if (!schemePattern.matches(rawScheme)) return reject(LinkReasons.SCHEME_INVALID)
        val scheme = rawScheme.lowercase(Locale.ROOT)
        if (scheme !in ALLOWED_SCHEMES) return reject(LinkReasons.SCHEME_NOT_ALLOWED)
        // A space is only ever part of a phone number; the tel pattern checks it.
        if (scheme != "tel" && uri.indexOf(' ') >= 0) return reject(LinkReasons.CHARACTERS)
        val rest = uri.substring(colon + 1)
        return when (scheme) {
            "http", "https" -> webLink(scheme, uri, rest)
            "mailto" -> mailLink(uri, rest)
            else -> telLink(uri, rest)
        }
    }

    private fun reject(reason: String): LinkCheck = LinkCheck.Rejected(reason)

    private fun webLink(scheme: String, uri: String, rest: String): LinkCheck {
        if (!rest.startsWith("//")) return reject(LinkReasons.NO_HOST)
        val afterSlashes = rest.substring(2)
        val authEnd = afterSlashes.indexOfFirst { it == '/' || it == '?' || it == '#' }
            .let { if (it < 0) afterSlashes.length else it }
        val authority = afterSlashes.substring(0, authEnd)
        if (authority.isEmpty()) return reject(LinkReasons.NO_HOST)
        if (authority.indexOf('@') >= 0) return reject(LinkReasons.USERINFO)
        if (authority.indexOf('%') >= 0) return reject(LinkReasons.PERCENT)
        if (authority.indexOf('[') >= 0 || authority.indexOf(']') >= 0) return reject(LinkReasons.IPV6)

        val parts = authority.split(':')
        if (parts.size > 2) return reject(LinkReasons.PORT)
        var port: Int? = null
        if (parts.size == 2) {
            val p = parts[1]
            if (p.isEmpty() || p.length > 5 || !digits.matches(p)) return reject(LinkReasons.PORT)
            val n = p.toInt()
            if (n !in 1..65535) return reject(LinkReasons.PORT)
            port = n
        }
        val host = parts[0].lowercase(Locale.ROOT)
        hostProblem(host)?.let { return reject(it) }

        val defaultPort = if (scheme == "https") 443 else 80
        val display = if (port != null && port != defaultPort) "$host:$port" else host
        return LinkCheck.Allowed(ParsedLink(scheme, host, port, display, uri))
    }

    /** The first reason the host is not acceptable, or null. Input is already lowercase. */
    private fun hostProblem(host: String): String? {
        if (host.isEmpty()) return LinkReasons.NO_HOST
        if (host.length > 253) return LinkReasons.HOST_TOO_LONG
        if (!hostPattern.matches(host)) return LinkReasons.HOST_CHARS
        val labels = host.split('.')
        if (labels.any { it.isEmpty() }) return LinkReasons.EMPTY_LABEL
        if (labels.any { it.length > 63 }) return LinkReasons.LABEL_TOO_LONG
        if (labels.any { it.startsWith("xn--") }) return LinkReasons.IDN
        if (labels.any { it.startsWith("0x") }) return LinkReasons.IPV4
        if (labels.last().all { it in '0'..'9' }) {
            // A numeric last label means an IPv4 address: only plain dotted-decimal is accepted.
            if (labels.size != 4 || !labels.all { octet.matches(it) && it.toInt() <= 255 }) return LinkReasons.IPV4
        }
        return null
    }

    private fun mailLink(uri: String, rest: String): LinkCheck {
        val q = rest.indexOf('?')
        val address = if (q < 0) rest else rest.substring(0, q)
        if (q >= 0) {
            val params = rest.substring(q + 1).split('&')
            if (params.size != 1) return reject(LinkReasons.MAIL_PARAM)
            if (!params[0].startsWith("subject=")) return reject(LinkReasons.MAIL_PARAM)
            if (hasEncodedControl(params[0].removePrefix("subject="))) return reject(LinkReasons.MAIL_SUBJECT)
        }
        if (address.isEmpty()) return reject(LinkReasons.ADDRESS_MISSING)
        if (!isMailAddress(address)) return reject(LinkReasons.MAIL_ADDRESS)
        return LinkCheck.Allowed(ParsedLink("mailto", null, null, address, uri))
    }

    /**
     * True when [value] holds a percent-encoded control character (%00-%1F, %7F), for example an
     * encoded CR/LF that a mail app could turn into extra headers, or a `%` that is not followed by
     * two hex digits.
     */
    private fun hasEncodedControl(value: String): Boolean {
        var i = value.indexOf('%')
        while (i >= 0) {
            if (i + 2 >= value.length) return true
            val code = value.substring(i + 1, i + 3).toIntOrNull(16) ?: return true
            if (value[i + 1] == '+' || value[i + 1] == '-') return true
            if (code < 0x20 || code == 0x7F) return true
            i = value.indexOf('%', i + 3)
        }
        return false
    }

    private fun isMailAddress(address: String): Boolean {
        if (address.count { it == '@' } != 1) return false
        val at = address.indexOf('@')
        val local = address.substring(0, at)
        val domain = address.substring(at + 1)
        if (!mailLocal.matches(local)) return false
        val labels = domain.split('.')
        return labels.size >= 2 && labels.all { mailLabel.matches(it) }
    }

    private fun telLink(uri: String, rest: String): LinkCheck {
        if (!telPattern.matches(rest)) return reject(LinkReasons.PHONE)
        return LinkCheck.Allowed(ParsedLink("tel", null, null, rest, uri))
    }
}
