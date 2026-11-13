package app.pdfa.design

import androidx.compose.ui.graphics.Color
import java.io.File

/**
 * Minimal reader for docs/design/tokens.json. The file is written in a flat, one-key-per-value
 * style, so targeted regular expressions are enough and no JSON library is needed on the
 * JVM test classpath. Unit tests run with the module directory as the working directory.
 */
internal object TokensFile {
    private val text: String by lazy {
        val file = File("../../docs/design/tokens.json")
        check(file.isFile) { "tokens.json not found at ${file.absolutePath}" }
        file.readText()
    }

    /** A colour under `color.<theme>` (theme is "light" or "dark"), as written in the file. */
    fun themeColor(theme: String, key: String): String {
        val start = text.indexOf("\"$theme\": {")
        check(start >= 0) { "no \"$theme\" block" }
        val end = text.indexOf('}', start)
        val block = text.substring(start, end)
        return hex(block, key)
    }

    /** A colour directly under `color`, such as pageSheet. */
    fun globalColor(key: String): String = hex(text, key)

    /** A number under any key, first occurrence. */
    fun number(key: String): Float {
        val match = Regex("\"$key\"\\s*:\\s*([0-9.]+)").find(text)
        checkNotNull(match) { "no number for $key" }
        return match.groupValues[1].toFloat()
    }

    /** The spacing scale array, e.g. [0, 4, 8, ...]. */
    fun spacingScale(): List<Float> {
        val match = Regex("\"scale\"\\s*:\\s*\\[([^\\]]*)\\]").find(text)
        checkNotNull(match) { "no spacing scale" }
        return match.groupValues[1].split(",").map { it.trim().toFloat() }
    }

    /** One typography row: size, line, weight and tracking. */
    fun typography(name: String): Map<String, Float> {
        val match = Regex(
            "\"$name\"\\s*:\\s*\\{\\s*\"size\"\\s*:\\s*([0-9.]+)\\s*,\\s*\"line\"\\s*:\\s*([0-9.]+)\\s*," +
                "\\s*\"weight\"\\s*:\\s*([0-9.]+)\\s*,\\s*\"tracking\"\\s*:\\s*([0-9.]+)",
        ).find(text)
        checkNotNull(match) { "no typography row $name" }
        val (size, line, weight, tracking) = match.groupValues.drop(1).map { it.toFloat() }
        return mapOf("size" to size, "line" to line, "weight" to weight, "tracking" to tracking)
    }

    private fun hex(source: String, key: String): String {
        val match = Regex("\"$key\"\\s*:\\s*\"(#[0-9A-Fa-f]{6,8})\"").find(source)
        checkNotNull(match) { "no colour for $key" }
        return match.groupValues[1]
    }

    /** Parses #RRGGBB or #RRGGBBAA (CSS order, alpha last) into a Compose colour. */
    fun parse(hex: String): Color {
        val digits = hex.removePrefix("#")
        val rgb = digits.substring(0, 6).toLong(16)
        val alpha = if (digits.length == 8) digits.substring(6, 8).toLong(16) else 0xFFL
        return Color((alpha shl 24) or rgb)
    }
}
