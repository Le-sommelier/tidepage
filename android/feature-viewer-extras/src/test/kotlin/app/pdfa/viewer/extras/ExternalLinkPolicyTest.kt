package app.pdfa.viewer.extras

import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.fail

/** Each case states the expected outcome: an accepted [ParsedLink] or an exact fixed reason. */
class ExternalLinkPolicyTest {

    private fun accept(raw: String): ParsedLink = when (val c = ExternalLinkPolicy.check(raw)) {
        is LinkCheck.Allowed -> c.link
        is LinkCheck.Rejected -> fail("expected accept for [$raw], got reject: ${c.reason}")
    }

    private fun reject(raw: String, reason: String) {
        assertEquals(LinkCheck.Rejected(reason), ExternalLinkPolicy.check(raw), "link [${raw.take(40)}]")
    }

    // ---- allowed schemes, basic shapes ----

    @Test fun `https with path query and fragment is accepted`() {
        val l = accept("https://example.org/report?id=3#top")
        assertEquals("https", l.scheme)
        assertEquals("example.org", l.host)
        assertNull(l.port)
        assertEquals("example.org", l.display)
    }

    @Test fun `http is accepted`() = assertEquals("example.org", accept("http://example.org").host)

    @Test fun `scheme and host are case-insensitive and the host is lowercased`() {
        val l = accept("HtTpS://Example.ORG/Path")
        assertEquals("https", l.scheme)
        assertEquals("example.org", l.host)
        assertEquals("example.org", l.display)
    }

    @Test fun `HTTP with a single-label host is accepted with host x`() {
        val l = accept("HTTP://X")
        assertEquals("http", l.scheme)
        assertEquals("x", l.host)
    }

    @Test fun `leading and trailing spaces are trimmed`() {
        assertEquals("https://example.org", accept("  https://example.org  ").uri)
    }

    // ---- userinfo, backslash, percent, IPv6 ----

    @Test fun `userinfo in an http link is rejected`() = reject("https://a.com@evil.com", LinkReasons.USERINFO)

    @Test fun `a backslash before the at sign is rejected as a backslash`() =
        reject("https://a.com\\@evil.com", LinkReasons.BACKSLASH)

    @Test fun `a percent-encoded slash before userinfo is rejected`() =
        reject("https://evil.com%2f@a", LinkReasons.USERINFO)

    @Test fun `an IPv6 literal is rejected`() = reject("http://[::1]/", LinkReasons.IPV6)

    @Test fun `a percent sign in the host is rejected`() = reject("https://a%20b.com", LinkReasons.PERCENT)

    // ---- punycode and host shape ----

    @Test fun `a punycode host is rejected`() = reject("https://xn--pple-43d.com", LinkReasons.IDN)

    @Test fun `a punycode label after the first one is rejected`() = reject("https://a.xn--pple-43d.com", LinkReasons.IDN)

    @Test fun `an underscore in the host is rejected`() = reject("https://a_b.com", LinkReasons.HOST_CHARS)

    @Test fun `a trailing dot in the host is rejected as an empty label`() =
        reject("https://example.com.", LinkReasons.EMPTY_LABEL)

    @Test fun `a double dot in the host is rejected as an empty label`() = reject("https://a..b", LinkReasons.EMPTY_LABEL)

    @Test fun `a host of exactly 253 characters is accepted`() {
        val host = "a".repeat(63) + "." + "b".repeat(63) + "." + "c".repeat(63) + "." + "d".repeat(61)
        assertEquals(253, host.length)
        assertEquals(host, accept("https://$host").host)
    }

    @Test fun `a host of 254 characters is rejected`() {
        val host = "a".repeat(63) + "." + "b".repeat(63) + "." + "c".repeat(63) + "." + "d".repeat(62)
        assertEquals(254, host.length)
        reject("https://$host", LinkReasons.HOST_TOO_LONG)
    }

    @Test fun `a label of 63 characters is accepted and 64 is rejected`() {
        accept("https://${"a".repeat(63)}.com")
        reject("https://${"a".repeat(64)}.com", LinkReasons.LABEL_TOO_LONG)
    }

    @Test fun `an empty host is rejected`() = reject("https://:443", LinkReasons.NO_HOST)

    // ---- IPv4 ----

    @Test fun `a plain dotted-decimal IPv4 host is accepted`() = assertEquals("1.2.3.4", accept("https://1.2.3.4").display)

    @Test fun `an IPv4 octet over 255 is rejected`() = reject("https://256.1.1.1", LinkReasons.IPV4)

    @Test fun `an IPv4 with a leading zero is rejected`() = reject("https://01.2.3.4", LinkReasons.IPV4)

    @Test fun `a hex IPv4 form is rejected`() = reject("https://0x7f.0.0.1", LinkReasons.IPV4)

    @Test fun `a single all-digit label is rejected as an invalid IPv4`() = reject("https://1234", LinkReasons.IPV4)

    @Test fun `a three-part numeric host is rejected`() = reject("https://1.2.3", LinkReasons.IPV4)

    // ---- authority, port ----

    @Test fun `a missing authority after the scheme is rejected`() {
        reject("https:/evil", LinkReasons.NO_HOST)
        reject("https:evil", LinkReasons.NO_HOST)
        reject("https:///evil", LinkReasons.NO_HOST)
        reject("http://?x", LinkReasons.NO_HOST)
    }

    @Test fun `port 0 and port 65536 are rejected`() {
        reject("https://a.com:0", LinkReasons.PORT)
        reject("https://a.com:65536", LinkReasons.PORT)
    }

    @Test fun `port 443 on https is accepted and not shown`() {
        val l = accept("https://a.com:443")
        assertEquals(443, l.port)
        assertEquals("a.com", l.display)
    }

    @Test fun `a non-default port is accepted and shown`() {
        val l = accept("https://a.com:8443")
        assertEquals(8443, l.port)
        assertEquals("a.com:8443", l.display)
    }

    @Test fun `port 1 and port 65535 are accepted`() {
        accept("https://a.com:1")
        accept("https://a.com:65535")
    }

    @Test fun `an empty port and a non-digit port are rejected`() {
        reject("https://a.com:", LinkReasons.PORT)
        reject("https://a.com:12ab", LinkReasons.PORT)
        reject("https://a.com:80:81", LinkReasons.PORT)
    }

    @Test fun `path query and fragment are accepted`() { accept("https://a.com/path?x=1#frag") }

    // ---- scheme and character rejections ----

    @Test fun `javascript in every case is rejected as a scheme`() {
        reject("javascript:alert(1)", LinkReasons.SCHEME_NOT_ALLOWED)
        reject("JAVASCRIPT:alert(1)", LinkReasons.SCHEME_NOT_ALLOWED)
        reject("JaVaScRiPt:alert(1)", LinkReasons.SCHEME_NOT_ALLOWED)
    }

    @Test fun `a leading tab before javascript is rejected as a character problem`() =
        reject("\t javascript:alert(1)", LinkReasons.CHARACTERS)

    @Test fun `a newline inside the scheme is rejected as a character problem`() =
        reject("jav\nascript:", LinkReasons.CHARACTERS)

    @Test fun `a 2049 character link is rejected as too long`() {
        val raw = "https://a.com/" + "a".repeat(ExternalLinkPolicy.MAX_LENGTH + 1 - "https://a.com/".length)
        assertEquals(2049, raw.length)
        reject(raw, LinkReasons.TOO_LONG)
    }

    @Test fun `a 2048 character link is accepted`() {
        val raw = "https://a.com/" + "a".repeat(ExternalLinkPolicy.MAX_LENGTH - "https://a.com/".length)
        assertEquals(2048, accept(raw).uri.length)
    }

    @Test fun `a NUL byte is rejected`() = reject("https://a.com\u0000", LinkReasons.CHARACTERS)

    @Test fun `U+202E right-to-left override is rejected`() = reject("https://a.com‮", LinkReasons.CHARACTERS)

    @Test fun `a fullwidth h in the scheme is rejected`() = reject("ｈttps://a.com", LinkReasons.CHARACTERS)

    @Test fun `intent android-app market content and file schemes are rejected`() {
        reject("intent://x#Intent;end", LinkReasons.SCHEME_NOT_ALLOWED)
        reject("android-app://x", LinkReasons.SCHEME_NOT_ALLOWED)
        reject("market://x", LinkReasons.SCHEME_NOT_ALLOWED)
        reject("content://x", LinkReasons.SCHEME_NOT_ALLOWED)
        reject("file:///x", LinkReasons.SCHEME_NOT_ALLOWED)
    }

    @Test fun `data and sms schemes are rejected`() {
        reject("data:text/html,x", LinkReasons.SCHEME_NOT_ALLOWED)
        reject("sms:+1?body=x", LinkReasons.SCHEME_NOT_ALLOWED)
        reject("smsto:+15551234", LinkReasons.SCHEME_NOT_ALLOWED)
    }

    @Test fun `a link with no scheme is rejected`() = reject("example.org", LinkReasons.NO_SCHEME)

    @Test fun `empty and blank links are rejected`() {
        reject("", LinkReasons.EMPTY)
        reject("   ", LinkReasons.EMPTY)
    }

    @Test fun `a reason never echoes the scheme or the link`() {
        val reasons = listOf(
            "gopher://x", "file:///etc/passwd", "1javascript:x", "ab cd:x",
        ).map { ExternalLinkPolicy.check(it) }
            .map { (it as LinkCheck.Rejected).reason }
        reasons.forEach { r ->
            assertEquals(false, r.contains("gopher") || r.contains("passwd") || r.contains("javascript"))
        }
    }

    // ---- mailto ----

    @Test fun `mailto with a plain address is accepted and shows the address`() {
        val l = accept("mailto:someone@example.org")
        assertEquals("someone@example.org", l.display)
        assertNull(l.host)
    }

    @Test fun `mailto with a subject is accepted`() {
        assertEquals("a@b.c", accept("mailto:a@b.c?subject=Hi").display)
    }

    @Test fun `mailto subject with an encoded space is accepted`() {
        assertEquals("a@b.c", accept("mailto:a@b.c?subject=Hello%20there").display)
    }

    @Test fun `mailto subject with encoded control characters is rejected`() {
        for (code in listOf("%00", "%0A", "%0a", "%0D", "%09", "%1F", "%1f", "%7F", "%7f")) {
            reject("mailto:a@b.c?subject=Hi${code}Bcc:x@y.z", LinkReasons.MAIL_SUBJECT)
        }
    }

    @Test fun `mailto subject with a broken percent escape is rejected`() {
        reject("mailto:a@b.c?subject=100%", LinkReasons.MAIL_SUBJECT)
        reject("mailto:a@b.c?subject=%zz", LinkReasons.MAIL_SUBJECT)
        reject("mailto:a@b.c?subject=%+A", LinkReasons.MAIL_SUBJECT)
    }

    @Test fun `mailto with a body is rejected`() = reject("mailto:a@b?body=x", LinkReasons.MAIL_PARAM)

    @Test fun `mailto with attach, body and file uri is rejected`() =
        reject("mailto:a@b?body=x&attach=file:///x", LinkReasons.MAIL_PARAM)

    @Test fun `mailto with cc is rejected`() = reject("mailto:a@b?cc=x", LinkReasons.MAIL_PARAM)

    @Test fun `mailto with subject and another parameter is rejected`() =
        reject("mailto:a@b.c?subject=x&body=y", LinkReasons.MAIL_PARAM)

    @Test fun `mailto with an empty query is rejected`() = reject("mailto:a@b.c?", LinkReasons.MAIL_PARAM)

    @Test fun `mailto with no address is rejected`() = reject("mailto:", LinkReasons.ADDRESS_MISSING)

    @Test fun `mailto with two at signs is rejected`() = reject("mailto:a@@b.c", LinkReasons.MAIL_ADDRESS)

    @Test fun `mailto with a one-label domain is rejected`() = reject("mailto:a@b", LinkReasons.MAIL_ADDRESS)

    @Test fun `mailto with a space in the address is rejected as a character problem`() =
        reject("mailto:a b@c.d", LinkReasons.CHARACTERS)

    @Test fun `the mailto scheme is accepted in mixed case`() = assertEquals("a@b.c", accept("MailTo:a@b.c").display)

    // ---- tel ----

    @Test fun `a USSD code is rejected`() = reject("tel:*21*1#", LinkReasons.PHONE)

    @Test fun `a hash code is rejected`() = reject("tel:#06#", LinkReasons.PHONE)

    @Test fun `a premium-rate looking number is accepted and shown as the number`() {
        // The pattern does not judge the number. The dialog must show it so the user can see it.
        assertEquals("+1900123456", accept("tel:+1900123456").display)
    }

    @Test fun `an international number with spaces is accepted and shown`() {
        assertEquals("+33 1 23 45 67 89", accept("tel:+33 1 23 45 67 89").display)
    }

    @Test fun `tel in uppercase is accepted`() = assertEquals("+123", accept("TEL:+123").display)

    @Test fun `a number that is too short is rejected`() {
        reject("tel:12", LinkReasons.PHONE)
        reject("tel:", LinkReasons.PHONE)
    }

    @Test fun `allow-list is exactly http https mailto and tel`() {
        assertEquals(setOf("http", "https", "mailto", "tel"), ExternalLinkPolicy.ALLOWED_SCHEMES)
    }
}
