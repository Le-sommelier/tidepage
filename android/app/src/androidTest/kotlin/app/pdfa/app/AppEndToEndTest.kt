package app.pdfa.app

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Rect
import android.net.Uri
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.util.Log
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.BySelector
import androidx.test.uiautomator.Configurator
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.UiObject2
import androidx.test.uiautomator.Until
import app.pdfa.engine.EngineError
import app.pdfa.engine.Pt
import app.pdfa.engine.Rotation
import app.pdfa.engine.impl.RemoteEngine
import app.pdfa.scan.ScanController
import app.pdfa.scan.addPickedImages
import app.pdfa.scan.toFrame
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.FixMethodOrder
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.MethodSorters
import java.io.FileInputStream
import java.util.regex.Pattern

/**
 * The whole app on a device: a real VIEW intent on a `content://` URI (served by [TestPdfProvider]),
 * the import into the workspace, the real engine in its isolated process, the viewer and its add-ons.
 *
 * The UI is driven from outside through UiAutomator. The test also runs inside the app process, which
 * it uses for two things only: asking the engine client for the engine pid (PING), and making one
 * engine call hang through the debug-only `__hang__` hook of :engine.
 *
 * Needs a userdebug emulator (`su 0`) for the kill test. Evidence lines are tagged `PDFA-PROOF` in
 * logcat; screenshots go to `/data/local/tmp/pdfa-shots/`.
 */
@RunWith(AndroidJUnit4::class)
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
class AppEndToEndTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val device: UiDevice = UiDevice.getInstance(instrumentation)
    private val context = instrumentation.targetContext
    private val container get() = (context.applicationContext as PdfaApplication).container

    @Before
    fun setUp() {
        shell("mkdir -p $SHOTS")
        // Compose screens are rarely "idle" for long; never wait 10 s for it before each action.
        Configurator.getInstance().setWaitForIdleTimeout(0).setWaitForSelectorTimeout(0)
        device.pressHome()
        // The restart-storm guard counts restarts per engine client. Each test starts with a fresh one.
        container.engineProvider.shutdown()
        // No unsaved work left over from an earlier test (it would show on Home and be offered for restore).
        runBlocking { container.recoverable().forEach { container.discardRecoverable(it.docId) } }
    }

    @After
    fun tearDown() {
        // Whatever a test changed (dark theme, font size) is put back, also when the test failed.
        shell("cmd uimode night no")
        shell("settings put system font_scale 1.0")
        device.pressHome()
    }

    // ---- (a) ----

    @Test
    fun a_viewIntentOpensThreePagePdfWithRealText() {
        val t0 = open("text3.pdf")
        val surface = waitForPage(1, 3)
        val firstTile = waitForInk(surface)
        proof("first-tile text3.pdf: ${firstTile - t0} ms from VIEW intent (page description after ${descAt - t0} ms)")

        val stats = ink(surface)
        assertTrue("page 1 must show dark text pixels, got $stats", stats.dark > MIN_DARK_PIXELS)
        assertTrue("page 1 must be mostly the white sheet, got $stats", stats.light > stats.total / 3)
        assertTrue("the text must not be a flat block, got $stats", stats.dark < stats.total / 4)

        // The badge fades out a moment after the last scroll: show it again right before looking.
        var badge: UiObject2? = null
        for (attempt in 1..4) {
            nudgeScreen()
            badge = device.wait(Until.findObject(By.text("1 / 3")), 2_000)
            if (badge != null) break
        }
        assertNotNull("badge 1 / 3", badge)
        shot("viewer_page1")
        assertEquals("text3.pdf", device.findObject(By.text("text3.pdf"))?.text)
    }

    // ---- (b) ----

    @Test
    fun b_threeHundredPagesFastScrollToPage150() {
        val t0 = open("big300.pdf")
        val surface = waitForPage(1, 300)
        val firstTile = waitForInk(surface)
        proof("first-tile big300.pdf: ${firstTile - t0} ms from VIEW intent (page description after ${descAt - t0} ms)")

        // A real drag of the fast-scroll thumb to about the middle of the track.
        nudge(surface)
        val thumb = device.wait(Until.findObject(By.desc("Scroll position")), 3_000)
        assertNotNull("the fast scroller shows while scrolling", thumb)
        val from = thumb.visibleCenter
        device.swipe(from.x, from.y, from.x, boundsOrPageSurface(surface).centerY(), 40)
        val middle = currentPage()
        proof("fast-scroll drag landed on page $middle of 300")
        assertTrue("the drag must travel far, got page $middle", middle in 100..200)

        // Then exactly page 150 through the scroller's own set-progress action.
        val fraction = viewModel().fractionForPage(149)
        setScrollProgress(fraction)
        val target = waitForPage(150, 300)
        // The badge fades out a moment after the last scroll: show it again right before looking, and look again if it was gone.
        var badge: UiObject2? = null
        for (attempt in 1..4) {
            nudgeScreen() // the scroller itself fades too: bring it back before using it
            setScrollProgress(fraction)
            badge = device.wait(Until.findObject(By.text("150 / 300")), 2_000)
            if (badge != null) break
        }
        assertNotNull("badge 150 / 300", badge)
        waitForInk(target)
        val stats = ink(target)
        assertTrue("page 150 must render text, got $stats", stats.dark > MIN_DARK_PIXELS)
        shot("viewer_page150")
    }

    // ---- (c) ----

    @Test
    fun c_corruptPdfShowsTheDamagedStateAndTheAppLives() {
        open("corrupt.pdf")
        assertNotNull(device.wait(Until.findObject(By.text("This PDF looks damaged")), LONG))
        shot("corrupt")
        tap(By.text("Back").clazz("android.widget.TextView"), 3_000)
        assertNotNull("back on Home, still alive", device.wait(Until.findObject(HOME), 5_000))
        // And the engine still serves the next document.
        open("text3.pdf")
        waitForInk(waitForPage(1, 3))
    }

    // ---- (d) ----

    @Test
    fun d_hungEngineCallIsKilledAndTheViewerKeepsWorking() {
        open("text3.pdf")
        waitForInk(waitForPage(1, 3))
        val engine = remote()
        val before = runBlocking { engine.ping() }
        assertTrue("engine process ${before.pid} is alive", alive(before.pid))

        // Debug builds of :engine park forever on this password. The watchdog must end it.
        val bytes = context.assets.let { instrumentation.context.assets.open("text3.pdf").use { s -> s.readBytes() } }
        val started = SystemClock.elapsedRealtime()
        val error = runBlocking {
            try {
                engine.open(bytes, "__hang__")
                null
            } catch (e: EngineError) {
                e
            }
        }
        val took = SystemClock.elapsedRealtime() - started
        assertTrue("the hung open must end in Timeout, got $error", error is EngineError.Timeout)

        assertNotNull(
            "safe-mode banner",
            device.wait(Until.findObject(By.text("The engine restarted. Your document is safe.")), 10_000),
        )
        shot("engine_restart_banner_hang")
        assertNull("nothing was unsaved, so no changes-lost dialog", device.findObject(By.textContains("changes since the last autosave")))

        val after = runBlocking { engine.ping() }
        proof("hang: open(__hang__) -> ${error?.javaClass?.simpleName} after $took ms; engine pid ${before.pid} -> ${after.pid}, bootId ${before.bootId} -> ${after.bootId}")
        assertNotEquals("a new engine process", before.pid, after.pid)
        assertNotEquals(before.bootId, after.bootId)
        assertFalse("the hung process ${before.pid} is gone", alive(before.pid))

        // The same viewer keeps working on the new process: a search is answered and pages still draw.
        searchFor("Hello")
        expectSearchCounter("1 of 3", "search answered by the new engine")
        tap(By.desc("Close search"), 3_000)
        viewModel().let { instrumentation.runOnMainSync { it.jumpToPage(2) } }
        val page3 = waitForPage(3, 3)
        waitForInk(page3)
    }

    // ---- (e) ----

    @Test
    fun e_killedEngineWhileScrollingRecovers() {
        open("document/big300.pdf")
        waitForInk(waitForPage(1, 300))
        // Unsaved work, autosaved: it must survive the engine being killed.
        runBlocking {
            val doc = session().document.value!!
            doc.edit { delete(it, listOf(299)) }
            doc.flush()
        }
        val surface = waitForPage(1, 299)
        waitForInk(surface)
        val engine = remote()
        val before = runBlocking { engine.ping() }
        val listed = shell("pidof ${context.packageName}:engine:app.pdfa.engine.service.EngineService:engine_1").trim()
        proof("kill: engine pid from PING ${before.pid}, from pidof '$listed'")

        fling(surface, times = 2)
        val out = shell("su 0 kill -9 ${before.pid}")
        assertTrue("kill -9 must work (userdebug image needed): $out", out.isBlank())
        fling(surface, times = 3)

        dismissEditsLost(expect = "reopened from the last autosave")
        assertNotNull(
            "safe-mode banner",
            device.wait(Until.findObject(By.text("The engine restarted. Your document is safe.")), 10_000),
        )
        shot("engine_restart_banner_kill")
        val after = runBlocking { engine.ping() }
        proof("kill: engine pid ${before.pid} -> ${after.pid}, bootId ${before.bootId} -> ${after.bootId}")
        assertNotEquals(before.pid, after.pid)
        assertFalse(alive(before.pid))

        // A page far away was never rendered before the kill: its tiles come from the new process.
        nudge(surface)
        assertEquals("the autosaved deletion survived the kill", 299, session().document.value!!.state.value.pageCount)
        assertTrue("still unsaved after the restart", session().document.value!!.state.value.dirty)
        setScrollProgress(viewModel().fractionForPage(279))
        val far = waitForPage(280, 299)
        waitForInk(far)
        assertTrue(ink(far).dark > MIN_DARK_PIXELS)
        shot("after_kill_page280")
    }

    // ---- (f) ----

    @Test
    fun f_searchFindsTheWordOnPage150AndScrollsThere() {
        open("big300.pdf")
        waitForInk(waitForPage(1, 300))
        val started = SystemClock.elapsedRealtime()
        searchFor("UNIQ150X")
        assertNotNull("one hit in 300 pages", device.wait(Until.findObject(By.text("1 of 1")), 120_000))
        proof("search: 300 pages scanned in ${SystemClock.elapsedRealtime() - started} ms (includes typing and debounce)")
        tap(By.desc("Next match"), 3_000)
        val page = waitForPage(150, 300)
        waitForInk(page)
        assertNotNull("still hit 1 of 1 after the first press", device.wait(Until.findObject(By.text("1 of 1")), 3_000))
        shot("search_results")
    }

    // ---- (g) ----

    @Test
    fun g_linksOnlyOpenThroughTheDialogAndHostileOnesAreNotOffered() {
        open("text3.pdf")
        val surface = waitForPage(1, 3)
        waitForInk(surface)

        tapPagePoint(surface, xPt = 200f, yFromTopPt = 792f - 625f) // the https link
        assertNotNull(device.wait(Until.findObject(By.text("Open this link?")), 5_000))
        assertNotNull("the host is shown", device.wait(Until.findObject(By.text("example.org")), 3_000))
        assertNotNull("the full link is shown", device.wait(Until.findObject(By.text("https://example.org/report")), 3_000))
        shot("link_dialog")
        tap(By.text("Cancel"), 3_000)
        device.wait(Until.gone(By.text("Open this link?")), 3_000)
        assertNotNull("cancel stays in the viewer", device.wait(Until.findObject(pageSelector(1, 3)), 5_000))

        tapPagePoint(surface, xPt = 200f, yFromTopPt = 792f - 545f) // the javascript: link
        assertNotNull(device.wait(Until.findObject(By.text("This link cannot be opened.")), 5_000))
        assertNull("a javascript: link is never offered", device.findObject(By.text("Open this link?")))
        assertNull(device.findObject(By.textContains("javascript")))
        shot("link_rejected")
    }

    // ---- (h) contents sheet and page previews ----

    @Test
    fun h_contentsSheetAndPagePreviews() {
        open("text3.pdf")
        waitForInk(waitForPage(1, 3))
        tap(By.desc("More"), 5_000)
        tap(By.text("Contents"), 3_000)
        assertNotNull(device.wait(Until.findObject(By.text("Chapter 3")), LONG))
        shot("outline_sheet")
        tap(By.text("Chapter 3"), 3_000)
        waitForPage(3, 3)

        tap(By.desc("More"), 5_000)
        tap(By.text("Pages"), 3_000)
        assertNotNull(device.wait(Until.findObject(By.desc("Page 2 of 3")), LONG))
        SystemClock.sleep(1_500)
        shot("pages_sheet")
    }

    // ---- (i) password ----

    @Test
    fun i_passwordProtectedPdfAsksAndOpens() {
        open("locked.pdf")
        val field = device.wait(Until.findObject(By.clazz("android.widget.EditText")), LONG)
        assertNotNull("the password sheet", field)
        submitPassword("wrong")
        // The real condition is the viewer's state. A click on Open that landed while the sheet was still
        // animating is lost (seen once in a full run), so an unanswered submit is repeated.
        var lastSubmit = SystemClock.uptimeMillis()
        waitUntil("the viewer reports the wrong password", 40_000) {
            val st = viewModel().state.value
            if (st is app.pdfa.viewer.ViewerState.PasswordRequired) {
                if (st.lastAttemptWrong) return@waitUntil true
                if (!st.submitting && SystemClock.uptimeMillis() - lastSubmit > 6_000) {
                    submitPassword("wrong")
                    lastSubmit = SystemClock.uptimeMillis()
                }
            }
            false
        }
        assertNotNull("wrong password is reported on screen", device.wait(Until.findObject(By.textContains("Wrong password")), LONG))
        submitPassword("secret")
        lastSubmit = SystemClock.uptimeMillis()
        waitUntil("the right password opens the document", 40_000) {
            val st = viewModel().state.value
            if (st is app.pdfa.viewer.ViewerState.PasswordRequired && !st.submitting && SystemClock.uptimeMillis() - lastSubmit > 6_000) {
                submitPassword("secret")
                lastSubmit = SystemClock.uptimeMillis()
            }
            st is app.pdfa.viewer.ViewerState.Ready
        }
        waitForInk(waitForPage(1, 1))
    }

    /** Types into the password field and presses Open, once the field took the text and the button is enabled. */
    private fun submitPassword(password: String) {
        waitUntil("the password field accepts text") {
            val field = device.findObject(By.clazz("android.widget.EditText").enabled(true)) ?: return@waitUntil false
            field.text = password
            SystemClock.sleep(200)
            device.findObject(By.clazz("android.widget.EditText"))?.text?.length == password.length
        }
        waitUntil("the Open button can be clicked", 5_000) {
            try {
                val open = device.findObject(By.text("Open")) ?: return@waitUntil false
                (open.parent ?: open).click()
                true
            } catch (e: androidx.test.uiautomator.StaleObjectException) {
                false
            }
        }
    }

    // ---- (j) recents ----

    @Test
    fun j_recentsAreWrittenOnASuccessfulOpenOnly() {
        open("corrupt.pdf")
        assertNotNull(device.wait(Until.findObject(By.text("This PDF looks damaged")), LONG))
        open("text3.pdf")
        waitForInk(waitForPage(1, 3))
        device.pressBack()
        assertNotNull(device.wait(Until.findObject(HOME), 5_000))
        assertNotNull("the opened document is in recents", device.wait(Until.findObject(By.text("text3.pdf")), 5_000))
        assertNull("a file that never opened is not", device.findObject(By.text("corrupt.pdf")))
        shot("home")
    }

    // ---- (k) numbers ----

    @Test
    fun k_memoryAndJankAfterScrollingAHundredPages() {
        open("big300.pdf")
        val surface = waitForPage(1, 300)
        waitForInk(surface)
        shell("dumpsys gfxinfo ${context.packageName} reset")
        var swipes = 0
        while (currentPage() < 101 && swipes < 200) {
            fling(surface, times = 1)
            swipes++
        }
        SystemClock.sleep(1_500)
        val reached = currentPage()
        val gfx = shell("dumpsys gfxinfo ${context.packageName}")
        val frames = Regex("Total frames rendered: (\\d+)").find(gfx)?.groupValues?.get(1)
        val janky = Regex("Janky frames: (\\d+) \\(([0-9.]+)%\\)").find(gfx)
        val enginePid = runBlocking { remote().ping().pid }
        val appPss = totalPss(shell("dumpsys meminfo ${context.packageName}"))
        val enginePss = totalPss(shell("dumpsys meminfo $enginePid"))
        proof("scroll: reached page $reached with $swipes flings; frames=$frames janky=${janky?.groupValues?.get(1)} (${janky?.groupValues?.get(2)}%)")
        proof("pss: app TOTAL PSS ${appPss} kB, engine (pid $enginePid) TOTAL PSS ${enginePss} kB")
        assertTrue("scrolled through 100 pages, reached $reached", reached >= 101)
        assertNotNull(appPss)
        assertNotNull(enginePss)
        waitForInk(device.wait(Until.findObject(By.descContains(" of 300").clazz("android.view.View")), 5_000))
    }

    // ---- (l) save flow ----

    @Test
    fun l_editSaveACopyAndReopenTheCopy() {
        shell("rm -f /data/data/${instrumentation.context.packageName}/cache/out1.pdf")
        open("document/text3.pdf")
        waitForInk(waitForPage(1, 3))
        val session = session()
        assertFalse(session.document.value!!.state.value.dirty)

        runBlocking { session.document.value!!.edit { rotate(it, listOf(0), Rotation.CW90) } }
        assertNotNull("Undo appears after an edit", device.wait(Until.findObject(By.desc("Undo")), 5_000))
        waitUntil("page 1 is redrawn in landscape") { firstPageIsLandscape() }
        waitForInk(device.findObject(pageSelector(1, 3)))
        assertTrue(session.document.value!!.state.value.dirty)
        shot("edited_rotated")

        // The same call the create-document picker's result makes.
        session.saveCopyTo("content://app.pdfa.test.pdfs/document/out1.pdf")
        assertNotNull(
            "only shown after the engine confirmed the save",
            device.wait(Until.findObject(By.text("Saved a copy. The original file is unchanged.")), LONG),
        )
        assertFalse(session.document.value!!.state.value.dirty)
        shot("saved_copy")

        // The copy, imported and opened again by the real engine, has the change.
        open("document/out1.pdf")
        waitForInk(waitForPage(1, 3))
        waitUntil("the copy's page 1 is landscape") { firstPageIsLandscape() }
        val copy = viewModel().view.value.layout!!
        assertTrue("page 2 of the copy is still portrait", copy.pageHeight(1) > copy.pageWidth)
        proof("save: copy reopened, page 1 ${copy.pageWidth}x${copy.pageHeight(0)} px (landscape), page 2 height ${copy.pageHeight(1)}")

        // And the original is untouched.
        open("document/text3.pdf")
        waitForInk(waitForPage(1, 3))
        assertFalse(firstPageIsLandscape())
    }

    @Test
    fun m_leavingWithChangesAsksAndUnsavedWorkIsRecovered() {
        open("document/text3.pdf")
        waitForInk(waitForPage(1, 3))
        runBlocking { session().document.value!!.edit { delete(it, listOf(2)) } }
        waitForPage(1, 2)

        device.pressBack()
        assertNotNull(device.wait(Until.findObject(By.text("Save your changes?")), 5_000))
        shot("leave_dialog")
        tap(By.text("Cancel"), 3_000)
        assertNotNull("cancel keeps the document open", device.wait(Until.findObject(pageSelector(1, 2)), 5_000))

        // Another document replaces this one without a decision: the work must not be lost.
        open("document/big300.pdf")
        waitForPage(1, 300)
        device.pressBack()
        assertNotNull(device.wait(Until.findObject(By.text("Unsaved changes: text3.pdf")), 10_000))
        shot("home_recovery")
        tap(By.text("Restore"), 3_000)
        waitForInk(waitForPage(1, 2))
        waitUntil("the restored document counts as unsaved") { session().document.value?.state?.value?.dirty == true }

        device.pressBack()
        tap(By.text("Discard changes"), 5_000)
        assertNotNull(device.wait(Until.findObject(HOME), 5_000))
        SystemClock.sleep(500)
        assertNull("discarded work is not offered again", device.findObject(By.textStartsWith("Unsaved changes")))
    }

    // ---- (n) organize pages ----

    @Test
    fun n_organizePagesRotateDeleteMoveSaveACopyAndReopenIt() {
        shell("rm -f /data/data/${instrumentation.context.packageName}/cache/out2.pdf")
        open("document/big300.pdf")
        waitForInk(waitForPage(1, 300))
        val session = session()

        tap(By.desc("More"), 5_000)
        tap(By.text("Organize pages"), 5_000)
        fun tapTile(n: Int, count: Int = 300) = tap(By.desc("Organizer page $n of $count"))

        // Rotate page 1 clockwise.
        tapTile(1)
        tap(By.desc("Rotate page 1 clockwise"), 5_000)
        waitUntil("the rotation is in the document") { session.document.value!!.state.value.canUndo }
        shot("organize_light_selected")

        // Delete page 2: the message offers Undo.
        tapTile(1) // deselect
        tapTile(2)
        tap(By.desc("Delete page 2"), 5_000)
        assertNotNull(device.wait(Until.findObject(By.text("Page 2 deleted")), 5_000))
        waitUntil("299 pages") { session.document.value!!.state.value.pageCount == 299 }
        shot("organize_after_delete")

        // Now [1, 3, 4, 5, 6, ...]: page 4 (third tile) moves one earlier -> [1, 4, 3, 5, 6, ...].
        tapTile(3, 299)
        tap(By.desc("Move page 3 earlier"), 5_000)
        waitUntil("the move is applied") { orderOf(session, 4) == listOf(1, 4, 3, 5) }

        // Drag the fifth tile (original page 6) onto the first: [6, 1, 4, 3, 5, ...].
        waitUntil("the drag gesture was performed") {
            try {
                val from = device.findObject(By.desc("Organizer page 5 of 299"))
                val to = device.findObject(By.desc("Organizer page 1 of 299"))
                if (from == null || to == null) false else {
                    val target = to.visibleBounds
                    from.drag(android.graphics.Point(target.centerX(), target.centerY()), 400)
                    true
                }
            } catch (e: androidx.test.uiautomator.StaleObjectException) {
                false
            }
        }
        waitUntil("the drag moved the page") { orderOf(session, 5) == listOf(6, 1, 4, 3, 5) }
        shot("organize_after_drag")

        // Dark theme must stay readable (the screen is recreated; the document session survives).
        try {
            shell("cmd uimode night yes")
            tapTile(1, 299)
            device.wait(Until.findObject(By.desc("Rotate page 1 clockwise")), 10_000)
            shot("organize_dark_selected")
        } finally {
            shell("cmd uimode night no")
        }
        device.wait(Until.findObject(By.text("Done")), 10_000)

        // Leave the organizer; the viewer shows the edited document and offers Undo.
        tap(By.text("Done"), 5_000)
        assertNotNull(device.wait(Until.findObject(By.desc("Undo")), 5_000))
        assertTrue(session.document.value!!.state.value.dirty)

        session.saveCopyTo("content://app.pdfa.test.pdfs/document/out2.pdf")
        assertNotNull(
            "only shown after the engine confirmed the save",
            device.wait(Until.findObject(By.text("Saved a copy. The original file is unchanged.")), LONG),
        )

        // The copy, opened again by the real engine: count, order and rotation.
        open("document/out2.pdf")
        waitForPage(1, 299)
        val copy = session()
        waitUntil("the copy is open") { copy.document.value != null }
        assertEquals(299, copy.document.value!!.state.value.pageCount)
        val order = orderOf(copy, 8)
        assertEquals(listOf(6, 1, 4, 3, 5, 7, 8, 9), order)
        val layout = viewModel().view.value.layout!!
        waitUntil("layout of the copy") { viewModel().view.value.layout != null }
        assertTrue("original page 1 (now second) is landscape", layout.pageHeight(1) < layout.pageWidth)
        assertTrue("the first page is still portrait", layout.pageHeight(0) > layout.pageWidth)
        proof("organize: copy has 299 pages, first pages are originals $order, page 2 rotated")
    }


    // ---- (o) merge ----

    @Test
    fun o_mergeThreeFilesWithARangeAndAPasswordThenOpenTheResult() {
        shell("rm -f /data/data/${instrumentation.context.packageName}/cache/out3.pdf")
        context.startActivity(
            Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK),
        )
        assertNotNull("Home is shown after the relaunch", device.wait(Until.findObject(HOME), LONG))
        // Home's top bar menu: the "More" button on the same line as the title. (Rows of recent files have
        // their own buttons; a click on one of those opens "Remove from recents" instead.)
        var entry: UiObject2? = null
        for (attempt in 1..4) {
            try {
                // Relaunching over the previous test's screen animates the old window out: wait until Home
                // stands still (the title at the same place twice), or a click lands on something scaled under the finger.
                device.wait(Until.findObject(HOME), 5_000) ?: continue
                var last: Rect? = null
                var settled = 0
                val deadline = SystemClock.uptimeMillis() + 5_000
                while (settled < 2 && SystemClock.uptimeMillis() < deadline) {
                    SystemClock.sleep(300)
                    val now = try { device.findObject(HOME)?.visibleBounds } catch (e: androidx.test.uiautomator.StaleObjectException) { null }
                    settled = if (now != null && now == last) settled + 1 else 0
                    last = now
                }
                val titleY = last?.centerY() ?: continue
                val more = device.findObjects(By.desc("More")).firstOrNull { Math.abs(it.visibleCenter.y - titleY) < 80 }
                more?.click()
                entry = device.wait(Until.findObject(By.text("Merge PDFs")), 3_000)
                if (entry != null) break
                // A menu opened by mistake covers Home; Back closes just that menu.
                if (device.hasObject(By.text("Remove from recents"))) device.pressBack()
            } catch (e: androidx.test.uiautomator.StaleObjectException) {
                // The screen changed under the lookup: look again.
            }
            SystemClock.sleep(500)
        }
        if (entry == null) { shot("home_menu_missing"); throw AssertionError("no Merge PDFs entry; the screen shows: " + screenTexts()) }
        entry.click()
        assertNotNull("the empty merge screen", device.wait(Until.findObject(By.textContains("Add two or more PDF files")), 5_000))
        shot("merge_empty")

        val controller = mergeController()
        val io = app.pdfa.app.tools.ToolsIo(container.streams)
        val base = "content://app.pdfa.test.pdfs/document/"
        // What the multi-select picker's result does.
        runBlocking { controller.addSources(listOf(base + "text3.pdf", base + "big300.pdf", base + "locked.pdf")) }
        val items = controller.state.value.items
        assertEquals("page counts of the three sources", listOf(3, 300, null), items.map { it.pageCount })
        assertEquals("locked.pdf needs a password", app.pdfa.pages.tools.SourceStatus.NEEDS_PASSWORD, items[2].status)
        controller.setRange(items[1].id, "1-5")
        assertNotNull("the Protected label of the locked source", device.wait(Until.findObject(By.textContains("Protected")), LONG))
        shot("merge_list_password")

        // Through the screen: the password field of the third file and its Unlock button.
        typeIntoLastField("nope")
        tapUntil(By.text("Unlock"), "the wrong password is refused") { controller.state.value.items[2].status == app.pdfa.pages.tools.SourceStatus.WRONG_PASSWORD }
        shot("merge_wrong_password")
        expectText("Wrong password")
        typeIntoLastField("secret")
        tapUntil(By.text("Unlock"), "the right password is accepted") { controller.state.value.items[2].status == app.pdfa.pages.tools.SourceStatus.READY }
        assertEquals("locked.pdf has one page", 1, controller.state.value.items[2].pageCount)

        // Reordering through the buttons, then back.
        clickUntil("Move file 2 down", "the order changed") { controller.state.value.items.map { it.pageCount } == listOf(3, 1, 300) }
        clickUntil("Move file 3 up", "the order is back") { controller.state.value.items.map { it.pageCount } == listOf(3, 300, 1) }
        shot("merge_list")

        // A bad range blocks the button and says why.
        controller.setRange(items[1].id, "1-400")
        expectText("does not exist")
        shot("merge_list_range_error")
        controller.setRange(items[1].id, "1-5")

        tap(By.text("Merge"), 5_000)
        shot("merge_progress_or_result")
        waitUntil("the merge finished", 60_000) { controller.state.value.phase == app.pdfa.pages.tools.MergePhase.MERGED }
        assertEquals("merged page count", 9, controller.state.value.mergedPages)
        assertNotNull("Save merged file is offered", device.wait(Until.findObject(By.text("Save merged file")), LONG))
        shot("merge_result")

        // What the create-document picker's result does.
        val saved = runBlocking { controller.save(base + "out3.pdf") { uri, bytes -> io.writeFile(uri, bytes) } }
        assertTrue("the merged file was written", saved)
        assertNotNull("Open merged file is offered", device.wait(Until.findObject(By.text("Open merged file")), LONG))
        shot("merge_saved")

        // Open it from the result card: the viewer shows 9 pages, and the pages are the right ones.
        tap(By.text("Open merged file"), 5_000)
        waitForPage(1, 9)
        val merged = session()
        waitUntil("the merged file is open") { merged.document.value?.state?.value?.pageCount == 9 }
        val handle = merged.document.value!!.handle
        runBlocking {
            for (n in 1..5) {
                assertTrue("UNIQ%03dX is on page ${3 + n}".format(n), merged.engine.searchPage(handle, 2 + n, "UNIQ%03dX".format(n)).isNotEmpty())
            }
            assertTrue("no sixth page of big300", (0 until 9).none { merged.engine.searchPage(handle, it, "UNIQ006X").isNotEmpty() })
            assertTrue("page 1 of text3 comes first", merged.engine.searchPage(handle, 0, "UNIQ001X").isEmpty())
        }
        proof("merge: text3 + big300 pages 1-5 + locked.pdf (password accepted by the engine) = 9 pages, UNIQ001X..UNIQ005X on pages 4..8")
    }

    // ---- (p) split and extract ----

    @Test
    fun p_splitEveryPageGivesThreeOnePagePartsAndTheDialogWorks() {
        shell("rm -f /data/data/${instrumentation.context.packageName}/cache/outpart*.pdf")
        open("document/text3.pdf")
        waitForPage(1, 3)
        val s = session()
        tap(By.desc("More"), 5_000)
        tap(By.text("Split or extract"), 5_000)
        assertNotNull("Extract pages entry", device.wait(Until.findObject(By.text("Extract pages")), 5_000))
        shot("split_dialog")

        // Extract: a bad page is explained, a good range is accepted.
        typeIntoField("9")
        expectText("does not exist")
        shot("split_dialog_error")
        typeIntoField("2-3")
        assertNotNull("extract summary for two pages", device.wait(Until.findObject(By.text("2 pages will be extracted")), 5_000))
        tap(By.text("Split every N pages"))
        typeIntoField("1")
        assertNotNull("split summary for three files", device.wait(Until.findObject(By.textContains("3 files will be made")), 5_000))
        shot("split_dialog_every_1")
        tap(By.text("Split"), 5_000)
        assertNotNull("split result card", device.wait(Until.findObject(By.text("3 files are ready. Choose a folder to save them in.")), 20_000))
        shot("split_ready")

        // The same controller calls the dialog and the folder picker's result make, against the open document.
        val handle = s.document.value!!.handle
        val controller = app.pdfa.pages.tools.SplitController(s.engine, handle, 3)
        controller.setMode(app.pdfa.pages.tools.SplitMode.EVERY_N)
        controller.setInput("1")
        assertTrue("split run succeeded", runBlocking { controller.run() })
        val io = app.pdfa.app.tools.ToolsIo(container.streams)
        val names = ArrayList<String>()
        val report = runBlocking {
            controller.saveParts { _, name, bytes ->
                names += name
                io.writeFile("content://app.pdfa.test.pdfs/document/out${name.replace("-", "")}", bytes)
            }
        }
        assertEquals("split save report", app.pdfa.pages.tools.SaveReport.PartsSaved(3), report)
        assertEquals("split part names", listOf("part-01.pdf", "part-02.pdf", "part-03.pdf"), names)
        val engine = container.engineProvider.engine
        for (name in names) {
            val bytes = container.streams.openInput("content://app.pdfa.test.pdfs/document/out${name.replace("-", "")}").use { it.readBytes() }
            assertTrue("part starts with %PDF-", String(bytes, 0, 5, Charsets.ISO_8859_1) == "%PDF-")
            val h = runBlocking { engine.open(bytes) }
            assertEquals("$name has one page", 1, runBlocking { engine.pageInfos(h) }.size)
            runBlocking { engine.close(h) }
        }
        // And the extract path: pages 2-3 of the open document as one file.
        val extract = app.pdfa.pages.tools.SplitController(s.engine, handle, 3)
        extract.setInput("2-3")
        assertTrue("extract run succeeded", runBlocking { extract.run() })
        var extracted = 0
        assertTrue("extract file written", runBlocking { extract.saveFile { bytes -> extracted = bytes.size; io.writeFile("content://app.pdfa.test.pdfs/document/outpartextract.pdf", bytes) } })
        val h2 = runBlocking { engine.open(container.streams.openInput("content://app.pdfa.test.pdfs/document/outpartextract.pdf").use { it.readBytes() }) }
        assertEquals("extract page count", 2, runBlocking { engine.pageInfos(h2) }.size)
        runBlocking { engine.close(h2) }
        assertTrue("extract wrote bytes", extracted > 0)
        proof("split: text3 every 1 page = 3 parts of 1 page; extract 2-3 = 2 pages; folder picker path (DocumentsContract.createDocument) not exercised")
    }

    // ---- (q) the organizer's action bar ----

    @Test
    fun q_organizerActionBarShowsEveryActionAtDoubleFontSize() {
        try {
            shell("settings put system font_scale 2.0")
            SystemClock.sleep(1_000)
            open("document/text3.pdf")
            waitForPage(1, 3)
            session()
            tap(By.desc("More"), 5_000)
            tap(By.text("Organize pages"), 5_000)
            tap(By.desc("Organizer page 1 of 3"), 10_000)
            val descriptions = listOf(
                "Rotate page 1 counter-clockwise", "Rotate page 1 clockwise", "Delete page 1", "Duplicate page 1",
                "Move page 1 earlier", "Move page 1 later", "Insert a blank page after page 1", "Extract page 1 to a new file",
            )
            val width = device.displayWidth
            val height = device.displayHeight
            for (d in descriptions) {
                val b = boundsOf(By.desc(d), d)
                assertTrue("$d is fully on screen: $b", b.left >= 0 && b.right <= width && b.bottom <= height && b.width() > 0)
            }
            shot("organize_bar_font200")
        } finally {
            shell("settings put system font_scale 1.0")
        }
    }

    // ---- (r, s, t) annotate ----

    @Test
    fun r_penStrokeOnARotatedPageLandsWhereDrawnAndSurvivesSaveAndReopen() {
        shell("rm -f /data/data/${instrumentation.context.packageName}/cache/out4.pdf")
        open("document/rotated.pdf")
        val surface = waitForPage(1, 2)
        waitForInk(surface)
        val session = session()
        assertTrue("page 1 (/Rotate 90) is shown landscape", firstPageIsLandscape())

        tap(By.desc("More"), 5_000)
        tap(By.text("Annotate"), 5_000)
        assertNotNull("the annotation bar replaces the tray", device.wait(Until.findObject(By.text("Pen")), 5_000))
        val annotate = session.annotate.value!!
        tapUntil(By.desc("Red colour"), "red pen") { annotate.state.value.penSwatch.rgb == 0xB3261E }
        shot("annotate_bar_light")

        // A real finger swipe across the rotated page, in page view points (the page is 792 x 612 there).
        val from = Pt(150f, 250f)
        val to = Pt(600f, 330f)
        val before = viewModel().view.value.pos
        val a = pagePixel(surface, 0, from)
        val b = pagePixel(surface, 0, to)
        device.swipe(a.x, a.y, b.x, b.y, 60)
        waitUntil("the stroke is in the document", 20_000) {
            session.document.value!!.state.value.canUndo && annotate.state.value.pending.isEmpty()
        }
        assertEquals("drawing did not pan or zoom the page", before, viewModel().view.value.pos)
        // The app's own preview is gone: red pixels now come from the engine's rendering.
        waitUntil("the rendered page shows the stroke along the drawn path", 20_000) { strokeAlong(surface, 0, from, to) }
        assertFalse("nothing red far from the path", redNear(surface, 0, Pt(375f, 150f), 40) || redNear(surface, 0, Pt(375f, 450f), 40))
        shot("annotate_stroke")
        proof("annotate: pen stroke on /Rotate 90 page rendered along ${from}->${to}")

        // Dark theme: the bar must stay readable (the activity is recreated; the annotation mode survives).
        try {
            shell("cmd uimode night yes")
            SystemClock.sleep(1_500)
            assertNotNull("Highlighter bar shown", device.wait(Until.findObject(By.text("Highlighter")), 10_000))
            shot("annotate_bar_dark")
        } finally {
            shell("cmd uimode night no")
        }
        SystemClock.sleep(1_500)

        // With the Highlighter a finger still scrolls the pages.
        tap(By.text("Highlighter"), 10_000)
        waitUntil("highlighter") { annotate.state.value.tool == app.pdfa.annotate.AnnotateTool.HIGHLIGHTER }
        val surface2 = waitForPage(1, 2)
        val y0 = viewModel().view.value.pos.scrollY
        val sb = boundsOrPageSurface(surface2)
        device.swipe(sb.centerX(), sb.centerY() + 200, sb.centerX(), sb.centerY() - 200, 20)
        waitUntil("a finger swipe scrolled the pages") { viewModel().view.value.pos.scrollY > y0 + 50 }
        tap(By.text("Done"), 5_000)
        assertTrue("the bar is gone", device.wait(Until.gone(By.text("Highlighter")), 5_000))

        session.saveCopyTo("content://app.pdfa.test.pdfs/document/out4.pdf")
        assertNotNull(
            "only shown after the engine confirmed the save",
            device.wait(Until.findObject(By.text("Saved a copy. The original file is unchanged.")), LONG),
        )

        // The copy, opened again by the real engine: the stroke is at the same place on the page.
        open("document/out4.pdf")
        val copySurface = waitForPage(1, 2)
        waitForInk(copySurface)
        assertTrue("reopened copy page 1 is landscape", firstPageIsLandscape())
        waitUntil("the reopened copy shows the stroke at the same place", 20_000) { strokeAlong(copySurface, 0, from, to) }
        assertFalse("no red far from the path in the copy", redNear(copySurface, 0, Pt(375f, 150f), 40) || redNear(copySurface, 0, Pt(375f, 450f), 40))
        shot("annotate_stroke_reopened")
        proof("annotate: stroke found again in the reopened copy at the same page position")
    }

    @Test
    fun s_highlightAWordOnTextPage() {
        open("document/text3.pdf")
        val surface = waitForPage(1, 3)
        waitForInk(surface)
        val session = session()
        tap(By.desc("More"), 5_000)
        tap(By.text("Annotate"), 5_000)
        val annotate = session.annotate.value!!
        tapUntil(By.text("Highlighter"), "highlighter") { annotate.state.value.tool == app.pdfa.annotate.AnnotateTool.HIGHLIGHTER }

        // "Hello" in "Hello PDF page 1 of 3": 28 pt Helvetica at x 72, baseline 92 pt from the top.
        val word = pagePixel(surface, 0, Pt(100f, 82f))
        device.swipe(word.x, word.y, word.x, word.y, 200) // a long press
        var rects: List<app.pdfa.engine.Rect> = emptyList()
        waitUntil("the word is selected") {
            val s = session.addOns.value?.selection?.state?.value
            rects = if (s?.page == 0) s.rects else emptyList()
            rects.isNotEmpty()
        }
        val r = rects.first()
        val baseline = tinted(surface, 0, r)
        tap(By.text("Highlight").enabled(true), 5_000)
        waitUntil("the highlight is in the document", 20_000) {
            session.document.value!!.state.value.canUndo && annotate.state.value.pending.isEmpty() &&
                session.addOns.value?.selection?.state?.value?.page == null
        }
        waitUntil("the word's area is tinted on the rendered page", 20_000) { tinted(surface, 0, r) > 0.3f }
        val outside = tinted(surface, 0, app.pdfa.engine.Rect(r.left, r.bottom + 30f, r.right, r.bottom + 50f))
        assertTrue("baseline was not tinted: $baseline", baseline < 0.05f)
        assertTrue("below the word is not tinted: $outside", outside < 0.05f)
        shot("annotate_highlight")
        proof("annotate: highlight over $r, tinted fraction ${tinted(surface, 0, r)}")
    }

    @Test
    fun t_annotationBarShowsEveryControlAtDoubleFontSize() {
        try {
            shell("settings put system font_scale 2.0")
            SystemClock.sleep(1_000)
            open("document/text3.pdf")
            waitForPage(1, 3)
            session()
            tap(By.desc("More"), 5_000)
            tap(By.text("Annotate"), 5_000)
            val width = device.displayWidth
            val height = device.displayHeight
            val controls = listOf(By.text("Pen"), By.text("Highlighter"), By.text("Note"), By.text("Undo"), By.text("Done"),
                By.desc("Black colour"), By.desc("Teal colour"), By.desc("Red colour"), By.desc("Blue colour"), By.text("Width: Medium"))
            for (sel in controls) {
                val b = boundsOf(sel, "$sel")
                assertTrue("$sel is fully on screen: $b", b.left >= 0 && b.right <= width && b.bottom <= height && b.width() > 0)
            }
            shot("annotate_bar_font200")
        } finally {
            shell("settings put system font_scale 1.0")
        }
    }

    /** Screen pixel of page view point [p] on [page], from the viewer's current layout. */
    @Test
    fun u_anOpenSearchSurvivesAnEditAnUndoAndAnEngineRestart() {
        open("document/text3.pdf")
        waitForInk(waitForPage(1, 3))
        searchFor("Hello")
        expectSearchCounter("1 of 3", "search counter before any edit")
        val session = session()

        val beforeEdit = session.addOns.value
        runBlocking { session.document.value!!.edit { rotate(it, listOf(0), Rotation.CW90) } }
        waitUntil("the add-ons were rebuilt after the edit") { session.addOns.value !== beforeEdit }
        assertEquals("the query is still in the new controller", "Hello", session.addOns.value!!.search.state.value.query)
        expectSearchCounter("1 of 3", "the counter came back after the edit")

        val beforeUndo = session.addOns.value
        instrumentation.runOnMainSync { session.undo() }
        waitUntil("the add-ons were rebuilt after the undo") { session.addOns.value !== beforeUndo }
        expectSearchCounter("1 of 3", "the counter came back after the undo")

        val beforeRestart = session.addOns.value
        val engine = remote()
        val pid = runBlocking { engine.ping() }.pid
        shell("su 0 kill -9 $pid")
        waitUntil("the add-ons were rebuilt after the engine restart", 30_000) { session.addOns.value !== beforeRestart }
        expectSearchCounter("1 of 3", "the counter came back after the engine restart")
    }

    // ---- (v, w) forms ----

    @Test
    fun v_fillTheFormThroughTheUiSaveACopyAndReadTheValuesBack() {
        shell("rm -f /data/data/${instrumentation.context.packageName}/cache/out5.pdf")
        open("document/form.pdf")
        val surface = waitForPage(1, 1)
        val session = session()
        waitUntil("the form fields were read") { session.forms.value?.state?.value?.fields?.size == 6 }
        val forms = session.forms.value!!
        val handle = session.document.value!!.handle
        val before = runBlocking { session.engine.listFields(handle) }.associateBy { it.name }

        // Module assumptions, read from the real engine.
        // Measured: the engine returns an EMPTY list (not "Off") for an unchecked box and an unselected group.
        proof("forms: unchecked box reads ${before.getValue("subscribe").value}, unselected radio group reads ${before.getValue("plan").value}")
        assertTrue("unchecked check box has no real value (Off or empty): ${before.getValue("subscribe").value}", before.getValue("subscribe").value.all { it.isEmpty() || it == "Off" })
        assertTrue("unselected radio group has no real value (Off or empty): ${before.getValue("plan").value}", before.getValue("plan").value.all { it.isEmpty() || it == "Off" })
        assertEquals("radio choices are the widgets' onStates", listOf("basic", "pro"), before.getValue("plan").widgets.mapNotNull { it.onState })
        assertTrue("name is required and notes is multiline", before.getValue("name").required && before.getValue("notes").multiline)
        assertTrue("id is read only and holds A-1234", before.getValue("id").readOnly && before.getValue("id").value == listOf("A-1234"))
        val nameRect = before.getValue("name").widgets[0].rect
        assertTrue("widget rects are in top-left view space: name sits at y 108..132 of 792, got $nameRect", Math.abs(nameRect.top - 108f) < 1.5f && Math.abs(nameRect.bottom - 132f) < 1.5f)

        // The engine draws values that have no appearance stream (NeedAppearances): the read-only id is visible.
        waitForInk(surface)
        waitUntil("the stored value A-1234 is drawn on the page", 20_000) { ink(fieldScreenRect("id")).dark > 15 }
        val idBase = ink(fieldScreenRect("id"))
        val nameBase = ink(fieldScreenRect("name"))
        assertTrue("the empty name field is blank: $nameBase", nameBase.dark < 5)

        openMenuItem("Fill form")
        waitUntil("the field outlines are up") { device.hasObject(By.descContains("Text field name")) }
        shot("form_outlines")

        // Text through the editor, with the keyboard open.
        tap(By.descContains("Text field name"))
        waitUntil("the name field is selected") { forms.state.value.currentName == "name" }
        val input = device.wait(Until.findObject(By.clazz("android.widget.EditText")), 5_000)
        assertNotNull("the editor shows a text box", input)
        tap(By.clazz("android.widget.EditText"))
        typeIntoField("Ada Lovelace")
        waitUntil("the keyboard is open") { shell("dumpsys input_method").contains("mInputShown=true") }
        shot("form_editor_text_keyboard")
        val editor = boundsOf(By.clazz("android.widget.EditText"), "the editor text box")
        val imeTop = shell("dumpsys window").lines().firstOrNull { it.contains("InputMethod") && it.contains("frame") }
        assertTrue("the editor is on screen above the keyboard: $editor, ime: $imeTop", editor.bottom <= device.displayHeight)
        tap(By.text("Next"), 5_000)
        waitUntil("the typed name reached the document") { forms.state.value.field("name")?.value == listOf("Ada Lovelace") }
        assertEquals("Next goes to the next field in reading order", "notes", forms.state.value.currentName)

        // Check box through the UI.
        device.pressBack() // the keyboard
        tap(By.descContains("Check box subscribe"))
        waitUntil("the check box is selected") { forms.state.value.currentName == "subscribe" }
        tap(By.checkable(true), 5_000)
        waitUntil("the check box is ticked") { forms.state.value.field("subscribe")?.value?.contains("Yes") == true }

        // Radio option through the UI.
        tap(By.descContains("Option group plan"))
        waitUntil("the option group is selected") { forms.state.value.currentName == "plan" }
        tap(By.text("basic"), 5_000)
        waitUntil("the radio is set to basic") { forms.state.value.field("plan")?.value == listOf("basic") }

        // Combo value through the UI.
        tap(By.descContains("Drop-down list country"))
        waitUntil("the combo is selected") { forms.state.value.currentName == "country" }
        tap(By.text("Germany"), 5_000)
        waitUntil("the combo holds de") { forms.state.value.field("country")?.value == listOf("de") }
        shot("form_filled_editor")

        // The read-only field refuses editing.
        val refused = runBlocking { forms.setText("id", "HACKED") }
        assertFalse("the read-only field refuses a value", refused)
        assertEquals("a read-only refusal is reported", app.pdfa.forms.FormProblem.READ_ONLY, forms.state.value.problem)
        assertEquals("the read-only value is unchanged", listOf("A-1234"), runBlocking { session.engine.listFields(handle) }.first { it.name == "id" }.value)
        forms.dismissProblem()

        // The field list sheet.
        forms.deselect()
        tap(By.text("Form fields"), 5_000)
        assertNotNull("the field list is shown", device.wait(Until.findObject(By.text("Clear form")), 5_000))
        shot("form_field_list")
        device.pressBack()
        waitUntil("the list is closed") { !device.hasObject(By.text("Clear form")) }

        // Done, then the page shows the filled values.
        tap(By.text("Done"), 5_000)
        waitUntil("the overlay is gone") { !device.hasObject(By.descContains("Text field name")) }
        waitUntil("the filled name is drawn on the page", 20_000) { ink(fieldScreenRect("name")).dark > 15 }
        shot("form_filled_page")

        // Nothing was saved by itself: the edit made the document dirty, the original file is unchanged.
        assertTrue("the form edits made the document dirty", session.document.value!!.state.value.dirty)
        session.saveCopyTo("content://app.pdfa.test.pdfs/document/out5.pdf")
        assertNotNull(
            "saved only after the engine confirmed it",
            device.wait(Until.findObject(By.text("Saved a copy. The original file is unchanged.")), LONG),
        )

        // The copy, reopened by the real engine: values re-read with listFields.
        open("document/out5.pdf")
        val copySurface = waitForPage(1, 1)
        val copy = session()
        val after = runBlocking { copy.engine.listFields(copy.document.value!!.handle) }.associateBy { it.name }
        assertEquals("name in the copy", listOf("Ada Lovelace"), after.getValue("name").value)
        assertTrue("check box in the copy is on: ${after.getValue("subscribe").value}", after.getValue("subscribe").value == listOf("Yes"))
        assertEquals("radio in the copy", listOf("basic"), after.getValue("plan").value)
        assertEquals("combo in the copy", listOf("de"), after.getValue("country").value)
        assertEquals("read-only id in the copy", listOf("A-1234"), after.getValue("id").value)
        waitUntil("the copy draws the filled name", 20_000) { ink(fieldScreenRect("name")).dark > 15 }
        shot("form_copy_reopened")
        proof("forms: filled name/check/radio/combo through the UI, saved out5.pdf, listFields on the copy = ${after.mapValues { it.value.value }}")
    }

    @Test
    fun w_clearFormKeepsTheReadOnlyValueAndUndoBringsTheValuesBack_darkTheme() {
        open("document/form.pdf")
        waitForPage(1, 1)
        val session = session()
        waitUntil("the form fields were read") { session.forms.value?.state?.value?.fields?.size == 6 }
        val forms = session.forms.value!!
        val handle = session.document.value!!.handle
        shell("cmd uimode night yes")
        try {
            runBlocking {
                assertTrue("text set", forms.setText("name", "Grace Hopper"))
                assertTrue("check box set", forms.setChecked("subscribe", true))
            }
            openMenuItem("Fill form")
            waitUntil("the field outlines are up") { device.hasObject(By.descContains("Text field name")) }
            tap(By.descContains("Text field name"))
            waitUntil("the name field is selected") { forms.state.value.currentName == "name" }
            tap(By.clazz("android.widget.EditText"))
            waitUntil("the keyboard is open") { shell("dumpsys input_method").contains("mInputShown=true") }
            shot("form_editor_dark_keyboard")
            device.pressBack()
            forms.deselect()
            tap(By.text("Form fields"), 5_000)
            assertNotNull("the field list is shown", device.wait(Until.findObject(By.text("Clear form")), 5_000))
            shot("form_field_list_dark")

            // Clear form asks first; Cancel changes nothing.
            tap(By.text("Clear form"))
            assertNotNull("the confirmation is shown", device.wait(Until.findObject(By.text("Clear the whole form?")), 5_000))
            shot("form_clear_confirm_dark")
            clickRetrying("Cancel") { device.findObject(By.text("Cancel")) }
            assertEquals("Cancel kept the values", listOf("Grace Hopper"), runBlocking { session.engine.listFields(handle) }.first { it.name == "name" }.value)
            clickRetrying("Clear form") { device.findObject(By.text("Clear form")) }
            device.wait(Until.findObject(By.text("Clear the whole form?")), 5_000)
            // The confirm button has the same label as the list's entry: it is the last node. A click that
            // lands while the dialog is still animating in is lost (seen once in 14 runs: the dialog stayed up,
            // the controller idle, nothing pending), so the click is repeated until the form is really cleared.
            var lastClick = 0L
            try {
                waitUntil("the form is cleared", 30_000) {
                    if (forms.state.value.field("name")?.value?.all { it.isEmpty() } == true) return@waitUntil true
                    if (SystemClock.uptimeMillis() - lastClick > 2_000 && !forms.state.value.busy) {
                        try {
                            device.findObjects(By.text("Clear form")).lastOrNull()?.let { it.click(); lastClick = SystemClock.uptimeMillis() }
                        } catch (e: androidx.test.uiautomator.StaleObjectException) {
                            // looked up again on the next pass
                        }
                    }
                    false
                }
            } catch (e: AssertionError) {
                shot("w_not_cleared")
                val st = forms.state.value
                throw AssertionError("${e.message}; busy=${st.busy} problem=${st.problem} values=${st.fields.associate { it.name to it.value }}; screen=${screenTexts()}")
            }
            val cleared = runBlocking { session.engine.listFields(handle) }.associateBy { it.name }
            assertEquals("Clear form left the read-only value (resetForm(null))", listOf("A-1234"), cleared.getValue("id").value)
            assertTrue("the check box is unchecked again: ${cleared.getValue("subscribe").value}", cleared.getValue("subscribe").value.all { it.isEmpty() || it == "Off" })

            // Undo brings the values back and the controller reloads.
            instrumentation.runOnMainSync { session.undo() }
            waitUntil("undo restored the name in the controller") { forms.state.value.field("name")?.value == listOf("Grace Hopper") }
        } finally {
            shell("cmd uimode night no")
        }
    }

    @Test
    fun x_fillFormIsOnlyOfferedWhenTheDocumentHasFillableFields() {
        open("document/text3.pdf")
        waitForInk(waitForPage(1, 3))
        val session = session()
        waitUntil("the form read finished", 10_000) { session.forms.value?.state?.value?.loaded == true }
        tap(By.desc("More"), 5_000)
        assertNotNull("the menu is open", device.wait(Until.findObject(By.text("Contents")), 5_000))
        assertFalse("no Fill form entry on a document without form fields", device.hasObject(By.text("Fill form")))
        device.pressBack()
    }

    // ---- (y, z) scan and images to PDF. The camera itself cannot be tested on this emulator. ----

    @Test
    fun y_sharedImagesBecomePagesCropLookSaveAndReopenAsPdf() {
        shell("rm -f /data/data/${instrumentation.context.packageName}/cache/out6.pdf")
        shareImages("docdark.png", "plain.png")
        val controller = scanController()
        waitForScanPages(controller, 2)
        assertNotNull("the page list shows page 1 of 2", device.wait(Until.findObject(By.text("Page 1 of 2")), LONG))
        assertNotNull("the page list shows page 2 of 2", device.wait(Until.findObject(By.text("Page 2 of 2")), LONG))
        assertFalse("the camera permission was not asked for an images-to-PDF flow", device.hasObject(By.text("Scan with your camera")))
        proof("scan: ${controller.state.value.pages.size} shared pictures became pages; bytes kept in memory: " +
            controller.state.value.pages.map { it.source.bytes.size })
        shot("scan_page_list")
        val pss = totalPss(shell("dumpsys meminfo ${context.packageName}"))
        proof("scan: app TOTAL PSS after adding two images = $pss kB")

        // Rotation keeps the pages.
        try {
            device.setOrientationLeft()
            SystemClock.sleep(1_500)
            assertEquals("the controller still holds both pages after rotation", 2, scanController().state.value.pages.size)
            assertNotNull("page 1 is still listed in landscape", device.wait(Until.findObject(By.text("Page 1 of 2")), LONG))
        } finally {
            device.setOrientationNatural()
            SystemClock.sleep(1_000)
        }

        // The crop screen of page 1: four corner handles; drag the top-left one inwards.
        clickUntil("Edit page 1", "the crop screen opened") { device.hasObject(By.desc("Top left corner")) }
        for (corner in listOf("Top left corner", "Top right corner", "Bottom right corner", "Bottom left corner")) {
            assertNotNull("handle: $corner", device.wait(Until.findObject(By.desc(corner)), 5_000))
        }
        shot("scan_crop_handles")
        val before = controller.state.value.pages[0].quad
        val handle = boundsOf(By.desc("Top left corner"), "the top-left handle")
        val x0 = handle.centerX()
        val y0 = handle.centerY()
        waitUntil("the top-left handle was dragged", 15_000) {
            device.swipe(x0, y0, x0 + 90, y0 + 120, 20)
            tap(By.text("Apply crop"), 5_000)
            val q = controller.state.value.pages[0].quad
            q.topLeft.x > before.topLeft.x + 5f && q.topLeft.y > before.topLeft.y + 5f
        }
        val moved = controller.state.value.pages[0].quad
        proof("scan: crop handle dragged: top-left ${before.topLeft} -> ${moved.topLeft}")
        waitUntil("the cropped page is processed", 30_000) { controller.state.value.busy == null && controller.state.value.pages[0].processed }

        // The look: Black and white.
        tap(By.text("Look"), 5_000)
        shot("scan_look_picker")
        tapUntil(By.text("Black and white"), "page 1 is black and white", 30_000) {
            controller.state.value.pages[0].look == app.pdfa.engine.ScanLook.BLACK_WHITE
        }
        waitUntil("the look is applied", 30_000) { controller.state.value.busy == null }
        tap(By.text("Use this look on all pages"), 5_000)
        waitUntil("both pages are black and white", 60_000) {
            controller.state.value.busy == null && controller.state.value.pages.all { it.look == app.pdfa.engine.ScanLook.BLACK_WHITE }
        }
        shot("scan_look_applied")

        // Build and save: what the Save button and the create-document picker's result do.
        val bytes = runBlocking { controller.buildPdf() }
        assertNotNull("the PDF was built", bytes)
        assertTrue("the output is a PDF", bytes!!.decodeToString(0, 5) == "%PDF-")
        val holder = scanHolder()
        instrumentation.runOnMainSync { holder.stage("Scan test.pdf", bytes) }
        instrumentation.runOnMainSync { holder.saveTo(SCAN_BASE + "out6.pdf") }
        waitUntil("the save finished and the pages were cleared", 30_000) { controller.state.value.pages.isEmpty() }
        assertNotNull("the saved dialog offers to open the PDF", device.wait(Until.findObject(By.text("PDF saved")), LONG))
        shot("scan_saved")
        tap(By.text("Open"), 5_000)

        waitForPage(1, 2)
        val sessionHandle = session()
        waitUntil("the saved PDF has two pages") { sessionHandle.document.value?.state?.value?.pageCount == 2 }
        val first = waitForInkAnywhere()
        proof("scan: saved out6.pdf reopened with 2 pages; page 1 ink $first")
        var tries = 0
        while (currentPage() != 2 && tries++ < 8) {
            device.swipe(device.displayWidth / 2, device.displayHeight * 3 / 4, device.displayWidth / 2, device.displayHeight / 4, 15)
            SystemClock.sleep(400)
        }
        assertEquals("scrolled to page 2", 2, currentPage())
        val second = waitForInkAnywhere()
        proof("scan: page 2 ink $second")
        shot("scan_reopened_page2")
    }

    @Test
    fun ya_aFailedSaveKeepsThePagesAndSaysSo_thenTheSaveWorks() {
        shell("rm -f /data/data/${instrumentation.context.packageName}/cache/out7.pdf")
        shareImages("plain.png")
        val controller = scanController()
        waitUntil("the shared picture is a page", 30_000) { controller.state.value.pages.size == 1 }
        val holder = scanHolder()
        val bytes = runBlocking { controller.buildPdf() }!!
        instrumentation.runOnMainSync { holder.stage("Scan test.pdf", bytes) }
        // missing.pdf is neither a fixture nor a save target: the provider refuses the write.
        instrumentation.runOnMainSync { holder.saveTo(SCAN_BASE + "missing.pdf") }
        assertNotNull("the failure is stated plainly", device.wait(Until.findObject(By.text("Could not save")), LONG))
        assertTrue("and says the pages are kept", device.wait(Until.hasObject(By.textContains("still here")), 5_000))
        assertFalse("success is not claimed", device.hasObject(By.text("PDF saved")))
        assertEquals("the page is still there", 1, controller.state.value.pages.size)
        shot("scan_save_failed")
        tap(By.text("Keep pages"), 5_000)
        instrumentation.runOnMainSync { holder.saveTo(SCAN_BASE + "out7.pdf") }
        waitUntil("the second save cleared the pages", 30_000) { controller.state.value.pages.isEmpty() }
        assertNotNull("the saved dialog", device.wait(Until.findObject(By.text("PDF saved")), LONG))
        tap(By.text("Close"), 5_000)
        assertNotNull("Close goes back to Home", device.wait(Until.findObject(HOME), LONG))
    }

    @Test
    fun yb_pageListInDarkTheme() {
        shell("cmd uimode night yes")
        shareImages("pagelight.png", "plain.png")
        val controller = scanController()
        waitForScanPages(controller, 2)
        assertNotNull("the page list", device.wait(Until.findObject(By.text("Page 1 of 2")), LONG))
        SystemClock.sleep(800)
        shot("scan_page_list_dark")
        clickUntil("Edit page 1", "the crop screen opened") { device.hasObject(By.desc("Top left corner")) }
        SystemClock.sleep(500)
        shot("scan_crop_dark")
    }

    @Test
    fun yc_theChangeOfConfigurationRightAfterAShareDoesNotLoseTheSharedImages() {
        // The share intent is parsed in onCreate and consumed by the composition. Recreating the activity in
        // between (night mode here, rotation or a scheduled theme in real life) must not drop the request.
        repeat(3) { round ->
            device.pressHome()
            shareImages("docdark.png", "plain.png")
            // Recreate the new activity as early as it exists: before its first composition could consume the request.
            var recreated = false
            val deadline = SystemClock.uptimeMillis() + 10_000
            while (!recreated && SystemClock.uptimeMillis() < deadline) {
                instrumentation.runOnMainSync {
                    val monitor = androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry.getInstance()
                    val activity = androidx.test.runner.lifecycle.Stage.values()
                        .flatMap { monitor.getActivitiesInStage(it) }
                        .filterIsInstance<MainActivity>()
                        .firstOrNull { it.intent?.action == Intent.ACTION_SEND_MULTIPLE && !it.isFinishing }
                    if (activity != null) { activity.recreate(); recreated = true }
                }
            }
            assertTrue("the activity was recreated (round $round)", recreated)
            val controller = scanController()
            waitForScanPages(controller, 2)
            assertNotNull("the page list is shown after recreation (round $round)", device.wait(Until.findObject(By.text("Page 2 of 2")), LONG))
            scanHolder().let { h -> instrumentation.runOnMainSync { h.release() } }
        }
    }

    @Test
    fun yd_aShareOfMoreThanThirtyPicturesSaysHowManyWereAdded() {
        shareImages(*Array(31) { "plain.png" })
        val controller = scanController()
        assertNotNull("the cap is stated", device.wait(Until.findObject(By.text("Too many pictures")), LONG))
        assertNotNull("with the number", device.findObject(By.textContains("first 30")))
        shot("scan_capped")
        waitForScanPages(controller, 30)
        assertEquals("exactly 30 pages", 30, controller.state.value.pages.size)
        tap(By.text("OK"), 5_000)
    }

    @Test
    fun za_cornerDetectionOnTheRealEngineFindsTheDocumentOrFallsBackToTheWholeFrame() {
        val controller = ScanController(container.engineProvider.engine, app.pdfa.scan.AndroidFrameCodec(), app.pdfa.scan.ScanClock { java.time.LocalDate.now() })
        for (name in listOf("docdark.png", "pagelight.png")) {
            val bitmap = instrumentation.context.assets.open(name).use { android.graphics.BitmapFactory.decodeStream(it) }
            val frame = bitmap.toFrame()
            bitmap.recycle()
            assertTrue("capture of $name accepted", runBlocking { controller.addCapture(frame) })
            val page = controller.state.value.pages.last()
            val q = page.quad
            val k = page.source.width / 900f
            val expected = listOf(Pt(180f * k, 240f * k), Pt(720f * k, 240f * k), Pt(720f * k, 960f * k), Pt(180f * k, 960f * k))
            val got = listOf(q.topLeft, q.topRight, q.bottomRight, q.bottomLeft)
            val worst = got.zip(expected).maxOf { (a, b) -> Math.hypot((a.x - b.x).toDouble(), (a.y - b.y).toDouble()) }
            val full = listOf(Pt(0f, 0f), Pt(page.source.width.toFloat(), 0f), Pt(page.source.width.toFloat(), page.source.height.toFloat()), Pt(0f, page.source.height.toFloat()))
            val worstFull = got.zip(full).maxOf { (a, b) -> Math.hypot((a.x - b.x).toDouble(), (a.y - b.y).toDouble()) }
            val verdict = when {
                worst <= 30.0 * k -> "DETECTED the rectangle (worst corner error ${"%.1f".format(worst)} px of ${page.source.width}x${page.source.height})"
                worstFull <= 2.0 -> "FELL BACK to the whole frame"
                else -> "NEITHER: corners $got, worst error to the rectangle ${"%.1f".format(worst)} px"
            }
            proof("scan corners $name: $verdict")
            assertTrue("$name: $verdict", worst <= 30.0 * k || worstFull <= 2.0)
        }
        assertEquals("both captures became pages", 2, controller.state.value.pages.size)
        assertTrue("the rectified result of a capture is a JPEG", controller.state.value.pages.all { it.result.bytes[0] == 0xFF.toByte() && it.result.bytes[1] == 0xD8.toByte() })
    }

    @Test
    fun zb_webpAndExifJpegAreConvertedAndCarryNoMetadata() {
        // The test runs in the app process: it cannot write the test package's cache, but the provider accepts
        // writes to names starting with "out", so the fixtures are made locally and copied through it.
        val cache = context.cacheDir
        // A photo-like JPEG with EXIF: GPS position, make, and orientation 6 (turn 90 degrees).
        val bmp = Bitmap.createBitmap(400, 300, Bitmap.Config.ARGB_8888)
        android.graphics.Canvas(bmp).apply {
            drawColor(android.graphics.Color.rgb(200, 190, 170))
            drawRect(60f, 50f, 340f, 250f, android.graphics.Paint().apply { color = android.graphics.Color.rgb(30, 40, 60) })
        }
        val jpgFile = java.io.File(cache, "exiftmp.jpg")
        jpgFile.outputStream().use { check(bmp.compress(Bitmap.CompressFormat.JPEG, 90, it)) }
        @Suppress("DEPRECATION")
        android.media.ExifInterface(jpgFile.path).apply {
            setAttribute(android.media.ExifInterface.TAG_GPS_LATITUDE, "48/1,51/1,30/1")
            setAttribute(android.media.ExifInterface.TAG_GPS_LATITUDE_REF, "N")
            setAttribute(android.media.ExifInterface.TAG_GPS_LONGITUDE, "2/1,21/1,7/1")
            setAttribute(android.media.ExifInterface.TAG_GPS_LONGITUDE_REF, "E")
            setAttribute(android.media.ExifInterface.TAG_MAKE, "TestMake")
            setAttribute(android.media.ExifInterface.TAG_ORIENTATION, "6")
            saveAttributes()
        }
        val webpFile = java.io.File(cache, "webptmp.webp")
        val webpFormat = if (android.os.Build.VERSION.SDK_INT >= 30) Bitmap.CompressFormat.WEBP_LOSSY else @Suppress("DEPRECATION") Bitmap.CompressFormat.WEBP
        webpFile.outputStream().use { check(bmp.compress(webpFormat, 85, it)) }
        bmp.recycle()
        val original = jpgFile.readBytes()
        for ((name, file) in listOf("outexif.jpg" to jpgFile, "outwebp.webp" to webpFile)) {
            context.contentResolver.openOutputStream(Uri.parse(SCAN_BASE + name), "wt")!!.use { it.write(file.readBytes()) }
        }
        assertTrue("sanity: the source JPEG really has an Exif segment", hasExifSegment(original))
        assertTrue("sanity: the source JPEG really has GPS tags", String(original, Charsets.ISO_8859_1).contains("TestMake"))
        assertEquals("the webp fixture is RIFF/WEBP", "RIFF", String(webpFile.readBytes(), 0, 4, Charsets.ISO_8859_1))

        val controller = ScanController(container.engineProvider.engine, app.pdfa.scan.AndroidFrameCodec(), app.pdfa.scan.ScanClock { java.time.LocalDate.now() })
        val importer = app.pdfa.scan.ImageImporter(context)
        val ok = runBlocking {
            controller.addPickedImages(importer, listOf(Uri.parse(SCAN_BASE + "outexif.jpg"), Uri.parse(SCAN_BASE + "outwebp.webp")))
        }
        val state = controller.state.value
        assertTrue("the import ran", ok)
        assertEquals("nothing was skipped (the WebP was converted)", 0, state.skippedImports)
        assertEquals("both pictures are pages", 2, state.pages.size)
        val jpegPage = state.pages[0]
        val webpPage = state.pages[1]
        assertTrue("stored bytes of the JPEG carry no Exif segment", !hasExifSegment(jpegPage.source.bytes))
        assertFalse("no camera make survived", String(jpegPage.source.bytes, Charsets.ISO_8859_1).contains("TestMake"))
        assertFalse("no Exif marker string survived", String(jpegPage.source.bytes, Charsets.ISO_8859_1).contains("Exif"))
        assertEquals("orientation 6 was applied: width", 300, jpegPage.source.width)
        assertEquals("orientation 6 was applied: height", 400, jpegPage.source.height)
        assertTrue("the WebP page is a JPEG now", webpPage.source.bytes[0] == 0xFF.toByte() && webpPage.source.bytes[1] == 0xD8.toByte())
        assertEquals("the WebP keeps its size", 400, webpPage.source.width)
        assertTrue("the WebP page has no Exif either", !hasExifSegment(webpPage.source.bytes))
        val pdf = runBlocking { controller.buildPdf() }
        assertNotNull("the PDF of the converted pictures was built by the real engine", pdf)
        assertEquals("the PDF starts with %PDF-", "%PDF-", pdf!!.decodeToString(0, 5))
        assertFalse("the PDF holds no Exif marker", String(pdf, Charsets.ISO_8859_1).contains("TestMake"))
        proof("scan import: EXIF JPEG (GPS, make, orientation 6) -> JPEG ${jpegPage.source.width}x${jpegPage.source.height} without APP1; WebP -> JPEG; PDF ${pdf.size} bytes")
        jpgFile.delete()
        webpFile.delete()
    }

    @Test
    fun zc_scanWithoutCameraPermissionExplainsAndOffersPickImagesInstead() {
        openHomeMenuEntry("Scan")
        val title = device.wait(Until.findObject(By.text("Scan with your camera")), LONG)
            ?: device.findObject(By.text("No camera found"))
        if (title == null) { shot("scan_entry_missing"); throw AssertionError("neither the rationale nor the no-camera state; the screen shows: " + screenTexts()) }
        val camera = title.text == "Scan with your camera"
        proof("scan entry: emulator ${if (camera) "has a camera: rationale shown" else "has no camera: the no-camera state shown"}")
        assertNotNull("Pick images instead is offered", device.findObject(By.text("Pick images instead")))
        shot("scan_permission_explanation")
        if (camera) {
            assertTrue("the privacy line is shown", device.hasObject(By.textContains("Nothing leaves your phone")))
            tap(By.text("Allow camera"), 5_000)
            // The system dialog: deny it (a second refusal may make the system skip the dialog).
            waitUntil("the system dialog is answered or the denied state shows", 20_000) {
                if (device.hasObject(By.text("Camera is off"))) return@waitUntil true
                val deny = device.findObject(By.res("com.android.permissioncontroller:id/permission_deny_button"))
                    ?: device.findObject(By.res("com.android.packageinstaller:id/permission_deny_button"))
                    ?: device.findObject(By.textContains("t allow"))
                    ?: device.findObject(By.text("Deny"))
                if (deny != null) { try { deny.click() } catch (e: androidx.test.uiautomator.StaleObjectException) { } }
                false
            }
            assertNotNull("the denied state is explained", device.wait(Until.findObject(By.text("Camera is off")), LONG))
            assertNotNull("Pick images instead stays offered", device.findObject(By.text("Pick images instead")))
            shot("scan_permission_denied")
        }
        assertTrue("the app did not crash", device.hasObject(By.pkg(context.packageName)))
        assertEquals("CAMERA was not granted", "denied", if (instrumentation.targetContext.checkSelfPermission("android.permission.CAMERA") == android.content.pm.PackageManager.PERMISSION_GRANTED) "granted" else "denied")
    }

    // ---- scan helpers ----

    private val SCAN_BASE = "content://app.pdfa.test.pdfs/document/"

    /** A real SEND / SEND_MULTIPLE image intent on fixture URIs, as the share sheet delivers it. */
    private fun shareImages(vararg names: String) {
        val uris = ArrayList(names.map { Uri.parse(SCAN_BASE + it) })
        val intent = (if (uris.size == 1) Intent(Intent.ACTION_SEND).putExtra(Intent.EXTRA_STREAM, uris[0])
        else Intent(Intent.ACTION_SEND_MULTIPLE).putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris))
            .setType("image/*")
            .setClassName(context, MainActivity::class.java.name)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        context.startActivity(intent)
    }

    /** Waits for [n] pages; on timeout says what the controller and the screen held and keeps a screenshot. */
    private fun waitForScanPages(controller: app.pdfa.scan.ScanController, n: Int) {
        try {
            // Look the controller up again on every poll: after a relaunch or a recreation the one captured
            // earlier can belong to an activity that is on its way out, while the screen already shows the pages.
            waitUntil("$n pages", 30_000) {
                controller.state.value.pages.size == n || scanController().state.value.pages.size == n
            }
        } catch (e: AssertionError) {
            shot("scan_pages_timeout")
            val st = scanController().state.value
            throw AssertionError("timed out waiting for $n pages: controller has ${st.pages.size}, busy=${st.busy}, problem=${st.problem}, skipped=${st.skippedImports}; screen: ${screenTexts()}")
        }
    }

    /**
     * The holder of the activity that got the share intent. Not "the resumed activity": right after a
     * relaunch the previous test's activity can still be resumed for a moment, and its holder would
     * be an empty one that never receives the pictures.
     */
    private fun scanHolder(): app.pdfa.app.tools.ScanHolder {
        var holder: app.pdfa.app.tools.ScanHolder? = null
        waitUntil("the scan screen", 30_000) {
            instrumentation.runOnMainSync {
                val monitor = androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry.getInstance()
                val resumed = monitor.getActivitiesInStage(androidx.test.runner.lifecycle.Stage.RESUMED).filterIsInstance<MainActivity>()
                val activity = resumed.lastOrNull { it.intent?.action == Intent.ACTION_SEND || it.intent?.action == Intent.ACTION_SEND_MULTIPLE }
                holder = activity?.let { androidx.lifecycle.ViewModelProvider(it)[app.pdfa.app.tools.ScanHolder::class.java] }
            }
            holder != null
        }
        return holder!!
    }

    private fun scanController(): app.pdfa.scan.ScanController = scanHolder().controller(container, context)

    /** True when the JPEG has an APP1 segment starting with "Exif". */
    private fun hasExifSegment(jpeg: ByteArray): Boolean {
        if (jpeg.size < 4 || jpeg[0] != 0xFF.toByte() || jpeg[1] != 0xD8.toByte()) return false
        var i = 2
        while (i + 4 <= jpeg.size) {
            if (jpeg[i] != 0xFF.toByte()) return false
            val marker = jpeg[i + 1].toInt() and 0xFF
            if (marker == 0xDA || marker == 0xD9) return false // scan data: no more headers
            val len = ((jpeg[i + 2].toInt() and 0xFF) shl 8) or (jpeg[i + 3].toInt() and 0xFF)
            if (marker == 0xE1 && i + 10 <= jpeg.size && String(jpeg, i + 4, 4, Charsets.ISO_8859_1) == "Exif") return true
            i += 2 + len
        }
        return false
    }

    /** Waits until the viewer's page area shows dark pixels (a black-and-white page has black ink). */
    private fun waitForInkAnywhere(): Ink {
        val bounds = boundsOf(By.desc(Pattern.compile("Page \\d+ of \\d+")).clazz("android.view.View"), "the page surface")
        val deadline = SystemClock.uptimeMillis() + LONG
        var last = Ink(0, 0, 0)
        while (SystemClock.uptimeMillis() < deadline) {
            last = ink(bounds)
            if (last.dark > MIN_DARK_PIXELS) return last
            SystemClock.sleep(100)
        }
        throw AssertionError("the page is blank: $last")
    }

    /** Starts the app on Home and opens an entry of the top bar menu. */
    private fun openHomeMenuEntry(label: String) {
        context.startActivity(
            Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK),
        )
        var entry: UiObject2? = null
        for (attempt in 1..5) {
            try {
                device.wait(Until.findObject(HOME), 5_000) ?: continue
                var last: Rect? = null
                var settled = 0
                val deadline = SystemClock.uptimeMillis() + 5_000
                while (settled < 2 && SystemClock.uptimeMillis() < deadline) {
                    SystemClock.sleep(300)
                    val now = try { device.findObject(HOME)?.visibleBounds } catch (e: androidx.test.uiautomator.StaleObjectException) { null }
                    settled = if (now != null && now == last) settled + 1 else 0
                    last = now
                }
                val titleY = last?.centerY() ?: continue
                device.findObjects(By.desc("More")).firstOrNull { Math.abs(it.visibleCenter.y - titleY) < 80 }?.click()
                entry = device.wait(Until.findObject(By.text(label)), 3_000)
                if (entry != null) break
                if (device.hasObject(By.text("Remove from recents"))) device.pressBack()
            } catch (e: androidx.test.uiautomator.StaleObjectException) {
                // look again
            }
            SystemClock.sleep(500)
        }
        if (entry == null) { shot("home_menu_missing"); throw AssertionError("no $label entry; the screen shows: " + screenTexts()) }
        entry.click()
    }

    /** Clicks the node once it exists, looking it up again when the screen changed under the lookup. */
    private fun tap(selector: BySelector, timeoutMs: Long = 10_000) {
        waitUntil("click $selector", timeoutMs) {
            try {
                val node = device.findObject(selector) ?: return@waitUntil false
                node.click()
                true
            } catch (e: androidx.test.uiautomator.StaleObjectException) {
                false
            }
        }
    }

    /** Taps until [done] holds: a tap that lands while the bar is still animating in is lost, so it is repeated. */
    private fun tapUntil(selector: BySelector, what: String, timeoutMs: Long = 30_000, done: () -> Boolean) {
        var last = 0L
        waitUntil(what, timeoutMs) {
            if (done()) return@waitUntil true
            if (SystemClock.uptimeMillis() - last > 1_500) {
                try {
                    device.findObject(selector)?.let { it.click(); last = SystemClock.uptimeMillis() }
                } catch (e: androidx.test.uiautomator.StaleObjectException) {
                    // looked up again on the next pass
                }
            }
            false
        }
    }

    private fun boundsOf(selector: BySelector, what: String, timeoutMs: Long = 10_000): Rect {
        var found: Rect? = null
        waitUntil("$what is shown", timeoutMs) {
            found = try { device.findObject(selector)?.visibleBounds } catch (e: androidx.test.uiautomator.StaleObjectException) { null }
            found != null
        }
        return found!!
    }

    /** A click that looks the node up again when the screen changed under it. */
    private fun clickRetrying(what: String, find: () -> UiObject2?) {
        waitUntil("$what can be clicked", 10_000) {
            try {
                val node = find() ?: return@waitUntil false
                node.click()
                true
            } catch (e: androidx.test.uiautomator.StaleObjectException) {
                false
            }
        }
    }

    private fun openMenuItem(label: String) {
        tap(By.desc("More"), 5_000)
        val item = device.wait(Until.findObject(By.text(label)), 5_000)
        assertNotNull("menu entry $label", item)
        item.click()
    }

    /** Screen rectangle of a field's first widget, with the page scrolled to the top and shown at fit width. */
    private fun fieldScreenRect(name: String): Rect {
        val f = session().forms.value!!.state.value.field(name)!!
        val w = f.widgets.first()
        val r = app.pdfa.app.viewer.widgetToScreen(viewModel().view.value, w.page, w.rect)!!
        val b = boundsOf(By.desc(Pattern.compile("Page 1 of 1")).clazz("android.view.View"), "page 1")
        return Rect((b.left + r.left).toInt(), (b.top + r.top).toInt(), (b.left + r.right).toInt() + 120, (b.top + r.bottom).toInt())
    }

    private fun pagePixel(surface: UiObject2, page: Int, p: Pt): android.graphics.Point {
        val v = viewModel().view.value
        val layout = v.layout!!
        val scale = layout.fitScale(page) * v.pos.zoom
        val b = boundsOrPageSurface(surface)
        return android.graphics.Point(
            (b.left + layout.pageScreenLeft(v.pos) + p.x * scale).toInt(),
            (b.top + layout.pageScreenTop(page, v.pos) + p.y * scale).toInt(),
        )
    }

    private fun isRed(p: Int): Boolean {
        val r = (p shr 16) and 0xFF
        val g = (p shr 8) and 0xFF
        val b = p and 0xFF
        return r > 120 && g < 100 && b < 100 && r - g > 60
    }

    private fun anyRed(shot: Bitmap, x: Int, y: Int, radius: Int): Boolean {
        for (yy in (y - radius)..(y + radius)) for (xx in (x - radius)..(x + radius)) {
            if (xx in 0 until shot.width && yy in 0 until shot.height && isRed(shot.getPixel(xx, yy))) return true
        }
        return false
    }

    /** Red pixels within 6 px of at least 18 of 20 points along the segment (ends excluded). */
    private fun strokeAlong(surface: UiObject2, page: Int, from: Pt, to: Pt): Boolean {
        val shot = instrumentation.uiAutomation.takeScreenshot() ?: return false
        try {
            var hits = 0
            for (i in 1..20) {
                val t = i / 21f
                val p = pagePixel(surface, page, Pt(from.x + (to.x - from.x) * t, from.y + (to.y - from.y) * t))
                if (anyRed(shot, p.x, p.y, 6)) hits++
            }
            return hits >= 18
        } finally {
            shot.recycle()
        }
    }

    private fun redNear(surface: UiObject2, page: Int, at: Pt, radius: Int): Boolean {
        val shot = instrumentation.uiAutomation.takeScreenshot() ?: return false
        try {
            val p = pagePixel(surface, page, at)
            return anyRed(shot, p.x, p.y, radius)
        } finally {
            shot.recycle()
        }
    }

    /** Fraction of the non-text pixels inside page rect [r] that carry the yellow highlight tint. */
    private fun tinted(surface: UiObject2, page: Int, r: app.pdfa.engine.Rect): Float {
        val shot = instrumentation.uiAutomation.takeScreenshot() ?: return 0f
        try {
            val a = pagePixel(surface, page, Pt(r.left, r.top))
            val b = pagePixel(surface, page, Pt(r.right, r.bottom))
            var paper = 0
            var tint = 0
            for (y in a.y + 2 until b.y - 2) for (x in a.x + 2 until b.x - 2) {
                if (x !in 0 until shot.width || y !in 0 until shot.height) continue
                val p = shot.getPixel(x, y)
                val red = (p shr 16) and 0xFF
                val green = (p shr 8) and 0xFF
                val blue = p and 0xFF
                if (red < 150) continue // the letters
                paper++
                if (red > 220 && green > 200 && blue < 215 && red - blue > 25) tint++
            }
            return if (paper == 0) 0f else tint.toFloat() / paper
        } finally {
            shot.recycle()
        }
    }

    private fun mergeController(): app.pdfa.pages.tools.MergeController {
        var holder: app.pdfa.app.tools.MergeHolder? = null
        waitUntil("the merge screen") {
            instrumentation.runOnMainSync {
                val activity = androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry.getInstance()
                    .getActivitiesInStage(androidx.test.runner.lifecycle.Stage.RESUMED).firstOrNull() as? MainActivity
                holder = activity?.let { androidx.lifecycle.ViewModelProvider(it)[app.pdfa.app.tools.MergeHolder::class.java] }
            }
            holder != null
        }
        return holder!!.controller(container)
    }

    /**
     * Clicks the node described by [desc] and waits for [done]; looks the node up again and clicks again
     * when nothing happened (a click on a layout that was still changing is lost).
     */
    private fun clickUntil(desc: String, what: String, done: () -> Boolean) {
        repeat(5) {
            try {
                device.wait(Until.findObject(By.desc(desc)), 5_000)?.click()
            } catch (e: androidx.test.uiautomator.StaleObjectException) {
                // look it up again
            }
            val deadline = SystemClock.uptimeMillis() + 2_000
            while (SystemClock.uptimeMillis() < deadline) {
                if (done()) return
                SystemClock.sleep(50)
            }
        }
        throw AssertionError("timed out waiting for: $what (after clicking '$desc')")
    }

    private fun screenTexts(): List<String> {
        val out = java.io.ByteArrayOutputStream()
        device.dumpWindowHierarchy(out)
        return Regex("(?:text|content-desc)=\"([^\"]+)\"").findAll(out.toString()).map { it.groupValues[1] }.toList()
    }

    /** Waits for text containing [part]; on failure says what text the screen did show. */
    private fun expectText(part: String) {
        // Text changed by the app (or set from outside) can take a moment to reach UiAutomator's view of
        // the screen; a screen capture round trip lets it catch up, like every shot() in these tests does.
        SystemClock.sleep(300)
        shell("screencap -p $SHOTS/_sync.png")
        if (device.wait(Until.findObject(By.textContains(part)), 5_000) != null) return
        throw AssertionError("no text containing '$part'; the screen shows: " + screenTexts())
    }

    /** Types into the last text field on screen (the password field under the range fields). */
    private fun typeIntoLastField(text: String) {
        waitUntil("the last text field accepts text") {
            val field = device.findObjects(By.clazz("android.widget.EditText").enabled(true)).lastOrNull() ?: return@waitUntil false
            field.text = text
            SystemClock.sleep(200)
            device.findObjects(By.clazz("android.widget.EditText")).lastOrNull()?.text?.length == text.length
        }
    }

    /** Replaces the text of the (only) text field on screen. */
    private fun typeIntoField(text: String) {
        waitUntil("the text field accepts text") {
            val field = device.findObject(By.clazz("android.widget.EditText").enabled(true)) ?: return@waitUntil false
            field.text = text
            SystemClock.sleep(200)
            device.findObject(By.clazz("android.widget.EditText"))?.text == text
        }
    }

    // ---- helpers ----

    private fun session(): app.pdfa.app.viewer.ViewerSession {
        var found: app.pdfa.app.viewer.ViewerSession? = null
        waitUntil("an open document with a session") {
            instrumentation.runOnMainSync {
                val activity = androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry.getInstance()
                    .getActivitiesInStage(androidx.test.runner.lifecycle.Stage.RESUMED).firstOrNull() as? MainActivity
                found = activity?.let { androidx.lifecycle.ViewModelProvider(it)[app.pdfa.app.viewer.ViewerSessions::class.java].peek() }
            }
            found?.document?.value != null
        }
        return found!!
    }

    /** Which ORIGINAL page (1-based, from its UNIQnnnX word) sits at each of the first [count] positions. */
    private fun orderOf(session: app.pdfa.app.viewer.ViewerSession, count: Int): List<Int> = runBlocking {
        val handle = session.document.value!!.handle
        (0 until count).map { position ->
            (1..12).firstOrNull { n ->
                session.engine.searchPage(handle, position, "UNIQ%03dX".format(n)).isNotEmpty()
            } ?: -1
        }
    }

    private fun firstPageIsLandscape(): Boolean {
        val layout = viewModel().view.value.layout ?: return false
        return layout.pageHeight(0) < layout.pageWidth
    }

    private fun waitUntil(what: String, timeoutMs: Long = 15_000, condition: () -> Boolean) {
        val deadline = SystemClock.uptimeMillis() + timeoutMs
        while (SystemClock.uptimeMillis() < deadline) {
            if (condition()) return
            SystemClock.sleep(50)
        }
        throw AssertionError("timed out waiting for: $what")
    }

    private var descAt = 0L

    /** Starts MainActivity with a VIEW intent on the fixture. Returns the uptime at the start. */
    private fun open(name: String): Long {
        val intent = Intent(Intent.ACTION_VIEW)
            .setDataAndType(Uri.parse("content://app.pdfa.test.pdfs/$name"), "application/pdf")
            .setClassName(context, MainActivity::class.java.name)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        val t0 = SystemClock.uptimeMillis()
        context.startActivity(intent)
        return t0
    }

    private fun pageSelector(page: Int, count: Int): BySelector = By.desc("Page $page of $count").clazz("android.view.View")

    /** The page surface once it describes [page]. Also records when that happened. */
    private fun waitForPage(page: Int, count: Int): UiObject2 {
        val node = device.wait(Until.findObject(pageSelector(page, count)), LONG)
        descAt = SystemClock.uptimeMillis()
        assertNotNull("viewer on page $page of $count", node)
        return node
    }

    private fun currentPage(): Int {
        val pattern = Pattern.compile("Page (\\d+) of \\d+")
        val desc = device.findObject(By.desc(pattern).clazz("android.view.View"))?.contentDescription ?: return -1
        return pattern.matcher(desc).let { if (it.matches()) it.group(1)!!.toInt() else -1 }
    }

    private class Ink(val dark: Int, val light: Int, val total: Int) {
        override fun toString() = "dark=$dark light=$light of $total sampled pixels"
    }

    /** Samples every 2nd pixel of the node's on-screen rectangle in a screenshot. */
    private fun ink(node: UiObject2): Ink = ink(boundsOrPageSurface(node))

    /** The node's bounds; when the node went stale (the page redrew), the page surface's, which is what callers pass. */
    private fun boundsOrPageSurface(node: UiObject2): Rect =
        try {
            node.visibleBounds
        } catch (e: androidx.test.uiautomator.StaleObjectException) {
            boundsOf(By.desc(Pattern.compile("Page \\d+ of \\d+")).clazz("android.view.View"), "the page surface")
        }

    private fun ink(bounds: Rect): Ink {
        val shot: Bitmap = instrumentation.uiAutomation.takeScreenshot() ?: return Ink(0, 0, 0)
        var dark = 0
        var light = 0
        var total = 0
        val right = minOf(bounds.right, shot.width) - 120 // keep clear of the fast scroller
        val bottom = minOf(bounds.bottom, shot.height)
        var y = bounds.top
        while (y < bottom) {
            var x = bounds.left
            while (x < right) {
                val p = shot.getPixel(x, y)
                val lum = (((p shr 16) and 0xFF) * 299 + ((p shr 8) and 0xFF) * 587 + (p and 0xFF) * 114) / 1000
                if (lum < 90) dark++ else if (lum > 235) light++
                total++
                x += 2
            }
            y += 2
        }
        shot.recycle()
        return Ink(dark, light, total)
    }

    /** Waits until rendered text is on screen. Returns the uptime of the first screenshot that had it. */
    private fun waitForInk(node: UiObject2): Long {
        val bounds = boundsOrPageSurface(node)
        val deadline = SystemClock.uptimeMillis() + LONG
        var last = Ink(0, 0, 0)
        while (SystemClock.uptimeMillis() < deadline) {
            val at = SystemClock.uptimeMillis()
            last = ink(bounds)
            if (last.dark > MIN_DARK_PIXELS) return at
            SystemClock.sleep(30)
        }
        throw AssertionError("no rendered text within $LONG ms: $last")
    }

    /** A short drag, enough to show the fast scroller and its badge. */
    /** A small scroll in the middle of the screen: shows the badge and the scroller, without holding a node that goes stale. */
    private fun nudgeScreen() {
        device.swipe(device.displayWidth / 2, device.displayHeight / 2, device.displayWidth / 2, device.displayHeight / 2 - 60, 10)
    }

    private fun nudge(surface: UiObject2) {
        val b = boundsOrPageSurface(surface)
        device.swipe(b.centerX(), b.centerY(), b.centerX(), b.centerY() - 60, 10)
    }

    private fun fling(surface: UiObject2, times: Int) {
        val b = boundsOrPageSurface(surface)
        repeat(times) {
            device.swipe(b.centerX(), b.bottom - 200, b.centerX(), b.top + 200, 6)
            SystemClock.sleep(350)
        }
    }

    private fun setScrollProgress(fraction: Float) {
        val deadline = SystemClock.uptimeMillis() + 3_000
        while (SystemClock.uptimeMillis() < deadline) {
            val node = find(instrumentation.uiAutomation.rootInActiveWindow) { it.contentDescription == "Scroll position" }
            if (node != null) {
                val args = Bundle().apply { putFloat(AccessibilityNodeInfo.ACTION_ARGUMENT_PROGRESS_VALUE, fraction) }
                assertTrue(node.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SET_PROGRESS.id, args))
                return
            }
            SystemClock.sleep(50)
        }
        throw AssertionError("the fast scroller is not on screen")
    }

    private fun find(node: AccessibilityNodeInfo?, match: (AccessibilityNodeInfo) -> Boolean): AccessibilityNodeInfo? {
        if (node == null) return null
        if (match(node)) return node
        for (i in 0 until node.childCount) find(node.getChild(i), match)?.let { return it }
        return null
    }

    /** Taps a point given in PDF points from the page's top-left, on page 1 at fit-width zoom, scrolled to the top. */
    private fun tapPagePoint(surface: UiObject2, xPt: Float, yFromTopPt: Float) {
        val v = viewModel().view.value
        val layout = v.layout!!
        val scale = layout.fitScale(0) * v.pos.zoom
        val b = boundsOrPageSurface(surface)
        val x = b.left + layout.pageScreenLeft(v.pos) + xPt * scale
        val y = b.top + layout.pageScreenTop(0, v.pos) + yFromTopPt * scale
        device.click(x.toInt(), y.toInt())
    }

    /**
     * The search counter, checked on the controller (the real condition) and then on screen. The
     * accessibility tree of the bar can lag behind what is drawn (seen: the screenshot showed the
     * counter while the tree still said "Searching page 1 of 3"), so a small nudge re-sends it.
     */
    private fun expectSearchCounter(text: String, what: String) {
        waitUntil("$what (controller)", LONG) {
            session().addOns.value?.search?.state?.value?.counter()?.let { "${it.position} of ${it.total}" } == text
        }
        val deadline = SystemClock.uptimeMillis() + LONG
        var found: UiObject2? = null
        while (found == null && SystemClock.uptimeMillis() < deadline) {
            found = device.wait(Until.findObject(By.text(text)), 3_000)
            if (found == null) nudgeScreen()
        }
        if (found == null) {
            shot("search_counter_missing")
            throw AssertionError("$what: the controller shows $text but the screen does not: ${screenTexts()}")
        }
    }

    private fun searchFor(query: String) {
        tap(By.desc("Search"), 5_000)
        val field = device.wait(Until.findObject(By.clazz("android.widget.EditText")), 5_000)
        assertNotNull("search field", field)
        field.text = query
    }

    /** The EditsLost dialog of :app. Checks its wording and closes it. */
    private fun dismissEditsLost(expect: String) {
        val body = device.wait(Until.findObject(By.textContains("changes since the last autosave were lost")), 40_000)
        assertNotNull("the EditsLost dialog", body)
        assertTrue(body.text, body.text.contains(expect))
        assertTrue(body.text, body.text.contains("stays unsaved until you save it"))
        tap(By.text("OK"), 3_000)
        device.wait(Until.gone(By.textContains("changes since the last autosave were lost")), 3_000)
    }

    private fun remote(): RemoteEngine = container.engineProvider.engine as RemoteEngine

    private fun viewModel(): app.pdfa.viewer.ViewerViewModel {
        var vm: app.pdfa.viewer.ViewerViewModel? = null
        instrumentation.runOnMainSync {
            val activity = androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry.getInstance()
                .getActivitiesInStage(androidx.test.runner.lifecycle.Stage.RESUMED).first() as MainActivity
            vm = androidx.lifecycle.ViewModelProvider(activity)[app.pdfa.app.viewer.ViewerSessions::class.java].peek()?.viewModel
        }
        return vm ?: throw AssertionError("no open viewer")
    }

    private fun alive(pid: Int): Boolean = shell("su 0 ls -d /proc/$pid").trim() == "/proc/$pid"

    private fun totalPss(meminfo: String): Int? =
        Regex("TOTAL PSS:\\s+(\\d+)").find(meminfo)?.groupValues?.get(1)?.toInt()
            ?: Regex("TOTAL\\s+(\\d+)").find(meminfo)?.groupValues?.get(1)?.toInt()

    private fun shot(name: String) {
        SystemClock.sleep(300)
        shell("screencap -p $SHOTS/$name.png")
    }

    private fun proof(line: String) {
        Log.i("PDFA-PROOF", line)
    }

    private fun shell(command: String): String {
        val pfd: ParcelFileDescriptor = instrumentation.uiAutomation.executeShellCommand(command)
        return FileInputStream(pfd.fileDescriptor).use { it.readBytes().decodeToString() }.also { pfd.close() }
    }

    private companion object {
        /** Home: the app name as the top bar title (the FAB label is not exposed as text). */
        val HOME: BySelector = By.text("Tidepage")
        const val SHOTS = "/data/local/tmp/pdfa-shots"
        const val LONG = 30_000L
        const val MIN_DARK_PIXELS = 400
    }
}
