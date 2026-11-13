package app.pdfa.engine.fake

import app.pdfa.engine.FieldKind
import app.pdfa.engine.FieldOption
import app.pdfa.engine.FieldWidget
import app.pdfa.engine.LinkTarget
import app.pdfa.engine.NewMarkup
import app.pdfa.engine.PageLink

/** Document information (the PDF info dictionary) plus the permission flags DocMeta reports. */
data class FakeInfo(
    val title: String = "",
    val author: String = "",
    val subject: String = "",
    val keywords: String = "",
    val producer: String = "FakeEngine",
    val pdfVersion: String = "1.7",
    val canPrint: Boolean = true,
    val canCopy: Boolean = true,
    val canModify: Boolean = true,
    val canAnnotate: Boolean = true,
)

/**
 * One page. Seeds (see [FakeEngine.fakePdf]) use 0-based page indices in [links] and leave [id]
 * at 0. Inside the engine every page reference is a stable [id], so moves, deletes and duplicates
 * never retarget a link or an outline entry by accident.
 */
data class FakePage(
    val width: Float,
    val height: Float,
    val label: String = "",
    val text: String = "",
    /** Degrees clockwise: 0, 90, 180 or 270. */
    val rotation: Int = 0,
    val markups: List<NewMarkup> = emptyList(),
    val links: List<PageLink> = emptyList(),
    internal val id: Int = 0,
)

/** One outline row. [path] is the index path from the root (`[0]`, `[0, 1]`, ...). [page] is a 0-based index in seeds. */
data class FakeOutline(
    val path: List<Int>,
    val title: String,
    val page: Int? = null,
    val open: Boolean = false,
)

/** A form field. Widget pages are 0-based indices in seeds, stable ids inside the engine. */
data class FakeField(
    val name: String,
    val kind: FieldKind,
    val value: List<String> = emptyList(),
    val options: List<FieldOption> = emptyList(),
    val widgets: List<FieldWidget> = emptyList(),
    val readOnly: Boolean = false,
    val required: Boolean = false,
    val multiline: Boolean = false,
    val maxLen: Int? = null,
    /** The value `resetForm` restores. Defaults to the initial [value]. */
    val defaultValue: List<String> = value,
)

/**
 * The whole document as the engine holds it: immutable, so undo snapshots are just references.
 * [openPassword] is the password the saved bytes require, or null for an unprotected file.
 */
internal data class FakeDocument(
    val info: FakeInfo,
    val openPassword: String?,
    val pages: List<FakePage>,
    val outline: List<FakeOutline>,
    val fields: List<FakeField>,
    /** Next free page id. Ids are never reused, so a removed page's id stays dead. */
    val nextId: Int,
)
