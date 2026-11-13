package app.pdfa.app.intent

import android.content.Intent
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class IntentRouterTest {

    private val good = "content://com.example.provider/document/1"

    private fun view(data: String?, type: String? = "application/pdf") =
        IntentFacts(Intent.ACTION_VIEW, type, data, streams = null)

    private fun send(stream: String?, type: String?) =
        IntentFacts(Intent.ACTION_SEND, type, null, stream?.let { listOf(it) })

    private fun sendMultiple(streams: List<String>?, type: String?) =
        IntentFacts(Intent.ACTION_SEND_MULTIPLE, type, null, streams)

    // ---- VIEW ----

    @Test
    fun viewAcceptsContentPdf() {
        val request = IntentRouter.parse(view(good))
        assertEquals(OpenRequest(Kind.PDF, listOf(DocRef(good))), request)
    }

    @Test
    fun viewAcceptsMissingOrGenericMimeAsHintOnly() {
        assertEquals(Kind.PDF, IntentRouter.parse(view(good, type = null))?.kind)
        assertEquals(Kind.PDF, IntentRouter.parse(view(good, type = "")) ?.kind)
        assertEquals(Kind.PDF, IntentRouter.parse(view(good, type = "application/octet-stream"))?.kind)
    }

    @Test
    fun viewNormalisesMimeCaseAndParameters() {
        val request = IntentRouter.parse(view(good, type = "Application/PDF; charset=binary"))
        assertEquals(Kind.PDF, request?.kind)
    }

    @Test
    fun viewRejectsOtherDeclaredMime() {
        assertNull(IntentRouter.parse(view(good, type = "image/png")))
        assertNull(IntentRouter.parse(view(good, type = "text/plain")))
        assertNull(IntentRouter.parse(view(good, type = "application/x-pdf-ish")))
    }

    @Test
    fun viewRejectsNonContentSchemes() {
        listOf(
            "file:///storage/emulated/0/Download/a.pdf",
            "file://localhost/x/a.pdf",
            "http://example.com/a.pdf",
            "https://example.com/a.pdf",
            "android.resource://app.tidepagepdf/raw/a",
            "ftp://host/a.pdf",
            "javascript:alert(1)",
            "/storage/emulated/0/a.pdf",
            "a.pdf",
        ).forEach { assertNull(IntentRouter.parse(view(it)), it) }
    }

    @Test
    fun viewRejectsMalformedContentUris() {
        listOf(
            "content:/com.example/document/1", // one slash
            "content:com.example/document/1", // no slashes
            "content:///document/1", // empty authority
            "content://",
            "content://com.example", // no path
            "content://com.example/", // path is just a slash
            "content://user@com.example/document/1", // userinfo
            "content://com.example:8080/document/1", // port
            "content://com example/document/1", // space in authority
            "content://com.example/doc ument/1", // space in path
            "content://com.example/document/1\n", // trailing newline
            "content://com.example/doc\u0000ument", // NUL
            "content://com.example/documentø", // non-ASCII
            "CONTENT://com.example/document/1", // upper-case scheme
            "Content://com.example/document/1",
        ).forEach { raw -> assertNull(IntentRouter.parse(view(raw)), raw) }
    }

    @Test
    fun percentEncodedPathIsWellFormed() {
        val encoded = "content://com.example/a%0D%0Ab"
        assertEquals(DocRef(encoded), IntentRouter.acceptDocUri(encoded))
    }

    @Test
    fun viewRejectsTooLongUri() {
        val long = "content://com.example/" + "a".repeat(IntentRouter.MAX_URI_LENGTH)
        assertNull(IntentRouter.parse(view(long)))
        val exact = "content://com.example/" + "a".repeat(IntentRouter.MAX_URI_LENGTH - "content://com.example/".length)
        assertEquals(IntentRouter.MAX_URI_LENGTH, exact.length)
        assertEquals(Kind.PDF, IntentRouter.parse(view(exact))?.kind)
    }

    @Test
    fun viewRejectsMissingData() {
        assertNull(IntentRouter.parse(view(null)))
    }

    @Test
    fun viewIgnoresStreamExtra() {
        val facts = IntentFacts(Intent.ACTION_VIEW, "application/pdf", good, listOf("file:///x.pdf"))
        assertEquals(DocRef(good), IntentRouter.parse(facts)?.refs?.single())
    }

    // ---- SEND ----

    @Test
    fun sendPdfStreamOpensPdf() {
        val request = IntentRouter.parse(send(good, "application/pdf"))
        assertEquals(OpenRequest(Kind.PDF, listOf(DocRef(good))), request)
    }

    @Test
    fun sendImageStreamIsImagesKind() {
        val request = IntentRouter.parse(send(good, "image/jpeg"))
        assertEquals(Kind.IMAGES, request?.kind)
        assertEquals(listOf(DocRef(good)), request?.refs)
    }

    @Test
    fun sendMissingStreamIsRejected() {
        assertNull(IntentRouter.parse(send(null, "application/pdf")))
        assertNull(IntentRouter.parse(IntentFacts(Intent.ACTION_SEND, "application/pdf", null, null)))
        assertNull(IntentRouter.parse(IntentFacts(Intent.ACTION_SEND, "application/pdf", null, emptyList())))
    }

    @Test
    fun sendUnknownTypeIsRejected() {
        assertNull(IntentRouter.parse(send(good, "text/plain")))
        assertNull(IntentRouter.parse(send(good, "*/*")))
        assertNull(IntentRouter.parse(send(good, null)))
        assertNull(IntentRouter.parse(send(good, "")))
    }

    @Test
    fun sendFileSchemeIsRejected() {
        assertNull(IntentRouter.parse(send("file:///sdcard/a.pdf", "application/pdf")))
    }

    @Test
    fun sendIgnoresDataField() {
        val facts = IntentFacts(Intent.ACTION_SEND, "application/pdf", "file:///x", listOf(good))
        assertEquals(DocRef(good), IntentRouter.parse(facts)?.refs?.single())
    }

    @Test
    fun sendTakesOnlyFirstStream() {
        val second = "content://com.example.provider/document/2"
        val request = IntentRouter.parse(IntentFacts(Intent.ACTION_SEND, "application/pdf", null, listOf(good, second)))
        assertEquals(listOf(DocRef(good)), request?.refs)
    }

    // ---- SEND_MULTIPLE ----

    @Test
    fun sendMultiplePdfsKeepsAll() {
        val second = "content://com.example.provider/document/2"
        val request = IntentRouter.parse(sendMultiple(listOf(good, second), "application/pdf"))
        assertEquals(Kind.PDF, request?.kind)
        assertEquals(listOf(DocRef(good), DocRef(second)), request?.refs)
    }

    @Test
    fun sendMultipleDropsHostileItemsAndKeepsTheRest() {
        val request = IntentRouter.parse(
            sendMultiple(
                listOf("file:///x.pdf", good, "http://h/a.pdf", "content:/bad", ""),
                "application/pdf",
            ),
        )
        assertEquals(listOf(DocRef(good)), request?.refs)
    }

    @Test
    fun sendMultipleWithOnlyHostileItemsIsRejected() {
        assertNull(IntentRouter.parse(sendMultiple(listOf("file:///a", "http://b"), "application/pdf")))
    }

    @Test
    fun sendMultipleMissingOrEmptyIsRejected() {
        assertNull(IntentRouter.parse(sendMultiple(null, "application/pdf")))
        assertNull(IntentRouter.parse(sendMultiple(emptyList(), "application/pdf")))
    }

    @Test
    fun sendMultipleOverTheCapIsRejected() {
        val many = List(IntentRouter.MAX_STREAMS + 1) { "content://com.example.provider/document/$it" }
        assertNull(IntentRouter.parse(sendMultiple(many, "application/pdf")))
        val atCap = many.take(IntentRouter.MAX_STREAMS)
        assertEquals(IntentRouter.MAX_STREAMS, IntentRouter.parse(sendMultiple(atCap, "application/pdf"))?.refs?.size)
    }

    @Test
    fun sendMultipleImagesIsImagesKind() {
        val request = IntentRouter.parse(sendMultiple(listOf(good), "image/*"))
        assertEquals(Kind.IMAGES, request?.kind)
    }

    @Test
    fun sendMultipleUnknownOrMissingTypeIsRejected() {
        assertNull(IntentRouter.parse(sendMultiple(listOf(good), "text/plain")))
        assertNull(IntentRouter.parse(sendMultiple(listOf(good), null)))
    }

    // ---- other actions ----

    @Test
    fun otherActionsAreIgnored() {
        listOf(Intent.ACTION_MAIN, Intent.ACTION_EDIT, Intent.ACTION_GET_CONTENT, Intent.ACTION_OPEN_DOCUMENT, "", "x")
            .forEach { action ->
                assertNull(IntentRouter.parse(IntentFacts(action, "application/pdf", good, listOf(good))), action)
            }
        assertNull(IntentRouter.parse(IntentFacts(null, "application/pdf", good, listOf(good))))
    }

    // ---- helpers ----

    @Test
    fun acceptDocUriAcceptsWellFormedAndRejectsNull() {
        assertEquals(DocRef(good), IntentRouter.acceptDocUri(good))
        assertNull(IntentRouter.acceptDocUri(null))
        assertNull(IntentRouter.acceptDocUri(""))
    }

    @Test
    fun docRefHidesItsUriInToString() {
        assertEquals("DocRef(redacted)", DocRef(good).toString())
    }

    @Test
    fun openRequestNeedsAtLeastOneDocument() {
        assertFailsWith<IllegalArgumentException> { OpenRequest(Kind.PDF, emptyList()) }
    }

    @Test
    fun normaliseMimeHandlesBlankAndParameters() {
        assertNull(IntentRouter.normaliseMime(null))
        assertNull(IntentRouter.normaliseMime("   "))
        assertEquals("application/pdf", IntentRouter.normaliseMime(" APPLICATION/PDF ; x=y"))
    }
}
