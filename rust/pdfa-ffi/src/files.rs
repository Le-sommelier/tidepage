//! Stateless file functions: merge PDFs, images to PDF, and the camera scan steps (find a
//! document's corners, straighten the picture, clean it up). Every export runs under the crate's
//! panic guard, `crate::guard`, which is private at the crate root and therefore reachable here.
//!
//! Pixel buffers are RGBA8, row-major. They are read as premultiplied alpha and composited over
//! white, as a rendered page is. A camera frame is opaque (alpha 255), where premultiplied and
//! straight values are the same. `rectify_and_clean` returns opaque RGBA8, so its output is
//! unaffected by that choice either.

use std::sync::Arc;

use pdfcraft_engine::{
    EditError, ImageResolution, Session, SourceProblem, camera, camera_clean, camera_edges,
    combine_source_check,
};

use crate::{ImageInput, MergeSource, PageRange, PdfError, Pt, Quad, RenderedImage, ScanLook};

/// Total input (all sources, or all images) above this is refused before parsing.
const MAX_TOTAL_INPUT_BYTES: u64 = crate::MAX_INPUT_BYTES as u64;
/// Output above this is refused.
const MAX_OUTPUT_BYTES: u64 = 512 * 1024 * 1024;
/// Camera frames, straightened pictures and decoded images above this many pixels are refused.
const MAX_PIXELS: u64 = 40_000_000;
/// The DPI range `images_to_pdf` accepts when a resolution is given.
const MIN_DPI: f64 = 1.0;
const MAX_DPI: f64 = 1200.0;

/// Merge PDFs, in order, into one PDF. A source may give a password and page ranges (0-based,
/// inclusive); no ranges means the whole document.
#[uniffi::export]
pub fn merge(sources: Vec<MergeSource>) -> Result<Vec<u8>, PdfError> {
    crate::guard(|| merge_impl(sources))
}

/// Create one PDF page per image, sized from each image's resolution. `dpi` overrides that
/// resolution (1 to 1200). HEIC, HEIF, AVIF and WebP are refused as `Unsupported`; the caller
/// converts them to JPEG first. Each image's declared size is checked against the pixel limit
/// before the engine decodes it.
#[uniffi::export]
pub fn images_to_pdf(images: Vec<ImageInput>, dpi: Option<f64>) -> Result<Vec<u8>, PdfError> {
    crate::guard(|| images_to_pdf_impl(images, dpi))
}

/// Find the four corners of the document in a camera frame, clockwise from the top-left, in
/// image pixels. `None` when no quadrilateral convinces the detector.
#[uniffi::export]
pub fn detect_document_corners(
    rgba: Vec<u8>,
    width: u32,
    height: u32,
) -> Result<Option<Quad>, PdfError> {
    crate::guard(|| {
        check_frame(rgba.len(), width, height)?;
        Ok(camera_edges::detect_document(&rgba, width, height).map(quad_from_points))
    })
}

/// Straighten the quadrilateral `quad` of a camera frame to an upright picture, then give it
/// `look` at `level` (0 to 100). The result is opaque RGBA8.
#[uniffi::export]
pub fn rectify_and_clean(
    rgba: Vec<u8>,
    width: u32,
    height: u32,
    quad: Quad,
    look: ScanLook,
    level: u8,
) -> Result<RenderedImage, PdfError> {
    crate::guard(|| rectify_impl(rgba, width, height, quad, look, level))
}

fn merge_impl(sources: Vec<MergeSource>) -> Result<Vec<u8>, PdfError> {
    if sources.is_empty() {
        return Err(invalid("merge needs at least one source"));
    }
    check_input(
        sources
            .iter()
            .fold(0u64, |acc, s| acc.saturating_add(s.bytes.len() as u64)),
    )?;

    let mut inputs: Vec<(String, Arc<Vec<u8>>, Option<String>)> = Vec::with_capacity(sources.len());
    let mut passwords: Vec<Option<String>> = Vec::with_capacity(sources.len());
    for source in sources {
        let range = range_text(&source.ranges)?;
        // Moves: the bytes and the password string are not copied here.
        inputs.push((source.name, Arc::new(source.bytes), range));
        passwords.push(source.password);
    }
    let refs: Vec<Option<&str>> = passwords.iter().map(|p| p.as_deref()).collect();

    let result = match Session::default().combine_unlocked(&inputs, &refs) {
        Ok(bytes) => finish(bytes),
        Err(e) => Err(explain_source_failure(e, &inputs, &refs)),
    };
    drop(refs);
    wipe_passwords(&mut passwords);
    result
}

fn images_to_pdf_impl(images: Vec<ImageInput>, dpi: Option<f64>) -> Result<Vec<u8>, PdfError> {
    if images.is_empty() {
        return Err(invalid("images_to_pdf needs at least one image"));
    }
    let resolution = match dpi {
        None => ImageResolution::Embedded,
        Some(d) if d.is_finite() && (MIN_DPI..=MAX_DPI).contains(&d) => ImageResolution::Dpi(d),
        Some(d) => {
            return Err(invalid(format!(
                "dpi must be between {MIN_DPI} and {MAX_DPI}, got {d}"
            )));
        }
    };
    check_input(
        images
            .iter()
            .fold(0u64, |acc, i| acc.saturating_add(i.bytes.len() as u64)),
    )?;
    for image in &images {
        if let Some(format) = unsupported_format(&image.bytes) {
            return Err(PdfError::Unsupported {
                detail: format!(
                    "{}: {format} images are not supported; convert the image to JPEG or PNG first",
                    image.name
                ),
            });
        }
        match declared_size(&image.bytes) {
            Ok(Some((w, h))) => check_pixels(w, h)?,
            Ok(None) => {}
            Err(why) => return Err(invalid(format!("{}: {why}", image.name))),
        }
    }
    let inputs: Vec<(String, Vec<u8>)> = images.into_iter().map(|i| (i.name, i.bytes)).collect();
    let out = Session::default()
        .create_from_images_with_resolution(&inputs, resolution)
        .map_err(map_edit_error)?;
    finish(out)
}

fn rectify_impl(
    rgba: Vec<u8>,
    width: u32,
    height: u32,
    quad: Quad,
    look: ScanLook,
    level: u8,
) -> Result<RenderedImage, PdfError> {
    check_frame(rgba.len(), width, height)?;
    if level > camera_clean::MAX_LEVEL {
        return Err(invalid(format!(
            "level must be 0 to {}, got {level}",
            camera_clean::MAX_LEVEL
        )));
    }
    let Quad {
        top_left,
        top_right,
        bottom_right,
        bottom_left,
    } = quad;
    let corners = [
        point_from(top_left)?,
        point_from(top_right)?,
        point_from(bottom_right)?,
        point_from(bottom_left)?,
    ];
    check_rectify_request(&corners, width, height)?;

    let camera::Warped {
        width: out_w,
        height: out_h,
        rgb,
    } = camera::warp(&rgba, width, height, corners).map_err(invalid)?;
    drop(rgba);
    // warp caps its own output at the same pixel limit; this checks what it actually returned.
    check_pixels(u64::from(out_w), u64::from(out_h))?;

    let look = match look {
        ScanLook::Original => camera_clean::Look::Original,
        ScanLook::Colour => camera_clean::Look::Colour,
        ScanLook::Greyscale => camera_clean::Look::Greyscale,
        ScanLook::BlackWhite => camera_clean::Look::BlackWhite,
    };
    let pixels = camera_clean::apply(&rgb, out_w, out_h, look, level)
        .ok_or_else(|| invalid("the straightened picture does not match its size"))?;
    drop(rgb);
    let rgba = match pixels {
        camera_clean::Pixels::Rgb(rgb) => {
            let mut out = Vec::with_capacity(rgb.len() / 3 * 4);
            for px in rgb.as_chunks::<3>().0 {
                let [r, g, b] = *px;
                out.extend_from_slice(&[r, g, b, 255]);
            }
            out
        }
        camera_clean::Pixels::Grey(grey) => {
            let mut out = Vec::with_capacity(grey.len() * 4);
            for v in grey {
                out.extend_from_slice(&[v, v, v, 255]);
            }
            out
        }
    };
    check_output(rgba.len() as u64)?;
    Ok(RenderedImage {
        width: out_w,
        height: out_h,
        rgba,
    })
}

/// Before `warp` runs: the corners must be near the frame, and the picture they describe must
/// fit the pixel limit. Sides are measured the way `warp` measures them (floor of the longer
/// pair), so the check matches what `warp` would produce.
fn check_rectify_request(corners: &[[f64; 2]; 4], width: u32, height: u32) -> Result<(), PdfError> {
    let limit = 4.0 * f64::from(width.max(height));
    if corners.iter().flatten().any(|v| v.abs() > limit) {
        return Err(invalid("quad corners lie far outside the frame"));
    }
    let [tl, tr, br, bl] = *corners;
    let dist = |a: [f64; 2], b: [f64; 2]| (a[0] - b[0]).hypot(a[1] - b[1]);
    let ow = dist(tl, tr).max(dist(bl, br)).floor() as u64;
    let oh = dist(tl, bl).max(dist(tr, br)).floor() as u64;
    check_pixels(ow.max(1), oh.max(1))
}

/// The page ranges as the engine's 1-based text ("1-3, 6"); `None` for no ranges (whole files).
/// Ranges may overlap or repeat; the engine decides what that selects.
fn range_text(ranges: &[PageRange]) -> Result<Option<String>, PdfError> {
    if ranges.is_empty() {
        return Ok(None);
    }
    let mut parts = Vec::with_capacity(ranges.len());
    for r in ranges {
        if r.start > r.end_inclusive {
            return Err(invalid(format!(
                "page range {}-{} runs backwards",
                r.start, r.end_inclusive
            )));
        }
        let (Some(first), Some(last)) = (r.start.checked_add(1), r.end_inclusive.checked_add(1))
        else {
            return Err(invalid("page range is out of range"));
        };
        parts.push(if first == last {
            first.to_string()
        } else {
            format!("{first}-{last}")
        });
    }
    Ok(Some(parts.join(", ")))
}

/// A source as the merge passes it to the engine: name, bytes and range text.
type MergeInput = (String, Arc<Vec<u8>>, Option<String>);

/// Map a combine failure. Only failures that can come from a source (the engine reports them as
/// `Source`, or `WrongPassword`) re-check each source; the rest map directly.
fn explain_source_failure(
    e: EditError,
    inputs: &[MergeInput],
    passwords: &[Option<&str>],
) -> PdfError {
    if matches!(e, EditError::Source(_) | EditError::WrongPassword) {
        explain_merge_failure(inputs, passwords).unwrap_or_else(|| map_edit_error(e))
    } else {
        map_edit_error(e)
    }
}

/// The reason a source cannot be combined, found by checking each source on its own.
fn explain_merge_failure(inputs: &[MergeInput], passwords: &[Option<&str>]) -> Option<PdfError> {
    for (i, (name, bytes, _)) in inputs.iter().enumerate() {
        let password = passwords.get(i).copied().flatten();
        match combine_source_check(bytes, password) {
            Ok(()) => {}
            Err(SourceProblem::Password) => return Some(PdfError::PasswordRequired),
            Err(SourceProblem::WrongPassword) => return Some(PdfError::WrongPassword),
            Err(SourceProblem::NotPermitted) => {
                return Some(PdfError::InvalidPdf {
                    detail: format!("{name}: its security settings don't allow copying pages"),
                });
            }
            Err(SourceProblem::Unreadable(detail)) => {
                return Some(PdfError::InvalidPdf {
                    detail: format!("{name}: {detail}"),
                });
            }
        }
    }
    None
}

/// Overwrite the password strings before they are released. Best effort: earlier copies made
/// inside the engine are not reachable from here, and no zeroize crate is a direct dependency.
fn wipe_passwords(passwords: &mut [Option<String>]) {
    for slot in passwords.iter_mut() {
        if let Some(p) = slot.as_mut() {
            let len = p.len();
            p.clear();
            p.extend(std::iter::repeat_n('\0', len));
            p.clear();
        }
    }
}

/// Map an engine error to the contract's error kinds. The engine's text goes in `detail`.
fn map_edit_error(error: EditError) -> PdfError {
    match error {
        EditError::WrongPassword => PdfError::WrongPassword,
        EditError::Source(detail) | EditError::Reopen(detail) => PdfError::InvalidPdf { detail },
        EditError::Print(detail) | EditError::Invalid(detail) => {
            PdfError::InvalidArgument { detail }
        }
        EditError::Create(e) => PdfError::InvalidArgument {
            detail: e.to_string(),
        },
        EditError::Write(detail) => PdfError::Io { detail },
        EditError::Cancelled => PdfError::Cancelled,
        other => PdfError::InvalidPdf {
            detail: other.to_string(),
        },
    }
}

/// The name of an image format the engine cannot read, if `bytes` is one.
fn unsupported_format(bytes: &[u8]) -> Option<&'static str> {
    const HEIF_BRANDS: [&[u8]; 10] = [
        b"heic", b"heix", b"hevc", b"hevx", b"heim", b"heis", b"mif1", b"msf1", b"avif", b"avis",
    ];
    if bytes.get(4..8) == Some(b"ftyp".as_slice())
        && bytes
            .get(8..12)
            .is_some_and(|brand| HEIF_BRANDS.contains(&brand))
    {
        return Some("HEIC, HEIF or AVIF");
    }
    if bytes.get(0..4) == Some(b"RIFF".as_slice()) && bytes.get(8..12) == Some(b"WEBP".as_slice()) {
        return Some("WebP");
    }
    None
}

/// The size an image's header declares: the largest frame, for multi-frame files. `Ok(None)` when
/// the bytes match no format this check reads (the engine then judges them). `Err` when the
/// signature matches but the header cannot be read: such a file is not decoded.
fn declared_size(bytes: &[u8]) -> Result<Option<(u64, u64)>, &'static str> {
    let bad = "image header is truncated or unreadable";
    let size = if bytes.starts_with(b"\x89PNG\r\n\x1a\n") {
        Some(png_size(bytes).ok_or(bad)?)
    } else if bytes.starts_with(&[0xFF, 0xD8]) {
        Some(jpeg_size(bytes).ok_or(bad)?)
    } else if bytes.starts_with(b"II*\0")
        || bytes.starts_with(b"MM\0*")
        || bytes.starts_with(b"II+\0")
        || bytes.starts_with(b"MM\0+")
    {
        Some(tiff_size(bytes).ok_or(bad)?)
    } else if bytes.starts_with(b"BM") {
        Some(bmp_size(bytes).ok_or(bad)?)
    } else if bytes.starts_with(b"GIF87a") || bytes.starts_with(b"GIF89a") {
        Some(gif_size(bytes).ok_or(bad)?)
    } else if bytes.first() == Some(&0x0A)
        && matches!(bytes.get(1), Some(0 | 2..=5))
        && bytes.get(2) == Some(&1)
        && matches!(bytes.get(3), Some(1 | 8))
    {
        Some(pcx_size(bytes).ok_or(bad)?)
    } else if bytes.starts_with(&[0, 0, 0, 0x0C, b'j', b'P', b' ', b' '])
        || bytes.starts_with(&[0xFF, 0x4F, 0xFF, 0x51])
    {
        Some(jpeg2000_size(bytes).ok_or(bad)?)
    } else {
        None
    };
    Ok(size)
}

/// Read an unsigned integer of `width` bytes (at most 8) at `at`, in the given byte order.
fn read_uint(b: &[u8], at: usize, width: usize, little_endian: bool) -> Option<u64> {
    let s = b.get(at..at.checked_add(width)?)?;
    Some(if little_endian {
        s.iter()
            .enumerate()
            .fold(0, |acc, (i, &x)| acc | (u64::from(x) << (8 * i)))
    } else {
        s.iter().fold(0, |acc, &x| (acc << 8) | u64::from(x))
    })
}

/// A size is usable only when both sides are at least 1.
fn nonzero(w: u64, h: u64) -> Option<(u64, u64)> {
    (w > 0 && h > 0).then_some((w, h))
}

fn png_size(b: &[u8]) -> Option<(u64, u64)> {
    if b.get(12..16)? != b"IHDR" {
        return None;
    }
    nonzero(read_uint(b, 16, 4, false)?, read_uint(b, 20, 4, false)?)
}

fn jpeg_size(b: &[u8]) -> Option<(u64, u64)> {
    let mut i = 2usize;
    loop {
        if *b.get(i)? != 0xFF {
            return None;
        }
        let marker = *b.get(i + 1)?;
        if marker == 0xFF {
            i += 1;
            continue;
        }
        // Markers without a length: stand-alone ones, and the restart markers.
        if marker == 0x01 || (0xD0..=0xD8).contains(&marker) {
            i += 2;
            continue;
        }
        if marker == 0xD9 {
            return None;
        }
        let len = read_uint(b, i + 2, 2, false)? as usize;
        if len < 2 {
            return None;
        }
        // Start-of-frame markers, excluding DHT (C4), JPG (C8) and DAC (CC).
        if (0xC0..=0xCF).contains(&marker) && !matches!(marker, 0xC4 | 0xC8 | 0xCC) {
            return nonzero(
                read_uint(b, i + 7, 2, false)?,
                read_uint(b, i + 5, 2, false)?,
            );
        }
        i = i.checked_add(2 + len)?;
    }
}

/// The largest frame of a TIFF (classic or BigTIFF), walking every IFD.
fn tiff_size(b: &[u8]) -> Option<(u64, u64)> {
    let le = b.starts_with(b"II");
    let big = read_uint(b, 2, 2, le)? == 43;
    // Offsets and counts are 4 bytes in a classic TIFF, 8 in BigTIFF; entries are 2+2+2*offset.
    let (off_w, count_w) = if big { (8, 8) } else { (4, 2) };
    let entry = 4 + 2 * off_w;
    let mut ifd = read_uint(b, if big { 8 } else { 4 }, off_w, le)?;
    let mut best: Option<(u64, u64)> = None;
    for _ in 0..10_000 {
        if ifd == 0 {
            break;
        }
        let at = usize::try_from(ifd).ok()?;
        let n = read_uint(b, at, count_w, le)?;
        let (mut w, mut h) = (None, None);
        for k in 0..n {
            let e = at
                .checked_add(count_w)?
                .checked_add(usize::try_from(k).ok()?.checked_mul(entry)?)?;
            let tag = read_uint(b, e, 2, le)?;
            let value_width = match read_uint(b, e + 2, 2, le)? {
                3 => 2,
                4 => 4,
                16 => 8,
                _ => continue,
            };
            let value = read_uint(b, e + 4 + off_w, value_width, le)?;
            match tag {
                256 => w = Some(value),
                257 => h = Some(value),
                _ => {}
            }
        }
        if let (Some(w), Some(h)) = (w, h) {
            let (w, h) = nonzero(w, h)?;
            if best.is_none_or(|(bw, bh)| w.saturating_mul(h) > bw.saturating_mul(bh)) {
                best = Some((w, h));
            }
        }
        let after = at
            .checked_add(count_w)?
            .checked_add(usize::try_from(n).ok()?.checked_mul(entry)?)?;
        ifd = read_uint(b, after, off_w, le)?;
    }
    best
}

fn bmp_size(b: &[u8]) -> Option<(u64, u64)> {
    let w = (read_uint(b, 18, 4, true)? as u32 as i32).unsigned_abs();
    let h = (read_uint(b, 22, 4, true)? as u32 as i32).unsigned_abs();
    nonzero(u64::from(w), u64::from(h))
}

fn gif_size(b: &[u8]) -> Option<(u64, u64)> {
    nonzero(read_uint(b, 6, 2, true)?, read_uint(b, 8, 2, true)?)
}

fn pcx_size(b: &[u8]) -> Option<(u64, u64)> {
    let (xmin, ymin) = (read_uint(b, 4, 2, true)?, read_uint(b, 6, 2, true)?);
    let (xmax, ymax) = (read_uint(b, 8, 2, true)?, read_uint(b, 10, 2, true)?);
    nonzero(xmax.checked_sub(xmin)? + 1, ymax.checked_sub(ymin)? + 1)
}

/// The image area of a JPEG 2000 codestream (SIZ marker: image extent minus offset).
fn jpeg2000_size(b: &[u8]) -> Option<(u64, u64)> {
    let p = b.windows(4).position(|w| w == [0xFF, 0x4F, 0xFF, 0x51])?;
    let siz = p + 2;
    let x_end = read_uint(b, siz + 6, 4, false)?;
    let y_end = read_uint(b, siz + 10, 4, false)?;
    let x_off = read_uint(b, siz + 14, 4, false)?;
    let y_off = read_uint(b, siz + 18, 4, false)?;
    nonzero(x_end.checked_sub(x_off)?, y_end.checked_sub(y_off)?)
}

/// Refuse a frame whose size and buffer do not describe the same pixels.
fn check_frame(len: usize, width: u32, height: u32) -> Result<(), PdfError> {
    if width == 0 || height == 0 {
        return Err(invalid(format!(
            "frame is {width}x{height}; both sides must be at least 1"
        )));
    }
    check_pixels(u64::from(width), u64::from(height))?;
    let expected = u64::from(width) * u64::from(height) * 4;
    if len as u64 != expected {
        return Err(invalid(format!(
            "rgba has {len} bytes, expected {expected} for {width}x{height}"
        )));
    }
    Ok(())
}

/// Refuse a picture of more than `MAX_PIXELS` pixels, computed without overflow.
fn check_pixels(width: u64, height: u64) -> Result<(), PdfError> {
    match width.checked_mul(height) {
        Some(pixels) if pixels <= MAX_PIXELS => Ok(()),
        _ => Err(PdfError::TooLarge {
            detail: format!("{width}x{height} is more than {MAX_PIXELS} pixels"),
        }),
    }
}

fn check_input(total: u64) -> Result<(), PdfError> {
    if total > MAX_TOTAL_INPUT_BYTES {
        return Err(PdfError::TooLarge {
            detail: format!("input is {total} bytes, limit is {MAX_TOTAL_INPUT_BYTES}"),
        });
    }
    Ok(())
}

fn check_output(len: u64) -> Result<(), PdfError> {
    if len > MAX_OUTPUT_BYTES {
        return Err(PdfError::TooLarge {
            detail: format!("output is {len} bytes, limit is {MAX_OUTPUT_BYTES}"),
        });
    }
    Ok(())
}

/// Check the output size and hand the bytes over. The engine returns a uniquely held buffer, so
/// normally nothing is copied. If it were shared, the fallback clone would copy up to 512 MiB.
fn finish(out: Arc<Vec<u8>>) -> Result<Vec<u8>, PdfError> {
    check_output(out.len() as u64)?;
    Ok(Arc::try_unwrap(out).unwrap_or_else(|shared| (*shared).clone()))
}

fn invalid(detail: impl Into<String>) -> PdfError {
    PdfError::InvalidArgument {
        detail: detail.into(),
    }
}

fn quad_from_points([tl, tr, br, bl]: [[f64; 2]; 4]) -> Quad {
    Quad {
        top_left: pt_from(tl),
        top_right: pt_from(tr),
        bottom_right: pt_from(br),
        bottom_left: pt_from(bl),
    }
}

fn pt_from([x, y]: [f64; 2]) -> Pt {
    Pt {
        x: x as f32,
        y: y as f32,
    }
}

fn point_from(p: Pt) -> Result<[f64; 2], PdfError> {
    let (x, y) = (f64::from(p.x), f64::from(p.y));
    if x.is_finite() && y.is_finite() {
        Ok([x, y])
    } else {
        Err(invalid("quad corners must be finite numbers"))
    }
}

#[cfg(test)]
mod tests {
    //! Tests live here rather than in tests/files.rs because `mod files` is private in lib.rs.
    //! Moving them is a `crate::` to `pdfa_ffi::` change once lib.rs re-exports the functions.

    use super::*;
    use crate::{PageRange, PdfDoc, PdfError};
    use pdfcraft_engine::{Edit, Protection};

    /// A PDF whose pages have the given sizes in points, each with the text "Page n" in Helvetica.
    fn build_pdf(sizes: &[(u32, u32)]) -> Vec<u8> {
        let n = sizes.len();
        let font_id = 3 + 2 * n;
        let mut objects: Vec<String> = vec![
            "<< /Type /Catalog /Pages 2 0 R >>".into(),
            format!(
                "<< /Type /Pages /Kids [{}] /Count {n} >>",
                (0..n)
                    .map(|i| format!("{} 0 R", 3 + 2 * i))
                    .collect::<Vec<_>>()
                    .join(" ")
            ),
        ];
        for (i, (w, h)) in sizes.iter().enumerate() {
            let content_id = 4 + 2 * i;
            objects.push(format!(
                "<< /Type /Page /Parent 2 0 R /MediaBox [0 0 {w} {h}] /Resources << /Font << /F1 {font_id} 0 R >> >> /Contents {content_id} 0 R >>"
            ));
            let text = format!("BT /F1 24 Tf 40 40 Td (Page {}) Tj ET", i + 1);
            objects.push(format!(
                "<< /Length {} >>\nstream\n{text}\nendstream",
                text.len()
            ));
        }
        objects.push("<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica >>".into());

        let mut out = b"%PDF-1.4\n".to_vec();
        let mut offsets = Vec::new();
        for (i, body) in objects.iter().enumerate() {
            offsets.push(out.len());
            out.extend_from_slice(format!("{} 0 obj\n{body}\nendobj\n", i + 1).as_bytes());
        }
        let xref = out.len();
        out.extend_from_slice(
            format!("xref\n0 {}\n0000000000 65535 f \n", objects.len() + 1).as_bytes(),
        );
        for off in offsets {
            out.extend_from_slice(format!("{off:010} 00000 n \n").as_bytes());
        }
        out.extend_from_slice(
            format!(
                "trailer\n<< /Size {} /Root 1 0 R >>\nstartxref\n{xref}\n%%EOF\n",
                objects.len() + 1
            )
            .as_bytes(),
        );
        out
    }

    /// The same PDF encrypted with an open password (AES-256), made by the upstream engine.
    fn encrypted_pdf(sizes: &[(u32, u32)], password: &str) -> Vec<u8> {
        let mut s = Session::default();
        let id = s
            .open("source.pdf", None, Arc::new(build_pdf(sizes)), None)
            .unwrap();
        s.apply(
            id,
            Edit::Protect(Protection {
                open_password: Some(password.into()),
                ..Protection::default()
            }),
        )
        .unwrap();
        s.save_bytes(id).unwrap().to_vec()
    }

    fn adler32(data: &[u8]) -> u32 {
        let (mut a, mut b) = (1u32, 0u32);
        for &x in data {
            a = (a + u32::from(x)) % 65521;
            b = (b + a) % 65521;
        }
        (b << 16) | a
    }

    fn crc32(data: &[u8]) -> u32 {
        let mut crc = 0xFFFF_FFFFu32;
        for &byte in data {
            crc ^= u32::from(byte);
            for _ in 0..8 {
                let mask = (crc & 1).wrapping_neg();
                crc = (crc >> 1) ^ (0xEDB8_8320 & mask);
            }
        }
        !crc
    }

    fn png_chunk(out: &mut Vec<u8>, kind: &[u8; 4], data: &[u8]) {
        out.extend_from_slice(&(data.len() as u32).to_be_bytes());
        let mut body = kind.to_vec();
        body.extend_from_slice(data);
        out.extend_from_slice(&body);
        out.extend_from_slice(&crc32(&body).to_be_bytes());
    }

    /// A PNG of `width` x `height` RGB pixels, with stored (uncompressed) deflate blocks. It has
    /// no pHYs chunk, so the engine sizes its page at 72 dpi (one point per pixel).
    fn png(width: u32, height: u32, pixel: impl Fn(u32, u32) -> [u8; 3]) -> Vec<u8> {
        let mut raw = Vec::with_capacity((width as usize * 3 + 1) * height as usize);
        for y in 0..height {
            raw.push(0);
            for x in 0..width {
                raw.extend_from_slice(&pixel(x, y));
            }
        }
        let mut z = vec![0x78, 0x01];
        let blocks: Vec<&[u8]> = raw.chunks(65535).collect();
        for (i, block) in blocks.iter().enumerate() {
            z.push(u8::from(i + 1 == blocks.len()));
            let len = block.len() as u16;
            z.extend_from_slice(&len.to_le_bytes());
            z.extend_from_slice(&(!len).to_le_bytes());
            z.extend_from_slice(block);
        }
        z.extend_from_slice(&adler32(&raw).to_be_bytes());

        let mut out = b"\x89PNG\r\n\x1a\n".to_vec();
        let mut ihdr = Vec::new();
        ihdr.extend_from_slice(&width.to_be_bytes());
        ihdr.extend_from_slice(&height.to_be_bytes());
        ihdr.extend_from_slice(&[8, 2, 0, 0, 0]);
        png_chunk(&mut out, b"IHDR", &ihdr);
        png_chunk(&mut out, b"IDAT", &z);
        png_chunk(&mut out, b"IEND", &[]);
        out
    }

    /// A small valid PNG whose IHDR declares a huge size: a decompression-bomb shape. The IDAT
    /// data is for 1x1 only, so the engine could not decode it anyway.
    fn png_declaring(width: u32, height: u32) -> Vec<u8> {
        let mut bytes = png(1, 1, |_, _| [0, 0, 0]);
        bytes[16..20].copy_from_slice(&width.to_be_bytes());
        bytes[20..24].copy_from_slice(&height.to_be_bytes());
        bytes
    }

    fn source(
        name: &str,
        bytes: Vec<u8>,
        password: Option<&str>,
        ranges: Vec<PageRange>,
    ) -> MergeSource {
        MergeSource {
            name: name.into(),
            bytes,
            password: password.map(Into::into),
            ranges,
        }
    }

    fn range(start: u32, end_inclusive: u32) -> PageRange {
        PageRange {
            start,
            end_inclusive,
        }
    }

    fn widths(pdf: &[u8]) -> Vec<f32> {
        let doc = PdfDoc::open(pdf.to_vec(), None).expect("merged output reopens");
        (0..doc.page_count().unwrap())
            .map(|i| doc.page_size(i).unwrap().width_pt)
            .collect()
    }

    fn doc_a() -> Vec<u8> {
        build_pdf(&[(100, 100), (200, 200), (300, 300)])
    }

    fn doc_b() -> Vec<u8> {
        build_pdf(&[(400, 150), (150, 400)])
    }

    #[test]
    fn merge_keeps_page_order_and_counts_all_pages() {
        let out = merge(vec![
            source("a.pdf", doc_a(), None, vec![]),
            source("b.pdf", doc_b(), None, vec![]),
        ])
        .unwrap();
        assert_eq!(widths(&out), [100.0, 200.0, 300.0, 400.0, 150.0]);
    }

    #[test]
    fn merge_takes_only_the_chosen_ranges() {
        let out = merge(vec![
            source("a.pdf", doc_a(), None, vec![range(0, 0), range(2, 2)]),
            source("b.pdf", doc_b(), None, vec![range(1, 1)]),
        ])
        .unwrap();
        assert_eq!(widths(&out), [100.0, 300.0, 150.0]);
    }

    #[test]
    fn overlapping_ranges_are_passed_to_the_engine_as_text() {
        // "1-3, 2-4" in the engine's 1-based text, from overlapping 0-based ranges.
        assert_eq!(
            range_text(&[range(0, 2), range(1, 3)]).unwrap().as_deref(),
            Some("1-3, 2-4")
        );
        let four = build_pdf(&[(100, 100), (200, 200), (300, 300), (400, 400)]);
        let out = merge(vec![source(
            "four.pdf",
            four,
            None,
            vec![range(0, 2), range(1, 3)],
        )])
        .unwrap();
        // Whatever the engine selects, every page must come from the source and the output reopens.
        let w = widths(&out);
        assert!(
            !w.is_empty() && w.iter().all(|x| [100.0, 200.0, 300.0, 400.0].contains(x)),
            "widths {w:?}"
        );
    }

    #[test]
    fn ranges_with_a_password_select_from_the_unlocked_source() {
        let locked = encrypted_pdf(&[(100, 100), (200, 200), (300, 300)], "secret");
        let out = merge(vec![source(
            "locked.pdf",
            locked,
            Some("secret"),
            vec![range(1, 1)],
        )])
        .unwrap();
        assert_eq!(widths(&out), [200.0]);
    }

    #[test]
    fn merge_output_renders() {
        let out = merge(vec![source("a.pdf", doc_a(), None, vec![])]).unwrap();
        let img = PdfDoc::open(out, None)
            .unwrap()
            .render_page(0, 1.0)
            .unwrap();
        assert_eq!((img.width, img.height), (100, 100));
    }

    #[test]
    fn merge_with_password_protected_source() {
        let locked = encrypted_pdf(&[(100, 100), (200, 200)], "secret");
        assert!(matches!(
            merge(vec![source("locked.pdf", locked.clone(), None, vec![])]),
            Err(PdfError::PasswordRequired)
        ));
        assert!(matches!(
            merge(vec![source(
                "locked.pdf",
                locked.clone(),
                Some("wrong"),
                vec![]
            )]),
            Err(PdfError::WrongPassword)
        ));
        let out = merge(vec![source("locked.pdf", locked, Some("secret"), vec![])]).unwrap();
        assert_eq!(widths(&out), [100.0, 200.0]);
    }

    #[test]
    fn merge_refuses_empty_list_and_bad_input() {
        assert!(matches!(
            merge(vec![]),
            Err(PdfError::InvalidArgument { .. })
        ));
        assert!(matches!(
            merge(vec![source(
                "junk.pdf",
                b"not a pdf".to_vec(),
                None,
                vec![]
            )]),
            Err(PdfError::InvalidPdf { .. })
        ));
        assert!(matches!(
            merge(vec![source("a.pdf", doc_a(), None, vec![range(0, 9)])]),
            Err(PdfError::InvalidArgument { .. })
        ));
        assert!(matches!(
            merge(vec![source("a.pdf", doc_a(), None, vec![range(2, 0)])]),
            Err(PdfError::InvalidArgument { .. })
        ));
    }

    #[test]
    fn merge_refuses_inputs_over_the_limit_without_parsing() {
        let big = vec![0u8; MAX_TOTAL_INPUT_BYTES as usize + 1];
        assert!(matches!(
            merge(vec![source("big.pdf", big, None, vec![])]),
            Err(PdfError::TooLarge { .. })
        ));
    }

    #[test]
    fn range_text_is_one_based_and_joined() {
        assert_eq!(range_text(&[]).unwrap(), None);
        assert_eq!(
            range_text(&[range(0, 2), range(5, 5)]).unwrap().as_deref(),
            Some("1-3, 6")
        );
        assert!(range_text(&[range(u32::MAX, u32::MAX)]).is_err());
    }

    fn png_pair() -> Vec<ImageInput> {
        vec![
            ImageInput {
                name: "a.png".into(),
                bytes: png(40, 60, |x, y| [(x * 6) as u8, (y * 4) as u8, 128]),
            },
            ImageInput {
                name: "b.png".into(),
                bytes: png(80, 30, |_, _| [250, 250, 250]),
            },
        ]
    }

    fn page_sizes(pdf: &[u8]) -> Vec<(f32, f32)> {
        let doc = PdfDoc::open(pdf.to_vec(), None).expect("images_to_pdf output reopens");
        (0..doc.page_count().unwrap())
            .map(|i| {
                let s = doc.page_size(i).unwrap();
                (s.width_pt, s.height_pt)
            })
            .collect()
    }

    #[test]
    fn images_become_one_page_each_at_their_size() {
        let out = images_to_pdf(png_pair(), None).unwrap();
        assert_eq!(page_sizes(&out), [(40.0, 60.0), (80.0, 30.0)]);
    }

    #[test]
    fn dpi_override_changes_the_page_size() {
        let out = images_to_pdf(png_pair(), Some(144.0)).unwrap();
        assert_eq!(page_sizes(&out), [(20.0, 30.0), (40.0, 15.0)]);
    }

    #[test]
    fn dpi_outside_the_range_is_invalid_argument() {
        for dpi in [0.0, 5000.0, f64::NAN, f64::INFINITY] {
            assert!(
                matches!(
                    images_to_pdf(png_pair(), Some(dpi)),
                    Err(PdfError::InvalidArgument { .. })
                ),
                "dpi {dpi}"
            );
        }
    }

    #[test]
    fn heic_is_unsupported_not_a_panic() {
        let mut heic = vec![0, 0, 0, 0x18];
        heic.extend_from_slice(b"ftypheic");
        heic.extend_from_slice(&[0u8; 12]);
        let images = vec![ImageInput {
            name: "photo.heic".into(),
            bytes: heic,
        }];
        assert!(matches!(
            images_to_pdf(images, None),
            Err(PdfError::Unsupported { .. })
        ));
    }

    #[test]
    fn garbage_images_are_refused_cleanly() {
        let garbage = vec![ImageInput {
            name: "x.bin".into(),
            bytes: b"garbage bytes".to_vec(),
        }];
        assert!(matches!(
            images_to_pdf(garbage, None),
            Err(PdfError::InvalidArgument { .. })
        ));
        assert!(matches!(
            images_to_pdf(vec![], None),
            Err(PdfError::InvalidArgument { .. })
        ));
    }

    #[test]
    fn zero_length_image_is_invalid_argument() {
        let empty = vec![ImageInput {
            name: "empty.png".into(),
            bytes: vec![],
        }];
        assert!(matches!(
            images_to_pdf(empty, None),
            Err(PdfError::InvalidArgument { .. })
        ));
    }

    #[test]
    fn decompression_bomb_png_is_refused_before_decoding() {
        // IHDR declares 100000 x 100000 (10^10 pixels) with 1x1 image data.
        let bomb = vec![ImageInput {
            name: "bomb.png".into(),
            bytes: png_declaring(100_000, 100_000),
        }];
        assert!(matches!(
            images_to_pdf(bomb, None),
            Err(PdfError::TooLarge { .. })
        ));
    }

    #[test]
    fn a_png_with_a_truncated_header_is_invalid_argument() {
        let mut bytes = png(4, 4, |_, _| [0, 0, 0]);
        bytes.truncate(20);
        assert!(matches!(
            images_to_pdf(
                vec![ImageInput {
                    name: "t.png".into(),
                    bytes
                }],
                None
            ),
            Err(PdfError::InvalidArgument { .. })
        ));
    }

    #[test]
    fn declared_sizes_for_each_format() {
        // JPEG: SOI, then SOF0 declaring 300 x 200.
        let jpeg = [
            0xFF, 0xD8, 0xFF, 0xC0, 0x00, 0x11, 0x08, 0x00, 0xC8, 0x01, 0x2C, 0x03, 0, 0, 0, 0, 0,
            0, 0, 0, 0, 0, 0, 0, 0, 0, 0,
        ];
        assert_eq!(declared_size(&jpeg), Ok(Some((300, 200))));
        // JPEG with a huge declared SOF.
        let jpeg_huge = [
            0xFF, 0xD8, 0xFF, 0xC0, 0x00, 0x11, 0x08, 0xFF, 0xFF, 0xFF, 0xFF, 0x03, 0, 0, 0, 0, 0,
            0, 0, 0, 0, 0, 0, 0, 0, 0, 0,
        ];
        assert_eq!(declared_size(&jpeg_huge), Ok(Some((65535, 65535))));
        // BMP: 64 x 32 (BITMAPINFOHEADER at offset 14).
        let mut bmp = vec![0u8; 54];
        bmp[0..2].copy_from_slice(b"BM");
        bmp[18..22].copy_from_slice(&64i32.to_le_bytes());
        bmp[22..26].copy_from_slice(&(-32i32).to_le_bytes());
        assert_eq!(declared_size(&bmp), Ok(Some((64, 32))));
        // GIF89a with logical screen 20 x 10.
        let mut gif = b"GIF89a".to_vec();
        gif.extend_from_slice(&[20, 0, 10, 0, 0, 0, 0]);
        assert_eq!(declared_size(&gif), Ok(Some((20, 10))));
        // Unknown bytes are left to the engine.
        assert_eq!(declared_size(b"garbage"), Ok(None));
        // A signature with a truncated header is an error.
        assert!(declared_size(&b"GIF89a"[..]).is_err());
    }

    #[test]
    fn declared_tiff_size_takes_the_largest_frame() {
        // Classic little-endian TIFF: IFD0 at 8 with 2 entries (width 10, height 20), next IFD 0.
        let mut t = b"II*\0".to_vec();
        t.extend_from_slice(&8u32.to_le_bytes());
        t.extend_from_slice(&2u16.to_le_bytes());
        t.extend_from_slice(&[0x00, 0x01, 0x03, 0x00, 1, 0, 0, 0, 10, 0, 0, 0]);
        t.extend_from_slice(&[0x01, 0x01, 0x03, 0x00, 1, 0, 0, 0, 20, 0, 0, 0]);
        t.extend_from_slice(&0u32.to_le_bytes());
        assert_eq!(declared_size(&t), Ok(Some((10, 20))));
    }

    #[test]
    fn declared_size_of_a_jpeg2000_codestream() {
        // SOC, then SIZ: Lsiz, Rsiz, Xsiz=500, Ysiz=400, XOsiz=0, YOsiz=0.
        let mut j = vec![0xFF, 0x4F, 0xFF, 0x51, 0, 41, 0, 0];
        j.extend_from_slice(&500u32.to_be_bytes());
        j.extend_from_slice(&400u32.to_be_bytes());
        j.extend_from_slice(&[0u8; 8]);
        assert_eq!(declared_size(&j), Ok(Some((500, 400))));
    }

    /// A white quadrilateral on a dark background, fully opaque.
    fn white_quad_frame(width: u32, height: u32, tl: (u32, u32), br: (u32, u32)) -> Vec<u8> {
        let mut rgba = Vec::with_capacity(width as usize * height as usize * 4);
        for y in 0..height {
            for x in 0..width {
                let inside = (tl.0..br.0).contains(&x) && (tl.1..br.1).contains(&y);
                let v = if inside { 245 } else { 30 };
                rgba.extend_from_slice(&[v, v, v, 255]);
            }
        }
        rgba
    }

    fn pt(x: f32, y: f32) -> Pt {
        Pt { x, y }
    }

    fn box_quad(x0: f32, y0: f32, x1: f32, y1: f32) -> Quad {
        Quad {
            top_left: pt(x0, y0),
            top_right: pt(x1, y0),
            bottom_right: pt(x1, y1),
            bottom_left: pt(x0, y1),
        }
    }

    #[test]
    fn detects_the_corners_of_a_white_page_on_a_dark_background() {
        let (w, h) = (320, 240);
        let frame = white_quad_frame(w, h, (60, 40), (260, 200));
        let quad = detect_document_corners(frame, w, h)
            .unwrap()
            .expect("a page is there");
        let tolerance = 6.0;
        for (got, want) in [
            (quad.top_left, (60.0, 40.0)),
            (quad.top_right, (259.0, 40.0)),
            (quad.bottom_right, (259.0, 199.0)),
            (quad.bottom_left, (60.0, 199.0)),
        ] {
            assert!(
                (got.x - want.0).abs() <= tolerance && (got.y - want.1).abs() <= tolerance,
                "corner {got:?} far from {want:?}"
            );
        }
    }

    #[test]
    fn a_flat_frame_has_no_document() {
        let frame = vec![128u8; 200 * 200 * 4];
        assert_eq!(detect_document_corners(frame, 200, 200).unwrap(), None);
    }

    #[test]
    fn mismatched_rgba_length_is_invalid_argument_for_detection() {
        assert!(matches!(
            detect_document_corners(vec![0; 10], 200, 150),
            Err(PdfError::InvalidArgument { .. })
        ));
    }

    #[test]
    fn rectify_gives_the_quad_size_for_every_look() {
        let (w, h) = (200, 150);
        let frame = white_quad_frame(w, h, (20, 20), (120, 80));
        for look in [
            ScanLook::Original,
            ScanLook::Colour,
            ScanLook::Greyscale,
            ScanLook::BlackWhite,
        ] {
            let img = rectify_and_clean(
                frame.clone(),
                w,
                h,
                box_quad(20.0, 20.0, 120.0, 80.0),
                look,
                50,
            )
            .unwrap();
            assert_eq!((img.width, img.height), (100, 60), "{look:?}");
            assert_eq!(img.rgba.len(), 100 * 60 * 4, "{look:?}");
            assert!(
                img.rgba.as_chunks::<4>().0.iter().all(|p| p[3] == 255),
                "{look:?} is opaque"
            );
        }
    }

    #[test]
    fn rectify_refuses_bad_arguments() {
        let (w, h) = (200, 150);
        let frame = white_quad_frame(w, h, (20, 20), (120, 80));
        let quad = box_quad(20.0, 20.0, 120.0, 80.0);
        assert!(matches!(
            rectify_and_clean(frame.clone(), w, h, quad, ScanLook::Colour, 101),
            Err(PdfError::InvalidArgument { .. })
        ));
        assert!(matches!(
            rectify_and_clean(frame[..100].to_vec(), w, h, quad, ScanLook::Colour, 50),
            Err(PdfError::InvalidArgument { .. })
        ));
        assert!(matches!(
            rectify_and_clean(vec![], 8000, 6000, quad, ScanLook::Colour, 50),
            Err(PdfError::TooLarge { .. })
        ));
        assert!(matches!(
            detect_document_corners(vec![], 0, 0),
            Err(PdfError::InvalidArgument { .. })
        ));
    }

    #[test]
    fn exactly_forty_megapixels_is_allowed_and_one_more_is_not() {
        assert!(check_pixels(8000, 5000).is_ok());
        assert!(check_pixels(6324, 6324).is_ok());
        assert!(matches!(
            check_pixels(8001, 5000),
            Err(PdfError::TooLarge { .. })
        ));
        assert!(matches!(
            check_pixels(u64::MAX, 2),
            Err(PdfError::TooLarge { .. })
        ));
        // The frame check uses the same limit: 8000 x 5000 with a full buffer is accepted,
        // 8001 x 5000 is refused as too large before the buffer length is looked at.
        assert!(check_frame(8000 * 5000 * 4, 8000, 5000).is_ok());
        assert!(matches!(
            check_frame(0, 8001, 5000),
            Err(PdfError::TooLarge { .. })
        ));
    }

    #[test]
    fn rectified_output_is_limited_before_warp() {
        // 7000 x 7000 = 49 MP output from a frame of up to 8000 x 5000: refused before warp runs.
        let big = [[0.0, 0.0], [7000.0, 0.0], [7000.0, 7000.0], [0.0, 7000.0]];
        assert!(matches!(
            check_rectify_request(&big, 8000, 5000),
            Err(PdfError::TooLarge { .. })
        ));
        // 6324 x 6324 is just under 40 MP.
        let edge = [[0.0, 0.0], [6324.0, 0.0], [6324.0, 6324.0], [0.0, 6324.0]];
        assert!(check_rectify_request(&edge, 8000, 5000).is_ok());
        // A 40 MP frame passes the frame check; its 7000 x 7000 straightened output is refused
        // before warp runs. A 40 MP + 1 frame is refused as a frame, before its length is read.
        let frame_40mp = vec![0u8; 8000 * 5000 * 4];
        assert!(matches!(
            rectify_and_clean(
                frame_40mp,
                8000,
                5000,
                box_quad(0.0, 0.0, 7000.0, 7000.0),
                ScanLook::Original,
                0
            ),
            Err(PdfError::TooLarge { .. })
        ));
        assert!(matches!(
            rectify_and_clean(
                vec![],
                8000,
                5001,
                box_quad(0.0, 0.0, 10.0, 10.0),
                ScanLook::Original,
                0
            ),
            Err(PdfError::TooLarge { .. })
        ));
    }

    #[test]
    fn degenerate_and_oversized_quads_are_invalid_argument() {
        let (w, h) = (200, 150);
        let frame = white_quad_frame(w, h, (20, 20), (120, 80));
        let nan = Quad {
            top_left: pt(f32::NAN, 0.0),
            ..box_quad(20.0, 20.0, 120.0, 80.0)
        };
        assert!(matches!(
            rectify_and_clean(frame.clone(), w, h, nan, ScanLook::Colour, 50),
            Err(PdfError::InvalidArgument { .. })
        ));
        let huge = box_quad(0.0, 0.0, 1.0e30, 1.0e30);
        assert!(matches!(
            rectify_and_clean(frame.clone(), w, h, huge, ScanLook::Colour, 50),
            Err(PdfError::InvalidArgument { .. })
        ));
        // All four corners on one point: no area, so warp refuses it.
        let point = box_quad(50.0, 50.0, 50.0, 50.0);
        assert!(matches!(
            rectify_and_clean(frame, w, h, point, ScanLook::Colour, 50),
            Err(PdfError::InvalidArgument { .. })
        ));
    }
}
