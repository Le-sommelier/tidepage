package app.pdfa.engine.fake

import app.pdfa.engine.EngineError
import app.pdfa.engine.FieldKind
import app.pdfa.engine.FieldOption
import app.pdfa.engine.FieldWidget
import app.pdfa.engine.LinkTarget
import app.pdfa.engine.MarkupKind
import app.pdfa.engine.NewMarkup
import app.pdfa.engine.PageLink
import app.pdfa.engine.Pt
import app.pdfa.engine.Rect
import app.pdfa.engine.Stroke
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import kotlin.test.assertFailsWith

class FakeFormatTest {

    private fun roundTrip(doc: FakeDocument): FakeDocument =
        FakeFormat.decode(FakeFormat.encode(doc))

    @Test fun roundTripsSeparatorsAndNewlinesInEveryTextValue() {
        val nasty = "a;b|c=d,e\nf%g+h \"quoted\" é"
        val doc = FakeDocument(
            info = FakeInfo(title = nasty, author = "x;y", keywords = "=|"),
            openPassword = "pa;ss=word",
            pages = listOf(
                FakePage(
                    width = 612f, height = 792f, label = nasty, text = nasty, rotation = 90, id = 7,
                    markups = listOf(
                        NewMarkup(
                            page = 7, kind = MarkupKind.NOTE, rgb = 0xABCDEF, opacity = 0.5f, widthPt = 1f,
                            rects = listOf(Rect(1f, 2f, 3f, 4f)), contents = nasty, author = "a|b",
                        ),
                    ),
                    links = listOf(PageLink(Rect(0f, 0f, 1f, 1f), LinkTarget.Uri(nasty))),
                ),
            ),
            outline = listOf(FakeOutline(path = listOf(0, 2), title = nasty, page = 7, open = true)),
            fields = listOf(
                FakeField(
                    name = "f;1", kind = FieldKind.LIST, value = listOf("a|b", "", "c=d"),
                    options = listOf(FieldOption("x,y", "z;w")),
                    widgets = listOf(FieldWidget(7, Rect(0f, 0f, 5f, 5f), onState = "S;1")),
                    maxLen = 9, defaultValue = listOf(""),
                ),
            ),
            nextId = 8,
        )
        assertEquals(doc, roundTrip(doc))
    }

    @Test fun keepsEmptyListDistinctFromListOfOneEmptyString() {
        val withEmpty = FakeField(name = "a", kind = FieldKind.LIST, value = listOf(""))
        val withNone = FakeField(name = "a", kind = FieldKind.LIST, value = emptyList())
        val doc = FakeDocument(FakeInfo(), null, listOf(FakePage(1f, 1f, id = 1)), emptyList(), listOf(withEmpty), 2)
        assertEquals(listOf(""), roundTrip(doc).fields.single().value)
        assertEquals(emptyList<String>(), roundTrip(doc.copy(fields = listOf(withNone))).fields.single().value)
    }

    @Test fun roundTripsPageLinkTargetsAndStrokes() {
        val page = FakePage(
            width = 100f, height = 100f, id = 3,
            markups = listOf(
                NewMarkup(
                    page = 3, kind = MarkupKind.INK, rgb = 0, opacity = 1f, widthPt = 2f,
                    strokes = listOf(Stroke(listOf(Pt(1f, 2f), Pt(3.5f, -4f))), Stroke(emptyList())),
                ),
            ),
            links = listOf(
                PageLink(Rect(0f, 0f, 1f, 1f), LinkTarget.Page(3)),
                PageLink(Rect(0f, 0f, 1f, 1f), LinkTarget.Other("launch: x")),
            ),
        )
        val doc = FakeDocument(FakeInfo(), null, listOf(page), emptyList(), emptyList(), 4)
        assertEquals(doc, roundTrip(doc))
    }

    @Test fun rejectsBytesWithoutTheHeader() {
        assertFailsWith<EngineError.InvalidPdf> { FakeFormat.decode("%PDF-1.7".toByteArray()) }
    }

    @Test fun rejectsAnUnknownFormatVersion() {
        assertFailsWith<EngineError.InvalidPdf> { FakeFormat.decode("FAKEPDF:9\n".toByteArray()) }
    }

    @Test fun rejectsAMalformedBody() {
        val bad = listOf(
            "FAKEPDF:1\npage;id=x;w=1.0;h=1.0;rot=0;label=;text=",
            "FAKEPDF:1\nbogus;a=1",
            "FAKEPDF:1\ndoc;title=%ZZ",
            "FAKEPDF:1\ndoc;title",
        )
        for (text in bad) {
            assertFailsWith<EngineError.InvalidPdf>(text) { FakeFormat.decode(text.toByteArray()) }
        }
    }

    @Test fun rejectsADocumentWithoutPages() {
        val text = "FAKEPDF:1\ndoc;title=;author=;subject=;keywords=;producer=;version=;print=1;copy=1;modify=1;annotate=1;next=1\n"
        assertFailsWith<EngineError.InvalidPdf> { FakeFormat.decode(text.toByteArray()) }
    }

    @Test fun rejectsAMarkupOnAPageThatDoesNotExist() {
        val text = "FAKEPDF:1\n" +
            "doc;title=;author=;subject=;keywords=;producer=;version=;print=1;copy=1;modify=1;annotate=1;next=2\n" +
            "page;id=1;w=1.0;h=1.0;rot=0;label=;text=\n" +
            "markup;page=9;kind=NOTE;rgb=0;opacity=1.0;width=1.0;strokes=;rects=;contents=;author=\n"
        assertFailsWith<EngineError.InvalidPdf> { FakeFormat.decode(text.toByteArray()) }
    }

    @Test fun parsesTheHandWrittenSpecExample() {
        val text = "FAKEPDF:1\n" +
            "doc;title=;author=;subject=;keywords=;producer=;version=;print=1;copy=1;modify=1;annotate=1;next=4;password=secret\n" +
            "page;id=1;w=612.0;h=792.0;rot=0;label=;text=Page+one\n" +
            "page;id=2;w=612.0;h=792.0;rot=0;label=;text=Page+two\n" +
            "page;id=3;w=612.0;h=792.0;rot=0;label=;text=Page+three\n"
        val doc = FakeFormat.decode(text.toByteArray())
        assertEquals("secret", doc.openPassword)
        assertEquals(listOf("Page one", "Page two", "Page three"), doc.pages.map { it.text })
    }

    @Test fun fieldWithoutOptionsOrWidgetsStillParses() {
        val doc = FakeDocument(
            FakeInfo(), null,
            listOf(FakePage(1f, 1f, id = 1)),
            emptyList(),
            listOf(FakeField(name = "plain", kind = FieldKind.TEXT)),
            2,
        )
        val parsed = roundTrip(doc).fields.single()
        assertNull(parsed.maxLen)
        assertEquals(emptyList<FieldOption>(), parsed.options)
        assertEquals(emptyList<FieldWidget>(), parsed.widgets)
    }
}
