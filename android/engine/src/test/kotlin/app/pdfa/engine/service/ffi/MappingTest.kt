package app.pdfa.engine.service.ffi

import app.pdfa.engine.ChangePerm
import app.pdfa.engine.EngineError
import app.pdfa.engine.FieldKind
import app.pdfa.engine.FieldValue
import app.pdfa.engine.ImageInput
import app.pdfa.engine.LinkTarget
import app.pdfa.engine.MarkupKind
import app.pdfa.engine.MergeSource
import app.pdfa.engine.NewMarkup
import app.pdfa.engine.PageRange
import app.pdfa.engine.PrintPerm
import app.pdfa.engine.ProtectionSpec
import app.pdfa.engine.Pt
import app.pdfa.engine.Quad
import app.pdfa.engine.Rect
import app.pdfa.engine.Rotation
import app.pdfa.engine.ScanLook
import app.pdfa.engine.SplitSpec
import app.pdfa.engine.Stroke
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import uniffi.pdfa_ffi.PdfException

/** The real mapping between the generated UniFFI types and `:engine-api` (no native library needed). */
class MappingTest {
    // ---- every PdfException subclass ----

    @Test fun `InvalidPdf maps with its detail`() {
        val e = PdfException.InvalidPdf("no xref").toEngineError()
        assertTrue(e is EngineError.InvalidPdf)
        assertEquals("no xref", e.detail)
    }

    @Test fun `PasswordRequired maps`() = assertSame(EngineError.PasswordRequired, PdfException.PasswordRequired().toEngineError())
    @Test fun `WrongPassword maps`() = assertSame(EngineError.WrongPassword, PdfException.WrongPassword().toEngineError())
    @Test fun `Cancelled maps`() = assertSame(EngineError.Cancelled, PdfException.Cancelled().toEngineError())

    @Test fun `TooLarge maps`() {
        val e = PdfException.TooLarge("big").toEngineError()
        assertTrue(e is EngineError.TooLarge); assertEquals("big", e.detail)
    }

    @Test fun `InvalidArgument maps`() {
        val e = PdfException.InvalidArgument("page 9").toEngineError()
        assertTrue(e is EngineError.InvalidArgument); assertEquals("page 9", e.detail)
    }

    @Test fun `Render maps`() {
        val e = PdfException.Render("r").toEngineError()
        assertTrue(e is EngineError.Render); assertEquals("r", e.detail)
    }

    @Test fun `Panic maps`() {
        val e = PdfException.Panic("p").toEngineError()
        assertTrue(e is EngineError.Panic); assertEquals("p", e.detail)
    }

    @Test fun `Unsupported maps`() {
        val e = PdfException.Unsupported("u").toEngineError()
        assertTrue(e is EngineError.Unsupported); assertEquals("u", e.detail)
    }

    @Test fun `Io maps`() {
        val e = PdfException.Io("i").toEngineError()
        assertTrue(e is EngineError.Io); assertEquals("i", e.detail)
    }

    @Test fun `NotPermitted maps`() {
        val e = PdfException.NotPermitted("owner").toEngineError()
        assertTrue(e is EngineError.NotPermitted); assertEquals("owner", e.detail)
    }

    @Test fun `no mapped error is client-only`() {
        val all = listOf(
            PdfException.InvalidPdf(""), PdfException.PasswordRequired(), PdfException.WrongPassword(), PdfException.TooLarge(""),
            PdfException.InvalidArgument(""), PdfException.Render(""), PdfException.Panic(""), PdfException.Unsupported(""),
            PdfException.Io(""), PdfException.Cancelled(), PdfException.NotPermitted(""),
        ).map { it.toEngineError() }
        assertEquals(11, all.map { it.javaClass }.toSet().size)
        assertTrue(all.none { it is EngineError.Timeout || it is EngineError.EngineDied })
    }

    // ---- records out of the engine ----

    @Test fun `EditState and DocMeta map field by field`() {
        val s = uniffi.pdfa_ffi.EditState(true, false, "Delete", 12u, true).toApi()
        assertEquals(app.pdfa.engine.EditState(true, false, "Delete", 12, true), s)
        val m = uniffi.pdfa_ffi.DocMeta("t", "a", "s", "k", "p", "2.0", 7u, 5_000_000_000uL, true, false, true, false, true).toApi()
        assertEquals(app.pdfa.engine.DocMeta("t", "a", "s", "k", "p", "2.0", 7, 5_000_000_000L, true, false, true, false, true), m)
    }

    @Test fun `unsigned values that do not fit are clamped, never negative`() {
        assertEquals(Int.MAX_VALUE, UInt.MAX_VALUE.int())
        assertEquals(Long.MAX_VALUE, ULong.MAX_VALUE.long())
        assertEquals(Int.MAX_VALUE, uniffi.pdfa_ffi.EditState(false, false, null, UInt.MAX_VALUE, false).toApi().pageCount)
    }

    @Test fun `page info, selection, outline and links map`() {
        assertEquals(app.pdfa.engine.PageInfo(10f, 20f, "iv"), uniffi.pdfa_ffi.PageInfo(10f, 20f, "iv").toApi())
        val r = uniffi.pdfa_ffi.Rect(1f, 2f, 3f, 4f)
        assertEquals(app.pdfa.engine.TextSelection("hi", listOf(Rect(1f, 2f, 3f, 4f))), uniffi.pdfa_ffi.TextSelection("hi", listOf(r)).toApi())
        val o = uniffi.pdfa_ffi.OutlinePage(listOf(uniffi.pdfa_ffi.OutlineRow(listOf(0u, 2u), "T", 4u, false), uniffi.pdfa_ffi.OutlineRow(listOf(1u), "U", null, true)), 200u).toApi()
        assertEquals(listOf(0, 2), o.rows[0].path)
        assertEquals(4, o.rows[0].page)
        assertNull(o.rows[1].page)
        assertEquals(200, o.next)
        assertEquals(LinkTarget.Page(3), uniffi.pdfa_ffi.PageLink(r, uniffi.pdfa_ffi.LinkTarget.Page(3u)).toApi().target)
        assertEquals(LinkTarget.Uri("https://x"), uniffi.pdfa_ffi.LinkTarget.Uri("https://x").toApi())
        assertEquals(LinkTarget.Other("launch"), uniffi.pdfa_ffi.LinkTarget.Other("launch").toApi())
    }

    @Test fun `field info maps with every kind`() {
        val f = uniffi.pdfa_ffi.FieldInfo(
            "a.b", uniffi.pdfa_ffi.FieldKind.COMBO, listOf("v"), listOf(uniffi.pdfa_ffi.FieldOption("e", "d")),
            true, false, true, 9u, listOf(uniffi.pdfa_ffi.FieldWidget(2u, uniffi.pdfa_ffi.Rect(0f, 0f, 1f, 1f), "On")),
        ).toApi()
        assertEquals("a.b", f.name)
        assertEquals(FieldKind.COMBO, f.kind)
        assertEquals("d", f.options.single().display)
        assertEquals(9, f.maxLen)
        assertEquals(2, f.widgets.single().page)
        assertEquals("On", f.widgets.single().onState)
        assertEquals(FieldKind.entries.map { it.name }, uniffi.pdfa_ffi.FieldKind.entries.map { it.toApi().name })
    }

    @Test fun `split part and quad map`() {
        val p = uniffi.pdfa_ffi.SplitPart("x.pdf", byteArrayOf(1)).toApi()
        assertEquals("x.pdf", p.name)
        val q = Quad(Pt(0f, 1f), Pt(2f, 3f), Pt(4f, 5f), Pt(6f, 7f))
        assertEquals(q, q.toFfi().toApi())
    }

    // ---- records into the engine ----

    @Test fun `enums into the engine keep their names`() {
        assertEquals(Rotation.entries.map { it.name }, Rotation.entries.map { it.toFfi().name })
        assertEquals(MarkupKind.entries.map { it.name }, MarkupKind.entries.map { it.toFfi().name })
        assertEquals(PrintPerm.entries.map { it.name }, PrintPerm.entries.map { it.toFfi().name })
        assertEquals(ChangePerm.entries.map { it.name }, ChangePerm.entries.map { it.toFfi().name })
        assertEquals(ScanLook.entries.map { it.name }, ScanLook.entries.map { it.toFfi().name })
    }

    @Test fun `markup maps with strokes, rects and a masked colour`() {
        val m = NewMarkup(
            2, MarkupKind.INK, 0x12FF8040, 0.5f, 2f, listOf(Stroke(listOf(Pt(1f, 2f), Pt(3f, 4f)))),
            listOf(Rect(0f, 0f, 1f, 1f)), "note", "me",
        ).toFfi()
        assertEquals(2u, m.page)
        assertEquals(0xFF8040u, m.rgb)
        assertEquals(uniffi.pdfa_ffi.Pt(3f, 4f), m.strokes.single().points[1])
        assertEquals("note", m.contents)
        assertEquals("me", m.author)
    }

    @Test fun `field values map`() {
        assertEquals(uniffi.pdfa_ffi.FieldValue.Text("t"), FieldValue.Text("t").toFfi())
        assertEquals(uniffi.pdfa_ffi.FieldValue.Check(true), FieldValue.Check(true).toFfi())
        assertEquals(uniffi.pdfa_ffi.FieldValue.Radio(null), FieldValue.Radio(null).toFfi())
        assertEquals(uniffi.pdfa_ffi.FieldValue.Choice(listOf("a", "b")), FieldValue.Choice(listOf("a", "b")).toFfi())
    }

    @Test fun `protection spec maps`() {
        val s = ProtectionSpec("open", "owner", PrintPerm.LOW, ChangePerm.FILL_SIGN, allowCopy = true, allowAccessibility = false).toFfi()
        assertEquals(uniffi.pdfa_ffi.ProtectionSpec("open", "owner", uniffi.pdfa_ffi.PrintPerm.LOW, uniffi.pdfa_ffi.ChangePerm.FILL_SIGN, true, false), s)
    }

    @Test fun `split spec, merge source and image input map`() {
        assertEquals(uniffi.pdfa_ffi.SplitSpec.EveryN(3u), SplitSpec.EveryN(3).toFfi())
        assertEquals(uniffi.pdfa_ffi.SplitSpec.Before(listOf(2u, 5u)), SplitSpec.Before(listOf(2, 5)).toFfi())
        val bytes = byteArrayOf(1, 2)
        val m = MergeSource("a.pdf", bytes, "pw", listOf(PageRange(0, 3))).toFfi()
        assertEquals("a.pdf", m.name)
        assertSame(bytes, m.bytes)
        assertEquals("pw", m.password)
        assertEquals(uniffi.pdfa_ffi.PageRange(0u, 3u), m.ranges.single())
        assertSame(bytes, ImageInput("i.png", bytes).toFfi().bytes)
    }
}
