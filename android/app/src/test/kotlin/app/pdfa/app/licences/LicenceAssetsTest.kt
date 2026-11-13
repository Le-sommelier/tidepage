package app.pdfa.app.licences

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Unit tests run with the module directory as the working directory, so the bundled assets are read
 * from their source location. Compile-time copies (the upstream texts) are checked for their holders.
 */
class LicenceAssetsTest {

    private val dir = File("src/main/assets/licenses")

    private fun text(name: String): String {
        val file = File(dir, name)
        assertTrue(file.isFile, "missing asset: ${file.path}")
        return file.readText(Charsets.UTF_8)
    }

    @Test
    fun everyDocumentListedInTheScreenExistsAsAnAsset() {
        Licences.documents.forEach { doc ->
            assertTrue(File(dir, doc.assetName).isFile, "missing ${doc.assetName}")
            assertTrue(text(doc.assetName).isNotBlank(), "empty ${doc.assetName}")
        }
    }

    @Test
    fun mitLicenceNamesTheCopyrightHolder() {
        val mit = text("LICENSE-MIT")
        assertTrue(mit.contains("MIT License"))
        assertTrue(mit.contains("ArtCraft Team"), "copyright holder missing from LICENSE-MIT")
    }

    @Test
    fun apacheLicenceIsTheFullApacheText() {
        val apache = text("LICENSE-APACHE")
        assertTrue(apache.contains("Apache License"), "Apache License heading missing")
        assertTrue(apache.contains("Version 2.0, January 2004"))
    }

    @Test
    fun noticeCarriesTheHolderAndTheTrademarkStatement() {
        val notice = text("NOTICE")
        assertTrue(notice.contains("ArtCraft Team"))
        assertTrue(notice.contains("trademarks"), "NOTICE must keep the trademark statement")
    }

    @Test
    fun projectNoticeStatesTheTrademarkPolicyAndTheUpstreamLicences() {
        val notice = text("PROJECT-NOTICE.txt")
        assertTrue(notice.contains("ArtCraft"))
        assertTrue(notice.contains("PdfCraft"))
        assertTrue(notice.contains("MIT License"))
        assertTrue(notice.contains("Apache License"))
        assertTrue(notice.contains("does not use"), "the app must say it does not use the upstream marks")
    }

    @Test
    fun screenOrderStartsWithTheUpstreamLicences() {
        assertEquals(
            listOf("LICENSE-MIT", "LICENSE-APACHE", "NOTICE", "PROJECT-NOTICE.txt"),
            Licences.documents.map { it.assetName },
        )
    }
}
