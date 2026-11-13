package app.pdfa.engine.protocol

import app.pdfa.engine.*
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.test.assertFailsWith

class ModelCodecTest {
    private val seeds = 0L until 60L

    private fun <T> rt(
        gen: Gen.() -> T,
        write: WireWriter.(T) -> Unit,
        read: WireReader.() -> T,
        same: (T, T) -> Boolean = { a, b -> a == b },
    ) {
        for (seed in seeds) {
            val v = Gen(seed).gen()
            val bytes = WireWriter().also { it.write(v) }.toByteArray()
            val r = WireReader(bytes)
            val back = r.read()
            r.finish()
            assertTrue("seed $seed: $v != $back", same(v, back))
            val again = WireWriter().also { it.write(back) }.toByteArray()
            assertTrue("seed $seed re-encode differs", bytes.contentEquals(again))
        }
    }

    @Test fun pt() = rt({ pt() }, { writePt(it) }, { readPt() })
    @Test fun rect() = rt({ rect() }, { writeRect(it) }, { readRect() })
    @Test fun quad() = rt({ quad() }, { writeQuad(it) }, { readQuad() })
    @Test fun pageRange() = rt({ pageRange() }, { writePageRange(it) }, { readPageRange() })
    @Test fun rects() = rt({ list(8) { rect() } }, { writeRects(it) }, { readRects() })
    @Test fun pageInfo() = rt({ pageInfo() }, { writePageInfo(it) }, { readPageInfo() })
    @Test fun editState() = rt({ editState() }, { writeEditState(it) }, { readEditState() })
    @Test fun docMeta() = rt({ docMeta() }, { writeDocMeta(it) }, { readDocMeta() })
    @Test fun tileRequest() = rt({ tileRequest() }, { writeTileRequest(it) }, { readTileRequest() })
    @Test fun tileInfo() = rt({ tileInfo() }, { writeTileInfo(it) }, { readTileInfo() })
    @Test fun searchHit() = rt({ searchHit() }, { writeSearchHit(it) }, { readSearchHit() })
    @Test fun textSelection() = rt({ textSelection() }, { writeTextSelection(it) }, { readTextSelection() })
    @Test fun outlineRow() = rt({ outlineRow() }, { writeOutlineRow(it) }, { readOutlineRow() })
    @Test fun outlinePage() = rt({ outlinePage() }, { writeOutlinePage(it) }, { readOutlinePage() })
    @Test fun linkTarget() = rt({ linkTarget() }, { writeLinkTarget(it) }, { readLinkTarget() })
    @Test fun pageLink() = rt({ pageLink() }, { writePageLink(it) }, { readPageLink() })
    @Test fun stroke() = rt({ stroke() }, { writeStroke(it) }, { readStroke() })
    @Test fun newMarkup() = rt({ newMarkup() }, { writeNewMarkup(it) }, { readNewMarkup() })
    @Test fun fieldOption() = rt({ fieldOption() }, { writeFieldOption(it) }, { readFieldOption() })
    @Test fun fieldWidget() = rt({ fieldWidget() }, { writeFieldWidget(it) }, { readFieldWidget() })
    @Test fun fieldInfo() = rt({ fieldInfo() }, { writeFieldInfo(it) }, { readFieldInfo() })
    @Test fun fieldValue() = rt({ fieldValue() }, { writeFieldValue(it) }, { readFieldValue() })
    @Test fun protectionSpec() = rt({ protectionSpec() }, { writeProtectionSpec(it) }, { readProtectionSpec() })
    @Test fun mergeSource() = rt({ mergeSource() }, { writeMergeSource(it) }, { readMergeSource() }, ::eq)
    @Test fun splitSpec() = rt({ splitSpec() }, { writeSplitSpec(it) }, { readSplitSpec() })
    @Test fun splitPart() = rt({ splitPart() }, { writeSplitPart(it) }, { readSplitPart() }, ::eq)
    @Test fun imageInput() = rt({ imageInput() }, { writeImageInput(it) }, { readImageInput() }, ::eq)
    @Test fun imageFrame() = rt({ imageFrame() }, { writeImageFrame(it) }, { readImageFrame() }, ::eq)

    @Test fun enumsRoundTripEveryEntry() {
        for (v in Rotation.entries) assertEquals(v, WireReader(WireWriter().writeRotation(v).toByteArray()).readRotation())
        for (v in ScanLook.entries) assertEquals(v, WireReader(WireWriter().writeScanLook(v).toByteArray()).readScanLook())
        for (v in MarkupKind.entries) assertEquals(v, WireReader(WireWriter().writeMarkupKind(v).toByteArray()).readMarkupKind())
        for (v in FieldKind.entries) assertEquals(v, WireReader(WireWriter().writeFieldKind(v).toByteArray()).readFieldKind())
        for (v in PrintPerm.entries) assertEquals(v, WireReader(WireWriter().writePrintPerm(v).toByteArray()).readPrintPerm())
        for (v in ChangePerm.entries) assertEquals(v, WireReader(WireWriter().writeChangePerm(v).toByteArray()).readChangePerm())
    }

    @Test fun unknownEnumOrdinalIsWireException() {
        assertFailsWith<WireException> { WireReader(byteArrayOf(99)).readRotation() }
        assertFailsWith<WireException> { WireReader(byteArrayOf(-1)).readFieldKind() }
    }

    @Test fun unknownTagsAreWireExceptions() {
        assertFailsWith<WireException> { WireReader(byteArrayOf(9)).readLinkTarget() }
        assertFailsWith<WireException> { WireReader(byteArrayOf(9)).readFieldValue() }
        assertFailsWith<WireException> { WireReader(byteArrayOf(9)).readSplitSpec() }
    }

    @Test fun badPresenceFlagIsWireException() {
        assertFailsWith<WireException> { WireReader(byteArrayOf(2)).optional { u8() } }
    }

    @Test fun badBooleanIsWireException() {
        assertFailsWith<WireException> { WireReader(byteArrayOf(2)).bool() }
    }

    @Test fun specialFloatsSurvive() {
        for (f in listOf(0f, -0f, Float.MAX_VALUE, Float.MIN_VALUE, Float.POSITIVE_INFINITY, Float.NaN)) {
            val back = WireReader(WireWriter().writePt(Pt(f, f)).toByteArray()).readPt()
            assertEquals(java.lang.Float.floatToRawIntBits(f), java.lang.Float.floatToRawIntBits(back.x))
        }
    }

    @Test fun intExtremesSurvive() {
        val s = EditState(true, false, null, Int.MIN_VALUE, true)
        assertEquals(s, WireReader(WireWriter().writeEditState(s).toByteArray()).readEditState())
        val i = WireReader(WireWriter().i64(Long.MIN_VALUE).toByteArray()).i64()
        assertEquals(Long.MIN_VALUE, i)
    }
}
