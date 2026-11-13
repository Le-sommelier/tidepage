package app.pdfa.engine.service

import app.pdfa.engine.DocMeta
import app.pdfa.engine.EditState
import app.pdfa.engine.FieldInfo
import app.pdfa.engine.FieldKind
import app.pdfa.engine.FieldValue
import app.pdfa.engine.ImageFrame
import app.pdfa.engine.ImageInput
import app.pdfa.engine.LinkTarget
import app.pdfa.engine.MergeSource
import app.pdfa.engine.NewMarkup
import app.pdfa.engine.OutlinePage
import app.pdfa.engine.OutlineRow
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
import java.util.concurrent.atomic.AtomicInteger

/** Scriptable backend: records every call as text, returns fixed values, throws [fail] when set. */
class FakeBackend : NativeEngineBackend {
    val calls = ArrayList<String>()
    val sessions = ArrayList<FakeSession>()
    @Volatile var fail: Throwable? = null
    @Volatile var encryption = false

    /** Overrides what renderTile returns (default: a correct tile filled with 7). */
    @Volatile var tile: ((TileRequest) -> RawTile)? = null

    /** Runs inside every session call (to observe overlap). */
    @Volatile var inside: (() -> Unit)? = null

    @Synchronized
    fun log(call: String) {
        calls += call
        fail?.let { throw it }
    }

    override fun open(bytes: ByteArray, password: String?): NativeSession {
        log("open(${bytes.size},$password)")
        return FakeSession(this, sessions.size + 1).also { sessions += it }
    }

    override fun merge(sources: List<MergeSource>): ByteArray {
        log("merge(${sources.map { it.name }})")
        return byteArrayOf(1, 2)
    }

    override fun imagesToPdf(images: List<ImageInput>, dpi: Double?): ByteArray {
        log("imagesToPdf(${images.map { it.name }},$dpi)")
        return byteArrayOf(3)
    }

    override fun detectDocumentCorners(frame: ImageFrame): Quad? {
        log("detect(${frame.width}x${frame.height})")
        return QUAD
    }

    override fun rectifyAndClean(frame: ImageFrame, quad: Quad, look: ScanLook, level: Int): ImageFrame {
        log("rectify(${frame.width}x${frame.height},$look,$level)")
        return ImageFrame(1, 1, byteArrayOf(9, 9, 9, 9))
    }

    override fun encryptionAvailable(): Boolean = encryption

    companion object {
        val STATE = EditState(canUndo = true, canRedo = false, undoLabel = "Rotate", pageCount = 3, dirty = true)
        val META = DocMeta("t", "a", "s", "k", "p", "1.7", 3, 1234L, false, true, true, true, true)
        val QUAD = Quad(Pt(0f, 0f), Pt(1f, 0f), Pt(1f, 1f), Pt(0f, 1f))
        val RECT = Rect(1f, 2f, 3f, 4f)
    }
}

class FakeSession(private val owner: FakeBackend, val number: Int) : NativeSession {
    val closes = AtomicInteger()
    val active = AtomicInteger()
    val maxActive = AtomicInteger()

    private fun <T> call(text: String, value: T): T {
        val now = active.incrementAndGet()
        maxActive.accumulateAndGet(now) { a, b -> maxOf(a, b) }
        try {
            owner.inside?.invoke()
            owner.log("#$number.$text")
            return value
        } finally {
            active.decrementAndGet()
        }
    }

    override fun meta() = call("meta", FakeBackend.META)
    override fun pageInfos() = call("pageInfos", listOf(PageInfo(595f, 842f, "i")))

    override fun renderTile(request: TileRequest): RawTile =
        call("renderTile(${request.page},${request.width}x${request.height})", Unit).let {
            owner.tile?.invoke(request)
                ?: RawTile(request.width, request.height, ByteArray(request.width * request.height * 4) { 7 })
        }

    override fun searchPage(page: Int, query: String, caseSensitive: Boolean, wholeWord: Boolean) =
        call("searchPage($page,$query,$caseSensitive,$wholeWord)", listOf(FakeBackend.RECT))

    override fun selectText(page: Int, from: Pt, to: Pt): TextSelection? =
        call("selectText($page,${from.x},${to.x})", TextSelection("word", listOf(FakeBackend.RECT)))

    override fun outline(offset: Int, limit: Int) =
        call("outline($offset,$limit)", OutlinePage(listOf(OutlineRow(listOf(0), "Chapter", 1, true)), null))

    override fun links(page: Int) = call("links($page)", listOf(PageLink(FakeBackend.RECT, LinkTarget.Page(2))))

    override fun state() = call("state", FakeBackend.STATE)
    override fun rotate(pages: List<Int>, by: Rotation) = call("rotate($pages,$by)", FakeBackend.STATE)
    override fun delete(pages: List<Int>) = call("delete($pages)", FakeBackend.STATE)
    override fun move(pages: List<Int>, toIndex: Int) = call("move($pages,$toIndex)", FakeBackend.STATE)
    override fun duplicate(pages: List<Int>) = call("duplicate($pages)", FakeBackend.STATE)
    override fun insertBlank(at: Int, widthPt: Float, heightPt: Float) = call("insertBlank($at,$widthPt,$heightPt)", FakeBackend.STATE)
    override fun undo() = call("undo", FakeBackend.STATE)
    override fun redo() = call("redo", FakeBackend.STATE)

    override fun addMarkup(markup: NewMarkup) = call("addMarkup(${markup.page},${markup.kind})", FakeBackend.STATE)

    override fun listFields() = call(
        "listFields",
        listOf(FieldInfo("f", FieldKind.TEXT, listOf("v"), emptyList(), false, false, false, null, emptyList())),
    )

    override fun setField(name: String, value: FieldValue) = call("setField($name,$value)", FakeBackend.STATE)
    override fun resetForm(names: List<String>?) = call("resetForm($names)", FakeBackend.STATE)

    override fun saveIncremental() = call("saveIncremental", byteArrayOf(10))
    override fun saveFull() = call("saveFull", byteArrayOf(11))
    override fun confirmSaved() = call("confirmSaved", FakeBackend.STATE.copy(dirty = false))
    override fun saveProtected(spec: ProtectionSpec) = call("saveProtected(${spec.printing})", byteArrayOf(12))
    override fun removeProtection() = call("removeProtection", FakeBackend.STATE)

    override fun extract(pages: List<Int>) = call("extract($pages)", byteArrayOf(13))
    override fun split(spec: SplitSpec) = call("split($spec)", listOf(SplitPart("part-1.pdf", byteArrayOf(14))))

    override fun close() {
        closes.incrementAndGet()
    }
}
