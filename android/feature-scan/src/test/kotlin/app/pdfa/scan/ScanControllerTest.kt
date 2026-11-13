package app.pdfa.scan

import app.pdfa.engine.EngineError
import app.pdfa.engine.ImageFrame
import app.pdfa.engine.Pt
import app.pdfa.engine.Quad
import app.pdfa.engine.ScanLook
import app.pdfa.engine.fake.FakeEngine
import java.time.LocalDate
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Codec that writes parseable headers instead of pixels and counts decoded frames alive at once. */
private class CountingCodec : FrameCodec {
    var live = 0
    var maxLive = 0
    var decodes = 0
    var encodes = 0
    var downscales = 0

    override suspend fun encode(frame: ImageFrame, quality: Int): ByteArray {
        encodes++
        return FakeEngine.fakeImage(frame.width, frame.height)
    }

    override suspend fun <T> withDecoded(jpeg: ByteArray, maxPixels: Long, block: suspend (ImageFrame) -> T): T {
        decodes++
        live++
        maxLive = maxOf(maxLive, live)
        try {
            // PNG header written by FakeEngine.fakeImage: width at 16, height at 20.
            val isJpeg = jpeg[0] == 0xFF.toByte() && jpeg[1] == 0xD8.toByte()
            val w = if (isJpeg) be16(jpeg, 9) else be32(jpeg, 16)
            val h = if (isJpeg) be16(jpeg, 7) else be32(jpeg, 20)
            return block(frame(w, h))
        } finally {
            live--
        }
    }

    override suspend fun downscale(frame: ImageFrame, to: PixelSize): ImageFrame {
        downscales++
        return frame(to.width, to.height)
    }

    private fun be16(b: ByteArray, i: Int) = ((b[i].toInt() and 0xFF) shl 8) or (b[i + 1].toInt() and 0xFF)

    private fun be32(b: ByteArray, i: Int) =
        ((b[i].toInt() and 0xFF) shl 24) or ((b[i + 1].toInt() and 0xFF) shl 16) or ((b[i + 2].toInt() and 0xFF) shl 8) or (b[i + 3].toInt() and 0xFF)
}

private fun frame(w: Int, h: Int, uniform: Boolean = false): ImageFrame {
    val rgba = ByteArray(w * h * 4) { if (uniform) 0x7F else (it * 31 % 251).toByte() }
    return ImageFrame(w, h, rgba)
}

/** Bytes that sniff as JPEG; the fake engine reads them with its own JPEG parser only for imagesToPdf. */
private fun jpegWithSize(w: Int, h: Int): ByteArray {
    // SOI, APP0-less, SOF0 segment with height/width.
    val sof = byteArrayOf(
        0xFF.toByte(), 0xC0.toByte(), 0, 17, 8,
        (h shr 8).toByte(), h.toByte(), (w shr 8).toByte(), w.toByte(),
        3, 1, 0x22, 0, 2, 0x11, 1, 3, 0x11, 1,
    )
    return byteArrayOf(0xFF.toByte(), 0xD8.toByte()) + sof + byteArrayOf(0xFF.toByte(), 0xD9.toByte())
}

@OptIn(ExperimentalCoroutinesApi::class)
class ScanControllerTest {
    private val clock = ScanClock { LocalDate.of(2026, 11, 10) }

    private fun test(engine: FakeEngine = FakeEngine(), body: suspend kotlinx.coroutines.test.TestScope.(ScanController, FakeEngine, CountingCodec) -> Unit) = runTest {
        val codec = CountingCodec()
        val c = ScanController(engine, codec, clock, UnconfinedTestDispatcher(testScheduler), ScanLevels.FAKE_ENGINE)
        body(c, engine, codec)
    }

    @Test
    fun captureFindsEdgesAndAddsAProcessedPage() = test { c, _, codec ->
        assertTrue(c.addCapture(frame(400, 300)))
        val s = c.state.value
        assertEquals(1, s.pages.size)
        val p = s.pages[0]
        // The fake detects a quad inset by a tenth of the short side (30 px).
        assertEquals(Quad(Pt(30f, 30f), Pt(370f, 30f), Pt(370f, 270f), Pt(30f, 270f)), p.quad)
        assertEquals(340, p.result.width)
        assertEquals(240, p.result.height)
        assertEquals(400, p.source.width)
        assertTrue(p.processed)
        assertEquals(ScanController.DEFAULT_LOOK, p.look)
        assertNull(s.busy)
        assertNull(s.problem)
        assertEquals(2, codec.encodes) // source and result
        assertEquals(0, codec.decodes)
    }

    @Test
    fun captureFallsBackToTheFullFrameWhenNothingIsDetected() = test { c, _, _ ->
        assertTrue(c.addCapture(frame(400, 300, uniform = true)))
        val p = c.state.value.pages[0]
        assertEquals(QuadGeometry.fullFrame(400, 300), p.quad)
        assertEquals(400, p.result.width)
        assertEquals(300, p.result.height)
    }

    @Test
    fun captureFallsBackWhenTheDetectedQuadIsUnusable() = test(FakeEngine()) { c, _, _ ->
        // An 8 x 8 frame: the fake's quad (0.8 px inset) has 6.4 px edges, under the minimum.
        assertTrue(c.addCapture(frame(8, 8)))
        assertEquals(QuadGeometry.fullFrame(8, 8), c.state.value.pages[0].quad)
    }

    @Test
    fun oversizedFrameIsRefusedBeforeAnyEngineCall() = test { c, _, codec ->
        val huge = ImageFrame(8001, 5000, ByteArray(0)) // 40,005,000 px; refused without touching the bytes
        assertFalse(c.addCapture(huge))
        assertEquals(ScanProblem.IMAGE_TOO_BIG, c.state.value.problem)
        assertTrue(c.state.value.pages.isEmpty())
        assertEquals(0, codec.encodes)
        assertEquals(0, codec.downscales)
    }

    @Test
    fun frameAboveTheWorkingSizeIsDownscaledFirst() = test { c, _, codec ->
        assertTrue(c.addCapture(frame(4000, 3000, uniform = true))) // 12 Mpx > 8 Mpx working size
        assertEquals(1, codec.downscales)
        val p = c.state.value.pages[0]
        assertTrue(p.source.size.pixels <= Sizing.SCAN_MAX_PIXELS)
        assertTrue(p.source.width <= Sizing.SCAN_MAX_LONG_EDGE)
        assertEquals(4.0 / 3.0, p.source.width.toDouble() / p.source.height, 0.01)
    }

    @Test
    fun setQuadReprocessesAndAcceptsAnyCornerOrder() = test { c, _, codec ->
        c.addCapture(frame(400, 300))
        val tl = Pt(50f, 40f); val tr = Pt(350f, 40f); val br = Pt(350f, 260f); val bl = Pt(50f, 260f)
        assertTrue(c.setQuad(0, Quad(br, tl, bl, tr)))
        val p = c.state.value.pages[0]
        assertEquals(Quad(tl, tr, br, bl), p.quad)
        assertEquals(300, p.result.width)
        assertEquals(220, p.result.height)
        assertEquals(1, codec.decodes)
    }

    @Test
    fun badCropIsRefusedAndKeepsThePage() = test { c, _, _ ->
        c.addCapture(frame(400, 300))
        val before = c.state.value.pages[0]
        val line = Quad(Pt(0f, 0f), Pt(10f, 10f), Pt(20f, 20f), Pt(30f, 30f))
        assertFalse(c.setQuad(0, line))
        assertEquals(ScanProblem.BAD_CROP, c.state.value.problem)
        assertTrue(before === c.state.value.pages[0])
    }

    @Test
    fun lookAndLevelChangesRerunRectify() = test { c, _, codec ->
        c.addCapture(frame(400, 300))
        val first = c.state.value.pages[0].result
        assertTrue(c.setLook(0, ScanLook.BLACK_WHITE))
        assertEquals(ScanLook.BLACK_WHITE, c.state.value.pages[0].look)
        assertTrue(first !== c.state.value.pages[0].result)
        assertTrue(c.setLevel(0, 99))
        assertEquals(ScanLevels.FAKE_ENGINE.max, c.state.value.pages[0].level)
        assertEquals(2, codec.decodes)
        // Same look again is a no-op: no decode.
        assertTrue(c.setLook(0, ScanLook.BLACK_WHITE))
        assertEquals(2, codec.decodes)
        assertFalse(c.setLook(5, ScanLook.GREYSCALE))
    }

    @Test
    fun realEngineRangeIsZeroToOneHundredWithMidDefault() = runTest {
        val c = ScanController(FakeEngine(), CountingCodec(), clock, UnconfinedTestDispatcher(testScheduler))
        assertEquals(0..100, c.state.value.levels.min..c.state.value.levels.max)
        assertEquals(50, c.state.value.defaultLevel)
        c.setDefaultLook(ScanLook.COLOUR, 500)
        assertEquals(100, c.state.value.defaultLevel)
    }

    @Test
    fun applyLookToAllTouchesEveryPage() = test { c, _, codec ->
        c.addCapture(frame(100, 100)); c.addCapture(frame(120, 90))
        assertTrue(c.applyLookToAll(ScanLook.GREYSCALE, 2))
        assertTrue(c.state.value.pages.all { it.look == ScanLook.GREYSCALE && it.level == 2 })
        assertEquals(2, codec.decodes)
        assertEquals(1, codec.maxLive)
    }

    @Test
    fun defaultLookAppliesToNextCapture() = test { c, _, _ ->
        c.setDefaultLook(ScanLook.GREYSCALE, 2)
        c.addCapture(frame(100, 100))
        assertEquals(ScanLook.GREYSCALE, c.state.value.pages[0].look)
        assertEquals(2, c.state.value.pages[0].level)
    }

    @Test
    fun reorderDeleteAndBounds() = test { c, _, _ ->
        repeat(3) { c.addCapture(frame(100 + it * 10, 100)) }
        val ids = c.state.value.pages.map { it.id }
        c.moveLater(0)
        assertEquals(listOf(ids[1], ids[0], ids[2]), c.state.value.pages.map { it.id })
        c.moveEarlier(0) // already first: nothing happens
        assertEquals(listOf(ids[1], ids[0], ids[2]), c.state.value.pages.map { it.id })
        c.moveLater(2) // already last
        assertEquals(listOf(ids[1], ids[0], ids[2]), c.state.value.pages.map { it.id })
        c.reorder(2, 0)
        assertEquals(listOf(ids[2], ids[1], ids[0]), c.state.value.pages.map { it.id })
        c.reorder(9, 0) // out of range is ignored
        assertEquals(3, c.state.value.pages.size)
        c.delete(1)
        assertEquals(listOf(ids[2], ids[0]), c.state.value.pages.map { it.id })
        c.delete(7)
        assertEquals(2, c.state.value.pages.size)
        c.clear()
        assertTrue(c.state.value.pages.isEmpty())
    }

    @Test
    fun retakeReplacesInPlaceThenAppendsAgain() = test { c, _, _ ->
        c.addCapture(frame(100, 100)); c.addCapture(frame(110, 100))
        val ids = c.state.value.pages.map { it.id }
        c.retake(0)
        assertEquals(0, c.state.value.retakeIndex)
        c.addCapture(frame(150, 100))
        val s = c.state.value
        assertEquals(2, s.pages.size)
        assertEquals(150, s.pages[0].source.width)
        assertTrue(s.pages[0].id != ids[0])
        assertEquals(ids[1], s.pages[1].id)
        assertNull(s.retakeIndex)
        c.addCapture(frame(160, 100))
        assertEquals(3, c.state.value.pages.size)
        c.retake(9) // ignored
        assertNull(c.state.value.retakeIndex)
        c.retake(1); c.cancelRetake()
        assertNull(c.state.value.retakeIndex)
    }

    @Test
    fun buildPdfSendsEveryPageInOrderWithOneDpi() = test { c, engine, _ ->
        c.addCapture(frame(400, 300)); c.addCapture(frame(300, 400)); c.moveEarlier(1)
        val bytes = assertNotNull(c.buildPdf())
        val h = engine.open(bytes)
        val infos = engine.pageInfos(h)
        assertEquals(2, infos.size)
        // Long edge 340 -> one dpi for all: 340 / 11.693 = 29.1 -> clamped to 50, so a point = px * 72 / 50.
        assertEquals(240f * 72f / 50f, infos[0].widthPt, 0.5f) // the portrait page came first: 240 wide
        assertEquals(340f * 72f / 50f, infos[0].heightPt, 0.5f)
        assertNull(c.state.value.busy)
        assertEquals("Scan 2026-11-10.pdf", c.suggestedName())
    }

    @Test
    fun buildPdfWithoutPagesReportsIt() = test { c, _, _ ->
        assertNull(c.buildPdf())
        assertEquals(ScanProblem.NO_PAGES, c.state.value.problem)
    }

    @Test
    fun tooLargeFromTheEngineIsReportedWithItsText() = test { c, engine, _ ->
        c.addCapture(frame(100, 100))
        engine.failNext(EngineError.TooLarge("output exceeds limit"), "imagesToPdf")
        assertNull(c.buildPdf())
        assertEquals(ScanProblem.TOO_LARGE, c.state.value.problem)
        assertEquals("output exceeds limit", c.state.value.problemDetail)
        assertEquals(1, c.state.value.pages.size)
        assertNull(c.state.value.busy)
        // Trying again works and clears the message.
        assertNotNull(c.buildPdf())
        assertNull(c.state.value.problem)
    }

    @Test
    fun otherEngineErrorsLeaveThePagesAlone() = test { c, engine, _ ->
        c.addCapture(frame(100, 100))
        engine.failNext(EngineError.Render("boom"), "rectifyAndClean")
        assertFalse(c.setLook(0, ScanLook.GREYSCALE))
        assertEquals(ScanProblem.ENGINE_FAILED, c.state.value.problem)
        assertEquals(ScanLook.COLOUR, c.state.value.pages[0].look)
        c.dismissProblem()
        assertNull(c.state.value.problem)
    }

    @Test
    fun failedCaptureAddsNoPage() = test { c, engine, _ ->
        engine.failNext(EngineError.EngineDied("gone"), "detectDocumentCorners")
        assertFalse(c.addCapture(frame(100, 100)))
        assertTrue(c.state.value.pages.isEmpty())
        assertEquals(ScanProblem.ENGINE_FAILED, c.state.value.problem)
    }

    @Test
    fun cancelStopsTheActionAndKeepsThePages() = test { c, engine, _ ->
        c.addCapture(frame(100, 100))
        engine.hangFor("rectifyAndClean", 10_000)
        val job = launch { c.setLook(0, ScanLook.GREYSCALE) }
        runCurrent()
        assertEquals(ScanBusy.CLEANING, c.state.value.busy)
        c.cancelCurrent()
        runCurrent()
        assertTrue(job.isCompleted)
        assertFalse(job.isCancelled) // only the action was cancelled, not its caller
        assertNull(c.state.value.busy)
        assertNull(c.state.value.problem)
        assertEquals(ScanLook.COLOUR, c.state.value.pages[0].look)
        assertTrue(c.addCapture(frame(100, 100))) // usable afterwards
    }

    @Test
    fun cancellingTheCallerAlsoUnblocksTheController() = test { c, engine, _ ->
        c.addCapture(frame(100, 100))
        engine.hangFor("rectifyAndClean", 10_000)
        val job = launch { c.setLook(0, ScanLook.GREYSCALE) }
        runCurrent()
        job.cancel()
        runCurrent()
        assertNull(c.state.value.busy)
        assertEquals(1, c.state.value.pages.size)
    }

    @Test
    fun secondActionIsIgnoredWhileBusy() = test { c, engine, _ ->
        engine.hangFor("detectDocumentCorners", 1_000)
        val first = launch { c.addCapture(frame(100, 100)) }
        runCurrent()
        assertFalse(c.addCapture(frame(100, 100)))
        advanceTimeBy(1_001)
        runCurrent()
        assertTrue(first.isCompleted)
        assertEquals(1, c.state.value.pages.size)
    }

    @Test
    fun atMostOneFullFrameIsDecodedAtATimeAcrossManyEdits() = test { c, _, codec ->
        repeat(4) { c.addCapture(frame(200, 150)) }
        c.applyLookToAll(ScanLook.BLACK_WHITE, 1)
        c.setQuad(2, Quad(Pt(10f, 10f), Pt(190f, 10f), Pt(190f, 140f), Pt(10f, 140f)))
        c.setLevel(3, 0)
        c.buildPdf()
        assertTrue(codec.decodes >= 6)
        assertEquals(1, codec.maxLive)
        assertEquals(0, codec.live)
    }

    @Test
    fun aFailingEditStillReleasesTheDecodedFrame() = test { c, engine, codec ->
        c.addCapture(frame(100, 100))
        engine.failNext(EngineError.Render("x"), "rectifyAndClean")
        c.setLook(0, ScanLook.GREYSCALE)
        assertEquals(0, codec.live)
    }

    // ---- images to PDF ----

    @Test
    fun importedImagesBecomePagesWithoutAnyEngineWorkOrDecode() = test { c, engine, codec ->
        assertTrue(c.addImage(ImportedImage(jpegWithSize(800, 600), 800, 600)))
        val p = c.state.value.pages[0]
        assertFalse(p.processed)
        assertTrue(p.source === p.result)
        assertEquals(QuadGeometry.fullFrame(800, 600), p.quad)
        assertEquals(0, codec.encodes + codec.decodes)
        val pdf = assertNotNull(c.buildPdf())
        assertEquals(1, engine.pageInfos(engine.open(pdf)).size)
    }

    @Test
    fun importedPageCanStillBeCropped() = test { c, _, codec ->
        c.addImage(ImportedImage(jpegWithSize(200, 100), 200, 100))
        assertTrue(c.setQuad(0, Quad(Pt(20f, 10f), Pt(180f, 10f), Pt(180f, 90f), Pt(20f, 90f))))
        val p = c.state.value.pages[0]
        assertTrue(p.processed)
        assertEquals(160, p.result.width)
        assertEquals(80, p.result.height)
        assertEquals(200, p.source.width)
        assertEquals(1, codec.maxLive)
    }

    @Test
    fun addImagesSkipsUnreadableOnesAndReportsProgress() = test { c, _, _ ->
        val inputs = listOf(1, 2, 3, 4)
        val ok = c.addImages(inputs) { n ->
            when (n) {
                2 -> null
                3 -> ImportedImage(FakeEngine.fakeImage(10, 10), 10, 10) // PNG bytes: refused, only JPEG is stored
                else -> ImportedImage(jpegWithSize(100 * n, 100), 100 * n, 100)
            }
        }
        assertTrue(ok)
        assertEquals(listOf(100, 400), c.state.value.pages.map { it.source.width })
        assertEquals(2, c.state.value.skippedImports)
        assertNull(c.state.value.progress)
    }

    @Test
    fun nonJpegAndHugeImportsAreRefused() = test { c, _, _ ->
        assertFalse(c.addImage(ImportedImage(FakeEngine.fakeImage(10, 10), 10, 10)))
        assertEquals(ScanProblem.UNSUPPORTED_IMAGE, c.state.value.problem)
        assertFalse(c.addImage(ImportedImage(jpegWithSize(8001, 5000), 8001, 5000)))
        assertEquals(ScanProblem.IMAGE_TOO_BIG, c.state.value.problem)
        assertTrue(c.state.value.pages.isEmpty())
    }
}
