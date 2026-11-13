package app.pdfa.scan

import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals

class ScanNamingTest {
    private val clock = ScanClock { LocalDate.of(2026, 11, 10) }

    @Test
    fun suggestsDatedName() {
        assertEquals("Scan 2026-11-10.pdf", ScanNaming.suggest(clock))
    }

    @Test
    fun padsSingleDigitMonthAndDay() {
        assertEquals("Scan 2026-01-05.pdf", ScanNaming.suggest(ScanClock { LocalDate.of(2026, 1, 5) }))
    }

    @Test
    fun avoidsTakenNames() {
        assertEquals("Scan 2026-11-10 (2).pdf", ScanNaming.suggest(clock, setOf("Scan 2026-11-10.pdf")))
        assertEquals("Scan 2026-11-10 (3).pdf", ScanNaming.suggest(clock, setOf("Scan 2026-11-10.pdf", "Scan 2026-11-10 (2).pdf")))
    }

    @Test
    fun sanitizeKeepsGoodNamesAndAddsOneExtension() {
        assertEquals("Lease.pdf", ScanNaming.sanitize("Lease", "x.pdf"))
        assertEquals("Lease.pdf", ScanNaming.sanitize("Lease.pdf", "x.pdf"))
        assertEquals("Lease.pdf", ScanNaming.sanitize("  Lease.PDF ", "x.pdf"))
    }

    @Test
    fun sanitizeRemovesPathAndReservedCharacters() {
        assertEquals("a b.pdf", ScanNaming.sanitize("a/b", "x.pdf"))
        assertEquals("a b c.pdf", ScanNaming.sanitize("a:b*c", "x.pdf"))
        assertEquals("evil.pdf", ScanNaming.sanitize("../evil", "x.pdf"))
        assertEquals("a b.pdf", ScanNaming.sanitize("a\nb", "x.pdf"))
    }

    @Test
    fun sanitizeFallsBackWhenNothingIsLeft() {
        assertEquals("fb.pdf", ScanNaming.sanitize("", "fb.pdf"))
        assertEquals("fb.pdf", ScanNaming.sanitize("  ...  ", "fb.pdf"))
        assertEquals("fb.pdf", ScanNaming.sanitize("///", "fb.pdf"))
        assertEquals("fb.pdf", ScanNaming.sanitize(".pdf", "fb.pdf"))
    }

    @Test
    fun sanitizeLimitsLength() {
        assertEquals(104, ScanNaming.sanitize("a".repeat(300), "x.pdf").length)
    }
}
