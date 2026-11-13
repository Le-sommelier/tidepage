package app.pdfa.viewer.extras

import app.pdfa.engine.DocHandle
import app.pdfa.engine.EngineApi
import app.pdfa.engine.EngineError
import app.pdfa.engine.LinkTarget
import app.pdfa.engine.PageLink
import app.pdfa.engine.Pt
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * What a tap on a link means. Nothing here opens anything: a [ConfirmExternalLink] must be shown
 * to the user, and only a confirmed one reaches a [UriOpener] (see [ConfirmedLinkOpener]).
 */
sealed interface LinkAction {
    /** Scroll to a page (0-based). */
    data class JumpTo(val page: Int) : LinkAction

    /** Ask the user before opening [uri]. Never opened automatically. */
    data class ConfirmExternalLink(val link: ParsedLink) : LinkAction

    data class Rejected(val reason: String) : LinkAction

    /** The tap missed every link. */
    object NoLink : LinkAction
}

/** Opens an accepted link in another app. Returns false when nothing could be opened. */
fun interface UriOpener {
    fun open(link: ParsedLink): Boolean
}

/**
 * Opens a link only after the user confirmed it, and checks it again on the way out.
 * Returns false when the URI is rejected or the confirmation was for something else.
 */
class ConfirmedLinkOpener(private val opener: UriOpener) {
    fun openConfirmed(action: LinkAction.ConfirmExternalLink): Boolean {
        val check = ExternalLinkPolicy.check(action.link.uri) as? LinkCheck.Allowed ?: return false
        return opener.open(check.link)
    }
}

/**
 * Per-page link lookup and hit testing. Links are cached per page (bounded by [cacheSize] pages,
 * least recently used evicted first). Points and rects are in page view space.
 */
class LinkResolver(
    private val engine: EngineApi,
    handle: DocHandle,
    private val pageCount: Int,
    private val dispatcher: CoroutineDispatcher,
    private val cacheSize: Int = CACHE_PAGES,
) {
    companion object {
        const val CACHE_PAGES: Int = 64

        /** The first link whose rect contains [point] (edges included), or null. */
        fun hitTestLinks(links: List<PageLink>, point: Pt): LinkTarget? =
            links.firstOrNull { l ->
                point.x >= l.rect.left && point.x <= l.rect.right &&
                    point.y >= l.rect.top && point.y <= l.rect.bottom
            }?.target

        /** Turns an engine target into what the UI should do. Pure. */
        fun actionFor(target: LinkTarget, pageCount: Int): LinkAction = when (target) {
            is LinkTarget.Page ->
                if (target.page in 0 until pageCount) LinkAction.JumpTo(target.page)
                else LinkAction.Rejected("The link points to a page that does not exist.")
            is LinkTarget.Uri -> when (val check = ExternalLinkPolicy.check(target.uri)) {
                is LinkCheck.Allowed -> LinkAction.ConfirmExternalLink(check.link)
                is LinkCheck.Rejected -> LinkAction.Rejected(check.reason)
            }
            is LinkTarget.Other -> LinkAction.Rejected("This kind of link is not supported.")
        }
    }

    private var handle: DocHandle = handle
    private val mutex = Mutex()
    private val cache = LinkedHashMap<Int, List<PageLink>>(16, 0.75f, true)

    /** Links of [page], cached. Throws the engine's error; the cache is not filled on failure. */
    suspend fun links(page: Int): List<PageLink> {
        mutex.withLock { cache[page] }?.let { return it }
        val fresh = withContext(dispatcher) { engine.links(handle, page) }
        mutex.withLock {
            cache[page] = fresh
            while (cache.size > cacheSize) cache.remove(cache.keys.first())
        }
        return fresh
    }

    /** The target under [point] on [page], or null when there is no link there. */
    suspend fun hitTest(page: Int, point: Pt): LinkTarget? = hitTestLinks(links(page), point)

    /** What a tap at [point] on [page] should do. Engine failures come back as [LinkAction.Rejected]. */
    suspend fun actionAt(page: Int, point: Pt): LinkAction {
        val target = try {
            hitTest(page, point)
        } catch (e: EngineError) {
            return LinkAction.Rejected("Links could not be read.")
        }
        if (target == null) return LinkAction.NoLink
        return actionFor(target, pageCount)
    }

    /** Forgets cached links (after an edit that may move them). */
    suspend fun invalidate() {
        mutex.withLock { cache.clear() }
    }

    /** The engine restarted: adopts the new handle and forgets the cache. */
    suspend fun onEngineRestarted(newHandle: DocHandle) {
        handle = newHandle
        invalidate()
    }
}
