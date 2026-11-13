package app.pdfa.design

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Shapes
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** Corner radii from tokens.json `radius`. */
object PdfaRadius {
    val Xs: Dp = 4.dp
    val Sm: Dp = 8.dp
    val Md: Dp = 12.dp
    val Lg: Dp = 16.dp
    val Xl: Dp = 28.dp
    val Full: Dp = 999.dp
}

/** Material 3 shape slots mapped to the radius scale. */
val PdfaShapes = Shapes(
    extraSmall = RoundedCornerShape(PdfaRadius.Xs),
    small = RoundedCornerShape(PdfaRadius.Sm),
    medium = RoundedCornerShape(PdfaRadius.Md),
    large = RoundedCornerShape(PdfaRadius.Lg),
    extraLarge = RoundedCornerShape(PdfaRadius.Xl),
)

/** Pill shape for selected tray items and chips. */
val PdfaFullShape = RoundedCornerShape(PdfaRadius.Full)
