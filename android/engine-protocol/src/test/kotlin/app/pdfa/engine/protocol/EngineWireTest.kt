package app.pdfa.engine.protocol

import app.pdfa.engine.*
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized
import kotlin.random.Random

/** Every op, both directions, seeded random values. */
class EngineWireOpsTest {
    private fun forSeeds(f: (Gen) -> Unit) { for (s in 100L until 130L) f(Gen(s)) }

    @Test fun open() = forSeeds { g ->
        val b = g.bytes(); val pw = g.opt { g.str() }
        val a = EngineWire.parseOpenArgs(EngineWire.openArgs(b, pw))
        assertTrue(a.bytes.contentEquals(b)); assertEquals(pw, a.password)
        val h = g.long()
        assertEquals(h, EngineWire.parseOpenResult(EngineWire.openResult(h)))
    }

    @Test fun handleOnlyOps() = forSeeds { g ->
        val h = g.long()
        assertEquals(h, EngineWire.parseHandleArgs(EngineWire.handleArgs(h)))
    }

    @Test fun emptyAndUnit() {
        EngineWire.parseEmptyArgs(EngineWire.emptyArgs())
        EngineWire.parseUnitResult(EngineWire.unitResult())
        assertEquals(PingInfo(PROTOCOL_VERSION, -77L, 1234), EngineWire.parsePingResult(EngineWire.pingResult(-77L, 1234)))
    }

    @Test fun meta() = forSeeds { g -> val v = g.docMeta(); assertEquals(v, EngineWire.parseMetaResult(EngineWire.metaResult(v))) }
    @Test fun pageInfos() = forSeeds { g -> val v = g.list(6) { g.pageInfo() }; assertEquals(v, EngineWire.parsePageInfosResult(EngineWire.pageInfosResult(v))) }

    @Test fun renderTile() = forSeeds { g ->
        val h = g.long(); val q = g.tileRequest()
        assertEquals(RenderTileArgs(h, q), EngineWire.parseRenderTileArgs(EngineWire.renderTileArgs(h, q)))
        val i = g.tileInfo()
        assertEquals(i, EngineWire.parseRenderTileResult(EngineWire.renderTileResult(i)))
    }

    @Test fun searchPage() = forSeeds { g ->
        val h = g.long(); val q = g.str(); val cs = g.bool(); val ww = g.bool(); val p = g.page()
        assertEquals(SearchPageArgs(h, p, q, cs, ww), EngineWire.parseSearchPageArgs(EngineWire.searchPageArgs(h, p, q, cs, ww)))
        val rs = g.list(6) { g.rect() }
        assertEquals(rs, EngineWire.parseSearchPageResult(EngineWire.searchPageResult(rs)))
    }

    @Test fun selectText() = forSeeds { g ->
        val h = g.long(); val a = g.pt(); val b = g.pt(); val p = g.page()
        assertEquals(SelectTextArgs(h, p, a, b), EngineWire.parseSelectTextArgs(EngineWire.selectTextArgs(h, p, a, b)))
        val sel = g.opt { g.textSelection() }
        assertEquals(sel, EngineWire.parseSelectTextResult(EngineWire.selectTextResult(sel)))
    }

    @Test fun outline() = forSeeds { g ->
        val h = g.long(); val o = g.int(); val l = g.int()
        assertEquals(OutlineArgs(h, o, l), EngineWire.parseOutlineArgs(EngineWire.outlineArgs(h, o, l)))
        val v = g.outlinePage()
        assertEquals(v, EngineWire.parseOutlineResult(EngineWire.outlineResult(v)))
    }

    @Test fun links() = forSeeds { g ->
        val h = g.long(); val p = g.page()
        assertEquals(LinksArgs(h, p), EngineWire.parseLinksArgs(EngineWire.linksArgs(h, p)))
        val v = g.list(6) { g.pageLink() }
        assertEquals(v, EngineWire.parseLinksResult(EngineWire.linksResult(v)))
    }

    @Test fun editStateResults() = forSeeds { g ->
        val v = g.editState()
        assertEquals(v, EngineWire.parseEditStateResult(EngineWire.editStateResult(v)))
    }

    @Test fun pagesOps() = forSeeds { g ->
        val h = g.long(); val p = g.ints()
        assertEquals(PagesArgs(h, p), EngineWire.parsePagesArgs(EngineWire.pagesArgs(h, p)))
    }

    @Test fun rotate() = forSeeds { g ->
        val h = g.long(); val p = g.ints(); val by = Rotation.entries.random(g.r)
        assertEquals(RotateArgs(h, p, by), EngineWire.parseRotateArgs(EngineWire.rotateArgs(h, p, by)))
    }

    @Test fun move() = forSeeds { g ->
        val h = g.long(); val p = g.ints(); val t = g.int()
        assertEquals(MoveArgs(h, p, t), EngineWire.parseMoveArgs(EngineWire.moveArgs(h, p, t)))
    }

    @Test fun insertBlank() = forSeeds { g ->
        val h = g.long(); val at = g.page(); val w = g.float(); val ht = g.float()
        assertEquals(InsertBlankArgs(h, at, w, ht), EngineWire.parseInsertBlankArgs(EngineWire.insertBlankArgs(h, at, w, ht)))
    }

    @Test fun addMarkup() = forSeeds { g ->
        val h = g.long(); val m = g.newMarkup()
        assertEquals(AddMarkupArgs(h, m), EngineWire.parseAddMarkupArgs(EngineWire.addMarkupArgs(h, m)))
    }

    @Test fun listFields() = forSeeds { g ->
        val v = g.list(4) { g.fieldInfo() }
        assertEquals(v, EngineWire.parseListFieldsResult(EngineWire.listFieldsResult(v)))
    }

    @Test fun setField() = forSeeds { g ->
        val h = g.long(); val n = g.str(); val v = g.fieldValue()
        assertEquals(SetFieldArgs(h, n, v), EngineWire.parseSetFieldArgs(EngineWire.setFieldArgs(h, n, v)))
    }

    @Test fun resetForm() = forSeeds { g ->
        val h = g.long(); val names = g.opt { g.list(4) { g.str() } }
        assertEquals(ResetFormArgs(h, names), EngineWire.parseResetFormArgs(EngineWire.resetFormArgs(h, names)))
    }

    @Test fun bytesResults() = forSeeds { g ->
        val b = g.bytes(500)
        assertTrue(b.contentEquals(EngineWire.parseBytesResult(EngineWire.bytesResult(b))))
    }

    @Test fun saveProtected() = forSeeds { g ->
        val h = g.long(); val s = g.protectionSpec()
        val a = EngineWire.parseSaveProtectedArgs(EngineWire.saveProtectedArgs(h, s))
        assertEquals(h, a.handle); assertEquals(s, a.spec)
    }

    @Test fun split() = forSeeds { g ->
        val h = g.long(); val s = g.splitSpec()
        assertEquals(SplitArgs(h, s), EngineWire.parseSplitArgs(EngineWire.splitArgs(h, s)))
        val parts = g.list(4) { g.splitPart() }
        val back = EngineWire.parseSplitResult(EngineWire.splitResult(parts))
        assertEquals(parts.size, back.size)
        parts.zip(back).forEach { (a, b) -> assertTrue(eq(a, b)) }
    }

    @Test fun merge() = forSeeds { g ->
        val v = g.list(4) { g.mergeSource() }
        val back = EngineWire.parseMergeArgs(EngineWire.mergeArgs(v))
        assertEquals(v.size, back.size)
        v.zip(back).forEach { (a, b) -> assertTrue(eq(a, b)) }
    }

    @Test fun imagesToPdf() = forSeeds { g ->
        val v = g.list(4) { g.imageInput() }; val dpi = g.opt { g.r.nextDouble() * 1200 }
        val back = EngineWire.parseImagesToPdfArgs(EngineWire.imagesToPdfArgs(v, dpi))
        assertEquals(dpi, back.dpi)
        v.zip(back.images).forEach { (a, b) -> assertTrue(eq(a, b)) }
        assertEquals(v.size, back.images.size)
    }

    @Test fun detectCorners() = forSeeds { g ->
        val f = g.imageFrame()
        assertTrue(eq(f, EngineWire.parseDetectDocumentCornersArgs(EngineWire.detectDocumentCornersArgs(f))))
        val q = g.opt { g.quad() }
        assertEquals(q, EngineWire.parseDetectDocumentCornersResult(EngineWire.detectDocumentCornersResult(q)))
    }

    @Test fun rectify() = forSeeds { g ->
        val f = g.imageFrame(); val q = g.quad(); val look = ScanLook.entries.random(g.r); val lv = g.int()
        val a = EngineWire.parseRectifyAndCleanArgs(EngineWire.rectifyAndCleanArgs(f, q, look, lv))
        assertTrue(eq(f, a.frame)); assertEquals(q, a.quad); assertEquals(look, a.look); assertEquals(lv, a.level)
        val out = g.imageFrame()
        assertTrue(eq(out, EngineWire.parseRectifyAndCleanResult(EngineWire.rectifyAndCleanResult(out))))
    }

    @Test fun progress() = forSeeds { g ->
        val id = g.str(); val f = g.r.nextFloat()
        assertEquals(EngineEvent.Progress(id, f), EngineWire.parseProgress(EngineWire.progress(id, f)))
    }

    @Test fun argHoldersDoNotPrintSecrets() {
        val open = EngineWire.parseOpenArgs(EngineWire.openArgs(byteArrayOf(1), "hunter2"))
        assertFalse(open.toString().contains("hunter2"))
        val p = EngineWire.parseSaveProtectedArgs(EngineWire.saveProtectedArgs(1, ProtectionSpec("hunter2", "owner-pw")))
        assertFalse(p.toString().contains("hunter2")); assertFalse(p.toString().contains("owner-pw"))
    }
}

/** Hostile input against every parser. */
@RunWith(Parameterized::class)
class HostileInputTest(private val name: String, private val parser: (ByteArray) -> Any?, private val valid: ByteArray) {
    companion object {
        private val g = Gen(7)
        private fun p(name: String, valid: ByteArray, parser: (ByteArray) -> Any?) = arrayOf<Any>(name, parser, valid)

        @JvmStatic
        @Parameterized.Parameters(name = "{0}")
        fun params(): List<Array<Any>> = listOf(
            p("openArgs", EngineWire.openArgs(g.bytes(), "pw"), EngineWire::parseOpenArgs),
            p("handleArgs", EngineWire.handleArgs(5), EngineWire::parseHandleArgs),
            p("openResult", EngineWire.openResult(5), EngineWire::parseOpenResult),
            p("meta", EngineWire.metaResult(g.docMeta()), EngineWire::parseMetaResult),
            p("pageInfos", EngineWire.pageInfosResult(g.list(5) { g.pageInfo() }.ifEmpty { listOf(g.pageInfo()) }), EngineWire::parsePageInfosResult),
            p("renderTileArgs", EngineWire.renderTileArgs(1, g.tileRequest()), EngineWire::parseRenderTileArgs),
            p("renderTileResult", EngineWire.renderTileResult(g.tileInfo()), EngineWire::parseRenderTileResult),
            p("searchArgs", EngineWire.searchPageArgs(1, 2, "abc", true, false), EngineWire::parseSearchPageArgs),
            p("searchResult", EngineWire.searchPageResult(listOf(g.rect(), g.rect())), EngineWire::parseSearchPageResult),
            p("selectArgs", EngineWire.selectTextArgs(1, 2, g.pt(), g.pt()), EngineWire::parseSelectTextArgs),
            p("selectResult", EngineWire.selectTextResult(TextSelection("hi", listOf(g.rect()))), EngineWire::parseSelectTextResult),
            p("outlineArgs", EngineWire.outlineArgs(1, 2, 3), EngineWire::parseOutlineArgs),
            p("outlineResult", EngineWire.outlineResult(OutlinePage(listOf(OutlineRow(listOf(0, 1), "t", 3, true)), 4)), EngineWire::parseOutlineResult),
            p("linksArgs", EngineWire.linksArgs(1, 2), EngineWire::parseLinksArgs),
            p("linksResult", EngineWire.linksResult(listOf(g.pageLink(), g.pageLink())), EngineWire::parseLinksResult),
            p("editState", EngineWire.editStateResult(g.editState()), EngineWire::parseEditStateResult),
            p("pagesArgs", EngineWire.pagesArgs(1, listOf(1, 2, 3)), EngineWire::parsePagesArgs),
            p("rotateArgs", EngineWire.rotateArgs(1, listOf(1, 2), Rotation.HALF), EngineWire::parseRotateArgs),
            p("moveArgs", EngineWire.moveArgs(1, listOf(1, 2), 0), EngineWire::parseMoveArgs),
            p("insertBlankArgs", EngineWire.insertBlankArgs(1, 2, 3f, 4f), EngineWire::parseInsertBlankArgs),
            p("addMarkupArgs", EngineWire.addMarkupArgs(1, g.newMarkup()), EngineWire::parseAddMarkupArgs),
            p("listFields", EngineWire.listFieldsResult(listOf(g.fieldInfo(), g.fieldInfo())), EngineWire::parseListFieldsResult),
            p("setFieldArgs", EngineWire.setFieldArgs(1, "n", FieldValue.Choice(listOf("a", "b"))), EngineWire::parseSetFieldArgs),
            p("resetFormArgs", EngineWire.resetFormArgs(1, listOf("a", "b")), EngineWire::parseResetFormArgs),
            p("bytesResult", EngineWire.bytesResult(g.bytes()), EngineWire::parseBytesResult),
            p("saveProtectedArgs", EngineWire.saveProtectedArgs(1, g.protectionSpec()), EngineWire::parseSaveProtectedArgs),
            p("splitArgs", EngineWire.splitArgs(1, SplitSpec.Before(listOf(1, 2))), EngineWire::parseSplitArgs),
            p("splitResult", EngineWire.splitResult(listOf(g.splitPart(), g.splitPart())), EngineWire::parseSplitResult),
            p("mergeArgs", EngineWire.mergeArgs(listOf(g.mergeSource(), g.mergeSource())), EngineWire::parseMergeArgs),
            p("imagesArgs", EngineWire.imagesToPdfArgs(listOf(g.imageInput(), g.imageInput()), 300.0), EngineWire::parseImagesToPdfArgs),
            p("cornersArgs", EngineWire.detectDocumentCornersArgs(g.imageFrame()), EngineWire::parseDetectDocumentCornersArgs),
            p("cornersResult", EngineWire.detectDocumentCornersResult(g.quad()), EngineWire::parseDetectDocumentCornersResult),
            p("rectifyArgs", EngineWire.rectifyAndCleanArgs(g.imageFrame(), g.quad(), ScanLook.COLOUR, 2), EngineWire::parseRectifyAndCleanArgs),
            p("rectifyResult", EngineWire.rectifyAndCleanResult(g.imageFrame()), EngineWire::parseRectifyAndCleanResult),
            p("progress", EngineWire.progress("op", 0.5f), EngineWire::parseProgress),
            p("ping", EngineWire.pingResult(5L, 6), EngineWire::parsePingResult),
            p("errorCodec", ErrorCodec.encode(EngineError.Render("x")), ErrorCodec::decode),
            p("requestFrame", Request(1, Op.META, byteArrayOf(1, 2)).encode(), Request::decode),
            p("responseFrame", Response.ok(1, byteArrayOf(1, 2)).encode(), Response::decode),
        )
    }

    /** Runs [parser]; only success or WireException is acceptable. Returns true on success. */
    private fun attempt(bytes: ByteArray): Boolean = try { parser(bytes); true } catch (e: WireException) { false }

    @Test fun validParses() { assertTrue(attempt(valid)) }

    @Test fun everyStrictPrefixIsRejected() {
        for (n in 0 until valid.size) assertFalse("$name prefix $n", attempt(valid.copyOf(n)))
    }

    @Test fun trailingByteIsRejected() { assertFalse(attempt(valid + byteArrayOf(0))) }

    @Test fun randomGarbageOnlyThrowsWireException() {
        val r = Random(99)
        repeat(300) { attempt(r.nextBytes(r.nextInt(0, 64))) }
    }

    @Test fun hostileLengthPrefixAtEveryOffsetNeverAllocates() {
        for (value in intArrayOf(0x7FFFFFFF, -1, Int.MIN_VALUE, 0x20000001, 1_000_001)) {
            var off = 0
            while (off + 4 <= valid.size) {
                val c = valid.copyOf()
                for (i in 0 until 4) c[off + i] = (value ushr (8 * i)).toByte()
                attempt(c)
                off++
            }
        }
    }

    @Test fun allMaxBytesGarbage() {
        attempt(ByteArray(64) { 0x7F })
        attempt(ByteArray(64) { 0xFF.toByte() })
        attempt(ByteArray(64))
    }
}
