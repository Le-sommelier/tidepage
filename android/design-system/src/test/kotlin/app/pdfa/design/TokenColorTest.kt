package app.pdfa.design

import androidx.compose.material3.ColorScheme
import androidx.compose.ui.graphics.Color
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Every colour in the Material 3 schemes must equal the token it is mapped from in
 * docs/design/tokens.json. Derived roles (no token of their own) are listed with the token they reuse.
 */
class TokenColorTest {

    /** Scheme slot to tokens.json key, per theme. Keys are in color.<theme>. */
    private fun mapping(scheme: ColorScheme, dark: Boolean): List<Triple<String, Color, String>> {
        val inverse = if (dark) "light" else "dark"
        return listOf(
            Triple("primary", scheme.primary, "accent"),
            Triple("onPrimary", scheme.onPrimary, "onAccent"),
            Triple("primaryContainer", scheme.primaryContainer, "accentContainer"),
            Triple("onPrimaryContainer", scheme.onPrimaryContainer, "onAccentContainer"),
            Triple("secondary", scheme.secondary, "inkMuted"),
            Triple("onSecondary", scheme.onSecondary, "canvas"),
            Triple("secondaryContainer", scheme.secondaryContainer, "surfaceHigh"),
            Triple("onSecondaryContainer", scheme.onSecondaryContainer, "ink"),
            Triple("tertiary", scheme.tertiary, "accent"),
            Triple("onTertiary", scheme.onTertiary, "onAccent"),
            Triple("tertiaryContainer", scheme.tertiaryContainer, "accentContainer"),
            Triple("onTertiaryContainer", scheme.onTertiaryContainer, "onAccentContainer"),
            Triple("background", scheme.background, "canvas"),
            Triple("onBackground", scheme.onBackground, "ink"),
            Triple("surface", scheme.surface, "surface"),
            Triple("onSurface", scheme.onSurface, "ink"),
            Triple("surfaceVariant", scheme.surfaceVariant, "surfaceHigh"),
            Triple("onSurfaceVariant", scheme.onSurfaceVariant, "inkMuted"),
            Triple("surfaceTint", scheme.surfaceTint, "accent"),
            Triple("inverseSurface", scheme.inverseSurface, "ink"),
            Triple("inverseOnSurface", scheme.inverseOnSurface, "canvas"),
            Triple("inversePrimary", scheme.inversePrimary, "accent@$inverse"),
            Triple("error", scheme.error, "danger"),
            Triple("onError", scheme.onError, "canvas"),
            Triple("errorContainer", scheme.errorContainer, "surfaceHigh"),
            Triple("onErrorContainer", scheme.onErrorContainer, "danger"),
            Triple("outline", scheme.outline, "outline"),
            Triple("outlineVariant", scheme.outlineVariant, "hairline"),
            Triple("scrim", scheme.scrim, "scrim"),
            Triple("surfaceBright", scheme.surfaceBright, "surfaceHigh"),
            Triple("surfaceDim", scheme.surfaceDim, "canvas"),
            Triple("surfaceContainerLowest", scheme.surfaceContainerLowest, "canvas"),
            Triple("surfaceContainerLow", scheme.surfaceContainerLow, "surface"),
            Triple("surfaceContainer", scheme.surfaceContainer, "surface"),
            Triple("surfaceContainerHigh", scheme.surfaceContainerHigh, "surfaceHigh"),
            Triple("surfaceContainerHighest", scheme.surfaceContainerHighest, "surfaceHigh"),
        )
    }

    private fun assertSchemeMatchesTokens(scheme: ColorScheme, dark: Boolean) {
        val theme = if (dark) "dark" else "light"
        mapping(scheme, dark).forEach { (slot, actual, key) ->
            val expected = if (key.contains('@')) {
                // "accent@dark" means the dark theme's accent, used for the opposite theme's inverse role.
                val (name, source) = key.split('@')
                TokensFile.parse(TokensFile.themeColor(source, name))
            } else {
                TokensFile.parse(TokensFile.themeColor(theme, key))
            }
            assertEquals("$theme $slot should equal token $key", expected, actual)
        }
    }

    @Test
    fun lightSchemeEqualsTokens() = assertSchemeMatchesTokens(PdfaColors.light, dark = false)

    @Test
    fun darkSchemeEqualsTokens() = assertSchemeMatchesTokens(PdfaColors.dark, dark = true)

    @Test
    fun extendedColoursEqualTokens() {
        val light = PdfaColors.lightExtended
        val dark = PdfaColors.darkExtended
        assertEquals(TokensFile.parse(TokensFile.themeColor("light", "warning")), light.warning)
        assertEquals(TokensFile.parse(TokensFile.themeColor("dark", "warning")), dark.warning)
        assertEquals(TokensFile.parse(TokensFile.themeColor("light", "highlight")), light.highlight)
        assertEquals(TokensFile.parse(TokensFile.themeColor("dark", "highlight")), dark.highlight)
        assertEquals(TokensFile.parse(TokensFile.themeColor("light", "hairline")), light.hairline)
        assertEquals(TokensFile.parse(TokensFile.themeColor("dark", "hairline")), dark.hairline)
        assertEquals(TokensFile.parse(TokensFile.globalColor("pageSheet")), light.pageSheet)
        assertEquals(TokensFile.parse(TokensFile.globalColor("pageSheet")), dark.pageSheet)
        val annotation = listOf("#14201F", "#0B6E6B", "#B3261E", "#1F5FBF").map { TokensFile.parse(it) }
        assertEquals(annotation, light.annotationInk)
        assertEquals(annotation, dark.annotationInk)
    }
}
