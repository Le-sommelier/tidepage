package app.pdfa.engine.fake

import java.nio.ByteBuffer
import kotlin.math.abs

/**
 * Reads only the pixel dimensions from an image header. The fake never decodes pixels, so
 * [FakeEngine.fakeImage] produces a PNG header with no image data.
 */
internal object FakeImages {
    private val PNG_SIGNATURE = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)

    /** Width and height in pixels, or null for an unsupported or truncated image. */
    fun dimensions(b: ByteArray): Pair<Int, Int>? {
        val size: Pair<Int, Int>? = when {
            startsWith(b, PNG_SIGNATURE) && b.size >= 24 && ascii(b, 12, 4) == "IHDR" ->
                be32(b, 16) to be32(b, 20)
            b.size >= 10 && ascii(b, 0, 4) == "GIF8" -> le16(b, 6) to le16(b, 8)
            b.size >= 26 && u8(b, 0) == 'B'.code && u8(b, 1) == 'M'.code ->
                abs(le32(b, 18)) to abs(le32(b, 22))
            b.size >= 4 && u8(b, 0) == 0xFF && u8(b, 1) == 0xD8 -> jpeg(b)
            else -> null
        }
        return size?.takeIf { it.first > 0 && it.second > 0 }
    }

    /** A PNG with a valid signature and IHDR chunk; pixel data is absent. */
    fun pngHeader(width: Int, height: Int): ByteArray =
        ByteBuffer.allocate(33)
            .put(PNG_SIGNATURE)
            .putInt(13)
            .put("IHDR".toByteArray(Charsets.US_ASCII))
            .putInt(width)
            .putInt(height)
            .put(byteArrayOf(8, 2, 0, 0, 0))
            .putInt(0)
            .array()

    private fun jpeg(b: ByteArray): Pair<Int, Int>? {
        var i = 2
        while (i + 3 < b.size) {
            if (u8(b, i) != 0xFF) return null
            val marker = u8(b, i + 1)
            when {
                marker == 0xFF -> i += 1
                marker == 0xD8 || marker == 0x01 || marker in 0xD0..0xD7 -> i += 2
                marker == 0xD9 -> return null
                marker in 0xC0..0xCF && marker != 0xC4 && marker != 0xC8 && marker != 0xCC -> {
                    return if (i + 8 < b.size) be16(b, i + 7) to be16(b, i + 5) else null
                }
                else -> {
                    val len = be16(b, i + 2)
                    if (len < 2) return null
                    i += 2 + len
                }
            }
        }
        return null
    }

    private fun startsWith(b: ByteArray, prefix: ByteArray): Boolean =
        b.size >= prefix.size && prefix.indices.all { b[it] == prefix[it] }

    private fun ascii(b: ByteArray, off: Int, len: Int): String =
        if (off + len > b.size) "" else String(b, off, len, Charsets.US_ASCII)

    private fun u8(b: ByteArray, i: Int): Int = b[i].toInt() and 0xFF

    private fun be16(b: ByteArray, i: Int): Int = (u8(b, i) shl 8) or u8(b, i + 1)

    private fun le16(b: ByteArray, i: Int): Int = u8(b, i) or (u8(b, i + 1) shl 8)

    private fun be32(b: ByteArray, i: Int): Int =
        (u8(b, i) shl 24) or (u8(b, i + 1) shl 16) or (u8(b, i + 2) shl 8) or u8(b, i + 3)

    private fun le32(b: ByteArray, i: Int): Int =
        u8(b, i) or (u8(b, i + 1) shl 8) or (u8(b, i + 2) shl 16) or (u8(b, i + 3) shl 24)
}
