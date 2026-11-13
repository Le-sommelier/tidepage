# Golden renders

`tests/golden.rs` renders six hand-built fixture PDFs with `PdfSession::render_tile` and compares
the pixels with the files in this directory.

## Commands

| Purpose | Command |
|---|---|
| Compare (default) | `cargo test -p pdfa-ffi --test golden` |
| Rewrite all goldens | `UPDATE_GOLDEN=1 cargo test -p pdfa-ffi --test golden` |
| Print checksums (device or emulator) | `cargo test -p pdfa-ffi --test golden -- --ignored --nocapture print_checksums` |

Rewrite goldens only after checking the `sanity_*` tests still pass. Those tests check the
fixtures' geometry, so a wrong golden cannot be baked in silently.

## Golden format

Each `<name>.golden` file is:

| Offset | Size | Content |
|---|---|---|
| 0 | 4 | magic `GLD1` |
| 4 | 4 | width, u32 little-endian |
| 8 | 4 | height, u32 little-endian |
| 12 | w*h*4 | raw premultiplied RGBA8, row-major, top row first |

This is the same layout as `RenderedImage`. There are no compression or dependencies.

## Tolerances

A pixel is compared channel by channel (R, G, B, A). The per-pixel delta is the largest
channel difference.

- Sizes must match exactly.
- At most 0.1 % of pixels (1 in 1000) may have a delta above 2.
- No pixel may have a delta above 32.

These tolerances allow for floating-point differences between host and arm64.

## Fixtures

| Golden | Fixture | Render |
|---|---|---|
| `text_tile256_s1` | Helvetica and Times black text at 12, 18, 24 and 36 pt | 256x256 tile at (0,0), scale 1.0 |
| `shapes_tile256_s2` | filled and stroked rectangles, bezier, dashed line, even-odd ring | 256x256 tile at (200,200), scale 2.0 |
| `rotate90_full_s1` | 150x200 page with `/Rotate 90`, red rectangle, text | whole page, 200x150, scale 1.0 |
| `cropbox_full_s1` | MediaBox origin (100,100), CropBox (150,150)-(350,350) | whole page, 200x200, scale 1.0 |
| `image_full_s1` | 32x32 uncompressed DeviceRGB gradient, upscaled to 160 pt | whole page, 160x160, scale 1.0 |
| `transparency_full_s1` | two overlapping rectangles with `/ca 0.5` via ExtGState | whole page, 150x150, scale 1.0 |

The sanity tests check each fixture against the geometry the engine's own tests use: view space,
rotation clockwise, CropBox origin, opaque white paper, and alpha blending over paper.

## Failure output

A failing comparison writes two files next to the golden:

- `<name>.actual.golden`: the render, in the same format.
- `<name>.diff.ppm`: a P6 image. Differing pixels are red. Other pixels show the golden over white.

Delete these files after investigating. They are not meant to be committed. The root `.gitignore`
does not cover them and this package may not edit it.

## Checksums

`print_checksums` prints `<name> <w>x<h> fnv1a64=<hex>` for each shot. FNV-1a 64 runs over the
raw RGBA bytes. Compare the output from host and emulator. Differences are expected only within
the tolerances above, so a hash mismatch alone does not prove a regression.
