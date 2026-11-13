package app.pdfa.storage

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class JsonAndManifestTest {

    @Test
    fun jsonRoundTripsQuotesBackslashesControlsAndUnicode() {
        val original = mapOf(
            "a" to "say \"hi\" \\ back",
            "b" to "tab\tnl\ncr\rctl\u0001 é 日本 🎉",
            "n" to null,
            "x" to 42L,
            "t" to true,
        )
        assertEquals(original, Json.decode(Json.encode(original)))
    }

    @Test
    fun jsonDecodesSurrogatePairEscapes() {
        val decoded = Json.decode("{\"s\":\"\\u00e9\\uD83D\\uDE00\\/\"}")
        assertEquals("é\uD83D\uDE00/", decoded["s"])
    }

    @Test
    fun jsonRejectsMalformedInput() {
        val bad = listOf(
            "", "{", "{\"a\":[1]}", "{\"a\":1.5}", "{\"a\":1} x", "{\"a\":\"\\q\"}", "{\"a\":1,}",
        )
        for (text in bad) {
            assertFailsWith<IllegalArgumentException>(text) { Json.decode(text) }
        }
    }

    @Test
    fun jsonRejectsBadUnicodeEscapes() {
        // Signs and short escapes must not pass through toIntOrNull(16).
        assertFailsWith<IllegalArgumentException> { Json.decode("{\"a\":\"\\u+abc\"}") }
        assertFailsWith<IllegalArgumentException> { Json.decode("{\"a\":\"\\u-123\"}") }
        assertFailsWith<IllegalArgumentException> { Json.decode("{\"a\":\"\\u12\"}") }
        assertFailsWith<IllegalArgumentException> { Json.decode("{\"a\":\"\\uzzzz\"}") }
    }

    @Test
    fun jsonRejectsUnpairedSurrogateEscapes() {
        assertFailsWith<IllegalArgumentException> { Json.decode("{\"a\":\"\\uD83D\"}") }
        assertFailsWith<IllegalArgumentException> { Json.decode("{\"a\":\"\\uDE00\"}") }
    }

    @Test
    fun jsonEncodeRejectsLoneSurrogatesInsteadOfMangling() {
        assertFailsWith<IllegalArgumentException> { Json.encode(mapOf("a" to "x\uD800y")) }
        assertFailsWith<IllegalArgumentException> { Json.encode(mapOf("a" to "x\uDC00")) }
    }

    @Test
    fun jsonRejectsDeepNesting() {
        val deep = "{\"a\":".repeat(40) + "1" + "}".repeat(40)
        assertFailsWith<IllegalArgumentException> { Json.decode(deep) }
    }

    @Test
    fun manifestRoundTripsUnicodeNameWithQuotes() {
        val m = Manifest(
            uri = "content://provider/doc/1?x=\"q\"",
            displayName = "Rapport \"final\" é 日本 🎉.pdf",
            size = 123_456L,
            lastModified = 1_700_000_000_123L,
            sha256 = "a".repeat(64),
        )
        assertEquals(m, Manifest.fromJson(m.toJson()))
    }

    @Test
    fun manifestRoundTripsNullDisplayNameAndNullLastModified() {
        val m = Manifest("content://x/y", null, 0L, null, "0123456789abcdef".repeat(4))
        assertEquals(m, Manifest.fromJson(m.toJson()))
    }

    @Test
    fun manifestRejectsBadFields() {
        val sha = "f".repeat(64)
        val good = Manifest("content://x", "n.pdf", 1L, 2L, sha).toJson()
        val cases = listOf(
            good.replace("\"version\":1", "\"version\":2"),
            good.replace("\"sha256\":\"$sha\"", "\"sha256\":\"XYZ\""),
            good.replace("\"size\":1", "\"size\":-1"),
            good.replace("\"uri\":\"content://x\"", "\"uri\":5"),
            good.replace("\"uri\":\"content://x\"", "\"uri\":{\"x\":1}"),
            "{}",
            "not json",
        )
        for (bad in cases) {
            val e = assertFailsWith<StorageException>(bad) { Manifest.fromJson(bad) }
            assertEquals(StorageError.ManifestInvalid("manifest is invalid"), e.error)
        }
    }

    @Test
    fun manifestRejectsNestedObjectsAndDeepInput() {
        val nested = "{\"version\":1,\"uri\":{\"deep\":{\"deeper\":1}}}"
        assertFailsWith<StorageException>(nested) { Manifest.fromJson(nested) }
        val deep = "{\"a\":".repeat(100) + "1" + "}".repeat(100)
        assertFailsWith<StorageException> { Manifest.fromJson(deep) }
    }

    @Test
    fun manifestRejectsOversizedText() {
        val huge = "{" + " ".repeat((1 shl 20) + 1) + "}"
        assertFailsWith<StorageException> { Manifest.fromJson(huge) }
    }
}
