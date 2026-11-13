package app.pdfa.viewer

import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.pdfa.design.PdfaTheme
import app.pdfa.engine.fake.FakeEngine
import app.pdfa.viewer.ui.ViewerScreen
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Drives the password sheet, its error state, FLAG_SECURE and the light theme on the emulator. */
@RunWith(AndroidJUnit4::class)
class ViewerPasswordTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    private val dir: File = InstrumentationRegistry.getInstrumentation().targetContext
        .getExternalFilesDir(null)!!.also { it.mkdirs() }

    private fun vm(password: String?, pages: Int = 30): ViewerViewModel {
        val bytes = FakeEngine.fakePdf(pageCount = pages, width = 612f, height = 792f, password = password)
        return ViewerViewModel(
            FakeEngine(),
            object : DocumentSource {
                override suspend fun read() = DocumentBytes(bytes, "protected.pdf")
            },
        )
    }

    private fun activityIsSecure() =
        rule.activity.window.attributes.flags and WindowManager.LayoutParams.FLAG_SECURE != 0

    /** Every window of this app: the activity and the bottom sheet. */
    private fun secureWindowCount(): Int {
        var n = 0
        rule.runOnUiThread {
            val views = android.view.inspector.WindowInspector.getGlobalWindowViews()
            for (v in views) {
                val lp = v.layoutParams as? WindowManager.LayoutParams ?: continue
                if (lp.flags and WindowManager.LayoutParams.FLAG_SECURE != 0) n++
            }
        }
        return n
    }

    @Test
    fun passwordSheetShowsErrorsAndIsSecure() {
        val vm = vm("secret")
        rule.setContent { PdfaTheme(darkTheme = false) { ViewerScreen(vm, onBack = {}) } }
        rule.waitUntil(10_000) { vm.state.value is ViewerState.PasswordRequired }
        rule.waitUntil(5_000) { rule.onAllNodesWithText("Open").fetchSemanticsNodes().isNotEmpty() }
        rule.waitForIdle()
        val report = StringBuilder()
        report.appendLine("sheet shown: activitySecure=${activityIsSecure()} secureWindows=${secureWindowCount()}")
        assertTrue("activity window must be secure while the password sheet is up", activityIsSecure())
        assertTrue("the sheet window must be secure too", secureWindowCount() >= 2)

        rule.onNode(hasSetTextAction()).performTextInput("wrong")
        rule.onNodeWithText("Open").performClick()
        rule.waitUntil(10_000) { vm.state.value == ViewerState.PasswordRequired(2, lastAttemptWrong = true) }
        rule.waitUntil(5_000) {
            rule.onAllNodesWithText("Wrong password. Try again. 2 left.").fetchSemanticsNodes().isNotEmpty()
        }
        report.appendLine("error state: ${vm.state.value}")
        Thread.sleep(500)
        // A secure window is blanked by the system, so this screenshot is expected to be black or empty.
        val shot = InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
        if (shot != null) {
            File(dir, "password-sheet-secure.png").outputStream().use { shot.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
            val px = shot.getPixel(shot.width / 2, shot.height - 200)
            report.appendLine("secure screenshot centre-bottom pixel=0x%08x".format(px))
        } else {
            report.appendLine("uiAutomation screenshot returned null (secure window)")
        }

        rule.onNode(hasSetTextAction()).performTextInput("secret")
        rule.onNodeWithText("Open").performClick()
        rule.waitUntil(10_000) { vm.state.value is ViewerState.Ready }
        rule.waitForIdle()
        report.appendLine("after unlock: activitySecure=${activityIsSecure()}")
        assertEquals("flag restored once the sheet is gone", false, activityIsSecure())
        File(dir, "password-report.txt").writeText(report.toString())
    }

    @Test
    fun lockedAfterThreeWrongPasswords() {
        val vm = vm("secret")
        rule.setContent { PdfaTheme(darkTheme = false) { ViewerScreen(vm, onBack = {}) } }
        rule.waitUntil(10_000) { vm.state.value is ViewerState.PasswordRequired }
        repeat(3) { i ->
            rule.waitUntil(5_000) { rule.onAllNodesWithText("Open").fetchSemanticsNodes().isNotEmpty() }
            rule.onNode(hasSetTextAction()).performTextInput("bad$i")
            rule.onNodeWithText("Open").performClick()
            rule.waitUntil(10_000) { vm.state.value !is ViewerState.PasswordRequired || (vm.state.value as ViewerState.PasswordRequired).attemptsLeft == 2 - i }
        }
        rule.waitUntil(10_000) { vm.state.value == ViewerState.Locked }
        rule.waitUntil(5_000) { rule.onAllNodesWithText("Too many wrong passwords").fetchSemanticsNodes().isNotEmpty() }
        rule.waitForIdle()
        File(dir, "locked.png").outputStream().use {
            rule.onRoot().captureToImage().asAndroidBitmap().compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
        }
        check(!activityIsSecure()) { "no secure flag once locked" }
    }

    @Test
    fun lightThemeViewer() {
        val vm = vm(null, pages = 300)
        rule.setContent { PdfaTheme(darkTheme = false) { ViewerScreen(vm, onBack = {}) } }
        rule.waitUntil(20_000) {
            val sched = vm.tileScheduler
            vm.state.value is ViewerState.Ready && vm.view.value.plan.tiles.isNotEmpty() &&
                sched.inFlightCount == 0 && sched.pendingCount == 0
        }
        Thread.sleep(800)
        rule.waitForIdle()
        File(dir, "viewer-4-light.png").outputStream().use {
            rule.onRoot().captureToImage().asAndroidBitmap().compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
        }
    }
}
