package app.pdfa.engine.service

import app.pdfa.engine.DocMeta
import app.pdfa.engine.EditState
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

/**
 * The seam between [UniffiEngine] and the generated UniFFI bindings. Everything here speaks
 * `:engine-api` types; the one implementation that touches `uniffi.*` is
 * `app.pdfa.engine.service.ffi.UniffiBackend`. Tests use a fake.
 *
 * Every method BLOCKS for as long as the native engine takes (which may be forever on a hostile
 * file) and throws only [app.pdfa.engine.EngineError] for engine-reported failures. Arguments are
 * already validated by [UniffiEngine] (no negative indices or sizes).
 */
interface NativeEngineBackend {
    fun open(bytes: ByteArray, password: String?): NativeSession
    fun merge(sources: List<MergeSource>): ByteArray
    fun imagesToPdf(images: List<ImageInput>, dpi: Double?): ByteArray
    fun detectDocumentCorners(frame: ImageFrame): Quad?
    fun rectifyAndClean(frame: ImageFrame, quad: Quad, look: ScanLook, level: Int): ImageFrame

    /** True only when the native library was built with the `encrypt` cargo feature. */
    fun encryptionAvailable(): Boolean
}

/** Tightly packed RGBA8 (premultiplied) as the engine returned it. */
class RawTile(val width: Int, val height: Int, val rgba: ByteArray)

/** One open document. [close] releases the native object and is called exactly once. */
interface NativeSession {
    fun meta(): DocMeta
    fun pageInfos(): List<PageInfo>
    fun renderTile(request: TileRequest): RawTile
    fun searchPage(page: Int, query: String, caseSensitive: Boolean, wholeWord: Boolean): List<Rect>
    fun selectText(page: Int, from: Pt, to: Pt): TextSelection?
    fun outline(offset: Int, limit: Int): OutlinePage
    fun links(page: Int): List<PageLink>

    fun state(): EditState
    fun rotate(pages: List<Int>, by: Rotation): EditState
    fun delete(pages: List<Int>): EditState
    fun move(pages: List<Int>, toIndex: Int): EditState
    fun duplicate(pages: List<Int>): EditState
    fun insertBlank(at: Int, widthPt: Float, heightPt: Float): EditState
    fun undo(): EditState
    fun redo(): EditState

    fun addMarkup(markup: NewMarkup): EditState
    fun listFields(): List<FieldInfo>
    fun setField(name: String, value: FieldValue): EditState
    fun resetForm(names: List<String>?): EditState

    fun saveIncremental(): ByteArray
    fun saveFull(): ByteArray
    fun confirmSaved(): EditState
    fun saveProtected(spec: ProtectionSpec): ByteArray
    fun removeProtection(): EditState

    fun extract(pages: List<Int>): ByteArray
    fun split(spec: SplitSpec): List<SplitPart>

    fun close()
}
