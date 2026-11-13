package app.pdfa.engine.protocol

import app.pdfa.engine.ChangePerm
import app.pdfa.engine.DocMeta
import app.pdfa.engine.EditState
import app.pdfa.engine.FieldInfo
import app.pdfa.engine.FieldKind
import app.pdfa.engine.FieldOption
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
import app.pdfa.engine.PageRange
import app.pdfa.engine.PrintPerm
import app.pdfa.engine.ProtectionSpec
import app.pdfa.engine.Pt
import app.pdfa.engine.Quad
import app.pdfa.engine.Rect
import app.pdfa.engine.Rotation
import app.pdfa.engine.ScanLook
import app.pdfa.engine.SearchHit
import app.pdfa.engine.SplitPart
import app.pdfa.engine.SplitSpec
import app.pdfa.engine.Stroke
import app.pdfa.engine.TextSelection
import app.pdfa.engine.TileRequest

/*
 * One write/read pair per model type of engine-api Models.kt. Naming: WireWriter.writeX(v) and
 * WireReader.readX(). Enums travel as a u8 ordinal (ordinals are frozen; new entries go last).
 * Every reader throws WireException on bad input and never allocates from an unchecked length.
 */

// ---- enums ----

private fun <E : Enum<E>> WireReader.enumOf(values: List<E>, what: String): E {
    val i = u8()
    return values.getOrNull(i) ?: throw WireException("unknown $what ordinal $i")
}

fun WireWriter.writeRotation(v: Rotation): WireWriter = u8(v.ordinal)
fun WireReader.readRotation(): Rotation = enumOf(Rotation.entries, "Rotation")
fun WireWriter.writeScanLook(v: ScanLook): WireWriter = u8(v.ordinal)
fun WireReader.readScanLook(): ScanLook = enumOf(ScanLook.entries, "ScanLook")
fun WireWriter.writeMarkupKind(v: MarkupKind): WireWriter = u8(v.ordinal)
fun WireReader.readMarkupKind(): MarkupKind = enumOf(MarkupKind.entries, "MarkupKind")
fun WireWriter.writeFieldKind(v: FieldKind): WireWriter = u8(v.ordinal)
fun WireReader.readFieldKind(): FieldKind = enumOf(FieldKind.entries, "FieldKind")
fun WireWriter.writePrintPerm(v: PrintPerm): WireWriter = u8(v.ordinal)
fun WireReader.readPrintPerm(): PrintPerm = enumOf(PrintPerm.entries, "PrintPerm")
fun WireWriter.writeChangePerm(v: ChangePerm): WireWriter = u8(v.ordinal)
fun WireReader.readChangePerm(): ChangePerm = enumOf(ChangePerm.entries, "ChangePerm")

// ---- geometry ----

fun WireWriter.writePt(v: Pt): WireWriter = f32(v.x).f32(v.y)
fun WireReader.readPt(): Pt = Pt(f32(), f32())

fun WireWriter.writeRect(v: Rect): WireWriter = f32(v.left).f32(v.top).f32(v.right).f32(v.bottom)
fun WireReader.readRect(): Rect = Rect(f32(), f32(), f32(), f32())

fun WireWriter.writeQuad(v: Quad): WireWriter {
    writePt(v.topLeft); writePt(v.topRight); writePt(v.bottomRight); writePt(v.bottomLeft)
    return this
}

fun WireReader.readQuad(): Quad = Quad(readPt(), readPt(), readPt(), readPt())

fun WireWriter.writePageRange(v: PageRange): WireWriter = i32(v.start).i32(v.endInclusive)
fun WireReader.readPageRange(): PageRange = PageRange(i32(), i32())

fun WireWriter.writeRects(v: List<Rect>): WireWriter = list(v) { writeRect(it) }
fun WireReader.readRects(): List<Rect> = list(16) { readRect() }

// ---- document ----

fun WireWriter.writePageInfo(v: PageInfo): WireWriter = f32(v.widthPt).f32(v.heightPt).string(v.label)
fun WireReader.readPageInfo(): PageInfo = PageInfo(f32(), f32(), string())

fun WireWriter.writeEditState(v: EditState): WireWriter {
    bool(v.canUndo).bool(v.canRedo).optional(v.undoLabel) { string(it) }.i32(v.pageCount).bool(v.dirty)
    return this
}

fun WireReader.readEditState(): EditState =
    EditState(bool(), bool(), optional { string() }, i32(), bool())

fun WireWriter.writeDocMeta(v: DocMeta): WireWriter {
    string(v.title).string(v.author).string(v.subject).string(v.keywords).string(v.producer)
        .string(v.pdfVersion).i32(v.pageCount).i64(v.fileSize).bool(v.encrypted).bool(v.canPrint)
        .bool(v.canCopy).bool(v.canModify).bool(v.canAnnotate)
    return this
}

fun WireReader.readDocMeta(): DocMeta = DocMeta(
    title = string(), author = string(), subject = string(), keywords = string(),
    producer = string(), pdfVersion = string(), pageCount = i32(), fileSize = i64(),
    encrypted = bool(), canPrint = bool(), canCopy = bool(), canModify = bool(), canAnnotate = bool(),
)

fun WireWriter.writeTileRequest(v: TileRequest): WireWriter =
    i32(v.page).f32(v.scale).i32(v.x).i32(v.y).i32(v.width).i32(v.height).bool(v.preview)

fun WireReader.readTileRequest(): TileRequest =
    TileRequest(i32(), f32(), i32(), i32(), i32(), i32(), bool())

fun WireWriter.writeTileInfo(v: TileInfo): WireWriter = i32(v.width).i32(v.height).i32(v.size).i64(v.transferId)
fun WireReader.readTileInfo(): TileInfo = TileInfo(i32(), i32(), i32(), i64())

// ---- viewer ----

fun WireWriter.writeSearchHit(v: SearchHit): WireWriter = i32(v.page).writeRects(v.rects)
fun WireReader.readSearchHit(): SearchHit = SearchHit(i32(), readRects())

fun WireWriter.writeTextSelection(v: TextSelection): WireWriter = string(v.text).writeRects(v.rects)
fun WireReader.readTextSelection(): TextSelection = TextSelection(string(), readRects())

fun WireWriter.writeOutlineRow(v: OutlineRow): WireWriter {
    intList(v.path).string(v.title).optional(v.page) { i32(it) }.bool(v.open)
    return this
}

fun WireReader.readOutlineRow(): OutlineRow = OutlineRow(intList(), string(), optional { i32() }, bool())

fun WireWriter.writeOutlinePage(v: OutlinePage): WireWriter {
    list(v.rows) { writeOutlineRow(it) }.optional(v.next) { i32(it) }
    return this
}

fun WireReader.readOutlinePage(): OutlinePage = OutlinePage(list(10) { readOutlineRow() }, optional { i32() })

fun WireWriter.writeLinkTarget(v: LinkTarget): WireWriter {
    when (v) {
        is LinkTarget.Page -> u8(0).i32(v.page)
        is LinkTarget.Uri -> u8(1).string(v.uri)
        is LinkTarget.Other -> u8(2).string(v.detail)
    }
    return this
}

fun WireReader.readLinkTarget(): LinkTarget = when (val tag = u8()) {
    0 -> LinkTarget.Page(i32())
    1 -> LinkTarget.Uri(string())
    2 -> LinkTarget.Other(string())
    else -> throw WireException("unknown LinkTarget tag $tag")
}

fun WireWriter.writePageLink(v: PageLink): WireWriter = writeRect(v.rect).writeLinkTarget(v.target)
fun WireReader.readPageLink(): PageLink = PageLink(readRect(), readLinkTarget())

// ---- annotations ----

fun WireWriter.writeStroke(v: Stroke): WireWriter = list(v.points) { writePt(it) }
fun WireReader.readStroke(): Stroke = Stroke(list(8) { readPt() })

fun WireWriter.writeNewMarkup(v: NewMarkup): WireWriter {
    i32(v.page).writeMarkupKind(v.kind).i32(v.rgb).f32(v.opacity).f32(v.widthPt)
        .list(v.strokes) { writeStroke(it) }.writeRects(v.rects).string(v.contents).string(v.author)
    return this
}

fun WireReader.readNewMarkup(): NewMarkup = NewMarkup(
    page = i32(), kind = readMarkupKind(), rgb = i32(), opacity = f32(), widthPt = f32(),
    strokes = list(4) { readStroke() }, rects = readRects(), contents = string(), author = string(),
)

// ---- forms ----

fun WireWriter.writeFieldOption(v: FieldOption): WireWriter = string(v.export).string(v.display)
fun WireReader.readFieldOption(): FieldOption = FieldOption(string(), string())

fun WireWriter.writeFieldWidget(v: FieldWidget): WireWriter {
    i32(v.page).writeRect(v.rect).optional(v.onState) { string(it) }
    return this
}

fun WireReader.readFieldWidget(): FieldWidget = FieldWidget(i32(), readRect(), optional { string() })

fun WireWriter.writeFieldInfo(v: FieldInfo): WireWriter {
    string(v.name).writeFieldKind(v.kind).list(v.value) { string(it) }.list(v.options) { writeFieldOption(it) }
        .bool(v.readOnly).bool(v.required).bool(v.multiline).optional(v.maxLen) { i32(it) }
        .list(v.widgets) { writeFieldWidget(it) }
    return this
}

fun WireReader.readFieldInfo(): FieldInfo = FieldInfo(
    name = string(), kind = readFieldKind(), value = list(4) { string() },
    options = list(8) { readFieldOption() }, readOnly = bool(), required = bool(), multiline = bool(),
    maxLen = optional { i32() }, widgets = list(21) { readFieldWidget() },
)

fun WireWriter.writeFieldValue(v: FieldValue): WireWriter {
    when (v) {
        is FieldValue.Text -> u8(0).string(v.value)
        is FieldValue.Check -> u8(1).bool(v.checked)
        is FieldValue.Radio -> u8(2).optional(v.onState) { string(it) }
        is FieldValue.Choice -> u8(3).list(v.values) { string(it) }
    }
    return this
}

fun WireReader.readFieldValue(): FieldValue = when (val tag = u8()) {
    0 -> FieldValue.Text(string())
    1 -> FieldValue.Check(bool())
    2 -> FieldValue.Radio(optional { string() })
    3 -> FieldValue.Choice(list(4) { string() })
    else -> throw WireException("unknown FieldValue tag $tag")
}

// ---- protection ----

fun WireWriter.writeProtectionSpec(v: ProtectionSpec): WireWriter {
    optional(v.openPassword) { string(it) }.optional(v.ownerPassword) { string(it) }
        .writePrintPerm(v.printing).writeChangePerm(v.changes).bool(v.allowCopy).bool(v.allowAccessibility)
    return this
}

fun WireReader.readProtectionSpec(): ProtectionSpec = ProtectionSpec(
    openPassword = optional { string() }, ownerPassword = optional { string() },
    printing = readPrintPerm(), changes = readChangePerm(), allowCopy = bool(), allowAccessibility = bool(),
)

// ---- files ----

fun WireWriter.writeMergeSource(v: MergeSource): WireWriter {
    string(v.name).bytes(v.bytes).optional(v.password) { string(it) }.list(v.ranges) { writePageRange(it) }
    return this
}

fun WireReader.readMergeSource(): MergeSource =
    MergeSource(string(), bytes(), optional { string() }, list(8) { readPageRange() })

fun WireWriter.writeSplitSpec(v: SplitSpec): WireWriter {
    when (v) {
        is SplitSpec.EveryN -> u8(0).i32(v.n)
        is SplitSpec.Before -> u8(1).intList(v.pages)
    }
    return this
}

fun WireReader.readSplitSpec(): SplitSpec = when (val tag = u8()) {
    0 -> SplitSpec.EveryN(i32())
    1 -> SplitSpec.Before(intList())
    else -> throw WireException("unknown SplitSpec tag $tag")
}

fun WireWriter.writeSplitPart(v: SplitPart): WireWriter = string(v.name).bytes(v.bytes)
fun WireReader.readSplitPart(): SplitPart = SplitPart(string(), bytes())

fun WireWriter.writeImageInput(v: ImageInput): WireWriter = string(v.name).bytes(v.bytes)
fun WireReader.readImageInput(): ImageInput = ImageInput(string(), bytes())

fun WireWriter.writeImageFrame(v: ImageFrame): WireWriter = i32(v.width).i32(v.height).bytes(v.rgba)
fun WireReader.readImageFrame(): ImageFrame = ImageFrame(i32(), i32(), bytes())
