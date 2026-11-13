package app.pdfa.design

import android.content.Context
import android.provider.Settings
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext

/** Durations and easing from tokens.json `motion`. */
object PdfaMotion {
    const val INSTANT_MS: Int = 80
    const val SHORT_MS: Int = 150
    const val MEDIUM_MS: Int = 250
    const val LONG_MS: Int = 350

    /** Opacity cross-fade length kept under reduced motion (tokens.json motion.reducedMotion). */
    const val CROSS_FADE_MS: Int = 80

    val Standard = CubicBezierEasing(0.2f, 0f, 0f, 1f)
    val Decelerate = CubicBezierEasing(0f, 0f, 0f, 1f)
    val Accelerate = CubicBezierEasing(0.3f, 0f, 1f, 1f)

    /** True when the user has turned animations off (animator duration scale is 0). */
    fun isReducedMotionScale(animatorDurationScale: Float): Boolean = animatorDurationScale == 0f

    /** Reads Settings.Global.ANIMATOR_DURATION_SCALE; 1 when the setting is missing. */
    fun isReducedMotion(context: Context): Boolean = isReducedMotionScale(
        Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f),
    )

    /**
     * Duration to use for a transition. Under reduced motion every duration is 0 except an
     * opacity cross-fade, which keeps [CROSS_FADE_MS].
     */
    fun durationMs(baseMs: Int, reduced: Boolean, opacityCrossFade: Boolean = false): Int = when {
        !reduced -> baseMs
        opacityCrossFade -> CROSS_FADE_MS
        else -> 0
    }
}

/** Composable read of the reduced-motion flag for the current context. */
@Composable
fun rememberReducedMotion(): Boolean = PdfaMotion.isReducedMotion(LocalContext.current)
