#!/usr/bin/env python3
"""Writes the PDF fixtures for the :app instrumented tests. Standard library only.

    python3 tools/gen_test_pdfs.py <output-dir>

Files:
  text3.pdf      3 pages of text, an outline, an https link and a javascript: link on page 1
  big300.pdf     300 pages; page N carries the unique word UNIQ<NNN>X
  corrupt.pdf    a %PDF- header followed by garbage
  locked.pdf     1 page, RC4 40-bit standard security handler, user password "secret"
  rotated.pdf    2 pages: page 1 has /Rotate 90 (shown landscape), page 2 is upright
  form.pdf       1 page AcroForm: text "name" (required), multiline "notes", check box "subscribe",
                 radio "plan" (basic, pro), combo "country" (fr, de, es), read-only text "id"

Every file is written by hand with a correct xref table. Output is deterministic.
"""
import hashlib
import os
import struct
import sys

PAD = bytes([
    0x28, 0xBF, 0x4E, 0x5E, 0x4E, 0x75, 0x8A, 0x41, 0x64, 0x00, 0x4E, 0x56, 0xFF, 0xFA, 0x01, 0x08,
    0x2E, 0x2E, 0x00, 0xB6, 0xD0, 0x68, 0x3E, 0x80, 0x2F, 0x0C, 0xA9, 0xFE, 0x64, 0x53, 0x69, 0x7A,
])


def rc4(key, data):
    s = list(range(256))
    j = 0
    for i in range(256):
        j = (j + s[i] + key[i % len(key)]) & 0xFF
        s[i], s[j] = s[j], s[i]
    out = bytearray()
    i = j = 0
    for b in data:
        i = (i + 1) & 0xFF
        j = (j + s[i]) & 0xFF
        s[i], s[j] = s[j], s[i]
        out.append(b ^ s[(s[i] + s[j]) & 0xFF])
    return bytes(out)


class Pdf:
    """Objects are numbered from 1 in the order they are reserved."""

    def __init__(self):
        self.objects = []  # bytes body, or (dict_bytes, stream_bytes)

    def reserve(self):
        self.objects.append(None)
        return len(self.objects)

    def put(self, num, body):
        self.objects[num - 1] = body

    def add(self, body):
        num = self.reserve()
        self.put(num, body)
        return num

    def build(self, root, encrypt=None, file_id=b"0123456789abcdef"):
        """encrypt: (key, encrypt_dict_bytes) for RC4 40-bit, or None."""
        out = bytearray(b"%PDF-1.4\n%\xe2\xe3\xcf\xd3\n")
        offsets = []
        enc_num = None
        objects = list(self.objects)
        if encrypt:
            objects.append(encrypt[1])
            enc_num = len(objects)
        for i, body in enumerate(objects):
            num = i + 1
            offsets.append(len(out))
            out += b"%d 0 obj\n" % num
            if isinstance(body, tuple):
                head, stream = body
                if encrypt and num != enc_num:
                    okey = hashlib.md5(encrypt[0] + struct.pack("<I", num)[:3] + b"\x00\x00").digest()[:10]
                    stream = rc4(okey, stream)
                out += b"<< " + head + b" /Length %d >>\nstream\n" % len(stream) + stream + b"\nendstream"
            else:
                out += body
            out += b"\nendobj\n"
        xref = len(out)
        out += b"xref\n0 %d\n" % (len(objects) + 1)
        out += b"0000000000 65535 f \n"
        for off in offsets:
            out += b"%010d 00000 n \n" % off
        hex_id = file_id.hex().encode()
        trailer = b"<< /Size %d /Root %d 0 R /ID [<%s> <%s>]" % (len(objects) + 1, root, hex_id, hex_id)
        if enc_num:
            trailer += b" /Encrypt %d 0 R" % enc_num
        out += b"trailer\n" + trailer + b" >>\nstartxref\n%d\n%%%%EOF\n" % xref
        return bytes(out)


def text_stream(lines):
    """lines: (x, y, size, text). ASCII text only, no parentheses or backslashes."""
    parts = []
    for x, y, size, text in lines:
        parts.append("BT /F1 %d Tf %d %d Td (%s) Tj ET" % (size, x, y, text))
    return "\n".join(parts).encode("ascii")


def document(page_lines, annots_for=None, outline=False, rotate_for=None):
    """page_lines: one list of text lines per page. rotate_for: {page index: degrees}.
    Returns (pdf, catalog number). Without rotate_for the bytes are exactly as before."""
    pdf = Pdf()
    catalog = pdf.reserve()
    pages = pdf.reserve()
    font = pdf.add(b"<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica /Encoding /WinAnsiEncoding >>")
    kids = []
    for index, lines in enumerate(page_lines):
        content = pdf.add((b"", text_stream(lines)))
        annots = b""
        if annots_for and index in annots_for:
            nums = [pdf.add(a) for a in annots_for[index]]
            annots = b" /Annots [" + b" ".join(b"%d 0 R" % n for n in nums) + b"]"
        if rotate_for and index in rotate_for:
            annots += b" /Rotate %d" % rotate_for[index]
        page = pdf.add(
            b"<< /Type /Page /Parent %d 0 R /MediaBox [0 0 612 792] /Contents %d 0 R"
            b" /Resources << /Font << /F1 %d 0 R >> >>%s >>" % (pages, content, font, annots)
        )
        kids.append(page)
    pdf.put(pages, b"<< /Type /Pages /Count %d /Kids [%s] >>" % (len(kids), b" ".join(b"%d 0 R" % k for k in kids)))
    extra = b""
    if outline:
        root = pdf.reserve()
        items = [pdf.reserve() for _ in kids]
        for i, item in enumerate(items):
            body = b"<< /Title (Chapter %d) /Parent %d 0 R /Dest [%d 0 R /Fit]" % (i + 1, root, kids[i])
            if i > 0:
                body += b" /Prev %d 0 R" % items[i - 1]
            if i + 1 < len(items):
                body += b" /Next %d 0 R" % items[i + 1]
            pdf.put(item, body + b" >>")
        pdf.put(root, b"<< /Type /Outlines /First %d 0 R /Last %d 0 R /Count %d >>" % (items[0], items[-1], len(items)))
        extra = b" /Outlines %d 0 R" % root
    pdf.put(catalog, b"<< /Type /Catalog /Pages %d 0 R%s >>" % (pages, extra))
    return pdf, catalog


def link(rect, uri):
    return (
        b"<< /Type /Annot /Subtype /Link /Rect [%d %d %d %d] /Border [0 0 0]"
        b" /A << /S /URI /URI (%s) >> >>" % (rect[0], rect[1], rect[2], rect[3], uri.encode("ascii"))
    )


def text3():
    pages = []
    for n in range(1, 4):
        lines = [(72, 700, 28, "Hello PDF page %d of 3" % n)]
        for row in range(8):
            lines.append((72, 480 - row * 28, 14, "Line %d of page %d: the quick brown fox jumps over the lazy dog." % (row + 1, n)))
        if n == 1:
            lines.append((72, 620, 18, "Good link: example.org report"))
            lines.append((72, 540, 18, "Hostile link: javascript"))
        pages.append(lines)
    annots = {0: [
        link((72, 600, 400, 650), "https://example.org/report"),
        link((72, 520, 400, 570), "javascript:alert(1)"),
    ]}
    pdf, catalog = document(pages, annots_for=annots, outline=True)
    return pdf.build(catalog)


def big300():
    pages = []
    for n in range(1, 301):
        lines = [
            (72, 700, 28, "Page %d of 300" % n),
            (72, 640, 20, "UNIQ%03dX" % n),
        ]
        for row in range(10):
            lines.append((72, 560 - row * 26, 13, "Row %d on page %d. Lorem ipsum dolor sit amet, consectetur." % (row + 1, n)))
        pages.append(lines)
    pdf, catalog = document(pages)
    return pdf.build(catalog)


def rotated():
    pages = [
        [(72, 700, 28, "Rotated page 1 of 2"), (72, 640, 16, "This page has Rotate 90.")],
        [(72, 700, 28, "Upright page 2 of 2"), (72, 640, 16, "This page is not rotated.")],
    ]
    pdf, catalog = document(pages, rotate_for={0: 90})
    return pdf.build(catalog)


def corrupt():
    garbage = hashlib.sha256(b"pdfa-corrupt").digest() * 64
    return b"%PDF-1.4\n" + garbage + b"\n%%EOF\n"


def locked(user_password=b"secret", owner_password=b"owner"):
    file_id = b"0123456789abcdef"
    permissions = -4  # everything allowed
    owner_key = hashlib.md5((owner_password + PAD)[:32]).digest()[:5]
    o_entry = rc4(owner_key, (user_password + PAD)[:32])
    key = hashlib.md5((user_password + PAD)[:32] + o_entry + struct.pack("<i", permissions) + file_id).digest()[:5]
    u_entry = rc4(key, PAD)
    encrypt = b"<< /Filter /Standard /V 1 /R 2 /O <%s> /U <%s> /P %d >>" % (
        o_entry.hex().encode(), u_entry.hex().encode(), permissions,
    )
    pdf, catalog = document([[(72, 700, 28, "Locked page 1 of 1"), (72, 640, 16, "The password was accepted.")]])
    return pdf.build(catalog, encrypt=(key, encrypt), file_id=file_id)


def form():
    """One page, six merged field/widget dictionaries, /NeedAppearances true (no appearance streams).
    Widget rects are PDF user space (origin bottom-left) on a 612 x 792 page."""
    pdf = Pdf()
    catalog = pdf.reserve()
    pages = pdf.reserve()
    font = pdf.add(b"<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica /Encoding /WinAnsiEncoding >>")
    content = pdf.add((b"", text_stream([
        (72, 720, 24, "Form fixture"),
        (72, 676, 12, "Name"), (72, 616, 12, "Notes"), (72, 536, 12, "Subscribe"),
        (72, 496, 12, "Plan: basic / pro"), (72, 456, 12, "Country"), (72, 416, 12, "Id"),
    ])))
    page = pdf.reserve()
    name, notes, subscribe, plan, plan_basic, plan_pro, country, ro = [pdf.reserve() for _ in range(8)]
    # One empty 20 x 20 form XObject names the on and off states of the check box and the radio
    # buttons; it draws nothing, so the box and buttons rely on /NeedAppearances for their look.
    blank = pdf.add((b"/Type /XObject /Subtype /Form /BBox [0 0 20 20]", b""))
    da = b"/DA (/Helv 12 Tf 0 g)"

    def widget(rect, extra):
        return b"/Type /Annot /Subtype /Widget /P %d 0 R /Rect [%d %d %d %d] /F 4 %s" % (
            page, rect[0], rect[1], rect[2], rect[3], extra)

    def states(on):
        return b"/AP << /N << /%s %d 0 R /Off %d 0 R >> >>" % (on, blank, blank)

    pdf.put(name, b"<< " + widget((150, 660, 400, 684), b"/FT /Tx /T (name) /Ff 2 " + da) + b" >>")
    pdf.put(notes, b"<< " + widget((150, 570, 400, 650), b"/FT /Tx /T (notes) /Ff 4096 " + da) + b" >>")
    pdf.put(subscribe, b"<< " + widget((150, 530, 170, 550), b"/FT /Btn /T (subscribe) /V /Off /AS /Off " + states(b"Yes")) + b" >>")
    pdf.put(plan, b"<< /FT /Btn /Ff 49152 /T (plan) /V /Off /Kids [%d 0 R %d 0 R] >>" % (plan_basic, plan_pro))
    pdf.put(plan_basic, b"<< " + widget((150, 490, 170, 510), b"/Parent %d 0 R /AS /Off " % plan + states(b"basic")) + b" >>")
    pdf.put(plan_pro, b"<< " + widget((200, 490, 220, 510), b"/Parent %d 0 R /AS /Off " % plan + states(b"pro")) + b" >>")
    pdf.put(country, b"<< " + widget((150, 450, 400, 472),
            b"/FT /Ch /T (country) /Ff 131072 /Opt [[(fr) (France)] [(de) (Germany)] [(es) (Spain)]] " + da) + b" >>")
    pdf.put(ro, b"<< " + widget((150, 410, 400, 432), b"/FT /Tx /T (id) /Ff 1 /V (A-1234) " + da) + b" >>")
    fields = [name, notes, subscribe, plan, country, ro]
    pdf.put(page,
            b"<< /Type /Page /Parent %d 0 R /MediaBox [0 0 612 792] /Contents %d 0 R"
            b" /Resources << /Font << /F1 %d 0 R /Helv %d 0 R >> >> /Annots [%s] >>" % (
                pages, content, font, font,
                b" ".join(b"%d 0 R" % n for n in (name, notes, subscribe, plan_basic, plan_pro, country, ro))))
    pdf.put(pages, b"<< /Type /Pages /Count 1 /Kids [%d 0 R] >>" % page)
    pdf.put(catalog,
            b"<< /Type /Catalog /Pages %d 0 R /AcroForm << /Fields [%s] /NeedAppearances true"
            b" /DA (/Helv 12 Tf 0 g) /DR << /Font << /Helv %d 0 R >> >> >> >>" % (
                pages, b" ".join(b"%d 0 R" % n for n in fields), font))
    return pdf.build(catalog)


def main():
    if len(sys.argv) != 2:
        sys.exit(__doc__)
    out = sys.argv[1]
    os.makedirs(out, exist_ok=True)
    for name, data in (
        ("text3.pdf", text3()),
        ("big300.pdf", big300()),
        ("corrupt.pdf", corrupt()),
        ("locked.pdf", locked()),
        ("rotated.pdf", rotated()),
        ("form.pdf", form()),
    ):
        with open(os.path.join(out, name), "wb") as f:
            f.write(data)
        print("%s %d bytes" % (name, len(data)))


if __name__ == "__main__":
    main()
