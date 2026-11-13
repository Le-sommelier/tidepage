package app.pdfa.design

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ConstantsTest {

    @Test
    fun minTouchTargetIs48Dp() {
        assertEquals(48.dp, PdfaSpacing.MinTouchTarget)
    }

    @Test
    fun toolTrayItemsAreAtLeastTheMinimumTarget() {
        assertTrue(PdfaSize.ToolTrayItem >= PdfaSpacing.MinTouchTarget)
    }

    @Test
    fun readingSheetIsWhiteInBothThemes() {
        assertEquals(Color.White, PdfaColors.readingSheet)
        assertEquals(Color.White, PdfaColors.lightExtended.pageSheet)
        assertEquals(Color.White, PdfaColors.darkExtended.pageSheet)
        assertEquals(PdfaColors.readingSheet, PdfaColors.extended(darkTheme = true).pageSheet)
    }

    @Test
    fun readingSheetDoesNotFollowTheDarkScheme() {
        // The dark scheme's canvas is not white, so the sheet must not be taken from it.
        assertFalse(PdfaColors.dark.background == Color.White)
        assertEquals(Color.White, PdfaColors.readingSheet)
    }

    @Test
    fun reducedMotionFlagIsSetOnlyForZeroScale() {
        assertTrue(PdfaMotion.isReducedMotionScale(0f))
        assertFalse(PdfaMotion.isReducedMotionScale(1f))
        assertFalse(PdfaMotion.isReducedMotionScale(0.5f))
    }

    @Test
    fun reducedMotionZeroesDurationsExceptCrossFade() {
        assertEquals(PdfaMotion.MEDIUM_MS, PdfaMotion.durationMs(PdfaMotion.MEDIUM_MS, reduced = false))
        assertEquals(0, PdfaMotion.durationMs(PdfaMotion.MEDIUM_MS, reduced = true))
        assertEquals(0, PdfaMotion.durationMs(PdfaMotion.LONG_MS, reduced = true))
        assertEquals(80, PdfaMotion.durationMs(PdfaMotion.SHORT_MS, reduced = true, opacityCrossFade = true))
    }

    @Test
    fun motionDurationsMatchTokens() {
        assertEquals(TokensFile.number("instant").toInt(), PdfaMotion.INSTANT_MS)
        assertEquals(TokensFile.number("short").toInt(), PdfaMotion.SHORT_MS)
        assertEquals(TokensFile.number("medium").toInt(), PdfaMotion.MEDIUM_MS)
        assertEquals(TokensFile.number("long").toInt(), PdfaMotion.LONG_MS)
    }

    @Test
    fun spacingScaleMatchesTokensOnThe4dpGrid() {
        val tokens = TokensFile.spacingScale()
        assertEquals(tokens, PdfaSpacing.Scale.map { it.value })
        val values = PdfaSpacing.Scale.map { it.value }
        assertEquals(values.sorted(), values)
        assertEquals(values.distinct(), values)
        values.drop(1).forEach { assertEquals(0f, it % 4f, 0f) }
    }

    @Test
    fun sizesAndRadiiMatchTokens() {
        assertEquals(TokensFile.number("topBar").dp, PdfaSize.TopBar)
        assertEquals(TokensFile.number("toolTrayItem").dp, PdfaSize.ToolTrayItem)
        assertEquals(TokensFile.number("toolTrayHeight").dp, PdfaSize.ToolTrayHeight)
        assertEquals(TokensFile.number("thumbnailMinWidth").dp, PdfaSize.ThumbnailMinWidth)
        assertEquals(TokensFile.number("minTouchTarget").dp, PdfaSpacing.MinTouchTarget)
        assertEquals(TokensFile.number("xs").dp, PdfaRadius.Xs)
        assertEquals(TokensFile.number("sm").dp, PdfaRadius.Sm)
        assertEquals(TokensFile.number("md").dp, PdfaRadius.Md)
        assertEquals(TokensFile.number("lg").dp, PdfaRadius.Lg)
        assertEquals(TokensFile.number("xl").dp, PdfaRadius.Xl)
    }

    @Test
    fun gutterValuesMatchTokens() {
        assertEquals(TokensFile.number("gutterPhone").dp, PdfaSpacing.GutterPhone)
        assertEquals(TokensFile.number("gutterTablet").dp, PdfaSpacing.GutterTablet)
    }

    @Test
    fun dpValuesAreNonNegative() {
        val all: List<Dp> = PdfaSpacing.Scale + listOf(PdfaSize.TopBar, PdfaSize.ToolTrayHeight)
        all.forEach { assertTrue(it.value >= 0f) }
    }
}
