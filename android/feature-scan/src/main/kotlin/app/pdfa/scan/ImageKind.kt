package app.pdfa.scan

/** What the first bytes of a file say it is. */
enum class ImageKind(val engineReadable: Boolean) {
    JPEG(true), PNG(true), GIF(true), BMP(true), TIFF(true),

    /** HEIC, HEIF, AVIF and WebP: the engine cannot read these. */
    HEIC(false), AVIF(false), WEBP(false),
    UNKNOWN(false),
    ;

    /** True when the file must be decoded and saved again as JPEG before the engine sees it. */
    val needsReencode: Boolean get() = !engineReadable

    companion object {
        private val HEIF_BRANDS = setOf("heic", "heix", "hevc", "hevx", "heim", "heis", "mif1", "msf1")
        private val AVIF_BRANDS = setOf("avif", "avis")

        /** Sniffs by magic bytes only; the file name and MIME type are never trusted. */
        fun sniff(b: ByteArray): ImageKind {
            fun u(i: Int) = b[i].toInt() and 0xFF
            fun ascii(off: Int, len: Int) = if (off + len > b.size) "" else String(b, off, len, Charsets.ISO_8859_1)
            return when {
                b.size >= 3 && u(0) == 0xFF && u(1) == 0xD8 && u(2) == 0xFF -> JPEG
                b.size >= 8 && u(0) == 0x89 && ascii(1, 3) == "PNG" && u(4) == 0x0D && u(5) == 0x0A && u(6) == 0x1A && u(7) == 0x0A -> PNG
                b.size >= 6 && (ascii(0, 6) == "GIF87a" || ascii(0, 6) == "GIF89a") -> GIF
                b.size >= 2 && ascii(0, 2) == "BM" -> BMP
                b.size >= 4 && ((u(0) == 0x49 && u(1) == 0x49 && u(2) == 0x2A && u(3) == 0) ||
                    (u(0) == 0x4D && u(1) == 0x4D && u(2) == 0 && u(3) == 0x2A)) -> TIFF
                b.size >= 12 && ascii(0, 4) == "RIFF" && ascii(8, 4) == "WEBP" -> WEBP
                b.size >= 12 && ascii(4, 4) == "ftyp" -> {
                    val brand = ascii(8, 4)
                    when {
                        brand in AVIF_BRANDS -> AVIF
                        brand in HEIF_BRANDS -> HEIC
                        else -> UNKNOWN
                    }
                }
                else -> UNKNOWN
            }
        }
    }
}
