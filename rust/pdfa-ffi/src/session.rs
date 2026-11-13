//! `PdfSession`: the stateful document object: one open document behind one mutex.
//!
//! Conventions (docs/contract/CONTRACT.md):
//! - pages are 0-based; every coordinate in or out is page VIEW space (points, origin top-left,
//!   y down, `/Rotate` applied);
//! - every exported body runs under `catch_unwind` (see [`with_session`] and [`guard`]);
//! - text, search and tiles go through the engine's render pool, hidden behind a blocking call
//!   with an internal wait limit.
//!
//! Locking: one mutex per session, held for the whole call, including waits on the render pool
//! (up to 30 s). `RenderPool` is not `Clone`, so the wait cannot be moved outside the lock. The
//! Kotlin client serialises calls per handle (EngineApi docs), so a slow tile delays only that
//! handle's later calls; keep it that way.
//!
//! Saving: `save_*` return the bytes and leave `dirty` set. The caller writes them, then calls
//! `confirm_saved`, which rebases editing on those bytes and clears `dirty`. Between the two the
//! session holds one extra shared copy of the file (`pending`); the Vec handed over the FFI is a
//! second one, unavoidable because the engine needs the bytes again at `confirm_saved`.
//!
//! A later package adds more methods in `files.rs` as a separate `impl PdfSession` block, using
//! [`PdfSession::with_session`] and [`map_edit_error`].

use std::sync::atomic::{AtomicU64, Ordering};
use std::sync::{Arc, Mutex, MutexGuard};
use std::time::{Duration, Instant};

use pdfcraft_engine::{DocId, Document, Edit, EditError, LinkAction, Session};
use pdfcraft_render::{PageText, RenderRequest, RenderedPage, RequestKind, Tile, device_pixels};

use crate::{
    DocMeta, EditState, LinkTarget, MAX_INPUT_BYTES, MAX_RENDER_PIXELS, OutlinePage, OutlineRow,
    PageInfo, PageLink, PdfError, Pt, Rect, RenderedImage, Rotation, TextSelection, guard,
    map_open_error,
};

/// Output over this many bytes is refused (contract rule 4).
#[cfg(target_pointer_width = "32")]
pub(crate) const MAX_OUTPUT_BYTES: usize = 128 * 1024 * 1024;
#[cfg(not(target_pointer_width = "32"))]
pub(crate) const MAX_OUTPUT_BYTES: usize = 512 * 1024 * 1024;
/// How long a text or search request may wait for the render pool.
const TEXT_WAIT: Duration = Duration::from_secs(10);
/// How long a tile request may wait for the render pool.
const PIXEL_WAIT: Duration = Duration::from_secs(30);
/// Largest tile side the renderer produces; larger requests would come back clipped. The
/// pixel cap `MAX_RENDER_PIXELS` (64 MP) therefore cannot fire (4096 x 4096 = 16.7 MP); it stays as
/// a second line of defence. On 32-bit targets tiles are smaller to bound memory.
#[cfg(target_pointer_width = "32")]
const MAX_TILE_SIDE: u32 = 2048;
#[cfg(not(target_pointer_width = "32"))]
const MAX_TILE_SIDE: u32 = 4096;
/// Largest scale (device pixels per point) the renderer accepts without clamping.
const MAX_SCALE: f32 = 400.0;
/// Most search matches returned for one page.
const MAX_SEARCH_MATCHES: usize = 10_000;
/// Longest search query, in characters.
const MAX_QUERY_CHARS: usize = 1_000;
/// Most outline rows returned by one call.
const MAX_OUTLINE_LIMIT: u32 = 1_000;
/// Smallest and largest page edge `insert_blank` accepts, in points (PDF's own limits).
const MIN_PAGE_EDGE: f32 = 3.0;
const MAX_PAGE_EDGE: f32 = 14_400.0;

/// Distinguishes this call's pool request from stale results of earlier, timed-out ones.
static NEXT_TAG: AtomicU64 = AtomicU64::new(1);

/// A saved file waiting for `confirm_saved`.
struct Pending {
    bytes: Arc<Vec<u8>>,
    /// The working file when it was saved; any edit, undo or redo replaces it.
    working: Arc<Vec<u8>>,
}

struct Inner {
    session: Session,
    id: DocId,
    pending: Option<Pending>,
}

/// One open document: the engine `Session` holding it, behind a mutex. The render pool lives in
/// the document (and is replaced by the engine after each edit), so it is fetched on every call.
#[derive(uniffi::Object)]
pub struct PdfSession {
    inner: Mutex<Inner>,
    /// Debug-only override of the pool wait limit, in milliseconds (0: the defaults).
    wait_ms: AtomicU64,
}

fn lock(m: &Mutex<Inner>) -> MutexGuard<'_, Inner> {
    // A panic while the lock was held poisons it; the state is replaced whole by the engine, so
    // recover the guard as the rest of the crate does.
    m.lock().unwrap_or_else(|poisoned| poisoned.into_inner())
}

fn invalid(detail: impl Into<String>) -> PdfError {
    PdfError::InvalidArgument {
        detail: detail.into(),
    }
}

/// Map an engine edit/save error to the contract's error type.
pub(crate) fn map_edit_error(e: EditError) -> PdfError {
    use pdfcraft_organize::OrganizeError;
    match e {
        EditError::NothingToUndo => invalid("nothing to undo"),
        EditError::NothingToRedo => invalid("nothing to redo"),
        EditError::Organize(OrganizeError::NoSuchPage(i)) => {
            invalid(format!("page {i} out of range"))
        }
        EditError::Organize(OrganizeError::WouldRemoveAllPages) => {
            invalid("a document must keep at least one page")
        }
        EditError::Organize(OrganizeError::Invalid(detail)) => invalid(detail),
        EditError::Invalid(detail) => invalid(detail),
        EditError::WrongPassword => PdfError::WrongPassword,
        EditError::Cancelled => PdfError::Cancelled,
        EditError::Write(detail) => PdfError::Io { detail },
        EditError::Reopen(detail) => PdfError::InvalidPdf { detail },
        EditError::NoDocument => PdfError::InvalidArgument {
            detail: "no such document".into(),
        },
        other => PdfError::Unsupported {
            detail: other.to_string(),
        },
    }
}

pub(crate) fn document(session: &Session, id: DocId) -> Result<&Document, PdfError> {
    session.get(id).ok_or_else(|| invalid("no such document"))
}

fn page_count(session: &Session, id: DocId) -> Result<usize, PdfError> {
    Ok(document(session, id)?.info.pages.len())
}

pub(crate) fn check_page(session: &Session, id: DocId, page: u32) -> Result<usize, PdfError> {
    let n = page_count(session, id)?;
    let p = page as usize;
    if p >= n {
        return Err(invalid(format!(
            "page {page} out of range (document has {n} pages)"
        )));
    }
    Ok(p)
}

/// Sorted, de-duplicated page indices; each must exist, and there must be at least one.
pub(crate) fn check_pages(
    session: &Session,
    id: DocId,
    pages: &[u32],
) -> Result<Vec<usize>, PdfError> {
    let n = page_count(session, id)?;
    if pages.is_empty() {
        return Err(invalid("no pages given"));
    }
    let mut out = Vec::with_capacity(pages.len());
    for &p in pages {
        let p = p as usize;
        if p >= n {
            return Err(invalid(format!(
                "page {p} out of range (document has {n} pages)"
            )));
        }
        out.push(p);
    }
    out.sort_unstable();
    out.dedup();
    Ok(out)
}

pub(crate) fn edit_state(session: &Session, id: DocId) -> Result<EditState, PdfError> {
    let doc = document(session, id)?;
    Ok(EditState {
        can_undo: doc.can_undo().is_some(),
        can_redo: doc.can_redo().is_some(),
        undo_label: doc.can_undo().map(str::to_owned),
        page_count: doc.info.pages.len() as u32,
        dirty: doc.dirty,
    })
}

fn rect_of(r: [f32; 4]) -> Rect {
    Rect {
        left: r[0],
        top: r[1],
        right: r[2],
        bottom: r[3],
    }
}

/// Run one request on the document's render pool and wait for it, up to `limit`.
fn pool_request(
    doc: &Document,
    mut req: RenderRequest,
    limit: Duration,
    what: &str,
) -> Result<RenderedPage, PdfError> {
    req.tag = NEXT_TAG.fetch_add(1, Ordering::Relaxed);
    let pool = &doc.renderer;
    pool.set_queue(vec![req]);
    let deadline = Instant::now() + limit;
    loop {
        match pool.try_recv() {
            Some(page) if page.request == req => {
                pool.set_queue(Vec::new());
                return match page.error {
                    Some(detail) => Err(PdfError::Render { detail }),
                    None => Ok(page),
                };
            }
            // A stale result of an earlier request: drop it.
            Some(_) => {}
            None => {
                if Instant::now() >= deadline {
                    // Ask the pool to stop working on it.
                    pool.set_queue(Vec::new());
                    return Err(PdfError::Render {
                        detail: format!("{what} timed out"),
                    });
                }
                std::thread::sleep(Duration::from_millis(2));
            }
        }
    }
}

fn page_text(doc: &Document, page: usize, limit: Duration) -> Result<Arc<PageText>, PdfError> {
    let req = RenderRequest {
        page,
        kind: RequestKind::Text,
        scale: 1.0,
        ..Default::default()
    };
    let out = pool_request(doc, req, limit, "text")?;
    out.text.ok_or_else(|| PdfError::Render {
        detail: "the renderer returned no text layer".into(),
    })
}

#[uniffi::export]
impl PdfSession {
    /// Open a PDF from memory. `password` is the user or owner password, if any.
    #[uniffi::constructor]
    pub fn open(bytes: Vec<u8>, password: Option<String>) -> Result<Arc<Self>, PdfError> {
        guard(|| {
            if bytes.len() > MAX_INPUT_BYTES {
                return Err(PdfError::TooLarge {
                    detail: format!("input is {} bytes, limit is {MAX_INPUT_BYTES}", bytes.len()),
                });
            }
            let mut session = Session::new();
            let id = session
                .open("input.pdf", None, Arc::new(bytes), password.as_deref())
                .map_err(map_open_error)?;
            Ok(Arc::new(Self {
                inner: Mutex::new(Inner {
                    session,
                    id,
                    pending: None,
                }),
                wait_ms: AtomicU64::new(0),
            }))
        })
    }

    /// Document information and permissions.
    pub fn meta(&self) -> Result<DocMeta, PdfError> {
        self.with_session(|session, id| {
            let doc = document(session, id)?;
            let field = |key: &str, fallback: &Option<String>| {
                doc.info_value(key)
                    .or_else(|| fallback.clone())
                    .unwrap_or_default()
            };
            Ok(DocMeta {
                title: field("Title", &doc.info.title),
                author: field("Author", &doc.info.author),
                subject: field("Subject", &doc.info.subject),
                keywords: field("Keywords", &doc.info.keywords),
                producer: doc.info.producer.clone().unwrap_or_default(),
                pdf_version: doc.info.pdf_version.clone(),
                page_count: doc.info.pages.len() as u32,
                file_size: doc.bytes.len() as u64,
                encrypted: doc.info.encrypted,
                can_print: doc.allows_printing(),
                can_copy: doc.allows_copying(),
                can_modify: doc.allows_modification(),
                can_annotate: doc.allows_annotation(),
            })
        })
    }

    /// Displayed size (after `/Rotate`) and label of every page.
    pub fn page_infos(&self) -> Result<Vec<PageInfo>, PdfError> {
        self.with_session(|session, id| {
            let doc = document(session, id)?;
            Ok(doc
                .info
                .pages
                .iter()
                .map(|p| PageInfo {
                    width_pt: p.width,
                    height_pt: p.height,
                    label: p.label.clone(),
                })
                .collect())
        })
    }

    /// Rasterise the region `x, y, w, h` (device pixels at `scale` pixels per point) of `page`.
    /// Premultiplied RGBA8; the result is exactly `w` x `h` (areas outside the page are paper).
    /// `preview` draws at low detail (thumbnails).
    #[allow(clippy::too_many_arguments)]
    pub fn render_tile(
        &self,
        page: u32,
        scale: f32,
        x: u32,
        y: u32,
        w: u32,
        h: u32,
        preview: bool,
    ) -> Result<RenderedImage, PdfError> {
        self.with_session(|session, id| {
            let p = check_page(session, id, page)?;
            if !(scale.is_finite() && scale > 0.0) {
                return Err(invalid(format!("scale must be a positive number, got {scale}")));
            }
            if scale > MAX_SCALE {
                return Err(PdfError::TooLarge { detail: format!("scale {scale} exceeds the limit of {MAX_SCALE}") });
            }
            if w == 0 || h == 0 {
                return Err(invalid("tile width and height must be at least 1"));
            }
            if w > MAX_TILE_SIDE || h > MAX_TILE_SIDE || u64::from(w) * u64::from(h) > MAX_RENDER_PIXELS {
                return Err(PdfError::TooLarge { detail: format!("tile is {w}x{h}, limit is {MAX_TILE_SIDE} per side and {MAX_RENDER_PIXELS} pixels") });
            }
            let doc = document(session, id)?;
            let info = doc.info.pages.get(p).ok_or_else(|| invalid("page out of range"))?;
            let (page_w, page_h) = (device_pixels(info.width, scale), device_pixels(info.height, scale));
            if x >= page_w || y >= page_h {
                return Err(invalid(format!("tile origin ({x}, {y}) is outside the {page_w}x{page_h} page")));
            }
            let req = RenderRequest { page: p, kind: RequestKind::Pixels, tile: Some(Tile { x, y, w, h }), scale, preview, ..Default::default() };
            let out = pool_request(doc, req, self.limit(PIXEL_WAIT), "render")?;
            let expected = (w as usize).checked_mul(h as usize).and_then(|n| n.checked_mul(4));
            if out.width != w || out.height != h || expected != Some(out.rgba.len()) {
                return Err(PdfError::Render { detail: format!("renderer returned {}x{}, expected {w}x{h}", out.width, out.height) });
            }
            Ok(RenderedImage { width: out.width, height: out.height, rgba: out.rgba.to_vec() })
        })
    }

    /// Find `query` on `page`; one rect per line fragment of each match, in reading order.
    /// An empty query finds nothing.
    pub fn search_page(
        &self,
        page: u32,
        query: String,
        case_sensitive: bool,
        whole_word: bool,
    ) -> Result<Vec<Rect>, PdfError> {
        self.with_session(|session, id| {
            let p = check_page(session, id, page)?;
            if query.chars().count() > MAX_QUERY_CHARS {
                return Err(invalid(format!(
                    "query is longer than {MAX_QUERY_CHARS} characters"
                )));
            }
            if query.trim().is_empty() {
                return Ok(Vec::new());
            }
            let text = page_text(document(session, id)?, p, self.limit(TEXT_WAIT))?;
            let mut out = Vec::new();
            for range in
                text.find_opts_limited(&query, case_sensitive, whole_word, MAX_SEARCH_MATCHES)
            {
                out.extend(text.line_rects(range).into_iter().map(rect_of));
            }
            Ok(out)
        })
    }

    /// The text between two points of `page`; equal points select the word under the point.
    /// `None` when there is no text there. Refused when the document forbids copying.
    pub fn select_text(
        &self,
        page: u32,
        from: Pt,
        to: Pt,
    ) -> Result<Option<TextSelection>, PdfError> {
        self.with_session(|session, id| {
            let p = check_page(session, id, page)?;
            if ![from.x, from.y, to.x, to.y].iter().all(|v| v.is_finite()) {
                return Err(invalid("selection points must be finite"));
            }
            let doc = document(session, id)?;
            if !doc.allows_copying() {
                return Err(PdfError::NotPermitted {
                    detail: "copy not permitted".into(),
                });
            }
            let text = page_text(doc, p, self.limit(TEXT_WAIT))?;
            let range = if from == to {
                let Some(i) = text.glyph_at(from.x, from.y) else {
                    return Ok(None);
                };
                let Some((first, last)) = text.word_at(i) else {
                    return Ok(None);
                };
                first..last.saturating_add(1)
            } else {
                let (Some(a), Some(b)) = (text.nearest(from.x, from.y), text.nearest(to.x, to.y))
                else {
                    return Ok(None);
                };
                a.min(b)..a.max(b).saturating_add(1)
            };
            let selected = text.text_of(range.clone());
            if selected.trim().is_empty() {
                return Ok(None);
            }
            let rects = text.line_rects(range).into_iter().map(rect_of).collect();
            Ok(Some(TextSelection {
                text: selected,
                rects,
            }))
        })
    }

    /// Up to `limit` bookmarks in outline order from position `offset`.
    pub fn outline(&self, offset: u32, limit: u32) -> Result<OutlinePage, PdfError> {
        self.with_session(|session, id| {
            if limit == 0 {
                return Err(invalid("limit must be at least 1"));
            }
            let limit = limit.min(MAX_OUTLINE_LIMIT);
            let Some(page) = session.bookmark_page(id, offset as usize, limit as usize) else {
                return Ok(OutlinePage {
                    rows: Vec::new(),
                    next: None,
                });
            };
            let rows = page
                .rows
                .into_iter()
                .map(|r| OutlineRow {
                    path: r
                        .path
                        .into_iter()
                        .map(|i| u32::try_from(i).unwrap_or(u32::MAX))
                        .collect(),
                    title: r.title,
                    page: r.page.and_then(|p| u32::try_from(p).ok()),
                    open: r.open,
                })
                .collect();
            Ok(OutlinePage {
                rows,
                next: page.next.and_then(|n| u32::try_from(n).ok()),
            })
        })
    }

    /// Link annotations of `page`, rects in view space.
    pub fn links(&self, page: u32) -> Result<Vec<PageLink>, PdfError> {
        self.with_session(|session, id| {
            let p = check_page(session, id, page)?;
            let doc = document(session, id)?;
            let info = doc
                .info
                .pages
                .get(p)
                .ok_or_else(|| invalid("page out of range"))?;
            let mut out = Vec::new();
            let n = doc.info.pages.len();
            for link in doc
                .links()
                .iter()
                .filter(|l| l.page == p && l.rect.iter().all(|v| v.is_finite()))
            {
                // Annotation rects are user space; convert both corners and normalise.
                let [x0, y0, x1, y1] = link.rect;
                let a = info.user_to_view(x0 as f32, y0 as f32);
                let b = info.user_to_view(x1 as f32, y1 as f32);
                let rect = Rect {
                    left: a[0].min(b[0]),
                    top: a[1].min(b[1]),
                    right: a[0].max(b[0]),
                    bottom: a[1].max(b[1]),
                };
                let target = match &link.action {
                    LinkAction::Page(t) if *t < n => LinkTarget::Page { page: *t as u32 },
                    LinkAction::Page(t) => LinkTarget::Other {
                        detail: format!("page {t} does not exist"),
                    },
                    LinkAction::Uri(uri) => LinkTarget::Uri { uri: uri.clone() },
                    LinkAction::Other(detail) => LinkTarget::Other {
                        detail: detail.clone(),
                    },
                };
                out.push(PageLink { rect, target });
            }
            Ok(out)
        })
    }

    /// The undo/redo/dirty state.
    pub fn state(&self) -> Result<EditState, PdfError> {
        self.with_session(|session, id| edit_state(session, id))
    }

    /// Rotate pages clockwise (`Cw90`, `Half`) or counter-clockwise (`Ccw90`).
    pub fn rotate_pages(&self, pages: Vec<u32>, by: Rotation) -> Result<EditState, PdfError> {
        self.with_session(|session, id| {
            let pages = check_pages(session, id, &pages)?;
            let degrees = match by {
                Rotation::Cw90 => 90,
                Rotation::Half => 180,
                Rotation::Ccw90 => 270,
            };
            session
                .apply(id, Edit::RotatePages { pages, degrees })
                .map_err(map_edit_error)?;
            edit_state(session, id)
        })
    }

    /// Delete pages; deleting every page is refused.
    pub fn delete_pages(&self, pages: Vec<u32>) -> Result<EditState, PdfError> {
        self.with_session(|session, id| {
            let pages = check_pages(session, id, &pages)?;
            session
                .apply(id, Edit::DeletePages { pages })
                .map_err(map_edit_error)?;
            edit_state(session, id)
        })
    }

    /// Move pages (kept in their relative order) so the first starts at `to_index` of the result.
    pub fn move_pages(&self, pages: Vec<u32>, to_index: u32) -> Result<EditState, PdfError> {
        self.with_session(|session, id| {
            let pages = check_pages(session, id, &pages)?;
            // `to_index` indexes the result (the other pages keep their order, the moved ones are
            // spliced in at that position), so the largest valid value is n - moved.
            let n = page_count(session, id)?;
            let max = n - pages.len();
            if to_index as usize > max {
                return Err(invalid(format!(
                    "destination {to_index} out of range (valid 0..={max})"
                )));
            }
            session
                .apply(
                    id,
                    Edit::MovePages {
                        pages,
                        to: to_index as usize,
                    },
                )
                .map_err(map_edit_error)?;
            edit_state(session, id)
        })
    }

    /// Insert a copy of each page right after the last of the given pages.
    pub fn duplicate_pages(&self, pages: Vec<u32>) -> Result<EditState, PdfError> {
        self.with_session(|session, id| {
            let pages = check_pages(session, id, &pages)?;
            session
                .apply(id, Edit::DuplicatePages { pages, at: None })
                .map_err(map_edit_error)?;
            edit_state(session, id)
        })
    }

    /// Insert a blank page of the given size so it becomes page `at` (`at == page_count` appends).
    pub fn insert_blank(
        &self,
        at: u32,
        width_pt: f32,
        height_pt: f32,
    ) -> Result<EditState, PdfError> {
        self.with_session(|session, id| {
            let n = page_count(session, id)?;
            if at as usize > n {
                return Err(invalid(format!("position {at} out of range (document has {n} pages)")));
            }
            let ok = |v: f32| v.is_finite() && (MIN_PAGE_EDGE..=MAX_PAGE_EDGE).contains(&v);
            if !ok(width_pt) || !ok(height_pt) {
                return Err(invalid(format!("page size must be {MIN_PAGE_EDGE}..={MAX_PAGE_EDGE} points, got {width_pt}x{height_pt}")));
            }
            session
                .apply(id, Edit::InsertBlankPage { at: at as usize, width: f64::from(width_pt), height: f64::from(height_pt) })
                .map_err(map_edit_error)?;
            edit_state(session, id)
        })
    }

    /// Undo the last edit.
    pub fn undo(&self) -> Result<EditState, PdfError> {
        self.with_session(|session, id| {
            session.undo(id).map_err(map_edit_error)?;
            edit_state(session, id)
        })
    }

    /// Redo the last undone edit.
    pub fn redo(&self) -> Result<EditState, PdfError> {
        self.with_session(|session, id| {
            session.redo(id).map_err(map_edit_error)?;
            edit_state(session, id)
        })
    }

    /// Call after the bytes from `save_*` were written successfully: rebases editing on them,
    /// clears `dirty` and returns the new state. Refused if the document changed since that save.
    pub fn confirm_saved(&self) -> Result<EditState, PdfError> {
        self.with_inner(|inner| {
            let id = inner.id;
            let Some(pending) = inner.pending.take() else {
                return Err(invalid("nothing was saved"));
            };
            if !Arc::ptr_eq(&document(&inner.session, id)?.bytes, &pending.working) {
                return Err(invalid("the document changed since it was saved"));
            }
            inner
                .session
                .mark_saved(id, pending.bytes, None)
                .map_err(map_edit_error)?;
            edit_state(&inner.session, id)
        })
    }

    /// The whole file with the edits appended as one revision (a full rewrite when the engine
    /// needs one). `dirty` stays set until the caller has written the bytes and calls
    /// `confirm_saved`.
    pub fn save_incremental(&self) -> Result<Vec<u8>, PdfError> {
        self.with_inner(|inner| save(inner, false))
    }

    /// A compact, garbage-collected rewrite. `dirty` stays set until `confirm_saved`.
    pub fn save_full(&self) -> Result<Vec<u8>, PdfError> {
        self.with_inner(|inner| save(inner, true))
    }
}

/// Write the document and check the bytes reopen. Does not mark the session saved.
pub(crate) fn save_checked(
    session: &mut Session,
    id: DocId,
    full: bool,
) -> Result<Arc<Vec<u8>>, PdfError> {
    let bytes = if full {
        session.save_full_bytes(id)
    } else {
        session.save_bytes(id)
    }
    .map_err(map_edit_error)?;
    if bytes.len() > MAX_OUTPUT_BYTES {
        return Err(PdfError::TooLarge {
            detail: format!(
                "output is {} bytes, limit is {MAX_OUTPUT_BYTES}",
                bytes.len()
            ),
        });
    }
    session.check_save(id, &bytes).map_err(map_edit_error)?;
    Ok(bytes)
}

fn save(inner: &mut Inner, full: bool) -> Result<Vec<u8>, PdfError> {
    let id = inner.id;
    let bytes = save_checked(&mut inner.session, id, full)?;
    let working = document(&inner.session, id)?.bytes.clone();
    let out = (*bytes).clone();
    inner.pending = Some(Pending { bytes, working });
    Ok(out)
}

impl PdfSession {
    /// Run `f` on the engine session and document id under the session lock, with panics turned
    /// into `PdfError::Panic`. The one entry point for every method, including those added in
    /// other files.
    fn limit(&self, default: Duration) -> Duration {
        match self.wait_ms.load(Ordering::Relaxed) {
            0 => default,
            ms => Duration::from_millis(ms.saturating_sub(1)),
        }
    }

    fn with_inner<R>(
        &self,
        f: impl FnOnce(&mut Inner) -> Result<R, PdfError>,
    ) -> Result<R, PdfError> {
        guard(|| f(&mut lock(&self.inner)))
    }

    pub(crate) fn with_session<R>(
        &self,
        f: impl FnOnce(&mut Session, DocId) -> Result<R, PdfError>,
    ) -> Result<R, PdfError> {
        self.with_inner(|inner| {
            let id = inner.id;
            f(&mut inner.session, id)
        })
    }

    /// Test hook: make pool waits last `ms - 1` milliseconds (`1` means no wait at all; `0`
    /// restores the defaults), to exercise the timeout path. Debug builds only.
    #[cfg(debug_assertions)]
    pub fn debug_set_wait_ms(&self, ms: u64) {
        self.wait_ms.store(ms, Ordering::Relaxed);
    }

    /// Test hook: panic while the session lock is held, to prove the panic maps to
    /// `PdfError::Panic` and the poisoned lock is recovered. Debug builds only.
    #[cfg(debug_assertions)]
    pub fn debug_panic_inside_lock(&self) -> Result<(), PdfError> {
        self.with_session(|_, _| -> Result<(), PdfError> {
            panic!("debug_panic_inside_lock requested")
        })
    }
}
