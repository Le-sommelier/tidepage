//! Golden-render harness.
//!
//! Hand-built fixture PDFs are rendered with `PdfSession::render_tile` and compared with the
//! goldens in `rust/pdfa-ffi/golden/`. Format, tolerances and fixtures: `golden/README.md`.
//!
//! - `cargo test -p pdfa-ffi --test golden`: compare (the default).
//! - `UPDATE_GOLDEN=1 cargo test -p pdfa-ffi --test golden`: rewrite the goldens.
//! - `cargo test -p pdfa-ffi --test golden -- --ignored --nocapture print_checksums`: print checksums.
//!
//! The `sanity_*` tests check what the fixtures are supposed to look like, in geometry the
//! engine's own tests already use (view space, paper is opaque white). They guard against
//! baking a wrong golden.

#![allow(clippy::chunks_exact_to_as_chunks)] // test code: readability over the lint

use std::fs;
use std::path::{Path, PathBuf};

use pdfa_ffi::{PdfSession, RenderedImage};

const MAGIC: &[u8; 4] = b"GLD1";
/// A channel may differ from its golden by this much without counting as a mismatch.
const CLOSE: u8 = 2;
/// At most this many pixels per thousand may exceed `CLOSE` (0.1 %).
const CLOSE_PER_MILLE: usize = 1;
/// No pixel may differ from its golden by more than this.
const HARD: u8 = 32;

// ---- fixtures -------------------------------------------------------------------------------

/// One render of a fixture: a tile (or the whole page) of page 0 at `scale`.
struct Shot {
    name: &'static str,
    scale: f32,
    x: u32,
    y: u32,
    w: u32,
    h: u32,
}

struct Fixture {
    bytes: Vec<u8>,
    shots: Vec<Shot>,
}

fn tile(name: &'static str, scale: f32, x: u32, y: u32) -> Shot {
    Shot {
        name,
        scale,
        x,
        y,
        w: 256,
        h: 256,
    }
}

fn full(name: &'static str, scale: f32, w: u32, h: u32) -> Shot {
    Shot {
        name,
        scale,
        x: 0,
        y: 0,
        w,
        h,
    }
}

fn fixtures() -> Vec<Fixture> {
    vec![
        Fixture {
            bytes: text_fixture(),
            shots: vec![tile("text_tile256_s1", 1.0, 0, 0)],
        },
        Fixture {
            bytes: shapes_fixture(),
            shots: vec![tile("shapes_tile256_s2", 2.0, 200, 200)],
        },
        Fixture {
            bytes: rotate_fixture(),
            shots: vec![full("rotate90_full_s1", 1.0, 200, 150)],
        },
        Fixture {
            bytes: crop_fixture(),
            shots: vec![full("cropbox_full_s1", 1.0, 200, 200)],
        },
        Fixture {
            bytes: image_fixture(),
            shots: vec![full("image_full_s1", 1.0, 160, 160)],
        },
        Fixture {
            bytes: transparency_fixture(),
            shots: vec![full("transparency_full_s1", 1.0, 150, 150)],
        },
    ]
}

/// Serialises a `PDF` from object bodies; object `i + 1` is `bodies[i]`, root is object 1.
/// Offsets and the xref table are computed, so every fixture has a correct xref.
fn pdf(bodies: &[Vec<u8>]) -> Vec<u8> {
    let mut out = b"%PDF-1.7\n%\xE2\xE3\xCF\xD3\n".to_vec();
    let mut offsets = Vec::new();
    for (i, body) in bodies.iter().enumerate() {
        offsets.push(out.len());
        out.extend_from_slice(format!("{} 0 obj\n", i + 1).as_bytes());
        out.extend_from_slice(body);
        out.extend_from_slice(b"\nendobj\n");
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

/// A stream object with an exact /Length.
fn stream(dict: &str, data: &[u8]) -> Vec<u8> {
    let mut v = format!("<< /Length {} {dict} >>\nstream\n", data.len()).into_bytes();
    v.extend_from_slice(data);
    v.extend_from_slice(b"\nendstream");
    v
}

fn font(base: &str) -> Vec<u8> {
    format!("<< /Type /Font /Subtype /Type1 /BaseFont /{base} /Encoding /WinAnsiEncoding >>")
        .into_bytes()
}

/// Catalog (1), page tree (2), one page (3) with its content stream (4), then `extra` (5..).
fn page_pdf(
    media: &str,
    page_extra: &str,
    resources: &str,
    ops: &str,
    extra: Vec<Vec<u8>>,
) -> Vec<u8> {
    let mut bodies = vec![
        b"<< /Type /Catalog /Pages 2 0 R >>".to_vec(),
        b"<< /Type /Pages /Kids [3 0 R] /Count 1 >>".to_vec(),
        format!("<< /Type /Page /Parent 2 0 R /MediaBox [{media}] {page_extra} /Resources << {resources} >> /Contents 4 0 R >>")
            .into_bytes(),
        stream("", ops.as_bytes()),
    ];
    bodies.extend(extra);
    pdf(&bodies)
}

/// Black Helvetica and Times text at four sizes, in the top-left 256 pt.
fn text_fixture() -> Vec<u8> {
    let ops = "BT /F1 24 Tf 10 752 Td (Hello Helvetica) Tj ET\n\
               BT /F2 18 Tf 10 712 Td (Times Roman 18) Tj ET\n\
               BT /F1 12 Tf 10 682 Td (Small text 12 pt) Tj ET\n\
               BT /F2 36 Tf 10 622 Td (Big) Tj ET\n";
    page_pdf(
        "0 0 612 792",
        "",
        "/Font << /F1 5 0 R /F2 6 0 R >>",
        ops,
        vec![font("Helvetica"), font("Times-Roman")],
    )
}

/// Filled and stroked rectangles, a bezier, a dashed line and an even-odd ring, on a 300 pt page.
/// The tile at (200, 200) with scale 2 shows user x 100..228, y 72..200.
fn shapes_fixture() -> Vec<u8> {
    let ops = "1 0 0 rg 110 150 40 40 re f\n\
               0 0 1 RG 4 w 160 150 40 40 re S\n\
               0 0.6 0 RG 3 w 110 110 m 130 140 170 80 200 110 c S\n\
               0 0 0 RG 1 w [4 2] 0 d 110 90 m 200 90 l S [] 0 d\n\
               1 0 1 rg 205 120 20 20 re 210 125 10 10 re f*\n";
    page_pdf("0 0 300 300", "", "", ops, vec![])
}

/// A 150 x 200 page with /Rotate 90, so it is displayed 200 wide and 150 tall.
fn rotate_fixture() -> Vec<u8> {
    page_pdf(
        "0 0 150 200",
        "/Rotate 90",
        "/Font << /F1 5 0 R >>",
        "1 0 0 rg 10 20 30 40 re f\n0 g\nBT /F1 24 Tf 80 100 Td (ROT) Tj ET\n",
        vec![font("Helvetica")],
    )
}

/// MediaBox with a non-zero origin (100 100 400 400) and a CropBox inside it (150 150 350 350).
/// The blue square lies in the MediaBox but outside the CropBox and must not show.
fn crop_fixture() -> Vec<u8> {
    page_pdf(
        "100 100 400 400",
        "/CropBox [150 150 350 350]",
        "",
        "1 0 0 rg 160 160 40 40 re f\n0 0 1 rg 100 100 40 40 re f\n",
        vec![],
    )
}

/// A 32 x 32 uncompressed /DeviceRGB gradient, drawn over a 160 pt page (5x upscale).
/// Red rises with x, green with y, blue is 128.
fn image_fixture() -> Vec<u8> {
    let mut rgb = Vec::with_capacity(32 * 32 * 3);
    for y in 0..32u32 {
        for x in 0..32u32 {
            rgb.push((x * 255 / 31) as u8);
            rgb.push((y * 255 / 31) as u8);
            rgb.push(128);
        }
    }
    let image = stream(
        "/Type /XObject /Subtype /Image /Width 32 /Height 32 /ColorSpace /DeviceRGB /BitsPerComponent 8",
        &rgb,
    );
    page_pdf(
        "0 0 160 160",
        "",
        "/XObject << /Im1 5 0 R >>",
        "q 160 0 0 160 0 0 cm /Im1 Do Q\n",
        vec![image],
    )
}

/// Two half-transparent (ca 0.5) rectangles that overlap, through an ExtGState.
fn transparency_fixture() -> Vec<u8> {
    page_pdf(
        "0 0 150 150",
        "",
        "/ExtGState << /GS1 5 0 R >>",
        "/GS1 gs\n1 0 0 rg 20 20 80 80 re f\n0 0 1 rg 60 60 70 70 re f\n",
        vec![b"<< /Type /ExtGState /ca 0.5 /CA 0.5 >>".to_vec()],
    )
}

fn open(bytes: &[u8]) -> std::sync::Arc<PdfSession> {
    PdfSession::open(bytes.to_vec(), None).expect("fixture opens")
}

fn render(session: &PdfSession, s: &Shot) -> RenderedImage {
    session
        .render_tile(0, s.scale, s.x, s.y, s.w, s.h, false)
        .unwrap_or_else(|e| panic!("render {} failed: {e:?}", s.name))
}

fn shot_image(name: &str) -> RenderedImage {
    for fx in fixtures() {
        if let Some(s) = fx.shots.iter().find(|s| s.name == name) {
            return render(&open(&fx.bytes), s);
        }
    }
    panic!("no fixture shot named {name}")
}

/// Checks the shot against the page geometry the engine reports, so a wrong page size cannot
/// pass as a golden. Returns a failure message, if any.
fn geometry_problem(session: &PdfSession, s: &Shot) -> Option<String> {
    let info = session.page_infos().ok()?.into_iter().next()?;
    let pw = (info.width_pt * s.scale).round() as u32;
    let ph = (info.height_pt * s.scale).round() as u32;
    if s.x + s.w > pw || s.y + s.h > ph {
        return Some(format!(
            "{}: tile {}x{} at ({},{}) runs past the {pw}x{ph} page",
            s.name, s.w, s.h, s.x, s.y
        ));
    }
    let is_full = s.x == 0 && s.y == 0 && s.w == pw && s.h == ph;
    let is_tile = s.w == 256 && s.h == 256;
    if !is_full && !is_tile {
        return Some(format!(
            "{}: shot {}x{} is neither a 256 tile nor the full {pw}x{ph} page",
            s.name, s.w, s.h
        ));
    }
    None
}

// ---- pixel helpers --------------------------------------------------------------------------

fn px(img: &RenderedImage, x: u32, y: u32) -> [u8; 4] {
    let i = ((y * img.width + x) * 4) as usize;
    [
        img.rgba[i],
        img.rgba[i + 1],
        img.rgba[i + 2],
        img.rgba[i + 3],
    ]
}

fn count(img: &RenderedImage, pred: impl Fn([u8; 4]) -> bool) -> usize {
    img.rgba
        .chunks_exact(4)
        .filter(|p| pred([p[0], p[1], p[2], p[3]]))
        .count()
}

/// Inclusive bounding box (x0, y0, x1, y1) of the pixels matching `pred`.
fn bbox(img: &RenderedImage, pred: impl Fn([u8; 4]) -> bool) -> Option<(u32, u32, u32, u32)> {
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

fn near(p: [u8; 4], want: [u8; 3], tol: u8) -> bool {
    (0..3).all(|c| p[c].abs_diff(want[c]) <= tol)
}

fn red(p: [u8; 4]) -> bool {
    p[3] == 255 && p[0] > 200 && p[1] < 80 && p[2] < 80
}

fn blue(p: [u8; 4]) -> bool {
    p[3] == 255 && p[2] > 200 && p[0] < 80 && p[1] < 80
}

fn green(p: [u8; 4]) -> bool {
    p[3] == 255 && p[1] > 100 && p[0] < 80 && p[2] < 80
}

fn magenta(p: [u8; 4]) -> bool {
    p[3] == 255 && p[0] > 200 && p[2] > 200 && p[1] < 80
}

fn dark(p: [u8; 4]) -> bool {
    p[3] == 255 && p[0] < 128 && p[1] < 128 && p[2] < 128
}

fn paper(p: [u8; 4]) -> bool {
    near(p, [255, 255, 255], 0) && p[3] == 255
}

fn assert_bbox(
    got: Option<(u32, u32, u32, u32)>,
    want: (u32, u32, u32, u32),
    tol: u32,
    what: &str,
) {
    let g = got.unwrap_or_else(|| panic!("{what}: nothing found"));
    let ok = g.0.abs_diff(want.0) <= tol
        && g.1.abs_diff(want.1) <= tol
        && g.2.abs_diff(want.2) <= tol
        && g.3.abs_diff(want.3) <= tol;
    assert!(ok, "{what}: bounding box {g:?}, expected {want:?} +-{tol}");
}

// ---- codec and comparison -------------------------------------------------------------------

/// `GLD1`, u32 width and u32 height (both little-endian), then raw premultiplied RGBA8.
fn encode(img: &RenderedImage) -> Vec<u8> {
    let mut v = Vec::with_capacity(12 + img.rgba.len());
    v.extend_from_slice(MAGIC);
    v.extend_from_slice(&img.width.to_le_bytes());
    v.extend_from_slice(&img.height.to_le_bytes());
    v.extend_from_slice(&img.rgba);
    v
}

struct Golden {
    width: u32,
    height: u32,
    rgba: Vec<u8>,
}

fn decode(bytes: &[u8]) -> Result<Golden, String> {
    if bytes.len() < 12 || &bytes[..4] != MAGIC {
        return Err("not a GLD1 golden".into());
    }
    let width = u32::from_le_bytes([bytes[4], bytes[5], bytes[6], bytes[7]]);
    let height = u32::from_le_bytes([bytes[8], bytes[9], bytes[10], bytes[11]]);
    let want = (width as usize)
        .checked_mul(height as usize)
        .and_then(|n| n.checked_mul(4))
        .ok_or("golden size overflows")?;
    if bytes.len() - 12 != want {
        return Err(format!(
            "golden holds {} bytes of pixels, {width}x{height} needs {want}",
            bytes.len() - 12
        ));
    }
    Ok(Golden {
        width,
        height,
        rgba: bytes[12..].to_vec(),
    })
}

#[derive(Debug)]
struct Verdict {
    size_ok: bool,
    pixels: usize,
    /// Pixels with a channel more than `CLOSE` away from the golden.
    over_close: usize,
    max_delta: u8,
    /// Pixels with a channel more than `HARD` away.
    over_hard: usize,
}

impl Verdict {
    fn passed(&self) -> bool {
        self.size_ok
            && self.over_hard == 0
            && self.over_close * 1000 <= self.pixels * CLOSE_PER_MILLE
    }
}

fn compare(golden: &Golden, actual: &RenderedImage) -> Verdict {
    let size_ok = golden.width == actual.width && golden.height == actual.height;
    let mut v = Verdict {
        size_ok,
        pixels: 0,
        over_close: 0,
        max_delta: 0,
        over_hard: 0,
    };
    if !size_ok {
        return v;
    }
    for (g, a) in golden.rgba.chunks_exact(4).zip(actual.rgba.chunks_exact(4)) {
        let d = g
            .iter()
            .zip(a)
            .map(|(x, y)| x.abs_diff(*y))
            .max()
            .unwrap_or(0);
        v.pixels += 1;
        v.max_delta = v.max_delta.max(d);
        if d > CLOSE {
            v.over_close += 1;
        }
        if d > HARD {
            v.over_hard += 1;
        }
    }
    v
}

fn fnv1a(bytes: &[u8]) -> u64 {
    let mut h: u64 = 0xcbf2_9ce4_8422_2325;
    for b in bytes {
        h ^= u64::from(*b);
        h = h.wrapping_mul(0x0000_0100_0000_01b3);
    }
    h
}

/// P6 PPM: differing pixels in red, the rest of the golden composited over white.
fn diff_ppm(golden: &Golden, actual: &RenderedImage) -> Vec<u8> {
    let mut out = format!("P6\n{} {}\n255\n", golden.width, golden.height).into_bytes();
    for (g, a) in golden.rgba.chunks_exact(4).zip(actual.rgba.chunks_exact(4)) {
        let d = g
            .iter()
            .zip(a)
            .map(|(x, y)| x.abs_diff(*y))
            .max()
            .unwrap_or(0);
        if d > CLOSE {
            out.extend_from_slice(&[255, 0, 0]);
        } else {
            for c in 0..3 {
                let over_white = u16::from(g[c]) + 255u16.saturating_sub(u16::from(g[3]));
                out.push(over_white.min(255) as u8);
            }
        }
    }
    out
}

fn golden_dir() -> PathBuf {
    Path::new(env!("CARGO_MANIFEST_DIR")).join("golden")
}

fn golden_path(name: &str) -> PathBuf {
    golden_dir().join(format!("{name}.golden"))
}

// ---- golden comparison ----------------------------------------------------------------------

#[test]
fn renders_match_goldens() {
    let update = std::env::var("UPDATE_GOLDEN").is_ok_and(|v| v == "1");
    let mut failures = Vec::new();
    for fx in fixtures() {
        let session = open(&fx.bytes);
        for s in &fx.shots {
            if let Some(problem) = geometry_problem(&session, s) {
                failures.push(problem);
                continue;
            }
            let actual = render(&session, s);
            let again = render(&session, s);
            if actual.width != again.width
                || actual.height != again.height
                || actual.rgba != again.rgba
            {
                failures.push(format!("{}: two renders of the same tile differ", s.name));
                continue;
            }
            let path = golden_path(s.name);
            if update {
                fs::write(&path, encode(&actual)).expect("write golden");
                eprintln!(
                    "wrote {} ({}x{})",
                    path.display(),
                    actual.width,
                    actual.height
                );
                continue;
            }
            let Ok(bytes) = fs::read(&path) else {
                failures.push(format!(
                    "{}: golden {} is missing; run `UPDATE_GOLDEN=1 cargo test -p pdfa-ffi --test golden` to create it",
                    s.name,
                    path.display()
                ));
                continue;
            };
            let golden = match decode(&bytes) {
                Ok(g) => g,
                Err(e) => {
                    failures.push(format!("{}: {} is unreadable: {e}", s.name, path.display()));
                    continue;
                }
            };
            let v = compare(&golden, &actual);
            if v.passed() {
                continue;
            }
            let dir = golden_dir();
            if let Err(e) = fs::write(
                dir.join(format!("{}.actual.golden", s.name)),
                encode(&actual),
            ) {
                failures.push(format!("{}: could not write the actual image: {e}", s.name));
            }
            if v.size_ok {
                let _ = fs::write(
                    dir.join(format!("{}.diff.ppm", s.name)),
                    diff_ppm(&golden, &actual),
                );
            }
            let msg = if v.size_ok {
                format!(
                    "{}: {} of {} pixels differ by more than {CLOSE} (allowed 0.1 %), max delta {}, {} pixels above {HARD}; wrote {}.actual.golden and {}.diff.ppm",
                    s.name, v.over_close, v.pixels, v.max_delta, v.over_hard, s.name, s.name
                )
            } else {
                format!(
                    "{}: size differs: golden {}x{}, render {}x{}",
                    s.name, golden.width, golden.height, actual.width, actual.height
                )
            };
            eprintln!("{msg}");
            failures.push(msg);
        }
    }
    assert!(
        failures.is_empty(),
        "golden mismatches:\n{}",
        failures.join("\n")
    );
}

#[test]
fn golden_files_stay_modest() {
    let total: u64 = fixtures()
        .iter()
        .flat_map(|fx| fx.shots.iter())
        .filter_map(|s| fs::metadata(golden_path(s.name)).ok())
        .map(|m| m.len())
        .sum();
    assert!(total < 1_500_000, "goldens total {total} bytes");
}

// ---- sanity: the fixtures show what they should ---------------------------------------------

#[test]
fn sanity_text_is_black_on_opaque_paper() {
    let img = shot_image("text_tile256_s1");
    assert_eq!((img.width, img.height), (256, 256));
    assert!(
        img.rgba.chunks_exact(4).all(|p| p[3] == 255),
        "paper must be opaque"
    );
    assert!(
        img.rgba
            .chunks_exact(4)
            .all(|p| p[0].abs_diff(p[1]) <= 2 && p[1].abs_diff(p[2]) <= 2),
        "black text on white must be grey"
    );
    let ink = count(&img, dark);
    assert!(ink > 500, "only {ink} dark pixels: the text did not render");
    // "Hello Helvetica" at 24 pt starts at x = 10 with its baseline at y = 40 (device).
    let (x0, y0, _, _) = bbox(&img, dark).expect("ink");
    assert!(
        (8..=14).contains(&x0) && (18..=28).contains(&y0),
        "text should start near (10, 23), got ({x0}, {y0})"
    );
}

#[test]
fn sanity_shapes_are_filled_stroked_curved_dashed_and_even_odd() {
    let img = shot_image("shapes_tile256_s2");
    // User point to tile pixel: device = user * 2 (y flipped on a 300 pt page), minus the tile origin.
    let at = |ux: f32, uy: f32| -> [u8; 4] {
        let x = (ux * 2.0 - 200.0).round() as u32;
        let y = ((300.0 - uy) * 2.0 - 200.0).round() as u32;
        px(&img, x, y)
    };
    // Filled red square, user 110..150 x 150..190 -> tile 20..100 on both axes.
    assert_bbox(bbox(&img, red), (20, 20, 99, 99), 3, "red fill");
    assert!(red(at(130.0, 170.0)), "the fill's centre is not red");
    // Stroked blue square: the edge is blue, the interior is paper (not filled).
    assert!(blue(at(160.0, 170.0)), "the stroke's left edge is not blue");
    assert!(
        paper(at(180.0, 170.0)),
        "the stroked square is filled inside"
    );
    // Bezier: green pixels exist along the curve.
    assert!(count(&img, green) > 50, "no green curve");
    // Even-odd: the ring is magenta, the hole is paper. Nonzero would fill the hole.
    assert!(
        magenta(at(207.0, 130.0)),
        "the even-odd ring is not magenta"
    );
    assert!(
        paper(at(215.0, 130.0)),
        "the even-odd hole is filled: fill rule is not even-odd"
    );
    // Dashed line at user y 90 (tile row 220): dark runs separated by paper gaps.
    let row = (215..=225)
        .max_by_key(|&y| (0..256).filter(|&x| dark(px(&img, x, y))).count())
        .expect("rows");
    let mut runs = 0;
    let mut prev = false;
    for x in 20..=200 {
        let d = dark(px(&img, x, row));
        if d && !prev {
            runs += 1;
        }
        prev = d;
    }
    assert!(
        (10..=20).contains(&runs),
        "dashed line has {runs} dark runs, expected about 15"
    );
    assert!(count(&img, paper) > 1000, "the tile is mostly paper");
}

#[test]
fn sanity_rotate90_displays_the_page_turned_clockwise() {
    let session = open(&rotate_fixture());
    let info = session.page_infos().unwrap()[0].clone();
    assert_eq!(
        (info.width_pt, info.height_pt),
        (200.0, 150.0),
        "a /Rotate 90 page of 150x200 shows as 200x150"
    );
    let img = shot_image("rotate90_full_s1");
    // Clockwise: view x = user y, view y = user x. The red rectangle user x 10..40, y 20..60
    // therefore sits at view x 20..60, y 10..40.
    assert_bbox(
        bbox(&img, red),
        (20, 10, 59, 39),
        2,
        "red rectangle on the rotated page",
    );
    assert!(count(&img, dark) > 100, "the text ROT did not render");
}

#[test]
fn sanity_cropbox_with_a_non_zero_mediabox_origin() {
    let session = open(&crop_fixture());
    let info = session.page_infos().unwrap()[0].clone();
    assert_eq!(
        (info.width_pt, info.height_pt),
        (200.0, 200.0),
        "the view is the CropBox, not the MediaBox"
    );
    let img = shot_image("cropbox_full_s1");
    // View x = user x - 150, view y = 350 - user y. Red user 160..200 x 160..200 -> view 10..50, 150..190.
    assert_bbox(
        bbox(&img, red),
        (10, 150, 49, 189),
        2,
        "red square inside the crop",
    );
    assert_eq!(
        count(&img, blue),
        0,
        "the blue square lies outside the CropBox and must not show"
    );
}

#[test]
fn sanity_image_is_the_gradient_upscaled() {
    let img = shot_image("image_full_s1");
    // Each image pixel covers 5x5 device pixels: column = x / 5, row = y / 5.
    // Column 0 red 0, column 31 red 255; row 31 green 255; blue is 128 everywhere.
    assert!(
        near(px(&img, 2, 2), [0, 0, 128], 12),
        "top-left {:?}",
        px(&img, 2, 2)
    );
    assert!(
        near(px(&img, 157, 2), [255, 0, 128], 12),
        "top-right {:?}",
        px(&img, 157, 2)
    );
    assert!(
        near(px(&img, 2, 157), [0, 255, 128], 12),
        "bottom-left {:?}",
        px(&img, 2, 157)
    );
    assert!(
        near(px(&img, 80, 80), [132, 132, 128], 12),
        "centre {:?}",
        px(&img, 80, 80)
    );
    assert!(
        img.rgba.chunks_exact(4).all(|p| p[3] == 255),
        "an opaque image leaves no transparent pixels"
    );
}

#[test]
fn sanity_transparency_blends_over_paper() {
    let img = shot_image("transparency_full_s1");
    // Device y = 150 - user y. Red at 50 % over white is (255, 128, 128); blue over that is (128, 64, 192).
    assert!(
        near(px(&img, 2, 2), [255, 255, 255], 0) && px(&img, 2, 2)[3] == 255,
        "paper is not white"
    );
    assert!(
        near(px(&img, 30, 120), [255, 128, 128], 4),
        "red only: {:?}",
        px(&img, 30, 120)
    );
    assert!(
        near(px(&img, 120, 30), [128, 128, 255], 4),
        "blue only: {:?}",
        px(&img, 120, 30)
    );
    let overlap = px(&img, 80, 70);
    assert!(
        near(overlap, [128, 64, 192], 4),
        "overlap should be the blend (128, 64, 192), got {overlap:?}"
    );
    assert_eq!(overlap[3], 255, "the overlap is opaque on paper");
}

// ---- comparator and codec unit tests --------------------------------------------------------

fn flat(w: u32, h: u32, v: u8) -> RenderedImage {
    RenderedImage {
        width: w,
        height: h,
        rgba: vec![v; (w * h * 4) as usize],
    }
}

fn golden_of(img: &RenderedImage) -> Golden {
    decode(&encode(img)).expect("round trip")
}

#[test]
fn codec_round_trips() {
    let img = flat(3, 2, 77);
    let bytes = encode(&img);
    assert_eq!(&bytes[..4], b"GLD1");
    assert_eq!(bytes.len(), 12 + 3 * 2 * 4);
    let g = decode(&bytes).unwrap();
    assert_eq!(
        (g.width, g.height, g.rgba.as_slice()),
        (3, 2, img.rgba.as_slice())
    );
    assert!(
        decode(&bytes[..bytes.len() - 1]).is_err(),
        "a short golden must be rejected"
    );
    assert!(
        decode(b"XXXX\0\0\0\0\0\0\0\0").is_err(),
        "a bad magic must be rejected"
    );
}

#[test]
fn comparator_accepts_identical_and_small_noise() {
    let img = flat(100, 100, 200);
    assert!(compare(&golden_of(&img), &img).passed());
    let mut noisy = img.clone();
    // Ten pixels off by 3 on one channel: exactly the 0.1 % allowance at 10 000 pixels.
    for i in 0..10 {
        noisy.rgba[i * 4] += 3;
    }
    let v = compare(&golden_of(&img), &noisy);
    assert_eq!(v.over_close, 10);
    assert!(v.passed(), "{v:?}");
}

#[test]
fn comparator_rejects_too_many_close_misses_and_any_hard_miss() {
    let img = flat(100, 100, 200);
    let mut many = img.clone();
    for i in 0..11 {
        many.rgba[i * 4] += 3;
    }
    assert!(
        !compare(&golden_of(&img), &many).passed(),
        "11 pixels over 2 is above 0.1 %"
    );
    let mut hard = img.clone();
    hard.rgba[0] += 33;
    let v = compare(&golden_of(&img), &hard);
    assert_eq!((v.over_hard, v.max_delta), (1, 33));
    assert!(!v.passed());
}

#[test]
fn comparator_rejects_a_size_change() {
    let a = flat(10, 10, 0);
    let b = flat(10, 11, 0);
    assert!(!compare(&golden_of(&a), &b).passed());
}

// ---- checksums for host versus device -------------------------------------------------------

/// Prints an FNV-1a hash of every fixture render, to compare host and arm64 emulator output.
#[test]
#[ignore = "run on device"]
fn print_checksums() {
    for fx in fixtures() {
        let session = open(&fx.bytes);
        for s in &fx.shots {
            let img = render(&session, s);
            println!(
                "{} {}x{} fnv1a64={:016x}",
                s.name,
                img.width,
                img.height,
                fnv1a(&img.rgba)
            );
        }
    }
}
