#![no_main]

use libfuzzer_sys::fuzz_target;
use pdfa_ffi::{PdfError, PdfSession, Pt, Rotation};

// Open arbitrary bytes, then the read calls the viewer makes. Errors are fine; panics are not
// (the wrapper turns them into PdfError::Panic, which this target treats as a bug).
fuzz_target!(|data: &[u8]| {
    let Ok(session) = PdfSession::open(data.to_vec(), None) else {
        return;
    };
    let _ = session.meta();
    let _ = session.page_infos();
    let _ = session.render_tile(0, 1.0, 0, 0, 128, 128, false);
    let _ = session.search_page(0, "a".to_owned(), false, false);
    let _ = session.select_text(0, Pt { x: 0.0, y: 0.0 }, Pt { x: 100.0, y: 100.0 });
    let _ = session.outline(0, 20);
    let _ = session.links(0);
    let _ = session.rotate_pages(vec![0], Rotation::Cw90);
    let _ = session.undo();
    if let Err(PdfError::Panic { detail }) = session.save_full() {
        panic!("panic surfaced through the wrapper: {detail}");
    }
});
