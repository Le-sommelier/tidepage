package app.pdfa.engine.impl

import app.pdfa.engine.EngineApi
import app.pdfa.engine.EngineError
import app.pdfa.engine.EngineEvent
import app.pdfa.engine.fake.FakeEngine
import app.pdfa.engine.protocol.EngineServer
import app.pdfa.engine.protocol.EngineWire
import app.pdfa.engine.protocol.HeapTileBuffer
import app.pdfa.engine.protocol.Op
import app.pdfa.engine.protocol.Request
import app.pdfa.engine.protocol.Response
import app.pdfa.engine.protocol.ResponseStatus
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.currentTime

/** One-shot script entry: applies to the first request whose op matches (any op when null). */
class OneShot(
    val op: Op?,
    val delayMs: Long = 0,
    val die: Boolean = false,
    val tamper: ((TransportResponse) -> TransportResponse)? = null,
    val garbage: Boolean = false,
    /** The request is already "past" the point where a kill could stop it. */
    val immune: Boolean = false,
)

/**
 * A transport whose "process" is an in-JVM [EngineServer] over a fresh [EngineApi] per generation.
 * Every request and response goes through the real wire codec in both directions. Scriptable:
 * delays, hangs, deaths, tampered or garbage responses, failing restarts.
 */
class LoopbackTransport(
    private val scope: CoroutineScope,
    private val newEngine: (generation: Long) -> EngineApi = { FakeEngine(clock = { 1_000L }) },
) : EngineTransport {
    override var generation = 1L
        private set
    var api: EngineApi = newEngine(1)
        private set
    /** When true every restarted "process" reuses the previous boot id (a zombie / failed restart). */
    var reuseBootIdCount = 0
    var rejectedStale = 0
    private var server = EngineServer(api)
    private var dead = CompletableDeferred<Unit>()

    val log = mutableListOf<Request>()
    val deadlines = mutableListOf<Pair<Op, Int>>()
    val engines = mutableListOf<EngineApi>(api)
    val kills = mutableListOf<String>()
    var restartCount = 0
    val cancelled = mutableListOf<Long>()
    val oneShots = mutableListOf<OneShot>()
    var failRestart = false
    var restartDelayMs = 0L
    var inFlight = 0
    var maxInFlight = 0
    val startTimes = mutableListOf<Pair<Op, Long>>()
    val endTimes = mutableListOf<Pair<Op, Long>>()
    var clock: () -> Long = { 0 }

    private val deathFlow = MutableSharedFlow<DeathEvent>(extraBufferCapacity = 16)
    private val progressFlow = MutableSharedFlow<EngineEvent.Progress>(extraBufferCapacity = 64)
    override val deaths: SharedFlow<DeathEvent> = deathFlow.asSharedFlow()
    override val progress: SharedFlow<EngineEvent.Progress> = progressFlow.asSharedFlow()

    init { watchProgress() }

    private fun watchProgress() {
        val s = server
        scope.launch(start = CoroutineStart.UNDISPATCHED) { s.progress.collect { progressFlow.emit(it) } }
    }

    fun hangNext(op: Op?, ms: Long = 10_000_000L) { oneShots += OneShot(op, delayMs = ms) }
    fun delayNext(op: Op?, ms: Long) { oneShots += OneShot(op, delayMs = ms) }
    fun dieNext(op: Op?) { oneShots += OneShot(op, die = true) }
    fun tamperNext(op: Op?, f: (TransportResponse) -> TransportResponse) { oneShots += OneShot(op, tamper = f) }
    fun garbageNext(op: Op?) { oneShots += OneShot(op, garbage = true) }
    fun immuneDelayNext(op: Op?, ms: Long) { oneShots += OneShot(op, delayMs = ms, immune = true) }

    suspend fun emitDeathOf(gen: Long, reason: String = "late") { deathFlow.emit(DeathEvent(gen, reason)) }

    /** The process dies by itself while idle. */
    suspend fun dieNow(reason: String = "oom") {
        val g = generation
        dead.complete(Unit)
        deathFlow.emit(DeathEvent(g, reason))
    }

    private fun takeShot(op: Op): OneShot? {
        val s = oneShots.firstOrNull { it.op == null || it.op == op } ?: return null
        oneShots.remove(s)
        return s
    }

    override suspend fun call(request: Request, deadlineMs: Long, expectedGeneration: Long): TransportResponse {
        if (expectedGeneration != -1L && expectedGeneration != generation) {
            rejectedStale++
            throw EngineError.EngineDied("engine generation changed")
        }
        log += request
        deadlines += request.op to request.deadlineMs
        val deadSignal = dead
        if (deadSignal.isCompleted) throw EngineError.EngineDied("process is dead")
        val shot = takeShot(request.op)
        startTimes += request.op to clock()
        inFlight++
        maxInFlight = maxOf(maxInFlight, inFlight)
        try {
            return coroutineScope {
                val watcher = launch { deadSignal.await(); throw EngineError.EngineDied("process killed") }
                if (shot?.immune == true) watcher.cancel()
                val work = async {
                    if (shot != null && shot.delayMs > 0) delay(shot.delayMs)
                    if (shot?.die == true) {
                        val g = generation
                        dead.complete(Unit)
                        deathFlow.emit(DeathEvent(g, "scripted death"))
                        awaitCancellation()
                    }
                    if (shot?.garbage == true) {
                        return@async TransportResponse(Response.ok(request.requestId, byteArrayOf(1, 2, 3)))
                    }
                    val req = Request.decode(request.encode())
                    val reply = server.handle(req)
                    val resp = Response.decode(reply.response.encode())
                    val tile = reply.tilePixels?.let { px ->
                        HeapTileBuffer(EngineWire.parseRenderTileResult(resp.payload).transferId, px)
                    }
                    var out = TransportResponse(resp, tile)
                    if (shot?.tamper != null) out = shot.tamper.invoke(out)
                    out
                }
                val result = work.await()
                watcher.cancel()
                result
            }
        } finally {
            inFlight--
            endTimes += request.op to clock()
        }
    }

    override fun cancel(requestId: Long) { cancelled += requestId }

    override fun kill(reason: String) {
        kills += reason
        dead.complete(Unit)
    }

    override suspend fun restart() {
        restartCount++
        if (restartDelayMs > 0) delay(restartDelayMs)
        if (failRestart) throw EngineError.EngineDied("cannot start")
        generation++
        api = newEngine(generation)
        engines += api
        server = if (reuseBootIdCount > 0) {
            reuseBootIdCount--
            EngineServer(api, bootId = server.bootId)
        } else {
            EngineServer(api)
        }
        dead = CompletableDeferred()
        watchProgress()
    }
}

class MapRecovery : RecoveryProvider {
    val bytes = HashMap<String, ByteArray?>()
    val passwords = HashMap<String, String>()
    val asked = mutableListOf<String>()
    override suspend fun latestBytes(docKey: String): ByteArray? { asked += docKey; return bytes[docKey] }
    override fun passwordFor(docKey: String): String? = passwords[docKey]
}

class Harness(
    val scope: TestScope,
    config: EngineConfig,
    newEngine: (Long) -> EngineApi,
) {
    val transport = LoopbackTransport(scope.backgroundScope, newEngine)
    val recovery = MapRecovery()
    val engine = RemoteEngine(transport, config, recovery, clock = { scope.currentTime }, monotonicMs = { scope.currentTime }, scope = scope.backgroundScope)
    init { transport.clock = { scope.currentTime } }
}

fun TestScope.harness(
    config: EngineConfig = EngineConfig(maxRestartsPerWindow = 1000),
    newEngine: (Long) -> EngineApi = { FakeEngine(flags = app.pdfa.engine.FeatureFlags(encrypt = true), clock = { 1_000L }) },
) = Harness(this, config, newEngine)

fun pdf(pages: Int = 3, password: String? = null): ByteArray =
    FakeEngine.fakePdf(pageCount = pages, text = listOf("Page one", "Page two", "Page three"), password = password)

fun okStatus(r: TransportResponse) = r.response.status == ResponseStatus.OK
