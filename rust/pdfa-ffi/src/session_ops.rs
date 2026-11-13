//! Session-bound operations that need the open document: annotations, forms, extract, split and
//! protection. A second `impl PdfSession` block next to the one in `session.rs`; every
//! method runs under `PdfSession::with_session` (lock + `catch_unwind`).
//!
//! Conventions (docs/contract/CONTRACT.md): pages are 0-based; every coordinate in or out is page
//! VIEW space (points, origin top-left, y down, `/Rotate` applied) and is converted here with the
//! engine's `PageInfo::view_to_user` / `user_to_view` (the crop box origin is part of that
//! conversion). Nothing here unwraps or indexes without a check.
//!
//! Decisions worth knowing:
//! - `save_protected` does NOT change the live session. It takes a full save of the current state,
//!   opens it in a scratch engine `Session`, applies `Edit::Protect` there and returns that
//!   session's full save. The caller therefore gets a protected COPY; the open document stays
//!   unprotected, keeps its undo stack and its `dirty` flag, and `confirm_saved` does not apply
//!   to it (`confirm_saved` would need the private `pending` slot of `session.rs`).
//! - `remove_protection` is a real, undoable edit of the live session (the next save is a full
//!   rewrite without encryption); it needs a document opened with the owner password.
//! - Algorithm is always AES-256. Passwords are never formatted or logged here.

use std::collections::HashSet;
use std::sync::Arc;

use pdfcraft_engine::{
    Edit, EditError, FormFieldKind, Markup, NewAnnotation, NoteIcon, Shape, SplitBy, Style,
};
use pdfcraft_forms::{FieldValue as FormValue, FormError};

#[cfg(feature = "encrypt")]
use crate::session::save_checked;
use crate::session::{
    MAX_OUTPUT_BYTES, check_page, check_pages, document, edit_state, map_edit_error,
};
use crate::{
    EditState, FieldInfo, FieldKind, FieldOption, FieldValue, FieldWidget, MarkupKind, NewMarkup,
    PdfError, PdfSession, ProtectionSpec, Rect, SplitPart, SplitSpec, Stroke,
};

/// Most points over all strokes of one ink annotation.
const MAX_INK_POINTS: usize = 100_000;
/// Most strokes in one ink annotation.
const MAX_STROKES: usize = 10_000;
/// Most rectangles (text lines) in one highlight.
const MAX_RECTS: usize = 10_000;
/// Longest note text / field text, in bytes.
const MAX_CONTENTS_BYTES: usize = 100_000;
const MAX_TEXT_FIELD_BYTES: usize = 1_000_000;
const MAX_AUTHOR_BYTES: usize = 1_000;
/// Widest stroke, in points.
const MAX_WIDTH_PT: f32 = 100.0;
/// A coordinate beyond this is nonsense for a page (PDF's own limit is 14 400 pt).
const MAX_COORD: f32 = 1.0e6;
/// Most parts `split` produces.
const MAX_SPLIT_PARTS: usize = 1_000;
/// Most values `set_field` takes for a choice.
const MAX_LIST_ITEMS: usize = 100_000;
/// Most names `reset_form` accepts.
const MAX_RESET_NAMES: usize = 10_000;
/// A password's longest length in UTF-8 bytes (PDF 2.0 limits AES-256 passwords to 127).
#[cfg(feature = "encrypt")]
const MAX_PASSWORD_BYTES: usize = 127;

fn invalid(detail: impl Into<String>) -> PdfError {
    PdfError::InvalidArgument {
        detail: detail.into(),
    }
}

/// `map_edit_error` sends form, comment and protection failures to `Unsupported`; for these
/// methods they are the caller's bad arguments.
fn map_ops_error(e: EditError) -> PdfError {
    match e {
        EditError::Form(FormError::ReadOnly(_)) => invalid("field is read-only"),
        EditError::Form(e) => invalid(e.to_string()),
        EditError::Comment(e) => invalid(e.to_string()),
        // Validation messages mention "password"; a missing protection is the caller's mistake
        // too. Anything else (no randomness, the cipher set-up failing) is not the caller's fault.
        EditError::Protection(detail)
            if detail.contains("password") || detail.contains("isn't protected") =>
        {
            invalid(detail)
        }
        EditError::Protection(detail) => PdfError::Io { detail },
        e @ (EditError::NotPermitted(_) | EditError::SignedRewrite(_)) => PdfError::NotPermitted {
            detail: e.to_string(),
        },
        EditError::Signed => PdfError::NotPermitted {
            detail: "document is signed".into(),
        },
        other => map_edit_error(other),
    }
}

fn finite_pt(x: f32, y: f32) -> bool {
    x.is_finite() && y.is_finite() && x.abs() <= MAX_COORD && y.abs() <= MAX_COORD
}

#[cfg(debug_assertions)]
thread_local! {
    /// Debug-only lowered output cap for tests (per thread: `with_session` runs on the caller's).
    static CAP_OVERRIDE: std::cell::Cell<Option<usize>> = const { std::cell::Cell::new(None) };
}

fn output_cap() -> usize {
    #[cfg(debug_assertions)]
    if let Some(cap) = CAP_OVERRIDE.with(std::cell::Cell::get) {
        return cap;
    }
    MAX_OUTPUT_BYTES
}

fn check_output(len: usize) -> Result<(), PdfError> {
    let cap = output_cap();
    if len > cap {
        return Err(PdfError::TooLarge {
            detail: format!("output is {len} bytes, limit is {cap}"),
        });
    }
    Ok(())
}

/// The bytes of a freshly built file without copying them when nobody else holds them.
fn into_vec(bytes: Arc<Vec<u8>>) -> Vec<u8> {
    Arc::try_unwrap(bytes).unwrap_or_else(|shared| (*shared).clone())
}

/// Factor from view points to user units on this page (differs from 1 with `/UserUnit`).
fn user_per_view(page: &pdfcraft_render::PageInfo) -> f64 {
    let [x0, y0, x1, y1] = page.crop;
    let user = if page.rotation.is_multiple_of(180) {
        x1 - x0
    } else {
        y1 - y0
    };
    let k = f64::from(user) / f64::from(page.width);
    if k.is_finite() && k > 0.0 { k } else { 1.0 }
}

/// 0xRRGGBB to the engine's 0..1 floats.
fn rgb_of(rgb: u32) -> Result<[f64; 3], PdfError> {
    if rgb > 0x00FF_FFFF {
        return Err(invalid(format!("colour {rgb:#x} is not 0xRRGGBB")));
    }
    let c = |shift: u32| f64::from((rgb >> shift) & 0xFF) / 255.0;
    Ok([c(16), c(8), c(0)])
}

fn check_rect(r: &Rect) -> Result<[f32; 4], PdfError> {
    if !finite_pt(r.left, r.top) || !finite_pt(r.right, r.bottom) {
        return Err(invalid("rectangle coordinates must be finite"));
    }
    if r.left >= r.right || r.top >= r.bottom {
        return Err(invalid("rectangle must have left < right and top < bottom"));
    }
    Ok([r.left, r.top, r.right, r.bottom])
}

fn ink_strokes(
    page: &pdfcraft_render::PageInfo,
    strokes: &[Stroke],
) -> Result<Vec<Vec<[f64; 2]>>, PdfError> {
    if strokes.is_empty() {
        return Err(invalid("an ink annotation needs at least one stroke"));
    }
    if strokes.len() > MAX_STROKES {
        return Err(invalid(format!(
            "too many strokes ({}, limit {MAX_STROKES})",
            strokes.len()
        )));
    }
    let mut total = 0usize;
    let mut out = Vec::with_capacity(strokes.len());
    for s in strokes {
        if s.points.len() < 2 {
            return Err(invalid("every stroke needs at least 2 points"));
        }
        total = total.saturating_add(s.points.len());
        if total > MAX_INK_POINTS {
            return Err(invalid(format!(
                "too many ink points (limit {MAX_INK_POINTS})"
            )));
        }
        let mut line = Vec::with_capacity(s.points.len());
        for p in &s.points {
            if !finite_pt(p.x, p.y) {
                return Err(invalid("stroke points must be finite"));
            }
            let [x, y] = page.view_to_user(p.x, p.y);
            line.push([f64::from(x), f64::from(y)]);
        }
        out.push(line);
    }
    Ok(out)
}

fn shape_of(m: &NewMarkup, page: &pdfcraft_render::PageInfo) -> Result<Shape, PdfError> {
    match m.kind {
        MarkupKind::Ink => {
            if !(m.width_pt.is_finite() && m.width_pt > 0.0 && m.width_pt <= MAX_WIDTH_PT) {
                return Err(invalid(format!(
                    "stroke width must be above 0 and at most {MAX_WIDTH_PT} points"
                )));
            }
            Ok(Shape::Ink {
                strokes: ink_strokes(page, &m.strokes)?,
            })
        }
        MarkupKind::Highlight => {
            if m.rects.is_empty() {
                return Err(invalid("a highlight needs at least one rectangle"));
            }
            if m.rects.len() > MAX_RECTS {
                return Err(invalid(format!(
                    "too many rectangles ({}, limit {MAX_RECTS})",
                    m.rects.len()
                )));
            }
            let mut quads = Vec::with_capacity(m.rects.len());
            for r in &m.rects {
                quads.push(page.view_rect_to_quad(check_rect(r)?));
            }
            Ok(Shape::TextMarkup {
                kind: Markup::Highlight,
                quads,
            })
        }
        MarkupKind::Note => {
            let r = m
                .rects
                .first()
                .ok_or_else(|| invalid("a note needs a rectangle for its anchor"))?;
            // Only the top-left is used, so a zero-size rectangle is fine.
            if !finite_pt(r.left, r.top) {
                return Err(invalid("note anchor must be finite"));
            }
            let (left, top) = (r.left, r.top);
            // The note carries the NoRotate flag: a viewer keeps the icon upright and pins the
            // top-left corner of its (user space) rectangle, i.e. `at`. Pinning `at` to the user
            // point of the view anchor therefore puts the upright icon right/down of the anchor
            // on every /Rotate (placing the whole 20 pt square in user space would shift it by
            // the icon size on rotated pages).
            let [ux, uy] = page.view_to_user(left, top);
            let at = [f64::from(ux), f64::from(uy)];
            Ok(Shape::Note {
                at,
                icon: NoteIcon::default(),
            })
        }
    }
}

fn field_kind(k: FormFieldKind) -> FieldKind {
    match k {
        FormFieldKind::Text => FieldKind::Text,
        FormFieldKind::CheckBox => FieldKind::CheckBox,
        FormFieldKind::Radio => FieldKind::Radio,
        FormFieldKind::PushButton => FieldKind::PushButton,
        FormFieldKind::Combo => FieldKind::Combo,
        FormFieldKind::List => FieldKind::List,
        FormFieldKind::Signature => FieldKind::Signature,
    }
}

#[cfg(feature = "encrypt")]
fn check_password(label: &str, pw: &Option<zeroize::Zeroizing<String>>) -> Result<(), PdfError> {
    if let Some(p) = pw {
        if p.is_empty() {
            return Err(invalid(format!("the {label} password can't be empty")));
        }
        if p.len() > MAX_PASSWORD_BYTES {
            return Err(invalid(format!(
                "the {label} password is longer than {MAX_PASSWORD_BYTES} bytes"
            )));
        }
    }
    Ok(())
}

/// Validate `spec` and build the engine's `Protection`. The spec's passwords are moved into
/// `Zeroizing` wrappers (wiped on drop, best effort) and cloned once into the engine type.
#[cfg(feature = "encrypt")]
fn protection_of(
    mut spec: ProtectionSpec,
    already_encrypted: bool,
) -> Result<pdfcraft_engine::Protection, PdfError> {
    use pdfcraft_engine::{Algorithm, Changes, Printing, Protection};
    use zeroize::Zeroizing;
    let open = spec.open_password.take().map(Zeroizing::new);
    let owner = spec.owner_password.take().map(Zeroizing::new);
    check_password("open", &open)?;
    check_password("owner", &owner)?;
    if open.is_none() && owner.is_none() {
        return Err(invalid("give an open password, an owner password, or both"));
    }
    if open.is_some() && open == owner {
        return Err(invalid(
            "the open password and the owner password must differ",
        ));
    }
    let restricted = spec.printing != crate::PrintPerm::High
        || spec.changes != crate::ChangePerm::AnyExceptExtract
        || !spec.allow_copy
        || !spec.allow_accessibility;
    if owner.is_none() && restricted {
        return Err(invalid("restrictions need an owner password"));
    }
    if owner.is_none() && already_encrypted {
        return Err(invalid(
            "protecting an encrypted document again needs an explicit owner password",
        ));
    }
    Ok(Protection {
        open_password: open.as_ref().map(|p| p.as_str().to_owned()),
        permissions_password: owner.as_ref().map(|p| p.as_str().to_owned()),
        printing: match spec.printing {
            crate::PrintPerm::None => Printing::None,
            crate::PrintPerm::Low => Printing::Low,
            crate::PrintPerm::High => Printing::High,
        },
        changes: match spec.changes {
            crate::ChangePerm::None => Changes::None,
            crate::ChangePerm::Pages => Changes::Pages,
            crate::ChangePerm::FillSign => Changes::FillSign,
            crate::ChangePerm::CommentFillSign => Changes::CommentFillSign,
            crate::ChangePerm::AnyExceptExtract => Changes::AnyExceptExtract,
        },
        copy: spec.allow_copy,
        accessibility: spec.allow_accessibility,
        // AES-256 only; RC4 and AES-128 are deliberately not reachable from this crate.
        algorithm: Algorithm::Aes256,
        encrypt_metadata: true,
    })
}

/// Whether this build can encrypt (`save_protected` / `remove_protection` work): true only with
/// the `encrypt` cargo feature.
#[uniffi::export]
pub fn encryption_available() -> bool {
    cfg!(feature = "encrypt")
}

#[uniffi::export]
impl PdfSession {
    /// Add an ink stroke set, a highlight or a sticky note. Coordinates are page view space.
    /// `opacity` is 0..=1; `width_pt` (ink only) is above 0 and at most 100.
    pub fn add_markup(&self, markup: NewMarkup) -> Result<EditState, PdfError> {
        self.with_session(|session, id| {
            let p = check_page(session, id, markup.page)?;
            if !(markup.opacity.is_finite() && (0.0..=1.0).contains(&markup.opacity)) {
                return Err(invalid(format!(
                    "opacity must be within 0..=1, got {}",
                    markup.opacity
                )));
            }
            if markup.contents.len() > MAX_CONTENTS_BYTES {
                return Err(invalid(format!(
                    "contents are longer than {MAX_CONTENTS_BYTES} bytes"
                )));
            }
            if markup.author.len() > MAX_AUTHOR_BYTES {
                return Err(invalid(format!(
                    "author is longer than {MAX_AUTHOR_BYTES} bytes"
                )));
            }
            let color = rgb_of(markup.rgb)?;
            let shape = {
                let doc = document(session, id)?;
                let info = doc
                    .info
                    .pages
                    .get(p)
                    .ok_or_else(|| invalid("page out of range"))?;
                shape_of(&markup, info)?
            };
            let mut style = Style::default_for(&shape);
            style.color = color;
            style.opacity = f64::from(markup.opacity);
            if markup.kind == MarkupKind::Ink {
                // Width is in view points, the style wants user units (they differ with /UserUnit).
                let k = document(session, id)?
                    .info
                    .pages
                    .get(p)
                    .map_or(1.0, user_per_view);
                style.width = f64::from(markup.width_pt) * k;
            }
            let annotation = NewAnnotation {
                page: p,
                shape,
                style,
                contents: markup.contents,
                author: markup.author,
            };
            session
                .apply(id, Edit::AddAnnotation(annotation))
                .map_err(map_ops_error)?;
            edit_state(session, id)
        })
    }

    /// Every form field of the current state, widget rects in view space. Widgets that are on no
    /// page are left out of `widgets` (the field is still listed). For check boxes and radio
    /// groups `value` holds the selected on-state (empty when off).
    pub fn list_fields(&self) -> Result<Vec<FieldInfo>, PdfError> {
        self.with_session(|session, id| {
            let doc = document(session, id)?;
            let mut out = Vec::with_capacity(doc.form.len());
            for f in doc.form.iter() {
                let mut widgets = Vec::new();
                for w in &f.widgets {
                    let Some(page) = w.page else { continue };
                    let Some(info) = doc.info.pages.get(page) else {
                        continue;
                    };
                    if !w.rect.iter().all(|v| v.is_finite()) {
                        continue;
                    }
                    let a = info.user_to_view(w.rect[0] as f32, w.rect[1] as f32);
                    let b = info.user_to_view(w.rect[2] as f32, w.rect[3] as f32);
                    if !a.iter().chain(&b).all(|v| v.is_finite()) {
                        continue;
                    }
                    widgets.push(FieldWidget {
                        page: u32::try_from(page).unwrap_or(u32::MAX),
                        rect: Rect {
                            left: a[0].min(b[0]),
                            top: a[1].min(b[1]),
                            right: a[0].max(b[0]),
                            bottom: a[1].max(b[1]),
                        },
                        on_state: w.on_state.clone(),
                    });
                }
                out.push(FieldInfo {
                    name: f.name.clone(),
                    kind: field_kind(f.kind),
                    value: f.value.clone(),
                    options: f
                        .options
                        .iter()
                        .map(|(e, d)| FieldOption {
                            export: e.clone(),
                            display: d.clone(),
                        })
                        .collect(),
                    read_only: f.read_only(),
                    required: f.has(pdfcraft_engine::field_flags::REQUIRED),
                    multiline: f.kind == FormFieldKind::Text
                        && f.has(pdfcraft_engine::field_flags::MULTILINE),
                    max_len: f.max_len.and_then(|m| u32::try_from(m).ok()),
                    widgets,
                });
            }
            Ok(out)
        })
    }

    /// Set one field. The value kind must match the field kind: `Text` for text fields, `Check`
    /// for check boxes, `Radio` for radio groups, `Choice` for combo and list boxes. Anything
    /// else (unknown name, read-only, button or signature field, mismatched kind) is
    /// `InvalidArgument`.
    pub fn set_field(&self, name: String, value: FieldValue) -> Result<EditState, PdfError> {
        self.with_session(|session, id| {
            let doc = document(session, id)?;
            let field = doc
                .form
                .iter()
                .find(|f| f.name == name)
                .ok_or_else(|| invalid(format!("no field named {name:?}")))?;
            if field.read_only() {
                return Err(invalid("field is read-only"));
            }
            let engine_value = match (field.kind, value) {
                (FormFieldKind::Text, FieldValue::Text { value }) => {
                    if value.len() > MAX_TEXT_FIELD_BYTES {
                        return Err(invalid(format!(
                            "text is longer than {MAX_TEXT_FIELD_BYTES} bytes"
                        )));
                    }
                    if !field.has(pdfcraft_engine::field_flags::MULTILINE)
                        && value.contains(['\n', '\r'])
                    {
                        return Err(invalid(format!(
                            "{name:?} is a single-line field; remove the line breaks"
                        )));
                    }
                    FormValue::Text(value)
                }
                (FormFieldKind::CheckBox, FieldValue::Check { checked }) => {
                    FormValue::Check(checked)
                }
                (FormFieldKind::Radio, FieldValue::Radio { on_state }) => {
                    FormValue::Radio(on_state)
                }
                (FormFieldKind::Combo | FormFieldKind::List, FieldValue::Choice { values }) => {
                    if values.len() > MAX_LIST_ITEMS
                        || values.iter().any(|v| v.len() > MAX_CONTENTS_BYTES)
                    {
                        return Err(invalid("too many or too long choice values"));
                    }
                    FormValue::Choice(values)
                }
                (FormFieldKind::PushButton, _) => {
                    return Err(invalid(format!("{name:?} is a button; it has no value")));
                }
                (FormFieldKind::Signature, _) => {
                    return Err(invalid(format!(
                        "{name:?} is a signature field and cannot be set"
                    )));
                }
                (kind, _) => {
                    return Err(invalid(format!(
                        "{name:?} is a {} field; the value kind does not match",
                        field_kind_name(kind)
                    )));
                }
            };
            session
                .apply(
                    id,
                    Edit::SetFieldValue {
                        name,
                        value: engine_value,
                    },
                )
                .map_err(map_ops_error)?;
            edit_state(session, id)
        })
    }

    /// Reset fields to their default values: the named ones, or every fillable field with
    /// `None`. Read-only fields are never reset (a named one is `InvalidArgument`). `Some` of an
    /// empty list is `InvalidArgument` (it would silently do nothing); an unknown name is too.
    pub fn reset_form(&self, names: Option<Vec<String>>) -> Result<EditState, PdfError> {
        self.with_session(|session, id| {
            if let Some(n) = &names
                && (n.is_empty() || n.len() > MAX_RESET_NAMES)
            {
                return Err(invalid(
                    "give at least one field name, or None to reset every field",
                ));
            }
            let doc = document(session, id)?;
            if doc.form.is_empty() {
                return Err(invalid("the document has no form fields"));
            }
            // Upstream resets read-only fields too, which erases fixed text (a read-only field
            // without a /DV goes blank). Never do that: `None` means every fillable field, and a
            // named read-only field is refused.
            let names = match names {
                Some(n) => {
                    let wanted: HashSet<&str> = n.iter().map(String::as_str).collect();
                    if let Some(f) = doc
                        .form
                        .iter()
                        .find(|f| f.read_only() && wanted.contains(f.name.as_str()))
                    {
                        return Err(invalid(format!("field {:?} is read-only", f.name)));
                    }
                    n
                }
                None => {
                    let mut all: Vec<String> = doc
                        .form
                        .iter()
                        .filter(|f| !f.read_only())
                        .map(|f| f.name.clone())
                        .collect();
                    all.dedup();
                    if all.is_empty() {
                        return edit_state(session, id);
                    }
                    all
                }
            };
            session
                .apply(id, Edit::ResetForm { names: Some(names) })
                .map_err(map_ops_error)?;
            edit_state(session, id)
        })
    }

    /// A new PDF with copies of `pages` (0-based; sorted and de-duplicated, so the output keeps
    /// document order) of the current state, unsaved edits included.
    pub fn extract_pages(&self, pages: Vec<u32>) -> Result<Vec<u8>, PdfError> {
        self.with_session(|session, id| {
            let pages = check_pages(session, id, &pages)?;
            let bytes = session.extract(id, &pages).map_err(map_ops_error)?;
            check_output(bytes.len())?;
            Ok(into_vec(bytes))
        })
    }

    /// Split the current state into several PDFs, named `part-01.pdf`, `part-02.pdf`, ...
    /// `EveryN{n}`: `n >= 1`. `Before{pages}`: strictly ascending, each within `1..page_count`
    /// (page 0 is implicit). More than 1000 parts, or output over the size cap, is `TooLarge`.
    pub fn split(&self, spec: SplitSpec) -> Result<Vec<SplitPart>, PdfError> {
        self.with_session(|session, id| {
            let n = document(session, id)?.info.pages.len();
            let by = match spec {
                SplitSpec::EveryN { n: every } => {
                    if every == 0 {
                        return Err(invalid("n must be at least 1"));
                    }
                    SplitBy::PageCount(every as usize)
                }
                SplitSpec::Before { pages } => {
                    let mut prev: Option<u32> = None;
                    for &p in &pages {
                        if p == 0 || p as usize >= n {
                            return Err(invalid(format!("split point {p} must be within 1..{n}")));
                        }
                        if prev.is_some_and(|q| p <= q) {
                            return Err(invalid("split points must be strictly ascending"));
                        }
                        prev = Some(p);
                    }
                    SplitBy::Before(pages.iter().map(|&p| p as usize).collect())
                }
            };
            // Upstream's `split` builds (and clones) every part before returning, so the parts
            // are extracted one by one here and the running total is checked as it grows.
            let ranges = pdfcraft_engine::split_ranges(n, &by);
            let count = ranges.len();
            if count > MAX_SPLIT_PARTS {
                return Err(PdfError::TooLarge {
                    detail: format!("that would make {count} parts, limit is {MAX_SPLIT_PARTS}"),
                });
            }
            let digits = count.to_string().len().max(2);
            let mut total = 0usize;
            let mut out = Vec::with_capacity(count);
            for (i, range) in ranges.into_iter().enumerate() {
                let pages: Vec<usize> = range.collect();
                let bytes = session.extract(id, &pages).map_err(map_ops_error)?;
                total = total.saturating_add(bytes.len());
                check_output(total)?;
                out.push(SplitPart {
                    name: format!("part-{:0digits$}.pdf", i + 1),
                    bytes: into_vec(bytes),
                });
            }
            Ok(out)
        })
    }

    /// A PDF copy of the current state encrypted with AES-256. Needs an open password, an owner
    /// password or both (different, non-empty, at most 127 bytes each). Any restriction
    /// (printing other than High, changes other than AnyExceptExtract, no copying, no
    /// accessibility) needs an `owner_password`, else `InvalidArgument`. An already-encrypted
    /// document needs an explicit `owner_password` too. A document with a filled signature is
    /// `NotPermitted{"document is signed"}`; one opened without the owner password is
    /// `NotPermitted`. The open document is not changed (see the module comment). Without the
    /// `encrypt` cargo feature: `Unsupported`.
    pub fn save_protected(&self, spec: ProtectionSpec) -> Result<Vec<u8>, PdfError> {
        #[cfg(not(feature = "encrypt"))]
        {
            let _ = spec;
            self.with_session(|_, _| Err(encryption_disabled()))
        }
        #[cfg(feature = "encrypt")]
        {
            use zeroize::Zeroizing;
            self.with_session(|session, id| {
                let (encrypted, signed) = {
                    let doc = document(session, id)?;
                    (doc.info.encrypted, doc.is_signed())
                };
                let protection = protection_of(spec, encrypted)?;
                if signed {
                    return Err(PdfError::NotPermitted {
                        detail: "document is signed".into(),
                    });
                }
                // Best effort: these FFI-side copies are wiped on drop; the engine's own copies
                // (inside `Protection`) live until the scratch session is dropped below.
                let password = document(session, id)?.password.clone().map(Zeroizing::new);
                let source = session.save_full_bytes(id).map_err(map_ops_error)?;
                check_output(source.len())?;
                let mut scratch = pdfcraft_engine::Session::new();
                let sid = scratch
                    .open(
                        "protect.pdf",
                        None,
                        source,
                        password.as_deref().map(String::as_str),
                    )
                    .map_err(crate::map_open_error)?;
                drop(password);
                scratch
                    .apply(sid, Edit::Protect(protection))
                    .map_err(map_ops_error)?;
                let bytes = save_checked(&mut scratch, sid, true)?;
                // The scratch session (source copy, parsed document, passwords) goes first, so
                // the output is the only large buffer left and is moved out without a copy.
                drop(scratch);
                Ok(into_vec(bytes))
            })
        }
    }

    /// Remove the password protection of a document opened with its owner password. An undoable
    /// edit; the next save is a full rewrite without encryption. `InvalidArgument` if the
    /// document is not protected; a document opened with only the open password (restricted
    /// rights) is `NotPermitted`. Without the `encrypt` cargo feature: `Unsupported`.
    pub fn remove_protection(&self) -> Result<EditState, PdfError> {
        #[cfg(not(feature = "encrypt"))]
        {
            self.with_session(|_, _| Err(encryption_disabled()))
        }
        #[cfg(feature = "encrypt")]
        {
            self.with_session(|session, id| {
                session
                    .apply(id, Edit::RemoveProtection)
                    .map_err(map_ops_error)?;
                edit_state(session, id)
            })
        }
    }
}

#[cfg(not(feature = "encrypt"))]
fn encryption_disabled() -> PdfError {
    PdfError::Unsupported {
        detail: "encryption disabled in this build".into(),
    }
}

fn field_kind_name(k: FormFieldKind) -> &'static str {
    match k {
        FormFieldKind::Text => "text",
        FormFieldKind::CheckBox => "check box",
        FormFieldKind::Radio => "radio",
        FormFieldKind::PushButton => "button",
        FormFieldKind::Combo => "combo box",
        FormFieldKind::List => "list box",
        FormFieldKind::Signature => "signature",
    }
}

impl PdfSession {
    /// Test hook: lower the output size cap (bytes; `0` restores the default) for the calling
    /// thread. Debug builds only.
    #[cfg(debug_assertions)]
    pub fn debug_set_output_cap(&self, bytes: u64) {
        CAP_OVERRIDE
            .with(|c| c.set((bytes != 0).then(|| usize::try_from(bytes).unwrap_or(usize::MAX))));
    }
}

#[cfg(test)]
mod tests {
    #[test]
    fn encryption_available_follows_the_feature() {
        assert_eq!(super::encryption_available(), cfg!(feature = "encrypt"));
    }
}
