package app.pdfa.viewer.ui

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.view.WindowManager
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.window.DialogWindowProvider

private fun Context.findActivity(): Activity? {
    var c: Context? = this
    while (c is ContextWrapper) {
        if (c is Activity) return c
        c = c.baseContext
    }
    return null
}

/**
 * While [enabled], the host Activity's window carries FLAG_SECURE (no screenshots, no recents thumbnail).
 * The previous state of the flag is restored when the effect leaves the composition or [enabled] changes.
 */
@Composable
fun SecureWindowEffect(enabled: Boolean) {
    val activity = LocalContext.current.findActivity()
    DisposableEffect(activity, enabled) {
        val window = activity?.window
        if (window == null || !enabled) {
            onDispose { }
        } else {
            val had = window.attributes.flags and WindowManager.LayoutParams.FLAG_SECURE != 0
            if (!had) window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
            onDispose { if (!had) window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE) }
        }
    }
}

/**
 * Call from INSIDE a bottom sheet / dialog content: the sheet is a separate window that the Activity flag does
 * not cover. Works for a Dialog-backed window and for Material 3's window-backed ModalBottomSheet, without
 * changing the design system.
 */
@Composable
internal fun SecureSheetWindowEffect() {
    val view = LocalView.current
    DisposableEffect(view) {
        val flag = WindowManager.LayoutParams.FLAG_SECURE
        val dialogWindow = (view.parent as? DialogWindowProvider)?.window
        var restore: (() -> Unit)? = null
        if (dialogWindow != null) {
            val had = dialogWindow.attributes.flags and flag != 0
            if (!had) dialogWindow.addFlags(flag)
            restore = { if (!had) dialogWindow.clearFlags(flag) }
        } else {
            val root = view.rootView
            val lp = root.layoutParams as? WindowManager.LayoutParams
            if (lp != null && root.isAttachedToWindow) {
                val had = lp.flags and flag != 0
                if (!had) {
                    lp.flags = lp.flags or flag
                    runCatching { (root.context.getSystemService(Context.WINDOW_SERVICE) as WindowManager).updateViewLayout(root, lp) }
                }
                restore = null // the window goes away with the sheet
            }
        }
        onDispose { restore?.invoke() }
    }
}
