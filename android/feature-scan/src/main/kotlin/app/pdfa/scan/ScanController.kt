package app.pdfa.scan

import app.pdfa.engine.EngineApi
import app.pdfa.engine.EngineError
import app.pdfa.engine.ImageFrame
import app.pdfa.engine.ImageInput
import app.pdfa.engine.Quad
import app.pdfa.engine.ScanLook
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withContext

/** A compressed picture and its pixel size. Compared by identity: the bytes are never copied. */
class JpegImage(val bytes: ByteArray, val width: Int, val height: Int) {
    val size: PixelSize get() = PixelSize(width, height)
}

/** One page of the scan. Every picture is JPEG; no decoded frame is kept here. */
class ScanPage(
    val id: Long,
    /** The camera frame (or imported picture) as captured, at working size. */
    val source: JpegImage,
    /** Corners in [source] pixels, clockwise from the top-left. */
    val quad: Quad,
    val look: ScanLook,
    val level: Int,
    /** The straightened, cleaned page: this is what goes into the PDF. */
    val result: JpegImage,
    /** False for an imported picture nobody cropped or filtered: [result] is [source]. */
    val processed: Boolean,
)

/** What the controller is doing; the text is what the screen shows. */
enum class ScanBusy(val text: String) {
    FINDING_EDGES("Finding the page edges"),
    CLEANING("Cleaning up the page"),
    IMPORTING("Adding pictures"),
    BUILDING_PDF("Building your PDF"),
}

/** Why the last action did not happen; [text] is shown as is. */
enum class ScanProblem(val text: String) {
    IMAGE_TOO_BIG("This picture is too large to scan. Try a smaller one."),
    UNSUPPORTED_IMAGE("This picture could not be read."),
    BAD_CROP("The corners do not outline a page. Drag them to the page edges."),
    TOO_LARGE("This is too large to process on this phone."),
    ENGINE_FAILED("That did not work. Your pages are kept. Try again."),
    NO_PAGES("Add at least one page first."),
}

/** Everything the scan screens show. */
data class ScanState(
    val pages: List<ScanPage> = emptyList(),
    val busy: ScanBusy? = null,
    /** 0..1 while a counted job runs (importing), else null. */
    val progress: Float? = null,
    val problem: ScanProblem? = null,
    /** The engine's own text for [problem], for a details line. */
    val problemDetail: String? = null,
    /** The next capture replaces this page instead of adding one. */
    val retakeIndex: Int? = null,
    /** Look and level given to the next captured page. */
    val defaultLook: ScanLook = ScanController.DEFAULT_LOOK,
    val defaultLevel: Int = ScanLevels().default,
    /** The range [ScanPage.level] and [defaultLevel] can take; the level slider uses it. */
    val levels: ScanLevels = ScanLevels(),
    /** Pictures skipped by the last [ScanController.addImages] because they could not be read. */
    val skippedImports: Int = 0,
)

/**
 * The cleanup strength range the engine accepts. The real engine takes 0..100 (it refuses 101);
 * the in-memory fake takes only 0..3, so tests pass [FAKE_ENGINE]. Pass nothing in the app.
 */
data class ScanLevels(val min: Int = 0, val max: Int = 100, val default: Int = 50) {
    init {
        require(min <= default && default <= max) { "default level outside the range" }
    }

    fun clamp(level: Int): Int = level.coerceIn(min, max)

    companion object {
        val FAKE_ENGINE = ScanLevels(0, 3, 1)
    }
}

/** A picture the importer already decoded and re-saved as JPEG (metadata stripped). */
class ImportedImage(val jpeg: ByteArray, val width: Int, val height: Int)

/**
 * Scan and images-to-PDF logic. No Android types.
 *
 * Memory: pages are JPEG bytes. A camera frame handed to [addCapture] is the only full-size frame
 * the controller touches in that call; re-processing decodes the source inside
 * [FrameCodec.withDecoded], one page at a time. Building the PDF decodes nothing: it sends the
 * stored JPEG results.
 *
 * Actions are suspend functions that do nothing (and return false/null) while another one runs. A
 * failure is reported through [ScanState.problem], never thrown. Cancelling the calling coroutine,
 * or calling [cancelCurrent], stops the action and leaves the pages as they were.
 */
class ScanController(
    private val engine: EngineApi,
    private val codec: FrameCodec,
    private val clock: ScanClock,
    private val dispatcher: CoroutineDispatcher = Dispatchers.Default,
    private val levels: ScanLevels = ScanLevels(),
) {
    private val _state = MutableStateFlow(ScanState(defaultLevel = levels.default, levels = levels))
    val state: StateFlow<ScanState> = _state.asStateFlow()

    private var nextId = 1L
    private var running: Job? = null

    // ---- capture and import ----

    /**
     * Adds a camera frame as a new page (or replaces the page set by [retake]): finds the page
     * edges, falls back to the whole frame when none are found or they are unusable, straightens
     * and cleans with the default look. The caller drops [frame] afterwards.
     */
    suspend fun addCapture(frame: ImageFrame): Boolean = act(ScanBusy.FINDING_EDGES) {
        if (Sizing.exceedsHardCap(frame.width, frame.height)) throw Failure(ScanProblem.IMAGE_TOO_BIG)
        val target = Sizing.fit(frame.width, frame.height, Sizing.SCAN_MAX_LONG_EDGE, Sizing.SCAN_MAX_PIXELS)
        val work = if (target == PixelSize(frame.width, frame.height)) frame else codec.downscale(frame, target)
        val detected = engine.detectDocumentCorners(work)
        val quad = QuadGeometry.orFullFrame(detected, work.width, work.height)
        busy(ScanBusy.CLEANING)
        val s = _state.value
        val cleaned = engine.rectifyAndClean(work, quad, s.defaultLook, s.defaultLevel)
        val source = JpegImage(codec.encode(work, SOURCE_QUALITY), work.width, work.height)
        val result = JpegImage(codec.encode(cleaned, RESULT_QUALITY), cleaned.width, cleaned.height)
        val page = ScanPage(nextId++, source, quad, s.defaultLook, s.defaultLevel, result, processed = true)
        _state.update {
            val at = it.retakeIndex
            val pages = if (at != null && at in it.pages.indices) it.pages.toMutableList().also { l -> l[at] = page } else it.pages + page
            it.copy(pages = pages, retakeIndex = null)
        }
    }

    /**
     * Adds a picked or shared picture as a page, no crop step. [image] must be JPEG (see
     * [ImageImporter], which always produces JPEG without EXIF or location data).
     */
    suspend fun addImage(image: ImportedImage): Boolean = act(ScanBusy.IMPORTING) {
        _state.update { it.copy(pages = it.pages + importedPage(image)) }
    }

    /**
     * Adds many pictures in order. [load] turns one input into an [ImportedImage], or null when it
     * cannot be read (that picture is skipped and counted in [ScanState.skippedImports]).
     */
    suspend fun <T> addImages(inputs: List<T>, load: suspend (T) -> ImportedImage?): Boolean = act(ScanBusy.IMPORTING) {
        _state.update { it.copy(progress = 0f, skippedImports = 0) }
        inputs.forEachIndexed { i, input ->
            val image = load(input)
            if (image == null || !isUsable(image)) {
                _state.update { it.copy(skippedImports = it.skippedImports + 1) }
            } else {
                _state.update { it.copy(pages = it.pages + importedPage(image)) }
            }
            _state.update { it.copy(progress = (i + 1f) / inputs.size) }
        }
    }

    private fun isUsable(image: ImportedImage): Boolean =
        image.width > 0 && image.height > 0 && !Sizing.exceedsHardCap(image.width, image.height) &&
            ImageKind.sniff(image.jpeg) == ImageKind.JPEG

    private fun importedPage(image: ImportedImage): ScanPage {
        if (!isUsable(image)) {
            throw Failure(
                if (Sizing.exceedsHardCap(image.width, image.height)) ScanProblem.IMAGE_TOO_BIG else ScanProblem.UNSUPPORTED_IMAGE,
            )
        }
        val jpeg = JpegImage(image.jpeg, image.width, image.height)
        val s = _state.value
        return ScanPage(nextId++, jpeg, QuadGeometry.fullFrame(image.width, image.height), s.defaultLook, s.defaultLevel, jpeg, processed = false)
    }

    /** The next [addCapture] replaces page [index]. */
    fun retake(index: Int) = _state.update {
        if (index in it.pages.indices) it.copy(retakeIndex = index, problem = null, problemDetail = null) else it
    }

    fun cancelRetake() = _state.update { it.copy(retakeIndex = null) }

    // ---- per-page edits ----

    /** Sets the crop of page [index]. The corners may come in any order; unusable ones are refused. */
    suspend fun setQuad(index: Int, quad: Quad): Boolean {
        val page = _state.value.pages.getOrNull(index) ?: return false
        val clean = QuadGeometry.sanitize(quad, page.source.width, page.source.height)
        if (clean == null) {
            _state.update { it.copy(problem = ScanProblem.BAD_CROP, problemDetail = null) }
            return false
        }
        return reprocess(page.id, clean, page.look, page.level)
    }

    suspend fun setLook(index: Int, look: ScanLook): Boolean {
        val page = _state.value.pages.getOrNull(index) ?: return false
        return reprocess(page.id, page.quad, look, page.level)
    }

    /** [level] is clamped to the range in [ScanState.levels]. */
    suspend fun setLevel(index: Int, level: Int): Boolean {
        val page = _state.value.pages.getOrNull(index) ?: return false
        return reprocess(page.id, page.quad, page.look, levels.clamp(level))
    }

    /** Look and level for the next captures. Existing pages are not touched. */
    fun setDefaultLook(look: ScanLook, level: Int = _state.value.defaultLevel) =
        _state.update { it.copy(defaultLook = look, defaultLevel = levels.clamp(level)) }

    /** Gives every page the same look and level, one page at a time. */
    suspend fun applyLookToAll(look: ScanLook, level: Int): Boolean = act(ScanBusy.CLEANING) {
        val lvl = levels.clamp(level)
        val ids = _state.value.pages.map { it.id }
        ids.forEachIndexed { i, id ->
            val page = _state.value.pages.firstOrNull { it.id == id } ?: return@forEachIndexed
            replace(process(page, page.quad, look, lvl))
            _state.update { it.copy(progress = (i + 1f) / ids.size) }
        }
    }

    private suspend fun reprocess(id: Long, quad: Quad, look: ScanLook, level: Int): Boolean = act(ScanBusy.CLEANING) {
        val page = _state.value.pages.firstOrNull { it.id == id } ?: return@act
        if (page.processed && page.quad == quad && page.look == look && page.level == level) return@act
        replace(process(page, quad, look, level))
    }

    /** Decodes the source (the only full-size frame alive), straightens it, stores the JPEG result. */
    private suspend fun process(page: ScanPage, quad: Quad, look: ScanLook, level: Int): ScanPage {
        val cleaned = codec.withDecoded(page.source.bytes, Sizing.DECODE_MAX_PIXELS) { frame ->
            val q = if (frame.width == page.source.width && frame.height == page.source.height) quad
            else QuadGeometry.scale(quad, page.source.width, page.source.height, frame.width, frame.height)
            engine.rectifyAndClean(frame, q, look, level)
        }
        val result = JpegImage(codec.encode(cleaned, RESULT_QUALITY), cleaned.width, cleaned.height)
        return ScanPage(page.id, page.source, quad, look, level, result, processed = true)
    }

    private fun replace(page: ScanPage) = _state.update { s ->
        s.copy(pages = s.pages.map { if (it.id == page.id) page else it })
    }

    // ---- list edits (instant, no engine) ----

    /** Moves page [from] so it ends at [to] (clamped). */
    fun reorder(from: Int, to: Int) = _state.update { s ->
        if (from !in s.pages.indices) return@update s
        val target = to.coerceIn(0, s.pages.lastIndex)
        if (target == from) return@update s
        val list = s.pages.toMutableList()
        list.add(target, list.removeAt(from))
        s.copy(pages = list, retakeIndex = null)
    }

    fun moveEarlier(index: Int) = reorder(index, index - 1)

    fun moveLater(index: Int) = reorder(index, index + 1)

    /** Removes page [index] and lets go of its pictures. */
    fun delete(index: Int) = _state.update { s ->
        if (index !in s.pages.indices) s else s.copy(pages = s.pages.filterIndexed { i, _ -> i != index }, retakeIndex = null)
    }

    /** Lets go of every page. */
    fun clear() = _state.update { ScanState(defaultLook = it.defaultLook, defaultLevel = it.defaultLevel, levels = levels) }

    fun dismissProblem() = _state.update { it.copy(problem = null, problemDetail = null) }

    // ---- output ----

    /** "Scan 2026-11-10.pdf" style name for the save sheet. */
    fun suggestedName(taken: Set<String> = emptySet()): String = ScanNaming.suggest(clock, taken)

    /** The pages as one PDF for the caller to save, or null (see [ScanState.problem]). */
    suspend fun buildPdf(): ByteArray? {
        var out: ByteArray? = null
        act(ScanBusy.BUILDING_PDF) {
            val pages = _state.value.pages
            if (pages.isEmpty()) throw Failure(ScanProblem.NO_PAGES)
            val inputs = pages.mapIndexed { i, p -> ImageInput("page-%03d.jpg".format(i + 1), p.result.bytes) }
            out = engine.imagesToPdf(inputs, Sizing.pdfDpi(pages.map { it.result.size }))
        }
        return out
    }

    /** Stops the running action, if any. The pages stay as they were. */
    fun cancelCurrent() {
        running?.cancel()
    }

    // ---- plumbing ----

    private class Failure(val problem: ScanProblem, val detail: String? = null) : Exception(problem.text)

    private fun busy(what: ScanBusy) = _state.update { it.copy(busy = what) }

    private suspend fun act(what: ScanBusy, block: suspend () -> Unit): Boolean {
        if (_state.value.busy != null) return false
        _state.update { it.copy(busy = what, progress = null, problem = null, problemDetail = null) }
        // The work runs in its own child job so cancelCurrent() stops it without cancelling the caller.
        val job = Job(currentCoroutineContext()[Job])
        running = job
        try {
            withContext(dispatcher + job) { block() }
            return true
        } catch (c: CancellationException) {
            if (currentCoroutineContext().isActive) return false // cancelCurrent(): not an error
            throw c
        } catch (f: Failure) {
            _state.update { it.copy(problem = f.problem, problemDetail = f.detail) }
            return false
        } catch (e: EngineError) {
            val problem = if (e is EngineError.TooLarge) ScanProblem.TOO_LARGE else ScanProblem.ENGINE_FAILED
            _state.update { it.copy(problem = problem, problemDetail = e.detail) }
            return false
        } finally {
            job.complete()
            if (running === job) running = null
            _state.update { it.copy(busy = null, progress = null) }
        }
    }

    companion object {
        val DEFAULT_LOOK = ScanLook.COLOUR

        /** JPEG quality of the stored camera source (re-processed later) and of the page in the PDF. */
        const val SOURCE_QUALITY = 90
        const val RESULT_QUALITY = 88
    }
}
