package app.pdfa.design

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/**
 * One row of the type scale from tokens.json `typography.scale`. Sizes are sp so the user's
 * font scale applies. [lineHeightMultiplier] is exposed so layouts can check that a 200% font
 * scale still fits without clipping (line height is always at least 1.25 times the size).
 */
data class PdfaTextToken(
    val sizeSp: Float,
    val lineSp: Float,
    val weight: Int,
    val trackingSp: Float,
) {
    val lineHeightMultiplier: Float get() = lineSp / sizeSp

    fun toTextStyle(): TextStyle = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight(weight),
        fontSize = sizeSp.sp,
        lineHeight = lineSp.sp,
        letterSpacing = trackingSp.sp,
    )
}

object PdfaType {
    /** tokens.json typography.maxFontScale. Layouts must hold up at this scale. */
    const val MAX_FONT_SCALE = 2.0f

    val display = PdfaTextToken(32f, 40f, 600, 0f)
    val title = PdfaTextToken(22f, 28f, 600, 0f)
    val titleSmall = PdfaTextToken(16f, 24f, 600, 0.1f)
    val body = PdfaTextToken(16f, 24f, 400, 0.2f)
    val bodySmall = PdfaTextToken(14f, 20f, 400, 0.2f)
    val label = PdfaTextToken(14f, 20f, 500, 0.1f)
    val caption = PdfaTextToken(12f, 16f, 500, 0.4f)

    /** Largest to smallest, the order used in tokens.json and DESIGN.md. */
    val ordered: List<Pair<String, PdfaTextToken>> = listOf(
        "display" to display,
        "title" to title,
        "titleSmall" to titleSmall,
        "body" to body,
        "bodySmall" to bodySmall,
        "label" to label,
        "caption" to caption,
    )
}

/** Material 3 slots filled from [PdfaType]; every slot is set so no default Roboto sizes leak in. */
val PdfaTypography = Typography(
    displayLarge = PdfaType.display.toTextStyle(),
    displayMedium = PdfaType.display.toTextStyle(),
    displaySmall = PdfaType.display.toTextStyle(),
    headlineLarge = PdfaType.display.toTextStyle(),
    headlineMedium = PdfaType.title.toTextStyle(),
    headlineSmall = PdfaType.title.toTextStyle(),
    titleLarge = PdfaType.title.toTextStyle(),
    titleMedium = PdfaType.titleSmall.toTextStyle(),
    titleSmall = PdfaType.titleSmall.toTextStyle(),
    bodyLarge = PdfaType.body.toTextStyle(),
    bodyMedium = PdfaType.bodySmall.toTextStyle(),
    bodySmall = PdfaType.bodySmall.toTextStyle(),
    labelLarge = PdfaType.label.toTextStyle(),
    labelMedium = PdfaType.caption.toTextStyle(),
    labelSmall = PdfaType.caption.toTextStyle(),
)
