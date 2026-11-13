package app.pdfa.scan

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SizingTest {
    @Test
    fun smallImageIsNeverUpscaled() {
        assertEquals(PixelSize(800, 600), Sizing.fit(800, 600, 3300, 8_000_000))
    }

    @Test
    fun exactlyAtTheLimitsIsUntouched() {
        assertEquals(PixelSize(3300, 2000), Sizing.fit(3300, 2000, 3300, 8_000_000)) // 6.6 Mpx
        assertEquals(PixelSize(4000, 2000), Sizing.fit(4000, 2000, 4000, 8_000_000)) // exactly 8 Mpx
    }

    @Test
    fun longEdgeCapKeepsAspect() {
        assertEquals(PixelSize(1000, 500), Sizing.fit(4000, 2000, 1000, 100_000_000))
        assertEquals(PixelSize(500, 1000), Sizing.fit(2000, 4000, 1000, 100_000_000))
    }

    @Test
    fun megapixelCapKeepsAspectAndStaysUnderTheCap() {
        val s = Sizing.fit(4000, 3000, 10_000, 4_000_000)
        assertTrue(s.pixels <= 4_000_000)
        assertTrue(s.pixels > 3_900_000)
        assertEquals(4.0 / 3.0, s.width.toDouble() / s.height, 0.01)
    }

    @Test
    fun theTighterOfTheTwoCapsWins() {
        val s = Sizing.fit(8000, 6000, 3300, 8_000_000)
        // Long edge alone gives 3300 x 2475 = 8.17 Mpx, over the pixel cap, so the pixel cap decides.
        assertTrue(s.width < 3300)
        assertTrue(s.pixels <= 8_000_000)
        assertTrue(s.pixels > 7_900_000)
    }

    @Test
    fun thinImageNeverCollapsesBelowOnePixel() {
        val s = Sizing.fit(100_000, 1, 1000, 1_000_000)
        assertEquals(PixelSize(1000, 1), s)
        assertTrue(Sizing.fit(1, 1, 1, 1).pixels == 1L)
    }

    @Test
    fun hardCapBoundary() {
        assertFalse(Sizing.exceedsHardCap(8000, 5000)) // exactly 40,000,000
        assertTrue(Sizing.exceedsHardCap(8000, 5001))
        assertFalse(Sizing.exceedsHardCap(0, 0))
        assertTrue(Sizing.exceedsHardCap(65_536, 65_536)) // no Int overflow
    }

    @Test
    fun sampleSizeIsAPowerOfTwoAndKeepsEnoughPixels() {
        assertEquals(1, Sizing.sampleSize(800, 600, 3300, 8_000_000))
        val s = Sizing.sampleSize(8000, 6000, 3300, 8_000_000)
        assertEquals(2, s) // 4000 x 3000 still covers the 3300 x 2475 target
        assertEquals(4, Sizing.sampleSize(16_000, 12_000, 3300, 8_000_000))
        assertEquals(1, Sizing.sampleSize(3400, 2000, 3300, 8_000_000))
    }

    @Test
    fun dpiMapsTheLargestLongEdgeToA4() {
        assertEquals(300.0, Sizing.pdfDpi(listOf(PixelSize(2480, 3508))), 0.2)
        assertEquals(300.0, Sizing.pdfDpi(listOf(PixelSize(3508, 2480), PixelSize(1000, 1000))), 0.2)
    }

    @Test
    fun dpiIsClampedToTheEnginesRange() {
        assertEquals(50.0, Sizing.pdfDpi(listOf(PixelSize(10, 10))))
        assertEquals(1200.0, Sizing.pdfDpi(listOf(PixelSize(60_000, 100))))
        assertEquals(300.0, Sizing.pdfDpi(emptyList()))
    }
}
