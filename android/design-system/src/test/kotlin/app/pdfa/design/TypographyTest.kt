package app.pdfa.design

import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.TextUnitType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TypographyTest {

    @Test
    fun scaleRowsMatchTokens() {
        PdfaType.ordered.forEach { (name, token) ->
            val expected = TokensFile.typography(name)
            assertEquals("$name size", expected.getValue("size"), token.sizeSp, 0f)
            assertEquals("$name line", expected.getValue("line"), token.lineSp, 0f)
            assertEquals("$name weight", expected.getValue("weight").toInt(), token.weight)
            assertEquals("$name tracking", expected.getValue("tracking"), token.trackingSp, 0.0001f)
        }
    }

    @Test
    fun sizesDescendFromDisplayToCaption() {
        val sizes = PdfaType.ordered.map { it.second.sizeSp }
        sizes.zipWithNext().forEach { (larger, smaller) ->
            assertTrue("$larger should be >= $smaller", larger >= smaller)
        }
        assertTrue(PdfaType.display.sizeSp > PdfaType.caption.sizeSp)
    }

    @Test
    fun lineHeightLeavesRoomForTwoHundredPercentFontScale() {
        // Line height is at least 1.25x the size, so text at 200% font scale keeps its line box.
        PdfaType.ordered.forEach { (name, token) ->
            assertTrue(
                "$name line multiplier ${token.lineHeightMultiplier} < 1.25",
                token.lineHeightMultiplier >= 1.25f,
            )
            assertTrue("$name line must be taller than size", token.lineSp > token.sizeSp)
        }
        assertEquals(2.0f, PdfaType.MAX_FONT_SCALE, 0f)
        assertEquals(TokensFile.number("maxFontScale"), PdfaType.MAX_FONT_SCALE, 0f)
    }

    @Test
    fun everyTextStyleUsesSpUnits() {
        val style = PdfaTypography
        listOf(
            style.displaySmall, style.titleLarge, style.titleMedium, style.bodyLarge,
            style.bodyMedium, style.labelLarge, style.labelSmall,
        ).forEach { s ->
            assertEquals(TextUnitType.Sp, s.fontSize.type)
            assertEquals(TextUnitType.Sp, s.lineHeight.type)
            assertEquals(TextUnitType.Sp, s.letterSpacing.type)
        }
    }

    @Test
    fun typographySlotsCarryTheExpectedWeights() {
        assertEquals(FontWeight(600), PdfaTypography.displaySmall.fontWeight)
        assertEquals(FontWeight(400), PdfaTypography.bodyLarge.fontWeight)
        assertEquals(FontWeight(500), PdfaTypography.labelLarge.fontWeight)
    }
}
