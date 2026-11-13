package app.pdfa.viewer

import app.pdfa.engine.EngineError
import app.pdfa.engine.TileBitmap
import app.pdfa.engine.TileRequest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/**
 * Runs tile renders for the latest plan.
 *  - Duplicate requests are coalesced (one render per key).
 *  - [submit] replaces the wanted set: queued requests that are no longer wanted are DROPPED before they
 *    start, and running renders for tiles that left the plan are CANCELLED (their result is never cached).
 *  - At most [maxInFlight] renders run at once.
 *  - A failing tile retries with exponential backoff, at most [maxAttempts] attempts, then lands in
 *    [failedKeys] (placeholder + error mark) and is not retried until it leaves and re-enters the plan.
 *  - A tile whose render kills the engine (Timeout / EngineDied) gets ONE more try; the second time it is
 *    POISONED: it stays in [failedKeys] and [poisonedKeys], and is only retried by [retry] (a manual action).
 *    [onEngineRestarted] never revives a poisoned tile, so a killer tile cannot cause an endless restart loop.
 */
class TileScheduler(
    private val scope: CoroutineScope,
    private val isCached: (TileKey) -> Boolean,
    private val render: suspend (TileRequest) -> TileBitmap,
    private val onReady: (TileKey, TileBitmap) -> Unit,
    private val maxInFlight: Int = 2,
    private val maxAttempts: Int = 3,
    private val backoffBaseMs: Long = 250L,
    private val maxCrashesPerTile: Int = 2,
) {
    private val lock = Any()
    private var wanted = LinkedHashMap<TileKey, PlannedTile>()
    private val pending = LinkedHashMap<TileKey, PlannedTile>()
    private val inFlight = HashMap<TileKey, Job>()
    private val attempts = HashMap<TileKey, Int>()
    private val backoff = HashMap<TileKey, Job>()
    private val failed = LinkedHashSet<TileKey>()
    private val lastError = HashMap<TileKey, Throwable>()
    private val crashes = HashMap<TileKey, Int>()
    private val poisoned = LinkedHashSet<TileKey>()
    private val failedState = MutableStateFlow<Set<TileKey>>(emptySet())
    private val poisonedState = MutableStateFlow<Set<TileKey>>(emptySet())

    val failedKeys: StateFlow<Set<TileKey>> get() = failedState
    val poisonedKeys: StateFlow<Set<TileKey>> get() = poisonedState

    var droppedCount: Int = 0
        private set
    var cancelledCount: Int = 0
        private set
    var startedCount: Int = 0
        private set
    val inFlightCount: Int get() = synchronized(lock) { inFlight.size }
    val pendingCount: Int get() = synchronized(lock) { pending.size }
    val backoffCount: Int get() = synchronized(lock) { backoff.size }

    fun submit(plan: List<PlannedTile>) {
        synchronized(lock) {
            val next = LinkedHashMap<TileKey, PlannedTile>()
            for (t in plan) next.putIfAbsent(t.key, t)
            droppedCount += pending.keys.count { it !in next }
            // Renders for tiles that left the plan are cancelled; their coroutine is the one awaiting the engine.
            for ((k, job) in inFlight.entries.toList()) {
                if (k !in next) {
                    inFlight.remove(k)
                    attempts.remove(k)
                    job.cancel()
                    cancelledCount++
                }
            }
            backoff.keys.filter { it !in next }.forEach { backoff.remove(it)?.cancel(); attempts.remove(it) }
            if (failed.removeAll { it !in next && it !in poisoned }) {
                lastError.keys.removeAll { it !in next && it !in poisoned }
                attempts.keys.removeAll { it !in next && it !in inFlight }
                failedState.value = failed.toSet()
            }
            wanted = next
            rebuildPending()
            pump()
        }
    }

    /** Gives failed tiles (except poisoned ones) a fresh start. */
    fun clearFailures() {
        synchronized(lock) {
            val revive = failed.filter { it !in poisoned }
            revive.forEach { failed.remove(it); attempts.remove(it); lastError.remove(it) }
            publishFailed()
            rebuildPending()
            pump()
        }
    }

    /**
     * The engine restarted: tiles that failed because of a Timeout or EngineDied get another try, unless they
     * already hit the crash cap (then they are poisoned). Tiles that failed for other reasons stay failed.
     */
    fun onEngineRestarted() {
        synchronized(lock) {
            val revive = failed.filter { k ->
                k !in poisoned && (lastError[k].let { it is EngineError.Timeout || it is EngineError.EngineDied }) &&
                    (crashes[k] ?: 0) < maxCrashesPerTile
            }
            revive.forEach { failed.remove(it); attempts.remove(it); lastError.remove(it) }
            if (revive.isNotEmpty()) {
                publishFailed()
                rebuildPending()
                pump()
            }
        }
    }

    /** Manual retry of one tile (the user pressed the retry button): clears its failure, poison and crash count. */
    fun retry(key: TileKey) {
        synchronized(lock) {
            failed.remove(key); poisoned.remove(key); crashes.remove(key); attempts.remove(key); lastError.remove(key)
            publishFailed()
            rebuildPending()
            pump()
        }
    }

    /** Cancels running renders and forgets everything queued. */
    fun cancelAll() {
        synchronized(lock) {
            inFlight.values.forEach { it.cancel() }
            backoff.values.forEach { it.cancel() }
            inFlight.clear(); backoff.clear(); pending.clear(); attempts.clear(); wanted = LinkedHashMap()
        }
    }

    private fun publishFailed() {
        failedState.value = failed.toSet()
        poisonedState.value = poisoned.toSet()
    }

    private fun eligible(key: TileKey) =
        key !in inFlight && key !in backoff && key !in failed && !isCached(key)

    private fun rebuildPending() {
        pending.clear()
        for ((k, t) in wanted) if (eligible(k)) pending[k] = t
    }

    private fun pump() {
        while (inFlight.size < maxInFlight && pending.isNotEmpty()) {
            val it = pending.entries.iterator()
            val e = it.next()
            it.remove()
            if (isCached(e.key)) continue
            start(e.value)
        }
    }

    private fun start(tile: PlannedTile) {
        val key = tile.key
        attempts[key] = (attempts[key] ?: 0) + 1
        startedCount++
        val job = scope.launch(start = CoroutineStart.LAZY) { run(tile) }
        inFlight[key] = job
        job.start()
    }

    private suspend fun run(tile: PlannedTile) {
        val key = tile.key
        val self = currentCoroutineContext()[Job]
        var error: Throwable? = null
        var bitmap: TileBitmap? = null
        try {
            bitmap = render(tile.request)
        } catch (c: CancellationException) {
            synchronized(lock) { if (inFlight[key] === self) inFlight.remove(key) }
            throw c
        } catch (t: Throwable) {
            error = t
        }
        val deliver = synchronized(lock) {
            val current = inFlight[key] === self
            if (!current) {
                false // cancelled by a newer plan while finishing: drop the result
            } else if (error == null) {
                key in wanted
            } else {
                inFlight.remove(key)
                handleFailure(key, error)
                pump()
                false
            }
        }
        if (error != null) return
        if (deliver && bitmap != null) onReady(key, bitmap)
        synchronized(lock) {
            if (inFlight[key] === self) {
                inFlight.remove(key)
                attempts.remove(key)
            }
            pump()
        }
    }

    private fun handleFailure(key: TileKey, error: Throwable) {
        lastError[key] = error
        val n = attempts[key] ?: 1
        if (error is EngineError.EngineDied || error is EngineError.Timeout) {
            val c = (crashes[key] ?: 0) + 1
            crashes[key] = c
            if (c >= maxCrashesPerTile) {
                failed += key
                poisoned += key
                attempts.remove(key)
                publishFailed()
                return
            }
        }
        val retryable = error is EngineError.Render || error is EngineError.Timeout ||
            error is EngineError.Panic || error is EngineError.Io || error is EngineError.EngineDied
        if (!retryable || n >= maxAttempts) {
            failed += key
            attempts.remove(key)
            publishFailed()
            return
        }
        if (key !in wanted) {
            attempts.remove(key)
            return
        }
        val wait = backoffBaseMs shl (n - 1)
        backoff[key] = scope.launch {
            delay(wait)
            synchronized(lock) {
                backoff.remove(key)
                rebuildPending()
                pump()
            }
        }
    }
}
