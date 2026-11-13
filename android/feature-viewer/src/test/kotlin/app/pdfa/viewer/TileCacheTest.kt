package app.pdfa.viewer

import java.nio.ByteBuffer
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TileCacheTest {
    /** 16x16 px = 1024 bytes. */
    private fun tile(w: Int = 16, h: Int = 16) = CachedTile(w, h, ByteBuffer.allocate(w * h * 4))
    private fun key(i: Int) = TileKey(i, 0, 0, 0)

    @Test fun putAndGet() {
        val c = TileCache(10_000)
        assertTrue(c.put(key(1), tile()))
        assertNotNull(c.get(key(1)))
        assertNull(c.get(key(2)))
    }

    @Test fun bytesAreAccounted() {
        val c = TileCache(10_000)
        c.put(key(1), tile())
        c.put(key(2), tile(8, 8))
        assertEquals(1024L + 256L, c.sizeBytes)
        assertEquals(2, c.count)
    }

    @Test fun evictsLeastRecentlyUsedWhenOverTheCap() {
        val c = TileCache(3000) // room for two 1024-byte tiles
        c.put(key(1), tile()); c.put(key(2), tile()); c.put(key(3), tile())
        assertNull(c.get(key(1)))
        assertNotNull(c.get(key(2)))
        assertNotNull(c.get(key(3)))
        assertTrue(c.sizeBytes <= 3000)
    }

    @Test fun readingRefreshesRecency() {
        val c = TileCache(3000)
        c.put(key(1), tile()); c.put(key(2), tile())
        c.get(key(1))
        c.put(key(3), tile())
        assertNotNull(c.get(key(1)))
        assertNull(c.get(key(2)))
    }

    @Test fun peekDoesNotRefreshRecency() {
        val c = TileCache(3000)
        c.put(key(1), tile()); c.put(key(2), tile())
        assertTrue(c.peek(key(1)))
        c.put(key(3), tile())
        assertNull(c.get(key(1)))
    }

    @Test fun replacingAKeyAdjustsBytes() {
        val c = TileCache(10_000)
        c.put(key(1), tile(16, 16))
        c.put(key(1), tile(8, 8))
        assertEquals(256L, c.sizeBytes)
        assertEquals(1, c.count)
    }

    @Test fun oversizedTileIsNotStored() {
        val c = TileCache(500)
        assertFalse(c.put(key(1), tile()))
        assertEquals(0, c.count)
        assertEquals(0L, c.sizeBytes)
    }

    @Test fun trimToShrinksFromTheOldEnd() {
        val c = TileCache(10_000)
        for (i in 1..5) c.put(key(i), tile())
        c.trimTo(2048)
        assertEquals(2048L, c.sizeBytes)
        assertNotNull(c.get(key(5)))
        assertNull(c.get(key(1)))
    }

    @Test fun trimToZeroClears() {
        val c = TileCache(10_000)
        c.put(key(1), tile())
        c.trimTo(0)
        assertEquals(0, c.count)
        assertEquals(0L, c.sizeBytes)
    }

    @Test fun evictionListenerSeesEvictedKeys() {
        val c = TileCache(2048)
        val seen = mutableListOf<TileKey>()
        c.evictionListener = { seen += it }
        c.put(key(1), tile()); c.put(key(2), tile()); c.put(key(3), tile())
        assertEquals(listOf(key(1)), seen)
    }

    @Test fun defaultCapIsMinOf64MiBAndMemoryClassOverEight() {
        assertEquals(32L * 1024 * 1024, TileCache.defaultMaxBytes(256))
        assertEquals(64L * 1024 * 1024, TileCache.defaultMaxBytes(1024))
        assertEquals(16L * 1024 * 1024, TileCache.defaultMaxBytes(128))
    }

    @Test fun pixelsViewsAreIndependent() {
        val t = tile(2, 2)
        val a = t.pixels()
        a.get()
        assertEquals(0, t.pixels().position())
    }

    @Test fun concurrentUseKeepsTheCapAndAccounting() {
        val c = TileCache(50 * 1024L)
        val pool = Executors.newFixedThreadPool(8)
        val done = CountDownLatch(8)
        repeat(8) { n ->
            pool.execute {
                repeat(2000) { i ->
                    val k = key((n * 7 + i) % 200)
                    c.put(k, tile())
                    c.get(key(i % 200))
                    if (i % 500 == 0) c.trimTo(20 * 1024)
                }
                done.countDown()
            }
        }
        done.await()
        pool.shutdown()
        assertTrue(c.sizeBytes <= 50 * 1024L)
        assertEquals(c.count * 1024L, c.sizeBytes)
    }

    @Test fun budgetIsOneCapSplitBetweenBytesAndBitmaps() {
        val b = TileBudget.forMemoryClass(256)
        assertEquals(32L * 1024 * 1024, b.totalBytes)
        assertEquals(b.totalBytes, b.cacheBytes + b.bitmapBytes)
        assertTrue(b.bitmapBytes > b.cacheBytes)
        assertEquals(64L * 1024 * 1024, TileBudget.forMemoryClass(4096).totalBytes)
        assertEquals(8L * 1024 * 1024, TileBudget.forMemoryClass(64).totalBytes)
    }
}
