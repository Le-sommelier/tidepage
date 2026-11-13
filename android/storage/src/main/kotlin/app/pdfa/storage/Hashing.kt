package app.pdfa.storage

import java.io.InputStream
import java.io.OutputStream
import java.security.MessageDigest

/** Called before each chunk of blocking work. Throws CancellationException when the caller left. */
internal typealias Check = () -> Unit

internal val NO_CHECK: Check = {}

internal const val COPY_BUFFER = 64 * 1024

internal class Hashed(val count: Long, val sha: String)

/** Reads [input] to the end, hashing (and optionally copying to [sink]) chunk by chunk. */
internal fun hashStream(input: InputStream, check: Check, sink: OutputStream?): Hashed {
    val md = MessageDigest.getInstance("SHA-256")
    val buffer = ByteArray(COPY_BUFFER)
    var count = 0L
    while (true) {
        check()
        val n = input.read(buffer)
        if (n < 0) break
        md.update(buffer, 0, n)
        sink?.write(buffer, 0, n)
        count += n
    }
    return Hashed(count, hex(md.digest()))
}

internal fun sha256Hex(bytes: ByteArray, check: Check = NO_CHECK): String {
    val md = MessageDigest.getInstance("SHA-256")
    var off = 0
    while (off < bytes.size) {
        check()
        val n = minOf(COPY_BUFFER, bytes.size - off)
        md.update(bytes, off, n)
        off += n
    }
    return hex(md.digest())
}

internal fun hex(bytes: ByteArray): String {
    val out = StringBuilder(bytes.size * 2)
    for (b in bytes) {
        val v = b.toInt() and 0xff
        out.append(HEX_DIGITS[v ushr 4]).append(HEX_DIGITS[v and 0x0f])
    }
    return out.toString()
}

private const val HEX_DIGITS = "0123456789abcdef"
