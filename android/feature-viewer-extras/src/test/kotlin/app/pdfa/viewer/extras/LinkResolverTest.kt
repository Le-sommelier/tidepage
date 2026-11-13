package app.pdfa.viewer.extras

import app.pdfa.engine.EngineError
import app.pdfa.engine.LinkTarget
import kotlin.test.assertIs
import app.pdfa.engine.PageLink
import app.pdfa.engine.Pt
import app.pdfa.engine.Rect
import app.pdfa.engine.fake.FakeEngine
import app.pdfa.engine.fake.FakePage
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class LinkResolverTest {

    private val web = PageLink(Rect(0f, 0f, 100f, 20f), LinkTarget.Uri("https://example.org/report"))
    private val jump = PageLink(Rect(0f, 30f, 100f, 50f), LinkTarget.Page(1))
    private val hostile = PageLink(Rect(0f, 60f, 100f, 80f), LinkTarget.Uri("javascript:alert(1)"))

    /** Records every URI the app would open. Nothing in the resolver may call it. */
    private class RecordingOpener : UriOpener {
        val opened = mutableListOf<String>()
        override fun open(link: ParsedLink): Boolean {
            opened += link.uri
            return true
        }
    }

    private suspend fun TestScope.setup(cacheSize: Int = LinkResolver.CACHE_PAGES): Triple<LinkResolver, ScriptedEngine, RecordingOpener> {
        val engine = ScriptedEngine()
        val pdf = FakeEngine.fakePdf(
            pages = listOf(FakePage(612f, 792f, links = listOf(web, jump, hostile)), FakePage(612f, 792f)),
        )
        val h = engine.open(pdf)
        val opener = RecordingOpener()
        val resolver = LinkResolver(
            engine = engine,
            handle = h,
            pageCount = 2,
            dispatcher = StandardTestDispatcher(testScheduler),
            cacheSize = cacheSize,
        )
        return Triple(resolver, engine, opener)
    }

    @Test
    fun `hit testing finds the link under the point and misses empty space`() = runTest {
        val (r, _, _) = setup()

        assertEquals(LinkTarget.Uri("https://example.org/report"), r.hitTest(0, Pt(10f, 10f)))
        assertEquals(LinkTarget.Page(1), r.hitTest(0, Pt(10f, 40f)))
        assertEquals(null, r.hitTest(0, Pt(500f, 500f)))
    }

    @Test
    fun `the edges of a link rect count as hits`() = runTest {
        val (r, _, _) = setup()

        assertEquals(LinkTarget.Uri("https://example.org/report"), r.hitTest(0, Pt(0f, 0f)))
        assertEquals(LinkTarget.Uri("https://example.org/report"), r.hitTest(0, Pt(100f, 20f)))
    }

    @Test
    fun `a tap on a web link asks for confirmation and opens nothing`() = runTest {
        val (r, _, opener) = setup()

        val action = r.actionAt(0, Pt(10f, 10f))

        assertEquals("https://example.org/report", assertIs<LinkAction.ConfirmExternalLink>(action).link.uri)
        assertTrue(opener.opened.isEmpty(), "a tap must never open a link by itself")
    }

    @Test
    fun `a Uri target never reaches an opener on its own`() = runTest {
        val (r, _, opener) = setup()

        repeat(3) { r.actionAt(0, Pt(10f, 10f)) }
        r.hitTest(0, Pt(10f, 10f))
        r.links(0)

        assertTrue(opener.opened.isEmpty())
    }

    @Test
    fun `a confirmed link opens exactly once`() = runTest {
        val (r, _, opener) = setup()
        val action = assertIs<LinkAction.ConfirmExternalLink>(r.actionAt(0, Pt(10f, 10f)))

        val ok = ConfirmedLinkOpener(opener).openConfirmed(action)

        assertTrue(ok)
        assertEquals(listOf("https://example.org/report"), opener.opened)
    }

    @Test
    fun `the confirmed opener refuses a hostile URI even when handed a confirmation for it`() = runTest {
        val (_, _, opener) = setup()

        val fake = ParsedLink("javascript", null, null, "javascript:alert(1)", "javascript:alert(1)")
        val ok = ConfirmedLinkOpener(opener).openConfirmed(LinkAction.ConfirmExternalLink(fake))

        assertEquals(false, ok)
        assertTrue(opener.opened.isEmpty())
    }

    @Test
    fun `a javascript link is rejected with a reason`() = runTest {
        val (r, _, _) = setup()

        val action = r.actionAt(0, Pt(10f, 70f))

        assertIs<LinkAction.Rejected>(action)
        assertEquals(LinkReasons.SCHEME_NOT_ALLOWED, action.reason)
    }

    @Test
    fun `a page link jumps to its page`() = runTest {
        val (r, _, _) = setup()

        assertEquals(LinkAction.JumpTo(1), r.actionAt(0, Pt(10f, 40f)))
    }

    @Test
    fun `a tap with no link returns NoLink`() = runTest {
        val (r, _, _) = setup()

        assertEquals(LinkAction.NoLink, r.actionAt(0, Pt(500f, 500f)))
    }

    @Test
    fun `links are read once per page and then cached`() = runTest {
        val (r, engine, _) = setup()

        r.links(0)
        r.links(0)
        r.hitTest(0, Pt(10f, 10f))

        assertEquals(listOf(0), engine.linkCalls)
    }

    @Test
    fun `invalidate makes the next read go to the engine again`() = runTest {
        val (r, engine, _) = setup()
        r.links(0)

        r.invalidate()
        r.links(0)

        assertEquals(listOf(0, 0), engine.linkCalls)
    }

    @Test
    fun `the cache forgets the least recently used page when it is full`() = runTest {
        val (r, engine, _) = setup(cacheSize = 1)

        r.links(0)
        r.links(1)
        r.links(0)

        assertEquals(listOf(0, 1, 0), engine.linkCalls)
    }

    @Test
    fun `an engine error while reading links becomes a rejection, not an exception`() = runTest {
        val (r, engine, _) = setup()
        engine.linksError = EngineError.Io("read")

        val action = r.actionAt(0, Pt(10f, 10f))

        assertEquals(LinkAction.Rejected("Links could not be read."), action)
    }

    @Test
    fun `a page target past the end of the document is rejected`() {
        assertIs<LinkAction.Rejected>(LinkResolver.actionFor(LinkTarget.Page(9), pageCount = 2))
        assertIs<LinkAction.Rejected>(LinkResolver.actionFor(LinkTarget.Page(-1), pageCount = 2))
        assertEquals(LinkAction.JumpTo(1), LinkResolver.actionFor(LinkTarget.Page(1), pageCount = 2))
    }

    @Test
    fun `other link kinds are rejected`() {
        val action = LinkResolver.actionFor(LinkTarget.Other("named destination"), pageCount = 2)

        assertIs<LinkAction.Rejected>(action)
    }

    @Test
    fun `a Uri target with a hostile scheme is rejected before any confirmation`() {
        assertIs<LinkAction.Rejected>(LinkResolver.actionFor(LinkTarget.Uri("file:///data/data/app/secret"), 2))
        assertIs<LinkAction.Rejected>(LinkResolver.actionFor(LinkTarget.Uri("content://com.android.providers/x"), 2))
        assertIs<LinkAction.Rejected>(LinkResolver.actionFor(LinkTarget.Uri("intent://scan/#Intent;end"), 2))
        assertIs<LinkAction.ConfirmExternalLink>(LinkResolver.actionFor(LinkTarget.Uri("mailto:a@example.org"), 2))
    }

    @Test
    fun `after an engine restart the links are read through the new handle`() = runTest {
        val (r, engine, _) = setup()
        r.links(0)
        val fresh = engine.open(FakeEngine.fakePdf(pages = listOf(FakePage(612f, 792f, links = listOf(web)))))

        r.onEngineRestarted(fresh)
        val links = r.links(0)

        assertEquals(1, links.size)
        assertEquals(listOf(0, 0), engine.linkCalls)
    }

    @Test
    fun `the hit test picks the first link when rects overlap`() {
        val target = LinkResolver.hitTestLinks(
            listOf(
                PageLink(Rect(0f, 0f, 50f, 50f), LinkTarget.Page(1)),
                PageLink(Rect(0f, 0f, 50f, 50f), LinkTarget.Uri("https://example.org")),
            ),
            Pt(10f, 10f),
        )

        assertEquals(LinkTarget.Page(1), target)
    }
}
