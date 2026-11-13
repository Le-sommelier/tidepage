package app.pdfa.viewer.extras

import app.pdfa.engine.EngineError
import app.pdfa.engine.Pt
import app.pdfa.engine.Rect
import app.pdfa.engine.TextSelection
import app.pdfa.engine.fake.FakeEngine
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class SelectionControllerTest {

    /** In the fake, glyphs are 8 pt wide and lines 14 pt tall; "hello world" sits on line 0. */
    private class RecordingClipboard : TextClipboard {
        val texts = mutableListOf<String>()
        var accept = true
        override fun setText(text: String): Boolean {
            if (!accept) return false
            texts += text
            return true
        }
    }

    private suspend fun TestScope.setup(
        policy: SelectionPolicy = SelectionPolicy(),
        clipboard: RecordingClipboard = RecordingClipboard(),
    ): Triple<SelectionController, ScriptedEngine, RecordingClipboard> {
        val engine = ScriptedEngine()
        val handle = engine.open(FakeEngine.fakePdf(pageCount = 2, text = listOf("hello world", "second")))
        val controller = SelectionController(
            engine = engine,
            handle = handle,
            dispatcher = StandardTestDispatcher(testScheduler),
            scope = this,
            clipboard = clipboard,
            policy = policy,
        )
        return Triple(controller, engine, clipboard)
    }

    @Test
    fun `a long press selects the word under the point`() = runTest {
        val (c, engine, _) = setup()

        c.longPress(0, Pt(9f, 3f))
        advanceUntilIdle()

        assertEquals("hello", c.state.value.text)
        assertEquals(0, c.state.value.page)
        assertEquals(1, c.state.value.rects.size)
        assertEquals(listOf(Pt(9f, 3f) to Pt(9f, 3f)), engine.selectCalls.map { it.second to it.third })
    }

    @Test
    fun `a long press on a space selects nothing and is not an error`() = runTest {
        val (c, _, _) = setup()

        c.longPress(0, Pt(8f * 5 + 1, 3f))
        advanceUntilIdle()

        assertFalse(c.state.value.hasText)
        assertEquals(0, c.state.value.page)
        assertFalse(c.state.value.copyForbidden)
        assertFalse(c.state.value.failed)
    }

    @Test
    fun `a drag selects the text between the two points`() = runTest {
        val (c, _, _) = setup()

        c.dragSelection(0, Pt(0f, 0f), Pt(8f * 5 + 4, 10f))
        advanceUntilIdle()

        assertEquals("hello ", c.state.value.text)
        assertEquals(1, c.state.value.rects.size)
    }

    @Test
    fun `a range with no text in it leaves the selection empty`() = runTest {
        val (c, _, _) = setup()

        c.dragSelection(0, Pt(500f, 500f), Pt(600f, 600f))
        advanceUntilIdle()

        assertFalse(c.state.value.hasText)
        assertTrue(c.state.value.rects.isEmpty())
    }

    @Test
    fun `NotPermitted becomes copyForbidden with a message instead of an error`() = runTest {
        val (c, engine, clipboard) = setup()
        engine.selectError = EngineError.NotPermitted("copying is not allowed")

        c.longPress(0, Pt(9f, 3f))
        advanceUntilIdle()

        val state = c.state.value
        assertTrue(state.copyForbidden)
        assertEquals(SelectionController.FORBIDDEN_MESSAGE, state.message)
        assertFalse(state.failed)
        assertFalse(c.copyText())
        assertTrue(clipboard.texts.isEmpty())
    }

    @Test
    fun `copy hands the selected text to the clipboard`() = runTest {
        val (c, _, clipboard) = setup()
        c.longPress(0, Pt(9f, 3f))
        advanceUntilIdle()

        assertTrue(c.copyText())

        assertEquals(listOf("hello"), clipboard.texts)
    }

    @Test
    fun `a refused clipboard write is reported in the state and cleared by a later success`() = runTest {
        val clipboard = RecordingClipboard().apply { accept = false }
        val (c, _, _) = setup(clipboard = clipboard)
        c.longPress(0, Pt(9f, 3f))
        advanceUntilIdle()

        assertFalse(c.copyText())
        assertTrue(c.state.value.copyFailed)

        clipboard.accept = true
        assertTrue(c.copyText())
        assertFalse(c.state.value.copyFailed)
    }

    @Test
    fun `copy with nothing selected does nothing`() = runTest {
        val (c, _, clipboard) = setup()

        assertFalse(c.copyText())

        assertTrue(clipboard.texts.isEmpty())
    }

    @Test
    fun `any other engine error marks the selection failed and does not throw`() = runTest {
        val (c, engine, _) = setup()
        engine.selectError = EngineError.Io("read")

        c.longPress(0, Pt(9f, 3f))
        advanceUntilIdle()

        assertTrue(c.state.value.failed)
        assertEquals(SelectionController.FAILED_MESSAGE, c.state.value.message)
        assertFalse(c.state.value.copyForbidden)
    }

    @Test
    fun `a page change clears the selection by default`() = runTest {
        val (c, _, _) = setup()
        c.longPress(0, Pt(9f, 3f))
        advanceUntilIdle()

        c.onPageChanged(1)

        assertFalse(c.state.value.hasText)
        assertNull(c.state.value.page)
    }

    @Test
    fun `a page change to the same page keeps the selection`() = runTest {
        val (c, _, _) = setup()
        c.longPress(0, Pt(9f, 3f))
        advanceUntilIdle()

        c.onPageChanged(0)

        assertEquals("hello", c.state.value.text)
    }

    @Test
    fun `zoom clears the selection by default`() = runTest {
        val (c, _, _) = setup()
        c.longPress(0, Pt(9f, 3f))
        advanceUntilIdle()

        c.onZoomChanged()

        assertFalse(c.state.value.hasText)
    }

    @Test
    fun `the policy can keep the selection through zoom and page changes`() = runTest {
        val (c, _, _) = setup(policy = SelectionPolicy(clearOnPageChange = false, clearOnZoom = false))
        c.longPress(0, Pt(9f, 3f))
        advanceUntilIdle()

        c.onPageChanged(1)
        c.onZoomChanged()

        assertEquals("hello", c.state.value.text)
    }

    @Test
    fun `a new selection replaces the one still in flight`() = runTest {
        val (c, _, _) = setup()

        c.longPress(0, Pt(0f, 3f))
        c.longPress(0, Pt(9f, 3f))
        advanceUntilIdle()

        assertEquals("hello", c.state.value.text)
    }

    @Test
    fun `selecting from a restarted engine uses the new handle`() = runTest {
        val (c, engine, _) = setup()
        val fresh = engine.open(FakeEngine.fakePdf(pageCount = 1, text = listOf("alpha beta")))

        c.onEngineRestarted(fresh)
        c.longPress(0, Pt(9f, 3f))
        advanceUntilIdle()

        assertEquals("alpha", c.state.value.text, "the restarted document is the one searched")
    }

    // ---- resource caps ----

    @Test
    fun `a selection longer than the cap is cut and flagged truncated`() = runTest {
        val (c, engine, clipboard) = setup()
        val big = "a".repeat(10 * 1024 * 1024)
        engine.selectOverride = { TextSelection(big, listOf(Rect(0f, 0f, 1f, 1f))) }

        c.longPress(0, Pt(9f, 3f))
        advanceUntilIdle()

        assertEquals(SelectionController.TEXT_CAP, c.state.value.text.length)
        assertTrue(c.state.value.truncated)
        assertTrue(c.copyText())
        assertEquals(SelectionController.TEXT_CAP, clipboard.texts.single().length)
    }

    @Test
    fun `a selection within the cap is not flagged`() = runTest {
        val (c, _, _) = setup()
        c.longPress(0, Pt(9f, 3f))
        advanceUntilIdle()

        assertFalse(c.state.value.truncated)
    }

    @Test
    fun `a clipboard that refuses the text makes copy return false`() = runTest {
        val clipboard = RecordingClipboard().apply { accept = false }
        val (c, _, _) = setup(clipboard = clipboard)
        c.longPress(0, Pt(9f, 3f))
        advanceUntilIdle()

        assertFalse(c.copyText())
        assertTrue(clipboard.texts.isEmpty())
    }
}
