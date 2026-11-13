package app.pdfa.design

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color

/**
 * Raw Tidewater palette, one instance per theme. Values are copied from docs/design/tokens.json
 * (color.light / color.dark); TokenColorTest fails if they drift.
 */
data class PdfaPalette(
    val canvas: Color,
    val surface: Color,
    val surfaceHigh: Color,
    val ink: Color,
    val inkMuted: Color,
    val outline: Color,
    val hairline: Color,
    val accent: Color,
    val onAccent: Color,
    val accentContainer: Color,
    val onAccentContainer: Color,
    val danger: Color,
    val warning: Color,
    val highlight: Color,
    val scrim: Color,
) {
    companion object {
        val Light = PdfaPalette(
            canvas = Color(0xFFFFFFFF),
            surface = Color(0xFFF4F7F7),
            surfaceHigh = Color(0xFFE8EEEE),
            ink = Color(0xFF14201F),
            inkMuted = Color(0xFF4A5958),
            outline = Color(0xFF829291),
            hairline = Color(0xFFDCE4E3),
            accent = Color(0xFF0B6E6B),
            onAccent = Color(0xFFFFFFFF),
            accentContainer = Color(0xFFE0F2F1),
            onAccentContainer = Color(0xFF064B49),
            danger = Color(0xFFB3261E),
            warning = Color(0xFF8A5A00),
            highlight = Color(0xFFFFF3B0),
            scrim = Color(0x6614201F),
        )

        val Dark = PdfaPalette(
            canvas = Color(0xFF0F1514),
            surface = Color(0xFF1A2322),
            surfaceHigh = Color(0xFF243130),
            ink = Color(0xFFE6EDEC),
            inkMuted = Color(0xFFA9B8B7),
            outline = Color(0xFF6F8180),
            hairline = Color(0xFF2E3C3B),
            accent = Color(0xFF5CCFC9),
            onAccent = Color(0xFF00201F),
            accentContainer = Color(0xFF12302F),
            onAccentContainer = Color(0xFF9FE8E3),
            danger = Color(0xFFFFB4AB),
            warning = Color(0xFFFFC857),
            highlight = Color(0xFF6B5200),
            scrim = Color(0xA6000000),
        )
    }
}

/** Colours that have no Material 3 role. Provided through [LocalPdfaExtendedColors]. */
data class PdfaExtendedColors(
    val warning: Color,
    val highlight: Color,
    val hairline: Color,
    /** Always white: the reading sheet never changes with the theme. */
    val pageSheet: Color,
    val annotationInk: List<Color>,
)

object PdfaColors {
    /** The page the document is drawn on. White in light and dark themes. */
    val readingSheet: Color = Color(0xFFFFFFFF)

    /** Annotation ink colours, identical in both themes (tokens.json color.annotationInk). */
    val annotationInk: List<Color> = listOf(
        Color(0xFF14201F),
        Color(0xFF0B6E6B),
        Color(0xFFB3261E),
        Color(0xFF1F5FBF),
    )

    val light: ColorScheme = buildScheme(PdfaPalette.Light, PdfaPalette.Dark, dark = false)
    val dark: ColorScheme = buildScheme(PdfaPalette.Dark, PdfaPalette.Light, dark = true)

    val lightExtended: PdfaExtendedColors = extended(PdfaPalette.Light)
    val darkExtended: PdfaExtendedColors = extended(PdfaPalette.Dark)

    fun scheme(darkTheme: Boolean): ColorScheme = if (darkTheme) dark else light

    fun extended(darkTheme: Boolean): PdfaExtendedColors =
        if (darkTheme) darkExtended else lightExtended

    private fun extended(p: PdfaPalette) = PdfaExtendedColors(
        warning = p.warning,
        highlight = p.highlight,
        hairline = p.hairline,
        pageSheet = readingSheet,
        annotationInk = annotationInk,
    )

    /**
     * Maps tokens onto Material 3 roles. [other] is the opposite theme; the inverse primary
     * (snackbar action colour) comes from it so the action reads on the inverse surface.
     * Roles without a token of their own reuse the closest token; TokenColorTest lists the mapping.
     */
    private fun buildScheme(p: PdfaPalette, other: PdfaPalette, dark: Boolean): ColorScheme {
        val base = if (dark) darkColorScheme() else lightColorScheme()
        return base.copy(
            primary = p.accent,
            onPrimary = p.onAccent,
            primaryContainer = p.accentContainer,
            onPrimaryContainer = p.onAccentContainer,
            inversePrimary = other.accent,
            secondary = p.inkMuted,
            onSecondary = p.canvas,
            secondaryContainer = p.surfaceHigh,
            onSecondaryContainer = p.ink,
            tertiary = p.accent,
            onTertiary = p.onAccent,
            tertiaryContainer = p.accentContainer,
            onTertiaryContainer = p.onAccentContainer,
            background = p.canvas,
            onBackground = p.ink,
            surface = p.surface,
            onSurface = p.ink,
            surfaceVariant = p.surfaceHigh,
            onSurfaceVariant = p.inkMuted,
            surfaceTint = p.accent,
            inverseSurface = p.ink,
            inverseOnSurface = p.canvas,
            error = p.danger,
            onError = p.canvas,
            errorContainer = p.surfaceHigh,
            onErrorContainer = p.danger,
            outline = p.outline,
            outlineVariant = p.hairline,
            scrim = p.scrim,
            surfaceBright = p.surfaceHigh,
            surfaceDim = p.canvas,
            surfaceContainerLowest = p.canvas,
            surfaceContainerLow = p.surface,
            surfaceContainer = p.surface,
            surfaceContainerHigh = p.surfaceHigh,
            surfaceContainerHighest = p.surfaceHigh,
        )
    }
}
