package app.pdfa.design

import androidx.compose.material3.ColorScheme
import androidx.compose.ui.graphics.Color
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.pow

/** WCAG 2.x contrast ratio between two opaque colours. */
internal fun contrastRatio(a: Color, b: Color): Double {
    fun linear(c: Float): Double {
        val v = c.toDouble()
        return if (v <= 0.03928) v / 12.92 else ((v + 0.055) / 1.055).pow(2.4)
    }
    fun luminance(c: Color) = 0.2126 * linear(c.red) + 0.7152 * linear(c.green) + 0.0722 * linear(c.blue)
    val la = luminance(a)
    val lb = luminance(b)
    val (hi, lo) = if (la > lb) la to lb else lb to la
    return (hi + 0.05) / (lo + 0.05)
}

class ContrastTest {

    private data class Pair(val name: String, val text: Color, val background: Color)

    /** Text pairs declared in DESIGN.md section 5 and tokens.json contrast, plus derived roles. */
    private fun textPairs(scheme: ColorScheme, extended: PdfaExtendedColors, theme: String): List<Pair> = listOf(
        Pair("$theme ink on canvas", scheme.onBackground, scheme.background),
        Pair("$theme ink on surface", scheme.onSurface, scheme.surface),
        Pair("$theme inkMuted on canvas", scheme.secondary, scheme.background),
        Pair("$theme inkMuted on surface", scheme.onSurfaceVariant, scheme.surfaceVariant),
        Pair("$theme onAccent on accent (primary button)", scheme.onPrimary, scheme.primary),
        Pair("$theme accent as text on canvas", scheme.primary, scheme.background),
        Pair("$theme accent as text on surface", scheme.primary, scheme.surface),
        Pair("$theme onAccentContainer on accentContainer", scheme.onPrimaryContainer, scheme.primaryContainer),
        Pair("$theme danger on canvas", scheme.error, scheme.background),
        Pair("$theme onError on danger", scheme.onError, scheme.error),
        Pair("$theme danger text on errorContainer", scheme.onErrorContainer, scheme.errorContainer),
        Pair("$theme warning on canvas", extended.warning, scheme.background),
        Pair("$theme ink on highlight", scheme.onBackground, extended.highlight),
        Pair("$theme snackbar text on snackbar", scheme.inverseOnSurface, scheme.inverseSurface),
        Pair("$theme snackbar action on snackbar", scheme.inversePrimary, scheme.inverseSurface),
    )

    private fun assertAll(pairs: List<Pair>) {
        val failures = mutableListOf<String>()
        pairs.forEach { p ->
            val ratio = contrastRatio(p.text, p.background)
            println("CONTRAST %-46s %6.2f:1".format(p.name, ratio))
            if (ratio < 4.5) failures += "${p.name} = ${"%.2f".format(ratio)}"
        }
        assertTrue("Below 4.5:1: $failures", failures.isEmpty())
    }

    @Test
    fun lightTextPairsMeetAa() {
        assertAll(textPairs(PdfaColors.light, PdfaColors.lightExtended, "light"))
    }

    @Test
    fun darkTextPairsMeetAa() {
        assertAll(textPairs(PdfaColors.dark, PdfaColors.darkExtended, "dark"))
    }

    /** DESIGN.md section 5 calls outline a non-text 3:1 colour; both themes must meet it. */
    @Test
    fun outlineMeetsThreeToOneNonText() {
        val light = contrastRatio(PdfaColors.light.outline, PdfaColors.light.background)
        val dark = contrastRatio(PdfaColors.dark.outline, PdfaColors.dark.background)
        println("CONTRAST %-46s %6.2f:1 (non-text)".format("light outline on canvas", light))
        println("CONTRAST %-46s %6.2f:1 (non-text)".format("dark outline on canvas", dark))
        assertTrue("light outline is $light", light >= 3.0)
        assertTrue("dark outline is $dark", dark >= 3.0)
    }
}
