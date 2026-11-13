package app.pdfa.viewer

import android.view.Choreographer
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.doubleClick
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.pinch
import androidx.compose.ui.test.swipeUp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.pdfa.design.PdfaTheme
import app.pdfa.engine.fake.FakeEngine
import app.pdfa.viewer.ui.ViewerScreen
import java.io.File
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Hosts [ViewerScreen] over a seeded 300-page fake document and drives it with real gestures. */
@RunWith(AndroidJUnit4::class)
class ViewerEmulatorTest {
    @get:Rule val rule = createComposeRule()

    private val dir: File = InstrumentationRegistry.getInstrumentation().targetContext
        .getExternalFilesDir(null)!!.also { it.mkdirs() }
    private val report = StringBuilder()

    private class FrameMeter : Choreographer.FrameCallback {
        val intervalsMs = ArrayList<Double>()
        private var last = 0L
        @Volatile private var running = false
        override fun doFrame(frameTimeNanos: Long) {
            if (last != 0L) intervalsMs += (frameTimeNanos - last) / 1e6
            last = frameTimeNanos
            if (running) Choreographer.getInstance().postFrameCallback(this)
        }
        fun start() { running = true; last = 0L; intervalsMs.clear(); Choreographer.getInstance().postFrameCallback(this) }
        fun stop() { running = false }
        fun summary(label: String): String {
            val sorted = intervalsMs.sorted()
            if (sorted.isEmpty()) return "$label: no frames"
            val p95 = sorted[(sorted.size * 0.95).toInt().coerceAtMost(sorted.size - 1)]
            val janky = sorted.count { it > 32.0 }
            return "$label: frames=${sorted.size} p95=%.1fms max=%.1fms over32ms=$janky".format(p95, sorted.last())
        }
    }

    private fun screenshot(name: String) {
        val bmp = rule.onRoot().captureToImage().asAndroidBitmap()
        File(dir, "$name.png").outputStream().use { bmp.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
    }

    /** Every planned tile was rendered (the byte cache may have evicted some: bitmaps hold them). */
    private fun sharpTilesReady(vm: ViewerViewModel): Boolean {
        val sched = vm.tileScheduler
        return vm.view.value.plan.tiles.isNotEmpty() && sched.inFlightCount == 0 && sched.pendingCount == 0 &&
            sched.backoffCount == 0
    }

    @Test
    fun scrollJumpAndZoom() {
        val bytes = FakeEngine.fakePdf(pageCount = 300, width = 612f, height = 792f, title = "Seeded")
        val vm = ViewerViewModel(
            FakeEngine(),
            object : DocumentSource {
                override suspend fun read() = DocumentBytes(bytes, "seeded.pdf")
            },
        )
        rule.setContent { PdfaTheme(darkTheme = true) { ViewerScreen(vm, onBack = {}) } }

        rule.waitUntil(20_000) { vm.state.value is ViewerState.Ready && sharpTilesReady(vm) }
        Thread.sleep(800)
        rule.waitForIdle()
        screenshot("viewer-1")
        report.appendLine("initial: page=${vm.view.value.currentPage + 1} badge=${vm.view.value.pageBadge} tiles=${vm.tiles.count} bytes=${vm.tiles.sizeBytes}")

        // Real swipes.
        val meter = FrameMeter()
        rule.runOnUiThread { meter.start() }
        repeat(4) { rule.onRoot().performTouchInput { swipeUp(durationMillis = 120) } }
        rule.waitForIdle()
        rule.runOnUiThread { meter.stop() }
        report.appendLine(meter.summary("swipes"))
        val afterSwipes = vm.view.value.currentPage
        report.appendLine("after swipes: page=${afterSwipes + 1}")
        check(afterSwipes > 0) { "swipes should scroll" }

        // Fast scroll to about page 150.
        rule.runOnUiThread { vm.onFastScroll(vm.fractionForPage(149)) }
        rule.waitUntil(20_000) { sharpTilesReady(vm) }
        Thread.sleep(800)
        rule.waitForIdle()
        screenshot("viewer-2")
        val page = vm.view.value.currentPage + 1
        report.appendLine("fast scroll: page=$page badge=${vm.view.value.pageBadge}")
        check(page in 140..160) { "page $page" }

        // Real pinch.
        val zoomMeter = FrameMeter()
        rule.runOnUiThread { zoomMeter.start() }
        rule.onRoot().performTouchInput {
            val c = Offset(width / 2f, height / 2f)
            pinch(c - Offset(40f, 0f), c - Offset(300f, 0f), c + Offset(40f, 0f), c + Offset(300f, 0f), durationMillis = 400)
        }
        rule.waitForIdle()
        rule.runOnUiThread { zoomMeter.stop() }
        report.appendLine(zoomMeter.summary("pinch"))
        val zoom = vm.view.value.pos.zoom
        report.appendLine("after pinch: zoom=$zoom")
        check(zoom > 1.5f) { "pinch should zoom, got $zoom" }
        rule.waitUntil(20_000) { sharpTilesReady(vm) }
        Thread.sleep(800)
        rule.waitForIdle()
        val bucket = vm.view.value.plan.tiles.first { it.kind == TileKind.SHARP }.key.bucket
        report.appendLine("sharp bucket=$bucket scale=${TileMath.scaleOf(bucket)} page=${vm.view.value.currentPage + 1}")
        screenshot("viewer-3")

        // Double tap returns to fit width, then zooms to 2.5x.
        rule.runOnUiThread { vm.zoomTo(1f, 0f, 0f) }
        rule.onRoot().performTouchInput { doubleClick() }
        rule.waitUntil(5_000) { vm.view.value.pos.zoom > 2.499f }
        org.junit.Assert.assertEquals(2.5f, vm.view.value.pos.zoom, 0.05f)
        report.appendLine("double tap: zoom=${vm.view.value.pos.zoom}")

        File(dir, "report.txt").writeText(report.toString())
    }
}
