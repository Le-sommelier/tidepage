package app.pdfa.viewer.extras

import app.pdfa.engine.DocHandle
import app.pdfa.engine.EngineApi
import app.pdfa.engine.EngineError
import app.pdfa.engine.OutlinePage
import app.pdfa.engine.PageLink
import app.pdfa.engine.Pt
import app.pdfa.engine.Rect
import app.pdfa.engine.TextSelection
import app.pdfa.engine.TileBitmap
import app.pdfa.engine.TileRequest
import app.pdfa.engine.fake.FakeEngine

/**
 * A [FakeEngine] with per-page scripting that the fake cannot do: errors thrown on chosen pages,
 * a real `Timeout` (the fake turns it into `EngineDied`), and recording of every call.
 */
class ScriptedEngine(val fake: FakeEngine = FakeEngine()) : EngineApi by fake {
    val searchedPages = mutableListOf<Int>()
    val searchQueries = mutableListOf<String>()
    val searchHandles = mutableListOf<Long>()

    /** Thrown on every search of the page. */
    val searchErrors = HashMap<Int, EngineError>()

    /** Thrown once, on the next search of the page. */
    val searchErrorsOnce = HashMap<Int, EngineError>()

    /** Runs before each search of a page; may suspend. */
    var searchPageHook: (suspend (Int) -> Unit)? = null

    val tileRequests = mutableListOf<TileRequest>()
    val tileHandles = mutableListOf<Long>()

    /** Thrown once, on the next render of the page. */
    val tileErrorsOnce = HashMap<Int, EngineError>()

    /** Runs before each render; may suspend. Also see [renderInFlight]. */
    var tileHook: (suspend (TileRequest) -> Unit)? = null
    var renderInFlight = 0
    var maxRenderInFlight = 0

    val outlineCalls = mutableListOf<Pair<Int, Int>>()
    var outlineOverride: ((Int, Int) -> OutlinePage)? = null

    val linkCalls = mutableListOf<Int>()
    var linksError: EngineError? = null

    var selectError: EngineError? = null
    var selectOverride: ((Int) -> TextSelection?)? = null
    val selectCalls = mutableListOf<Triple<Int, Pt, Pt>>()

    override suspend fun searchPage(
        handle: DocHandle,
        page: Int,
        query: String,
        caseSensitive: Boolean,
        wholeWord: Boolean,
    ): List<Rect> {
        searchedPages += page
        searchQueries += query
        searchHandles += handle.id
        searchPageHook?.invoke(page)
        searchErrorsOnce.remove(page)?.let { throw it }
        searchErrors[page]?.let { throw it }
        return fake.searchPage(handle, page, query, caseSensitive, wholeWord)
    }

    override suspend fun renderTile(handle: DocHandle, request: TileRequest): TileBitmap {
        tileRequests += request
        tileHandles += handle.id
        renderInFlight++
        maxRenderInFlight = maxOf(maxRenderInFlight, renderInFlight)
        try {
            tileHook?.invoke(request)
            tileErrorsOnce.remove(request.page)?.let { throw it }
            return fake.renderTile(handle, request)
        } finally {
            renderInFlight--
        }
    }

    override suspend fun outline(handle: DocHandle, offset: Int, limit: Int): OutlinePage {
        outlineCalls += offset to limit
        outlineOverride?.let { return it(offset, limit) }
        return fake.outline(handle, offset, limit)
    }

    override suspend fun links(handle: DocHandle, page: Int): List<PageLink> {
        linkCalls += page
        linksError?.let { throw it }
        return fake.links(handle, page)
    }

    override suspend fun selectText(handle: DocHandle, page: Int, from: Pt, to: Pt): TextSelection? {
        selectCalls += Triple(page, from, to)
        selectError?.let { throw it }
        selectOverride?.let { return it(page) }
        return fake.selectText(handle, page, from, to)
    }
}

/** Opens a fake document with one page per entry; the entry is that page's text. */
suspend fun ScriptedEngine.openText(texts: List<String>): DocHandle =
    open(FakeEngine.fakePdf(pageCount = texts.size, text = texts))

/** Opens a blank fake document of [pages] pages, each [width] by [height] points. */
suspend fun ScriptedEngine.openBlank(pages: Int, width: Float = 612f, height: Float = 792f): DocHandle =
    open(FakeEngine.fakePdf(pageCount = pages, width = width, height = height))
