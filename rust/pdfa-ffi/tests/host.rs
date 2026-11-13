//! Host-side tests for the FFI surface (run with `cargo test -p pdfa-ffi`).

use pdfa_ffi::{PdfDoc, PdfError, debug_force_panic};

const HELLO: &[u8] = include_bytes!("fixtures/hello.pdf");

#[test]
fn opens_and_renders_page_one() {
    let doc = PdfDoc::open(HELLO.to_vec(), None).expect("hello.pdf should open");
    assert_eq!(doc.page_count().unwrap(), 1);

    let size = doc.page_size(0).unwrap();
    assert!(
        (size.width_pt - 612.0).abs() < 0.01,
        "width {}",
        size.width_pt
    );
    assert!(
        (size.height_pt - 792.0).abs() < 0.01,
        "height {}",
        size.height_pt
    );

    let img = doc.render_page(0, 1.0).expect("page 1 should render");
    assert_eq!((img.width, img.height), (612, 792));
    assert_eq!(img.rgba.len(), (img.width * img.height * 4) as usize);

    // The blue rectangle is drawn: an opaque, blue-dominant pixel exists.
    assert!(
        img.rgba
            .as_chunks::<4>()
            .0
            .iter()
            .any(|p| p[3] == 255 && p[2] > 200 && p[0] < 50),
        "blue rectangle not found"
    );
    // The Helvetica text is drawn: a dark, opaque pixel exists.
    assert!(
        img.rgba
            .as_chunks::<4>()
            .0
            .iter()
            .any(|p| p[3] == 255 && p[0] < 80 && p[1] < 80 && p[2] < 80),
        "text not found"
    );
}

#[test]
fn render_page_rejects_bad_arguments() {
    let doc = PdfDoc::open(HELLO.to_vec(), None).unwrap();
    assert!(matches!(
        doc.page_size(7),
        Err(PdfError::InvalidArgument { .. })
    ));
    assert!(matches!(
        doc.render_page(7, 1.0),
        Err(PdfError::InvalidArgument { .. })
    ));
    assert!(matches!(
        doc.render_page(0, 0.0),
        Err(PdfError::InvalidArgument { .. })
    ));
    assert!(matches!(
        doc.render_page(0, f32::NAN),
        Err(PdfError::InvalidArgument { .. })
    ));
}

#[test]
fn render_page_refuses_more_than_64_megapixels() {
    let doc = PdfDoc::open(HELLO.to_vec(), None).unwrap();
    // 612 x 792 pt at scale 200 is ~122400 x 158400 pixels: far past the cap, refused before rasterising.
    assert!(matches!(
        doc.render_page(0, 200.0),
        Err(PdfError::TooLarge { .. })
    ));
}

#[test]
fn truncated_header_is_an_error_not_a_panic() {
    match PdfDoc::open(HELLO[..10].to_vec(), None) {
        Err(PdfError::Panic { detail }) => panic!("truncated input panicked: {detail}"),
        Err(_) => {}
        Ok(_) => panic!("a 10-byte file must not open"),
    }
}

#[test]
fn truncated_files_never_panic() {
    // Cuts through the body, the xref and the trailer. Repair may still open some of them;
    // what matters here is that none of them comes back as a caught panic.
    for cut in [30, 120, HELLO.len() / 2, HELLO.len() - 40, HELLO.len() - 1] {
        match PdfDoc::open(HELLO[..cut].to_vec(), None) {
            Err(PdfError::Panic { detail }) => panic!("cut at {cut} panicked: {detail}"),
            Err(_) => {}
            Ok(doc) => {
                // A file that opens must still be safe to use.
                if let Ok(n) = doc.page_count() {
                    for p in 0..n {
                        let _ = doc.render_page(p, 1.0);
                    }
                }
            }
        }
    }
}

#[test]
fn oversize_input_is_refused_before_parsing() {
    // 256 MB + 1 byte of zeros: refused by the size guard without being parsed.
    let big = vec![0u8; pdfa_ffi::MAX_INPUT_BYTES + 1];
    assert!(matches!(
        PdfDoc::open(big, None),
        Err(PdfError::TooLarge { .. })
    ));
}

#[test]
fn debug_force_panic_returns_panic_error() {
    match debug_force_panic() {
        Err(PdfError::Panic { detail }) => assert!(
            detail.contains("debug_force_panic requested"),
            "detail: {detail}"
        ),
        other => panic!("expected PdfError::Panic, got {other:?}"),
    }
}
