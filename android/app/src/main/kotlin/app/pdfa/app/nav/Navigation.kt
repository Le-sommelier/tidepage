package app.pdfa.app.nav

import app.pdfa.app.intent.DocRef
import app.pdfa.app.intent.IntentRouter

sealed interface Route {
    data object Home : Route
    /** [restoreDocId]: open the recovered autosave of that working copy instead of importing the file again. */
    data class Viewer(val doc: DocRef, val restoreDocId: String? = null) : Route
    data object Settings : Route
    data object Licences : Route

    /** Placeholder for tools whose packages have not landed yet. [kind] is `images` for now. */
    data class Tools(val kind: String) : Route
}

/**
 * Back stack. The bottom entry is always [Route.Home]. Pure value: no Compose, no Android.
 * The stack never holds bytes or passwords; only route keys and document URIs.
 */
data class NavState(val stack: List<Route> = listOf(Route.Home)) {
    init {
        require(stack.isNotEmpty() && stack.first() == Route.Home) { "the root must be Home" }
    }

    val current: Route get() = stack.last()
}

object Navigator {
    private const val MAX_DEPTH = 4
    private const val DOC_ID_LENGTH = 36
    private val DOC_ID = Regex("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")

    /**
     * Opens [route] from an intent or the picker. Replaces whatever was open, so a second intent
     * while the Viewer is showing does not stack a second Viewer. Home stays the Back destination.
     */
    fun open(route: Route): NavState =
        if (route == Route.Home) NavState() else NavState(listOf(Route.Home, route))

    /** Pushes a screen above the current one (Settings, Licences). Pushing the current screen is a no-op. */
    fun push(state: NavState, route: Route): NavState {
        if (route == Route.Home) return NavState()
        if (state.current == route) return state
        if (state.stack.size >= MAX_DEPTH) return state
        return NavState(state.stack + route)
    }

    /** Pops one screen. Returns null at Home: the caller leaves the app. */
    fun back(state: NavState): NavState? =
        if (state.stack.size <= 1) null else NavState(state.stack.dropLast(1))

    /** Encodes the stack for saved state. Document keys are the only payload. */
    fun encode(state: NavState): List<String> = state.stack.map { route ->
        when (route) {
            Route.Home -> "home"
            is Route.Viewer ->
                if (route.restoreDocId != null) "restore:" + route.restoreDocId + ":" + route.doc.uri
                else "viewer:" + route.doc.uri
            Route.Settings -> "settings"
            Route.Licences -> "licences"
            is Route.Tools -> "tools:" + route.kind
        }
    }

    /**
     * Restores a stack. Anything unexpected (missing, unknown key, invalid URI, wrong root, too
     * deep) gives Home alone: a corrupt saved state must never crash the app or open a stranger file.
     */
    fun decode(keys: List<String>?): NavState {
        if (keys.isNullOrEmpty() || keys.size > MAX_DEPTH || keys.first() != "home") return NavState()
        val routes = keys.map { key -> decodeRoute(key) ?: return NavState() }
        return if (routes.first() == Route.Home) NavState(routes) else NavState()
    }

    private fun decodeRoute(key: String): Route? = when {
        key == "home" -> Route.Home
        key == "settings" -> Route.Settings
        key == "licences" -> Route.Licences
        key.startsWith("viewer:") ->
            IntentRouter.acceptDocUri(key.removePrefix("viewer:"))?.let { Route.Viewer(it) }
        key.startsWith("restore:") -> {
            val rest = key.removePrefix("restore:")
            val id = rest.take(DOC_ID_LENGTH)
            if (!DOC_ID.matches(id) || rest.getOrNull(DOC_ID_LENGTH) != ':') null
            else IntentRouter.acceptDocUri(rest.drop(DOC_ID_LENGTH + 1))?.let { Route.Viewer(it, id) }
        }
        key.startsWith("tools:") -> key.removePrefix("tools:").takeIf { it.isNotEmpty() && it.length <= 32 }
            ?.let { Route.Tools(it) }
        else -> null
    }
}
