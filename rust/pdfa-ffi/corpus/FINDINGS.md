# Known findings (hostile corpus)

Gate: `cargo test -p pdfa-ffi --test hostile -- --nocapture` is green. Each finding below is listed in `KNOWN` in `tests/hostile.rs` and has an `#[ignore]` regression test. Run them with `cargo test -p pdfa-ffi --test hostile -- --ignored finding_`; they fail until the bug is fixed.

Scope: the engine (vendored hayro, lopdf, pdfcraft) and the wrapper. None of the findings is a `PdfError::Panic` surfacing through the wrapper. Byte-mutation families (truncation, bit flips, zero runs, garbage, header-only) produced no panic, hang or crash.

## F1: deep /Resources nesting overflows the stack (debug build only)

- **Severity:** high in debug, low in release. The debug build aborts the process (SIGABRT, "has overflowed its stack"). The release build survives depth 10000 with a 2 MiB stack.
- **What happens:** a `/Resources` dictionary nested 500 deep (`<< /K << /K ...`) aborts during `PdfSession::open` on a thread the engine spawns. Its name is `<unknown>`, so the caller's stack size has no effect: the 512 MiB and 2 MiB children both overflowed at depth 10000 and 100000.
- **Repro:** `corpus/findings/nesting-resources-d500.pdf` and `nesting-resources-d10000.pdf`. Run `cargo test -p pdfa-ffi --test hostile -- --ignored finding_resources`.
- **Likely cause:** unbounded recursion in object loading. Thread spawns in `third_party/pdfcraft/vendor/hayro-syntax/src/data.rs` (around lines 197 and 230) and `xref.rs` (around 1828 and 1895) are candidates. Not confirmed which one.
- **Why it is not counted as a bug in release:** frames are smaller, so the same input passes. The gate runs in debug, so the cases are in `KNOWN`.

## F2: 100000 /Kids pointing at one page is slow (debug build only)

- **Severity:** medium. Debug takes more than 10 s. Release finishes well under the limit.
- **Repro:** `corpus/findings/kids-100000-same-page.pdf`, test `finding_kids_100000_hang`.
- **Likely cause:** page-tree walks cost O(kids) per access, possibly with no seen-set.

## F3: 1 GiB inflate bomb is slow (debug build only)

- **Severity:** high for DoS (time and memory not bounded upstream). Release completes within the limit, but the peak RSS was not measured. The debug run was killed at 10 s.
- **Repro:** a 6.8 MB zlib stream that inflates to 1 GiB of zeros, from `zlib_zeros(1 << 30)`. It is generated, not committed: `cargo test -p pdfa-ffi --test hostile -- --ignored finding_flate_bomb`.
- **Likely cause:** no cap on the inflated size of a content stream.

## F4: 10 MB text-operator flood is slow (debug build only)

- **Severity:** low to medium. Upstream has no cancel, so the process watchdog is the only defence (CONTRACT, risk 1).
- **Repro:** `BT /F1 1 Tf (x) Tj ET` repeated to about 10.5 MB, generated. Test `finding_operators_10mb_text_hang`.

## F5: hayro crypto panics on empty /O and /U

- **Severity:** low. The panic is caught inside the engine and turned into an error, so the wrapper returns an error, not `PdfError::Panic`. The panic message still reaches stderr.
- **What happens:** `third_party/pdfcraft/vendor/hayro-syntax/src/crypto/mod.rs:604` slices `[..512]` on a 16-byte buffer.
- **Repro:** `corpus/findings/encrypt-v2-empty-o-u.pdf`, test `finding_encrypt_empty_o_u_internal_panic`.
- **Fix direction:** length-check `/O` and `/U` before slicing, and return a `PasswordRequired` or `InvalidPdf` error.

## Release vs debug

Release run of the full gate: 464 cases in 3.4 s, zero hang, crash or panic. Debug run: 464 cases in about 14 s, the same eight KNOWN cases (five nesting aborts, kids, bomb and operator flood).
