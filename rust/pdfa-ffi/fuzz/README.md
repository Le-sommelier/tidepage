# pdfa-ffi fuzz targets

cargo-fuzz (libFuzzer) targets for the PDF wrapper. They call the same public API as the app.

- `open`: opens arbitrary bytes, then the viewer's read calls, then `save_full`.
- `merge`: merges two arbitrary inputs, the second with a page range.

A panic that surfaces as `PdfError::Panic` is treated as a crash by both targets.

## Run

Needs nightly Rust and cargo-fuzz (`cargo install cargo-fuzz`):

    cd rust/pdfa-ffi/fuzz
    cargo +nightly fuzz run open -- -max_total_time=60

Seed inputs: `../corpus/seed/` (copy them into `fuzz/corpus/open/` first, or pass the directory
to `cargo fuzz run open ../corpus/seed`). Regenerate the seeds with
`cargo test -p pdfa-ffi --test hostile -- --ignored write_seed_corpus`.

## Status

Not run yet. The crate was checked with `cargo metadata` only. Nightly and cargo-fuzz were not
installed when this was written. Fuzzer findings go to `../corpus/findings/`, with a regression test
in `tests/hostile.rs`.
