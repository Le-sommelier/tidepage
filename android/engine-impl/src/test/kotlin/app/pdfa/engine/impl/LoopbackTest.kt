package app.pdfa.engine.impl

import app.pdfa.engine.*
import app.pdfa.engine.fake.FakeEngine
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import kotlin.test.assertFailsWith

/**
 * Every EngineApi method, end to end through RemoteEngine -> real wire codec -> EngineServer ->
 * FakeEngine, compared with the same calls made directly on a second FakeEngine.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class LoopbackTest {
    private val flags = FeatureFlags(encrypt = true)
    private fun direct() = FakeEngine(flags = flags, clock = { 1_000L })

    private class Pair2(val remote: RemoteEngine, val direct: FakeEngine, val h: Harness)

    private suspend fun both(scope: TestScope, bytes: ByteArray = pdf(), pw: String? = null, block: suspend (Pair2, DocHandle, DocHandle) -> Unit) {
        val h = scope.harness()
        val d = direct()
        val rh = h.engine.open(bytes, pw)
        val dh = d.open(bytes, pw)
        block(Pair2(h.engine, d, h), rh, dh)
    }

    private fun bytesOf(b: ByteBuffer): ByteArray { val c = b.duplicate(); c.position(0); return ByteArray(c.remaining()).also { c.get(it) } }

    @Test fun open_and_close() = runTest {
        both(this) { p, rh, dh ->
            p.remote.close(rh)
            assertFailsWith<EngineError.InvalidArgument> { p.remote.meta(rh) }
            assertFailsWith<EngineError.InvalidArgument> { p.remote.close(rh) }
        }
    }

    @Test fun open_password_errors_roundTrip() = runTest {
        val h = harness()
        val enc = pdf(2, password = "pw")
        assertTrue(assertFailsWith<EngineError> { h.engine.open(enc, null) } === EngineError.PasswordRequired)
        assertTrue(assertFailsWith<EngineError> { h.engine.open(enc, "bad") } === EngineError.WrongPassword)
        val ok = h.engine.open(enc, "pw")
        assertEquals(2, h.engine.meta(ok).pageCount)
    }

    @Test fun open_invalidPdf() = runTest {
        val h = harness()
        val e = assertFailsWith<EngineError.InvalidPdf> { h.engine.open(byteArrayOf(1, 2, 3)) }
        val d = assertFailsWith<EngineError.InvalidPdf> { direct().open(byteArrayOf(1, 2, 3)) }
        assertEquals(d.detail, e.detail)
    }

    @Test fun meta() = runTest { both(this) { p, rh, dh -> assertEquals(p.direct.meta(dh), p.remote.meta(rh)) } }
    @Test fun pageInfos() = runTest { both(this) { p, rh, dh -> assertEquals(p.direct.pageInfos(dh), p.remote.pageInfos(rh)) } }

    @Test fun renderTile() = runTest {
        both(this) { p, rh, dh ->
            val req = TileRequest(1, 1f, 0, 0, 40, 30)
            val a = p.remote.renderTile(rh, req); val b = p.direct.renderTile(dh, req)
            assertEquals(b.width, a.width); assertEquals(b.height, a.height)
            assertArrayEquals(bytesOf(b.premultipliedRgba), bytesOf(a.premultipliedRgba))
            val prev = p.remote.renderTile(rh, req.copy(preview = true))
            assertArrayEquals(bytesOf(p.direct.renderTile(dh, req.copy(preview = true)).premultipliedRgba), bytesOf(prev.premultipliedRgba))
        }
    }

    @Test fun renderTile_errorsRoundTrip() = runTest {
        both(this) { p, rh, _ ->
            assertFailsWith<EngineError.InvalidArgument> { p.remote.renderTile(rh, TileRequest(9, 1f, 0, 0, 10, 10)) }
        }
    }

    @Test fun searchPage() = runTest {
        both(this) { p, rh, dh ->
            assertEquals(p.direct.searchPage(dh, 0, "page"), p.remote.searchPage(rh, 0, "page"))
            assertEquals(p.direct.searchPage(dh, 0, "PAGE", caseSensitive = true), p.remote.searchPage(rh, 0, "PAGE", caseSensitive = true))
            assertEquals(p.direct.searchPage(dh, 1, "two", wholeWord = true), p.remote.searchPage(rh, 1, "two", wholeWord = true))
            assertTrue(p.remote.searchPage(rh, 0, "page").isNotEmpty())
        }
    }

    @Test fun selectText() = runTest {
        both(this) { p, rh, dh ->
            assertEquals(p.direct.selectText(dh, 0, Pt(2f, 5f), Pt(2f, 5f)), p.remote.selectText(rh, 0, Pt(2f, 5f), Pt(2f, 5f)))
            assertEquals(p.direct.selectText(dh, 0, Pt(2f, 5f), Pt(50f, 5f)), p.remote.selectText(rh, 0, Pt(2f, 5f), Pt(50f, 5f)))
            assertNull(p.remote.selectText(rh, 0, Pt(500f, 700f), Pt(500f, 700f)))
        }
    }

    @Test fun outline() = runTest {
        val bytes = FakeEngine.fakePdf(
            pages = List(3) { app.pdfa.engine.fake.FakePage(612f, 792f, text = "t$it") },
            outline = listOf(app.pdfa.engine.fake.FakeOutline(listOf(0), "One", 0, true), app.pdfa.engine.fake.FakeOutline(listOf(1), "Two", 2, false)),
        )
        both(this, bytes) { p, rh, dh ->
            assertEquals(p.direct.outline(dh), p.remote.outline(rh))
            assertEquals(p.direct.outline(dh, 1, 1), p.remote.outline(rh, 1, 1))
        }
    }

    @Test fun links() = runTest {
        val bytes = FakeEngine.fakePdf(listOf(
            app.pdfa.engine.fake.FakePage(612f, 792f, links = listOf(PageLink(Rect(0f, 0f, 10f, 10f), LinkTarget.Page(1)), PageLink(Rect(1f, 1f, 2f, 2f), LinkTarget.Uri("https://x.test")))),
            app.pdfa.engine.fake.FakePage(612f, 792f),
        ))
        both(this, bytes) { p, rh, dh ->
            val a = p.remote.links(rh, 0)
            assertEquals(p.direct.links(dh, 0), a)
            assertEquals(2, a.size)
        }
    }

    @Test fun state_rotate_delete_move_duplicate_insertBlank_undo_redo() = runTest {
        both(this) { p, rh, dh ->
            assertEquals(p.direct.state(dh), p.remote.state(rh))
            assertEquals(p.direct.rotate(dh, listOf(0), Rotation.CW90), p.remote.rotate(rh, listOf(0), Rotation.CW90))
            assertEquals(p.direct.duplicate(dh, listOf(1)), p.remote.duplicate(rh, listOf(1)))
            assertEquals(p.direct.insertBlank(dh, 0, 100f, 200f), p.remote.insertBlank(rh, 0, 100f, 200f))
            assertEquals(p.direct.move(dh, listOf(0), 2), p.remote.move(rh, listOf(0), 2))
            assertEquals(p.direct.delete(dh, listOf(1)), p.remote.delete(rh, listOf(1)))
            assertEquals(p.direct.undo(dh), p.remote.undo(rh))
            assertEquals(p.direct.redo(dh), p.remote.redo(rh))
            assertEquals(p.direct.pageInfos(dh), p.remote.pageInfos(rh))
        }
    }

    @Test fun editErrorsRoundTrip() = runTest {
        both(this) { p, rh, dh ->
            val a = assertFailsWith<EngineError.InvalidArgument> { p.remote.move(rh, listOf(0), 99) }
            val b = assertFailsWith<EngineError.InvalidArgument> { p.direct.move(dh, listOf(0), 99) }
            assertEquals(b.detail, a.detail)
            assertFailsWith<EngineError.InvalidArgument> { p.remote.delete(rh, listOf(0, 1, 2)) }
        }
    }

    @Test fun addMarkup() = runTest {
        both(this) { p, rh, dh ->
            val ink = NewMarkup(0, MarkupKind.INK, 0xFF0000, 1f, 2f, strokes = listOf(Stroke(listOf(Pt(1f, 1f), Pt(5f, 5f)))))
            val hl = NewMarkup(1, MarkupKind.HIGHLIGHT, 0xFFFF00, 0.5f, 1f, rects = listOf(Rect(0f, 0f, 20f, 14f)))
            val note = NewMarkup(2, MarkupKind.NOTE, 0x00FF00, 1f, 1f, rects = listOf(Rect(5f, 5f, 6f, 6f)), contents = "héllo", author = "me")
            for (m in listOf(ink, hl, note)) assertEquals(p.direct.addMarkup(dh, m), p.remote.addMarkup(rh, m))
            assertFailsWith<EngineError.InvalidArgument> { p.remote.addMarkup(rh, ink.copy(opacity = 3f)) }
        }
    }

    private fun formPdf() = FakeEngine.fakePdf(
        pages = listOf(app.pdfa.engine.fake.FakePage(612f, 792f)),
        fields = listOf(
            app.pdfa.engine.fake.FakeField("name", FieldKind.TEXT, widgets = listOf(FieldWidget(0, Rect(1f, 1f, 50f, 20f), null))),
            app.pdfa.engine.fake.FakeField("agree", FieldKind.CHECK_BOX, widgets = listOf(FieldWidget(0, Rect(1f, 30f, 10f, 40f), "Yes"))),
        ),
    )

    @Test fun listFields_setField_resetForm() = runTest {
        both(this, formPdf()) { p, rh, dh ->
            assertEquals(p.direct.listFields(dh), p.remote.listFields(rh))
            assertEquals(p.direct.setField(dh, "name", FieldValue.Text("Zoë")), p.remote.setField(rh, "name", FieldValue.Text("Zoë")))
            assertEquals(p.direct.setField(dh, "agree", FieldValue.Check(true)), p.remote.setField(rh, "agree", FieldValue.Check(true)))
            assertEquals(p.direct.listFields(dh), p.remote.listFields(rh))
            assertEquals(p.direct.resetForm(dh, listOf("name")), p.remote.resetForm(rh, listOf("name")))
            assertEquals(p.direct.resetForm(dh, null), p.remote.resetForm(rh, null))
            assertFailsWith<EngineError.InvalidArgument> { p.remote.setField(rh, "nope", FieldValue.Text("x")) }
        }
    }

    @Test fun saveIncremental_saveFull_confirmSaved() = runTest {
        both(this) { p, rh, dh ->
            p.remote.rotate(rh, listOf(0), Rotation.HALF); p.direct.rotate(dh, listOf(0), Rotation.HALF)
            assertArrayEquals(p.direct.saveIncremental(dh), p.remote.saveIncremental(rh))
            assertArrayEquals(p.direct.saveFull(dh), p.remote.saveFull(rh))
            assertTrue(p.remote.state(rh).dirty)
            assertEquals(p.direct.confirmSaved(dh), p.remote.confirmSaved(rh))
            assertEquals(false, p.remote.state(rh).dirty)
        }
    }

    @Test fun saveProtected_removeProtection() = runTest {
        both(this) { p, rh, dh ->
            val spec = ProtectionSpec("open", "owner", PrintPerm.LOW, ChangePerm.COMMENT_FILL_SIGN, allowCopy = true)
            assertArrayEquals(p.direct.saveProtected(dh, spec), p.remote.saveProtected(rh, spec))
            assertEquals(p.direct.removeProtection(dh), p.remote.removeProtection(rh))
        }
    }

    @Test fun saveProtected_unsupportedWhenFlagOff() = runTest {
        val h = harness(newEngine = { FakeEngine(clock = { 1L }) })
        val doc = h.engine.open(pdf())
        assertFailsWith<EngineError.Unsupported> { h.engine.saveProtected(doc, ProtectionSpec("a", "b")) }
    }

    @Test fun extract() = runTest { both(this) { p, rh, dh -> assertArrayEquals(p.direct.extract(dh, listOf(2, 0)), p.remote.extract(rh, listOf(2, 0))) } }

    @Test fun split() = runTest {
        both(this) { p, rh, dh ->
            for (spec in listOf(SplitSpec.EveryN(2), SplitSpec.Before(listOf(1, 2)))) {
                val a = p.remote.split(rh, spec); val b = p.direct.split(dh, spec)
                assertEquals(b.size, a.size)
                b.zip(a).forEach { (x, y) -> assertEquals(x.name, y.name); assertArrayEquals(x.bytes, y.bytes) }
            }
            assertFailsWith<EngineError.InvalidArgument> { p.remote.split(rh, SplitSpec.EveryN(0)) }
        }
    }

    @Test fun merge() = runTest {
        val h = harness(); val d = direct()
        val sources = listOf(MergeSource("a", pdf(2)), MergeSource("b", pdf(3), ranges = listOf(PageRange(1, 2))), MergeSource("c", pdf(2, "pw"), password = "pw"))
        assertArrayEquals(d.merge(sources), h.engine.merge(sources))
        assertFailsWith<EngineError.InvalidArgument> { h.engine.merge(emptyList()) }
        assertTrue(assertFailsWith<EngineError> { h.engine.merge(listOf(MergeSource("c", pdf(2, "pw")))) } === EngineError.PasswordRequired)
    }

    @Test fun imagesToPdf() = runTest {
        val h = harness(); val d = direct()
        val imgs = listOf(ImageInput("one.png", FakeEngine.fakeImage(100, 200)), ImageInput("two.png", FakeEngine.fakeImage(300, 150)))
        assertArrayEquals(d.imagesToPdf(imgs, 150.0), h.engine.imagesToPdf(imgs, 150.0))
        assertArrayEquals(d.imagesToPdf(imgs, null), h.engine.imagesToPdf(imgs, null))
        assertFailsWith<EngineError.InvalidArgument> { h.engine.imagesToPdf(imgs, 0.1) }
    }

    private fun frame(w: Int = 20, h: Int = 10) = ImageFrame(w, h, ByteArray(w * h * 4) { (it * 7).toByte() })

    @Test fun detectDocumentCorners() = runTest {
        val h = harness(); val d = direct()
        assertEquals(d.detectDocumentCorners(frame()), h.engine.detectDocumentCorners(frame()))
        assertNotNull(h.engine.detectDocumentCorners(frame()))
        assertNull(h.engine.detectDocumentCorners(ImageFrame(4, 4, ByteArray(64))))
    }

    @Test fun rectifyAndClean() = runTest {
        val h = harness(); val d = direct()
        val f = frame(); val q = Quad(Pt(1f, 1f), Pt(18f, 1f), Pt(18f, 8f), Pt(1f, 8f))
        for (look in ScanLook.entries) {
            val a = h.engine.rectifyAndClean(f, q, look, 1); val b = d.rectifyAndClean(f, q, look, 1)
            assertEquals(b.width, a.width); assertEquals(b.height, a.height); assertArrayEquals(b.rgba, a.rgba)
        }
        assertFailsWith<EngineError.InvalidArgument> { h.engine.rectifyAndClean(f, q, ScanLook.COLOUR, 9) }
    }

    @Test fun ping() = runTest {
        val h = harness()
        assertEquals(app.pdfa.engine.protocol.PROTOCOL_VERSION, h.engine.ping().protocolVersion)
    }

    @Test fun progressEventsAreForwarded() = runTest {
        val h = harness()
        val got = mutableListOf<EngineEvent>()
        val job = launch(kotlinx.coroutines.Dispatchers.Unconfined) { h.engine.events.collect { got += it } }
        val doc = h.engine.open(pdf(4))
        h.engine.split(doc, SplitSpec.EveryN(2))
        h.engine.merge(listOf(MergeSource("a", pdf(1)), MergeSource("b", pdf(1))))
        runCurrent()
        job.cancel()
        val progress = got.filterIsInstance<EngineEvent.Progress>()
        assertTrue("got $got", progress.size >= 4)
        assertTrue(progress.all { it.fraction in 0f..1f })
        assertEquals(1f, progress.last().fraction, 0f)
    }

    @Test fun everyEngineApiMethodWasExercised() {
        // Guards against a new EngineApi method that the loopback suite forgets.
        val names = EngineApi::class.java.declaredMethods.map { it.name }.filter { it != "getEvents" }.toSet()
        val covered = setOf(
            "open", "close", "meta", "pageInfos", "renderTile", "searchPage", "selectText", "outline", "links", "state",
            "rotate", "delete", "move", "duplicate", "insertBlank", "undo", "redo", "addMarkup", "listFields", "setField",
            "resetForm", "saveIncremental", "saveFull", "confirmSaved", "saveProtected", "removeProtection", "extract",
            "split", "merge", "imagesToPdf", "detectDocumentCorners", "rectifyAndClean",
        )
        assertEquals(covered, names.filter { !it.contains('$') }.map { it.substringBefore('-') }.toSet())
    }
}
