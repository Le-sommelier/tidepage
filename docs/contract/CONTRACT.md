# Engine contract

Owner: senior reviewer. Work packages implement against this; they do not change it. A change
needs a contract amendment (a commit that touches this file, `api_types.rs` and the Kotlin
`engine-api` files together).

Files:
- Rust types: `rust/pdfa-ffi/src/api_types.rs` (compiled, uniffi-exported)
- Rust functions: signatures below (implemented in rust/pdfa-ffi)
- Kotlin: `android/engine-api/src/main/kotlin/app/pdfa/engine/{Models,EngineError,EngineApi}.kt`

## Rules

1. **Pages are 0-based** everywhere. The UI shows `index + 1`.
2. **One coordinate system.** Everything crossing the boundary is page *view space*: PDF points,
   origin top-left, y down, `/Rotate` applied. Upstream mixes view space (text, search) and PDF
   user space (annotations, links, form widgets). The Rust wrapper converts, using
   `PageInfo::view_to_user` (`render/src/inspect/model.rs:113`) and its inverse. Kotlin never sees
   user space.
3. **Errors** carry their text in `detail`. `Timeout` and `EngineDied` exist only on the Kotlin
   side (the engine process was supervised and failed); Rust never produces them.
4. **Guard rails** (enforced in Rust): input over 256 MiB, render over 64 megapixels, output
   over 512 MiB all fail with `TooLarge`.
5. **Panics** never cross the boundary: every export is wrapped in `catch_unwind` and maps to
   `PdfError::Panic`.
6. **Encryption is AES-256 only** and is behind the cargo feature `encrypt` and the Kotlin
   `FeatureFlags.encrypt` flag, both off until the interop test passes (upstream bugs #774, #836).
   Without the feature the functions return `PdfError::Unsupported`.
7. **No network, no file paths.** The engine only ever receives bytes. Kotlin reads and writes
   files (SAF, app storage).
8. **HEIC/HEIF/AVIF/WebP** are not supported by the engine; Kotlin converts to JPEG.
9. Modules other than `:engine` never import `uniffi.*`.

## Rust functions (`rust/pdfa-ffi`)

New object:

```rust
#[derive(uniffi::Object)]
pub struct PdfSession { /* Mutex<Session + DocId + render pool>; one document */ }

#[uniffi::export]
impl PdfSession {
    #[uniffi::constructor]
    pub fn open(bytes: Vec<u8>, password: Option<String>) -> Result<Arc<Self>, PdfError>;

    // read
    pub fn meta(&self) -> Result<DocMeta, PdfError>;
    pub fn page_infos(&self) -> Result<Vec<PageInfo>, PdfError>;
    pub fn render_tile(&self, page: u32, scale: f32, x: u32, y: u32, w: u32, h: u32, preview: bool)
        -> Result<RenderedImage, PdfError>;
    pub fn search_page(&self, page: u32, query: String, case_sensitive: bool, whole_word: bool)
        -> Result<Vec<Rect>, PdfError>;
    pub fn select_text(&self, page: u32, from: Pt, to: Pt) -> Result<Option<TextSelection>, PdfError>;
    pub fn outline(&self, offset: u32, limit: u32) -> Result<OutlinePage, PdfError>;
    pub fn links(&self, page: u32) -> Result<Vec<PageLink>, PdfError>;

    // edit (every one returns the new EditState)
    pub fn state(&self) -> Result<EditState, PdfError>;
    pub fn rotate_pages(&self, pages: Vec<u32>, by: Rotation) -> Result<EditState, PdfError>;
    pub fn delete_pages(&self, pages: Vec<u32>) -> Result<EditState, PdfError>;
    pub fn move_pages(&self, pages: Vec<u32>, to_index: u32) -> Result<EditState, PdfError>; // to_index in 0..=(page_count - pages.len()), index in the result
    pub fn duplicate_pages(&self, pages: Vec<u32>) -> Result<EditState, PdfError>;
    pub fn insert_blank(&self, at: u32, width_pt: f32, height_pt: f32) -> Result<EditState, PdfError>;
    pub fn undo(&self) -> Result<EditState, PdfError>;
    pub fn redo(&self) -> Result<EditState, PdfError>;
    pub fn add_markup(&self, markup: NewMarkup) -> Result<EditState, PdfError>;
    pub fn list_fields(&self) -> Result<Vec<FieldInfo>, PdfError>;
    pub fn set_field(&self, name: String, value: FieldValue) -> Result<EditState, PdfError>;
    pub fn reset_form(&self, names: Option<Vec<String>>) -> Result<EditState, PdfError>;

    // save and files that need the open document
    pub fn save_incremental(&self) -> Result<Vec<u8>, PdfError>; // does NOT clear `dirty`
    pub fn save_full(&self) -> Result<Vec<u8>, PdfError>;        // does NOT clear `dirty`
    pub fn confirm_saved(&self) -> Result<EditState, PdfError>;  // clears `dirty` after the caller's write succeeded
    pub fn save_protected(&self, spec: ProtectionSpec) -> Result<Vec<u8>, PdfError>; // feature `encrypt`
    pub fn remove_protection(&self) -> Result<EditState, PdfError>;                  // feature `encrypt`
    pub fn extract_pages(&self, pages: Vec<u32>) -> Result<Vec<u8>, PdfError>;
    pub fn split(&self, spec: SplitSpec) -> Result<Vec<SplitPart>, PdfError>;
}

// stateless
#[uniffi::export] pub fn merge(sources: Vec<MergeSource>) -> Result<Vec<u8>, PdfError>;
#[uniffi::export] pub fn images_to_pdf(images: Vec<ImageInput>, dpi: Option<f64>) -> Result<Vec<u8>, PdfError>;
#[uniffi::export] pub fn detect_document_corners(rgba: Vec<u8>, width: u32, height: u32) -> Result<Option<Quad>, PdfError>;
#[uniffi::export] pub fn rectify_and_clean(rgba: Vec<u8>, width: u32, height: u32, quad: Quad, look: ScanLook, level: u8)
    -> Result<RenderedImage, PdfError>;
```

## Where each call lands upstream

Paths relative to `third_party/pdfcraft/crates/`. Line numbers are at commit `8d5d39e`.

| Call | Upstream |
|---|---|
| `open` | `engine/src/open.rs:97` `Session::open(name, path, Arc<Vec<u8>>, password)`; unlock `engine/src/session/save.rs:99` |
| `meta` | `Document::info_value` `engine/src/document.rs:606`; `permissions()` `:424`; `DocInfo` `render/src/inspect.rs:49` |
| `page_infos` | `DocInfo.pages[i]` (width, height, `label`) |
| `render_tile` | `render/src/raster.rs:521` `PageRenderer::render(RenderRequest{tile, scale, kind: Pixels, preview})`; result premultiplied RGBA8 |
| `search_page` | `render/src/text.rs:140` `PageText::find_opts`, then `line_rects` `:268`; text from `RenderRequest{kind: Text}` (`raster.rs:202,184`) via the render pool. **View space already.** |
| `select_text` | `text.rs:182` `glyph_at`, `:234` `word_at`, `:275` `glyph_rects`. View space. |
| `outline` | `engine/src/session/view.rs:148` `bookmark_page(id, offset, limit)` |
| `links` | `engine/src/document.rs:490` `Document::links()` → `LinkItem{rect (user space), action}` (`annot/src/links.rs:71`). Convert to view space. |
| `rotate/delete/move/duplicate/insert_blank` | `engine/src/edit.rs:22,26,29,37,44` via `Session::apply` (`session/editing.rs:22`); `degrees` multiple of 90 |
| `undo / redo` | `session/editing.rs:247 / :276` |
| `add_markup` | `Edit::AddAnnotation(NewAnnotation)` `edit.rs:158`; Ink `annot/src/types.rs:385` (`strokes: Vec<Vec<[f64;2]>>`, user space); highlight `TextMarkup{Highlight, quads}` `:363`, quad order TL,TR,BL,BR; note `Note{at, icon}` `:357`; style `:582` |
| `list_fields` | `forms/src/read.rs:291` `fields(&Document) -> Vec<Field>`; `Field` `forms/src/lib.rs:149`, `Widget` page is 0-based, rect user space |
| `set_field` | `Edit::SetFieldValue{name, FieldValue}` `edit.rs:305` (`forms/src/lib.rs:248`) |
| `reset_form` | `Edit::ResetForm{names}` `edit.rs:310` |
| `save_*` | `session/save.rs:14 save_bytes` (incremental, falls back to full when encryption changed), `:43 save_full_bytes` |
| `save_protected` | `Edit::Protect(Protection)` `edit.rs:634`; `Protection` is at `engine/src/protection.rs:43`; we only ever set `Algorithm::Aes256` |
| `extract_pages` | `engine/src/session/files.rs:351` `extract(id, &[usize])` (0-based) |
| `split` | `files.rs:361` `split(id, &SplitBy)`; `SplitBy::{PageCount, Before}` (`organize/src/import/combine.rs:41`) |
| `merge` | `files.rs:204` `combine_ranges(&[CombineSource])`, `:247` `combine_unlocked` for passwords; ranges are 1-based text ("1-3, 6") so build the string from `PageRange` |
| `images_to_pdf` | `files.rs:19,24` `create_from_images[_with_resolution]` → `create/src/lib.rs:836`; `ImageResolution::{Embedded, Dpi(1..=1200)}` |
| `detect_document_corners` | `engine/src/camera_edges.rs:49` `detect_document` |
| `rectify_and_clean` | `engine/src/camera.rs:32` `warp`, `camera_clean.rs:58` `apply(rgb,w,h,Look,level)` |

## Known gaps (the contract hides them, the implementer must handle them)

- **No cancel or deadline** on open, render or save upstream. The process watchdog is the only
  defence (`EngineError.Timeout`).
- **Text and search go through the async render pool** (`set_queue` / `try_recv`,
  `render/src/raster/pool.rs:343,360`). The wrapper must hide that behind blocking calls with its
  own internal wait limit.
- **Search is per page.** Kotlin loops pages and can be cancelled between pages.
- `pdfcraft-render` types are not re-exported by `pdfcraft-engine`; the wrapper depends on both.
- **Gating** sign, print, js, ocr needs an upstream patch (see docs/size-report.md). Print cannot be removed while
  `session/files.rs` uses `select_pages`; JS cannot be removed independently of XFA.

## Amendments

- 2026-11-09: `confirm_saved` added; `save_*` no longer clear `dirty`, so a failed
  write cannot hide unsaved changes. `move_pages` documented: `to_index` is an index in the result,
  valid range `0..=(page_count - pages.len())`, larger values are `InvalidArgument`.
- 2026-11-09: `PdfError::NotPermitted{detail}` / `EngineError.NotPermitted` added
  (not the owner, copying forbidden). `ProtectionSpec` and `MergeSource` have redacting `Debug`.
  `save_protected` rules: restrictions (printing != High, changes != AnyExceptExtract, !allow_copy,
  !allow_accessibility) require an `owner_password`, otherwise `InvalidArgument`; a document that
  carries a filled signature is refused with `NotPermitted{"document is signed"}`; re-protecting an
  already-encrypted document requires an explicit `owner_password`. Exported free function
  `encryption_available() -> bool` reports whether the `encrypt` feature is compiled in.
- 2026-11-09: `EngineEvent.EditsLost(handle, atEpochMs, originalRestored)` added: emitted
  per reopened document after an engine restart. Critical events (SafeModeRestarted, EditsLost) must
  not be dropped by flow backpressure or when no collector is active (replay or sticky state).
