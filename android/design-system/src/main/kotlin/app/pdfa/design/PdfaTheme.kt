package app.pdfa.design

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.platform.LocalContext

/** Extended colours (warning, highlight, reading sheet, annotation inks) for the current theme. */
val LocalPdfaExtendedColors = staticCompositionLocalOf { PdfaColors.lightExtended }

/**
 * Pdfa Material 3 theme. The static Tidewater palette is the default; dynamic colour is opt-in
 * and only applies on Android 12 (API 31) and later.
 */
@Composable
fun PdfaTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = false,
    content: @Composable () -> Unit,
) {
    val colorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val context = LocalContext.current
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }
        else -> PdfaColors.scheme(darkTheme)
    }
    CompositionLocalProvider(
        LocalPdfaExtendedColors provides PdfaColors.extended(darkTheme),
    ) {
        MaterialTheme(
            colorScheme = colorScheme,
            typography = PdfaTypography,
            shapes = PdfaShapes,
            content = content,
        )
    }
}
