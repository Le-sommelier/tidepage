//! Host-side tests for the session-bound operations (markup, forms, extract, split,
//! protection). Every fixture PDF is generated here by hand with a correct xref table.
//! Run with `cargo test -p pdfa-ffi` and again with `--features encrypt`.

#![allow(clippy::type_complexity, clippy::vec_init_then_push)]

use std::sync::Arc;

use pdfa_ffi::{
    FieldInfo, FieldKind, FieldValue, MarkupKind, NewMarkup, PdfError, PdfSession, Pt, Rect,
    RenderedImage, SplitSpec, Stroke,
};

// ---- fixtures -------------------------------------------------------------------------------

/// Serialises a PDF from object bodies; object `i + 1` is `bodies[i]`, root is object 1.
fn pdf(bodies: &[String]) -> Vec<u8> {
    let mut out = b"%PDF-1.7\n".to_vec();
    let mut offsets = Vec::new();
    for (i, body) in bodies.iter().enumerate() {
        offsets.push(out.len());
        out.extend_from_slice(format!("{} 0 obj\n{body}\nendobj\n", i + 1).as_bytes());
    }
    let xref = out.len();
    out.extend_from_slice(
        format!("xref\n0 {}\n0000000000 65535 f \n", bodies.len() + 1).as_bytes(),
    );
    for off in offsets {
        out.extend_from_slice(format!("{off:010} 00000 n \n").as_bytes());
    }
    out.extend_from_slice(
        format!(
            "trailer\n<< /Size {} /Root 1 0 R >>\nstartxref\n{xref}\n%%EOF\n",
            bodies.len() + 1
        )
        .as_bytes(),
    );
    out
}

fn stream(dict: &str, body: &str) -> String {
    format!(
        "<< {dict} /Length {} >>\nstream\n{body}\nendstream",
        body.len()
    )
}

/// `n` pages; page `i` has `page_extra(i)` extra dictionary entries and `sizes[i % len]` as MediaBox.
fn pages_pdf(n: usize, sizes: &[(u32, u32)], page_extra: impl Fn(usize) -> String) -> Vec<u8> {
    let font = 3 + 2 * n;
    let mut bodies = vec![
        "<< /Type /Catalog /Pages 2 0 R >>".to_string(),
        format!(
            "<< /Type /Pages /Kids [{}] /Count {n} >>",
            (0..n)
                .map(|i| format!("{} 0 R", 3 + i))
                .collect::<Vec<_>>()
                .join(" ")
        ),
    ];
    for i in 0..n {
        let (w, h) = sizes[i % sizes.len()];
        bodies.push(format!(
            "<< /Type /Page /Parent 2 0 R /MediaBox [0 0 {w} {h}] /Resources << /Font << /F1 {font} 0 R >> >> /Contents {} 0 R {} >>",
            3 + n + i,
            page_extra(i)
        ));
    }
    for i in 0..n {
        let (_, h) = sizes[i % sizes.len()];
        bodies.push(stream(
            "",
            &format!("BT /F1 24 Tf 72 {} Td (Page {}) Tj ET", h - 100, i + 1),
        ));
    }
    bodies.push(
        "<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica /Encoding /WinAnsiEncoding >>"
            .to_string(),
    );
    pdf(&bodies)
}

fn three() -> Vec<u8> {
    pages_pdf(3, &[(612, 792), (400, 500), (595, 842)], |_| String::new())
}

/// One page, MediaBox 200 x 100 with /Rotate 90: displayed 100 wide, 200 tall.
fn rotated() -> Vec<u8> {
    pages_pdf(1, &[(200, 100)], |_| "/Rotate 90".into())
}

/// One page, MediaBox 400 x 400 with CropBox [100 100 300 300]: displayed 200 x 200, user origin
/// of the visible area at (100, 100).
fn offset_crop() -> Vec<u8> {
    pages_pdf(1, &[(400, 400)], |_| "/CropBox [100 100 300 300]".into())
}

/// An AcroForm page (300 x 400, `rotate` degrees) with a text field (MaxLen 10, value "Bob",
/// default "Anon"), a check box, a two-button radio group (A, B), a combo box, a read-only text
/// field and a signature field.
fn form(rotate: u32) -> Vec<u8> {
    let da = "/DA (/Helv 12 Tf 0 g)";
    let bodies = vec![
        "<< /Type /Catalog /Pages 2 0 R /AcroForm << /Fields [5 0 R 6 0 R 7 0 R 10 0 R 11 0 R 12 0 R] /DA (/Helv 0 Tf 0 g) /DR << /Font << /Helv 4 0 R >> >> >> >>".to_string(),
        "<< /Type /Pages /Kids [3 0 R] /Count 1 >>".to_string(),
        format!(
            "<< /Type /Page /Parent 2 0 R /MediaBox [0 0 300 400] /Rotate {rotate} /Resources << /Font << /Helv 4 0 R >> >> /Contents 13 0 R /Annots [5 0 R 6 0 R 8 0 R 9 0 R 10 0 R 11 0 R 12 0 R] >>"
        ),
        "<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica /Encoding /WinAnsiEncoding >>".to_string(),
        format!("<< /Type /Annot /Subtype /Widget /FT /Tx /T (name) /Rect [20 340 180 360] /F 4 /P 3 0 R {da} /MaxLen 10 /V (Bob) /DV (Anon) >>"),
        "<< /Type /Annot /Subtype /Widget /FT /Btn /T (agree) /Rect [20 300 40 320] /F 4 /P 3 0 R /AS /Off /V /Off /DV /Off /AP << /N << /Yes 14 0 R /Off 15 0 R >> >> >>".to_string(),
        "<< /FT /Btn /Ff 32768 /T (choice) /Kids [8 0 R 9 0 R] >>".to_string(),
        "<< /Type /Annot /Subtype /Widget /Parent 7 0 R /Rect [20 260 40 280] /F 4 /P 3 0 R /AS /Off /AP << /N << /A 14 0 R /Off 15 0 R >> >> >>".to_string(),
        "<< /Type /Annot /Subtype /Widget /Parent 7 0 R /Rect [60 260 80 280] /F 4 /P 3 0 R /AS /Off /AP << /N << /B 14 0 R /Off 15 0 R >> >> >>".to_string(),
        format!("<< /Type /Annot /Subtype /Widget /FT /Ch /Ff 131072 /T (color) /Opt [[(r) (Red)] [(g) (Green)]] /Rect [20 200 120 220] /F 4 /P 3 0 R {da} >>"),
        format!("<< /Type /Annot /Subtype /Widget /FT /Tx /Ff 1 /T (locked) /Rect [20 150 180 170] /F 4 /P 3 0 R {da} /V (fixed) >>"),
        "<< /Type /Annot /Subtype /Widget /FT /Sig /T (sig) /Rect [20 80 180 120] /F 4 /P 3 0 R >>".to_string(),
        stream("", "BT /Helv 14 Tf 20 380 Td (Form) Tj ET"),
        stream("/Type /XObject /Subtype /Form /BBox [0 0 20 20]", "0 0 20 20 re f"),
        stream("/Type /XObject /Subtype /Form /BBox [0 0 20 20]", ""),
    ];
    pdf(&bodies)
}

fn open(bytes: &[u8]) -> Arc<PdfSession> {
    PdfSession::open(bytes.to_vec(), None).expect("fixture should open")
}

fn reopen(s: &PdfSession) -> Arc<PdfSession> {
    open(&s.save_full().expect("save_full"))
}

// ---- rendering helpers ----------------------------------------------------------------------

fn render(s: &PdfSession, page: u32) -> RenderedImage {
    let info = &s.page_infos().unwrap()[page as usize];
    s.render_tile(
        page,
        1.0,
        0,
        0,
        info.width_pt.round() as u32,
        info.height_pt.round() as u32,
        false,
    )
    .unwrap()
}

fn px(img: &RenderedImage, x: u32, y: u32) -> [u8; 3] {
    let i = ((y * img.width + x) * 4) as usize;
    [img.rgba[i], img.rgba[i + 1], img.rgba[i + 2]]
}

fn is_red(p: [u8; 3]) -> bool {
    p[0] > 200 && p[1] < 80 && p[2] < 80
}

fn is_yellow(p: [u8; 3]) -> bool {
    p[0] > 200 && p[1] > 200 && p[2] < 90
}

fn is_paper(p: [u8; 3]) -> bool {
    p.iter().all(|&c| c > 245)
}

/// Bounding box (x0, y0, x1, y1 inclusive) of the pixels matching `pred`.
fn bbox(img: &RenderedImage, pred: impl Fn([u8; 3]) -> bool) -> Option<(u32, u32, u32, u32)> {
    let mut b: Option<(u32, u32, u32, u32)> = None;
    for y in 0..img.height {
        for x in 0..img.width {
            if pred(px(img, x, y)) {
                b = Some(match b {
                    None => (x, y, x, y),
                    Some((a, c, d, e)) => (a.min(x), c.min(y), d.max(x), e.max(y)),
                });
            }
        }
    }
    b
}

fn ink(page: u32, rgb: u32, width_pt: f32, strokes: Vec<Vec<(f32, f32)>>) -> NewMarkup {
    NewMarkup {
        page,
        kind: MarkupKind::Ink,
        rgb,
        opacity: 1.0,
        width_pt,
        strokes: strokes
            .into_iter()
            .map(|s| Stroke {
                points: s.into_iter().map(|(x, y)| Pt { x, y }).collect(),
            })
            .collect(),
        rects: vec![],
        contents: String::new(),
        author: "Example".into(),
    }
}

fn assert_rect(got: Rect, want: Rect) {
    let close = |a: f32, b: f32| (a - b).abs() < 0.01;
    assert!(
        close(got.left, want.left)
            && close(got.top, want.top)
            && close(got.right, want.right)
            && close(got.bottom, want.bottom),
        "{got:?} vs {want:?}"
    );
}

fn rect(left: f32, top: f32, right: f32, bottom: f32) -> Rect {
    Rect {
        left,
        top,
        right,
        bottom,
    }
}

fn highlight(page: u32, rgb: u32, rects: Vec<Rect>) -> NewMarkup {
    NewMarkup {
        page,
        kind: MarkupKind::Highlight,
        rgb,
        opacity: 1.0,
        width_pt: 0.0,
        strokes: vec![],
        rects,
        contents: String::new(),
        author: "Example".into(),
    }
}

fn note(page: u32, rgb: u32, at: Rect, text: &str) -> NewMarkup {
    NewMarkup {
        page,
        kind: MarkupKind::Note,
        rgb,
        opacity: 1.0,
        width_pt: 0.0,
        strokes: vec![],
        rects: vec![at],
        contents: text.into(),
        author: "Example".into(),
    }
}

fn invalid_arg<T: std::fmt::Debug>(r: Result<T, PdfError>) -> String {
    match r {
        Err(PdfError::InvalidArgument { detail }) => detail,
        other => panic!("expected InvalidArgument, got {other:?}"),
    }
}

// ---- add_markup: ink ------------------------------------------------------------------------

/// A stroke across view (100..200, 200) on a plain page lands there, before and after a save.
#[test]
fn ink_lands_where_the_view_space_input_said_and_persists() {
    let s = open(&three());
    let state = s
        .add_markup(ink(
            0,
            0xFF0000,
            8.0,
            vec![vec![(100.0, 200.0), (200.0, 200.0)]],
        ))
        .unwrap();
    assert!(state.dirty && state.can_undo);
    for session in [s.clone(), reopen(&s)] {
        let img = render(&session, 0);
        assert!(
            is_red(px(&img, 150, 200)),
            "stroke missing: {:?}",
            px(&img, 150, 200)
        );
        assert!(is_paper(px(&img, 150, 300)));
        assert!(is_paper(px(&img, 150, 150)));
        let (x0, y0, x1, y1) = bbox(&img, is_red).unwrap();
        assert!(
            (94..=100).contains(&x0) && (200..=206).contains(&x1),
            "x range {x0}..{x1}"
        );
        assert!(
            (194..=198).contains(&y0) && (202..=206).contains(&y1),
            "y range {y0}..{y1}"
        );
    }
}

/// On a /Rotate 90 page (displayed 100 x 200) a stroke at view y=30 stays at y=30 and is not mirrored.
#[test]
fn ink_on_a_rotated_page_lands_in_view_space() {
    let s = open(&rotated());
    assert_eq!(s.page_infos().unwrap()[0].width_pt.round() as u32, 100);
    s.add_markup(ink(
        0,
        0xFF0000,
        6.0,
        vec![vec![(10.0, 30.0), (60.0, 30.0)]],
    ))
    .unwrap();
    for session in [s.clone(), reopen(&s)] {
        let img = render(&session, 0);
        assert!(is_red(px(&img, 35, 30)), "{:?}", px(&img, 35, 30));
        assert!(is_paper(px(&img, 35, 170)), "mirrored vertically");
        assert!(is_paper(px(&img, 85, 30)), "stroke too long");
        let (x0, y0, x1, y1) = bbox(&img, is_red).unwrap();
        assert!(
            (7..=12).contains(&x0) && (58..=63).contains(&x1),
            "x range {x0}..{x1}"
        );
        assert!(
            (25..=28).contains(&y0) && (32..=35).contains(&y1),
            "y range {y0}..{y1}"
        );
    }
}

/// A CropBox with a non-zero origin shifts user space; the stroke must still land in view space.
#[test]
fn ink_on_an_offset_crop_box_lands_in_view_space() {
    let s = open(&offset_crop());
    s.add_markup(ink(
        0,
        0xFF0000,
        6.0,
        vec![vec![(20.0, 20.0), (80.0, 20.0)]],
    ))
    .unwrap();
    for session in [s.clone(), reopen(&s)] {
        let img = render(&session, 0);
        assert!(is_red(px(&img, 50, 20)), "{:?}", px(&img, 50, 20));
        assert!(is_paper(px(&img, 50, 180)));
        let (x0, y0, x1, y1) = bbox(&img, is_red).unwrap();
        assert!(
            (17..=22).contains(&x0) && (78..=83).contains(&x1),
            "x range {x0}..{x1}"
        );
        assert!(
            (16..=18).contains(&y0) && (22..=24).contains(&y1),
            "y range {y0}..{y1}"
        );
    }
}

#[test]
fn undo_removes_the_markup() {
    let s = open(&three());
    s.add_markup(ink(
        0,
        0xFF0000,
        8.0,
        vec![vec![(100.0, 200.0), (200.0, 200.0)]],
    ))
    .unwrap();
    assert!(!is_paper(px(&render(&s, 0), 150, 200)));
    let state = s.undo().unwrap();
    assert!(!state.can_undo && state.can_redo && !state.dirty);
    assert!(is_paper(px(&render(&s, 0), 150, 200)));
    s.redo().unwrap();
    assert!(is_red(px(&render(&s, 0), 150, 200)));
}

#[test]
fn ink_with_several_strokes_and_opacity() {
    let s = open(&three());
    let mut m = ink(
        1,
        0x0000FF,
        10.0,
        vec![
            vec![(50.0, 50.0), (150.0, 50.0)],
            vec![(50.0, 100.0), (150.0, 100.0)],
        ],
    );
    m.opacity = 0.5;
    s.add_markup(m).unwrap();
    let img = render(&s, 1);
    for y in [50, 100] {
        let p = px(&img, 100, y);
        assert!(
            p[2] > 200 && p[0] > 90 && p[0] < 180,
            "half-transparent blue expected at y={y}, got {p:?}"
        );
    }
    assert!(is_paper(px(&img, 100, 75)));
}

// ---- add_markup: highlight and note ---------------------------------------------------------

#[test]
fn highlight_covers_each_rect_on_normal_rotated_and_cropped_pages() {
    let cases: Vec<(Vec<u8>, Rect, (u32, u32), (u32, u32))> = vec![
        (
            three(),
            rect(100.0, 100.0, 220.0, 120.0),
            (160, 110),
            (160, 140),
        ),
        (rotated(), rect(20.0, 40.0, 80.0, 60.0), (50, 50), (50, 120)),
        (
            offset_crop(),
            rect(30.0, 60.0, 150.0, 80.0),
            (90, 70),
            (90, 150),
        ),
    ];
    for (bytes, r, inside, outside) in cases {
        let s = open(&bytes);
        s.add_markup(highlight(0, 0xFFFF00, vec![r])).unwrap();
        for session in [s.clone(), reopen(&s)] {
            let img = render(&session, 0);
            assert!(
                is_yellow(px(&img, inside.0, inside.1)),
                "{inside:?}: {:?}",
                px(&img, inside.0, inside.1)
            );
            assert!(is_paper(px(&img, outside.0, outside.1)));
            let (x0, y0, x1, y1) = bbox(&img, is_yellow).unwrap();
            let t = 2;
            assert!(
                (x0 as f32 - r.left).abs() <= t as f32
                    && (y0 as f32 - r.top).abs() <= t as f32
                    && (x1 as f32 + 1.0 - r.right).abs() <= t as f32
                    && (y1 as f32 + 1.0 - r.bottom).abs() <= t as f32,
                "highlight bbox ({x0},{y0})-({x1},{y1}) vs {r:?}"
            );
        }
    }
}

#[test]
fn highlight_with_two_rects_covers_both_lines() {
    let s = open(&rotated());
    s.add_markup(highlight(
        0,
        0xFFFF00,
        vec![rect(10.0, 20.0, 90.0, 35.0), rect(10.0, 100.0, 60.0, 115.0)],
    ))
    .unwrap();
    let img = render(&s, 0);
    assert!(is_yellow(px(&img, 50, 27)) && is_yellow(px(&img, 30, 107)));
    assert!(is_paper(px(&img, 50, 65)));
}

#[test]
fn note_is_placed_at_its_view_anchor_and_keeps_its_text() {
    for (bytes, anchor) in [
        (three(), (100.0, 100.0)),
        (rotated(), (30.0, 40.0)),
        (offset_crop(), (60.0, 90.0)),
    ] {
        let s = open(&bytes);
        s.add_markup(note(
            0,
            0xFF0000,
            rect(anchor.0, anchor.1, anchor.0 + 5.0, anchor.1 + 5.0),
            "remember this",
        ))
        .unwrap();
        let reopened = reopen(&s);
        let img = render(&reopened, 0);
        // The icon is 20 x 20 pt, with its top-left at the anchor (the page text is not red).
        let (rx0, ry0, rx1, ry1) = bbox(&img, is_red).expect("red icon drawn");
        assert!(
            (rx0 as f32 - anchor.0).abs() <= 4.0 && (ry0 as f32 - anchor.1).abs() <= 4.0,
            "icon starts at ({rx0},{ry0}), anchor {anchor:?}"
        );
        assert!(
            rx1 as f32 <= anchor.0 + 22.0 && ry1 as f32 <= anchor.1 + 22.0,
            "icon ends at ({rx1},{ry1})"
        );
    }
}

// ---- add_markup: validation -----------------------------------------------------------------

#[test]
fn markup_validation_errors() {
    let s = open(&three());
    let good = || ink(0, 0xFF0000, 2.0, vec![vec![(10.0, 10.0), (20.0, 20.0)]]);
    let n = f32::NAN;
    let mut cases: Vec<(&str, NewMarkup)> = Vec::new();
    cases.push(("bad page", NewMarkup { page: 3, ..good() }));
    cases.push((
        "opacity high",
        NewMarkup {
            opacity: 1.5,
            ..good()
        },
    ));
    cases.push((
        "opacity negative",
        NewMarkup {
            opacity: -0.1,
            ..good()
        },
    ));
    cases.push((
        "opacity nan",
        NewMarkup {
            opacity: n,
            ..good()
        },
    ));
    cases.push((
        "width zero",
        NewMarkup {
            width_pt: 0.0,
            ..good()
        },
    ));
    cases.push((
        "width too big",
        NewMarkup {
            width_pt: 100.5,
            ..good()
        },
    ));
    cases.push((
        "width nan",
        NewMarkup {
            width_pt: n,
            ..good()
        },
    ));
    cases.push((
        "colour",
        NewMarkup {
            rgb: 0x0100_0000,
            ..good()
        },
    ));
    cases.push(("no strokes", ink(0, 0, 2.0, vec![])));
    cases.push(("one point", ink(0, 0, 2.0, vec![vec![(1.0, 1.0)]])));
    cases.push((
        "empty stroke",
        ink(0, 0, 2.0, vec![vec![(1.0, 1.0), (2.0, 2.0)], vec![]]),
    ));
    cases.push((
        "nan point",
        ink(0, 0, 2.0, vec![vec![(1.0, 1.0), (n, 2.0)]]),
    ));
    cases.push((
        "inf point",
        ink(0, 0, 2.0, vec![vec![(1.0, 1.0), (2.0, f32::INFINITY)]]),
    ));
    cases.push((
        "too many points",
        ink(
            0,
            0,
            2.0,
            vec![(0..100_001).map(|i| (i as f32 % 500.0, 10.0)).collect()],
        ),
    ));
    cases.push((
        "huge contents",
        NewMarkup {
            contents: "x".repeat(100_001),
            ..good()
        },
    ));
    cases.push(("highlight no rects", highlight(0, 0, vec![])));
    cases.push((
        "highlight inverted",
        highlight(0, 0, vec![rect(50.0, 10.0, 10.0, 20.0)]),
    ));
    cases.push((
        "highlight flat",
        highlight(0, 0, vec![rect(10.0, 10.0, 50.0, 10.0)]),
    ));
    cases.push((
        "highlight nan",
        highlight(0, 0, vec![rect(10.0, 10.0, n, 20.0)]),
    ));
    cases.push((
        "note no rect",
        NewMarkup {
            rects: vec![],
            ..note(0, 0, rect(1.0, 1.0, 2.0, 2.0), "x")
        },
    ));
    for (what, m) in cases {
        let detail = invalid_arg(s.add_markup(m));
        assert!(!detail.is_empty(), "{what}");
    }
    // Nothing above changed the document.
    assert!(!s.state().unwrap().dirty);
    // Width does not matter for a highlight.
    s.add_markup(highlight(0, 0, vec![rect(10.0, 10.0, 50.0, 20.0)]))
        .unwrap();
}

// ---- forms ----------------------------------------------------------------------------------

fn field(fields: &[FieldInfo], name: &str) -> FieldInfo {
    fields
        .iter()
        .find(|f| f.name == name)
        .unwrap_or_else(|| panic!("no field {name}"))
        .clone()
}

fn texts(v: &[&str]) -> Vec<String> {
    v.iter().map(|s| (*s).to_owned()).collect()
}

#[test]
fn list_fields_reports_kinds_flags_options_and_view_space_rects() {
    let s = open(&form(0));
    let fields = s.list_fields().unwrap();
    let names: Vec<&str> = fields.iter().map(|f| f.name.as_str()).collect();
    assert_eq!(names, ["name", "agree", "choice", "color", "locked", "sig"]);
    let name = field(&fields, "name");
    assert_eq!(name.kind, FieldKind::Text);
    assert_eq!(name.value, texts(&["Bob"]));
    assert_eq!(name.max_len, Some(10));
    assert!(!name.read_only && !name.multiline && !name.required);
    // user [20 340 180 360] on a 300 x 400 page -> view [20 40 180 60]
    assert_eq!(name.widgets.len(), 1);
    assert_eq!(name.widgets[0].page, 0);
    assert_rect(name.widgets[0].rect, rect(20.0, 40.0, 180.0, 60.0));
    assert_eq!(field(&fields, "agree").kind, FieldKind::CheckBox);
    assert_eq!(
        field(&fields, "agree").widgets[0].on_state.as_deref(),
        Some("Yes")
    );
    let choice = field(&fields, "choice");
    assert_eq!(choice.kind, FieldKind::Radio);
    assert_eq!(
        choice
            .widgets
            .iter()
            .map(|w| w.on_state.clone())
            .collect::<Vec<_>>(),
        vec![Some("A".to_string()), Some("B".to_string())]
    );
    assert_rect(choice.widgets[1].rect, rect(60.0, 120.0, 80.0, 140.0));
    let color = field(&fields, "color");
    assert_eq!(color.kind, FieldKind::Combo);
    assert_eq!(
        color
            .options
            .iter()
            .map(|o| (o.export.as_str(), o.display.as_str()))
            .collect::<Vec<_>>(),
        vec![("r", "Red"), ("g", "Green")]
    );
    assert!(field(&fields, "locked").read_only);
    assert_eq!(field(&fields, "locked").value, texts(&["fixed"]));
    assert_eq!(field(&fields, "sig").kind, FieldKind::Signature);
}

#[test]
fn list_fields_converts_widget_rects_on_a_rotated_page() {
    let s = open(&form(90));
    assert_eq!(s.page_infos().unwrap()[0].width_pt.round() as u32, 400);
    let name = field(&s.list_fields().unwrap(), "name");
    // /Rotate 90 maps user (x, y) to view (y, x): [20 340 180 360] -> [340 20 360 180].
    assert_rect(name.widgets[0].rect, rect(340.0, 20.0, 360.0, 180.0));
}

#[test]
fn a_document_without_a_form_lists_no_fields() {
    assert!(open(&three()).list_fields().unwrap().is_empty());
}

#[test]
fn set_field_for_each_kind_and_persistence() {
    let s = open(&form(0));
    let state = s
        .set_field(
            "name".into(),
            FieldValue::Text {
                value: "Alice".into(),
            },
        )
        .unwrap();
    assert!(state.dirty && state.can_undo);
    s.set_field("agree".into(), FieldValue::Check { checked: true })
        .unwrap();
    s.set_field(
        "choice".into(),
        FieldValue::Radio {
            on_state: Some("B".into()),
        },
    )
    .unwrap();
    s.set_field(
        "color".into(),
        FieldValue::Choice {
            values: texts(&["g"]),
        },
    )
    .unwrap();
    for session in [s.clone(), reopen(&s)] {
        let fields = session.list_fields().unwrap();
        assert_eq!(field(&fields, "name").value, texts(&["Alice"]));
        assert_eq!(field(&fields, "agree").value, texts(&["Yes"]));
        assert_eq!(field(&fields, "choice").value, texts(&["B"]));
        assert_eq!(field(&fields, "color").value, texts(&["g"]));
        assert_eq!(field(&fields, "locked").value, texts(&["fixed"]));
    }
    s.set_field("agree".into(), FieldValue::Check { checked: false })
        .unwrap();
    s.set_field("choice".into(), FieldValue::Radio { on_state: None })
        .unwrap();
    let fields = s.list_fields().unwrap();
    assert!(field(&fields, "agree").value.is_empty() && field(&fields, "choice").value.is_empty());
}

#[test]
fn set_field_undo_restores_the_value() {
    let s = open(&form(0));
    s.set_field(
        "name".into(),
        FieldValue::Text {
            value: "Alice".into(),
        },
    )
    .unwrap();
    s.undo().unwrap();
    assert_eq!(
        field(&s.list_fields().unwrap(), "name").value,
        texts(&["Bob"])
    );
}

#[test]
fn set_field_rejects_unknown_read_only_and_mismatched_kinds() {
    let s = open(&form(0));
    let text = |v: &str| FieldValue::Text { value: v.into() };
    let check = FieldValue::Check { checked: true };
    let radio = FieldValue::Radio {
        on_state: Some("A".into()),
    };
    let choice = |v: &[&str]| FieldValue::Choice { values: texts(v) };
    assert!(invalid_arg(s.set_field("nope".into(), text("x"))).contains("nope"));
    assert_eq!(
        invalid_arg(s.set_field("locked".into(), text("x"))),
        "field is read-only"
    );
    let mismatches: Vec<(&str, FieldValue)> = vec![
        ("name", check.clone()),
        ("name", radio.clone()),
        ("name", choice(&["a"])),
        ("agree", text("Yes")),
        ("agree", radio.clone()),
        ("agree", choice(&["Yes"])),
        ("choice", text("A")),
        ("choice", check.clone()),
        ("choice", choice(&["A"])),
        ("color", text("r")),
        ("color", check.clone()),
        ("color", radio.clone()),
        ("sig", text("x")),
        ("sig", check.clone()),
    ];
    for (name, value) in mismatches {
        invalid_arg(s.set_field(name.into(), value.clone()));
        let _ = value;
    }
    // Right kind, bad value.
    invalid_arg(s.set_field("name".into(), text("this is longer than ten")));
    invalid_arg(s.set_field(
        "choice".into(),
        FieldValue::Radio {
            on_state: Some("Z".into()),
        },
    ));
    invalid_arg(s.set_field("color".into(), choice(&["purple"])));
    invalid_arg(s.set_field("color".into(), choice(&["r", "g"])));
    assert!(
        !s.state().unwrap().dirty,
        "a rejected set_field must not touch the document"
    );
}

#[test]
fn reset_form_all_and_named() {
    let s = open(&form(0));
    s.set_field(
        "name".into(),
        FieldValue::Text {
            value: "Alice".into(),
        },
    )
    .unwrap();
    s.set_field("agree".into(), FieldValue::Check { checked: true })
        .unwrap();
    s.set_field(
        "color".into(),
        FieldValue::Choice {
            values: texts(&["r"]),
        },
    )
    .unwrap();
    // Named: only "name" goes back to its default.
    s.reset_form(Some(texts(&["name"]))).unwrap();
    let fields = s.list_fields().unwrap();
    assert_eq!(field(&fields, "name").value, texts(&["Anon"]));
    assert_eq!(field(&fields, "agree").value, texts(&["Yes"]));
    assert_eq!(field(&fields, "color").value, texts(&["r"]));
    // All.
    s.reset_form(None).unwrap();
    let fields = reopen(&s).list_fields().unwrap();
    assert_eq!(field(&fields, "name").value, texts(&["Anon"]));
    assert!(field(&fields, "agree").value.is_empty());
    assert!(field(&fields, "color").value.is_empty());
    assert_eq!(
        field(&fields, "locked").value,
        texts(&["fixed"]),
        "read-only fields are not reset"
    );
}

#[test]
fn reset_form_validation() {
    let s = open(&form(0));
    invalid_arg(s.reset_form(Some(vec![])));
    invalid_arg(s.reset_form(Some(texts(&["nope"]))));
    assert!(invalid_arg(s.reset_form(Some(texts(&["locked"])))).contains("read-only"));
    let plain = open(&three());
    invalid_arg(plain.reset_form(None));
    invalid_arg(plain.set_field("x".into(), FieldValue::Text { value: "y".into() }));
}

// ---- extract and split ----------------------------------------------------------------------

fn widths(bytes: &[u8]) -> Vec<u32> {
    open(bytes)
        .page_infos()
        .unwrap()
        .iter()
        .map(|p| p.width_pt.round() as u32)
        .collect()
}

#[test]
fn extract_keeps_chosen_pages_in_document_order_and_dedups() {
    let s = open(&three());
    let out = s.extract_pages(vec![2, 0, 0]).unwrap();
    assert_eq!(widths(&out), vec![612, 595]);
    assert_eq!(widths(&s.extract_pages(vec![1]).unwrap()), vec![400]);
    assert_eq!(
        widths(&s.extract_pages(vec![0, 1, 2]).unwrap()),
        vec![612, 400, 595]
    );
    // The session itself is untouched.
    assert!(!s.state().unwrap().dirty);
    assert_eq!(s.state().unwrap().page_count, 3);
}

#[test]
fn extract_follows_unsaved_edits_and_validates() {
    let s = open(&three());
    s.delete_pages(vec![0]).unwrap();
    assert_eq!(
        widths(&s.extract_pages(vec![0, 1]).unwrap()),
        vec![400, 595]
    );
    invalid_arg(s.extract_pages(vec![2]));
    invalid_arg(s.extract_pages(vec![]));
    invalid_arg(s.extract_pages(vec![u32::MAX]));
}

fn five() -> Vec<u8> {
    pages_pdf(
        5,
        &[(100, 100), (200, 100), (300, 100), (400, 100), (500, 100)],
        |_| String::new(),
    )
}

fn part_widths(parts: &[pdfa_ffi::SplitPart]) -> Vec<Vec<u32>> {
    parts.iter().map(|p| widths(&p.bytes)).collect()
}

#[test]
fn split_every_n_makes_parts_with_padded_names() {
    let s = open(&five());
    let parts = s.split(SplitSpec::EveryN { n: 2 }).unwrap();
    assert_eq!(
        parts.iter().map(|p| p.name.as_str()).collect::<Vec<_>>(),
        ["part-01.pdf", "part-02.pdf", "part-03.pdf"]
    );
    assert_eq!(
        part_widths(&parts),
        vec![vec![100, 200], vec![300, 400], vec![500]]
    );
    let one = s.split(SplitSpec::EveryN { n: 100 }).unwrap();
    assert_eq!(part_widths(&one), vec![vec![100, 200, 300, 400, 500]]);
    let twelve = open(&pages_pdf(12, &[(100, 100)], |_| String::new()))
        .split(SplitSpec::EveryN { n: 1 })
        .unwrap();
    assert_eq!(
        (
            twelve.len(),
            twelve[0].name.as_str(),
            twelve[11].name.as_str()
        ),
        (12, "part-01.pdf", "part-12.pdf")
    );
}

#[test]
fn split_before_cuts_at_the_listed_pages() {
    let s = open(&five());
    let parts = s.split(SplitSpec::Before { pages: vec![2, 3] }).unwrap();
    assert_eq!(
        part_widths(&parts),
        vec![vec![100, 200], vec![300], vec![400, 500]]
    );
    assert_eq!(parts[2].name, "part-03.pdf");
    let whole = s.split(SplitSpec::Before { pages: vec![] }).unwrap();
    assert_eq!(part_widths(&whole), vec![vec![100, 200, 300, 400, 500]]);
}

#[test]
fn split_validation() {
    let s = open(&five());
    invalid_arg(s.split(SplitSpec::EveryN { n: 0 }));
    invalid_arg(s.split(SplitSpec::Before { pages: vec![0] }));
    invalid_arg(s.split(SplitSpec::Before { pages: vec![5] }));
    invalid_arg(s.split(SplitSpec::Before { pages: vec![3, 2] }));
    invalid_arg(s.split(SplitSpec::Before { pages: vec![2, 2] }));
    // More than 1000 parts is refused before anything is extracted.
    let big = open(&pages_pdf(1001, &[(100, 100)], |_| String::new()));
    match big.split(SplitSpec::EveryN { n: 1 }) {
        Err(PdfError::TooLarge { detail }) => assert!(detail.contains("1001")),
        other => panic!("expected TooLarge, got {other:?}"),
    }
    assert_eq!(big.split(SplitSpec::EveryN { n: 2 }).unwrap().len(), 501);
}

// ---- protection -----------------------------------------------------------------------------

#[cfg(not(feature = "encrypt"))]
#[test]
fn protection_is_unsupported_without_the_encrypt_feature() {
    use pdfa_ffi::{ChangePerm, PrintPerm, ProtectionSpec};
    let s = open(&three());
    let spec = ProtectionSpec {
        open_password: Some("open-pw".into()),
        owner_password: None,
        printing: PrintPerm::High,
        changes: ChangePerm::None,
        allow_copy: true,
        allow_accessibility: true,
    };
    for r in [
        s.save_protected(spec).map(|_| ()),
        s.remove_protection().map(|_| ()),
    ] {
        assert_eq!(
            r,
            Err(PdfError::Unsupported {
                detail: "encryption disabled in this build".into()
            })
        );
    }
    assert!(!s.state().unwrap().dirty);
}

#[cfg(feature = "encrypt")]
mod protection {
    use super::*;
    use pdfa_ffi::{ChangePerm, PrintPerm, ProtectionSpec};

    fn spec(open: Option<&str>, owner: Option<&str>) -> ProtectionSpec {
        ProtectionSpec {
            open_password: open.map(str::to_owned),
            owner_password: owner.map(str::to_owned),
            // Restrictions only with an owner password; without one, nothing is restricted.
            printing: if owner.is_some() {
                PrintPerm::None
            } else {
                PrintPerm::High
            },
            changes: if owner.is_some() {
                ChangePerm::None
            } else {
                ChangePerm::AnyExceptExtract
            },
            allow_copy: owner.is_none(),
            allow_accessibility: true,
        }
    }

    fn open_with(bytes: &[u8], pw: Option<&str>) -> Result<Arc<PdfSession>, PdfError> {
        PdfSession::open(bytes.to_vec(), pw.map(str::to_owned))
    }

    /// Whether `needle` followed by optional whitespace and `value` appears in `hay`.
    fn has_entry(hay: &[u8], key: &str, value: &str) -> bool {
        let text = String::from_utf8_lossy(hay);
        text.match_indices(key).any(|(i, _)| {
            let rest = &text[i + key.len()..];
            let rest = rest.trim_start();
            rest.starts_with(value)
                && !rest[value.len()..].starts_with(|c: char| c.is_ascii_digit())
        })
    }

    #[test]
    fn protected_copy_needs_the_open_password_and_carries_the_permissions() {
        let s = open(&three());
        let bytes = s
            .save_protected(spec(Some("open-pw"), Some("owner-pw")))
            .unwrap();
        // The live session is untouched: still plain, still clean.
        let live = s.meta().unwrap();
        assert!(!live.encrypted && !s.state().unwrap().dirty && !s.state().unwrap().can_undo);
        assert!(!reopen(&s).meta().unwrap().encrypted);

        assert_eq!(
            open_with(&bytes, None).err(),
            Some(PdfError::PasswordRequired)
        );
        assert_eq!(
            open_with(&bytes, Some("wrong")).err(),
            Some(PdfError::WrongPassword)
        );
        let user = open_with(&bytes, Some("open-pw")).unwrap();
        let m = user.meta().unwrap();
        assert!(m.encrypted && m.page_count == 3);
        assert!(
            !m.can_print && !m.can_copy,
            "permissions bits must reflect the spec: {m:?}"
        );
        // The owner password opens it with full rights.
        let owner = open_with(&bytes, Some("owner-pw")).unwrap().meta().unwrap();
        assert!(owner.can_print && owner.can_copy);
        // And it still renders.
        assert!(is_paper(px(&render(&user, 0), 5, 5)));
    }

    #[test]
    fn permissions_follow_the_spec() {
        let s = open(&three());
        let mut sp = spec(Some("open-pw"), Some("owner-pw"));
        sp.printing = PrintPerm::High;
        sp.allow_copy = true;
        let m = open_with(&s.save_protected(sp).unwrap(), Some("open-pw"))
            .unwrap()
            .meta()
            .unwrap();
        assert!(m.can_print && m.can_copy, "{m:?}");
        // Printing low-res only is still "can print".
        let mut sp = spec(Some("open-pw"), Some("owner-pw"));
        sp.printing = PrintPerm::Low;
        assert!(
            open_with(&s.save_protected(sp).unwrap(), Some("open-pw"))
                .unwrap()
                .meta()
                .unwrap()
                .can_print
        );
    }

    #[test]
    fn owner_password_alone_opens_without_a_password_but_restricts() {
        let s = open(&three());
        let bytes = s.save_protected(spec(None, Some("owner-pw"))).unwrap();
        let m = open_with(&bytes, None).unwrap().meta().unwrap();
        assert!(m.encrypted && !m.can_print && !m.can_copy, "{m:?}");
    }

    #[test]
    fn only_aes_256_is_written() {
        let s = open(&three());
        let bytes = s
            .save_protected(spec(Some("open-pw"), Some("owner-pw")))
            .unwrap();
        assert!(has_entry(&bytes, "/V", "5"), "expected /V 5");
        assert!(has_entry(&bytes, "/R", "6"), "expected /R 6");
        let text = String::from_utf8_lossy(&bytes);
        assert!(text.contains("/AESV3"), "expected the AESV3 crypt filter");
        assert!(
            !text.contains("/AESV2") && !text.contains("/CFM /V2"),
            "no AES-128 / RC4 crypt filters"
        );
        assert!(
            !has_entry(&bytes, "/R", "4")
                && !has_entry(&bytes, "/R", "3")
                && !has_entry(&bytes, "/R", "2")
        );
    }

    #[test]
    fn protection_includes_unsaved_edits() {
        let s = open(&three());
        s.delete_pages(vec![0]).unwrap();
        let bytes = s.save_protected(spec(Some("open-pw"), None)).unwrap();
        assert_eq!(
            open_with(&bytes, Some("open-pw"))
                .unwrap()
                .meta()
                .unwrap()
                .page_count,
            2
        );
        assert!(
            s.state().unwrap().dirty && s.state().unwrap().can_undo,
            "the live session keeps its edit state"
        );
    }

    #[test]
    fn spec_validation() {
        let s = open(&three());
        invalid_arg(s.save_protected(spec(None, None)));
        invalid_arg(s.save_protected(spec(Some(""), None)));
        invalid_arg(s.save_protected(spec(Some("a"), Some(""))));
        invalid_arg(s.save_protected(spec(Some("same"), Some("same"))));
        invalid_arg(s.save_protected(spec(Some(&"p".repeat(128)), None)));
        invalid_arg(s.save_protected(spec(Some(&"é".repeat(64)), None))); // 128 bytes
        let ok = s
            .save_protected(spec(Some(&"p".repeat(127)), None))
            .unwrap();
        assert!(open_with(&ok, Some(&"p".repeat(127))).is_ok());
        // Rejected specs must not leak the password into the error text.
        let detail =
            invalid_arg(s.save_protected(spec(Some("same-secret-pw"), Some("same-secret-pw"))));
        assert!(!detail.contains("same-secret-pw"));
    }

    #[test]
    fn remove_protection_round_trip() {
        let s = open(&three());
        let bytes = s
            .save_protected(spec(Some("open-pw"), Some("owner-pw")))
            .unwrap();
        // Opened as owner: protection can be removed, as an undoable edit.
        let owner = open_with(&bytes, Some("owner-pw")).unwrap();
        assert!(owner.meta().unwrap().encrypted);
        let state = owner.remove_protection().unwrap();
        assert!(state.dirty && state.can_undo && state.undo_label.is_some());
        let plain = owner.save_full().unwrap();
        let reopened = open_with(&plain, None).unwrap();
        assert!(!reopened.meta().unwrap().encrypted && reopened.meta().unwrap().page_count == 3);
        assert!(!String::from_utf8_lossy(&plain).contains("/Encrypt"));
        // Undo puts the protection back.
        owner.undo().unwrap();
        assert_eq!(
            open_with(&owner.save_full().unwrap(), None).err(),
            Some(PdfError::PasswordRequired)
        );
    }

    #[test]
    fn remove_protection_needs_the_owner_and_a_protected_document() {
        let s = open(&three());
        invalid_arg(s.remove_protection());
        let bytes = s
            .save_protected(spec(Some("open-pw"), Some("owner-pw")))
            .unwrap();
        let user = open_with(&bytes, Some("open-pw")).unwrap();
        match user.remove_protection() {
            Err(PdfError::NotPermitted { .. }) => {}
            other => panic!("a restricted user must not remove protection: {other:?}"),
        }
    }

    #[test]
    fn restrictions_need_an_owner_password() {
        let s = open(&three());
        let mut sp = spec(Some("open-pw"), None);
        for tweak in 0..4 {
            let mut t = sp.clone();
            match tweak {
                0 => t.printing = PrintPerm::Low,
                1 => t.changes = ChangePerm::Pages,
                2 => t.allow_copy = false,
                _ => t.allow_accessibility = false,
            }
            assert!(
                invalid_arg(s.save_protected(t)).contains("owner"),
                "tweak {tweak}"
            );
        }
        sp.allow_accessibility = true;
        assert!(s.save_protected(sp).is_ok());
    }

    #[test]
    fn debug_output_never_shows_passwords() {
        let sp = spec(Some("hunter2-open"), Some("hunter2-owner"));
        let text = format!("{sp:?}");
        assert!(!text.contains("hunter2"), "{text}");
        let src = pdfa_ffi::MergeSource {
            name: "a.pdf".into(),
            bytes: vec![1, 2, 3],
            password: Some("hunter2-merge".into()),
            ranges: vec![],
        };
        assert!(!format!("{src:?}").contains("hunter2"));
    }

    #[test]
    fn a_signed_document_is_refused() {
        let s = open(&signed());
        assert_eq!(
            s.save_protected(spec(Some("open-pw"), Some("owner-pw"))),
            Err(PdfError::NotPermitted {
                detail: "document is signed".into()
            })
        );
    }

    #[test]
    fn re_protecting_an_encrypted_document() {
        let s = open(&three());
        let bytes = s
            .save_protected(spec(Some("open-pw"), Some("owner-pw")))
            .unwrap();
        let owner = open_with(&bytes, Some("owner-pw")).unwrap();
        // No explicit owner password: refused, never a random one.
        assert!(invalid_arg(owner.save_protected(spec(Some("new-open"), None))).contains("owner"));
        // With one: the copy opens with the new passwords only.
        let again = owner
            .save_protected(spec(Some("new-open"), Some("new-owner")))
            .unwrap();
        assert_eq!(
            open_with(&again, Some("open-pw")).err(),
            Some(PdfError::WrongPassword)
        );
        assert_eq!(
            open_with(&again, Some("new-open"))
                .unwrap()
                .meta()
                .unwrap()
                .page_count,
            3
        );
        // Opened with the user password only (restricted): not permitted.
        let user = open_with(&bytes, Some("open-pw")).unwrap();
        assert!(matches!(
            user.save_protected(spec(Some("x-open"), Some("x-owner"))),
            Err(PdfError::NotPermitted { .. })
        ));
        assert!(matches!(
            user.remove_protection(),
            Err(PdfError::NotPermitted { .. })
        ));
    }

    #[test]
    fn a_restricted_document_refuses_edits_as_not_permitted() {
        let s = open(&three());
        let bytes = s
            .save_protected(spec(Some("open-pw"), Some("owner-pw")))
            .unwrap();
        let user = open_with(&bytes, Some("open-pw")).unwrap();
        let m = user.add_markup(highlight(0, 0xFFFF00, vec![rect(10.0, 10.0, 50.0, 20.0)]));
        assert!(matches!(m, Err(PdfError::NotPermitted { .. })), "{m:?}");
        assert!(matches!(
            user.extract_pages(vec![0]),
            Err(PdfError::NotPermitted { .. })
        ));
        assert!(matches!(
            user.split(SplitSpec::EveryN { n: 1 }),
            Err(PdfError::NotPermitted { .. })
        ));
        assert!(!user.state().unwrap().dirty);
    }
}

// ---- review round: more pages, limits, redaction ----------------------------------------------

/// A page with a filled signature field (a /Sig dictionary with /ByteRange and /Contents).
fn signed() -> Vec<u8> {
    let bodies = vec![
        "<< /Type /Catalog /Pages 2 0 R /AcroForm << /Fields [4 0 R] /SigFlags 3 >> >>".to_string(),
        "<< /Type /Pages /Kids [3 0 R] /Count 1 >>".to_string(),
        "<< /Type /Page /Parent 2 0 R /MediaBox [0 0 200 200] /Annots [4 0 R] >>".to_string(),
        "<< /Type /Annot /Subtype /Widget /FT /Sig /T (Sig1) /Rect [10 10 100 50] /F 4 /P 3 0 R /V 5 0 R >>".to_string(),
        format!(
            "<< /Type /Sig /Filter /Adobe.PPKLite /SubFilter /adbe.pkcs7.detached /ByteRange [0 100 200 100] /Contents <{}> /M (D:20200101000000Z) >>",
            "00".repeat(64)
        ),
    ];
    pdf(&bodies)
}

#[test]
fn the_signed_fixture_counts_as_signed() {
    // Guards the fixture of the protection tests: the engine must see a filled signature.
    let s = open(&signed());
    let f = s.list_fields().unwrap();
    assert_eq!(f[0].kind, FieldKind::Signature);
}

#[test]
fn debug_of_a_spec_and_a_merge_source_hides_passwords() {
    use pdfa_ffi::{ChangePerm, MergeSource, PrintPerm, ProtectionSpec};
    let sp = ProtectionSpec {
        open_password: Some("hunter2-open".into()),
        owner_password: Some("hunter2-owner".into()),
        printing: PrintPerm::High,
        changes: ChangePerm::None,
        allow_copy: true,
        allow_accessibility: true,
    };
    assert!(!format!("{sp:?}").contains("hunter2"));
    let src = MergeSource {
        name: "a.pdf".into(),
        bytes: vec![1],
        password: Some("hunter2-merge".into()),
        ranges: vec![],
    };
    assert!(!format!("{src:?}").contains("hunter2"));
}

fn near_box(got: (u32, u32, u32, u32), want: (f32, f32, f32, f32), tol: f32, what: &str) {
    let g = [
        got.0 as f32,
        got.1 as f32,
        got.2 as f32 + 1.0,
        got.3 as f32 + 1.0,
    ];
    let w = [want.0, want.1, want.2, want.3];
    assert!(
        g.iter().zip(&w).all(|(a, b)| (a - b).abs() <= tol),
        "{what}: got {got:?}, want {want:?}"
    );
}

/// Ink, highlight and note on /Rotate 180 and 270, and on a page that is rotated 90 AND cropped
/// to a box with a non-zero origin.
#[test]
fn markup_lands_in_view_space_on_every_rotation_and_a_rotated_cropped_page() {
    let pages: Vec<(&str, Vec<u8>)> = vec![
        (
            "rotate 180",
            pages_pdf(1, &[(200, 100)], |_| "/Rotate 180".into()),
        ),
        (
            "rotate 270",
            pages_pdf(1, &[(200, 100)], |_| "/Rotate 270".into()),
        ),
        (
            "rotate 90 + crop",
            pages_pdf(1, &[(400, 400)], |_| {
                "/Rotate 90 /CropBox [100 100 300 250]".into()
            }),
        ),
    ];
    for (what, bytes) in pages {
        // Ink
        let s = open(&bytes);
        s.add_markup(ink(
            0,
            0xFF0000,
            6.0,
            vec![vec![(10.0, 30.0), (60.0, 30.0)]],
        ))
        .unwrap();
        for session in [s.clone(), reopen(&s)] {
            let b = bbox(&render(&session, 0), is_red).unwrap_or_else(|| panic!("{what}: no ink"));
            near_box(b, (7.0, 27.0, 63.0, 33.0), 4.0, &format!("{what} ink"));
        }
        // Highlight
        let s = open(&bytes);
        s.add_markup(highlight(0, 0xFFFF00, vec![rect(20.0, 50.0, 70.0, 65.0)]))
            .unwrap();
        for session in [s.clone(), reopen(&s)] {
            let b = bbox(&render(&session, 0), is_yellow)
                .unwrap_or_else(|| panic!("{what}: no highlight"));
            near_box(
                b,
                (20.0, 50.0, 70.0, 65.0),
                2.0,
                &format!("{what} highlight"),
            );
        }
        // Note
        let s = open(&bytes);
        s.add_markup(note(0, 0xFF0000, rect(30.0, 60.0, 35.0, 65.0), "n"))
            .unwrap();
        let (x0, y0, x1, y1) =
            bbox(&render(&reopen(&s), 0), is_red).unwrap_or_else(|| panic!("{what}: no note"));
        assert!(
            (x0 as f32 - 30.0).abs() <= 4.0
                && (y0 as f32 - 60.0).abs() <= 4.0
                && x1 <= 52
                && y1 <= 82,
            "{what} note ({x0},{y0})-({x1},{y1})"
        );
    }
}

#[test]
fn list_fields_on_rotated_180_and_270_pages() {
    let r180 = field(&open(&form(180)).list_fields().unwrap(), "name");
    assert_rect(r180.widgets[0].rect, rect(120.0, 340.0, 280.0, 360.0));
    let r270 = field(&open(&form(270)).list_fields().unwrap(), "name");
    assert_rect(r270.widgets[0].rect, rect(40.0, 120.0, 60.0, 280.0));
}

#[test]
fn a_widget_rect_that_overflows_f32_is_skipped() {
    let huge = "1".to_string() + &"0".repeat(40);
    let bodies = vec![
        "<< /Type /Catalog /Pages 2 0 R /AcroForm << /Fields [4 0 R] /DA (/Helv 0 Tf 0 g) >> >>"
            .to_string(),
        "<< /Type /Pages /Kids [3 0 R] /Count 1 >>".to_string(),
        "<< /Type /Page /Parent 2 0 R /MediaBox [0 0 200 200] /Annots [4 0 R] >>".to_string(),
        format!(
            "<< /Type /Annot /Subtype /Widget /FT /Tx /T (big) /Rect [0 0 {huge} 10] /F 4 /P 3 0 R >>"
        ),
    ];
    let s = open(&pdf(&bodies));
    let f = s.list_fields().unwrap();
    assert_eq!(f.len(), 1);
    for w in &f[0].widgets {
        assert!(
            [w.rect.left, w.rect.top, w.rect.right, w.rect.bottom]
                .iter()
                .all(|v| v.is_finite())
        );
    }
}

/// With /UserUnit 2 a 100 pt page is displayed 200 pt wide; the stroke width is in view points.
#[test]
fn ink_width_is_in_view_points_with_a_user_unit() {
    let s = open(&pages_pdf(1, &[(100, 100)], |_| "/UserUnit 2".into()));
    let w = s.page_infos().unwrap()[0].width_pt.round() as u32;
    assert!(w == 200 || w == 100, "unexpected page width {w}");
    s.add_markup(ink(
        0,
        0xFF0000,
        10.0,
        vec![vec![(20.0, 50.0), (80.0, 50.0)]],
    ))
    .unwrap();
    let img = render(&s, 0);
    let thickness = (0..img.height).filter(|&y| is_red(px(&img, 50, y))).count();
    assert!(
        (9..=11).contains(&thickness),
        "stroke is {thickness} px thick at 1 px per view point"
    );
    let (x0, _, x1, _) = bbox(&img, is_red).unwrap();
    assert!(
        (14..=20).contains(&x0) && (80..=86).contains(&x1),
        "x {x0}..{x1}"
    );
}

#[test]
fn zero_size_and_non_finite_note_anchors() {
    let s = open(&three());
    s.add_markup(note(0, 0xFF0000, rect(40.0, 40.0, 40.0, 40.0), "dot"))
        .unwrap();
    assert!(bbox(&render(&s, 0), is_red).is_some());
    invalid_arg(s.add_markup(note(0, 0, rect(f32::NAN, 40.0, 40.0, 40.0), "x")));
    invalid_arg(s.add_markup(note(0, 0, rect(40.0, f32::INFINITY, 40.0, 40.0), "x")));
}

#[test]
fn single_line_text_fields_reject_line_breaks_and_reset_caps_its_names() {
    let s = open(&form(0));
    invalid_arg(s.set_field(
        "name".into(),
        FieldValue::Text {
            value: "a\nb".into(),
        },
    ));
    invalid_arg(s.set_field(
        "name".into(),
        FieldValue::Text {
            value: "a\rb".into(),
        },
    ));
    invalid_arg(s.reset_form(Some((0..10_001).map(|i| format!("f{i}")).collect())));
}

#[test]
fn output_over_the_cap_is_too_large_before_the_rest_is_built() {
    let s = open(&five());
    s.debug_set_output_cap(1);
    for r in [
        s.split(SplitSpec::EveryN { n: 1 }).map(|_| ()),
        s.extract_pages(vec![0]).map(|_| ()),
    ] {
        assert!(matches!(r, Err(PdfError::TooLarge { .. })), "{r:?}");
    }
    s.debug_set_output_cap(0);
    assert_eq!(s.split(SplitSpec::EveryN { n: 1 }).unwrap().len(), 5);
}
