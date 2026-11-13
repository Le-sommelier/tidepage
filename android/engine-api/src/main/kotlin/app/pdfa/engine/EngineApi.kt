package app.pdfa.engine

import kotlinx.coroutines.flow.Flow

/**
 * The only door between the UI and the PDF engine. Feature modules depend on this interface
 * and on nothing else from the engine. Implementations: `:engine-impl` (isolated process,
 * watchdog) and `:engine-fake` (in memory, scriptable failures).
 *
 * Contract rules:
 *  - Every call is `suspend`, never touches the main thread, and may throw [EngineError].
 *  - Page indices are 0-based. Coordinates are page view space (see Models.kt).
 *  - Every mutating call returns the new [EditState].
 *  - After [EngineError.EngineDied] or [EngineError.Timeout] every [DocHandle] is invalid;
 *    the client reopens documents from the journal and emits [EngineEvent.SafeModeRestarted].
 *  - Calls on one handle are serialised by the implementation; different handles may overlap.
 *  - Inputs over 256 MB and renders over 64 megapixels throw [EngineError.TooLarge].
 */
interface EngineApi {
    // ---- lifecycle ----
    /** Throws [EngineError.PasswordRequired] or [EngineError.WrongPassword] when encrypted. */
    suspend fun open(bytes: ByteArray, password: String? = null): DocHandle
    suspend fun close(handle: DocHandle)

    // ---- read: viewer ----
    suspend fun meta(handle: DocHandle): DocMeta
    suspend fun pageInfos(handle: DocHandle): List<PageInfo>
    suspend fun renderTile(handle: DocHandle, request: TileRequest): TileBitmap
    suspend fun searchPage(
        handle: DocHandle,
        page: Int,
        query: String,
        caseSensitive: Boolean = false,
        wholeWord: Boolean = false,
    ): List<Rect>

    /** Equal [from] and [to] select the word under the point; null if there is no text there. */
    suspend fun selectText(handle: DocHandle, page: Int, from: Pt, to: Pt): TextSelection?
    suspend fun outline(handle: DocHandle, offset: Int = 0, limit: Int = 200): OutlinePage
    suspend fun links(handle: DocHandle, page: Int): List<PageLink>

    // ---- edit: organize ----
    suspend fun state(handle: DocHandle): EditState
    suspend fun rotate(handle: DocHandle, pages: List<Int>, by: Rotation): EditState
    suspend fun delete(handle: DocHandle, pages: List<Int>): EditState

    /**
     * Moves [pages] so the first of them lands at [toIndex], an index in the RESULT.
     * Valid range: 0..(pageCount - pages.size); anything larger throws InvalidArgument.
     */
    suspend fun move(handle: DocHandle, pages: List<Int>, toIndex: Int): EditState
    suspend fun duplicate(handle: DocHandle, pages: List<Int>): EditState
    suspend fun insertBlank(handle: DocHandle, at: Int, widthPt: Float, heightPt: Float): EditState
    suspend fun undo(handle: DocHandle): EditState
    suspend fun redo(handle: DocHandle): EditState

    // ---- edit: annotate and forms ----
    suspend fun addMarkup(handle: DocHandle, markup: NewMarkup): EditState
    suspend fun listFields(handle: DocHandle): List<FieldInfo>
    suspend fun setField(handle: DocHandle, name: String, value: FieldValue): EditState

    /** Clears the named fields, or every field when [names] is null. */
    suspend fun resetForm(handle: DocHandle, names: List<String>? = null): EditState

    // ---- save ----
    /**
     * Appends changes to the original bytes where possible. Result is the full file. Does NOT
     * clear [EditState.dirty]: call [confirmSaved] only after the bytes were durably written.
     */
    suspend fun saveIncremental(handle: DocHandle): ByteArray
    suspend fun saveFull(handle: DocHandle): ByteArray

    /** Marks the document as saved (clears `dirty`) after the caller verified its write. */
    suspend fun confirmSaved(handle: DocHandle): EditState

    /** Gated: throws [EngineError.Unsupported] unless the `encrypt` feature flag is on. */
    suspend fun saveProtected(handle: DocHandle, spec: ProtectionSpec): ByteArray

    /** Removes encryption; the next save writes an unprotected file. Gated like protect. */
    suspend fun removeProtection(handle: DocHandle): EditState

    // ---- files ----
    suspend fun extract(handle: DocHandle, pages: List<Int>): ByteArray
    suspend fun split(handle: DocHandle, spec: SplitSpec): List<SplitPart>
    suspend fun merge(sources: List<MergeSource>): ByteArray
    suspend fun imagesToPdf(images: List<ImageInput>, dpi: Double? = null): ByteArray

    // ---- scan (stateless, pure Rust) ----
    suspend fun detectDocumentCorners(frame: ImageFrame): Quad?

    /** Perspective-corrects [frame] to [quad], cleans it, and returns an RGBA frame. */
    suspend fun rectifyAndClean(frame: ImageFrame, quad: Quad, look: ScanLook, level: Int): ImageFrame

    // ---- supervision ----
    val events: Flow<EngineEvent>
}

sealed interface EngineEvent {
    /** The engine process was restarted; open documents were reopened from the journal. */
    data class SafeModeRestarted(val atEpochMs: Long, val reason: String) : EngineEvent

    /** Long operations (merge, split, scan) report 0.0..1.0. */
    data class Progress(val opId: String, val fraction: Float) : EngineEvent

    /**
     * After an engine restart this document was reopened WITHOUT the edits made since the last
     * autosave (undo history is gone too): [originalRestored] is true when the reopened content is
     * the bytes the document was first opened with, false when it came from an autosave. The UI must
     * tell the user, and `dirty` is true until `confirmSaved`.
     */
    data class EditsLost(val handle: DocHandle, val atEpochMs: Long, val originalRestored: Boolean) : EngineEvent
}

/** Feature switches fixed at build time and readable by UI to hide unfinished features. */
data class FeatureFlags(
    /** Off until upstream bugs #774/#836 are verified fixed by the interop test. */
    val encrypt: Boolean = false,
)
