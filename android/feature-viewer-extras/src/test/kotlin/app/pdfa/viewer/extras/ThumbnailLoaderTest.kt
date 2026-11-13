package app.pdfa.viewer.extras

import app.pdfa.engine.DocHandle
import app.pdfa.engine.EngineError
import app.pdfa.engine.PageInfo
import app.pdfa.engine.TileRequest
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Test
import kotlin.math.abs
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class ThumbnailLoaderTest {

    private val letter = PageInfo(612f, 792f, "")

    private fun TestScope.loader(
        engine: ScriptedEngine,
        handle: DocHandle,
        pages: List<PageInfo>,
        targetWidthPx: Int = 120,
        cacheBytes: Long = ThumbnailLoader.CACHE_BYTES,
        maxParallel: Int = ThumbnailLoader.MAX_PARALLEL,
    ) = ThumbnailLoader(
        engine = engine,
        handle = handle,
        pages = pages,
        targetWidthPx = targetWidthPx,
        dispatcher = StandardTestDispatcher(testScheduler),
        scope = this,
        cacheBytes = cacheBytes,
        maxParallel = maxParallel,
    )

    private fun expectedSize(info: PageInfo, target: Int): Pair<Int, Int> {
        val scale = ThumbnailMath.scaleFor(info, target)!!
        return ThumbnailMath.tileSize(info, scale)!!
    }

    @Test
    fun `visible pages are rendered as preview tiles at the target width`() = runTest {
        val engine = ScriptedEngine()
        val h = engine.openBlank(3)
        val l = loader(engine, h, List(3) { letter })

        l.setVisible(setOf(0, 1))
        advanceUntilIdle()

        assertEquals(2, engine.tileRequests.size)
        for (req in engine.tileRequests) {
            assertTrue(req.preview, "thumbnails ask for the preview path")
            assertEquals(0, req.x)
            assertEquals(0, req.y)
            assertTrue(abs(req.width - 120) <= 1, "width ${req.width} is near 120")
        }
    }

    @Test
    fun `only pages on screen are requested`() = runTest {
        val engine = ScriptedEngine()
        val h = engine.openBlank(10)
        val l = loader(engine, h, List(10) { letter })

        l.setVisible(setOf(7))
        advanceUntilIdle()

        assertEquals(listOf(7), engine.tileRequests.map { it.page })
    }

    @Test
    fun `a ready thumbnail holds width times height times four bytes`() = runTest {
        val engine = ScriptedEngine()
        val h = engine.openBlank(1)
        val l = loader(engine, h, listOf(letter))

        l.setVisible(setOf(0))
        advanceUntilIdle()

        val ready = assertIs<ThumbnailState.Ready>(l.states.value[0])
        val (w, hgt) = expectedSize(letter, 120)
        assertEquals(w, ready.width)
        assertEquals(hgt, ready.height)
        assertEquals(w * hgt * 4, ready.rgba.size)
    }

    @Test
    fun `scrolling a page out of view cancels its render and drops its state`() = runTest {
        val engine = ScriptedEngine()
        val h = engine.openBlank(2)
        val l = loader(engine, h, List(2) { letter })
        engine.tileHook = { delay(1_000) }

        l.setVisible(setOf(0))
        advanceTimeBy(10)
        runCurrent()
        assertEquals(ThumbnailState.Loading, l.states.value[0])

        l.setVisible(emptySet())
        advanceUntilIdle()

        assertTrue(l.states.value.isEmpty())
        assertEquals(1, engine.tileRequests.size)
        assertEquals(0, engine.renderInFlight)
    }

    @Test
    fun `a page that comes back into view after being cancelled renders again`() = runTest {
        val engine = ScriptedEngine()
        val h = engine.openBlank(2)
        val l = loader(engine, h, List(2) { letter })
        engine.tileHook = { delay(1_000) }
        l.setVisible(setOf(0))
        advanceTimeBy(10)
        runCurrent()
        l.setVisible(emptySet())
        engine.tileHook = null

        l.setVisible(setOf(0))
        advanceUntilIdle()

        assertIs<ThumbnailState.Ready>(l.states.value[0])
        assertEquals(2, engine.tileRequests.size)
    }

    @Test
    fun `a cached thumbnail is shown at once without a second render`() = runTest {
        val engine = ScriptedEngine()
        val h = engine.openBlank(1)
        val l = loader(engine, h, listOf(letter))
        l.setVisible(setOf(0))
        advanceUntilIdle()
        l.setVisible(emptySet())

        l.setVisible(setOf(0))

        assertIs<ThumbnailState.Ready>(l.states.value[0])
        advanceUntilIdle()
        assertEquals(1, engine.tileRequests.size)
    }

    @Test
    fun `a failed render shows failed and retry renders it again`() = runTest {
        val engine = ScriptedEngine()
        val h = engine.openBlank(1)
        val l = loader(engine, h, listOf(letter))
        engine.tileErrorsOnce[0] = EngineError.Io("read")

        l.setVisible(setOf(0))
        advanceUntilIdle()
        val failed = assertIs<ThumbnailState.Failed>(l.states.value[0])
        assertFalse(failed.engineLost)

        l.retry(0)
        advanceUntilIdle()

        assertIs<ThumbnailState.Ready>(l.states.value[0])
        assertEquals(2, engine.tileRequests.size)
    }

    @Test
    fun `a failed page stays failed while visible and is not requested on every scroll`() = runTest {
        val engine = ScriptedEngine()
        val h = engine.openBlank(1)
        val l = loader(engine, h, listOf(letter))
        engine.tileErrorsOnce[0] = EngineError.Io("read")
        l.setVisible(setOf(0))
        advanceUntilIdle()

        l.setVisible(setOf(0))
        l.setVisible(setOf(0))
        advanceUntilIdle()

        assertIs<ThumbnailState.Failed>(l.states.value[0])
        assertEquals(1, engine.tileRequests.size)
    }

    @Test
    fun `an engine death during a render is flagged so the UI knows to reopen`() = runTest {
        val engine = ScriptedEngine()
        val h = engine.openBlank(1)
        val l = loader(engine, h, listOf(letter))
        engine.tileErrorsOnce[0] = EngineError.EngineDied("oom")

        l.setVisible(setOf(0))
        advanceUntilIdle()

        assertTrue(assertIs<ThumbnailState.Failed>(l.states.value[0]).engineLost)
    }

    @Test
    fun `a timeout during a render is an engine loss too`() = runTest {
        val engine = ScriptedEngine()
        val h = engine.openBlank(1)
        val l = loader(engine, h, listOf(letter))
        engine.tileErrorsOnce[0] = EngineError.Timeout("slow")

        l.setVisible(setOf(0))
        advanceUntilIdle()

        assertTrue(assertIs<ThumbnailState.Failed>(l.states.value[0]).engineLost)
    }

    @Test
    fun `at most two renders run at once`() = runTest {
        val engine = ScriptedEngine()
        val h = engine.openBlank(4)
        val l = loader(engine, h, List(4) { letter })
        engine.tileHook = { delay(100) }

        l.setVisible(setOf(0, 1, 2, 3))
        advanceUntilIdle()

        assertEquals(2, engine.maxRenderInFlight)
        assertEquals(4, l.states.value.size)
        assertTrue(l.states.value.values.all { it is ThumbnailState.Ready })
    }

    @Test
    fun `a page with no usable size fails without touching the engine`() = runTest {
        val engine = ScriptedEngine()
        val h = engine.openBlank(1)
        val l = loader(engine, h, listOf(PageInfo(0f, 0f, "")))

        l.setVisible(setOf(0))
        advanceUntilIdle()

        assertIs<ThumbnailState.Failed>(l.states.value[0])
        assertTrue(engine.tileRequests.isEmpty())
    }

    @Test
    fun `an engine restart drops the cache and renders with the new handle`() = runTest {
        val engine = ScriptedEngine()
        val h1 = engine.openBlank(1)
        val l = loader(engine, h1, listOf(letter))
        l.setVisible(setOf(0))
        advanceUntilIdle()

        val h2 = engine.openBlank(1)
        l.onEngineRestarted(h2)
        advanceUntilIdle()

        assertEquals(listOf(h1.id, h2.id), engine.tileHandles)
        assertIs<ThumbnailState.Ready>(l.states.value[0])
    }

    @Test
    fun `the cache evicts old thumbnails when the byte cap is reached`() = runTest {
        val engine = ScriptedEngine()
        val h = engine.openBlank(2)
        val (w, hgt) = expectedSize(letter, 120)
        val oneThumb = (w * hgt * 4).toLong()
        val l = loader(engine, h, List(2) { letter }, cacheBytes = oneThumb + oneThumb / 2)

        l.setVisible(setOf(0))
        advanceUntilIdle()
        l.setVisible(setOf(1))
        advanceUntilIdle()
        l.setVisible(setOf(0))
        advanceUntilIdle()

        assertEquals(listOf(0, 1, 0), engine.tileRequests.map { it.page })
    }

    @Test
    fun `a request object carries the preview flag and a full scale`() = runTest {
        val engine = ScriptedEngine()
        val h = engine.openBlank(1)
        val l = loader(engine, h, listOf(letter))

        l.setVisible(setOf(0))
        advanceUntilIdle()

        val req: TileRequest = engine.tileRequests.single()
        assertTrue(req.scale > 0f)
        assertEquals(0, req.page)
        assertTrue(req.preview)
    }

    @Test
    fun `close cancels renders still in flight`() = runTest {
        val engine = ScriptedEngine()
        val h = engine.openBlank(1)
        val l = loader(engine, h, listOf(letter))
        engine.tileHook = { delay(1_000) }
        l.setVisible(setOf(0))
        advanceTimeBy(10)
        runCurrent()

        l.close()
        advanceUntilIdle()

        assertEquals(0, engine.renderInFlight)
        assertTrue(l.states.value.isEmpty())
    }

    @Test
    fun `a retry on a page that is not failed does nothing`() = runTest {
        val engine = ScriptedEngine()
        val h = engine.openBlank(1)
        val l = loader(engine, h, listOf(letter))
        l.setVisible(setOf(0))
        advanceUntilIdle()

        l.retry(0)
        advanceUntilIdle()

        assertEquals(1, engine.tileRequests.size)
    }

    // ---- pure maths and the LRU cache ----

    @Test
    fun `scale is set by the target width for an ordinary page`() {
        val scale = ThumbnailMath.scaleFor(letter, 120)!!

        assertEquals(120f / 612f, scale, 1e-6f)
    }

    @Test
    fun `scale is reduced for a page too tall for the height cap`() {
        val tall = PageInfo(100f, 100_000f, "")

        val scale = ThumbnailMath.scaleFor(tall, 120)!!

        assertEquals(ThumbnailMath.MAX_HEIGHT_PX / 100_000f, scale, 1e-6f)
        assertTrue(scale < 120f / 100f)
    }

    @Test
    fun `scale is null for pages with no usable size or target`() {
        assertNull(ThumbnailMath.scaleFor(PageInfo(0f, 10f, ""), 120))
        assertNull(ThumbnailMath.scaleFor(PageInfo(10f, 0f, ""), 120))
        assertNull(ThumbnailMath.scaleFor(PageInfo(Float.NaN, 10f, ""), 120))
        assertNull(ThumbnailMath.scaleFor(PageInfo(Float.POSITIVE_INFINITY, 10f, ""), 120))
        assertNull(ThumbnailMath.scaleFor(letter, 0))
        assertNull(ThumbnailMath.scaleFor(letter, -5))
    }

    @Test
    fun `tile size rounds down and is at least one pixel`() {
        assertEquals(1 to 1, ThumbnailMath.tileSize(PageInfo(0.5f, 0.5f, ""), 0.1f))
        val (w, h) = ThumbnailMath.tileSize(letter, 120f / 612f)!!
        assertTrue(w == 119 || w == 120, "width $w")
        assertEquals(155, h)
    }

    @Test
    fun `tile size is null for a bad scale`() {
        assertNull(ThumbnailMath.tileSize(letter, 0f))
        assertNull(ThumbnailMath.tileSize(letter, Float.NaN))
        assertNull(ThumbnailMath.tileSize(letter, -1f))
    }

    @Test
    fun `the LRU cache returns what was stored`() {
        val cache = LruByteCache(100)
        val key = ThumbKey(0, 1, 1)

        assertTrue(cache.put(key, ByteArray(10)))

        assertEquals(10, cache.get(key)!!.size)
        assertEquals(10L, cache.sizeBytes)
        assertEquals(1, cache.entryCount)
    }

    @Test
    fun `the LRU cache evicts the least recently used entry first`() {
        val cache = LruByteCache(10)
        val a = ThumbKey(0, 1, 1)
        val b = ThumbKey(1, 1, 1)
        val c = ThumbKey(2, 1, 1)
        cache.put(a, ByteArray(4))
        cache.put(b, ByteArray(4))
        cache.get(a)

        cache.put(c, ByteArray(4))

        assertTrue(cache.contains(a), "a was touched, so it stays")
        assertFalse(cache.contains(b), "b was least recently used, so it goes")
        assertTrue(cache.contains(c))
        assertEquals(8L, cache.sizeBytes)
    }

    @Test
    fun `an entry bigger than the whole cap is refused and evicts nothing`() {
        val cache = LruByteCache(10)
        val small = ThumbKey(0, 1, 1)
        cache.put(small, ByteArray(4))

        assertFalse(cache.put(ThumbKey(1, 9, 9), ByteArray(11)))

        assertTrue(cache.contains(small))
        assertEquals(4L, cache.sizeBytes)
    }

    @Test
    fun `replacing an entry does not count it twice`() {
        val cache = LruByteCache(100)
        val key = ThumbKey(0, 1, 1)
        cache.put(key, ByteArray(30))

        cache.put(key, ByteArray(20))

        assertEquals(20L, cache.sizeBytes)
        assertEquals(1, cache.entryCount)
    }

    @Test
    fun `clear empties the LRU cache`() {
        val cache = LruByteCache(100)
        cache.put(ThumbKey(0, 1, 1), ByteArray(30))

        cache.clear()

        assertEquals(0L, cache.sizeBytes)
        assertEquals(0, cache.entryCount)
    }

    @Test
    fun `a tile above 2 MiB is never requested and shows failed`() = runTest {
        val engine = ScriptedEngine()
        val h = engine.openBlank(1)
        val l = loader(engine, h, listOf(letter), targetWidthPx = 2000)

        l.setVisible(setOf(0))
        advanceUntilIdle()

        assertIs<ThumbnailState.Failed>(l.states.value[0])
        assertTrue(engine.tileRequests.isEmpty())
    }

    @Test
    fun `a small tile is still rendered`() = runTest {
        val engine = ScriptedEngine()
        val h = engine.openBlank(1)
        val l = loader(engine, h, listOf(letter), targetWidthPx = 300)

        l.setVisible(setOf(0))
        advanceUntilIdle()

        assertIs<ThumbnailState.Ready>(l.states.value[0])
    }
}
