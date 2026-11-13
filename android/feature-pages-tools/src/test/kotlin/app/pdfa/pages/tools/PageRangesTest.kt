package app.pdfa.pages.tools

import app.pdfa.engine.PageRange
import app.pdfa.engine.SplitSpec
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class PageRangesTest {
    private fun ok(text: String, count: Int = 10) = (parseRanges(text, count) as RangeResult.Ok).ranges
    private fun bad(text: String, count: Int = 10) = (parseRanges(text, count) as RangeResult.Invalid).error

    @Test fun empty_and_blank_are_empty() {
        assertEquals(RangeResult.Empty, parseRanges("", 10))
        assertEquals(RangeResult.Empty, parseRanges("  \t ", 10))
    }

    @Test fun oneBased_becomesZeroBasedInclusive() {
        assertEquals(listOf(PageRange(0, 2), PageRange(5, 5)), ok("1-3, 6"))
        assertEquals(listOf(PageRange(9, 9)), ok("10"))
    }

    @Test fun whitespace_isFree_andOrderIsKept() {
        assertEquals(listOf(PageRange(6, 7), PageRange(0, 1)), ok("  7 -  8 ,\t1-2  "))
        assertEquals(listOf(PageRange(2, 4)), ok("3–5")) // en dash
    }

    @Test fun roundTrip_toDisplay() {
        assertEquals("1-3, 6", ok("1-3,6").toDisplay())
        assertEquals("2", ok("2-2").toDisplay())
        assertEquals(listOf(0, 1, 2, 5), ok("1-3, 6").pageIndices())
        assertEquals(4, ok("1-3, 6").pageTotal())
    }

    @Test fun reversed() = assertEquals(InputError.REVERSED, bad("5-3"))

    @Test fun zero() {
        assertEquals(InputError.ZERO, bad("0"))
        assertEquals(InputError.ZERO, bad("0-3"))
    }

    @Test fun outOfBounds() {
        assertEquals(InputError.OUT_OF_BOUNDS, bad("11"))
        assertEquals(InputError.OUT_OF_BOUNDS, bad("8-11"))
        assertEquals(listOf(PageRange(9, 9)), ok("10")) // the last page is fine
    }

    @Test fun duplicatesAndOverlaps_areRefused_notFixed() {
        assertEquals(InputError.OVERLAP, bad("2, 2"))
        assertEquals(InputError.OVERLAP, bad("1-3, 3-5"))
        assertEquals(InputError.OVERLAP, bad("4-6, 5"))
        assertEquals(InputError.OVERLAP, bad("6, 1-9"))
        assertEquals(listOf(PageRange(0, 2), PageRange(3, 4)), ok("1-3, 4-5")) // adjacent is fine
    }

    @Test fun syntax() {
        for (t in listOf("a", "1-", "-3", "1,,2", "1,", ",1", "1 2", "1-2-3", "1.5", "+3", "٣", "1;2")) {
            assertEquals(InputError.SYNTAX, bad(t), "for '$t'")
        }
    }

    @Test fun hugeNumbers_doNotCrash() {
        assertEquals(InputError.SYNTAX, bad("99999999999999999999"))
        assertEquals(InputError.OUT_OF_BOUNDS, bad("999999999"))
    }

    @Test fun tooManyRanges() {
        val text = (1..1001).joinToString(",") { "1" }
        assertEquals(InputError.TOO_MANY, bad(text, 5))
    }

    // ---- split ----

    @Test fun everyN() {
        val p = planEveryN(" 3 ", 10) as SplitPlan.Ok
        assertEquals(SplitSpec.EveryN(3), p.spec)
        assertEquals(4, p.parts)
        assertEquals(10, (planEveryN("1", 10) as SplitPlan.Ok).parts)
        assertEquals(2, (planEveryN("9", 10) as SplitPlan.Ok).parts)
    }

    @Test fun everyN_errors() {
        assertEquals(InputError.EMPTY, (planEveryN("", 10) as SplitPlan.Invalid).error)
        assertEquals(InputError.SYNTAX, (planEveryN("x", 10) as SplitPlan.Invalid).error)
        assertEquals(InputError.SYNTAX, (planEveryN("-2", 10) as SplitPlan.Invalid).error)
        assertEquals(InputError.N_TOO_SMALL, (planEveryN("0", 10) as SplitPlan.Invalid).error)
        assertEquals(InputError.N_TOO_BIG, (planEveryN("10", 10) as SplitPlan.Invalid).error)
        assertEquals(InputError.N_TOO_BIG, (planEveryN("50", 10) as SplitPlan.Invalid).error)
    }

    @Test fun before_convertsSortsAndCounts() {
        val p = planBefore("7, 4", 10) as SplitPlan.Ok
        assertEquals(SplitSpec.Before(listOf(3, 6)), p.spec)
        assertEquals(3, p.parts)
    }

    @Test fun before_errors() {
        fun err(t: String) = (planBefore(t, 10) as SplitPlan.Invalid).error
        assertEquals(InputError.EMPTY, err(" "))
        assertEquals(InputError.FIRST_PAGE, err("1"))
        assertEquals(InputError.FIRST_PAGE, err("4, 1"))
        assertEquals(InputError.ZERO, err("0"))
        assertEquals(InputError.OUT_OF_BOUNDS, err("11"))
        assertEquals(InputError.DUPLICATE, err("4, 4"))
        assertEquals(InputError.SYNTAX, err("4-6"))
        assertEquals(InputError.SYNTAX, err("4,,6"))
    }

    @Test fun partCap_1000IsFine_1001IsRefusedBeforeTheEngine() {
        assertEquals(1000, (planEveryN("1", 1000) as SplitPlan.Ok).parts)
        assertEquals(InputError.TOO_MANY_PARTS, (planEveryN("1", 1001) as SplitPlan.Invalid).error)
        assertEquals(1000, (planEveryN("2", 2000) as SplitPlan.Ok).parts)
        // Before: 999 points make 1000 parts, 1000 points make 1001.
        val ok = (2..1000).joinToString(",")
        assertEquals(1000, (planBefore(ok, 2000) as SplitPlan.Ok).parts)
        val tooMany = (2..1001).joinToString(",")
        assertEquals(InputError.TOO_MANY_PARTS, (planBefore(tooMany, 2000) as SplitPlan.Invalid).error)
    }

    @Test fun partNames() {
        assertEquals("part-01.pdf", partName(0, 3))
        assertEquals("part-03.pdf", partName(2, 3))
        assertEquals("part-99.pdf", partName(98, 99))
        assertEquals("part-001.pdf", partName(0, 100))
        assertEquals("part-100.pdf", partName(99, 100))
        assertEquals("part-1000.pdf", partName(999, 1000))
        assertEquals("part-0001.pdf", partName(0, 1000))
        assertFailsWith<IllegalArgumentException> { partName(3, 3) }
        assertFailsWith<IllegalArgumentException> { partName(-1, 3) }
    }
}
