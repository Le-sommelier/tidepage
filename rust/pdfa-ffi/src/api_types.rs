//! The engine contract (docs/contract/CONTRACT.md): data types that cross the Rust <-> Kotlin boundary.
//!
//! This file is owned by the senior reviewer. Work packages implement the functions that use
//! these types (see docs/contract/CONTRACT.md); they never change a type here without a
//! contract amendment.
//!
//! Rules (full text in docs/contract/CONTRACT.md):
//! - Page indices are 0-based everywhere. The UI shows `index + 1`.
//! - Every coordinate that crosses the boundary is in page VIEW space: PDF points (1/72 in),
//!   origin top-left, y grows downward, /Rotate already applied. The Rust side converts
//!   to and from PDF user space (annotations, links); Kotlin never sees user space.
//! - Every error field is named `detail` (never `message`, which clashes with Throwable).

/// A point in page view space.
#[derive(Debug, Clone, Copy, PartialEq, uniffi::Record)]
pub struct Pt {
    pub x: f32,
    pub y: f32,
}

/// A rectangle in page view space (`left < right`, `top < bottom`).
#[derive(Debug, Clone, Copy, PartialEq, uniffi::Record)]
pub struct Rect {
    pub left: f32,
    pub top: f32,
    pub right: f32,
    pub bottom: f32,
}

/// Four document corners in image pixel space, clockwise from the top-left.
#[derive(Debug, Clone, Copy, PartialEq, uniffi::Record)]
pub struct Quad {
    pub top_left: Pt,
    pub top_right: Pt,
    pub bottom_right: Pt,
    pub bottom_left: Pt,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, uniffi::Enum)]
pub enum Rotation {
    Cw90,
    Half,
    Ccw90,
}

/// Inclusive page range, 0-based.
#[derive(Debug, Clone, Copy, PartialEq, Eq, uniffi::Record)]
pub struct PageRange {
    pub start: u32,
    pub end_inclusive: u32,
}

/// Per-page facts the viewer needs before it renders anything.
#[derive(Debug, Clone, PartialEq, uniffi::Record)]
pub struct PageInfo {
    pub width_pt: f32,
    pub height_pt: f32,
    /// Page label ("iv", "A-3") or the 1-based number as text.
    pub label: String,
}

/// What the edit stack looks like after every mutating call.
#[derive(Debug, Clone, PartialEq, Eq, uniffi::Record)]
pub struct EditState {
    pub can_undo: bool,
    pub can_redo: bool,
    pub undo_label: Option<String>,
    pub page_count: u32,
    /// True when the document differs from the last saved bytes.
    pub dirty: bool,
}

#[derive(Debug, Clone, PartialEq, uniffi::Record)]
pub struct DocMeta {
    pub title: String,
    pub author: String,
    pub subject: String,
    pub keywords: String,
    pub producer: String,
    pub pdf_version: String,
    pub page_count: u32,
    pub file_size: u64,
    pub encrypted: bool,
    pub can_print: bool,
    pub can_copy: bool,
    pub can_modify: bool,
    pub can_annotate: bool,
}

// ---- viewer: search, selection, outline, links --------------------------------------------

/// Text found on one page; one rect per line fragment.
#[derive(Debug, Clone, PartialEq, uniffi::Record)]
pub struct SearchHit {
    pub page: u32,
    pub rects: Vec<Rect>,
}

/// Text selected between two points (equal points select the word under it).
#[derive(Debug, Clone, PartialEq, uniffi::Record)]
pub struct TextSelection {
    pub text: String,
    pub rects: Vec<Rect>,
}

/// One bookmark row. `path` is the index path from the root; its length is the depth.
#[derive(Debug, Clone, PartialEq, uniffi::Record)]
pub struct OutlineRow {
    pub path: Vec<u32>,
    pub title: String,
    pub page: Option<u32>,
    pub open: bool,
}

#[derive(Debug, Clone, PartialEq, uniffi::Record)]
pub struct OutlinePage {
    pub rows: Vec<OutlineRow>,
    /// Offset to pass to the next call, or `None` when the outline is complete.
    pub next: Option<u32>,
}

#[derive(Debug, Clone, PartialEq, uniffi::Enum)]
pub enum LinkTarget {
    Page { page: u32 },
    Uri { uri: String },
    Other { detail: String },
}

#[derive(Debug, Clone, PartialEq, uniffi::Record)]
pub struct PageLink {
    pub rect: Rect,
    pub target: LinkTarget,
}

// ---- annotations ---------------------------------------------------------------------------

#[derive(Debug, Clone, Copy, PartialEq, Eq, uniffi::Enum)]
pub enum MarkupKind {
    Ink,
    Highlight,
    Note,
}

#[derive(Debug, Clone, PartialEq, uniffi::Record)]
pub struct Stroke {
    pub points: Vec<Pt>,
}

/// A new annotation. Ink uses `strokes`; Highlight uses `rects` (one per line); Note uses
/// `rects[0].left/top` as its anchor and `contents` as its text.
#[derive(Debug, Clone, PartialEq, uniffi::Record)]
pub struct NewMarkup {
    pub page: u32,
    pub kind: MarkupKind,
    /// 0xRRGGBB.
    pub rgb: u32,
    /// 0.0..=1.0.
    pub opacity: f32,
    pub width_pt: f32,
    pub strokes: Vec<Stroke>,
    pub rects: Vec<Rect>,
    pub contents: String,
    pub author: String,
}

// ---- forms ---------------------------------------------------------------------------------

#[derive(Debug, Clone, Copy, PartialEq, Eq, uniffi::Enum)]
pub enum FieldKind {
    Text,
    CheckBox,
    Radio,
    PushButton,
    Combo,
    List,
    Signature,
}

#[derive(Debug, Clone, PartialEq, uniffi::Record)]
pub struct FieldOption {
    pub export: String,
    pub display: String,
}

#[derive(Debug, Clone, PartialEq, uniffi::Record)]
pub struct FieldWidget {
    pub page: u32,
    pub rect: Rect,
    /// The state name this widget selects (radio and check boxes), if any.
    pub on_state: Option<String>,
}

#[derive(Debug, Clone, PartialEq, uniffi::Record)]
pub struct FieldInfo {
    /// Fully qualified field name; unique, and the key for `set_field`.
    pub name: String,
    pub kind: FieldKind,
    pub value: Vec<String>,
    pub options: Vec<FieldOption>,
    pub read_only: bool,
    pub required: bool,
    pub multiline: bool,
    pub max_len: Option<u32>,
    pub widgets: Vec<FieldWidget>,
}

#[derive(Debug, Clone, PartialEq, uniffi::Enum)]
pub enum FieldValue {
    Text { value: String },
    Check { checked: bool },
    Radio { on_state: Option<String> },
    Choice { values: Vec<String> },
}

// ---- protection ----------------------------------------------------------------------------

#[derive(Debug, Clone, Copy, PartialEq, Eq, uniffi::Enum)]
pub enum PrintPerm {
    None,
    Low,
    High,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, uniffi::Enum)]
pub enum ChangePerm {
    None,
    Pages,
    FillSign,
    CommentFillSign,
    AnyExceptExtract,
}

/// Always AES-256. RC4 and AES-128 are deliberately not offered.
/// `Debug` is hand-written so passwords can never be printed.
#[derive(Clone, PartialEq, Eq, uniffi::Record)]
pub struct ProtectionSpec {
    pub open_password: Option<String>,
    pub owner_password: Option<String>,
    pub printing: PrintPerm,
    pub changes: ChangePerm,
    pub allow_copy: bool,
    pub allow_accessibility: bool,
}

// ---- files: merge, split, images, scan -----------------------------------------------------

/// One input to `merge`. Empty `ranges` means the whole document.
/// `Debug` is hand-written so the password and the document bytes are never printed.
#[derive(Clone, PartialEq, Eq, uniffi::Record)]
pub struct MergeSource {
    pub name: String,
    pub bytes: Vec<u8>,
    pub password: Option<String>,
    pub ranges: Vec<PageRange>,
}

#[derive(Debug, Clone, PartialEq, Eq, uniffi::Enum)]
pub enum SplitSpec {
    EveryN {
        n: u32,
    },
    /// Start a new part before each listed page (0-based, ascending, page 0 is implicit).
    Before {
        pages: Vec<u32>,
    },
}

#[derive(Debug, Clone, PartialEq, Eq, uniffi::Record)]
pub struct SplitPart {
    pub name: String,
    pub bytes: Vec<u8>,
}

/// Image bytes for `images_to_pdf`: JPEG, PNG, TIFF, GIF, BMP, PCX or JPEG 2000. HEIC, HEIF,
/// AVIF and WebP are not supported by the engine; Kotlin converts them to JPEG first.
#[derive(Debug, Clone, PartialEq, Eq, uniffi::Record)]
pub struct ImageInput {
    pub name: String,
    pub bytes: Vec<u8>,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, uniffi::Enum)]
pub enum ScanLook {
    Original,
    Colour,
    Greyscale,
    BlackWhite,
}

impl std::fmt::Debug for ProtectionSpec {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        f.debug_struct("ProtectionSpec")
            .field(
                "open_password",
                &self.open_password.as_ref().map(|_| "<redacted>"),
            )
            .field(
                "owner_password",
                &self.owner_password.as_ref().map(|_| "<redacted>"),
            )
            .field("printing", &self.printing)
            .field("changes", &self.changes)
            .field("allow_copy", &self.allow_copy)
            .field("allow_accessibility", &self.allow_accessibility)
            .finish()
    }
}

impl std::fmt::Debug for MergeSource {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        f.debug_struct("MergeSource")
            .field("name", &self.name)
            .field("bytes", &format_args!("<{} bytes>", self.bytes.len()))
            .field("password", &self.password.as_ref().map(|_| "<redacted>"))
            .field("ranges", &self.ranges)
            .finish()
    }
}
