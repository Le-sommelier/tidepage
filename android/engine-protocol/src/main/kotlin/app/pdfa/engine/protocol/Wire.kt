package app.pdfa.engine.protocol

import java.nio.charset.StandardCharsets

/** Every malformed, truncated, oversized or hostile input raises this and nothing else. */
class WireException(detail: String) : Exception(detail)

/** Hard caps. A length prefix above a cap is rejected before anything is allocated. */
object WireLimits {
    /** Largest byte blob (documents, images, pixels, request payloads): 512 MiB. */
    const val MAX_BLOB_BYTES: Int = 512 * 1024 * 1024

    /** Largest string, in UTF-8 bytes: 64 MiB. */
    const val MAX_STRING_BYTES: Int = 64 * 1024 * 1024

    /** Largest list: 1M items. */
    const val MAX_LIST_ITEMS: Int = 1_000_000

    /** The contract's input limit (256 MiB): the client refuses larger inputs with TooLarge. */
    const val MAX_INPUT_BYTES: Long = 256L * 1024 * 1024

    /** The contract's render limit (2^26 pixels). */
    const val MAX_RENDER_PIXELS: Long = 64L * 1024 * 1024
}

/**
 * Little-endian writer over a growing ByteArray. Blob writes reserve their exact size first, so a
 * large blob never makes the buffer double past what is needed.
 */
class WireWriter(initialCapacity: Int = 64) {
    private var buf = ByteArray(initialCapacity.coerceAtLeast(16))
    private var len = 0

    val size: Int get() = len

    private fun ensure(extra: Int) {
        val need = len.toLong() + extra
        if (need > Int.MAX_VALUE - 16) throw WireException("message too large")
        if (need > buf.size) {
            val grown = maxOf(need, minOf(buf.size.toLong() * 2, Int.MAX_VALUE - 16L))
            buf = buf.copyOf(grown.toInt())
        }
    }

    fun u8(v: Int): WireWriter {
        ensure(1)
        buf[len++] = v.toByte()
        return this
    }

    fun bool(v: Boolean): WireWriter = u8(if (v) 1 else 0)

    fun i32(v: Int): WireWriter {
        ensure(4)
        buf[len++] = v.toByte()
        buf[len++] = (v ushr 8).toByte()
        buf[len++] = (v ushr 16).toByte()
        buf[len++] = (v ushr 24).toByte()
        return this
    }

    fun i64(v: Long): WireWriter {
        ensure(8)
        for (i in 0 until 8) buf[len++] = (v ushr (8 * i)).toByte()
        return this
    }

    fun f32(v: Float): WireWriter = i32(java.lang.Float.floatToRawIntBits(v))

    fun f64(v: Double): WireWriter = i64(java.lang.Double.doubleToRawLongBits(v))

    /** Length-prefixed (i32) bytes. */
    fun bytes(v: ByteArray): WireWriter {
        if (v.size > WireLimits.MAX_BLOB_BYTES) throw WireException("blob of ${v.size} bytes exceeds the cap")
        ensure(4 + v.size)
        i32(v.size)
        System.arraycopy(v, 0, buf, len, v.size)
        len += v.size
        return this
    }

    /** Length-prefixed (i32 UTF-8 byte count) string. */
    fun string(v: String): WireWriter {
        val raw = v.toByteArray(StandardCharsets.UTF_8)
        if (raw.size > WireLimits.MAX_STRING_BYTES) throw WireException("string of ${raw.size} bytes exceeds the cap")
        ensure(4 + raw.size)
        i32(raw.size)
        System.arraycopy(raw, 0, buf, len, raw.size)
        len += raw.size
        return this
    }

    fun count(n: Int): WireWriter {
        if (n < 0 || n > WireLimits.MAX_LIST_ITEMS) throw WireException("list of $n items exceeds the cap")
        return i32(n)
    }

    /** Presence byte, then the value when non-null. */
    inline fun <T : Any> optional(v: T?, write: WireWriter.(T) -> Unit): WireWriter {
        if (v == null) {
            u8(0)
        } else {
            u8(1)
            write(v)
        }
        return this
    }

    inline fun <T> list(v: List<T>, write: WireWriter.(T) -> Unit): WireWriter {
        count(v.size)
        for (item in v) write(item)
        return this
    }

    fun intList(v: List<Int>): WireWriter {
        count(v.size)
        for (x in v) i32(x)
        return this
    }

    fun toByteArray(): ByteArray = buf.copyOf(len)
}

/**
 * Little-endian bounds-checked reader. Every read checks the remaining bytes first. A length prefix
 * is validated against both its cap and the bytes actually left BEFORE any allocation, so a
 * hostile 0x7FFFFFFF prefix costs nothing.
 */
class WireReader(private val data: ByteArray, start: Int = 0, private val end: Int = data.size) {
    private var pos = start

    init {
        if (start < 0 || end > data.size || start > end) throw WireException("bad reader window")
    }

    val remaining: Int get() = end - pos

    private fun need(n: Int) {
        if (n < 0 || n > end - pos) throw WireException("truncated: need $n bytes, have ${end - pos}")
    }

    fun u8(): Int {
        need(1)
        return data[pos++].toInt() and 0xFF
    }

    fun bool(): Boolean = when (val b = u8()) {
        0 -> false
        1 -> true
        else -> throw WireException("bad boolean $b")
    }

    fun i32(): Int {
        need(4)
        val v = (data[pos].toInt() and 0xFF) or
            ((data[pos + 1].toInt() and 0xFF) shl 8) or
            ((data[pos + 2].toInt() and 0xFF) shl 16) or
            ((data[pos + 3].toInt() and 0xFF) shl 24)
        pos += 4
        return v
    }

    fun i64(): Long {
        need(8)
        var v = 0L
        for (i in 0 until 8) v = v or ((data[pos + i].toLong() and 0xFF) shl (8 * i))
        pos += 8
        return v
    }

    fun f32(): Float = java.lang.Float.intBitsToFloat(i32())

    fun f64(): Double = java.lang.Double.longBitsToDouble(i64())

    private fun length(cap: Int, what: String): Int {
        val n = i32()
        if (n < 0 || n > cap) throw WireException("$what length $n is outside 0..$cap")
        if (n > remaining) throw WireException("$what length $n exceeds the $remaining bytes left")
        return n
    }

    fun bytes(): ByteArray {
        val n = length(WireLimits.MAX_BLOB_BYTES, "blob")
        val out = data.copyOfRange(pos, pos + n)
        pos += n
        return out
    }

    fun string(): String {
        val n = length(WireLimits.MAX_STRING_BYTES, "string")
        val s = String(data, pos, n, StandardCharsets.UTF_8)
        pos += n
        return s
    }

    /** A list count, checked against the cap and against [minElementBytes] per item still to come. */
    fun count(minElementBytes: Int = 1): Int {
        val n = i32()
        if (n < 0 || n > WireLimits.MAX_LIST_ITEMS) throw WireException("list length $n is outside 0..${WireLimits.MAX_LIST_ITEMS}")
        if (minElementBytes > 0 && n.toLong() * minElementBytes > remaining) {
            throw WireException("list of $n items cannot fit in the $remaining bytes left")
        }
        return n
    }

    inline fun <T : Any> optional(read: WireReader.() -> T): T? = when (val flag = u8()) {
        0 -> null
        1 -> read()
        else -> throw WireException("bad presence flag $flag")
    }

    inline fun <T> list(minElementBytes: Int = 1, read: WireReader.() -> T): List<T> {
        val n = count(minElementBytes)
        val out = ArrayList<T>(minOf(n, 1024))
        repeat(n) { out += read() }
        return out
    }

    fun intList(): List<Int> {
        val n = count(4)
        val out = ArrayList<Int>(minOf(n, 1024))
        repeat(n) { out += i32() }
        return out
    }

    /** Fails unless every byte was consumed: trailing garbage is a protocol error. */
    fun finish() {
        if (remaining != 0) throw WireException("$remaining unexpected trailing bytes")
    }
}
