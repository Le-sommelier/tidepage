package app.pdfa.engine.service

import app.pdfa.engine.DocHandle
import app.pdfa.engine.EngineError
import app.pdfa.engine.FeatureFlags
import app.pdfa.engine.FieldValue
import app.pdfa.engine.ImageFrame
import app.pdfa.engine.ImageInput
import app.pdfa.engine.MarkupKind
import app.pdfa.engine.MergeSource
import app.pdfa.engine.NewMarkup
import app.pdfa.engine.PageRange
import app.pdfa.engine.ProtectionSpec
import app.pdfa.engine.Pt
import app.pdfa.engine.Rotation
import app.pdfa.engine.ScanLook
import app.pdfa.engine.SplitSpec
import app.pdfa.engine.TileRequest
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

class UniffiEngineTest {
    private val backend = FakeBackend()
    private val engine = UniffiEngine(backend)
    private val pdf = byteArrayOf(1, 2, 3)

    private fun open(): DocHandle = runBlocking { engine.open(pdf, null) }
    private fun last() = backend.calls.last()

    private inline fun <reified E : EngineError> failsWith(crossinline block: suspend () -> Unit): E {
        try {
            runBlocking { block() }
        } catch (e: Throwable) {
            if (e is E) return e
            throw AssertionError("expected ${E::class.simpleName}, got $e", e)
        }
        fail("expected ${E::class.simpleName}, nothing was thrown")
        throw IllegalStateException()
    }

    // ---- lifecycle and handles ----

    @Test fun `open passes bytes and password and returns handle 1`() = runBlocking {
        val h = engine.open(pdf, "pw")
        assertEquals(1L, h.id)
        assertEquals("open(3,pw)", last())
    }

    @Test fun `handles count up and are never reused`() = runBlocking {
        val a = engine.open(pdf)
        engine.close(a)
        val b = engine.open(pdf)
        val c = engine.open(pdf)
        assertEquals(listOf(1L, 2L, 3L), listOf(a.id, b.id, c.id))
    }

    @Test fun `close releases the native session once`() = runBlocking {
        val h = open()
        engine.close(h)
        assertEquals(1, backend.sessions[0].closes.get())
        assertEquals(0, engine.openCount())
    }

    @Test fun `double close is safe and closes once`() = runBlocking {
        val h = open()
        engine.close(h)
        engine.close(h)
        assertEquals(1, backend.sessions[0].closes.get())
    }

    @Test fun `closing an unknown handle does nothing`() = runBlocking {
        engine.close(DocHandle(99))
        assertTrue(backend.calls.isEmpty())
    }

    @Test fun `a closed handle is InvalidArgument`() {
        val h = open()
        runBlocking { engine.close(h) }
        failsWith<EngineError.InvalidArgument> { engine.meta(h) }
        failsWith<EngineError.InvalidArgument> { engine.saveFull(h) }
    }

    @Test fun `an unknown handle is InvalidArgument and never reaches the backend`() {
        failsWith<EngineError.InvalidArgument> { engine.state(DocHandle(5)) }
        assertTrue(backend.calls.isEmpty())
    }

    @Test fun `closing one document leaves the others open`() = runBlocking {
        val a = open()
        val b = open()
        engine.close(a)
        assertEquals(FakeBackend.META, engine.meta(b))
        assertEquals("#2.meta", last())
        assertEquals(1, engine.openCount())
    }

    @Test fun `closeAll closes every session`() {
        open(); open(); open()
        engine.closeAll()
        assertEquals(listOf(1, 1, 1), backend.sessions.map { it.closes.get() })
        assertEquals(0, engine.openCount())
    }

    @Test fun `a failed open leaves no handle behind`() {
        backend.fail = EngineError.InvalidPdf("bad")
        failsWith<EngineError.InvalidPdf> { engine.open(pdf) }
        assertEquals(0, engine.openCount())
        backend.fail = null
        assertEquals(1L, open().id)
    }

    @Test fun `a close that throws still frees the handle`() = runBlocking {
        val failing = object : NativeEngineBackend by backend {
            override fun open(bytes: ByteArray, password: String?): NativeSession =
                object : NativeSession by FakeSession(backend, 1) {
                    override fun close() = throw IllegalStateException("boom")
                }
        }
        val e = UniffiEngine(failing)
        val h = e.open(pdf)
        e.close(h)
        assertEquals(0, e.openCount())
    }

    @Test fun `calls on one handle never overlap`() {
        val h = open()
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        backend.inside = { entered.countDown(); release.await(2, TimeUnit.SECONDS) }
        val t1 = thread { runBlocking { engine.state(h) } }
        assertTrue(entered.await(2, TimeUnit.SECONDS))
        val t2 = thread { runBlocking { engine.undo(h) } }
        Thread.sleep(100)
        release.countDown()
        t1.join(); t2.join()
        assertEquals(1, backend.sessions[0].maxActive.get())
    }

    @Test fun `different handles may overlap`() {
        val a = open()
        val b = open()
        val both = CountDownLatch(2)
        backend.inside = { both.countDown(); both.await(2, TimeUnit.SECONDS) }
        val t1 = thread { runBlocking { engine.state(a) } }
        val t2 = thread { runBlocking { engine.state(b) } }
        t1.join(); t2.join()
        assertEquals(0L, both.count)
    }

    @Test fun `events is an empty flow`() = runBlocking {
        assertTrue(engine.events.toList().isEmpty())
    }

    // ---- read ----

    @Test fun `meta delegates`() = runBlocking {
        assertEquals(FakeBackend.META, engine.meta(open()))
        assertEquals("#1.meta", last())
    }

    @Test fun `pageInfos delegates`() = runBlocking {
        assertEquals(595f, engine.pageInfos(open()).single().widthPt)
        assertEquals("#1.pageInfos", last())
    }

    @Test fun `renderTile returns a direct buffer of width x height x 4 bytes`() = runBlocking {
        val t = engine.renderTile(open(), TileRequest(0, 1f, 0, 0, 8, 4))
        assertEquals(8, t.width)
        assertEquals(4, t.height)
        assertTrue(t.premultipliedRgba.isDirect)
        assertEquals(0, t.premultipliedRgba.position())
        assertEquals(8 * 4 * 4, t.premultipliedRgba.remaining())
        assertEquals(7.toByte(), t.premultipliedRgba.get(127))
        assertEquals("#1.renderTile(0,8x4)", last())
    }

    @Test fun `renderTile rejects a tile of the wrong size from the engine`() {
        val h = open()
        backend.tile = { RawTile(it.width, it.height, ByteArray(5)) }
        failsWith<EngineError.Render> { engine.renderTile(h, TileRequest(0, 1f, 0, 0, 8, 4)) }
        backend.tile = { RawTile(it.width + 1, it.height, ByteArray((it.width + 1) * it.height * 4)) }
        failsWith<EngineError.Render> { engine.renderTile(h, TileRequest(0, 1f, 0, 0, 8, 4)) }
    }

    @Test fun `renderTile validates its request before the engine sees it`() {
        val h = open()
        val before = backend.calls.size
        failsWith<EngineError.InvalidArgument> { engine.renderTile(h, TileRequest(-1, 1f, 0, 0, 8, 4)) }
        failsWith<EngineError.InvalidArgument> { engine.renderTile(h, TileRequest(0, 1f, -1, 0, 8, 4)) }
        failsWith<EngineError.InvalidArgument> { engine.renderTile(h, TileRequest(0, 1f, 0, 0, 0, 4)) }
        failsWith<EngineError.InvalidArgument> { engine.renderTile(h, TileRequest(0, 0f, 0, 0, 8, 4)) }
        failsWith<EngineError.InvalidArgument> { engine.renderTile(h, TileRequest(0, Float.NaN, 0, 0, 8, 4)) }
        assertEquals(before, backend.calls.size)
    }

    @Test fun `renderTile over 64 megapixels is TooLarge`() {
        val h = open()
        failsWith<EngineError.TooLarge> { engine.renderTile(h, TileRequest(0, 1f, 0, 0, 8193, 8192)) }
        assertEquals("open(3,null)", last())
    }

    @Test fun `searchPage delegates`() = runBlocking {
        assertEquals(listOf(FakeBackend.RECT), engine.searchPage(open(), 2, "Hello", caseSensitive = true, wholeWord = false))
        assertEquals("#1.searchPage(2,Hello,true,false)", last())
    }

    @Test fun `selectText delegates`() = runBlocking {
        assertEquals("word", engine.selectText(open(), 1, Pt(5f, 5f), Pt(6f, 6f))?.text)
        assertEquals("#1.selectText(1,5.0,6.0)", last())
    }

    @Test fun `outline delegates`() = runBlocking {
        assertEquals("Chapter", engine.outline(open(), 10, 50).rows.single().title)
        assertEquals("#1.outline(10,50)", last())
    }

    @Test fun `links delegates`() = runBlocking {
        assertEquals(1, engine.links(open(), 4).size)
        assertEquals("#1.links(4)", last())
    }

    @Test fun `negative page indices are InvalidArgument`() {
        val h = open()
        failsWith<EngineError.InvalidArgument> { engine.searchPage(h, -1, "x") }
        failsWith<EngineError.InvalidArgument> { engine.selectText(h, -1, Pt(0f, 0f), Pt(0f, 0f)) }
        failsWith<EngineError.InvalidArgument> { engine.links(h, -1) }
        failsWith<EngineError.InvalidArgument> { engine.outline(h, -1, 5) }
        failsWith<EngineError.InvalidArgument> { engine.rotate(h, listOf(0, -2), Rotation.CW90) }
        failsWith<EngineError.InvalidArgument> { engine.move(h, listOf(0), -1) }
        failsWith<EngineError.InvalidArgument> { engine.insertBlank(h, -1, 10f, 10f) }
        failsWith<EngineError.InvalidArgument> { engine.extract(h, listOf(-1)) }
        assertEquals("open(3,null)", last())
    }

    // ---- edit ----

    @Test fun `state delegates`() = runBlocking {
        assertEquals(FakeBackend.STATE, engine.state(open()))
        assertEquals("#1.state", last())
    }

    @Test fun `rotate delegates`() = runBlocking {
        assertEquals(FakeBackend.STATE, engine.rotate(open(), listOf(0, 2), Rotation.HALF))
        assertEquals("#1.rotate([0, 2],HALF)", last())
    }

    @Test fun `delete delegates`() = runBlocking {
        engine.delete(open(), listOf(1))
        assertEquals("#1.delete([1])", last())
    }

    @Test fun `move delegates`() = runBlocking {
        engine.move(open(), listOf(1, 2), 0)
        assertEquals("#1.move([1, 2],0)", last())
    }

    @Test fun `duplicate delegates`() = runBlocking {
        engine.duplicate(open(), listOf(0))
        assertEquals("#1.duplicate([0])", last())
    }

    @Test fun `insertBlank delegates`() = runBlocking {
        engine.insertBlank(open(), 1, 595f, 842f)
        assertEquals("#1.insertBlank(1,595.0,842.0)", last())
    }

    @Test fun `undo and redo delegate`() = runBlocking {
        val h = open()
        engine.undo(h)
        assertEquals("#1.undo", last())
        engine.redo(h)
        assertEquals("#1.redo", last())
    }

    @Test fun `addMarkup delegates`() = runBlocking {
        engine.addMarkup(open(), NewMarkup(1, MarkupKind.HIGHLIGHT, 0xFFFF00, 0.5f, 1f))
        assertEquals("#1.addMarkup(1,HIGHLIGHT)", last())
    }

    @Test fun `listFields delegates`() = runBlocking {
        assertEquals("f", engine.listFields(open()).single().name)
        assertEquals("#1.listFields", last())
    }

    @Test fun `setField delegates`() = runBlocking {
        engine.setField(open(), "name", FieldValue.Check(true))
        assertEquals("#1.setField(name,Check(checked=true))", last())
    }

    @Test fun `resetForm delegates with names and with null`() = runBlocking {
        val h = open()
        engine.resetForm(h, listOf("a"))
        assertEquals("#1.resetForm([a])", last())
        engine.resetForm(h)
        assertEquals("#1.resetForm(null)", last())
    }

    // ---- save ----

    @Test fun `saveIncremental delegates`() = runBlocking {
        assertArrayEquals(byteArrayOf(10), engine.saveIncremental(open()))
    }

    @Test fun `saveFull delegates`() = runBlocking {
        assertArrayEquals(byteArrayOf(11), engine.saveFull(open()))
    }

    @Test fun `confirmSaved delegates and reports clean`() = runBlocking {
        assertFalse(engine.confirmSaved(open()).dirty)
        assertEquals("#1.confirmSaved", last())
    }

    @Test fun `saveProtected is Unsupported by default and the engine is not called`() {
        val h = open()
        backend.encryption = true
        failsWith<EngineError.Unsupported> { engine.saveProtected(h, ProtectionSpec("a", "b")) }
        assertEquals("open(3,null)", last())
    }

    @Test fun `removeProtection is Unsupported by default`() {
        val h = open()
        backend.encryption = true
        failsWith<EngineError.Unsupported> { engine.removeProtection(h) }
        assertEquals("open(3,null)", last())
    }

    @Test fun `saveProtected is Unsupported when the flag is on but the library lacks encryption`() {
        val e = UniffiEngine(backend, FeatureFlags(encrypt = true))
        val h = runBlocking { e.open(pdf) }
        failsWith<EngineError.Unsupported> { e.saveProtected(h, ProtectionSpec("a", "b")) }
        failsWith<EngineError.Unsupported> { e.removeProtection(h) }
    }

    @Test fun `saveProtected and removeProtection work when flag and library allow`() = runBlocking {
        backend.encryption = true
        val e = UniffiEngine(backend, FeatureFlags(encrypt = true))
        val h = e.open(pdf)
        assertArrayEquals(byteArrayOf(12), e.saveProtected(h, ProtectionSpec("a", "b")))
        assertEquals("#1.saveProtected(HIGH)", last())
        assertEquals(FakeBackend.STATE, e.removeProtection(h))
        assertEquals("#1.removeProtection", last())
    }

    // ---- files and scan ----

    @Test fun `extract delegates`() = runBlocking {
        assertArrayEquals(byteArrayOf(13), engine.extract(open(), listOf(0, 1)))
        assertEquals("#1.extract([0, 1])", last())
    }

    @Test fun `split delegates and validates`() {
        val h = open()
        runBlocking { assertEquals("part-1.pdf", engine.split(h, SplitSpec.EveryN(2)).single().name) }
        assertEquals("#1.split(EveryN(n=2))", last())
        failsWith<EngineError.InvalidArgument> { engine.split(h, SplitSpec.EveryN(0)) }
        failsWith<EngineError.InvalidArgument> { engine.split(h, SplitSpec.Before(listOf(-1))) }
    }

    @Test fun `merge delegates and validates ranges`() {
        runBlocking { assertArrayEquals(byteArrayOf(1, 2), engine.merge(listOf(MergeSource("a.pdf", pdf), MergeSource("b.pdf", pdf)))) }
        assertEquals("merge([a.pdf, b.pdf])", last())
        failsWith<EngineError.InvalidArgument> { engine.merge(listOf(MergeSource("a", pdf, ranges = listOf(PageRange(3, 1))))) }
        failsWith<EngineError.InvalidArgument> { engine.merge(listOf(MergeSource("a", pdf, ranges = listOf(PageRange(-1, 1))))) }
    }

    @Test fun `imagesToPdf delegates and validates dpi`() {
        runBlocking { assertArrayEquals(byteArrayOf(3), engine.imagesToPdf(listOf(ImageInput("a.jpg", pdf)), 150.0)) }
        assertEquals("imagesToPdf([a.jpg],150.0)", last())
        failsWith<EngineError.InvalidArgument> { engine.imagesToPdf(listOf(ImageInput("a.jpg", pdf)), 0.0) }
        failsWith<EngineError.InvalidArgument> { engine.imagesToPdf(listOf(ImageInput("a.jpg", pdf)), Double.NaN) }
    }

    @Test fun `detectDocumentCorners delegates`() = runBlocking {
        assertEquals(FakeBackend.QUAD, engine.detectDocumentCorners(ImageFrame(2, 1, ByteArray(8))))
        assertEquals("detect(2x1)", last())
    }

    @Test fun `rectifyAndClean delegates`() = runBlocking {
        val out = engine.rectifyAndClean(ImageFrame(2, 1, ByteArray(8)), FakeBackend.QUAD, ScanLook.GREYSCALE, 3)
        assertEquals(1, out.width)
        assertEquals("rectify(2x1,GREYSCALE,3)", last())
    }

    @Test fun `scan frames are validated`() {
        failsWith<EngineError.InvalidArgument> { engine.detectDocumentCorners(ImageFrame(2, 2, ByteArray(8))) }
        failsWith<EngineError.InvalidArgument> { engine.detectDocumentCorners(ImageFrame(0, 2, ByteArray(0))) }
        failsWith<EngineError.InvalidArgument> { engine.rectifyAndClean(ImageFrame(2, 1, ByteArray(8)), FakeBackend.QUAD, ScanLook.COLOUR, 256) }
        failsWith<EngineError.InvalidArgument> { engine.rectifyAndClean(ImageFrame(2, 1, ByteArray(8)), FakeBackend.QUAD, ScanLook.COLOUR, -1) }
        assertTrue(backend.calls.isEmpty())
    }

    // ---- the error funnel: every EngineError kind, from open and from a session call ----

    private fun passesThrough(error: EngineError) {
        val h = open()
        backend.fail = error
        val fromSession = try { runBlocking { engine.meta(h) }; null } catch (e: EngineError) { e }
        val fromOpen = try { runBlocking { engine.open(pdf) }; null } catch (e: EngineError) { e }
        val fromFree = try { runBlocking { engine.merge(emptyList()) }; null } catch (e: EngineError) { e }
        assertSame(error, fromSession)
        assertSame(error, fromOpen)
        assertSame(error, fromFree)
    }

    @Test fun `InvalidPdf passes through`() = passesThrough(EngineError.InvalidPdf("x"))
    @Test fun `PasswordRequired passes through`() = passesThrough(EngineError.PasswordRequired)
    @Test fun `WrongPassword passes through`() = passesThrough(EngineError.WrongPassword)
    @Test fun `TooLarge passes through`() = passesThrough(EngineError.TooLarge("x"))
    @Test fun `InvalidArgument passes through`() = passesThrough(EngineError.InvalidArgument("x"))
    @Test fun `Render passes through`() = passesThrough(EngineError.Render("x"))
    @Test fun `Panic passes through`() = passesThrough(EngineError.Panic("x"))
    @Test fun `Unsupported passes through`() = passesThrough(EngineError.Unsupported("x"))
    @Test fun `Io passes through`() = passesThrough(EngineError.Io("x"))
    @Test fun `Cancelled passes through`() = passesThrough(EngineError.Cancelled)
    @Test fun `NotPermitted passes through`() = passesThrough(EngineError.NotPermitted("x"))

    @Test fun `client-only errors from the backend become Panic`() {
        val h = open()
        backend.fail = EngineError.Timeout("t")
        failsWith<EngineError.Panic> { engine.meta(h) }
        backend.fail = EngineError.EngineDied("d")
        failsWith<EngineError.Panic> { engine.open(pdf) }
    }

    @Test fun `an unexpected exception becomes Panic without its message`() {
        val h = open()
        backend.fail = IllegalStateException("secret document text")
        val e = failsWith<EngineError.Panic> { engine.meta(h) }
        assertEquals("engine failure: IllegalStateException", e.detail)
        val o = failsWith<EngineError.Panic> { engine.open(pdf, "hunter2") }
        assertFalse(o.detail.contains("hunter2") || o.detail.contains("secret"))
    }

    @Test fun `a link error becomes Panic`() {
        backend.fail = UnsatisfiedLinkError("libpdfa_ffi.so not found")
        assertEquals("engine failure: UnsatisfiedLinkError", failsWith<EngineError.Panic> { engine.open(pdf) }.detail)
    }

    @Test fun `out of memory becomes TooLarge`() {
        val h = open()
        backend.fail = OutOfMemoryError()
        failsWith<EngineError.TooLarge> { engine.saveFull(h) }
        failsWith<EngineError.TooLarge> { engine.open(pdf) }
    }

    @Test fun `the engine keeps working after an error`() = runBlocking {
        val h = open()
        backend.fail = EngineError.Render("x")
        try { engine.meta(h); fail("no error") } catch (_: EngineError.Render) { }
        backend.fail = null
        assertEquals(FakeBackend.META, engine.meta(h))
    }
}
