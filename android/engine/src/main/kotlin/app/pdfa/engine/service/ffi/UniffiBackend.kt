package app.pdfa.engine.service.ffi

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
import app.pdfa.engine.service.NativeEngineBackend
import app.pdfa.engine.service.NativeSession
import app.pdfa.engine.service.RawTile
import uniffi.pdfa_ffi.PdfException
import uniffi.pdfa_ffi.PdfSession

/**
 * The real engine: `libpdfa_ffi.so` through the generated UniFFI bindings. This package is the only
 * place that imports `uniffi.*`. The library is loaded by JNA on first use (`pdfa_ffi`, from the
 * APK's `lib/<abi>`), which works inside the isolated process: no file or network access is needed.
 */
class UniffiBackend : NativeEngineBackend {
    override fun open(bytes: ByteArray, password: String?): NativeSession =
        ffi { UniffiSession(PdfSession.open(bytes, password)) }

    override fun merge(sources: List<MergeSource>): ByteArray =
        ffi { uniffi.pdfa_ffi.merge(sources.map { it.toFfi() }) }

    override fun imagesToPdf(images: List<ImageInput>, dpi: Double?): ByteArray =
        ffi { uniffi.pdfa_ffi.imagesToPdf(images.map { it.toFfi() }, dpi) }

    override fun detectDocumentCorners(frame: ImageFrame): Quad? =
        ffi { uniffi.pdfa_ffi.detectDocumentCorners(frame.rgba, frame.width.toUInt(), frame.height.toUInt())?.toApi() }

    override fun rectifyAndClean(frame: ImageFrame, quad: Quad, look: ScanLook, level: Int): ImageFrame = ffi {
        val out = uniffi.pdfa_ffi.rectifyAndClean(
            frame.rgba, frame.width.toUInt(), frame.height.toUInt(), quad.toFfi(), look.toFfi(), level.toUByte(),
        )
        ImageFrame(out.width.int(), out.height.int(), out.rgba)
    }

    override fun encryptionAvailable(): Boolean = uniffi.pdfa_ffi.encryptionAvailable()

    companion object {
        /** Forces JNA to load `libpdfa_ffi.so` now; throws (UnsatisfiedLinkError...) when it cannot. */
        fun warmUp() {
            uniffi.pdfa_ffi.encryptionAvailable()
        }
    }
}

/** Runs one native call; the engine's typed error becomes the matching EngineError. */
private inline fun <T> ffi(block: () -> T): T =
    try {
        block()
    } catch (e: PdfException) {
        throw e.toEngineError()
    }

private class UniffiSession(private val s: PdfSession) : NativeSession {
    override fun meta(): DocMeta = ffi { s.meta().toApi() }
    override fun pageInfos(): List<PageInfo> = ffi { s.pageInfos().map { it.toApi() } }

    override fun renderTile(request: TileRequest): RawTile = ffi {
        val img = s.renderTile(
            request.page.toUInt(), request.scale, request.x.toUInt(), request.y.toUInt(),
            request.width.toUInt(), request.height.toUInt(), request.preview,
        )
        RawTile(img.width.int(), img.height.int(), img.rgba)
    }

    override fun searchPage(page: Int, query: String, caseSensitive: Boolean, wholeWord: Boolean): List<Rect> =
        ffi { s.searchPage(page.toUInt(), query, caseSensitive, wholeWord).map { it.toApi() } }

    override fun selectText(page: Int, from: Pt, to: Pt): TextSelection? =
        ffi { s.selectText(page.toUInt(), from.toFfi(), to.toFfi())?.toApi() }

    override fun outline(offset: Int, limit: Int): OutlinePage = ffi { s.outline(offset.toUInt(), limit.toUInt()).toApi() }
    override fun links(page: Int): List<PageLink> = ffi { s.links(page.toUInt()).map { it.toApi() } }

    override fun state(): EditState = ffi { s.state().toApi() }
    override fun rotate(pages: List<Int>, by: Rotation): EditState = ffi { s.rotatePages(pages.uints(), by.toFfi()).toApi() }
    override fun delete(pages: List<Int>): EditState = ffi { s.deletePages(pages.uints()).toApi() }
    override fun move(pages: List<Int>, toIndex: Int): EditState = ffi { s.movePages(pages.uints(), toIndex.toUInt()).toApi() }
    override fun duplicate(pages: List<Int>): EditState = ffi { s.duplicatePages(pages.uints()).toApi() }

    override fun insertBlank(at: Int, widthPt: Float, heightPt: Float): EditState =
        ffi { s.insertBlank(at.toUInt(), widthPt, heightPt).toApi() }

    override fun undo(): EditState = ffi { s.undo().toApi() }
    override fun redo(): EditState = ffi { s.redo().toApi() }

    override fun addMarkup(markup: NewMarkup): EditState = ffi { s.addMarkup(markup.toFfi()).toApi() }
    override fun listFields(): List<FieldInfo> = ffi { s.listFields().map { it.toApi() } }
    override fun setField(name: String, value: FieldValue): EditState = ffi { s.setField(name, value.toFfi()).toApi() }
    override fun resetForm(names: List<String>?): EditState = ffi { s.resetForm(names).toApi() }

    override fun saveIncremental(): ByteArray = ffi { s.saveIncremental() }
    override fun saveFull(): ByteArray = ffi { s.saveFull() }
    override fun confirmSaved(): EditState = ffi { s.confirmSaved().toApi() }
    override fun saveProtected(spec: ProtectionSpec): ByteArray = ffi { s.saveProtected(spec.toFfi()) }
    override fun removeProtection(): EditState = ffi { s.removeProtection().toApi() }

    override fun extract(pages: List<Int>): ByteArray = ffi { s.extractPages(pages.uints()) }
    override fun split(spec: SplitSpec): List<SplitPart> = ffi { s.split(spec.toFfi()).map { it.toApi() } }

    override fun close() = s.destroy()
}
