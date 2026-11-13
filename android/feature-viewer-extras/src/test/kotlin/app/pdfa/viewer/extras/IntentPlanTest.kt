package app.pdfa.viewer.extras

import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class IntentPlanTest {

    private fun link(raw: String): ParsedLink =
        (ExternalLinkPolicy.check(raw) as? LinkCheck.Allowed)?.link ?: error("test link must be accepted: $raw")

    @Test
    fun `web plan views the URI as browsable with a browser-only selector and nothing else`() {
        val uri = "https://example.org/report?id=3"
        val plan = IntentPlanner.planFor(link(uri))!!

        assertEquals(IntentNames.ACTION_VIEW, plan.action)
        assertEquals(uri, plan.data)
        assertEquals(setOf(IntentNames.CATEGORY_BROWSABLE), plan.categories)
        assertEquals(IntentNames.ACTION_VIEW, plan.selectorAction)
        assertEquals("http://", plan.selectorData)
        assertEquals(setOf(IntentNames.CATEGORY_BROWSABLE), plan.selectorCategories)
        assertEquals(IntentNames.FLAG_NEW_TASK, plan.flags)
        assertFalse(plan.hasExtras)
        assertFalse(plan.hasClipData)
        assertEquals(0, plan.grantFlags)
    }

    @Test
    fun `http gets the same browser-only plan`() {
        val plan = IntentPlanner.planFor(link("http://example.org"))!!
        assertEquals(setOf(IntentNames.CATEGORY_BROWSABLE), plan.categories)
        assertEquals("http://", plan.selectorData)
    }

    @Test
    fun `new task is the only flag on every plan`() {
        listOf("https://a.com", "mailto:a@b.c", "tel:+123456").forEach { raw ->
            assertEquals(IntentNames.FLAG_NEW_TASK, IntentPlanner.planFor(link(raw))!!.flags, raw)
        }
    }

    @Test
    fun `tel uses the dialer only and never ACTION_CALL`() {
        val plan = IntentPlanner.planFor(link("tel:+33 1 23 45 67 89"))!!

        assertEquals(IntentNames.ACTION_DIAL, plan.action)
        assertNotEquals("android.intent.action.CALL", plan.action)
        assertEquals("tel:+33123456789", plan.data, "spaces are removed from the number")
        assertNull(plan.selectorAction)
        assertTrue(plan.categories.isEmpty())
    }

    @Test
    fun `mailto is a send-to with mailto data only and no selector`() {
        val plan = IntentPlanner.planFor(link("mailto:a@b.c?subject=Hi"))!!

        assertEquals(IntentNames.ACTION_SENDTO, plan.action)
        assertEquals("mailto:a@b.c?subject=Hi", plan.data)
        assertTrue(plan.data.startsWith("mailto:"))
        assertNull(plan.selectorAction)
        assertFalse(plan.hasExtras)
    }

    @Test
    fun `no plan exists for a scheme outside the allow-list`() {
        assertNull(IntentPlanner.planFor(ParsedLink("javascript", null, null, "x", "javascript:x")))
        assertNull(IntentPlanner.planFor(ParsedLink("sms", null, null, "x", "sms:x")))
    }

    @Test
    fun `the re-parsed host must match the displayed host`() {
        val l = link("https://example.org/x")
        assertTrue(IntentPlanner.reparsedHostMatches(l, "example.org"))
        assertTrue(IntentPlanner.reparsedHostMatches(l, "EXAMPLE.org"))
        assertFalse(IntentPlanner.reparsedHostMatches(l, "evil.com"))
        assertFalse(IntentPlanner.reparsedHostMatches(l, null))
    }

    @Test
    fun `non-web links have no host to compare`() {
        assertTrue(IntentPlanner.reparsedHostMatches(link("mailto:a@b.c"), null))
        assertTrue(IntentPlanner.reparsedHostMatches(link("tel:+123456"), null))
    }

    // ---- dialog model ----

    @Test
    fun `the dialog headline is the parsed host, never userinfo`() {
        val l = link("https://a.com/x")
        val model = LinkDialogModels.of(l)

        assertEquals("a.com", model.headline)
        assertEquals(l.host, model.headline)
        assertFalse(model.headline.contains("@"))
    }

    @Test
    fun `the dialog headline shows a non-default port`() {
        assertEquals("a.com:8443", LinkDialogModels.of(link("https://a.com:8443/x")).headline)
    }

    @Test
    fun `the dialog shows the number for a tel link`() {
        assertEquals("+1900123456", LinkDialogModels.of(link("tel:+1900123456")).headline)
    }

    @Test
    fun `the dialog shows the address for a mail link`() {
        assertEquals("a@b.c", LinkDialogModels.of(link("mailto:a@b.c?subject=Hi")).headline)
    }

    @Test
    fun `a long web link keeps its scheme and whole host and is cut in the middle`() {
        val long = "https://a.example.com/" + "p".repeat(400) + "/end-of-path"
        val model = LinkDialogModels.of(link(long))

        assertTrue(model.fullUri.startsWith("https://a.example.com"), model.fullUri)
        assertTrue(model.fullUri.contains("…"))
        assertTrue(model.fullUri.endsWith("end-of-path"))
        assertTrue(model.fullUri.length <= LinkDialogModels.MAX_URI_CHARS + 1 + "https://a.example.com".length)
    }

    @Test
    fun `a short link is shown whole`() {
        assertEquals("https://a.com/x", LinkDialogModels.of(link("https://a.com/x")).fullUri)
    }
}
