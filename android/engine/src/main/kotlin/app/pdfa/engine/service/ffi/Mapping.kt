package app.pdfa.engine.service.ffi

import app.pdfa.engine.ChangePerm
import app.pdfa.engine.DocMeta
import app.pdfa.engine.EditState
import app.pdfa.engine.EngineError
import app.pdfa.engine.FieldInfo
import app.pdfa.engine.FieldKind
import app.pdfa.engine.FieldOption
import app.pdfa.engine.FieldValue
import app.pdfa.engine.FieldWidget
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
import app.pdfa.engine.SplitPart
import app.pdfa.engine.SplitSpec
import app.pdfa.engine.Stroke
import app.pdfa.engine.TextSelection
import uniffi.pdfa_ffi.PdfException
import uniffi.pdfa_ffi.ChangePerm as FChangePerm
import uniffi.pdfa_ffi.DocMeta as FDocMeta
import uniffi.pdfa_ffi.EditState as FEditState
import uniffi.pdfa_ffi.FieldInfo as FFieldInfo
import uniffi.pdfa_ffi.FieldKind as FFieldKind
import uniffi.pdfa_ffi.FieldValue as FFieldValue
import uniffi.pdfa_ffi.ImageInput as FImageInput
import uniffi.pdfa_ffi.LinkTarget as FLinkTarget
import uniffi.pdfa_ffi.MarkupKind as FMarkupKind
import uniffi.pdfa_ffi.MergeSource as FMergeSource
import uniffi.pdfa_ffi.NewMarkup as FNewMarkup
import uniffi.pdfa_ffi.OutlinePage as FOutlinePage
import uniffi.pdfa_ffi.PageInfo as FPageInfo
import uniffi.pdfa_ffi.PageLink as FPageLink
import uniffi.pdfa_ffi.PageRange as FPageRange
import uniffi.pdfa_ffi.PrintPerm as FPrintPerm
import uniffi.pdfa_ffi.ProtectionSpec as FProtectionSpec
import uniffi.pdfa_ffi.Pt as FPt
import uniffi.pdfa_ffi.Quad as FQuad
import uniffi.pdfa_ffi.Rect as FRect
import uniffi.pdfa_ffi.Rotation as FRotation
import uniffi.pdfa_ffi.ScanLook as FScanLook
import uniffi.pdfa_ffi.SplitPart as FSplitPart
import uniffi.pdfa_ffi.SplitSpec as FSplitSpec
import uniffi.pdfa_ffi.Stroke as FStroke
import uniffi.pdfa_ffi.TextSelection as FTextSelection

/*
 * Record and error mapping between the generated UniFFI types (prefix F here) and `:engine-api`.
 * Pure functions: building these records does not load the native library, so they are unit-tested
 * on the JVM. Unsigned values that do not fit a Kotlin Int are clamped, never wrapped negative.
 */

internal fun UInt.int(): Int = if (this > Int.MAX_VALUE.toUInt()) Int.MAX_VALUE else toInt()
internal fun ULong.long(): Long = if (this > Long.MAX_VALUE.toULong()) Long.MAX_VALUE else toLong()
internal fun List<Int>.uints(): List<UInt> = map { it.toUInt() }

/** One to one with Rust's `PdfError`. Details come from the engine and never hold passwords. */
fun PdfException.toEngineError(): EngineError = when (this) {
    is PdfException.InvalidPdf -> EngineError.InvalidPdf(detail)
    is PdfException.PasswordRequired -> EngineError.PasswordRequired
    is PdfException.WrongPassword -> EngineError.WrongPassword
    is PdfException.TooLarge -> EngineError.TooLarge(detail)
    is PdfException.InvalidArgument -> EngineError.InvalidArgument(detail)
    is PdfException.Render -> EngineError.Render(detail)
    is PdfException.Panic -> EngineError.Panic(detail)
    is PdfException.Unsupported -> EngineError.Unsupported(detail)
    is PdfException.Io -> EngineError.Io(detail)
    is PdfException.Cancelled -> EngineError.Cancelled
    is PdfException.NotPermitted -> EngineError.NotPermitted(detail)
}

// ---- geometry ----

fun FPt.toApi() = Pt(x, y)
fun Pt.toFfi() = FPt(x, y)
fun FRect.toApi() = Rect(left, top, right, bottom)
fun Rect.toFfi() = FRect(left, top, right, bottom)
fun FQuad.toApi() = Quad(topLeft.toApi(), topRight.toApi(), bottomRight.toApi(), bottomLeft.toApi())
fun Quad.toFfi() = FQuad(topLeft.toFfi(), topRight.toFfi(), bottomRight.toFfi(), bottomLeft.toFfi())

// ---- enums ----

fun Rotation.toFfi(): FRotation = when (this) {
    Rotation.CW90 -> FRotation.CW90
    Rotation.HALF -> FRotation.HALF
    Rotation.CCW90 -> FRotation.CCW90
}

fun MarkupKind.toFfi(): FMarkupKind = when (this) {
    MarkupKind.INK -> FMarkupKind.INK
    MarkupKind.HIGHLIGHT -> FMarkupKind.HIGHLIGHT
    MarkupKind.NOTE -> FMarkupKind.NOTE
}

fun FFieldKind.toApi(): FieldKind = when (this) {
    FFieldKind.TEXT -> FieldKind.TEXT
    FFieldKind.CHECK_BOX -> FieldKind.CHECK_BOX
    FFieldKind.RADIO -> FieldKind.RADIO
    FFieldKind.PUSH_BUTTON -> FieldKind.PUSH_BUTTON
    FFieldKind.COMBO -> FieldKind.COMBO
    FFieldKind.LIST -> FieldKind.LIST
    FFieldKind.SIGNATURE -> FieldKind.SIGNATURE
}

fun PrintPerm.toFfi(): FPrintPerm = when (this) {
    PrintPerm.NONE -> FPrintPerm.NONE
    PrintPerm.LOW -> FPrintPerm.LOW
    PrintPerm.HIGH -> FPrintPerm.HIGH
}

fun ChangePerm.toFfi(): FChangePerm = when (this) {
    ChangePerm.NONE -> FChangePerm.NONE
    ChangePerm.PAGES -> FChangePerm.PAGES
    ChangePerm.FILL_SIGN -> FChangePerm.FILL_SIGN
    ChangePerm.COMMENT_FILL_SIGN -> FChangePerm.COMMENT_FILL_SIGN
    ChangePerm.ANY_EXCEPT_EXTRACT -> FChangePerm.ANY_EXCEPT_EXTRACT
}

fun ScanLook.toFfi(): FScanLook = when (this) {
    ScanLook.ORIGINAL -> FScanLook.ORIGINAL
    ScanLook.COLOUR -> FScanLook.COLOUR
    ScanLook.GREYSCALE -> FScanLook.GREYSCALE
    ScanLook.BLACK_WHITE -> FScanLook.BLACK_WHITE
}

// ---- records out of the engine ----

fun FEditState.toApi() = EditState(canUndo, canRedo, undoLabel, pageCount.int(), dirty)

fun FDocMeta.toApi() = DocMeta(
    title, author, subject, keywords, producer, pdfVersion, pageCount.int(), fileSize.long(),
    encrypted, canPrint, canCopy, canModify, canAnnotate,
)

fun FPageInfo.toApi() = PageInfo(widthPt, heightPt, label)
fun FTextSelection.toApi() = TextSelection(text, rects.map { it.toApi() })

fun FOutlinePage.toApi() = OutlinePage(
    rows.map { OutlineRow(it.path.map { i -> i.int() }, it.title, it.page?.int(), it.open) },
    next?.int(),
)

fun FLinkTarget.toApi(): LinkTarget = when (this) {
    is FLinkTarget.Page -> LinkTarget.Page(page.int())
    is FLinkTarget.Uri -> LinkTarget.Uri(uri)
    is FLinkTarget.Other -> LinkTarget.Other(detail)
}

fun FPageLink.toApi() = PageLink(rect.toApi(), target.toApi())

fun FFieldInfo.toApi() = FieldInfo(
    name = name,
    kind = kind.toApi(),
    value = value,
    options = options.map { FieldOption(it.export, it.display) },
    readOnly = readOnly,
    required = required,
    multiline = multiline,
    maxLen = maxLen?.int(),
    widgets = widgets.map { FieldWidget(it.page.int(), it.rect.toApi(), it.onState) },
)

fun FSplitPart.toApi() = SplitPart(name, bytes)

// ---- records into the engine ----

fun NewMarkup.toFfi() = FNewMarkup(
    page = page.toUInt(),
    kind = kind.toFfi(),
    rgb = rgb.toUInt() and 0xFFFFFFu,
    opacity = opacity,
    widthPt = widthPt,
    strokes = strokes.map { s: Stroke -> FStroke(s.points.map { it.toFfi() }) },
    rects = rects.map { it.toFfi() },
    contents = contents,
    author = author,
)

fun FieldValue.toFfi(): FFieldValue = when (this) {
    is FieldValue.Text -> FFieldValue.Text(value)
    is FieldValue.Check -> FFieldValue.Check(checked)
    is FieldValue.Radio -> FFieldValue.Radio(onState)
    is FieldValue.Choice -> FFieldValue.Choice(values)
}

fun ProtectionSpec.toFfi() = FProtectionSpec(
    openPassword, ownerPassword, printing.toFfi(), changes.toFfi(), allowCopy, allowAccessibility,
)

fun SplitSpec.toFfi(): FSplitSpec = when (this) {
    is SplitSpec.EveryN -> FSplitSpec.EveryN(n.toUInt())
    is SplitSpec.Before -> FSplitSpec.Before(pages.uints())
}

fun PageRange.toFfi() = FPageRange(start.toUInt(), endInclusive.toUInt())
fun MergeSource.toFfi() = FMergeSource(name, bytes, password, ranges.map { it.toFfi() })
fun ImageInput.toFfi() = FImageInput(name, bytes)
