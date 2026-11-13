package app.pdfa.engine.protocol

import app.pdfa.engine.DocMeta
import app.pdfa.engine.EditState
import app.pdfa.engine.EngineEvent
import app.pdfa.engine.FieldInfo
import app.pdfa.engine.FieldValue
import app.pdfa.engine.ImageFrame
import app.pdfa.engine.ImageInput
import app.pdfa.engine.MergeSource
import app.pdfa.engine.NewMarkup
import app.pdfa.engine.OutlinePage
import app.pdfa.engine.PageInfo
import app.pdfa.engine.PageLink
import app.pdfa.engine.ProtectionSpec
import app.pdfa.engine.Pt
import app.pdfa.engine.Quad
import app.pdfa.engine.Rect
import app.pdfa.engine.Rotation
import app.pdfa.engine.ScanLook
import app.pdfa.engine.SplitPart
import app.pdfa.engine.SplitSpec
import app.pdfa.engine.TextSelection
import app.pdfa.engine.TileRequest

// ---- argument holders (toString never prints bytes or passwords) ----

/** PING reply: protocol version, a random id fixed for one service process lifetime, and its pid. */
data class PingInfo(val protocolVersion: Int, val bootId: Long, val pid: Int)

class OpenArgs(val bytes: ByteArray, val password: String?) {
    override fun toString() = "OpenArgs(bytes=${bytes.size}, password=${if (password == null) "none" else "<redacted>"})"
}

data class RenderTileArgs(val handle: Long, val request: TileRequest)
data class SearchPageArgs(val handle: Long, val page: Int, val query: String, val caseSensitive: Boolean, val wholeWord: Boolean) {
    override fun toString() = "SearchPageArgs(handle=$handle, page=$page)"
}
data class SelectTextArgs(val handle: Long, val page: Int, val from: Pt, val to: Pt)
data class OutlineArgs(val handle: Long, val offset: Int, val limit: Int)
data class LinksArgs(val handle: Long, val page: Int)
data class PagesArgs(val handle: Long, val pages: List<Int>)
data class RotateArgs(val handle: Long, val pages: List<Int>, val by: Rotation)
data class MoveArgs(val handle: Long, val pages: List<Int>, val toIndex: Int)
data class InsertBlankArgs(val handle: Long, val at: Int, val widthPt: Float, val heightPt: Float)
data class AddMarkupArgs(val handle: Long, val markup: NewMarkup) {
    override fun toString() = "AddMarkupArgs(handle=$handle)"
}
data class SetFieldArgs(val handle: Long, val name: String, val value: FieldValue) {
    override fun toString() = "SetFieldArgs(handle=$handle)"
}
data class ResetFormArgs(val handle: Long, val names: List<String>?) {
    override fun toString() = "ResetFormArgs(handle=$handle)"
}
class SaveProtectedArgs(val handle: Long, val spec: ProtectionSpec) {
    override fun toString() = "SaveProtectedArgs(handle=$handle, spec=<redacted>)"
}
data class SplitArgs(val handle: Long, val spec: SplitSpec)
class ImagesToPdfArgs(val images: List<ImageInput>, val dpi: Double?) {
    override fun toString() = "ImagesToPdfArgs(images=${images.size}, dpi=$dpi)"
}
class RectifyArgs(val frame: ImageFrame, val quad: Quad, val look: ScanLook, val level: Int) {
    override fun toString() = "RectifyArgs(${frame.width}x${frame.height}, look=$look, level=$level)"
}

/**
 * Per-op payload builders and parsers. Convention (both directions exist for every op):
 *  - request payload:  `<op>Args(...)` builds, `parse<Op>Args(payload)` parses;
 *  - response payload: `<op>Result(...)` builds, `parse<Op>Result(payload)` parses.
 * Ops sharing a shape share functions (see the table in README.md): `handleArgs`,
 * `editStateResult`, `bytesResult`, `unitResult`, `emptyArgs`. Every parser calls
 * [WireReader.finish], so trailing bytes are a [WireException].
 */
object EngineWire {
    private inline fun build(initial: Int = 64, f: WireWriter.() -> Unit): ByteArray =
        WireWriter(initial).apply(f).toByteArray()

    private inline fun <T> parse(payload: ByteArray, f: WireReader.() -> T): T {
        val r = WireReader(payload)
        val v = r.f()
        r.finish()
        return v
    }

    // ---- empty / unit ----
    fun emptyArgs(): ByteArray = ByteArray(0)
    fun parseEmptyArgs(payload: ByteArray) = parse(payload) { }
    fun unitResult(): ByteArray = ByteArray(0)
    fun parseUnitResult(payload: ByteArray) = parse(payload) { }

    // ---- handle-only ops: CLOSE META PAGE_INFOS STATE UNDO REDO LIST_FIELDS SAVE_INCREMENTAL
    //      SAVE_FULL CONFIRM_SAVED REMOVE_PROTECTION ----
    fun handleArgs(handle: Long): ByteArray = build { i64(handle) }
    fun parseHandleArgs(payload: ByteArray): Long = parse(payload) { i64() }

    // ---- OPEN ----
    fun openArgs(bytes: ByteArray, password: String?): ByteArray =
        build(bytes.size + 32) { bytes(bytes); optional(password) { string(it) } }
    fun parseOpenArgs(payload: ByteArray): OpenArgs = parse(payload) { OpenArgs(bytes(), optional { string() }) }
    fun openResult(handle: Long): ByteArray = build { i64(handle) }
    fun parseOpenResult(payload: ByteArray): Long = parse(payload) { i64() }

    // ---- META / PAGE_INFOS ----
    fun metaResult(v: DocMeta): ByteArray = build { writeDocMeta(v) }
    fun parseMetaResult(payload: ByteArray): DocMeta = parse(payload) { readDocMeta() }
    fun pageInfosResult(v: List<PageInfo>): ByteArray = build { list(v) { writePageInfo(it) } }
    fun parsePageInfosResult(payload: ByteArray): List<PageInfo> = parse(payload) { list(12) { readPageInfo() } }

    // ---- RENDER_TILE ----
    fun renderTileArgs(handle: Long, request: TileRequest): ByteArray = build { i64(handle); writeTileRequest(request) }
    fun parseRenderTileArgs(payload: ByteArray): RenderTileArgs = parse(payload) { RenderTileArgs(i64(), readTileRequest()) }
    fun renderTileResult(info: TileInfo): ByteArray = build { writeTileInfo(info) }
    fun parseRenderTileResult(payload: ByteArray): TileInfo = parse(payload) { readTileInfo() }

    // ---- SEARCH_PAGE ----
    fun searchPageArgs(handle: Long, page: Int, query: String, caseSensitive: Boolean, wholeWord: Boolean): ByteArray =
        build { i64(handle); i32(page); string(query); bool(caseSensitive); bool(wholeWord) }
    fun parseSearchPageArgs(payload: ByteArray): SearchPageArgs =
        parse(payload) { SearchPageArgs(i64(), i32(), string(), bool(), bool()) }
    fun searchPageResult(v: List<Rect>): ByteArray = build { writeRects(v) }
    fun parseSearchPageResult(payload: ByteArray): List<Rect> = parse(payload) { readRects() }

    // ---- SELECT_TEXT ----
    fun selectTextArgs(handle: Long, page: Int, from: Pt, to: Pt): ByteArray =
        build { i64(handle); i32(page); writePt(from); writePt(to) }
    fun parseSelectTextArgs(payload: ByteArray): SelectTextArgs =
        parse(payload) { SelectTextArgs(i64(), i32(), readPt(), readPt()) }
    fun selectTextResult(v: TextSelection?): ByteArray = build { optional(v) { writeTextSelection(it) } }
    fun parseSelectTextResult(payload: ByteArray): TextSelection? = parse(payload) { optional { readTextSelection() } }

    // ---- OUTLINE ----
    fun outlineArgs(handle: Long, offset: Int, limit: Int): ByteArray = build { i64(handle); i32(offset); i32(limit) }
    fun parseOutlineArgs(payload: ByteArray): OutlineArgs = parse(payload) { OutlineArgs(i64(), i32(), i32()) }
    fun outlineResult(v: OutlinePage): ByteArray = build { writeOutlinePage(v) }
    fun parseOutlineResult(payload: ByteArray): OutlinePage = parse(payload) { readOutlinePage() }

    // ---- LINKS ----
    fun linksArgs(handle: Long, page: Int): ByteArray = build { i64(handle); i32(page) }
    fun parseLinksArgs(payload: ByteArray): LinksArgs = parse(payload) { LinksArgs(i64(), i32()) }
    fun linksResult(v: List<PageLink>): ByteArray = build { list(v) { writePageLink(it) } }
    fun parseLinksResult(payload: ByteArray): List<PageLink> = parse(payload) { list(17) { readPageLink() } }

    // ---- EditState results: STATE ROTATE DELETE MOVE DUPLICATE INSERT_BLANK UNDO REDO ADD_MARKUP
    //      SET_FIELD RESET_FORM CONFIRM_SAVED REMOVE_PROTECTION ----
    fun editStateResult(v: EditState): ByteArray = build { writeEditState(v) }
    fun parseEditStateResult(payload: ByteArray): EditState = parse(payload) { readEditState() }

    // ---- page-list ops: DELETE DUPLICATE EXTRACT ----
    fun pagesArgs(handle: Long, pages: List<Int>): ByteArray = build { i64(handle); intList(pages) }
    fun parsePagesArgs(payload: ByteArray): PagesArgs = parse(payload) { PagesArgs(i64(), intList()) }

    // ---- ROTATE / MOVE / INSERT_BLANK ----
    fun rotateArgs(handle: Long, pages: List<Int>, by: Rotation): ByteArray =
        build { i64(handle); intList(pages); writeRotation(by) }
    fun parseRotateArgs(payload: ByteArray): RotateArgs = parse(payload) { RotateArgs(i64(), intList(), readRotation()) }
    fun moveArgs(handle: Long, pages: List<Int>, toIndex: Int): ByteArray =
        build { i64(handle); intList(pages); i32(toIndex) }
    fun parseMoveArgs(payload: ByteArray): MoveArgs = parse(payload) { MoveArgs(i64(), intList(), i32()) }
    fun insertBlankArgs(handle: Long, at: Int, widthPt: Float, heightPt: Float): ByteArray =
        build { i64(handle); i32(at); f32(widthPt); f32(heightPt) }
    fun parseInsertBlankArgs(payload: ByteArray): InsertBlankArgs =
        parse(payload) { InsertBlankArgs(i64(), i32(), f32(), f32()) }

    // ---- ADD_MARKUP / LIST_FIELDS / SET_FIELD / RESET_FORM ----
    fun addMarkupArgs(handle: Long, markup: NewMarkup): ByteArray = build { i64(handle); writeNewMarkup(markup) }
    fun parseAddMarkupArgs(payload: ByteArray): AddMarkupArgs = parse(payload) { AddMarkupArgs(i64(), readNewMarkup()) }
    fun listFieldsResult(v: List<FieldInfo>): ByteArray = build { list(v) { writeFieldInfo(it) } }
    fun parseListFieldsResult(payload: ByteArray): List<FieldInfo> = parse(payload) { list(20) { readFieldInfo() } }
    fun setFieldArgs(handle: Long, name: String, value: FieldValue): ByteArray =
        build { i64(handle); string(name); writeFieldValue(value) }
    fun parseSetFieldArgs(payload: ByteArray): SetFieldArgs = parse(payload) { SetFieldArgs(i64(), string(), readFieldValue()) }
    fun resetFormArgs(handle: Long, names: List<String>?): ByteArray =
        build { i64(handle); optional(names) { n -> list(n) { string(it) } } }
    fun parseResetFormArgs(payload: ByteArray): ResetFormArgs =
        parse(payload) { ResetFormArgs(i64(), optional { list(4) { string() } }) }

    // ---- byte results: SAVE_INCREMENTAL SAVE_FULL SAVE_PROTECTED EXTRACT MERGE IMAGES_TO_PDF ----
    fun bytesResult(v: ByteArray): ByteArray = build(v.size + 8) { bytes(v) }
    fun parseBytesResult(payload: ByteArray): ByteArray = parse(payload) { bytes() }

    // ---- SAVE_PROTECTED ----
    fun saveProtectedArgs(handle: Long, spec: ProtectionSpec): ByteArray = build { i64(handle); writeProtectionSpec(spec) }
    fun parseSaveProtectedArgs(payload: ByteArray): SaveProtectedArgs =
        parse(payload) { SaveProtectedArgs(i64(), readProtectionSpec()) }

    // ---- SPLIT ----
    fun splitArgs(handle: Long, spec: SplitSpec): ByteArray = build { i64(handle); writeSplitSpec(spec) }
    fun parseSplitArgs(payload: ByteArray): SplitArgs = parse(payload) { SplitArgs(i64(), readSplitSpec()) }
    fun splitResult(v: List<SplitPart>, sizeHint: Int = 64): ByteArray = build(sizeHint) { list(v) { writeSplitPart(it) } }
    fun parseSplitResult(payload: ByteArray): List<SplitPart> = parse(payload) { list(8) { readSplitPart() } }

    // ---- MERGE ----
    fun mergeArgs(sources: List<MergeSource>): ByteArray =
        build(sources.sumOf { it.bytes.size.toLong() }.coerceAtMost(WireLimits.MAX_BLOB_BYTES.toLong()).toInt() + 64) {
            list(sources) { writeMergeSource(it) }
        }
    fun parseMergeArgs(payload: ByteArray): List<MergeSource> = parse(payload) { list(9) { readMergeSource() } }

    // ---- IMAGES_TO_PDF ----
    fun imagesToPdfArgs(images: List<ImageInput>, dpi: Double?): ByteArray =
        build(images.sumOf { it.bytes.size.toLong() }.coerceAtMost(WireLimits.MAX_BLOB_BYTES.toLong()).toInt() + 64) {
            list(images) { writeImageInput(it) }; optional(dpi) { f64(it) }
        }
    fun parseImagesToPdfArgs(payload: ByteArray): ImagesToPdfArgs =
        parse(payload) { ImagesToPdfArgs(list(8) { readImageInput() }, optional { f64() }) }

    // ---- DETECT_DOCUMENT_CORNERS ----
    fun detectDocumentCornersArgs(frame: ImageFrame): ByteArray = build(frame.rgba.size + 32) { writeImageFrame(frame) }
    fun parseDetectDocumentCornersArgs(payload: ByteArray): ImageFrame = parse(payload) { readImageFrame() }
    fun detectDocumentCornersResult(v: Quad?): ByteArray = build { optional(v) { writeQuad(it) } }
    fun parseDetectDocumentCornersResult(payload: ByteArray): Quad? = parse(payload) { optional { readQuad() } }

    // ---- RECTIFY_AND_CLEAN ----
    fun rectifyAndCleanArgs(frame: ImageFrame, quad: Quad, look: ScanLook, level: Int): ByteArray =
        build(frame.rgba.size + 96) { writeImageFrame(frame); writeQuad(quad); writeScanLook(look); i32(level) }
    fun parseRectifyAndCleanArgs(payload: ByteArray): RectifyArgs =
        parse(payload) { RectifyArgs(readImageFrame(), readQuad(), readScanLook(), i32()) }
    fun rectifyAndCleanResult(v: ImageFrame): ByteArray = build(v.rgba.size + 32) { writeImageFrame(v) }
    fun parseRectifyAndCleanResult(payload: ByteArray): ImageFrame = parse(payload) { readImageFrame() }

    // ---- PING / SHUTDOWN ----
    fun pingResult(bootId: Long, pid: Int, protocolVersion: Int = PROTOCOL_VERSION): ByteArray =
        build { i32(protocolVersion); i64(bootId); i32(pid) }
    fun parsePingResult(payload: ByteArray): PingInfo = parse(payload) { PingInfo(i32(), i64(), i32()) }

    // ---- Progress (server push, not a Response): see README ----
    fun progress(opId: String, fraction: Float): ByteArray = build { string(opId); f32(fraction) }
    fun parseProgress(payload: ByteArray): EngineEvent.Progress = parse(payload) { EngineEvent.Progress(string(), f32()) }
}
