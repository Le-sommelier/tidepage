package app.pdfa.scan

import java.time.LocalDate

/** Supplies today's date; injected so names are testable. */
fun interface ScanClock {
    fun today(): LocalDate
}

object ScanNaming {
    private const val MAX_STEM = 100

    /** "Scan 2026-11-10.pdf", or "Scan 2026-11-10 (2).pdf" when that name is [taken]. */
    fun suggest(clock: ScanClock, taken: Set<String> = emptySet()): String {
        val stem = "Scan ${clock.today()}"
        if ("$stem.pdf" !in taken) return "$stem.pdf"
        var n = 2
        while ("$stem ($n).pdf" in taken) n++
        return "$stem ($n).pdf"
    }

    /**
     * A name the user typed, made safe as a file name: no path separators or reserved characters,
     * no control characters, one ".pdf" extension, not empty. Falls back to [fallback].
     */
    fun sanitize(typed: String, fallback: String): String {
        val cleaned = typed
            .map { if (it.isISOControl() || it in "/\\:*?\"<>|") ' ' else it }
            .joinToString("")
            .replace(Regex("\\s+"), " ")
            .trim()
        val noExt = if (cleaned.endsWith(".pdf", ignoreCase = true)) cleaned.dropLast(4) else cleaned
        val stem = noExt.trim().trim('.').take(MAX_STEM).trim()
        return if (stem.isEmpty()) fallback else "$stem.pdf"
    }
}
