#!/usr/bin/env python3
"""Writes the image fixtures of the scan tests (stdlib only: PNG through zlib).

Usage: gen_test_images.py OUTDIR

  docdark.png   900x1200. A light, slightly graded "table" with a DARK document rectangle at a known
                position (RECT below). The engine's corner detection should find it, or the app
                falls back to the whole frame; the test reports which.
  pagelight.png 900x1200. The usual photo of a page: a LIGHT page with dark text lines on a DARK table,
                same rectangle.
  plain.png     600x800. A flat colour with a gradient and a black frame: any picture.

JPEG (with EXIF and GPS) and WebP cannot be written with the standard library; the instrumented
test makes those on the device with Bitmap.compress and ExifInterface.
"""
import os
import struct
import sys
import zlib

W, H = 900, 1200
# left, top, right, bottom of the document in docdark.png and pagelight.png.
RECT = (180, 240, 720, 960)


def png(path, width, height, pixel):
    raw = bytearray()
    for y in range(height):
        raw.append(0)  # filter: none
        for x in range(width):
            raw.extend(pixel(x, y))

    def chunk(kind, data):
        body = kind + data
        return struct.pack(">I", len(data)) + body + struct.pack(">I", zlib.crc32(body) & 0xFFFFFFFF)

    out = b"\x89PNG\r\n\x1a\n"
    out += chunk(b"IHDR", struct.pack(">IIBBBBB", width, height, 8, 2, 0, 0, 0))
    out += chunk(b"IDAT", zlib.compress(bytes(raw), 9))
    out += chunk(b"IEND", b"")
    with open(path, "wb") as f:
        f.write(out)


def inside(x, y):
    l, t, r, b = RECT
    return l <= x < r and t <= y < b


def doc_dark(x, y):
    if inside(x, y):
        return (38, 44, 60)
    g = 215 + int(20 * y / H)
    return (g + 10, g + 5, g - 5)


def page_light(x, y):
    l, t, r, b = RECT
    if inside(x, y):
        # dark text-like lines every 40 px, 14 px high, inset by 40 px
        if (y - t) % 40 < 14 and l + 40 <= x < r - 40 and (y - t) > 40:
            return (30, 30, 30)
        return (246, 244, 236)
    return (48, 52, 58)


def plain(x, y):
    if x < 6 or y < 6 or x >= 594 or y >= 794:
        return (0, 0, 0)
    return (int(255 * x / 600), int(255 * y / 800), 160)


def main():
    out = sys.argv[1]
    os.makedirs(out, exist_ok=True)
    png(os.path.join(out, "docdark.png"), W, H, doc_dark)
    png(os.path.join(out, "pagelight.png"), W, H, page_light)
    png(os.path.join(out, "plain.png"), 600, 800, plain)


if __name__ == "__main__":
    main()
