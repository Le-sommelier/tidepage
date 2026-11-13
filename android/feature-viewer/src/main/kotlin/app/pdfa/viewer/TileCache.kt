package app.pdfa.viewer

import java.nio.ByteBuffer

/** One rendered tile: premultiplied RGBA8, `width * height * 4` bytes. Bitmap conversion happens in the UI layer only. */
class CachedTile(val width: Int, val height: Int, private val buffer: ByteBuffer) {
    val bytes: Long = width.toLong() * height * 4

    /**
     * A fresh view positioned at 0, so concurrent readers never share a position. Callers only read it.
     *
     * Not `asReadOnlyBuffer()`: a read-only view of a HEAP buffer hides its array, and
     * `Bitmap.copyPixelsFromBuffer` then gets a null array and aborts the process (seen with the real
     * engine client, whose tiles are heap buffers; the fake engine's are direct).
     */
    fun pixels(): ByteBuffer = buffer.duplicate().apply { rewind() }
}

/**
 * LRU cache of tiles bounded by BYTES, safe to use from any thread.
 * Default cap: min(64 MiB, memoryClass / 8).
 */
class TileCache(maxBytes: Long = defaultMaxBytes(256)) {
    private val lock = Any()
    private val map = LinkedHashMap<TileKey, CachedTile>(64, 0.75f, true)
    private var total = 0L

    var maxBytes: Long = maxBytes
        private set

    /** Called (outside the lock) for every tile dropped by eviction or trim. */
    @Volatile
    var evictionListener: ((TileKey) -> Unit)? = null

    val sizeBytes: Long get() = synchronized(lock) { total }
    val count: Int get() = synchronized(lock) { map.size }

    fun get(key: TileKey): CachedTile? = synchronized(lock) { map[key] }

    /** Looks up without changing recency. */
    fun peek(key: TileKey): Boolean = synchronized(lock) { map.containsKey(key) }

    /** Returns false when the tile alone is larger than the cap (it is not stored). */
    fun put(key: TileKey, tile: CachedTile): Boolean {
        val evicted = ArrayList<TileKey>()
        val stored: Boolean
        synchronized(lock) {
            if (tile.bytes > maxBytes) {
                map.remove(key)?.let { total -= it.bytes; evicted += key }
                stored = false
            } else {
                map.put(key, tile)?.let { total -= it.bytes }
                total += tile.bytes
                evictDownTo(maxBytes, evicted, protect = key)
                stored = true
            }
        }
        notify(evicted)
        return stored
    }

    /** Evicts least-recently-used tiles until at most [bytes] remain (onTrimMemory). */
    fun trimTo(bytes: Long) {
        val evicted = ArrayList<TileKey>()
        synchronized(lock) { evictDownTo(bytes.coerceAtLeast(0L), evicted, protect = null) }
        notify(evicted)
    }

    fun clear() = trimTo(0L)

    fun keys(): List<TileKey> = synchronized(lock) { map.keys.toList() }

    private fun evictDownTo(limit: Long, out: MutableList<TileKey>, protect: TileKey?) {
        val it = map.entries.iterator()
        while (total > limit && it.hasNext()) {
            val e = it.next()
            if (e.key == protect && limit >= e.value.bytes) continue
            total -= e.value.bytes
            out += e.key
            it.remove()
        }
    }

    private fun notify(keys: List<TileKey>) {
        val l = evictionListener ?: return
        keys.forEach(l)
    }

    companion object {
        private const val MIB = 1024L * 1024L

        fun defaultMaxBytes(memoryClassMb: Int): Long = minOf(64L * MIB, memoryClassMb.toLong() * MIB / 8L)
    }
}
