package app.pdfa.viewer.ui

import android.graphics.Bitmap
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import app.pdfa.viewer.CachedTile
import app.pdfa.viewer.TileKey

/**
 * Bitmaps of the tiles being drawn.
 *
 * Memory: a tile exists as bytes in the [app.pdfa.viewer.TileCache] and, while visible, as a bitmap here. The two
 * caps are ONE budget ([app.pdfa.viewer.TileBudget], at most memoryClass/8 and 64 MiB). This store's share
 * [maxBytes] covers live bitmaps, retired ones and the reuse pool together.
 *
 * Reuse: an evicted bitmap is not reused at once, because a frame already recorded may still be drawing it on
 * the render thread. It is "retired" and joins the pool only after [beginFrame] has been called twice (two frames
 * later). `recycle()` is never called. All access is synchronized; [convert] may run on any thread.
 */
class BitmapStore(private val maxBytes: Long) {
    class Entry(val bitmap: Bitmap) {
        val image: ImageBitmap = bitmap.asImageBitmap()
        val bytes: Long = bitmap.allocationByteCount.toLong()
    }

    private class Retired(val bitmap: Bitmap, var age: Int)

    private val map = LinkedHashMap<TileKey, Entry>(64, 0.75f, true)
    private var liveBytes = 0L
    private var idleBytes = 0L
    private val retired = ArrayDeque<Retired>()
    private val pool = ArrayDeque<Bitmap>()

    @Synchronized
    fun get(key: TileKey): Entry? = map[key]

    @Synchronized
    fun contains(key: TileKey): Boolean = map.containsKey(key)

    /** Call at the start of every draw: bitmaps retired two frames ago become reusable. */
    @Synchronized
    fun beginFrame() {
        if (retired.isEmpty()) return
        val it = retired.iterator()
        while (it.hasNext()) {
            val r = it.next()
            r.age++
            if (r.age >= 2) {
                it.remove()
                pool.addLast(r.bitmap)
            }
        }
    }

    @Synchronized
    fun put(key: TileKey, entry: Entry) {
        map.put(key, entry)?.let { liveBytes -= it.bytes; retire(it.bitmap) }
        liveBytes += entry.bytes
        // Free idle memory first (pool, then retired), then the least recently used live tiles.
        while (liveBytes + idleBytes > maxBytes && pool.isNotEmpty()) idleBytes -= pool.removeFirst().bytes()
        while (liveBytes + idleBytes > maxBytes && retired.isNotEmpty()) idleBytes -= retired.removeFirst().bitmap.bytes()
        if (liveBytes + idleBytes > maxBytes) {
            for (k in map.keys.toList()) {
                if (liveBytes + idleBytes <= maxBytes) break
                if (k == key) continue
                val e = map.remove(k) ?: continue
                liveBytes -= e.bytes
                retire(e.bitmap)
                while (liveBytes + idleBytes > maxBytes && retired.isNotEmpty()) idleBytes -= retired.removeFirst().bitmap.bytes()
            }
        }
    }

    @Synchronized
    fun clear() {
        map.clear(); retired.clear(); pool.clear()
        liveBytes = 0L; idleBytes = 0L
    }

    /** Premultiplied RGBA8 bytes into an ARGB_8888 bitmap (same memory layout). */
    fun convert(tile: CachedTile): Entry {
        val bitmap = obtain(tile.width, tile.height)
        bitmap.copyPixelsFromBuffer(tile.pixels())
        return Entry(bitmap)
    }

    @Synchronized
    private fun obtain(w: Int, h: Int): Bitmap {
        val it = pool.iterator()
        while (it.hasNext()) {
            val b = it.next()
            if (b.width == w && b.height == h && b.isMutable) {
                it.remove()
                idleBytes -= b.bytes()
                return b
            }
        }
        return Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).also { it.isPremultiplied = true }
    }

    private fun retire(b: Bitmap) {
        retired.addLast(Retired(b, 0))
        idleBytes += b.bytes()
    }

    private fun Bitmap.bytes(): Long = allocationByteCount.toLong()
}
