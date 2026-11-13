package app.pdfa.engine.fake

import app.pdfa.engine.ChangePerm
import app.pdfa.engine.DocHandle
import app.pdfa.engine.DocMeta
import app.pdfa.engine.EditState
import app.pdfa.engine.EngineApi
import app.pdfa.engine.EngineError
import app.pdfa.engine.EngineEvent
import app.pdfa.engine.FeatureFlags
import app.pdfa.engine.FieldInfo
import app.pdfa.engine.FieldKind
import app.pdfa.engine.FieldValue
import app.pdfa.engine.FieldWidget
import app.pdfa.engine.ImageFrame
import app.pdfa.engine.ImageInput
import app.pdfa.engine.LinkTarget
import app.pdfa.engine.MarkupKind
import app.pdfa.engine.MergeSource
import app.pdfa.engine.NewMarkup
import app.pdfa.engine.OutlinePage
import app.pdfa.engine.OutlineRow
import app.pdfa.engine.PageInfo
import app.pdfa.engine.PageLink
import app.pdfa.engine.PrintPerm
import app.pdfa.engine.Pt
import app.pdfa.engine.ProtectionSpec
import app.pdfa.engine.Quad
import app.pdfa.engine.Rect
import app.pdfa.engine.Rotation
import app.pdfa.engine.ScanLook
import app.pdfa.engine.SplitPart
import app.pdfa.engine.SplitSpec
import app.pdfa.engine.TextSelection
import app.pdfa.engine.TileBitmap
import app.pdfa.engine.TileRequest
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.nio.ByteBuffer
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

/** Text layout used by search and selection: every line is [LINE_HEIGHT] tall, every character [CHAR_WIDTH] wide. */
private const val CHAR_WIDTH = 8f
private const val LINE_HEIGHT = 14f

/** Every caller error in the fake is an [EngineError.InvalidArgument]. */
private fun invalid(detail: String): Nothing = throw EngineError.InvalidArgument(detail)

/**
 * A deterministic, in-memory [EngineApi] with no Rust. Documents are the fake format from
 * [FakeFormat]; see [fakePdf] to build one. Every public call is serialised by one [Mutex], so
 * concurrent callers see consistent state. Failures can be scripted with [hangFor], [crashAfter]
 * and [failNext].
 *
 * @param limits size guard rails; lower them in tests to exercise [EngineError.TooLarge].
 * @param flags feature switches; `encrypt` is off by default, as in production.
 * @param clock source of [EngineEvent.SafeModeRestarted.atEpochMs]; inject a fixed one in tests.
 */
class FakeEngine(
    private val limits: Limits = Limits(),
    private val flags: FeatureFlags = FeatureFlags(),
    private val clock: () -> Long = { System.currentTimeMillis() },
) : EngineApi {

    /** Guard rails. The defaults are the contract's: 256 MiB in, 64 Mpx per render, 512 MiB out. */
    data class Limits(
        val maxInputBytes: Long = 256L * 1024 * 1024,
        /** 64 * 1024 * 1024 pixels, so "64 megapixels" means 2^26 here. */
        val maxRenderPixels: Long = 64L * 1024 * 1024,
        val maxOutputBytes: Long = 512L * 1024 * 1024,
    )

    // ---- state, guarded by mutex ----

    private val mutex = Mutex()
    private val sessions = HashMap<Long, DocSession>()
    private var nextHandle = 1L
    private var lastRevision = 0L
    private var opCounter = 0
    private var crashCountdown: Int? = null
    private val hangs = ArrayList<Hang>()
    private val failures = ArrayList<Failure>()

    private val eventBus = MutableSharedFlow<EngineEvent>(extraBufferCapacity = 64)

    override val events: SharedFlow<EngineEvent> = eventBus.asSharedFlow()

    private class Snapshot(val label: String, val doc: FakeDocument, val revision: Long)

    /**
     * One open document. [revision] identifies the current state; [savedRevision] the state last
     * confirmed saved. Revisions come from a counter that never repeats, so dirty is a plain
     * comparison even after undo and redo.
     */
    private class DocSession(var doc: FakeDocument, var revision: Long) {
        val undo = ArrayList<Snapshot>()
        val redo = ArrayList<Snapshot>()
        var savedRevision: Long = revision
    }

    private class Hang(val callName: String?, val ms: Long)
    private class Failure(val callName: String?, val error: EngineError)

    // ---- scripting ----

    /** The next call named [callName] (any call when null) suspends for [ms] before it runs. One-shot. */
    suspend fun hangFor(callName: String? = null, ms: Long) = mutex.withLock {
        hangs += Hang(callName, ms)
    }

    /**
     * The first [n] calls succeed; call number n + 1 throws [EngineError.EngineDied], invalidates
     * every handle and emits [EngineEvent.SafeModeRestarted]. One-shot.
     */
    suspend fun crashAfter(n: Int) = mutex.withLock {
        require(n >= 0) { "crashAfter needs n >= 0" }
        crashCountdown = n
    }

    /** The next call named [callName] (any call when null) throws [error]. One-shot. */
    suspend fun failNext(error: EngineError, callName: String? = null) = mutex.withLock {
        failures += Failure(callName, error)
    }

    // ---- lifecycle ----

    override suspend fun open(bytes: ByteArray, password: String?): DocHandle = call("open") {
        val doc = decodeChecked(bytes, password)
        val id = nextHandle++
        sessions[id] = DocSession(doc, newRevision())
        DocHandle(id)
    }

    override suspend fun close(handle: DocHandle) = call("close") {
        if (sessions.remove(handle.id) == null) invalid("handle ${handle.id} is closed or invalid")
        Unit
    }

    // ---- read: viewer ----

    override suspend fun meta(handle: DocHandle): DocMeta = call("meta") {
        val s = session(handle)
        val i = s.doc.info
        DocMeta(
            title = i.title,
            author = i.author,
            subject = i.subject,
            keywords = i.keywords,
            producer = i.producer,
            pdfVersion = i.pdfVersion,
            pageCount = s.doc.pages.size,
            fileSize = FakeFormat.encode(s.doc).size.toLong(),
            encrypted = s.doc.openPassword != null,
            canPrint = i.canPrint,
            canCopy = i.canCopy,
            canModify = i.canModify,
            canAnnotate = i.canAnnotate,
        )
    }

    override suspend fun pageInfos(handle: DocHandle): List<PageInfo> = call("pageInfos") {
        session(handle).doc.pages.map { p ->
            val (w, h) = viewSize(p)
            PageInfo(w, h, p.label)
        }
    }

    override suspend fun renderTile(handle: DocHandle, request: TileRequest): TileBitmap = call("renderTile") {
        val page = pageAt(session(handle).doc, request.page)
        if (!(request.scale > 0f) || request.scale.isInfinite()) invalid("scale must be a positive number")
        if (request.width <= 0 || request.height <= 0) invalid("tile must have a positive size")
        if (request.x < 0 || request.y < 0) invalid("tile origin must be non-negative")
        val (vw, vh) = viewSize(page)
        val pxW = ceil(vw * request.scale).toLong()
        val pxH = ceil(vh * request.scale).toLong()
        if (request.x.toLong() + request.width > pxW || request.y.toLong() + request.height > pxH) {
            invalid("tile ${request.x},${request.y} ${request.width}x${request.height} lies outside page ${request.page}")
        }
        val pixels = request.width.toLong() * request.height
        if (pixels > limits.maxRenderPixels) {
            throw EngineError.TooLarge("render of $pixels px exceeds ${limits.maxRenderPixels}")
        }
        renderBitmap(page, request.width, request.height, request.preview)
    }

    override suspend fun searchPage(
        handle: DocHandle,
        page: Int,
        query: String,
        caseSensitive: Boolean,
        wholeWord: Boolean,
    ): List<Rect> = call("searchPage") {
        val p = pageAt(session(handle).doc, page)
        if (query.isEmpty()) invalid("search query is empty")
        val needle = if (caseSensitive) query else query.lowercase()
        val rects = ArrayList<Rect>()
        p.text.split('\n').forEachIndexed { line, text ->
            val hay = if (caseSensitive) text else text.lowercase()
            var from = 0
            while (true) {
                val idx = hay.indexOf(needle, from)
                if (idx < 0) break
                val end = idx + needle.length
                if (!wholeWord || (!isWordChar(text, idx - 1) && !isWordChar(text, end))) {
                    rects += lineRect(line, idx, end)
                }
                from = end
            }
        }
        rects
    }

    override suspend fun selectText(handle: DocHandle, page: Int, from: Pt, to: Pt): TextSelection? = call("selectText") {
        val lines = pageAt(session(handle).doc, page).text.split('\n')
        if (from == to) selectWordAt(lines, from) else selectRegion(lines, from, to)
    }

    override suspend fun outline(handle: DocHandle, offset: Int, limit: Int): OutlinePage = call("outline") {
        val doc = session(handle).doc
        if (offset < 0 || limit <= 0) invalid("outline needs offset >= 0 and limit > 0")
        val slice = doc.outline.drop(offset).take(limit)
        val rows = slice.map { o ->
            OutlineRow(o.path, o.title, o.page?.let { pageIndexOf(doc, it) }, o.open)
        }
        val end = offset + slice.size
        OutlinePage(rows, if (end < doc.outline.size) end else null)
    }

    override suspend fun links(handle: DocHandle, page: Int): List<PageLink> = call("links") {
        val doc = session(handle).doc
        pageAt(doc, page).links.map { l ->
            val target = when (val t = l.target) {
                is LinkTarget.Page -> pageIndexOf(doc, t.page)?.let { LinkTarget.Page(it) }
                    ?: LinkTarget.Other("target page was removed")
                else -> t
            }
            PageLink(l.rect, target)
        }
    }

    // ---- edit: organize ----

    override suspend fun state(handle: DocHandle): EditState = call("state") {
        stateOf(session(handle))
    }

    override suspend fun rotate(handle: DocHandle, pages: List<Int>, by: Rotation): EditState = call("rotate") {
        val s = session(handle)
        val picked = validPages(s.doc, pages).toSet()
        val delta = when (by) {
            Rotation.CW90 -> 90
            Rotation.HALF -> 180
            Rotation.CCW90 -> 270
        }
        edit(s, "Rotate pages") { doc ->
            doc.copy(pages = doc.pages.mapIndexed { i, p ->
                if (i in picked) p.copy(rotation = (p.rotation + delta) % 360) else p
            })
        }
    }

    override suspend fun delete(handle: DocHandle, pages: List<Int>): EditState = call("delete") {
        val s = session(handle)
        val picked = validPages(s.doc, pages).toSet()
        if (picked.size == s.doc.pages.size) invalid("cannot delete every page of a document")
        edit(s, "Delete pages") { doc -> doc.copy(pages = doc.pages.filterIndexed { i, _ -> i !in picked }) }
    }

    override suspend fun move(handle: DocHandle, pages: List<Int>, toIndex: Int): EditState = call("move") {
        val s = session(handle)
        val doc = s.doc
        validPages(doc, pages)
        val max = doc.pages.size - pages.size
        if (toIndex !in 0..max) invalid("toIndex $toIndex is outside 0..$max")
        val picked = pages.toSet()
        val block = pages.sorted().map { doc.pages[it] }
        val rest = doc.pages.filterIndexed { i, _ -> i !in picked }
        edit(s, "Move pages") { d -> d.copy(pages = rest.take(toIndex) + block + rest.drop(toIndex)) }
    }

    override suspend fun duplicate(handle: DocHandle, pages: List<Int>): EditState = call("duplicate") {
        val s = session(handle)
        val picked = validPages(s.doc, pages).toSet()
        edit(s, "Duplicate pages") { doc ->
            var next = doc.nextId
            val out = ArrayList<FakePage>()
            doc.pages.forEachIndexed { i, p ->
                out += p
                if (i in picked) {
                    out += p.copy(id = next)
                    next++
                }
            }
            doc.copy(pages = out, nextId = next)
        }
    }

    override suspend fun insertBlank(handle: DocHandle, at: Int, widthPt: Float, heightPt: Float): EditState =
        call("insertBlank") {
            val s = session(handle)
            if (at !in 0..s.doc.pages.size) invalid("insert position $at is outside 0..${s.doc.pages.size}")
            if (!(widthPt > 0f) || !(heightPt > 0f)) invalid("blank page size must be positive")
            edit(s, "Insert blank page") { doc ->
                val blank = FakePage(width = widthPt, height = heightPt, id = doc.nextId)
                doc.copy(
                    pages = doc.pages.subList(0, at) + blank + doc.pages.subList(at, doc.pages.size),
                    nextId = doc.nextId + 1,
                )
            }
        }

    override suspend fun undo(handle: DocHandle): EditState = call("undo") {
        val s = session(handle)
        if (s.undo.isNotEmpty()) {
            val snap = s.undo.removeAt(s.undo.lastIndex)
            s.redo += Snapshot(snap.label, s.doc, s.revision)
            s.doc = snap.doc
            s.revision = snap.revision
        }
        stateOf(s)
    }

    override suspend fun redo(handle: DocHandle): EditState = call("redo") {
        val s = session(handle)
        if (s.redo.isNotEmpty()) {
            val snap = s.redo.removeAt(s.redo.lastIndex)
            s.undo += Snapshot(snap.label, s.doc, s.revision)
            s.doc = snap.doc
            s.revision = snap.revision
        }
        stateOf(s)
    }

    // ---- edit: annotate and forms ----

    override suspend fun addMarkup(handle: DocHandle, markup: NewMarkup): EditState = call("addMarkup") {
        val s = session(handle)
        pageAt(s.doc, markup.page)
        validateMarkup(markup)
        edit(s, "Add markup") { doc ->
            doc.copy(pages = doc.pages.mapIndexed { i, p ->
                if (i == markup.page) p.copy(markups = p.markups + markup.copy(page = p.id)) else p
            })
        }
    }

    override suspend fun listFields(handle: DocHandle): List<FieldInfo> = call("listFields") {
        val doc = session(handle).doc
        doc.fields.map { f ->
            FieldInfo(
                name = f.name,
                kind = f.kind,
                value = f.value,
                options = f.options,
                readOnly = f.readOnly,
                required = f.required,
                multiline = f.multiline,
                maxLen = f.maxLen,
                widgets = f.widgets.mapNotNull { w ->
                    pageIndexOf(doc, w.page)?.let { FieldWidget(it, w.rect, w.onState) }
                },
            )
        }
    }

    override suspend fun setField(handle: DocHandle, name: String, value: FieldValue): EditState = call("setField") {
        val s = session(handle)
        val field = s.doc.fields.firstOrNull { it.name == name } ?: invalid("no field named '$name'")
        val newValue = valueFor(field, value)
        edit(s, "Set field") { doc ->
            doc.copy(fields = doc.fields.map { if (it.name == name) it.copy(value = newValue) else it })
        }
    }

    override suspend fun resetForm(handle: DocHandle, names: List<String>?): EditState = call("resetForm") {
        val s = session(handle)
        if (names != null) {
            for (n in names) if (s.doc.fields.none { it.name == n }) invalid("no field named '$n'")
        }
        val targets = names?.toSet()
        edit(s, "Reset form") { doc ->
            doc.copy(fields = doc.fields.map { f ->
                if (targets == null || f.name in targets) f.copy(value = f.defaultValue) else f
            })
        }
    }

    // ---- save ----

    override suspend fun saveIncremental(handle: DocHandle): ByteArray = call("saveIncremental") {
        output(session(handle).doc)
    }

    override suspend fun saveFull(handle: DocHandle): ByteArray = call("saveFull") {
        output(session(handle).doc)
    }

    override suspend fun confirmSaved(handle: DocHandle): EditState = call("confirmSaved") {
        val s = session(handle)
        s.savedRevision = s.revision
        stateOf(s)
    }

    override suspend fun saveProtected(handle: DocHandle, spec: ProtectionSpec): ByteArray = call("saveProtected") {
        requireEncryption("saveProtected")
        val s = session(handle)
        val info = s.doc.info.copy(
            canPrint = spec.printing != PrintPerm.NONE,
            canCopy = spec.allowCopy,
            canModify = spec.changes != ChangePerm.NONE,
            canAnnotate = spec.changes == ChangePerm.COMMENT_FILL_SIGN || spec.changes == ChangePerm.ANY_EXCEPT_EXTRACT,
        )
        output(s.doc.copy(info = info, openPassword = spec.openPassword))
    }

    override suspend fun removeProtection(handle: DocHandle): EditState = call("removeProtection") {
        requireEncryption("removeProtection")
        val s = session(handle)
        edit(s, "Remove protection") { it.copy(openPassword = null) }
    }

    // ---- files ----

    override suspend fun extract(handle: DocHandle, pages: List<Int>): ByteArray = call("extract") {
        val doc = session(handle).doc
        validPages(doc, pages)
        output(subset(doc, pages))
    }

    override suspend fun split(handle: DocHandle, spec: SplitSpec): List<SplitPart> = call("split") {
        val doc = session(handle).doc
        val count = doc.pages.size
        val groups: List<List<Int>> = when (spec) {
            is SplitSpec.EveryN -> {
                if (spec.n < 1) invalid("EveryN needs n >= 1")
                (0 until count).toList().chunked(spec.n)
            }
            is SplitSpec.Before -> {
                if (spec.pages.any { it < 0 || it >= count }) invalid("split point outside 0..${count - 1}")
                if (spec.pages.zipWithNext().any { (a, b) -> b <= a }) invalid("split points must be strictly ascending")
                // Page 0 is implicit, so a split point at 0 starts no new part.
                val starts = listOf(0) + spec.pages.filter { it > 0 }
                starts.mapIndexed { i, start ->
                    val end = starts.getOrElse(i + 1) { count }
                    (start until end).toList()
                }
            }
        }
        val opId = "split-${++opCounter}"
        groups.mapIndexed { i, group ->
            val bytes = output(subset(doc, group))
            eventBus.emit(EngineEvent.Progress(opId, (i + 1).toFloat() / groups.size))
            SplitPart("part-${i + 1}.pdf", bytes)
        }
    }

    override suspend fun merge(sources: List<MergeSource>): ByteArray = call("merge") {
        if (sources.isEmpty()) invalid("merge needs at least one source")
        // Decode every source first, so a bad password fails before any progress is reported.
        val docs = sources.map { decodeChecked(it.bytes, it.password) }
        val opId = "merge-${++opCounter}"
        val out = ArrayList<FakePage>()
        var nextId = 1
        for ((i, src) in sources.withIndex()) {
            val doc = docs[i]
            val indices = ArrayList<Int>()
            if (src.ranges.isEmpty()) {
                indices += doc.pages.indices
            } else {
                for (r in src.ranges) {
                    if (r.start < 0 || r.endInclusive >= doc.pages.size || r.start > r.endInclusive) {
                        invalid("range ${r.start}..${r.endInclusive} is outside source '${src.name}'")
                    }
                    indices += r.start..r.endInclusive
                }
            }
            // Renumber this source's pages, then point its links at the renumbered pages.
            val renumber = HashMap<Int, Int>()
            val added = indices.map { idx ->
                val p = doc.pages[idx]
                val newId = nextId++
                renumber[p.id] = newId
                p.copy(id = newId)
            }
            for (p in added) {
                out += p.copy(links = p.links.map { l ->
                    val t = l.target
                    if (t is LinkTarget.Page) {
                        val mapped = renumber[t.page]
                        if (mapped != null) l.copy(target = LinkTarget.Page(mapped))
                        else l.copy(target = LinkTarget.Other("target page was not merged"))
                    } else {
                        l
                    }
                })
            }
            eventBus.emit(EngineEvent.Progress(opId, (i + 1).toFloat() / sources.size))
        }
        // Outline and form fields are not carried into a merge; the first source's info is kept.
        output(FakeDocument(docs.first().info, null, out, emptyList(), emptyList(), nextId))
    }

    override suspend fun imagesToPdf(images: List<ImageInput>, dpi: Double?): ByteArray = call("imagesToPdf") {
        if (images.isEmpty()) invalid("no images given")
        if (dpi != null && !(dpi >= 1.0 && dpi <= 1200.0)) invalid("dpi must be within 1..1200")
        val resolution = dpi ?: DEFAULT_DPI
        val pages = images.mapIndexed { i, img ->
            if (img.bytes.size > limits.maxInputBytes) {
                throw EngineError.TooLarge("image '${img.name}' exceeds ${limits.maxInputBytes} bytes")
            }
            val (w, h) = FakeImages.dimensions(img.bytes)
                ?: invalid("'${img.name}' is not a supported image")
            FakePage(
                width = (w * 72.0 / resolution).toFloat(),
                height = (h * 72.0 / resolution).toFloat(),
                label = img.name,
                id = i + 1,
            )
        }
        output(FakeDocument(FakeInfo(), null, pages, emptyList(), emptyList(), pages.size + 1))
    }

    // ---- scan (stateless) ----

    override suspend fun detectDocumentCorners(frame: ImageFrame): Quad? = call("detectDocumentCorners") {
        checkFrame(frame)
        if (isUniform(frame.rgba)) {
            null
        } else {
            val w = frame.width.toFloat()
            val h = frame.height.toFloat()
            val inset = min(w, h) / 10f
            Quad(
                topLeft = Pt(inset, inset),
                topRight = Pt(w - inset, inset),
                bottomRight = Pt(w - inset, h - inset),
                bottomLeft = Pt(inset, h - inset),
            )
        }
    }

    override suspend fun rectifyAndClean(frame: ImageFrame, quad: Quad, look: ScanLook, level: Int): ImageFrame =
        call("rectifyAndClean") {
            checkFrame(frame)
            if (level !in 0..3) invalid("level must be 0..3")
            val corners = listOf(quad.topLeft, quad.topRight, quad.bottomRight, quad.bottomLeft)
            if (corners.any { !(it.x >= 0f && it.x <= frame.width && it.y >= 0f && it.y <= frame.height) }) {
                invalid("quad corner lies outside the frame")
            }
            val minX = corners.minOf { it.x }
            val maxX = corners.maxOf { it.x }
            val minY = corners.minOf { it.y }
            val maxY = corners.maxOf { it.y }
            val outW = max(1, ceil(maxX - minX).toInt())
            val outH = max(1, ceil(maxY - minY).toInt())
            if (outW.toLong() * outH * 4 > limits.maxOutputBytes) throw EngineError.TooLarge("output exceeds limit")
            val out = ByteArray(outW * outH * 4)
            val x0 = floor(minX).toInt()
            val y0 = floor(minY).toInt()
            for (y in 0 until outH) {
                for (x in 0 until outW) {
                    val sx = (x0 + x).coerceIn(0, frame.width - 1)
                    val sy = (y0 + y).coerceIn(0, frame.height - 1)
                    val src = (sy * frame.width + sx) * 4
                    var r = frame.rgba[src].toInt() and 0xFF
                    var g = frame.rgba[src + 1].toInt() and 0xFF
                    var b = frame.rgba[src + 2].toInt() and 0xFF
                    when (look) {
                        ScanLook.ORIGINAL, ScanLook.COLOUR -> Unit
                        ScanLook.GREYSCALE -> {
                            val l = luma(r, g, b)
                            r = l; g = l; b = l
                        }
                        ScanLook.BLACK_WHITE -> {
                            val v = if (luma(r, g, b) >= 128) 255 else 0
                            r = v; g = v; b = v
                        }
                    }
                    val dst = (y * outW + x) * 4
                    out[dst] = r.toByte()
                    out[dst + 1] = g.toByte()
                    out[dst + 2] = b.toByte()
                    out[dst + 3] = 0xFF.toByte()
                }
            }
            ImageFrame(outW, outH, out)
        }

    // ---- helpers: calls and scripting ----

    /** Runs one public call under the mutex, after the scripted hang, crash and failure checks. */
    private suspend fun <T> call(name: String, block: suspend () -> T): T = mutex.withLock {
        scripted(name)
        block()
    }

    private suspend fun scripted(name: String) {
        hangs.firstOrNull { it.callName == null || it.callName == name }?.let {
            hangs.remove(it)
            delay(it.ms)
        }
        crashCountdown?.let { left ->
            if (left == 0) {
                crashCountdown = null
                die("fake crash during $name")
            }
            crashCountdown = left - 1
        }
        val failure = failures.firstOrNull { it.callName == null || it.callName == name }
        if (failure != null) {
            failures.remove(failure)
            if (failure.error is EngineError.EngineDied || failure.error is EngineError.Timeout) {
                die(failure.error.detail)
            }
            throw failure.error
        }
    }

    /** Invalidates every handle, emits the restart event, and throws [EngineError.EngineDied]. */
    private suspend fun die(reason: String): Nothing {
        sessions.clear()
        eventBus.emit(EngineEvent.SafeModeRestarted(clock(), reason))
        throw EngineError.EngineDied(reason)
    }

    private fun session(handle: DocHandle): DocSession =
        sessions[handle.id] ?: invalid("handle ${handle.id} is closed or was invalidated")

    private fun newRevision(): Long {
        lastRevision += 1
        return lastRevision
    }

    private fun stateOf(s: DocSession) = EditState(
        canUndo = s.undo.isNotEmpty(),
        canRedo = s.redo.isNotEmpty(),
        undoLabel = s.undo.lastOrNull()?.label,
        pageCount = s.doc.pages.size,
        dirty = s.revision != s.savedRevision,
    )

    /** Applies one undoable change. A change that leaves the document equal is not recorded. */
    private fun edit(s: DocSession, label: String, change: (FakeDocument) -> FakeDocument): EditState {
        val next = change(s.doc)
        if (next == s.doc) return stateOf(s)
        s.undo += Snapshot(label, s.doc, s.revision)
        s.redo.clear()
        s.doc = next
        s.revision = newRevision()
        return stateOf(s)
    }

    private fun output(doc: FakeDocument): ByteArray {
        val bytes = FakeFormat.encode(doc)
        if (bytes.size.toLong() > limits.maxOutputBytes) {
            throw EngineError.TooLarge("output of ${bytes.size} bytes exceeds ${limits.maxOutputBytes}")
        }
        return bytes
    }

    /** Parses and unlocks seed bytes without taking the mutex, so [merge] can call it inside [call]. */
    private fun decodeChecked(bytes: ByteArray, password: String?): FakeDocument {
        if (bytes.size.toLong() > limits.maxInputBytes) {
            throw EngineError.TooLarge("input of ${bytes.size} bytes exceeds ${limits.maxInputBytes}")
        }
        val doc = FakeFormat.decode(bytes)
        val required = doc.openPassword
        if (required != null) {
            if (password == null) throw EngineError.PasswordRequired
            if (password != required) throw EngineError.WrongPassword
        }
        return doc
    }

    private fun requireEncryption(op: String) {
        if (!flags.encrypt) throw EngineError.Unsupported("$op needs the encrypt feature flag")
    }

    private fun checkFrame(frame: ImageFrame) {
        if (frame.width <= 0 || frame.height <= 0) invalid("frame must have a positive size")
        val expected = frame.width.toLong() * frame.height * 4
        if (frame.rgba.size.toLong() > limits.maxInputBytes) throw EngineError.TooLarge("frame exceeds input limit")
        if (frame.rgba.size.toLong() != expected) {
            invalid("frame has ${frame.rgba.size} bytes, expected $expected for ${frame.width}x${frame.height} RGBA")
        }
    }

    private fun isUniform(rgba: ByteArray): Boolean {
        for (i in 4 until rgba.size) if (rgba[i] != rgba[i % 4]) return false
        return true
    }

    private fun luma(r: Int, g: Int, b: Int): Int = (r * 299 + g * 587 + b * 114) / 1000

    // ---- helpers: pages ----

    private fun pageAt(doc: FakeDocument, index: Int): FakePage {
        if (index !in doc.pages.indices) invalid("page $index is outside 0..${doc.pages.size - 1}")
        return doc.pages[index]
    }

    /** Checks a page-index list: non-empty, in range, no repeats. Returns it unchanged. */
    private fun validPages(doc: FakeDocument, pages: List<Int>): List<Int> {
        if (pages.isEmpty()) invalid("no pages given")
        for (p in pages) if (p !in doc.pages.indices) invalid("page $p is outside 0..${doc.pages.size - 1}")
        if (pages.toSet().size != pages.size) invalid("page list has duplicates")
        return pages
    }

    private fun pageIndexOf(doc: FakeDocument, id: Int): Int? =
        doc.pages.indexOfFirst { it.id == id }.takeIf { it >= 0 }

    private fun viewSize(p: FakePage): Pair<Float, Float> =
        if (p.rotation == 90 || p.rotation == 270) p.height to p.width else p.width to p.height

    /** Keeps the given pages, in the given order, with their ids. Outline and fields refer by id. */
    private fun subset(doc: FakeDocument, pages: List<Int>): FakeDocument =
        doc.copy(openPassword = null, pages = pages.map { doc.pages[it] })

    private fun renderBitmap(page: FakePage, w: Int, h: Int, preview: Boolean): TileBitmap {
        // Colour from the stable page id, so a moved or duplicated page keeps its colour.
        val hash = page.id * -1640531535
        val r = (hash ushr 16) and 0xFF
        val g = (hash ushr 8) and 0xFF
        val b = hash and 0xFF
        // Preview tiles are half-transparent, so a test can tell a preview from a full render.
        val alpha = if (preview) 128 else 255
        val pr = (r * alpha / 255).toByte()
        val pg = (g * alpha / 255).toByte()
        val pb = (b * alpha / 255).toByte()
        val pa = alpha.toByte()
        val buf = ByteBuffer.allocateDirect(w * h * 4)
        repeat(w * h) {
            buf.put(pr)
            buf.put(pg)
            buf.put(pb)
            buf.put(pa)
        }
        buf.flip()
        return TileBitmap(w, h, buf)
    }

    private fun validateMarkup(m: NewMarkup) {
        if (m.rgb !in 0..0xFFFFFF) invalid("rgb must be 0xRRGGBB")
        if (!(m.opacity >= 0f && m.opacity <= 1f)) invalid("opacity must be within 0..1")
        if (!(m.widthPt > 0f)) invalid("line width must be positive")
        when (m.kind) {
            MarkupKind.INK -> {
                if (m.strokes.isEmpty()) invalid("ink needs at least one stroke")
                if (m.strokes.any { it.points.isEmpty() }) invalid("every ink stroke needs a point")
            }
            MarkupKind.HIGHLIGHT, MarkupKind.NOTE -> {
                if (m.rects.isEmpty()) invalid("${m.kind} needs at least one rect")
                if (m.rects.any { !(it.left < it.right && it.top < it.bottom) }) invalid("rect must have left < right and top < bottom")
            }
        }
    }

    private fun valueFor(f: FakeField, v: FieldValue): List<String> {
        if (f.readOnly) invalid("field '${f.name}' is read-only")
        return when (f.kind) {
            FieldKind.TEXT -> {
                val t = (v as? FieldValue.Text)?.value ?: invalid("text field '${f.name}' needs FieldValue.Text")
                val max = f.maxLen
                if (max != null && t.length > max) invalid("value is longer than maxLen $max")
                if (t.isEmpty()) emptyList() else listOf(t)
            }
            FieldKind.CHECK_BOX -> {
                val checked = (v as? FieldValue.Check)?.checked
                    ?: invalid("check box '${f.name}' needs FieldValue.Check")
                if (checked) listOf(f.widgets.firstNotNullOfOrNull { it.onState } ?: "Yes") else emptyList()
            }
            FieldKind.RADIO -> {
                val on = (v as? FieldValue.Radio ?: invalid("radio '${f.name}' needs FieldValue.Radio")).onState
                if (on == null) {
                    emptyList()
                } else {
                    if (f.widgets.none { it.onState == on }) invalid("radio '${f.name}' has no state '$on'")
                    listOf(on)
                }
            }
            FieldKind.COMBO, FieldKind.LIST -> {
                val values = (v as? FieldValue.Choice ?: invalid("choice '${f.name}' needs FieldValue.Choice")).values
                if (f.kind == FieldKind.COMBO && values.size > 1) invalid("combo box '${f.name}' takes one value")
                if (f.options.isNotEmpty()) {
                    for (x in values) if (f.options.none { it.export == x }) invalid("'$x' is not an option of '${f.name}'")
                }
                values
            }
            FieldKind.PUSH_BUTTON, FieldKind.SIGNATURE -> invalid("${f.kind} field '${f.name}' cannot hold a value")
        }
    }

    private fun selectWordAt(lines: List<String>, p: Pt): TextSelection? {
        val row = floor(p.y / LINE_HEIGHT).toInt()
        val col = floor(p.x / CHAR_WIDTH).toInt()
        val line = lines.getOrNull(row) ?: return null
        if (col !in line.indices || !line[col].isLetterOrDigit()) return null
        var start = col
        while (start > 0 && line[start - 1].isLetterOrDigit()) start--
        var end = col + 1
        while (end < line.length && line[end].isLetterOrDigit()) end++
        return TextSelection(line.substring(start, end), listOf(lineRect(row, start, end)))
    }

    /** Characters whose cell centre falls inside the rectangle spanned by [a] and [b]. */
    private fun selectRegion(lines: List<String>, a: Pt, b: Pt): TextSelection? {
        val minX = min(a.x, b.x)
        val maxX = max(a.x, b.x)
        val minY = min(a.y, b.y)
        val maxY = max(a.y, b.y)
        val texts = ArrayList<String>()
        val rects = ArrayList<Rect>()
        lines.forEachIndexed { row, line ->
            val cy = row * LINE_HEIGHT + LINE_HEIGHT / 2
            if (cy in minY..maxY) {
                val cols = line.indices.filter { col -> col * CHAR_WIDTH + CHAR_WIDTH / 2 in minX..maxX }
                if (cols.isNotEmpty()) {
                    texts += cols.map { line[it] }.joinToString("")
                    rects += lineRect(row, cols.first(), cols.last() + 1)
                }
            }
        }
        return if (rects.isEmpty()) null else TextSelection(texts.joinToString("\n"), rects)
    }

    private fun lineRect(row: Int, startCol: Int, endCol: Int): Rect =
        Rect(startCol * CHAR_WIDTH, row * LINE_HEIGHT, endCol * CHAR_WIDTH, row * LINE_HEIGHT + LINE_HEIGHT)

    private fun isWordChar(text: String, i: Int): Boolean = i in text.indices && text[i].isLetterOrDigit()

    companion object {
        /** Used by [imagesToPdf] when no dpi is given: one image pixel is one point. */
        const val DEFAULT_DPI = 72.0

        /**
         * Builds fake bytes from pages. Page indices in [FakePage.links], [FakePage.markups],
         * [outline] and [fields] are 0-based indices into [pages]. The built file gets ids 1..n.
         */
        fun fakePdf(
            pages: List<FakePage>,
            password: String? = null,
            info: FakeInfo = FakeInfo(),
            outline: List<FakeOutline> = emptyList(),
            fields: List<FakeField> = emptyList(),
        ): ByteArray {
            require(pages.isNotEmpty()) { "a document needs at least one page" }
            val count = pages.size
            fun ref(i: Int): Int {
                require(i in 0 until count) { "page index $i is outside 0..${count - 1}" }
                return i + 1
            }
            val seeded = pages.mapIndexed { i, p ->
                p.copy(
                    id = i + 1,
                    markups = p.markups.map { it.copy(page = i + 1) },
                    links = p.links.map { l ->
                        val t = l.target
                        if (t is LinkTarget.Page) l.copy(target = LinkTarget.Page(ref(t.page))) else l
                    },
                )
            }
            val doc = FakeDocument(
                info = info,
                openPassword = password,
                pages = seeded,
                outline = outline.map { o -> o.copy(page = o.page?.let(::ref)) },
                fields = fields.map { f ->
                    f.copy(widgets = f.widgets.map { w -> w.copy(page = ref(w.page)) })
                },
                nextId = count + 1,
            )
            return FakeFormat.encode(doc)
        }

        /** A plain document of [pageCount] pages of [width] x [height] points, with optional per-page text. */
        fun fakePdf(
            pageCount: Int = 1,
            width: Float = 612f,
            height: Float = 792f,
            text: List<String> = emptyList(),
            password: String? = null,
            title: String = "",
        ): ByteArray = fakePdf(
            pages = List(pageCount) { i -> FakePage(width, height, text = text.getOrElse(i) { "" }) },
            password = password,
            info = FakeInfo(title = title),
        )

        /** Bytes of a PNG header of [width] x [height] pixels. Enough for [imagesToPdf] and [detectDocumentCorners]. */
        fun fakeImage(width: Int, height: Int): ByteArray = FakeImages.pngHeader(width, height)
    }

    /** Test hook: the document as the engine holds it, after every queued call finished. */
    internal suspend fun inspect(handle: DocHandle): FakeDocument = mutex.withLock { session(handle).doc }

    /** Test hook: the undo label stack, oldest first. */
    internal suspend fun undoLabels(handle: DocHandle): List<String> = mutex.withLock {
        session(handle).undo.map { it.label }
    }
}
