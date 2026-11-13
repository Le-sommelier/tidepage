package app.pdfa.engine

import java.nio.ByteBuffer

/*
 * The engine contract (docs/contract/CONTRACT.md). Pure Kotlin/JVM: no Android imports, no
 * `uniffi.*` imports. These types mirror rust/pdfa-ffi/src/api_types.rs one to one.
 *
 * Rules: page indices are 0-based (the UI shows index + 1); every coordinate is in page VIEW
 * space (PDF points, origin top-left, y down, /Rotate applied).
 */

/** Opaque handle to an open document inside the engine process. */
@JvmInline
value class DocHandle(val id: Long)

data class Pt(val x: Float, val y: Float)

/** `left < right`, `top < bottom`. */
data class Rect(val left: Float, val top: Float, val right: Float, val bottom: Float)

/** Four document corners in image pixel space, clockwise from the top-left. */
data class Quad(val topLeft: Pt, val topRight: Pt, val bottomRight: Pt, val bottomLeft: Pt)

enum class Rotation { CW90, HALF, CCW90 }

/** Inclusive, 0-based. */
data class PageRange(val start: Int, val endInclusive: Int)

data class PageInfo(val widthPt: Float, val heightPt: Float, val label: String)

data class EditState(
    val canUndo: Boolean,
    val canRedo: Boolean,
    val undoLabel: String?,
    val pageCount: Int,
    /** True when the document differs from the last saved bytes. */
    val dirty: Boolean,
)

data class DocMeta(
    val title: String,
    val author: String,
    val subject: String,
    val keywords: String,
    val producer: String,
    val pdfVersion: String,
    val pageCount: Int,
    val fileSize: Long,
    val encrypted: Boolean,
    val canPrint: Boolean,
    val canCopy: Boolean,
    val canModify: Boolean,
    val canAnnotate: Boolean,
)

/** A rectangle of a page at a zoom, in device pixels. `scale` = device pixels per point. */
data class TileRequest(
    val page: Int,
    val scale: Float,
    val x: Int,
    val y: Int,
    val width: Int,
    val height: Int,
    /** True for thumbnails and low-res first paint: the engine may skip tiny images. */
    val preview: Boolean = false,
)

/** Premultiplied RGBA8, row-major, `width * height * 4` bytes. */
class TileBitmap(val width: Int, val height: Int, val premultipliedRgba: ByteBuffer)

// ---- viewer ----

data class SearchHit(val page: Int, val rects: List<Rect>)
data class TextSelection(val text: String, val rects: List<Rect>)

/** `path` is the index path from the outline root; its size is the depth. */
data class OutlineRow(val path: List<Int>, val title: String, val page: Int?, val open: Boolean)

/** `next` is the offset for the following call, or null when the outline is complete. */
data class OutlinePage(val rows: List<OutlineRow>, val next: Int?)

sealed interface LinkTarget {
    data class Page(val page: Int) : LinkTarget
    data class Uri(val uri: String) : LinkTarget
    data class Other(val detail: String) : LinkTarget
}

data class PageLink(val rect: Rect, val target: LinkTarget)

// ---- annotations ----

enum class MarkupKind { INK, HIGHLIGHT, NOTE }

data class Stroke(val points: List<Pt>)

/**
 * Ink uses [strokes]; Highlight uses [rects] (one per text line); Note anchors at the top-left
 * of `rects[0]` and carries its text in [contents].
 */
data class NewMarkup(
    val page: Int,
    val kind: MarkupKind,
    /** 0xRRGGBB. */
    val rgb: Int,
    /** 0.0..1.0 */
    val opacity: Float,
    val widthPt: Float,
    val strokes: List<Stroke> = emptyList(),
    val rects: List<Rect> = emptyList(),
    val contents: String = "",
    val author: String = "",
)

// ---- forms ----

enum class FieldKind { TEXT, CHECK_BOX, RADIO, PUSH_BUTTON, COMBO, LIST, SIGNATURE }
data class FieldOption(val export: String, val display: String)
data class FieldWidget(val page: Int, val rect: Rect, val onState: String?)

data class FieldInfo(
    /** Fully qualified, unique; the key for `setField`. */
    val name: String,
    val kind: FieldKind,
    val value: List<String>,
    val options: List<FieldOption>,
    val readOnly: Boolean,
    val required: Boolean,
    val multiline: Boolean,
    val maxLen: Int?,
    val widgets: List<FieldWidget>,
)

sealed interface FieldValue {
    data class Text(val value: String) : FieldValue
    data class Check(val checked: Boolean) : FieldValue
    data class Radio(val onState: String?) : FieldValue
    data class Choice(val values: List<String>) : FieldValue
}

// ---- protection ----

enum class PrintPerm { NONE, LOW, HIGH }
enum class ChangePerm { NONE, PAGES, FILL_SIGN, COMMENT_FILL_SIGN, ANY_EXCEPT_EXTRACT }

/** Always AES-256; RC4 and AES-128 are deliberately not offered. */
data class ProtectionSpec(
    val openPassword: String?,
    val ownerPassword: String?,
    val printing: PrintPerm = PrintPerm.HIGH,
    val changes: ChangePerm = ChangePerm.ANY_EXCEPT_EXTRACT,
    val allowCopy: Boolean = false,
    val allowAccessibility: Boolean = true,
)

// ---- files ----

/** Empty [ranges] means the whole document. */
class MergeSource(
    val name: String,
    val bytes: ByteArray,
    val password: String? = null,
    val ranges: List<PageRange> = emptyList(),
)

sealed interface SplitSpec {
    data class EveryN(val n: Int) : SplitSpec

    /** Start a new part before each listed page (0-based, ascending; page 0 is implicit). */
    data class Before(val pages: List<Int>) : SplitSpec
}

class SplitPart(val name: String, val bytes: ByteArray)

/**
 * JPEG, PNG, TIFF, GIF, BMP, PCX or JPEG 2000. HEIC/HEIF/AVIF/WebP are NOT supported by the
 * engine: the caller converts them to JPEG first (Android's ImageDecoder can).
 */
class ImageInput(val name: String, val bytes: ByteArray)

enum class ScanLook { ORIGINAL, COLOUR, GREYSCALE, BLACK_WHITE }

/** An RGBA8 camera frame handed to the scan routines. */
class ImageFrame(val width: Int, val height: Int, val rgba: ByteArray)
