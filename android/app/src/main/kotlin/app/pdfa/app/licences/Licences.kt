package app.pdfa.app.licences

import android.content.res.AssetManager
import androidx.annotation.StringRes
import app.pdfa.app.R

/** One licence text bundled in `assets/licenses/`. [titleRes] is a string resource, shown as a heading. */
data class LicenceDoc(@StringRes val titleRes: Int, val assetName: String)

/**
 * The texts shown on the Licences screen, in order. Upstream PdfCraft texts are copied verbatim
 * from third_party/pdfcraft; PROJECT-NOTICE.txt is this project's own notice.
 */
object Licences {
    const val DIR = "licenses"

    val documents: List<LicenceDoc> = listOf(
        LicenceDoc(R.string.licence_title_mit, "LICENSE-MIT"),
        LicenceDoc(R.string.licence_title_apache, "LICENSE-APACHE"),
        LicenceDoc(R.string.licence_title_notice, "NOTICE"),
        LicenceDoc(R.string.licence_title_project, "PROJECT-NOTICE.txt"),
    )

    /** Reads one text asset. Call on a background dispatcher only. */
    fun read(assets: AssetManager, doc: LicenceDoc): String =
        assets.open("$DIR/${doc.assetName}").bufferedReader(Charsets.UTF_8).use { it.readText() }
}
