package app.pdfa.design

import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** Spacing on the 4 dp grid from tokens.json `spacing.scale`. */
object PdfaSpacing {
    val S0: Dp = 0.dp
    val S1: Dp = 4.dp
    val S2: Dp = 8.dp
    val S3: Dp = 12.dp
    val S4: Dp = 16.dp
    val S5: Dp = 24.dp
    val S6: Dp = 32.dp
    val S7: Dp = 48.dp

    /** The whole grid, ascending. */
    val Scale: List<Dp> = listOf(S0, S1, S2, S3, S4, S5, S6, S7)

    val GutterPhone: Dp = 16.dp
    val GutterTablet: Dp = 24.dp

    /** Smallest interactive target (WCAG 2.5.5 / Material). */
    val MinTouchTarget: Dp = 48.dp
}

/** Fixed sizes from tokens.json `size`. */
object PdfaSize {
    val TopBar: Dp = 64.dp
    val ToolTrayItem: Dp = 56.dp
    val ToolTrayHeight: Dp = 72.dp
    val ThumbnailMinWidth: Dp = 104.dp
    val Fab: Dp = 56.dp
    val IconDefault: Dp = 24.dp

    /** Icon stroke weight from the design doc (outline icons, 1.75). Informational for icon artwork. */
    const val IconStroke: Float = 1.75f
}
