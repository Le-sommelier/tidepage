package app.pdfa.engine.fake

import app.pdfa.engine.DocHandle
import app.pdfa.engine.EngineError
import app.pdfa.engine.EngineEvent
import app.pdfa.engine.FeatureFlags
import app.pdfa.engine.FieldKind
import app.pdfa.engine.FieldOption
import app.pdfa.engine.FieldWidget
import app.pdfa.engine.FieldValue
import app.pdfa.engine.ImageFrame
import app.pdfa.engine.ImageInput
import app.pdfa.engine.LinkTarget
import app.pdfa.engine.MarkupKind
import app.pdfa.engine.MergeSource
import app.pdfa.engine.NewMarkup
import app.pdfa.engine.PageLink
import app.pdfa.engine.PageRange
import app.pdfa.engine.Pt
import app.pdfa.engine.ProtectionSpec
import app.pdfa.engine.Quad
import app.pdfa.engine.Rect
import app.pdfa.engine.Rotation
import app.pdfa.engine.ScanLook
import app.pdfa.engine.SplitSpec
import app.pdfa.engine.Stroke
import app.pdfa.engine.TileRequest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.TimeoutCancellationException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.test.assertFailsWith

@OptIn(ExperimentalCoroutinesApi::class)
class FakeEngineTest {

    private val texts = listOf("Page one", "Page two", "Page three")

    private fun threePages(password: String? = null): ByteArray =
        FakeEngine.fakePdf(pageCount = 3, text = texts, password = password)

    private fun engine(
        limits: FakeEngine.Limits = FakeEngine.Limits(),
        flags: FeatureFlags = FeatureFlags(),
    ) = FakeEngine(limits = limits, flags = flags, clock = { 1_000L })

    private suspend fun FakeEngine.texts(h: DocHandle): List<String> = inspect(h).pages.map { it.text }

    private fun markupAt(page: Int, kind: MarkupKind = MarkupKind.HIGHLIGHT) = NewMarkup(
        page = page,
        kind = kind,
        rgb = 0xFF0000,
        opacity = 0.5f,
        widthPt = 1f,
        rects = listOf(Rect(0f, 0f, 10f, 10f)),
    )

    // ---- open and lifecycle ----

    @Test fun openReadsAPlainDocument() = runTest {
        val e = engine()
        val h = e.open(threePages())
        assertEquals(3, e.meta(h).pageCount)
        assertFalse(e.meta(h).encrypted)
        assertEquals(3, e.pageInfos(h).size)
    }

    @Test fun openRejectsGarbageAsInvalidPdf() = runTest {
        assertFailsWith<EngineError.InvalidPdf> { engine().open("not a pdf".toByteArray()) }
    }

    @Test fun openRejectsTruncatedBodyAsInvalidPdf() = runTest {
        val truncated = "FAKEPDF:1\npage;id=1;w=1.0".toByteArray()
        assertFailsWith<EngineError.InvalidPdf> { engine().open(truncated) }
    }

    @Test fun openWithoutPasswordOfEncryptedDocumentFails() = runTest {
        assertFailsWith<EngineError.PasswordRequired> { engine().open(threePages("secret")) }
    }

    @Test fun openWithWrongPasswordFails() = runTest {
        assertFailsWith<EngineError.WrongPassword> { engine().open(threePages("secret"), "nope") }
    }

    @Test fun openWithCorrectPasswordSucceedsAndReportsEncrypted() = runTest {
        val e = engine()
        val h = e.open(threePages("secret"), "secret")
        assertTrue(e.meta(h).encrypted)
    }

    @Test fun openRejectsInputOverTheLimit() = runTest {
        val e = engine(limits = FakeEngine.Limits(maxInputBytes = 10))
        assertFailsWith<EngineError.TooLarge> { e.open(threePages()) }
    }

    @Test fun handleIsInvalidAfterClose() = runTest {
        val e = engine()
        val h = e.open(threePages())
        e.close(h)
        assertFailsWith<EngineError.InvalidArgument> { e.state(h) }
    }

    @Test fun closingTwiceIsAnInvalidArgument() = runTest {
        val e = engine()
        val h = e.open(threePages())
        e.close(h)
        assertFailsWith<EngineError.InvalidArgument> { e.close(h) }
    }

    @Test fun metaCarriesInfoAndPermissions() = runTest {
        val e = engine()
        val bytes = FakeEngine.fakePdf(
            pages = listOf(FakePage(612f, 792f)),
            info = FakeInfo(title = "Quarterly", author = "Q", canPrint = false),
        )
        val m = e.meta(e.open(bytes))
        assertEquals("Quarterly", m.title)
        assertEquals("Q", m.author)
        assertFalse(m.canPrint)
        assertTrue(m.canCopy)
    }

    @Test fun openingTheSameBytesTwiceGivesIndependentHandles() = runTest {
        val e = engine()
        val a = e.open(threePages())
        val b = e.open(threePages())
        e.rotate(a, listOf(0), Rotation.CW90)
        assertTrue(e.state(a).dirty)
        assertFalse(e.state(b).dirty)
    }

    // ---- viewer ----

    @Test fun pageInfosSwapsSizeForQuarterTurns() = runTest {
        val e = engine()
        val h = e.open(FakeEngine.fakePdf(pageCount = 1, width = 612f, height = 792f))
        e.rotate(h, listOf(0), Rotation.CW90)
        val info = e.pageInfos(h).single()
        assertEquals(792f, info.widthPt, 0f)
        assertEquals(612f, info.heightPt, 0f)
    }

    @Test fun renderTileFillsPremultipliedRgbaAndDiffersPerPage() = runTest {
        val e = engine()
        val h = e.open(threePages())
        val a = e.renderTile(h, TileRequest(page = 0, scale = 1f, x = 0, y = 0, width = 4, height = 2))
        val b = e.renderTile(h, TileRequest(page = 1, scale = 1f, x = 0, y = 0, width = 4, height = 2))
        assertEquals(4, a.width)
        assertEquals(2, a.height)
        assertEquals(32, a.premultipliedRgba.limit())
        assertEquals(255, a.premultipliedRgba.get(3).toInt() and 0xFF)
        assertFalse((0 until 3).all { a.premultipliedRgba.get(it) == b.premultipliedRgba.get(it) })
    }

    @Test fun renderTilePreviewIsHalfTransparent() = runTest {
        val e = engine()
        val h = e.open(threePages())
        val preview = e.renderTile(h, TileRequest(0, 1f, 0, 0, 1, 1, preview = true))
        assertEquals(128, preview.premultipliedRgba.get(3).toInt() and 0xFF)
    }

    @Test fun renderTileOutsideThePageIsInvalid() = runTest {
        val e = engine()
        val h = e.open(threePages())
        assertFailsWith<EngineError.InvalidArgument> {
            e.renderTile(h, TileRequest(0, 1f, 610, 0, 10, 10))
        }
    }

    @Test fun renderTileOfMissingPageIsInvalid() = runTest {
        val e = engine()
        val h = e.open(threePages())
        assertFailsWith<EngineError.InvalidArgument> {
            e.renderTile(h, TileRequest(3, 1f, 0, 0, 1, 1))
        }
    }

    @Test fun renderTileOverThePixelLimitIsTooLarge() = runTest {
        val e = engine(limits = FakeEngine.Limits(maxRenderPixels = 100))
        val h = e.open(threePages())
        assertFailsWith<EngineError.TooLarge> {
            e.renderTile(h, TileRequest(0, 1f, 0, 0, 11, 10))
        }
    }

    @Test fun searchIsCaseInsensitiveByDefaultWithDeterministicRects() = runTest {
        val e = engine()
        val h = e.open(threePages())
        val rects = e.searchPage(h, 0, "PAGE")
        assertEquals(listOf(Rect(0f, 0f, 32f, 14f)), rects)
    }

    @Test fun searchCaseSensitiveSkipsOtherCase() = runTest {
        val e = engine()
        val h = e.open(FakeEngine.fakePdf(pageCount = 1, text = listOf("Page one page")))
        val rects = e.searchPage(h, 0, "page", caseSensitive = true)
        assertEquals(listOf(Rect(72f, 0f, 104f, 14f)), rects)
    }

    @Test fun searchWholeWordRejectsPartialMatches() = runTest {
        val e = engine()
        val h = e.open(FakeEngine.fakePdf(pageCount = 1, text = listOf("cat concatenate cat")))
        assertEquals(2, e.searchPage(h, 0, "cat", wholeWord = true).size)
        assertEquals(3, e.searchPage(h, 0, "cat").size)
    }

    @Test fun searchWithEmptyQueryIsInvalid() = runTest {
        val e = engine()
        val h = e.open(threePages())
        assertFailsWith<EngineError.InvalidArgument> { e.searchPage(h, 0, "") }
    }

    @Test fun selectTextOnAPointPicksTheWordUnderIt() = runTest {
        val e = engine()
        val h = e.open(threePages())
        val sel = e.selectText(h, 0, Pt(44f, 7f), Pt(44f, 7f))!!
        assertEquals("one", sel.text)
        assertEquals(listOf(Rect(40f, 0f, 64f, 14f)), sel.rects)
    }

    @Test fun selectTextOnWhitespaceReturnsNull() = runTest {
        val e = engine()
        val h = e.open(threePages())
        assertNull(e.selectText(h, 0, Pt(36f, 7f), Pt(36f, 7f)))
    }

    @Test fun selectTextInARegionTakesCharactersWhoseCentreIsInside() = runTest {
        val e = engine()
        val h = e.open(threePages())
        val sel = e.selectText(h, 0, Pt(0f, 0f), Pt(24f, 14f))!!
        assertEquals("Pag", sel.text)
    }

    @Test fun outlinePagesThroughRowsWithNextOffsets() = runTest {
        val e = engine()
        val rows = (0 until 5).map { FakeOutline(path = listOf(it), title = "Ch $it", page = it % 3) }
        val h = e.open(FakeEngine.fakePdf(pages = List(3) { FakePage(100f, 100f) }, outline = rows))
        val first = e.outline(h, offset = 0, limit = 2)
        assertEquals(listOf("Ch 0", "Ch 1"), first.rows.map { it.title })
        assertEquals(2, first.next)
        val second = e.outline(h, offset = 2, limit = 2)
        assertEquals(4, second.next)
        val last = e.outline(h, offset = 4, limit = 2)
        assertEquals(listOf("Ch 4"), last.rows.map { it.title })
        assertNull(last.next)
        assertEquals(1, last.rows.single().page)
    }

    @Test fun outlineFollowsPagesThroughEditsByStableId() = runTest {
        val e = engine()
        val bytes = FakeEngine.fakePdf(
            pages = List(3) { FakePage(100f, 100f) },
            outline = listOf(FakeOutline(path = listOf(0), title = "Third", page = 2)),
        )
        val h = e.open(bytes)
        e.delete(h, listOf(0))
        assertEquals(1, e.outline(h).rows.single().page)
    }

    @Test fun outlineToADeletedPageLosesItsPageNumber() = runTest {
        val e = engine()
        val bytes = FakeEngine.fakePdf(
            pages = List(3) { FakePage(100f, 100f) },
            outline = listOf(FakeOutline(path = listOf(0), title = "Second", page = 1)),
        )
        val h = e.open(bytes)
        e.delete(h, listOf(1))
        assertNull(e.outline(h).rows.single().page)
    }

    @Test fun linksMapPageTargetsToCurrentIndices() = runTest {
        val e = engine()
        val pages = listOf(
            FakePage(100f, 100f, links = listOf(PageLink(Rect(0f, 0f, 5f, 5f), LinkTarget.Page(1)))),
            FakePage(100f, 100f),
            FakePage(100f, 100f),
        )
        val h = e.open(FakeEngine.fakePdf(pages = pages))
        assertEquals(LinkTarget.Page(1), e.links(h, 0).single().target)
        e.move(h, listOf(1), 0)
        assertEquals(LinkTarget.Page(0), e.links(h, 1).single().target)
    }

    @Test fun linkToADeletedPageBecomesOther() = runTest {
        val e = engine()
        val pages = listOf(
            FakePage(100f, 100f, links = listOf(PageLink(Rect(0f, 0f, 5f, 5f), LinkTarget.Page(1)))),
            FakePage(100f, 100f),
        )
        val h = e.open(FakeEngine.fakePdf(pages = pages))
        e.delete(h, listOf(1))
        assertTrue(e.links(h, 0).single().target is LinkTarget.Other)
    }

    // ---- organize ----

    @Test fun rotateQuarterTurnsAccumulateAndWrap() = runTest {
        val e = engine()
        val h = e.open(threePages())
        e.rotate(h, listOf(0), Rotation.CW90)
        e.rotate(h, listOf(0), Rotation.HALF)
        assertEquals(270, e.inspect(h).pages[0].rotation)
        e.rotate(h, listOf(0), Rotation.CCW90)
        assertEquals(180, e.inspect(h).pages[0].rotation)
    }

    @Test fun rotateRejectsBadPageLists() = runTest {
        val e = engine()
        val h = e.open(threePages())
        assertFailsWith<EngineError.InvalidArgument> { e.rotate(h, emptyList(), Rotation.CW90) }
        assertFailsWith<EngineError.InvalidArgument> { e.rotate(h, listOf(0, 0), Rotation.CW90) }
        assertFailsWith<EngineError.InvalidArgument> { e.rotate(h, listOf(3), Rotation.CW90) }
    }

    @Test fun deleteRemovesPagesAndUndoRestoresThem() = runTest {
        val e = engine()
        val h = e.open(threePages())
        val after = e.delete(h, listOf(0, 2))
        assertEquals(1, after.pageCount)
        assertEquals(listOf("Page two"), e.texts(h))
        val undone = e.undo(h)
        assertEquals(3, undone.pageCount)
        assertEquals(texts, e.texts(h))
    }

    @Test fun deletingEveryPageIsInvalid() = runTest {
        val e = engine()
        val h = e.open(threePages())
        assertFailsWith<EngineError.InvalidArgument> { e.delete(h, listOf(0, 1, 2)) }
    }

    @Test fun moveTwoPagesToIndexOneOfThree() = runTest {
        val e = engine()
        val h = e.open(threePages())
        e.move(h, listOf(0, 1), 1)
        assertEquals(listOf("Page three", "Page one", "Page two"), e.texts(h))
    }

    @Test fun moveTwoPagesToIndexTwoOfThreeIsOutOfRange() = runTest {
        val e = engine()
        val h = e.open(threePages())
        assertFailsWith<EngineError.InvalidArgument> { e.move(h, listOf(0, 1), 2) }
    }

    @Test fun moveAcceptsTheFullRangeEdges() = runTest {
        val e = engine()
        val h = e.open(threePages())
        e.move(h, listOf(0, 1), 0)
        assertEquals(texts, e.texts(h))
        e.move(h, listOf(0, 1), 1)
        assertEquals(listOf("Page three", "Page one", "Page two"), e.texts(h))
    }

    @Test fun moveKeepsPickedPagesInAscendingOrder() = runTest {
        val e = engine()
        val h = e.open(threePages())
        e.move(h, listOf(2, 0), 0)
        assertEquals(listOf("Page one", "Page three", "Page two"), e.texts(h))
    }

    @Test fun moveRejectsDuplicatesAndEmptyLists() = runTest {
        val e = engine()
        val h = e.open(threePages())
        assertFailsWith<EngineError.InvalidArgument> { e.move(h, emptyList(), 0) }
        assertFailsWith<EngineError.InvalidArgument> { e.move(h, listOf(1, 1), 0) }
    }

    @Test fun duplicateInsertsCopiesRightAfterTheOriginal() = runTest {
        val e = engine()
        val h = e.open(threePages())
        val after = e.duplicate(h, listOf(0))
        assertEquals(4, after.pageCount)
        assertEquals(listOf("Page one", "Page one", "Page two", "Page three"), e.texts(h))
        val ids = e.inspect(h).pages.map { it.id }
        assertEquals(4, ids.toSet().size)
    }

    @Test fun insertBlankPlacesAPageOfTheGivenSize() = runTest {
        val e = engine()
        val h = e.open(threePages())
        e.insertBlank(h, at = 3, widthPt = 300f, heightPt = 400f)
        val info = e.pageInfos(h).last()
        assertEquals(300f, info.widthPt, 0f)
        assertEquals(400f, info.heightPt, 0f)
        assertEquals("", e.inspect(h).pages.last().text)
    }

    @Test fun insertBlankOutsideTheDocumentIsInvalid() = runTest {
        val e = engine()
        val h = e.open(threePages())
        assertFailsWith<EngineError.InvalidArgument> { e.insertBlank(h, at = 4, widthPt = 1f, heightPt = 1f) }
        assertFailsWith<EngineError.InvalidArgument> { e.insertBlank(h, at = 0, widthPt = 0f, heightPt = 1f) }
    }

    @Test fun undoAndRedoReportLabelsAndFlags() = runTest {
        val e = engine()
        val h = e.open(threePages())
        val rotated = e.rotate(h, listOf(0), Rotation.CW90)
        assertTrue(rotated.canUndo)
        assertFalse(rotated.canRedo)
        assertEquals("Rotate pages", rotated.undoLabel)
        val undone = e.undo(h)
        assertFalse(undone.canUndo)
        assertTrue(undone.canRedo)
        assertNull(undone.undoLabel)
        val redone = e.redo(h)
        assertTrue(redone.canUndo)
        assertFalse(redone.canRedo)
        assertEquals(90, e.inspect(h).pages[0].rotation)
    }

    @Test fun aNewEditClearsTheRedoStack() = runTest {
        val e = engine()
        val h = e.open(threePages())
        e.rotate(h, listOf(0), Rotation.CW90)
        e.undo(h)
        val state = e.delete(h, listOf(0))
        assertFalse(state.canRedo)
    }

    @Test fun undoWithNothingToUndoLeavesStateAlone() = runTest {
        val e = engine()
        val h = e.open(threePages())
        val state = e.undo(h)
        assertEquals(3, state.pageCount)
        assertFalse(state.dirty)
    }

    // ---- annotate and forms ----

    @Test fun addMarkupStoresHighlightsAndInkOnThePage() = runTest {
        val e = engine()
        val h = e.open(threePages())
        e.addMarkup(h, markupAt(1))
        e.addMarkup(
            h,
            NewMarkup(
                page = 1, kind = MarkupKind.INK, rgb = 0, opacity = 1f, widthPt = 2f,
                strokes = listOf(Stroke(listOf(Pt(1f, 1f), Pt(2f, 2f)))),
            ),
        )
        assertEquals(listOf(MarkupKind.HIGHLIGHT, MarkupKind.INK), e.inspect(h).pages[1].markups.map { it.kind })
    }

    @Test fun addMarkupValidatesKindSpecificFields() = runTest {
        val e = engine()
        val h = e.open(threePages())
        assertFailsWith<EngineError.InvalidArgument> {
            e.addMarkup(h, NewMarkup(page = 0, kind = MarkupKind.INK, rgb = 0, opacity = 1f, widthPt = 1f))
        }
        assertFailsWith<EngineError.InvalidArgument> { e.addMarkup(h, markupAt(0).copy(opacity = 1.5f)) }
        assertFailsWith<EngineError.InvalidArgument> { e.addMarkup(h, markupAt(0).copy(rgb = 0x1000000)) }
        assertFailsWith<EngineError.InvalidArgument> { e.addMarkup(h, markupAt(9)) }
    }

    private fun formDoc() = FakeEngine.fakePdf(
        pages = List(2) { FakePage(612f, 792f) },
        fields = listOf(
            FakeField(
                name = "name", kind = FieldKind.TEXT, value = listOf("old"),
                widgets = listOf(FieldWidget(1, Rect(0f, 0f, 50f, 10f), onState = null)),
                maxLen = 5,
            ),
            FakeField(
                name = "agree", kind = FieldKind.CHECK_BOX,
                widgets = listOf(FieldWidget(0, Rect(0f, 0f, 10f, 10f), onState = "Yes")),
            ),
            FakeField(name = "locked", kind = FieldKind.TEXT, value = listOf("fixed"), readOnly = true),
            FakeField(
                name = "size", kind = FieldKind.COMBO, value = listOf("S"),
                options = listOf(FieldOption("S", "Small"), FieldOption("M", "Medium")),
            ),
        ),
    )

    @Test fun listFieldsMapsWidgetsToCurrentPages() = runTest {
        val e = engine()
        val h = e.open(formDoc())
        val fields = e.listFields(h)
        assertEquals(4, fields.size)
        val name = fields.single { it.name == "name" }
        assertEquals(FieldKind.TEXT, name.kind)
        assertEquals(listOf("old"), name.value)
        assertEquals(1, name.widgets.single().page)
        assertEquals(5, name.maxLen)
    }

    @Test fun setFieldUpdatesTextAndRespectsMaxLen() = runTest {
        val e = engine()
        val h = e.open(formDoc())
        e.setField(h, "name", FieldValue.Text("new"))
        assertEquals(listOf("new"), e.listFields(h).single { it.name == "name" }.value)
        assertFailsWith<EngineError.InvalidArgument> { e.setField(h, "name", FieldValue.Text("too long")) }
    }

    @Test fun setFieldRejectsUnknownNamesReadOnlyAndWrongKinds() = runTest {
        val e = engine()
        val h = e.open(formDoc())
        assertFailsWith<EngineError.InvalidArgument> { e.setField(h, "nope", FieldValue.Text("x")) }
        assertFailsWith<EngineError.InvalidArgument> { e.setField(h, "locked", FieldValue.Text("x")) }
        assertFailsWith<EngineError.InvalidArgument> { e.setField(h, "name", FieldValue.Check(true)) }
    }

    @Test fun setFieldChoiceRequiresAKnownOption() = runTest {
        val e = engine()
        val h = e.open(formDoc())
        e.setField(h, "size", FieldValue.Choice(listOf("M")))
        assertEquals(listOf("M"), e.listFields(h).single { it.name == "size" }.value)
        assertFailsWith<EngineError.InvalidArgument> { e.setField(h, "size", FieldValue.Choice(listOf("XL"))) }
    }

    @Test fun checkBoxTurnsOnWithItsWidgetState() = runTest {
        val e = engine()
        val h = e.open(formDoc())
        e.setField(h, "agree", FieldValue.Check(true))
        assertEquals(listOf("Yes"), e.listFields(h).single { it.name == "agree" }.value)
        e.setField(h, "agree", FieldValue.Check(false))
        assertTrue(e.listFields(h).single { it.name == "agree" }.value.isEmpty())
    }

    @Test fun resetFormRestoresDefaultsOfTheNamedFields() = runTest {
        val e = engine()
        val h = e.open(formDoc())
        e.setField(h, "name", FieldValue.Text("new"))
        e.setField(h, "agree", FieldValue.Check(true))
        e.resetForm(h, listOf("name"))
        assertEquals(listOf("old"), e.listFields(h).single { it.name == "name" }.value)
        assertEquals(listOf("Yes"), e.listFields(h).single { it.name == "agree" }.value)
        e.resetForm(h)
        assertTrue(e.listFields(h).single { it.name == "agree" }.value.isEmpty())
    }

    @Test fun resetFormWithUnknownNameIsInvalid() = runTest {
        val e = engine()
        val h = e.open(formDoc())
        assertFailsWith<EngineError.InvalidArgument> { e.resetForm(h, listOf("nope")) }
    }

    // ---- save and dirty ----

    @Test fun dirtyFollowsEditsSavesAndConfirmation() = runTest {
        val e = engine()
        val h = e.open(threePages())
        assertFalse(e.state(h).dirty)

        e.rotate(h, listOf(0), Rotation.CW90)
        assertTrue(e.state(h).dirty)

        e.saveFull(h)
        assertTrue("saving alone must not clear dirty", e.state(h).dirty)
        e.saveIncremental(h)
        assertTrue(e.state(h).dirty)

        assertFalse(e.confirmSaved(h).dirty)

        // Undo after confirmSaved: the document now differs from the saved bytes again.
        assertTrue(e.undo(h).dirty)
        // Redo returns to exactly the saved state, which is clean again.
        assertFalse(e.redo(h).dirty)
    }

    @Test fun confirmSavedAfterAnEditOnlyCoversTheSavedRevision() = runTest {
        val e = engine()
        val h = e.open(threePages())
        e.rotate(h, listOf(0), Rotation.CW90)
        e.saveFull(h)
        e.delete(h, listOf(2))
        e.confirmSaved(h)
        assertFalse(e.state(h).dirty)
        assertTrue(e.undo(h).dirty)
    }

    @Test fun reopeningSavedBytesRoundTripsTheDocument() = runTest {
        val e = engine()
        val h = e.open(threePages())
        e.rotate(h, listOf(1), Rotation.CW90)
        e.addMarkup(h, markupAt(0))
        val saved = e.saveFull(h)
        val reopened = e.open(saved)
        assertEquals(e.inspect(h).pages, e.inspect(reopened).pages)
        assertEquals(3, e.meta(reopened).pageCount)
        assertFalse(e.state(reopened).dirty)
    }

    @Test fun incrementalAndFullSavesAreTheSameBytes() = runTest {
        val e = engine()
        val h = e.open(threePages())
        e.delete(h, listOf(0))
        assertTrue(e.saveIncremental(h).contentEquals(e.saveFull(h)))
    }

    @Test fun saveAboveTheOutputLimitIsTooLarge() = runTest {
        val e = engine(limits = FakeEngine.Limits(maxOutputBytes = 16))
        val h = e.open(threePages())
        assertFailsWith<EngineError.TooLarge> { e.saveFull(h) }
    }

    @Test fun saveProtectedIsGatedOffByDefault() = runTest {
        val e = engine()
        val h = e.open(threePages())
        assertFailsWith<EngineError.Unsupported> {
            e.saveProtected(h, ProtectionSpec(openPassword = "p", ownerPassword = "o"))
        }
        assertFailsWith<EngineError.Unsupported> { e.removeProtection(h) }
    }

    @Test fun saveProtectedWithTheFlagProducesAPasswordFile() = runTest {
        val e = engine(flags = FeatureFlags(encrypt = true))
        val h = e.open(threePages())
        val locked = e.saveProtected(h, ProtectionSpec(openPassword = "p", ownerPassword = "o"))
        assertFailsWith<EngineError.PasswordRequired> { e.open(locked) }
        assertTrue(e.meta(e.open(locked, "p")).encrypted)
    }

    @Test fun removeProtectionDropsThePasswordAndIsUndoable() = runTest {
        val e = engine(flags = FeatureFlags(encrypt = true))
        val h = e.open(threePages("secret"), "secret")
        val state = e.removeProtection(h)
        assertTrue(state.dirty)
        assertEquals("Remove protection", state.undoLabel)
        assertFalse(e.meta(e.open(e.saveFull(h))).encrypted)
        e.undo(h)
        assertTrue(e.meta(e.open(e.saveFull(h), "secret")).encrypted)
    }

    // ---- files ----

    @Test fun extractKeepsTheRequestedPagesInGivenOrder() = runTest {
        val e = engine()
        val h = e.open(threePages())
        val bytes = e.extract(h, listOf(2, 0))
        assertEquals(listOf("Page three", "Page one"), e.texts(e.open(bytes)))
    }

    @Test fun splitEveryNMakesChunksOfN() = runTest {
        val e = engine()
        val five = FakeEngine.fakePdf(pageCount = 5)
        val h = e.open(five)
        val parts = e.split(h, SplitSpec.EveryN(2))
        assertEquals(listOf(2, 2, 1), parts.map { e.meta(e.open(it.bytes)).pageCount })
    }

    @Test fun splitBeforeStartsPartsAtTheListedPages() = runTest {
        val e = engine()
        val h = e.open(FakeEngine.fakePdf(pageCount = 4, text = listOf("a", "b", "c", "d")))
        val parts = e.split(h, SplitSpec.Before(listOf(1, 3)))
        assertEquals(
            listOf(listOf("a"), listOf("b", "c"), listOf("d")),
            parts.map { e.texts(e.open(it.bytes)) },
        )
    }

    @Test fun splitWithPageZeroIsImplicitAndNoEmptyPart() = runTest {
        val e = engine()
        val h = e.open(threePages())
        assertEquals(2, e.split(h, SplitSpec.Before(listOf(0, 2))).size)
    }

    @Test fun splitRejectsBadSpecs() = runTest {
        val e = engine()
        val h = e.open(threePages())
        assertFailsWith<EngineError.InvalidArgument> { e.split(h, SplitSpec.EveryN(0)) }
        assertFailsWith<EngineError.InvalidArgument> { e.split(h, SplitSpec.Before(listOf(2, 1))) }
        assertFailsWith<EngineError.InvalidArgument> { e.split(h, SplitSpec.Before(listOf(3))) }
    }

    @Test fun splitReportsProgressThroughTheEventFlow() = runTest {
        val e = engine()
        val h = e.open(FakeEngine.fakePdf(pageCount = 4))
        val received = ArrayList<EngineEvent>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            e.events.collect { received += it }
        }
        e.split(h, SplitSpec.EveryN(2))
        val fractions = received.filterIsInstance<EngineEvent.Progress>().map { it.fraction }
        assertEquals(listOf(0.5f, 1f), fractions)
    }

    @Test fun mergeHonoursRangesAndPasswords() = runTest {
        val e = engine()
        val a = FakeEngine.fakePdf(pageCount = 2, text = listOf("A1", "A2"))
        val b = FakeEngine.fakePdf(pageCount = 4, text = listOf("B1", "B2", "B3", "B4"), password = "pw")
        val merged = e.merge(
            listOf(
                MergeSource("a.pdf", a),
                MergeSource("b.pdf", b, password = "pw", ranges = listOf(PageRange(1, 2))),
            ),
        )
        assertEquals(listOf("A1", "A2", "B2", "B3"), e.texts(e.open(merged)))
    }

    @Test fun mergeWithoutThePasswordFails() = runTest {
        val e = engine()
        val b = FakeEngine.fakePdf(pageCount = 1, password = "pw")
        assertFailsWith<EngineError.PasswordRequired> { e.merge(listOf(MergeSource("b.pdf", b))) }
    }

    @Test fun mergeWithAWrongPasswordFails() = runTest {
        val e = engine()
        val b = FakeEngine.fakePdf(pageCount = 1, password = "pw")
        assertFailsWith<EngineError.WrongPassword> {
            e.merge(listOf(MergeSource("b.pdf", b, password = "bad")))
        }
    }

    @Test fun mergeRejectsOutOfRangeRanges() = runTest {
        val e = engine()
        val a = FakeEngine.fakePdf(pageCount = 2)
        assertFailsWith<EngineError.InvalidArgument> {
            e.merge(listOf(MergeSource("a.pdf", a, ranges = listOf(PageRange(0, 2)))))
        }
        assertFailsWith<EngineError.InvalidArgument> { e.merge(emptyList()) }
    }

    @Test fun mergeReportsProgressPerSource() = runTest {
        val e = engine()
        val received = ArrayList<EngineEvent>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            e.events.collect { received += it }
        }
        val a = FakeEngine.fakePdf(pageCount = 1)
        e.merge(listOf(MergeSource("a", a), MergeSource("b", a), MergeSource("c", a)))
        val fractions = received.filterIsInstance<EngineEvent.Progress>().map { it.fraction }
        assertEquals(listOf(1f / 3, 2f / 3, 1f), fractions)
    }

    @Test fun imagesToPdfMakesOnePagePerImageAtSeventyTwoDpiByDefault() = runTest {
        val e = engine()
        val bytes = e.imagesToPdf(
            listOf(
                ImageInput("a.png", FakeEngine.fakeImage(100, 200)),
                ImageInput("b.png", FakeEngine.fakeImage(50, 50)),
            ),
        )
        val infos = e.pageInfos(e.open(bytes))
        assertEquals(2, infos.size)
        assertEquals(100f, infos[0].widthPt, 0f)
        assertEquals(200f, infos[0].heightPt, 0f)
        assertEquals("b.png", infos[1].label)
    }

    @Test fun imagesToPdfScalesPageSizeByDpi() = runTest {
        val e = engine()
        val bytes = e.imagesToPdf(listOf(ImageInput("a.png", FakeEngine.fakeImage(100, 200))), dpi = 50.0)
        val info = e.pageInfos(e.open(bytes)).single()
        assertEquals(144f, info.widthPt, 0.001f)
        assertEquals(288f, info.heightPt, 0.001f)
    }

    @Test fun imagesToPdfRejectsUndecodableImagesAndBadDpi() = runTest {
        val e = engine()
        assertFailsWith<EngineError.InvalidArgument> {
            e.imagesToPdf(listOf(ImageInput("x.bin", "garbage".toByteArray())))
        }
        assertFailsWith<EngineError.InvalidArgument> {
            e.imagesToPdf(listOf(ImageInput("a.png", FakeEngine.fakeImage(1, 1))), dpi = 0.5)
        }
        assertFailsWith<EngineError.InvalidArgument> { e.imagesToPdf(emptyList()) }
    }

    // ---- scan ----

    @Test fun detectCornersReturnsNothingForAUniformFrame() = runTest {
        val e = engine()
        assertNull(e.detectDocumentCorners(ImageFrame(4, 4, ByteArray(64) { 0x7F })))
    }

    @Test fun detectCornersInsetsAFixedQuadClockwiseFromTopLeft() = runTest {
        val e = engine()
        val rgba = ByteArray(10 * 10 * 4) { 0x20 }
        rgba[0] = 0xFF.toByte()
        val quad = e.detectDocumentCorners(ImageFrame(10, 10, rgba))!!
        assertEquals(Quad(Pt(1f, 1f), Pt(9f, 1f), Pt(9f, 9f), Pt(1f, 9f)), quad)
    }

    @Test fun rectifyReturnsTheBoundingSizeOfTheQuad() = runTest {
        val e = engine()
        val frame = ImageFrame(10, 10, ByteArray(10 * 10 * 4) { 0x10 })
        val quad = Quad(Pt(1f, 1f), Pt(6f, 1f), Pt(6f, 4f), Pt(1f, 4f))
        val out = e.rectifyAndClean(frame, quad, ScanLook.GREYSCALE, level = 1)
        assertEquals(5, out.width)
        assertEquals(3, out.height)
        assertEquals(5 * 3 * 4, out.rgba.size)
    }

    @Test fun rectifyRejectsCornersOutsideTheFrame() = runTest {
        val e = engine()
        val frame = ImageFrame(10, 10, ByteArray(400))
        val quad = Quad(Pt(0f, 0f), Pt(11f, 0f), Pt(11f, 9f), Pt(0f, 9f))
        assertFailsWith<EngineError.InvalidArgument> {
            e.rectifyAndClean(frame, quad, ScanLook.COLOUR, level = 0)
        }
    }

    @Test fun frameSizeMustMatchRgba() = runTest {
        val e = engine()
        assertFailsWith<EngineError.InvalidArgument> { e.detectDocumentCorners(ImageFrame(4, 4, ByteArray(10))) }
    }

    // ---- scripted failures ----

    @Test fun hangedCallTimesOutAndTheEngineStaysUsable() = runTest {
        val e = engine()
        val h = e.open(threePages())
        e.hangFor(callName = "renderTile", ms = 10_000)
        assertFailsWith<TimeoutCancellationException> {
            withTimeout(100) { e.renderTile(h, TileRequest(0, 1f, 0, 0, 1, 1)) }
        }
        // The lock was released by the cancellation, so the next call goes through.
        assertEquals(3, e.pageInfos(h).size)
    }

    @Test fun hangOnlyAppliesToTheNamedCall() = runTest {
        val e = engine()
        val h = e.open(threePages())
        e.hangFor(callName = "renderTile", ms = 10_000)
        assertEquals(3, withTimeout(100) { e.pageInfos(h) }.size)
    }

    @Test fun hangIsOneShot() = runTest {
        val e = engine()
        val h = e.open(threePages())
        e.hangFor(ms = 10_000)
        assertFailsWith<TimeoutCancellationException> { withTimeout(50) { e.state(h) } }
        assertEquals(3, withTimeout(50) { e.state(h) }.pageCount)
    }

    @Test fun crashAfterNInvalidatesEveryHandleAndEmitsTheRestart() = runTest {
        val e = engine()
        val received = ArrayList<EngineEvent>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            e.events.collect { received += it }
        }
        val first = e.open(threePages())
        val second = e.open(threePages())
        e.crashAfter(0)
        assertFailsWith<EngineError.EngineDied> { e.state(first) }
        assertFailsWith<EngineError.InvalidArgument> { e.state(second) }
        val restart = received.single() as EngineEvent.SafeModeRestarted
        assertEquals(1_000L, restart.atEpochMs)
    }

    @Test fun crashAfterLetsTheFirstNCallsSucceed() = runTest {
        val e = engine()
        e.crashAfter(2)
        val h = e.open(threePages())
        e.state(h)
        assertFailsWith<EngineError.EngineDied> { e.state(h) }
    }

    @Test fun failNextFailsOnceThenRecovers() = runTest {
        val e = engine()
        val h = e.open(threePages())
        e.failNext(EngineError.Io("disk full"))
        assertFailsWith<EngineError.Io> { e.state(h) }
        assertEquals(3, e.state(h).pageCount)
    }

    @Test fun failNextWithCallNameSkipsOtherCalls() = runTest {
        val e = engine()
        val h = e.open(threePages())
        e.failNext(EngineError.Render("boom"), callName = "renderTile")
        assertEquals(3, e.state(h).pageCount)
        assertFailsWith<EngineError.Render> { e.renderTile(h, TileRequest(0, 1f, 0, 0, 1, 1)) }
    }

    @Test fun failNextWithEngineDiedInvalidatesHandles() = runTest {
        val e = engine()
        val h = e.open(threePages())
        e.failNext(EngineError.EngineDied("oom"))
        assertFailsWith<EngineError.EngineDied> { e.state(h) }
        assertFailsWith<EngineError.InvalidArgument> { e.state(h) }
    }

    // ---- concurrency ----

    @Test fun concurrentEditsOnDefaultDispatcherKeepStateConsistent() = runTest {
        val e = engine()
        val h = e.open(threePages())
        withContext(Dispatchers.Default) {
            coroutineScope {
                (1..50).map { async(Dispatchers.Default) { e.insertBlank(h, 0, 10f, 10f) } }.awaitAll()
            }
        }
        val doc = e.inspect(h)
        assertEquals(53, doc.pages.size)
        assertEquals(53, doc.pages.map { it.id }.toSet().size)
        assertEquals(50, e.undoLabels(h).size)
        assertTrue(e.state(h).dirty)
    }

    @Test fun eachHandleSerialisesItsOwnUndoStack() = runTest {
        val e = engine()
        val a = e.open(threePages())
        val b = e.open(threePages())
        withContext(Dispatchers.Default) {
            coroutineScope {
                listOf(a, b).map { h ->
                    async(Dispatchers.Default) { e.rotate(h, listOf(0), Rotation.CW90) }
                }.awaitAll()
            }
        }
        assertEquals(listOf("Rotate pages"), e.undoLabels(a))
        assertEquals(listOf("Rotate pages"), e.undoLabels(b))
    }

    // ---- stateless ----

    @Test fun mutatingCallsReturnTheNewStateNotTheOldOne() = runTest {
        val e = engine()
        val h = e.open(threePages())
        val state = e.move(h, listOf(0), 2)
        assertEquals(3, state.pageCount)
        assertTrue(state.canUndo)
    }

    @Test fun markupRectsAreValidatedAsLeftAboveRightBelow() = runTest {
        val e = engine()
        val h = e.open(threePages())
        assertFailsWith<EngineError.InvalidArgument> {
            e.addMarkup(h, markupAt(0).copy(rects = listOf(Rect(5f, 0f, 1f, 10f))))
        }
    }
}
