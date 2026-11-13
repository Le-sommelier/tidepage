package app.pdfa.app.nav

import app.pdfa.app.intent.DocRef
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class NavigationTest {

    private val docA = DocRef("content://com.example.provider/document/a")
    private val docB = DocRef("content://com.example.provider/document/b")

    @Test
    fun startsAtHomeAndHomeIsTheRoot() {
        val state = NavState()
        assertEquals(Route.Home, state.current)
        assertEquals(listOf(Route.Home), state.stack)
    }

    @Test
    fun backFromHomeLeavesTheApp() {
        assertNull(Navigator.back(NavState()))
    }

    @Test
    fun openingADocumentGivesHomeThenViewer() {
        val state = Navigator.open(Route.Viewer(docA))
        assertEquals(listOf(Route.Home, Route.Viewer(docA)), state.stack)
    }

    @Test
    fun backFromViewerReturnsHome() {
        val viewer = Navigator.open(Route.Viewer(docA))
        assertEquals(NavState(), Navigator.back(viewer))
    }

    @Test
    fun secondIntentWhileViewingReplacesTheViewer() {
        val first = Navigator.open(Route.Viewer(docA))
        val second = Navigator.open(Route.Viewer(docB))
        assertEquals(listOf(Route.Home, Route.Viewer(docB)), second.stack)
        assertEquals(Route.Home, Navigator.back(second)?.current)
        assertEquals(Route.Viewer(docA), first.current)
    }

    @Test
    fun pushSettingsThenLicencesThenBackWalksDown() {
        val licences = Navigator.push(Navigator.push(NavState(), Route.Settings), Route.Licences)
        assertEquals(listOf(Route.Home, Route.Settings, Route.Licences), licences.stack)

        val settings = Navigator.back(licences)
        assertEquals(Route.Settings, settings?.current)
        assertEquals(Route.Home, Navigator.back(settings!!)?.current)
        assertNull(Navigator.back(Navigator.back(settings)!!))
    }

    @Test
    fun pushingTheCurrentScreenIsANoOp() {
        val once = Navigator.push(NavState(), Route.Settings)
        assertEquals(once, Navigator.push(once, Route.Settings))
    }

    @Test
    fun pushingHomeResetsToRoot() {
        val deep = Navigator.push(Navigator.push(NavState(), Route.Settings), Route.Licences)
        assertEquals(NavState(), Navigator.push(deep, Route.Home))
    }

    @Test
    fun stackDepthIsCapped() {
        var state = NavState()
        state = Navigator.push(state, Route.Settings)
        state = Navigator.push(state, Route.Licences)
        state = Navigator.push(state, Route.Tools("images"))
        assertEquals(4, state.stack.size)
        assertEquals(state, Navigator.push(state, Route.Viewer(docA)))
    }

    @Test
    fun rootMustBeHome() {
        assertFailsWith<IllegalArgumentException> { NavState(listOf(Route.Settings)) }
        assertFailsWith<IllegalArgumentException> { NavState(emptyList()) }
    }

    @Test
    fun encodeDecodeRoundTripKeepsEveryRoute() {
        val state = NavState(
            listOf(
                Route.Home,
                Route.Settings,
                Route.Licences,
                Route.Tools("images"),
            ),
        )
        assertEquals(state, Navigator.decode(Navigator.encode(state)))

        val viewer = Navigator.open(Route.Viewer(DocRef("content://com.example/document/primary%3ADocs%2Fa.pdf")))
        assertEquals(viewer, Navigator.decode(Navigator.encode(viewer)))
    }

    @Test
    fun savedStateHoldsOnlyRouteKeysAndUris() {
        val keys = Navigator.encode(Navigator.open(Route.Viewer(docA)))
        assertEquals(listOf("home", "viewer:content://com.example.provider/document/a"), keys)
    }

    @Test
    fun corruptSavedStateRestoresToHome() {
        val bad = listOf(
            null,
            emptyList(),
            listOf("viewer:content://x/y"), // does not start with home
            listOf("home", "nonsense"),
            listOf("home", "viewer:file:///sdcard/a.pdf"), // not a content URI
            listOf("home", "viewer:"),
            listOf("home", "tools:"),
            listOf("home", "viewer:content://com.example/document/1 with space"),
            listOf("home", "settings", "licences", "tools:images", "settings"), // too deep
            listOf("home", "Settings"),
        )
        bad.forEach { assertEquals(NavState(), Navigator.decode(it), it.toString()) }
    }
}
