//! Host-side tests for `PdfSession` (run with `cargo test -p pdfa-ffi`). Every fixture PDF is
//! generated here by hand, with a correct xref table, so no repair path hides a parse problem.

#![allow(clippy::chunks_exact_to_as_chunks)]

use std::sync::Arc;

use pdfa_ffi::{LinkTarget, PdfError, PdfSession, Pt, Rect, Rotation};

// ---- fixtures -------------------------------------------------------------------------------

struct Spec {
    /// Page sizes in points; page `i` shows `texts[i]`.
    sizes: Vec<(u32, u32)>,
    texts: Vec<&'static str>,
    labels: bool,
    outline: bool,
    links: bool,
    /// Give page 0 a crop box with a non-zero origin.
    crop: bool,
}

impl Spec {
    /// Three pages of distinct sizes and text, with an /Info dictionary.
    fn three_pages() -> Self {
        Self {
            sizes: vec![(612, 792), (400, 500), (595, 842)],
            texts: vec!["Page one", "Page two", "Page three"],
            labels: false,
            outline: false,
            links: false,
            crop: false,
        }
    }
}

fn stream(body: &str) -> String {
    format!("<< /Length {} >>\nstream\n{body}\nendstream", body.len())
}

fn build_pdf(spec: &Spec) -> Vec<u8> {
    let n = spec.sizes.len();
    let page_obj = |i: usize| 3 + i;
    let content_obj = |i: usize| 3 + n + i;
    let font_obj = 3 + 2 * n;
    let info_obj = font_obj + 1;
    let mut next = info_obj + 1;
    let (outline_root, item0, item1) = (next, next + 1, next + 2);
    if spec.outline {
        next += 3;
    }
    let (link_page, link_uri) = (next, next + 1);

    let mut objs: Vec<(usize, String)> = Vec::new();
    let mut catalog = String::from("<< /Type /Catalog /Pages 2 0 R");
    if spec.outline {
        catalog.push_str(&format!(" /Outlines {outline_root} 0 R"));
    }
    if spec.labels {
        // i, ii, then A-1.
        catalog.push_str(" /PageLabels << /Nums [0 << /S /r >> 2 << /S /D /P (A-) >>] >>");
    }
    catalog.push_str(" >>");
    objs.push((1, catalog));
    let kids: Vec<String> = (0..n).map(|i| format!("{} 0 R", page_obj(i))).collect();
    objs.push((
        2,
        format!("<< /Type /Pages /Kids [{}] /Count {n} >>", kids.join(" ")),
    ));
    for (i, &(w, h)) in spec.sizes.iter().enumerate() {
        let annots = if spec.links && i == 0 {
            format!(" /Annots [{link_page} 0 R {link_uri} 0 R]")
        } else {
            String::new()
        };
        let crop = if spec.crop && i == 0 {
            " /CropBox [50 50 562 742]"
        } else {
            ""
        };
        objs.push((
            page_obj(i),
            format!(
                "<< /Type /Page /Parent 2 0 R /MediaBox [0 0 {w} {h}]{crop} /Resources << /Font << /F1 {font_obj} 0 R >> >> /Contents {} 0 R{annots} >>",
                content_obj(i)
            ),
        ));
    }
    for (i, &(_, h)) in spec.sizes.iter().enumerate() {
        let body = format!("BT /F1 24 Tf 72 {} Td ({}) Tj ET", h - 100, spec.texts[i]);
        objs.push((content_obj(i), stream(&body)));
    }
    objs.push((
        font_obj,
        "<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica /Encoding /WinAnsiEncoding >>".into(),
    ));
    objs.push((info_obj, "<< /Title (Fixture Title) /Author (Example) /Subject (Tests) /Keywords (a, b) /Producer (hand-made) >>".into()));
    if spec.outline {
        objs.push((
            outline_root,
            format!("<< /Type /Outlines /First {item0} 0 R /Last {item1} 0 R /Count 2 >>"),
        ));
        objs.push((item0, format!("<< /Title (Chapter 1) /Parent {outline_root} 0 R /Next {item1} 0 R /Dest [{} 0 R /Fit] >>", page_obj(0))));
        objs.push((item1, format!("<< /Title (Chapter 3) /Parent {outline_root} 0 R /Prev {item0} 0 R /Dest [{} 0 R /Fit] >>", page_obj(n - 1))));
    }
    if spec.links {
        objs.push((
            link_page,
            format!("<< /Type /Annot /Subtype /Link /Rect [100 700 200 720] /Border [0 0 0] /Dest [{} 0 R /Fit] >>", page_obj(1)),
        ));
        objs.push((
            link_uri,
            "<< /Type /Annot /Subtype /Link /Rect [100 600 300 620] /Border [0 0 0] /A << /S /URI /URI (https://example.com/) >> >>".into(),
        ));
    }

    objs.sort_by_key(|(num, _)| *num);
    let mut out: Vec<u8> = b"%PDF-1.7\n".to_vec();
    let mut offsets = Vec::new();
    for (num, body) in &objs {
        offsets.push(out.len());
        out.extend_from_slice(format!("{num} 0 obj\n{body}\nendobj\n").as_bytes());
    }
    let xref_at = out.len();
    out.extend_from_slice(format!("xref\n0 {}\n0000000000 65535 f \n", objs.len() + 1).as_bytes());
    for off in offsets {
        out.extend_from_slice(format!("{off:010} 00000 n \n").as_bytes());
    }
    out.extend_from_slice(
        format!("trailer\n<< /Size {} /Root 1 0 R /Info {info_obj} 0 R >>\nstartxref\n{xref_at}\n%%EOF\n", objs.len() + 1).as_bytes(),
    );
    out
}

fn three() -> Vec<u8> {
    build_pdf(&Spec::three_pages())
}

fn open(bytes: &[u8]) -> Arc<PdfSession> {
    PdfSession::open(bytes.to_vec(), None).expect("fixture should open")
}

fn widths(s: &PdfSession) -> Vec<u32> {
    s.page_infos()
        .unwrap()
        .iter()
        .map(|p| p.width_pt.round() as u32)
        .collect()
}

fn near(a: f32, b: f32, tol: f32) -> bool {
    (a - b).abs() <= tol
}

fn center(r: &Rect) -> Pt {
    Pt {
        x: (r.left + r.right) / 2.0,
        y: (r.top + r.bottom) / 2.0,
    }
}

// ---- open -----------------------------------------------------------------------------------

#[test]
fn opens_a_generated_document() {
    let s = open(&three());
    assert_eq!(s.page_infos().unwrap().len(), 3);
}

#[test]
fn garbage_and_truncated_input_are_errors_not_panics() {
    for bytes in [
        Vec::new(),
        b"not a pdf at all".to_vec(),
        three()[..40].to_vec(),
    ] {
        match PdfSession::open(bytes, None) {
            Err(PdfError::Panic { detail }) => panic!("panicked: {detail}"),
            Err(_) => {}
            Ok(_) => {}
        }
    }
}

#[test]
fn oversize_input_is_refused() {
    let big = vec![0u8; pdfa_ffi::MAX_INPUT_BYTES + 1];
    assert!(matches!(
        PdfSession::open(big, None),
        Err(PdfError::TooLarge { .. })
    ));
}

/// An AES-256 fixture made with the engine itself (the contract keeps `save_protected` gated).
fn encrypted_fixture() -> Option<Vec<u8>> {
    use pdfcraft_engine::{Edit, Protection, Session};
    let plain = three();
    let mut session = Session::new();
    let id = session.open("f.pdf", None, Arc::new(plain), None).ok()?;
    let protection = Protection {
        open_password: Some("s3cret".into()),
        ..Protection::default()
    };
    session.apply(id, Edit::Protect(protection)).ok()?;
    let bytes = session.save_full_bytes(id).ok()?;
    Some((*bytes).clone())
}

#[test]
fn password_required_wrong_and_correct() {
    let Some(bytes) = encrypted_fixture() else {
        eprintln!("SKIPPED: the engine could not produce an encrypted fixture");
        return;
    };
    assert!(matches!(
        PdfSession::open(bytes.clone(), None),
        Err(PdfError::PasswordRequired)
    ));
    assert!(matches!(
        PdfSession::open(bytes.clone(), Some("nope".into())),
        Err(PdfError::WrongPassword)
    ));
    let s = PdfSession::open(bytes, Some("s3cret".into())).expect("right password opens");
    assert_eq!(s.page_infos().unwrap().len(), 3);
    assert!(s.meta().unwrap().encrypted);
    // Rendering and text work on the decrypted document.
    assert!(s.render_tile(0, 1.0, 0, 0, 100, 100, false).is_ok());
    assert!(
        !s.search_page(0, "Page one".into(), false, false)
            .unwrap()
            .is_empty()
    );
}

// ---- read -----------------------------------------------------------------------------------

#[test]
fn meta_reports_info_and_permissions() {
    let bytes = three();
    let m = open(&bytes).meta().unwrap();
    assert_eq!(m.title, "Fixture Title");
    assert_eq!(m.author, "Example");
    assert_eq!(m.subject, "Tests");
    assert_eq!(m.keywords, "a, b");
    assert_eq!(m.page_count, 3);
    assert_eq!(m.file_size, bytes.len() as u64);
    assert!(m.pdf_version.starts_with("1."), "version {}", m.pdf_version);
    assert!(!m.encrypted);
    assert!(m.can_print && m.can_copy && m.can_modify && m.can_annotate);
}

#[test]
fn page_infos_have_sizes_and_labels() {
    let s = open(&three());
    let infos = s.page_infos().unwrap();
    let sizes: Vec<(f32, f32)> = infos.iter().map(|p| (p.width_pt, p.height_pt)).collect();
    assert_eq!(sizes, vec![(612.0, 792.0), (400.0, 500.0), (595.0, 842.0)]);
    let labels: Vec<&str> = infos.iter().map(|p| p.label.as_str()).collect();
    assert_eq!(labels, ["1", "2", "3"]);

    let labelled = open(&build_pdf(&Spec {
        labels: true,
        ..Spec::three_pages()
    }));
    let labels: Vec<String> = labelled
        .page_infos()
        .unwrap()
        .into_iter()
        .map(|p| p.label)
        .collect();
    assert_eq!(labels, ["i", "ii", "A-1"]);
}

#[test]
fn render_tile_is_premultiplied_rgba_of_the_requested_size() {
    let s = open(&three());
    let img = s.render_tile(0, 1.0, 0, 0, 612, 792, false).unwrap();
    assert_eq!((img.width, img.height), (612, 792));
    assert_eq!(img.rgba.len(), 612 * 792 * 4);
    // The page is opaque paper with dark text on it.
    assert!(
        img.rgba.chunks_exact(4).all(|p| p[3] == 255),
        "page pixels must be opaque"
    );
    assert!(
        img.rgba
            .chunks_exact(4)
            .any(|p| p[0] < 80 && p[1] < 80 && p[2] < 80),
        "text not drawn"
    );
    assert!(
        img.rgba
            .chunks_exact(4)
            .any(|p| p[0] > 240 && p[1] > 240 && p[2] > 240),
        "paper not drawn"
    );
}

#[test]
fn a_region_tile_matches_the_crop_of_a_full_render() {
    let s = open(&three());
    let full = s.render_tile(0, 1.0, 0, 0, 612, 792, false).unwrap();
    // Around the text line (baseline at y = 692 pt, view y = 100).
    let (x, y, w, h) = (60u32, 60u32, 200u32, 60u32);
    let tile = s.render_tile(0, 1.0, x, y, w, h, false).unwrap();
    assert_eq!((tile.width, tile.height), (w, h));
    assert_eq!(tile.rgba.len(), (w * h * 4) as usize);
    let mut differing = 0usize;
    let mut dark = 0usize;
    for row in 0..h as usize {
        let t = &tile.rgba[row * w as usize * 4..(row + 1) * w as usize * 4];
        let start = ((y as usize + row) * 612 + x as usize) * 4;
        let f = &full.rgba[start..start + w as usize * 4];
        differing += t.iter().zip(f).filter(|(a, b)| a.abs_diff(**b) > 2).count();
        dark += t.chunks_exact(4).filter(|p| p[0] < 80).count();
    }
    assert!(
        dark > 0,
        "the crop must contain text, or the comparison proves nothing"
    );
    assert_eq!(differing, 0, "tile and crop differ in {differing} bytes");
}

#[test]
fn preview_tiles_and_scaled_tiles_have_the_requested_size() {
    let s = open(&three());
    let thumb = s.render_tile(1, 0.25, 0, 0, 100, 125, true).unwrap();
    assert_eq!(
        (thumb.width, thumb.height, thumb.rgba.len()),
        (100, 125, 100 * 125 * 4)
    );
    // A tile hanging over the page edge is still the size asked for.
    let edge = s.render_tile(1, 1.0, 300, 400, 256, 256, false).unwrap();
    assert_eq!((edge.width, edge.height), (256, 256));
}

#[test]
fn render_guard_rails() {
    let s = open(&three());
    assert!(matches!(
        s.render_tile(9, 1.0, 0, 0, 10, 10, false),
        Err(PdfError::InvalidArgument { .. })
    ));
    assert!(matches!(
        s.render_tile(0, 0.0, 0, 0, 10, 10, false),
        Err(PdfError::InvalidArgument { .. })
    ));
    assert!(matches!(
        s.render_tile(0, f32::NAN, 0, 0, 10, 10, false),
        Err(PdfError::InvalidArgument { .. })
    ));
    assert!(matches!(
        s.render_tile(0, 1.0, 0, 0, 0, 10, false),
        Err(PdfError::InvalidArgument { .. })
    ));
    assert!(matches!(
        s.render_tile(0, 1.0, 5000, 0, 10, 10, false),
        Err(PdfError::InvalidArgument { .. })
    ));
    // Huge scale, and huge tiles: refused before rasterising.
    assert!(matches!(
        s.render_tile(0, 1000.0, 0, 0, 10, 10, false),
        Err(PdfError::TooLarge { .. })
    ));
    assert!(matches!(
        s.render_tile(0, 1.0, 0, 0, 8000, 8000, false),
        Err(PdfError::TooLarge { .. })
    ));
    assert!(matches!(
        s.render_tile(0, 1.0, 0, 0, 5000, 10, false),
        Err(PdfError::TooLarge { .. })
    ));
}

#[test]
fn search_finds_text_with_plausible_rects() {
    let s = open(&three());
    let hits = s.search_page(1, "Page two".into(), false, false).unwrap();
    assert_eq!(hits.len(), 1, "{hits:?}");
    let r = hits[0];
    // Page 1 is 400 x 500 pt; the text starts at x = 72 with a baseline at view y = 100.
    assert!(near(r.left, 72.0, 3.0), "{r:?}");
    assert!(r.right > r.left + 60.0 && r.right < r.left + 160.0, "{r:?}");
    assert!(
        r.top > 60.0 && r.bottom < 110.0 && r.bottom > r.top,
        "{r:?}"
    );
    assert!(r.right <= 400.0 && r.bottom <= 500.0);
    // Other pages and absent text.
    assert!(
        s.search_page(0, "Page two".into(), false, false)
            .unwrap()
            .is_empty()
    );
    assert!(
        s.search_page(1, "Zebra".into(), false, false)
            .unwrap()
            .is_empty()
    );
    assert!(
        s.search_page(1, "".into(), false, false)
            .unwrap()
            .is_empty()
    );
}

#[test]
fn search_honours_case_and_whole_word_flags() {
    let s = open(&three());
    assert_eq!(
        s.search_page(1, "page TWO".into(), false, false)
            .unwrap()
            .len(),
        1
    );
    assert!(
        s.search_page(1, "page TWO".into(), true, false)
            .unwrap()
            .is_empty()
    );
    assert_eq!(
        s.search_page(1, "Page two".into(), true, false)
            .unwrap()
            .len(),
        1
    );
    // A fragment of a word matches unless whole words are asked for.
    assert_eq!(
        s.search_page(1, "Page tw".into(), false, false)
            .unwrap()
            .len(),
        1
    );
    assert!(
        s.search_page(1, "Page tw".into(), false, true)
            .unwrap()
            .is_empty()
    );
    assert_eq!(
        s.search_page(1, "two".into(), false, true).unwrap().len(),
        1
    );
    assert!(matches!(
        s.search_page(7, "x".into(), false, false),
        Err(PdfError::InvalidArgument { .. })
    ));
}

#[test]
fn select_text_picks_a_word_or_a_drag() {
    let s = open(&three());
    let two = s.search_page(1, "two".into(), false, false).unwrap()[0];
    let word = s
        .select_text(1, center(&two), center(&two))
        .unwrap()
        .expect("a word under the point");
    assert_eq!(word.text, "two");
    assert!(!word.rects.is_empty());
    assert!(word.rects[0].left <= center(&two).x && word.rects[0].right >= center(&two).x);

    let page = s.search_page(1, "Page".into(), false, false).unwrap()[0];
    // From inside the first letter to inside the last one.
    let start = Pt {
        x: page.left + 1.0,
        y: center(&page).y,
    };
    let end = Pt {
        x: two.right - 1.0,
        y: center(&two).y,
    };
    let drag = s
        .select_text(1, start, end)
        .unwrap()
        .expect("a drag selection");
    assert_eq!(drag.text, "Page two");
    // Order of the two points does not matter.
    let back = s.select_text(1, end, start).unwrap().unwrap();
    assert_eq!(back.text, "Page two");

    // Blank paper selects nothing; a bad page is an argument error.
    assert_eq!(
        s.select_text(1, Pt { x: 380.0, y: 480.0 }, Pt { x: 380.0, y: 480.0 })
            .unwrap(),
        None
    );
    assert!(matches!(
        s.select_text(5, Pt { x: 0.0, y: 0.0 }, Pt { x: 1.0, y: 1.0 }),
        Err(PdfError::InvalidArgument { .. })
    ));
    assert!(matches!(
        s.select_text(
            1,
            Pt {
                x: f32::NAN,
                y: 0.0
            },
            Pt { x: 1.0, y: 1.0 }
        ),
        Err(PdfError::InvalidArgument { .. })
    ));
}

fn full_fixture() -> Vec<u8> {
    build_pdf(&Spec {
        outline: true,
        links: true,
        ..Spec::three_pages()
    })
}

#[test]
fn outline_rows_and_paging() {
    let s = open(&full_fixture());
    let all = s.outline(0, 100).unwrap();
    assert_eq!(all.rows.len(), 2);
    assert_eq!(
        (
            all.rows[0].title.as_str(),
            all.rows[0].page,
            all.rows[0].path.clone()
        ),
        ("Chapter 1", Some(0), vec![0])
    );
    assert_eq!(
        (
            all.rows[1].title.as_str(),
            all.rows[1].page,
            all.rows[1].path.clone()
        ),
        ("Chapter 3", Some(2), vec![1])
    );
    assert_eq!(all.next, None);

    let first = s.outline(0, 1).unwrap();
    assert_eq!(first.rows.len(), 1);
    assert_eq!(first.next, Some(1));
    let second = s.outline(first.next.unwrap(), 1).unwrap();
    assert_eq!(second.rows[0].title, "Chapter 3");
    assert_eq!(second.next, None);

    assert!(matches!(
        s.outline(0, 0),
        Err(PdfError::InvalidArgument { .. })
    ));
    // No outline at all is an empty page, not an error.
    let none = open(&three()).outline(0, 10).unwrap();
    assert!(none.rows.is_empty() && none.next.is_none());
}

#[test]
fn links_have_targets_and_view_space_rects() {
    let s = open(&full_fixture());
    let links = s.links(0).unwrap();
    assert_eq!(links.len(), 2, "{links:?}");
    let to_page = links
        .iter()
        .find(|l| matches!(l.target, LinkTarget::Page { .. }))
        .expect("page link");
    assert_eq!(to_page.target, LinkTarget::Page { page: 1 });
    // User rect [100 700 200 720] on a 792 pt page is view y 72..92.
    let r = to_page.rect;
    assert!(
        near(r.left, 100.0, 0.5) && near(r.right, 200.0, 0.5),
        "{r:?}"
    );
    assert!(near(r.top, 72.0, 0.5) && near(r.bottom, 92.0, 0.5), "{r:?}");
    let uri = links
        .iter()
        .find(|l| matches!(l.target, LinkTarget::Uri { .. }))
        .expect("uri link");
    assert_eq!(
        uri.target,
        LinkTarget::Uri {
            uri: "https://example.com/".into()
        }
    );
    assert!(
        near(uri.rect.top, 172.0, 0.5) && near(uri.rect.bottom, 192.0, 0.5),
        "{:?}",
        uri.rect
    );
    for l in &links {
        assert!(
            l.rect.left >= 0.0
                && l.rect.right <= 612.0
                && l.rect.top >= 0.0
                && l.rect.bottom <= 792.0
        );
        assert!(l.rect.left < l.rect.right && l.rect.top < l.rect.bottom);
    }
    assert!(s.links(1).unwrap().is_empty());
    assert!(matches!(s.links(3), Err(PdfError::InvalidArgument { .. })));
}

#[test]
fn link_rects_follow_page_rotation() {
    let s = open(&full_fixture());
    let after = s.rotate_pages(vec![0], Rotation::Cw90).unwrap();
    assert!(after.dirty);
    let info = s.page_infos().unwrap()[0].clone();
    assert_eq!((info.width_pt, info.height_pt), (792.0, 612.0));
    let links = s.links(0).unwrap();
    let to_page = links
        .iter()
        .find(|l| matches!(l.target, LinkTarget::Page { .. }))
        .unwrap();
    // Rotated a quarter turn clockwise, the same area is at x 700..720, y 100..200.
    let r = to_page.rect;
    assert!(
        near(r.left, 700.0, 0.5) && near(r.right, 720.0, 0.5),
        "{r:?}"
    );
    assert!(
        near(r.top, 100.0, 0.5) && near(r.bottom, 200.0, 0.5),
        "{r:?}"
    );
    for l in &links {
        assert!(l.rect.right <= 792.0 && l.rect.bottom <= 612.0);
    }
    // Search rects rotate with the page too.
    let hits = s.search_page(0, "Page one".into(), false, false).unwrap();
    assert_eq!(hits.len(), 1);
    assert!(
        hits[0].right <= 792.0 && hits[0].bottom <= 612.0,
        "{:?}",
        hits[0]
    );
}

// ---- edits ----------------------------------------------------------------------------------

#[test]
fn a_fresh_document_is_clean() {
    let st = open(&three()).state().unwrap();
    assert_eq!(
        (
            st.can_undo,
            st.can_redo,
            st.undo_label,
            st.page_count,
            st.dirty
        ),
        (false, false, None, 3, false)
    );
}

#[test]
fn rotate_changes_the_displayed_size_and_undo_redo_restore_it() {
    let s = open(&three());
    let st = s.rotate_pages(vec![1], Rotation::Cw90).unwrap();
    assert!(st.can_undo && !st.can_redo && st.dirty);
    assert!(st.undo_label.is_some());
    let infos = s.page_infos().unwrap();
    assert_eq!((infos[1].width_pt, infos[1].height_pt), (500.0, 400.0));
    assert_eq!(widths(&s), [612, 500, 595]);

    let st = s.undo().unwrap();
    assert!(!st.can_undo && st.can_redo && !st.dirty, "{st:?}");
    assert_eq!(widths(&s), [612, 400, 595]);
    let st = s.redo().unwrap();
    assert!(st.can_undo && !st.can_redo && st.dirty);
    assert_eq!(widths(&s), [612, 500, 595]);

    // A half turn keeps the size; a counter-clockwise quarter turn undoes the clockwise one.
    s.rotate_pages(vec![1], Rotation::Half).unwrap();
    assert_eq!(widths(&s), [612, 500, 595]);
    s.rotate_pages(vec![1], Rotation::Ccw90).unwrap();
    assert_eq!(widths(&s), [612, 400, 595]);
}

#[test]
fn rendering_follows_an_edit() {
    let s = open(&three());
    s.rotate_pages(vec![0], Rotation::Cw90).unwrap();
    // The page is now 792 wide: a tile at x = 700 exists and is paper, not an out-of-page error.
    let img = s.render_tile(0, 1.0, 700, 0, 92, 100, false).unwrap();
    assert_eq!((img.width, img.height), (92, 100));
    assert!(matches!(
        s.render_tile(0, 1.0, 800, 0, 10, 10, false),
        Err(PdfError::InvalidArgument { .. })
    ));
}

#[test]
fn delete_pages_and_undo() {
    let s = open(&three());
    let st = s.delete_pages(vec![1]).unwrap();
    assert_eq!((st.page_count, st.dirty, st.can_undo), (2, true, true));
    assert_eq!(widths(&s), [612, 595]);
    // Text of the surviving pages is still found.
    assert_eq!(
        s.search_page(1, "Page three".into(), false, false)
            .unwrap()
            .len(),
        1
    );
    let st = s.undo().unwrap();
    assert_eq!(st.page_count, 3);
    assert_eq!(widths(&s), [612, 400, 595]);
    let st = s.redo().unwrap();
    assert_eq!(st.page_count, 2);
}

#[test]
fn bad_edit_arguments_are_invalid_argument() {
    let s = open(&three());
    assert!(
        matches!(
            s.delete_pages(vec![0, 1, 2]),
            Err(PdfError::InvalidArgument { .. })
        ),
        "deleting every page"
    );
    assert!(matches!(
        s.delete_pages(vec![]),
        Err(PdfError::InvalidArgument { .. })
    ));
    assert!(matches!(
        s.delete_pages(vec![3]),
        Err(PdfError::InvalidArgument { .. })
    ));
    assert!(matches!(
        s.rotate_pages(vec![9], Rotation::Half),
        Err(PdfError::InvalidArgument { .. })
    ));
    assert!(matches!(
        s.move_pages(vec![0], 4),
        Err(PdfError::InvalidArgument { .. })
    ));
    assert!(matches!(
        s.duplicate_pages(vec![]),
        Err(PdfError::InvalidArgument { .. })
    ));
    assert!(matches!(
        s.insert_blank(4, 100.0, 100.0),
        Err(PdfError::InvalidArgument { .. })
    ));
    assert!(matches!(
        s.insert_blank(0, f32::NAN, 100.0),
        Err(PdfError::InvalidArgument { .. })
    ));
    assert!(matches!(
        s.insert_blank(0, 100.0, 0.0),
        Err(PdfError::InvalidArgument { .. })
    ));
    assert!(
        matches!(s.undo(), Err(PdfError::InvalidArgument { .. })),
        "nothing to undo"
    );
    assert!(
        matches!(s.redo(), Err(PdfError::InvalidArgument { .. })),
        "nothing to redo"
    );
    // None of the failures left a trace.
    let st = s.state().unwrap();
    assert_eq!((st.can_undo, st.dirty, st.page_count), (false, false, 3));
}

#[test]
fn move_pages_reorders() {
    let s = open(&three());
    let st = s.move_pages(vec![0], 2).unwrap();
    assert_eq!(st.page_count, 3);
    assert_eq!(widths(&s), [400, 595, 612]);
    assert_eq!(
        s.search_page(2, "Page one".into(), false, false)
            .unwrap()
            .len(),
        1
    );
    s.undo().unwrap();
    assert_eq!(widths(&s), [612, 400, 595]);
    // Moving two pages keeps their relative order.
    s.move_pages(vec![2, 1], 0).unwrap();
    assert_eq!(widths(&s), [400, 595, 612]);
}

#[test]
fn move_pages_to_index_is_an_index_in_the_result() {
    let s = open(&three());
    // Pages [0, 1] of 3: valid destinations are 0..=1.
    assert!(matches!(
        s.move_pages(vec![0, 1], 2),
        Err(PdfError::InvalidArgument { .. })
    ));
    assert!(!s.state().unwrap().dirty);
    s.move_pages(vec![0, 1], 1).unwrap();
    assert_eq!(widths(&s), [595, 612, 400], "moved pages land at 1 and 2");
    s.undo().unwrap();
    s.move_pages(vec![0, 1], 0).unwrap();
    assert_eq!(widths(&s), [612, 400, 595]);
    // One page: 0..=2. Duplicated indices count once.
    assert!(s.move_pages(vec![1, 1], 2).is_ok());
    assert!(matches!(
        s.move_pages(vec![0, 1, 2], 1),
        Err(PdfError::InvalidArgument { .. })
    ));
    assert!(s.move_pages(vec![0, 1, 2], 0).is_ok());
}

#[test]
fn duplicate_pages_copies_after_the_last() {
    let s = open(&three());
    let st = s.duplicate_pages(vec![0]).unwrap();
    assert_eq!(st.page_count, 4);
    assert_eq!(widths(&s), [612, 612, 400, 595]);
    assert_eq!(
        s.search_page(1, "Page one".into(), false, false)
            .unwrap()
            .len(),
        1
    );
    assert_eq!(s.undo().unwrap().page_count, 3);
    assert_eq!(s.redo().unwrap().page_count, 4);
}

#[test]
fn insert_blank_adds_a_page_of_the_given_size() {
    let s = open(&three());
    let st = s.insert_blank(1, 200.0, 300.0).unwrap();
    assert_eq!(st.page_count, 4);
    let infos = s.page_infos().unwrap();
    assert_eq!((infos[1].width_pt, infos[1].height_pt), (200.0, 300.0));
    assert_eq!(widths(&s), [612, 200, 400, 595]);
    assert!(
        s.search_page(1, "Page".into(), false, false)
            .unwrap()
            .is_empty()
    );
    // Appending is allowed.
    assert_eq!(s.insert_blank(4, 100.0, 100.0).unwrap().page_count, 5);
    assert_eq!(s.undo().unwrap().page_count, 4);
    assert_eq!(s.undo().unwrap().page_count, 3);
    assert!(!s.state().unwrap().dirty);
}

#[test]
fn save_full_round_trips_and_clears_dirty() {
    let original = three();
    let s = open(&original);
    s.delete_pages(vec![1]).unwrap();
    assert!(s.state().unwrap().dirty);
    let saved = s.save_full().unwrap();
    assert!(saved.starts_with(b"%PDF"));
    let st = s.state().unwrap();
    assert!(
        st.dirty && st.can_undo,
        "saving alone must not clear dirty: {st:?}"
    );
    let st = s.confirm_saved().unwrap();
    assert!(
        !st.dirty && st.can_undo,
        "confirm_saved clears dirty and keeps the history: {st:?}"
    );
    let again = open(&saved);
    assert_eq!(again.page_infos().unwrap().len(), 2);
    assert_eq!(widths(&again), [612, 595]);
    assert_eq!(
        again
            .search_page(1, "Page three".into(), false, false)
            .unwrap()
            .len(),
        1
    );
    // The session is still usable after a save.
    assert_eq!(s.undo().unwrap().page_count, 3);
    assert!(
        s.state().unwrap().dirty,
        "undo after confirm_saved is a change again"
    );
}

#[test]
fn save_incremental_appends_a_revision() {
    let original = three();
    let s = open(&original);
    s.rotate_pages(vec![0], Rotation::Cw90).unwrap();
    let saved = s.save_incremental().unwrap();
    assert!(saved.len() > original.len());
    assert!(
        saved.starts_with(&original),
        "an incremental save keeps the original bytes"
    );
    assert!(
        s.state().unwrap().dirty,
        "a failed write must not hide unsaved changes"
    );
    assert!(!s.confirm_saved().unwrap().dirty);
    let again = open(&saved);
    assert_eq!(again.page_infos().unwrap().len(), 3);
    assert_eq!(widths(&again), [792, 400, 595]);

    // A second edit appends to the first save.
    s.insert_blank(3, 100.0, 100.0).unwrap();
    let second = s.save_incremental().unwrap();
    assert!(second.starts_with(&saved));
    assert_eq!(open(&second).page_infos().unwrap().len(), 4);
}

#[test]
fn confirm_saved_needs_a_current_save() {
    let s = open(&three());
    assert!(
        matches!(s.confirm_saved(), Err(PdfError::InvalidArgument { .. })),
        "nothing saved yet"
    );
    s.rotate_pages(vec![0], Rotation::Half).unwrap();
    s.save_incremental().unwrap();
    // An edit after the save: the written file is stale, so it cannot be confirmed.
    s.rotate_pages(vec![1], Rotation::Half).unwrap();
    assert!(matches!(
        s.confirm_saved(),
        Err(PdfError::InvalidArgument { .. })
    ));
    assert!(s.state().unwrap().dirty);
    // Save again, confirm, and the confirmation is single-use.
    s.save_full().unwrap();
    assert!(!s.confirm_saved().unwrap().dirty);
    assert!(matches!(
        s.confirm_saved(),
        Err(PdfError::InvalidArgument { .. })
    ));
}

#[test]
fn saving_without_edits_returns_a_valid_document() {
    let s = open(&three());
    for bytes in [s.save_incremental().unwrap(), s.save_full().unwrap()] {
        assert_eq!(open(&bytes).page_infos().unwrap().len(), 3);
    }
}

// ---- panics and concurrency -----------------------------------------------------------------

#[cfg(debug_assertions)]
#[test]
fn a_panic_maps_to_pdf_error_and_the_session_stays_usable() {
    let s = open(&three());
    match s.debug_panic_inside_lock() {
        Err(PdfError::Panic { detail }) => assert!(
            detail.contains("debug_panic_inside_lock requested"),
            "detail: {detail}"
        ),
        other => panic!("expected PdfError::Panic, got {other:?}"),
    }
    // The lock was poisoned by the panic; every call recovers it.
    assert_eq!(s.page_infos().unwrap().len(), 3);
    assert_eq!(
        s.rotate_pages(vec![0], Rotation::Half).unwrap().page_count,
        3
    );
    assert!(s.render_tile(0, 1.0, 0, 0, 50, 50, false).is_ok());
    assert_eq!(
        s.search_page(0, "Page one".into(), false, false)
            .unwrap()
            .len(),
        1
    );
}

#[test]
fn concurrent_readers_and_an_editor_do_not_deadlock_or_panic() {
    let s = open(&three());
    let handles: Vec<_> = (0..4)
        .map(|t| {
            let s = Arc::clone(&s);
            std::thread::spawn(move || {
                for i in 0..6 {
                    match (t + i) % 3 {
                        0 => assert!(s.render_tile(0, 0.5, 0, 0, 100, 100, true).is_ok()),
                        1 => assert!(s.search_page(0, "Page".into(), false, false).is_ok()),
                        _ => assert!(s.rotate_pages(vec![2], Rotation::Half).is_ok()),
                    }
                }
            })
        })
        .collect();
    for h in handles {
        h.join().expect("worker thread panicked");
    }
    assert_eq!(s.page_infos().unwrap().len(), 3);
}

// ---- review additions -------------------------------------------------------------------------

#[test]
fn copy_forbidden_documents_refuse_select_text_with_a_fixed_detail() {
    use pdfcraft_engine::{Edit, Protection, Session};
    let mut session = Session::new();
    let id = session
        .open("f.pdf", None, Arc::new(three()), None)
        .unwrap();
    let protection = Protection {
        open_password: Some("u".into()),
        permissions_password: Some("o".into()),
        copy: false,
        ..Protection::default()
    };
    if session.apply(id, Edit::Protect(protection)).is_err() {
        eprintln!("SKIPPED: could not build a restricted fixture");
        return;
    }
    let bytes = (*session.save_full_bytes(id).unwrap()).clone();
    let s = PdfSession::open(bytes, Some("u".into())).unwrap();
    assert!(!s.meta().unwrap().can_copy);
    match s.select_text(0, Pt { x: 100.0, y: 90.0 }, Pt { x: 100.0, y: 90.0 }) {
        Err(PdfError::NotPermitted { detail }) => assert_eq!(detail, "copy not permitted"),
        other => panic!("expected Unsupported, got {other:?}"),
    }
    // Searching and rendering stay available.
    assert!(
        !s.search_page(0, "Page one".into(), false, false)
            .unwrap()
            .is_empty()
    );
}

#[test]
fn text_on_a_rotated_page_is_in_view_space() {
    let s = open(&three());
    s.rotate_pages(vec![1], Rotation::Cw90).unwrap(); // 400 x 500 becomes 500 x 400
    let hits = s.search_page(1, "Page two".into(), false, false).unwrap();
    assert_eq!(hits.len(), 1);
    let r = hits[0];
    assert!(
        r.left >= 0.0 && r.top >= 0.0 && r.right <= 500.0 && r.bottom <= 400.0,
        "{r:?}"
    );
    // Turned a quarter clockwise, the line runs downwards: taller than wide.
    assert!(r.bottom - r.top > r.right - r.left, "{r:?}");
    let two = s.search_page(1, "two".into(), false, false).unwrap()[0];
    let word = s.select_text(1, center(&two), center(&two)).unwrap();
    assert_eq!(word.map(|w| w.text), Some("two".to_string()));
}

#[test]
fn a_crop_box_with_a_non_zero_origin_shifts_view_space() {
    let s = open(&build_pdf(&Spec {
        crop: true,
        ..Spec::three_pages()
    }));
    let info = s.page_infos().unwrap()[0].clone();
    assert_eq!((info.width_pt, info.height_pt), (512.0, 692.0));
    let hits = s.search_page(0, "Page one".into(), false, false).unwrap();
    assert_eq!(hits.len(), 1);
    let r = hits[0];
    // Text at user (72, 692), crop origin (50, 50), crop top 742: view x 22, baseline y 50.
    assert!(near(r.left, 22.0, 3.0), "{r:?}");
    assert!(r.top > 15.0 && r.bottom < 60.0, "{r:?}");
    let one = s.search_page(0, "one".into(), false, false).unwrap()[0];
    assert_eq!(
        s.select_text(0, center(&one), center(&one))
            .unwrap()
            .map(|w| w.text),
        Some("one".to_string())
    );
    // The tile at the text is dark, i.e. render and text agree about where the text is.
    let tile = s
        .render_tile(0, 1.0, r.left as u32, r.top as u32, 40, 20, false)
        .unwrap();
    assert!(
        tile.rgba.chunks_exact(4).any(|p| p[0] < 100),
        "no ink where the text layer says the text is"
    );
}

#[test]
fn a_tile_past_the_page_edge_is_paper_beyond_it() {
    let s = open(&three());
    // Page 1 is 400 x 500: a 256 x 256 tile at (300, 400) covers 100 x 100 of page.
    let t = s.render_tile(1, 1.0, 300, 400, 256, 256, false).unwrap();
    assert_eq!(t.rgba.len(), 256 * 256 * 4);
    let px = |x: usize, y: usize| &t.rgba[(y * 256 + x) * 4..(y * 256 + x) * 4 + 4];
    assert_eq!(px(10, 10), [255, 255, 255, 255]);
    assert_eq!(
        px(200, 200)[3],
        255,
        "outside the page the buffer is still opaque paper"
    );
    assert_eq!(px(255, 255), [255, 255, 255, 255]);
}

#[test]
fn non_finite_inputs_are_invalid_arguments() {
    let s = open(&three());
    for scale in [f32::NAN, f32::INFINITY, f32::NEG_INFINITY, -1.0] {
        assert!(
            matches!(
                s.render_tile(0, scale, 0, 0, 10, 10, false),
                Err(PdfError::InvalidArgument { .. })
            ),
            "scale {scale}"
        );
    }
    let bad = Pt {
        x: f32::INFINITY,
        y: 0.0,
    };
    let ok = Pt { x: 1.0, y: 1.0 };
    assert!(matches!(
        s.select_text(0, bad, ok),
        Err(PdfError::InvalidArgument { .. })
    ));
    assert!(matches!(
        s.select_text(
            0,
            ok,
            Pt {
                x: 0.0,
                y: f32::NAN
            }
        ),
        Err(PdfError::InvalidArgument { .. })
    ));
    for (w, h) in [
        (f32::INFINITY, 100.0),
        (100.0, f32::NAN),
        (f32::NEG_INFINITY, 100.0),
    ] {
        assert!(
            matches!(
                s.insert_blank(0, w, h),
                Err(PdfError::InvalidArgument { .. })
            ),
            "{w}x{h}"
        );
    }
    assert!(!s.state().unwrap().dirty);
}

#[test]
fn links_never_report_a_stale_page_target() {
    let s = open(&full_fixture());
    s.delete_pages(vec![1]).unwrap(); // the link's destination
    for l in s.links(0).unwrap() {
        if let LinkTarget::Page { page } = l.target {
            assert!(page < 2, "stale page target {page}");
        }
    }
}

#[cfg(debug_assertions)]
#[test]
fn pool_waits_time_out_and_the_session_recovers() {
    let s = open(&three());
    s.debug_set_wait_ms(1); // no waiting at all
    match s.search_page(0, "Page".into(), false, false) {
        Err(PdfError::Render { detail }) => assert_eq!(detail, "text timed out"),
        other => panic!("expected a text timeout, got {other:?}"),
    }
    match s.render_tile(0, 1.0, 0, 0, 100, 100, false) {
        Err(PdfError::Render { detail }) => assert_eq!(detail, "render timed out"),
        other => panic!("expected a render timeout, got {other:?}"),
    }
    // State calls are unaffected, and normal waits work again afterwards.
    assert_eq!(s.state().unwrap().page_count, 3);
    s.debug_set_wait_ms(0);
    assert_eq!(
        s.search_page(0, "Page".into(), false, false).unwrap().len(),
        1
    );
    assert!(s.render_tile(0, 1.0, 0, 0, 100, 100, false).is_ok());
}
