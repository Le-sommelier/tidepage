package app.pdfa.app

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The product name is written once: `app_name` in res/values/strings.xml.
 * Everything else refers to it through R.string.app_name, so renaming touches one file.
 */
class ProductNameTest {

    private val productWord = Regex("(?<![.\\w])Tidepage(?![.\\w])", RegexOption.IGNORE_CASE)
    private val stringLiteral = Regex("\"(?:[^\"\\\\\\n]|\\\\.)*\"")

    private fun sourceFiles(root: String): List<File> =
        File(root).walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()

    @Test
    fun noKotlinStringLiteralSpellsTheProductName() {
        val hits = mutableListOf<String>()
        listOf("src/main/kotlin", "src/debug/kotlin", "src/release/kotlin").forEach { root ->
            sourceFiles(root).forEach { file ->
                file.readLines().forEachIndexed { index, line ->
                    stringLiteral.findAll(line).forEach { literal ->
                        if (productWord.containsMatchIn(literal.value)) {
                            hits += "${file.name}:${index + 1}: ${literal.value}"
                        }
                    }
                }
            }
        }
        assertTrue(hits.isEmpty(), "product name hard-coded, use R.string.app_name: $hits")
    }

    @Test
    fun userVisibleTextInResourcesComesFromOneDefinition() {
        val values = File("src/main/res/values").listFiles().orEmpty().filter { it.extension == "xml" }
        val textHits = values.filter { file ->
            Regex(">[^<]*Tidepage[^<]*<", RegexOption.IGNORE_CASE).containsMatchIn(file.readText())
        }
        assertEquals(listOf("strings.xml"), textHits.map { it.name }, "the product name text belongs in strings.xml only")

        val strings = File("src/main/res/values/strings.xml").readText()
        assertEquals(1, Regex("name=\"app_name\"").findAll(strings).count())
        assertEquals(1, Regex("Tidepage").findAll(strings).count())
    }

    @Test
    fun manifestUsesTheStringResourceForItsLabel() {
        val manifest = File("src/main/AndroidManifest.xml").readText()
        val labels = Regex("android:label=\"([^\"]*)\"").findAll(manifest).map { it.groupValues[1] }.toList()
        assertTrue(labels.isNotEmpty())
        assertTrue(labels.all { it == "@string/app_name" }, "labels must reference @string/app_name: $labels")
    }
}
