package app.pdfa.scan

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ImageKindTest {
    private fun bytes(vararg v: Int) = ByteArray(v.size) { v[it].toByte() }
    private fun ascii(s: String) = s.toByteArray(Charsets.ISO_8859_1)
    private fun ftyp(brand: String) = bytes(0, 0, 0, 0x18) + ascii("ftyp$brand") + ByteArray(8)

    @Test
    fun passThroughFormats() {
        assertEquals(ImageKind.JPEG, ImageKind.sniff(bytes(0xFF, 0xD8, 0xFF, 0xE0, 0, 0x10)))
        assertEquals(ImageKind.PNG, ImageKind.sniff(bytes(0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A, 0)))
        assertEquals(ImageKind.GIF, ImageKind.sniff(ascii("GIF89a") + ByteArray(4)))
        assertEquals(ImageKind.GIF, ImageKind.sniff(ascii("GIF87a") + ByteArray(4)))
        assertEquals(ImageKind.BMP, ImageKind.sniff(ascii("BM") + ByteArray(30)))
        assertEquals(ImageKind.TIFF, ImageKind.sniff(bytes(0x49, 0x49, 0x2A, 0x00, 8)))
        assertEquals(ImageKind.TIFF, ImageKind.sniff(bytes(0x4D, 0x4D, 0x00, 0x2A, 8)))
        listOf(ImageKind.JPEG, ImageKind.PNG, ImageKind.GIF, ImageKind.BMP, ImageKind.TIFF).forEach {
            assertTrue(it.engineReadable)
            assertFalse(it.needsReencode)
        }
    }

    @Test
    fun heicHeifAndAvifNeedReencoding() {
        listOf("heic", "heix", "hevc", "mif1", "msf1").forEach { assertEquals(ImageKind.HEIC, ImageKind.sniff(ftyp(it)), it) }
        listOf("avif", "avis").forEach { assertEquals(ImageKind.AVIF, ImageKind.sniff(ftyp(it)), it) }
        assertTrue(ImageKind.HEIC.needsReencode)
        assertTrue(ImageKind.AVIF.needsReencode)
    }

    @Test
    fun webpNeedsReencoding() {
        val webp = ascii("RIFF") + ByteArray(4) + ascii("WEBP") + ByteArray(4)
        assertEquals(ImageKind.WEBP, ImageKind.sniff(webp))
        assertTrue(ImageKind.WEBP.needsReencode)
        // A WAV is RIFF too, but not an image.
        assertEquals(ImageKind.UNKNOWN, ImageKind.sniff(ascii("RIFF") + ByteArray(4) + ascii("WAVE") + ByteArray(4)))
    }

    @Test
    fun unknownTruncatedAndEmptyNeedReencoding() {
        assertEquals(ImageKind.UNKNOWN, ImageKind.sniff(ByteArray(0)))
        assertEquals(ImageKind.UNKNOWN, ImageKind.sniff(bytes(0xFF)))
        assertEquals(ImageKind.UNKNOWN, ImageKind.sniff(bytes(0xFF, 0xD8))) // JPEG needs three bytes
        assertEquals(ImageKind.UNKNOWN, ImageKind.sniff(ascii("%PDF-1.7")))
        assertEquals(ImageKind.UNKNOWN, ImageKind.sniff(ftyp("mp42"))) // a video container
        assertTrue(ImageKind.UNKNOWN.needsReencode)
    }
}
