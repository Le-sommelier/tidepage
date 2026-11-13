package app.pdfa.storage

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicInteger

/**
 * In-memory [UriStreams] with fault injection. Output truncates on open (like `"wt"`) and bumps lastModified
 * on close. Knobs are read when a stream or stat is created, so set them before the call under test.
 */
class FakeUriStreams : UriStreams {

    class Entry(var content: ByteArray, var lastModified: Long?, val displayName: String?)

    private val files = ConcurrentHashMap<String, Entry>()
    private var clock = 1_000L

    @Volatile var failStat = false
    @Volatile var failOpenInput = false
    /** Input throws an IOException once this many bytes have been read (every open). */
    @Volatile var failInputAfter: Long? = null
    /** Only for the open numbered [failReadAtOpen] (1-based): the stream fails after [failReadBytes] bytes. */
    @Volatile var failReadAtOpen: Int? = null
    @Volatile var failReadBytes: Long = 0
    /** Output throws once this many bytes have been written to the current stream. */
    @Volatile var failWriteAfter: Long? = null
    /** Bytes silently dropped from the end on close. */
    @Volatile var dropTailOnClose = 0
    /** Flips the first byte written. */
    @Volatile var flipFirstByte = false
    @Volatile var reportLastModified = true
    @Volatile var writeDelayMs = 0L
    @Volatile var readDelayMs = 0L
    /** When set, stat reports this size instead of the real one (a provider with a stale SIZE). */
    @Volatile var staleSize: Long? = null
    /** Thrown by sync(). */
    @Volatile var syncThrows: Throwable? = null
    /** close() throws an IOException after the bytes were stored. */
    @Volatile var closeThrows = false
    /** close() blocks until this latch is released (a hung provider). */
    @Volatile var closeGate: CountDownLatch? = null
    /** Runs after a successful close. */
    @Volatile var onClose: (() -> Unit)? = null
    /** Runs at each stat call, with the 1-based call number. */
    @Volatile var statHook: ((Int) -> Unit)? = null
    /** Runs at each openInput, with the 1-based open number, before the bytes are captured. */
    @Volatile var onInputOpen: ((Int) -> Unit)? = null
    /** documentKey returns null for every URI (a provider that cannot resolve identity). */
    @Volatile var documentKeyNull = false
    /** Alternative URI strings for one document, for documentKey tests. */
    val aliases = ConcurrentHashMap<String, String>()

    val statCalls = AtomicInteger()
    val inputOpens = AtomicInteger()
    val outputsOpened = AtomicInteger()
    val activeWriters = AtomicInteger()
    val maxActiveWriters = AtomicInteger()

    fun put(uri: String, bytes: ByteArray, lastModified: Long? = nextTime(), displayName: String? = null) {
        files[uri] = Entry(bytes.copyOf(), lastModified, displayName)
    }

    /** Makes [other] a second address of the file behind [uri] (one file, two URIs). */
    fun link(other: String, uri: String) {
        files[other] = entry(uri)
    }

    fun bytes(uri: String): ByteArray = entry(uri).content.copyOf()

    fun setLastModified(uri: String, value: Long?) {
        entry(uri).lastModified = value
    }

    override fun documentKey(uri: String): String? = if (documentKeyNull) null else aliases[uri] ?: uri

    override fun openInput(uri: String): InputStream {
        val n = inputOpens.incrementAndGet()
        onInputOpen?.invoke(n)
        if (failOpenInput) throw IOException("injected open failure")
        val limit = if (failReadAtOpen == n) failReadBytes else failInputAfter
        return FakeInput(entry(uri).content.copyOf(), limit, readDelayMs)
    }

    override fun openOutputTruncating(uri: String): OutputStreamWithSync {
        val e = entry(uri)
        outputsOpened.incrementAndGet()
        e.content = ByteArray(0)
        val now = activeWriters.incrementAndGet()
        maxActiveWriters.accumulateAndGet(now) { a, b -> maxOf(a, b) }
        val buf = ByteArrayOutputStream()
        val fail = failWriteAfter
        val drop = dropTailOnClose
        val flip = flipFirstByte
        val delay = writeDelayMs
        val syncErr = syncThrows
        val closeErr = closeThrows
        val gate = closeGate
        val closeHook = onClose

        return object : OutputStreamWithSync() {
            private var written = 0L
            private var closed = false

            override fun write(b: Int) = write(byteArrayOf(b.toByte()), 0, 1)

            override fun write(b: ByteArray, off: Int, len: Int) {
                if (delay > 0) Thread.sleep(delay)
                if (fail != null && written + len > fail) {
                    val allowed = (fail - written).toInt().coerceAtLeast(0)
                    buf.write(b, off, allowed)
                    written += allowed
                    e.content = buf.toByteArray()
                    throw IOException("injected write failure")
                }
                val chunk = b.copyOfRange(off, off + len)
                if (flip && written == 0L && chunk.isNotEmpty()) chunk[0] = (chunk[0].toInt() xor 0x7f).toByte()
                buf.write(chunk)
                written += len
                e.content = buf.toByteArray()
            }

            override fun sync() {
                syncErr?.let { throw it }
            }

            override fun close() {
                if (closed) return
                closed = true
                val data = buf.toByteArray()
                e.content = if (drop > 0) data.copyOf(maxOf(0, data.size - drop)) else data
                e.lastModified = nextTime()
                activeWriters.decrementAndGet()
                gate?.await()
                if (closeErr) throw IOException("injected close failure")
                closeHook?.invoke()
            }
        }
    }

    override fun stat(uri: String): SourceStat {
        statHook?.invoke(statCalls.incrementAndGet())
        if (failStat) throw IOException("injected stat failure")
        val e = entry(uri)
        return SourceStat(
            size = staleSize ?: e.content.size.toLong(),
            lastModified = if (reportLastModified) e.lastModified else null,
            displayName = e.displayName,
        )
    }

    private fun entry(uri: String): Entry = files[uri] ?: throw IOException("no such document")

    @Synchronized
    private fun nextTime(): Long = ++clock

    private class FakeInput(content: ByteArray, private val limit: Long?, private val delayMs: Long) : InputStream() {
        private val delegate = ByteArrayInputStream(content)
        private var read = 0L

        override fun read(): Int {
            val one = ByteArray(1)
            val n = read(one, 0, 1)
            return if (n < 0) -1 else one[0].toInt() and 0xff
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (delayMs > 0) Thread.sleep(delayMs)
            if (limit != null && read >= limit) throw IOException("injected read failure")
            val max = if (limit != null) minOf(len.toLong(), limit - read).toInt() else len
            val n = delegate.read(b, off, max)
            if (n > 0) read += n
            return n
        }
    }
}
