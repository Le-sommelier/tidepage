#![no_main]

use libfuzzer_sys::fuzz_target;
use pdfa_ffi::{MergeSource, PageRange, PdfError, merge};

// Merge two arbitrary inputs, with and without page ranges. Errors are fine; a panic surfacing as
// PdfError::Panic is a bug.
fuzz_target!(|data: &[u8]| {
    let mid = data.len() / 2;
    let (left, right) = data.split_at(mid);
    let source = |name: &str, bytes: &[u8], ranges: Vec<PageRange>| MergeSource {
        name: name.to_owned(),
        bytes: bytes.to_vec(),
        password: None,
        ranges,
    };
    let whole = vec![PageRange { start: 0, end_inclusive: 2 }];
    let sources = vec![source("a.pdf", left, Vec::new()), source("b.pdf", right, whole)];
    if let Err(PdfError::Panic { detail }) = merge(sources) {
        panic!("panic surfaced through the wrapper: {detail}");
    }
});
