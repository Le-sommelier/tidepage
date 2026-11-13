# Feature gating and `.so` size

Patch: `scripts/patches/0001-engine-feature-gates.patch` (26 files, +482/-38; applied by
`scripts/fetch-upstream.sh` on top of upstream `8d5d39e`). It adds four cargo features to
`pdfcraft-engine`, all on by default, so upstream behaviour is unchanged unless a consumer sets
`default-features = false`.

## Sizes (arm64-v8a, release, `cargo ndk`, NDK 29, `llvm-strip`)

Base profile is the repo's: opt-level 3, thin LTO, codegen-units 1, panic unwind. MB = 10^6 bytes.

| Variant (features on) | Unstripped | Stripped | vs all on |
|---|---|---|---|
| all four (= no gating) | 29.52 | **20.02** | 0 |
| sign off | 27.87 | 18.65 | -1.37 |
| ocr off | 29.26 | 19.91 | -0.11 |
| print off | 29.50 | 20.01 | -0.01 |
| js off | 19.72 | **12.17** | -7.85 |
| all four off | 17.77 | 10.68 | -9.34 |

Profile variants (measurement crate only):

| Variant | Stripped |
|---|---|
| js off, lto=fat | 12.17 (no gain over thin) |
| js off, opt-level z + lto fat | 11.34 |
| all on, opt-level z + lto fat | 16.74 |
| js + sign off, opt-level z + lto fat | 10.09 |

`js` is the whole story: it removes the `boa_engine` JavaScript interpreter. With the four features
off, `boa_engine`, `ocrs`, `rten`, `rsa`, `p256`, `craft-print` and `aws-lc-rs` are absent from the
dependency graph (checked with `cargo tree -i`). The all-on figure reproduces the 20.0 MB of the first, ungated build.

## Recommendation

Android: **turn `js` off** (goal 15 MB is met with 12.2 MB at the repo profile). Also turn off
`ocr` and `print`: they save little but are dead weight for a viewer/editor with no OCR models and
no spooler. `sign` costs 1.4 MB; keep it off unless the app will verify or create digital
signatures (the viewer needs only to know a file is signed, which the stub still does). `opt-level
= "z"` saves a further ~0.8 MB at a speed cost to rendering; not recommended without a render
benchmark.

Root `Cargo.toml`: no change needed (keep the profile).

`rust/pdfa-ffi/Cargo.toml`:

```toml
[features]
default = []
# Pass-through to the engine; all off for the Android build.
sign  = ["pdfcraft-engine/sign"]
ocr   = ["pdfcraft-engine/ocr"]
print = ["pdfcraft-engine/print"]
js    = ["pdfcraft-engine/js"]

[dependencies]
pdfcraft-engine = { path = "../../third_party/pdfcraft/crates/engine", default-features = false }
```

Cargo unifies features: nothing else in the pdfa-ffi graph depends on `pdfcraft-engine`, so the
defaults stay off. Host tests that need a feature run with `--features js` etc.

## Behaviour with a feature off

- `sign`: `SignatureInfo`/`SignatureStatus`/`TrustStore`/`StatusCaptions` are small stand-ins
  (`crates/engine/src/sign_off.rs`); status is always `Unknown`, nothing is drawn or verified.
  A signed file (a dictionary with `/ByteRange` and `/Contents`) is still recognised, so
  `is_signed` and the refusal to rewrite a signed file keep working (own test added). `sign`,
  `set_trusted_certificates`, timestamps, LTV are gone.
- `print`: `print_pdf`, `print_text_up`, `print_raster` and the `print` module are gone. Merge
  and split page ranges ("1-3, 6", also `5-2`, `-3`, `5-`) still work through a local parser
  (`page_range.rs`): numbers only, no page labels; errors keep the wording "page N is out of range
  (1-count)", including "pages to print" for an empty range. `select_pages` could not be dropped
  because `session/files.rs` uses it for `combine*` and ranges, so it was replaced rather than
  kept.
- `ocr`: the `ocr` module and `Edit::AddOcrText` are gone; the Recognize-text action step returns
  an error; form detection on scans just finds no recognised words.
- `js`: no field script or XFA script runs. Forms are seen as with JavaScript turned off (no XFA
  template kept; AF formatting stays native). `Session::javascript()` returns false;
  `run_javascript` and `Step::RunJavaScript` are gone or return an error. `js::JsOutput` is an
  always-empty stub. `js` and `xfa` could not be cut independently: the XFA script runner
  (`xfa.rs`) is built on `pdfcraft-js`; the non-script XFA layout (`pdfcraft-xfa`) stays.
  `pdfcraft-xfa` is still needed by create/redact.

## Tests

`cargo test -p pdfcraft-engine --lib`: 536 passed (default features, unchanged).
`--no-default-features`: 387 passed. Each single-feature-off combination also passes (lib, `tests/`
and doc tests). Tests that exercise a feature are `#[cfg(feature = ...)]`-gated (111 attributes in
`tests.rs`, `tests/form_date_language.rs`, `tests/it/main.rs`): sign (signing, trust, status
layers, signature-field-walk caps), print (print_pdf, print as image), ocr (recognise, text layer),
js (field scripts, XFA scripts and anything that needs their results, e.g. the XFA flatten,
convert and warning tests rely on a script-computed label). Helpers only used by gated tests are
silenced with one `allow(dead_code)`.

## Not verified

x86_64 build and on-device behaviour with features off; the full upstream workspace (only the
engine and the pdfa-ffi graph were built); `cargo test --workspace` default. Android builds were
done in a scratch crate, not through `scripts/build-android.sh` with the patched clone.
