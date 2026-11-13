package app.pdfa.engine.fake

import app.pdfa.engine.EngineError
import app.pdfa.engine.FieldKind
import app.pdfa.engine.FieldOption
import app.pdfa.engine.FieldWidget
import app.pdfa.engine.LinkTarget
import app.pdfa.engine.MarkupKind
import app.pdfa.engine.NewMarkup
import app.pdfa.engine.PageLink
import app.pdfa.engine.Pt
import app.pdfa.engine.Rect
import app.pdfa.engine.Stroke
import java.net.URLDecoder
import java.net.URLEncoder

/**
 * The fake file format. A fake PDF is the ASCII header `FAKEPDF:1` followed by one record per line:
 *
 *     tag;key=value;key=value
 *
 * Every value is percent-encoded (URL form encoding), so `;`, `|`, `=`, `,` and newlines in page
 * text, labels, field values or markup contents survive the round trip. Lists are encoded as
 * `e<item>` joined with `|`, each item encoded once more, so an empty list and a list holding one
 * empty string differ. Records: `doc`, `page` (then its `markup` and `link` records), `outline`,
 * `field`. Bytes that do not start with `FAKEPDF:` are not a fake PDF.
 */
internal object FakeFormat {
    private const val MAGIC = "FAKEPDF:"
    private const val HEADER = "FAKEPDF:1"

    fun encode(doc: FakeDocument): ByteArray {
        val out = StringBuilder()
        out.append(HEADER).append('\n')

        val docPairs = mutableListOf(
            "title" to doc.info.title,
            "author" to doc.info.author,
            "subject" to doc.info.subject,
            "keywords" to doc.info.keywords,
            "producer" to doc.info.producer,
            "version" to doc.info.pdfVersion,
            "print" to flag(doc.info.canPrint),
            "copy" to flag(doc.info.canCopy),
            "modify" to flag(doc.info.canModify),
            "annotate" to flag(doc.info.canAnnotate),
            "next" to doc.nextId.toString(),
        )
        // Presence of `password` means the saved bytes are protected.
        doc.openPassword?.let { docPairs += "password" to it }
        line(out, "doc", docPairs)

        for (p in doc.pages) {
            line(
                out, "page", listOf(
                    "id" to p.id.toString(),
                    "w" to p.width.toString(),
                    "h" to p.height.toString(),
                    "rot" to p.rotation.toString(),
                    "label" to p.label,
                    "text" to p.text,
                ),
            )
            for (m in p.markups) {
                line(
                    out, "markup", listOf(
                        "page" to p.id.toString(),
                        "kind" to m.kind.name,
                        "rgb" to m.rgb.toString(),
                        "opacity" to m.opacity.toString(),
                        "width" to m.widthPt.toString(),
                        "strokes" to encList(m.strokes.map { s -> s.points.joinToString(" ") { pointRaw(it) } }),
                        "rects" to encList(m.rects.map { rectRaw(it) }),
                        "contents" to m.contents,
                        "author" to m.author,
                    ),
                )
            }
            for (l in p.links) {
                val (kind, target) = when (val t = l.target) {
                    is LinkTarget.Page -> "page" to t.page.toString()
                    is LinkTarget.Uri -> "uri" to t.uri
                    is LinkTarget.Other -> "other" to t.detail
                }
                line(
                    out, "link", listOf(
                        "page" to p.id.toString(),
                        "rect" to rectRaw(l.rect),
                        "kind" to kind,
                        "target" to target,
                    ),
                )
            }
        }

        for (o in doc.outline) {
            val pairs = mutableListOf(
                "path" to o.path.joinToString("."),
                "title" to o.title,
                "open" to flag(o.open),
            )
            o.page?.let { pairs += "page" to it.toString() }
            line(out, "outline", pairs)
        }

        for (f in doc.fields) {
            val pairs = mutableListOf(
                "name" to f.name,
                "kind" to f.kind.name,
                "value" to encList(f.value),
                "default" to encList(f.defaultValue),
                "options" to encList(f.options.map { enc(it.export) + "," + enc(it.display) }),
                "widgets" to encList(f.widgets.map { w -> widgetRaw(w) }),
                "readonly" to flag(f.readOnly),
                "required" to flag(f.required),
                "multiline" to flag(f.multiline),
            )
            f.maxLen?.let { pairs += "maxlen" to it.toString() }
            line(out, "field", pairs)
        }
        return out.toString().toByteArray(Charsets.UTF_8)
    }

    /** Parses fake bytes. Anything that is not a well-formed fake document is [EngineError.InvalidPdf]. */
    fun decode(bytes: ByteArray): FakeDocument {
        val text = bytes.toString(Charsets.UTF_8)
        if (!text.startsWith(MAGIC)) bad("not a fake PDF: missing $MAGIC header")
        val lines = text.split('\n').filter { it.isNotBlank() }
        if (lines.first() != HEADER) bad("unsupported fake PDF header '${lines.first()}'")

        var docRec: Rec? = null
        val pageRecs = ArrayList<Rec>()
        val markups = HashMap<Int, MutableList<NewMarkup>>()
        val links = HashMap<Int, MutableList<PageLink>>()
        val outline = ArrayList<FakeOutline>()
        val fields = ArrayList<FakeField>()

        for (line in lines.drop(1)) {
            val rec = parseLine(line)
            when (rec.tag) {
                "doc" -> docRec = rec
                "page" -> pageRecs += rec
                "markup" -> {
                    val pageId = rec.int("page")
                    markups.getOrPut(pageId) { ArrayList() }.add(NewMarkup(
                        page = pageId,
                        kind = enumOf<MarkupKind>(rec.str("kind")),
                        rgb = rec.int("rgb"),
                        opacity = rec.float("opacity"),
                        widthPt = rec.float("width"),
                        strokes = rec.list("strokes").map { raw ->
                            Stroke(if (raw.isEmpty()) emptyList() else raw.split(' ').map { pointOf(it) })
                        },
                        rects = rec.list("rects").map { rectOf(it) },
                        contents = rec.str("contents"),
                        author = rec.str("author"),
                    ))
                }
                "link" -> {
                    val pageId = rec.int("page")
                    val kind = rec.str("kind")
                    val raw = rec.str("target")
                    val target = when (kind) {
                        "page" -> LinkTarget.Page(raw.toIntOrNull() ?: bad("bad link target '$raw'"))
                        "uri" -> LinkTarget.Uri(raw)
                        "other" -> LinkTarget.Other(raw)
                        else -> bad("unknown link kind '$kind'")
                    }
                    links.getOrPut(pageId) { ArrayList() }.add(PageLink(rectOf(rec.str("rect")), target))
                }
                "outline" -> outline += FakeOutline(
                    path = rec.str("path").let { p ->
                        if (p.isEmpty()) emptyList() else p.split('.').map { it.toIntOrNull() ?: bad("bad outline path") }
                    },
                    title = rec.str("title"),
                    page = if (rec.has("page")) rec.int("page") else null,
                    open = rec.flag("open"),
                )
                "field" -> fields += FakeField(
                    name = rec.str("name"),
                    kind = enumOf<FieldKind>(rec.str("kind")),
                    value = rec.list("value"),
                    options = rec.list("options").map { raw ->
                        val p = raw.split(',')
                        if (p.size != 2) bad("bad field option")
                        FieldOption(dec(p[0]), dec(p[1]))
                    },
                    widgets = rec.list("widgets").map { raw -> widgetOf(raw) },
                    readOnly = rec.flag("readonly"),
                    required = rec.flag("required"),
                    multiline = rec.flag("multiline"),
                    maxLen = if (rec.has("maxlen")) rec.int("maxlen") else null,
                    defaultValue = rec.list("default"),
                )
                else -> bad("unknown record '${rec.tag}'")
            }
        }

        val d = docRec ?: bad("missing doc record")
        val pages = pageRecs.map { r ->
            val id = r.int("id")
            val width = r.float("w")
            val height = r.float("h")
            val rotation = r.int("rot")
            if (!(width > 0f) || !(height > 0f)) bad("page $id has a non-positive size")
            if (rotation !in setOf(0, 90, 180, 270)) bad("page $id has rotation $rotation")
            FakePage(
                width = width,
                height = height,
                label = r.str("label"),
                text = r.str("text"),
                rotation = rotation,
                markups = markups.remove(id).orEmpty(),
                links = links.remove(id).orEmpty(),
                id = id,
            )
        }
        if (pages.isEmpty()) bad("no pages")
        if (pages.map { it.id }.toSet().size != pages.size) bad("duplicate page ids")
        if (markups.isNotEmpty() || links.isNotEmpty()) bad("markup or link refers to an unknown page")

        val info = FakeInfo(
            title = d.str("title"),
            author = d.str("author"),
            subject = d.str("subject"),
            keywords = d.str("keywords"),
            producer = d.str("producer"),
            pdfVersion = d.str("version"),
            canPrint = d.flag("print"),
            canCopy = d.flag("copy"),
            canModify = d.flag("modify"),
            canAnnotate = d.flag("annotate"),
        )
        val password = if (d.has("password")) d.str("password") else null
        val nextId = maxOf(d.int("next"), (pages.maxOf { it.id }) + 1)
        return FakeDocument(info, password, pages, outline, fields, nextId)
    }

    // ---- record plumbing ----

    private class Rec(val tag: String, private val raw: Map<String, String>) {
        fun has(key: String) = key in raw
        fun str(key: String): String = dec(raw[key] ?: bad("missing '$key' in '$tag' record"))
        fun flag(key: String): Boolean = str(key) == "1"
        fun int(key: String): Int = str(key).toIntOrNull() ?: bad("'$key' is not an integer")
        fun float(key: String): Float = str(key).toFloatOrNull() ?: bad("'$key' is not a number")
        fun list(key: String): List<String> = decList(str(key))
    }

    private fun parseLine(line: String): Rec {
        val parts = line.split(';')
        val map = HashMap<String, String>()
        for (p in parts.drop(1)) {
            val eq = p.indexOf('=')
            if (eq <= 0) bad("malformed field '$p'")
            map[p.substring(0, eq)] = p.substring(eq + 1)
        }
        return Rec(parts[0], map)
    }

    private fun line(out: StringBuilder, tag: String, pairs: List<Pair<String, String>>) {
        out.append(tag)
        for ((k, v) in pairs) out.append(';').append(k).append('=').append(enc(v))
        out.append('\n')
    }

    private fun flag(b: Boolean) = if (b) "1" else "0"

    private fun enc(s: String): String = URLEncoder.encode(s, "UTF-8")

    private fun dec(s: String): String = try {
        URLDecoder.decode(s, "UTF-8")
    } catch (e: IllegalArgumentException) {
        bad("malformed escape in '$s'")
    }

    /** Each item is encoded once and prefixed with `e`, so `[]` and `[""]` stay distinct. */
    private fun encList(items: List<String>): String = items.joinToString("|") { "e" + enc(it) }

    private fun decList(raw: String): List<String> =
        if (raw.isEmpty()) emptyList() else raw.split('|').map { dec(it.drop(1)) }

    private fun pointRaw(p: Pt) = "${p.x},${p.y}"

    private fun rectRaw(r: Rect) = "${r.left},${r.top},${r.right},${r.bottom}"

    private fun widgetRaw(w: FieldWidget): String {
        val on = w.onState?.let { "S" + enc(it) } ?: "N"
        return "${w.page},${rectRaw(w.rect)},$on"
    }

    private fun widgetOf(raw: String): FieldWidget {
        val p = raw.split(',')
        if (p.size != 6) bad("bad field widget")
        val onState = if (p[5].startsWith("S")) dec(p[5].substring(1)) else null
        return FieldWidget(
            page = p[0].toIntOrNull() ?: bad("bad widget page"),
            rect = Rect(p[1].toFloatOrNull() ?: bad("bad rect"), p[2].toFloatOrNull() ?: bad("bad rect"), p[3].toFloatOrNull() ?: bad("bad rect"), p[4].toFloatOrNull() ?: bad("bad rect")),
            onState = onState,
        )
    }

    private fun rectOf(raw: String): Rect {
        val p = raw.split(',').map { it.toFloatOrNull() ?: bad("bad rect '$raw'") }
        if (p.size != 4) bad("bad rect '$raw'")
        return Rect(p[0], p[1], p[2], p[3])
    }

    private fun pointOf(raw: String): Pt {
        val p = raw.split(',').map { it.toFloatOrNull() ?: bad("bad point '$raw'") }
        if (p.size != 2) bad("bad point '$raw'")
        return Pt(p[0], p[1])
    }

    private inline fun <reified E : Enum<E>> enumOf(s: String): E =
        enumValues<E>().firstOrNull { it.name == s } ?: bad("unknown ${E::class.simpleName} '$s'")

    private fun bad(detail: String): Nothing = throw EngineError.InvalidPdf(detail)
}
