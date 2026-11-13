package app.pdfa.engine.protocol

import app.pdfa.engine.*
import kotlin.random.Random

/** Seeded random values for property-style round trips. Strings include non-ASCII text. */
class Gen(seed: Long) {
    val r = Random(seed)

    fun int(max: Int = 5000) = r.nextInt(-max, max)
    fun page() = r.nextInt(0, 500)
    fun long() = r.nextLong()
    fun bool() = r.nextBoolean()
    fun float() = r.nextFloat() * 2000f - 1000f
    fun str(max: Int = 20): String {
        val alphabet = "abcXYZ 012_-éàü漢字😀"
        val n = r.nextInt(0, max)
        val sb = StringBuilder()
        repeat(n) {
            val c = alphabet[r.nextInt(alphabet.length)]
            // keep surrogate pairs intact
            if (Character.isHighSurrogate(c)) sb.append(c).append('\uDE00') else if (!Character.isLowSurrogate(c)) sb.append(c)
        }
        return sb.toString()
    }
    fun <T : Any> opt(f: () -> T): T? = if (r.nextInt(3) == 0) null else f()
    fun bytes(max: Int = 200) = r.nextBytes(r.nextInt(0, max))
    fun <T> list(max: Int = 5, f: () -> T) = List(r.nextInt(0, max + 1)) { f() }
    fun ints() = list(6) { page() }

    fun pt() = Pt(float(), float())
    fun rect() = Rect(float(), float(), float(), float())
    fun quad() = Quad(pt(), pt(), pt(), pt())
    fun pageInfo() = PageInfo(float(), float(), str())
    fun editState() = EditState(bool(), bool(), opt { str() }, int(), bool())
    fun docMeta() = DocMeta(str(), str(), str(), str(), str(), str(), int(), long(), bool(), bool(), bool(), bool(), bool())
    fun tileRequest() = TileRequest(page(), float(), int(), int(), int(), int(), bool())
    fun tileInfo() = TileInfo(int(), int(), int(), long())
    fun searchHit() = SearchHit(page(), list { rect() })
    fun textSelection() = TextSelection(str(), list { rect() })
    fun outlineRow() = OutlineRow(ints(), str(), opt { page() }, bool())
    fun outlinePage() = OutlinePage(list { outlineRow() }, opt { page() })
    fun linkTarget(): LinkTarget = when (r.nextInt(3)) {
        0 -> LinkTarget.Page(page())
        1 -> LinkTarget.Uri(str())
        else -> LinkTarget.Other(str())
    }
    fun pageLink() = PageLink(rect(), linkTarget())
    fun stroke() = Stroke(list(6) { pt() })
    fun newMarkup() = NewMarkup(
        page(), MarkupKind.entries.random(r), r.nextInt(0, 0x1000000), r.nextFloat(), float(),
        list(3) { stroke() }, list { rect() }, str(), str(),
    )
    fun fieldOption() = FieldOption(str(), str())
    fun fieldWidget() = FieldWidget(page(), rect(), opt { str() })
    fun fieldInfo() = FieldInfo(
        str(), FieldKind.entries.random(r), list { str() }, list { fieldOption() }, bool(), bool(), bool(),
        opt { page() }, list(3) { fieldWidget() },
    )
    fun fieldValue(): FieldValue = when (r.nextInt(4)) {
        0 -> FieldValue.Text(str())
        1 -> FieldValue.Check(bool())
        2 -> FieldValue.Radio(opt { str() })
        else -> FieldValue.Choice(list { str() })
    }
    fun protectionSpec() = ProtectionSpec(
        opt { str() }, opt { str() }, PrintPerm.entries.random(r), ChangePerm.entries.random(r), bool(), bool(),
    )
    fun pageRange() = PageRange(page(), page())
    fun mergeSource() = MergeSource(str(), bytes(), opt { str() }, list { pageRange() })
    fun splitSpec(): SplitSpec = if (bool()) SplitSpec.EveryN(int()) else SplitSpec.Before(ints())
    fun splitPart() = SplitPart(str(), bytes())
    fun imageInput() = ImageInput(str(), bytes())
    fun imageFrame() = ImageFrame(int(), int(), bytes())
}

/** Structural equality for the contract classes that are not data classes. */
fun eq(a: MergeSource, b: MergeSource) =
    a.name == b.name && a.bytes.contentEquals(b.bytes) && a.password == b.password && a.ranges == b.ranges
fun eq(a: SplitPart, b: SplitPart) = a.name == b.name && a.bytes.contentEquals(b.bytes)
fun eq(a: ImageInput, b: ImageInput) = a.name == b.name && a.bytes.contentEquals(b.bytes)
fun eq(a: ImageFrame, b: ImageFrame) = a.width == b.width && a.height == b.height && a.rgba.contentEquals(b.rgba)
