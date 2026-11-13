package app.pdfa.pages.tools

import app.pdfa.engine.PageRange
import app.pdfa.engine.SplitSpec

/** Why a typed page list or a split setting was refused. The screen turns it into a sentence. */
enum class InputError {
    EMPTY,
    SYNTAX,
    ZERO,
    REVERSED,
    OUT_OF_BOUNDS,
    OVERLAP,
    TOO_MANY,
    N_TOO_SMALL,
    N_TOO_BIG,
    FIRST_PAGE,
    DUPLICATE,
    TOO_MANY_PARTS,
}

sealed interface RangeResult {
    /** Nothing typed. Merge reads this as "the whole document"; extract refuses it. */
    data object Empty : RangeResult

    /** Ranges in the order typed, 0-based and inclusive, as the engine wants them. */
    data class Ok(val ranges: List<PageRange>) : RangeResult

    data class Invalid(val error: InputError) : RangeResult
}

/** The most ranges accepted in one list. */
internal const val MAX_RANGES = 1000
private const val MAX_DIGITS = 9
private val RANGE_TOKEN = Regex("([0-9]{1,$MAX_DIGITS})(?:\\s*[-–]\\s*([0-9]{1,$MAX_DIGITS}))?")

/**
 * Parses what the user typed, 1-based ("1-3, 6"), against a document of [pageCount] pages.
 * Whitespace is free. Reversed, past-the-end, repeated and overlapping pages are refused, never
 * silently fixed. The result is 0-based and inclusive.
 */
fun parseRanges(text: String, pageCount: Int): RangeResult {
    if (text.isBlank()) return RangeResult.Empty
    val tokens = text.split(',')
    if (tokens.size > MAX_RANGES) return RangeResult.Invalid(InputError.TOO_MANY)
    val out = ArrayList<PageRange>(tokens.size)
    for (raw in tokens) {
        val match = RANGE_TOKEN.matchEntire(raw.trim()) ?: return RangeResult.Invalid(InputError.SYNTAX)
        val from = match.groupValues[1].toInt()
        val to = match.groupValues[2].ifEmpty { match.groupValues[1] }.toInt()
        if (from == 0 || to == 0) return RangeResult.Invalid(InputError.ZERO)
        if (from > to) return RangeResult.Invalid(InputError.REVERSED)
        if (to > pageCount) return RangeResult.Invalid(InputError.OUT_OF_BOUNDS)
        val range = PageRange(from - 1, to - 1)
        if (out.any { it.start <= range.endInclusive && range.start <= it.endInclusive }) {
            return RangeResult.Invalid(InputError.OVERLAP)
        }
        out += range
    }
    return RangeResult.Ok(out)
}

/** The 0-based page indices of [ranges], in order. */
fun List<PageRange>.pageIndices(): List<Int> = flatMap { it.start..it.endInclusive }

/** How many pages [ranges] select. */
fun List<PageRange>.pageTotal(): Int = sumOf { it.endInclusive - it.start + 1 }

/** The 1-based text for [ranges]: "1-3, 6". */
fun List<PageRange>.toDisplay(): String =
    joinToString(", ") { if (it.start == it.endInclusive) "${it.start + 1}" else "${it.start + 1}-${it.endInclusive + 1}" }

// ---- split ----

/** Parts above this are refused before the engine is asked. */
const val MAX_PARTS = 1000

sealed interface SplitPlan {
    /** [parts] is how many files the split will make. */
    data class Ok(val spec: SplitSpec, val parts: Int) : SplitPlan

    data class Invalid(val error: InputError) : SplitPlan
}

/** "Every [text] pages" for a document of [pageCount] pages. */
fun planEveryN(text: String, pageCount: Int): SplitPlan {
    val t = text.trim()
    if (t.isEmpty()) return SplitPlan.Invalid(InputError.EMPTY)
    if (!Regex("[0-9]{1,$MAX_DIGITS}").matches(t)) return SplitPlan.Invalid(InputError.SYNTAX)
    val n = t.toInt()
    if (n < 1) return SplitPlan.Invalid(InputError.N_TOO_SMALL)
    if (n >= pageCount) return SplitPlan.Invalid(InputError.N_TOO_BIG)
    val parts = (pageCount + n - 1) / n
    if (parts > MAX_PARTS) return SplitPlan.Invalid(InputError.TOO_MANY_PARTS)
    return SplitPlan.Ok(SplitSpec.EveryN(n), parts)
}

/** "A new part starts at each page in [text]" (1-based, any order). Page 1 always starts part one. */
fun planBefore(text: String, pageCount: Int): SplitPlan {
    if (text.isBlank()) return SplitPlan.Invalid(InputError.EMPTY)
    val tokens = text.split(',')
    if (tokens.size > MAX_PARTS) return SplitPlan.Invalid(InputError.TOO_MANY_PARTS)
    val pages = ArrayList<Int>(tokens.size)
    for (raw in tokens) {
        val t = raw.trim()
        if (!Regex("[0-9]{1,$MAX_DIGITS}").matches(t)) return SplitPlan.Invalid(InputError.SYNTAX)
        val page = t.toInt()
        if (page == 0) return SplitPlan.Invalid(InputError.ZERO)
        if (page == 1) return SplitPlan.Invalid(InputError.FIRST_PAGE)
        if (page > pageCount) return SplitPlan.Invalid(InputError.OUT_OF_BOUNDS)
        pages += page
    }
    if (pages.toSet().size != pages.size) return SplitPlan.Invalid(InputError.DUPLICATE)
    val parts = pages.size + 1
    if (parts > MAX_PARTS) return SplitPlan.Invalid(InputError.TOO_MANY_PARTS)
    return SplitPlan.Ok(SplitSpec.Before(pages.sorted().map { it - 1 }), parts)
}

/**
 * The file name of part [index] (0-based) out of [total]: part-01.pdf, part-02.pdf ... The number is
 * padded to two digits, or more when there are 100 or more parts, so names sort in order.
 */
fun partName(index: Int, total: Int): String {
    require(index in 0 until total) { "part $index of $total" }
    val width = maxOf(2, total.toString().length)
    return "part-" + (index + 1).toString().padStart(width, '0') + ".pdf"
}
