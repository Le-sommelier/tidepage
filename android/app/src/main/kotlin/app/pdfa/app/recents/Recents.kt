package app.pdfa.app.recents

import app.pdfa.app.intent.DocRef
import app.pdfa.app.intent.IntentRouter
import app.pdfa.storage.RecentsStore
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.net.URLDecoder

data class RecentItem(
    val doc: DocRef,
    val name: String,
    val lastOpenedMs: Long,
    /** False when the document could not be read last time. Shown disabled, with a remove action. */
    val available: Boolean = true,
)

/** Most recently used documents, newest first. The app uses [StoreRecentsRepository]. */
interface RecentsRepository {
    /** Newest first, one entry per document. */
    fun observe(): Flow<List<RecentItem>>

    /** Puts [doc] at the top, replacing an older entry for the same document, and marks it available. */
    suspend fun add(doc: DocRef, displayName: String)

    suspend fun remove(doc: DocRef)
    suspend fun clear()

    /** Keeps the entry (so the user sees why it is greyed out) and marks it unavailable. */
    suspend fun markUnavailable(doc: DocRef)
}

/** Process-lifetime recents, for previews and tests. */
class InMemoryRecentsRepository(
    private val clock: () -> Long = { System.currentTimeMillis() },
    private val capacity: Int = MAX_RECENTS,
) : RecentsRepository {
    init {
        require(capacity > 0) { "capacity must be positive" }
    }

    private val state = MutableStateFlow<List<RecentItem>>(emptyList())

    override fun observe(): Flow<List<RecentItem>> = state.asStateFlow()

    override suspend fun add(doc: DocRef, displayName: String) {
        val item = RecentItem(doc, displayName, clock(), available = true)
        state.update { list -> (listOf(item) + list.filter { it.doc != doc }).take(capacity) }
    }

    override suspend fun remove(doc: DocRef) {
        state.update { list -> list.filter { it.doc != doc } }
    }

    override suspend fun clear() {
        state.update { emptyList() }
    }

    override suspend fun markUnavailable(doc: DocRef) {
        state.update { list -> list.map { if (it.doc == doc) it.copy(available = false) else it } }
    }

    companion object {
        const val MAX_RECENTS = 20
    }
}

/**
 * A readable name for a document URI, from its last path segment, until :storage supplies the
 * provider's display name. Never throws; falls back to a fixed label.
 */
object DisplayNames {
    const val FALLBACK = "PDF document"
    private const val MAX_LENGTH = 120

    fun fromUri(uri: String): String {
        val path = uri.substringBefore('?')
        // Decode first: SAF ids are percent-encoded, so "primary%3ADocs%2Ffoo.pdf" holds a slash.
        val decoded = try {
            URLDecoder.decode(path.replace("+", "%2B"), "UTF-8")
        } catch (e: IllegalArgumentException) {
            path
        }
        val clean = decoded.substringAfterLast('/').filter { !it.isISOControl() }.trim().take(MAX_LENGTH)
        return clean.ifEmpty { FALLBACK }
    }
}

/**
 * Recents kept in a file by :storage's [RecentsStore]. The store does blocking file I/O, so it is
 * created and used on [io] only; the UI observes a snapshot that is republished after every change.
 * A failed write leaves the list as it is on disk (the store rolls back).
 */
class StoreRecentsRepository(
    open: () -> RecentsStore,
    scope: CoroutineScope,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) : RecentsRepository {
    private val store: RecentsStore by lazy(open)
    private val state = MutableStateFlow<List<RecentItem>>(emptyList())

    init {
        scope.launch(io) { publish() }
    }

    override fun observe(): Flow<List<RecentItem>> = state.asStateFlow()

    override suspend fun add(doc: DocRef, displayName: String) = change {
        store.recordOpened(doc.uri, displayName)
    }

    override suspend fun remove(doc: DocRef) = change {
        keyOf(doc)?.let { store.remove(it) }
    }

    override suspend fun clear() = change { store.clear() }

    override suspend fun markUnavailable(doc: DocRef) = change {
        keyOf(doc)?.let { store.markUnavailable(it) }
    }

    private suspend fun change(block: () -> Unit) = withContext(io) {
        block()
        publish()
    }

    private fun keyOf(doc: DocRef): String? = store.list().firstOrNull { it.uri == doc.uri }?.docKey

    private fun publish() {
        state.value = store.list().mapNotNull { e ->
            // A row whose uri no longer passes the intent rules is not offered.
            val ref = IntentRouter.acceptDocUri(e.uri) ?: return@mapNotNull null
            RecentItem(
                doc = ref,
                name = e.displayName?.takeIf { it.isNotBlank() } ?: DisplayNames.fromUri(e.uri),
                lastOpenedMs = e.lastOpenedAt,
                available = !e.unavailable,
            )
        }
    }
}
