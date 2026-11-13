package app.pdfa.engine.impl

import app.pdfa.engine.EngineEvent
import app.pdfa.engine.protocol.Request
import app.pdfa.engine.protocol.Response
import app.pdfa.engine.protocol.TileBuffer
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow

/** A response frame plus, for RENDER_TILE, the out-of-band pixels. */
class TransportResponse(val response: Response, val tile: TileBuffer? = null)

/** The engine process died by itself (OOM, abort, kill). [generation] is the process that died. */
data class DeathEvent(val generation: Long, val reason: String)

/**
 * The only thing [RemoteEngine] needs from the platform. Contract:
 *  - [call] connects lazily if needed; completes with the server's frame; throws
 *    `EngineError.EngineDied` (or any non-cancellation exception, treated the same) when the
 *    connection breaks before an answer; must be safe to call concurrently.
 *  - After [kill], every pending [call] must complete exceptionally (EngineDied) promptly.
 *  - [restart] starts a fresh process and increments [generation].
 *  - [deaths] emits only for deaths NOT caused by [kill]; [DeathEvent.generation] names the process.
 */
interface EngineTransport {
    /** Incremented by every [restart]; the first process is generation 1. */
    val generation: Long

    /**
     * [expectedGeneration] is the process generation the caller's handles belong to (-1 = any). If it
     * differs from [generation] the call MUST fail with EngineDied BEFORE anything is sent, so an
     * edit can never land on a different process's document.
     */
    suspend fun call(request: Request, deadlineMs: Long, expectedGeneration: Long = -1L): TransportResponse

    /** Best-effort: tell the server to stop work on [requestId]. Never blocks, never throws. */
    fun cancel(requestId: Long) {}

    /** Hard-kill the engine process and drop the connection. Never blocks. */
    fun kill(reason: String)

    suspend fun restart()

    val deaths: Flow<DeathEvent>

    /** Server-pushed progress, already decoded. */
    val progress: Flow<EngineEvent.Progress> get() = emptyFlow()
}

/** Supplies the bytes to reopen a document after the engine restarted. */
interface RecoveryProvider {
    /** Latest recoverable bytes for [docKey] (journal, else original), or null if there are none. */
    suspend fun latestBytes(docKey: String): ByteArray?

    /** Password to reopen [docKey], or null. Must not block. */
    fun passwordFor(docKey: String): String?

    /** True if [latestBytes] for [docKey] is the original file rather than an autosave (for EditsLost). */
    fun restoredFromOriginal(docKey: String): Boolean = false
}

/**
 * Deadlines and policy. Every field can be overridden; [deadlineOverrides] wins per op.
 */
data class EngineConfig(
    val openMs: Long = 15_000,
    val tileMs: Long = 2_000,
    val textMs: Long = 10_000,
    val editMs: Long = 5_000,
    val saveMs: Long = 60_000,
    val filesMs: Long = 120_000,
    val scanMs: Long = 10_000,
    val pingMs: Long = 2_000,
    /** Deadline for transport.restart() and RecoveryProvider.latestBytes(). */
    val recoveryStepMs: Long = 15_000,
    /** Cap on the total time of one recovery; documents not reopened by then are marked dead. */
    val maxRecoveryMs: Long = 60_000,
    val deadlineOverrides: Map<app.pdfa.engine.protocol.Op, Long> = emptyMap(),
    /** Cancelling a caller of these ops kills and restarts the process. */
    val killOnCancel: Set<app.pdfa.engine.protocol.Op> = setOf(
        app.pdfa.engine.protocol.Op.MERGE,
        app.pdfa.engine.protocol.Op.SPLIT,
        app.pdfa.engine.protocol.Op.IMAGES_TO_PDF,
    ),
    /** saveFull of a document at least this large (bytes as opened) also kills on cancel. */
    val killSaveFullOnCancelAboveBytes: Long = 32L * 1024 * 1024,
    /** More than this many restarts within [stormWindowMs] degrades the engine. */
    val maxRestartsPerWindow: Int = 3,
    val stormWindowMs: Long = 60_000,
    val degradedCooldownMs: Long = 30_000,
    /** Documents opened without a docKey are recoverable only if at most this large. */
    val maxInMemoryRecoveryBytes: Long = 32L * 1024 * 1024,
    val maxInputBytes: Long = app.pdfa.engine.protocol.WireLimits.MAX_INPUT_BYTES,
    val maxRequestBytes: Long = app.pdfa.engine.protocol.WireLimits.MAX_BLOB_BYTES.toLong(),
    val maxRenderPixels: Long = app.pdfa.engine.protocol.WireLimits.MAX_RENDER_PIXELS,
) {
    fun deadlineFor(op: app.pdfa.engine.protocol.Op): Long = deadlineOverrides[op] ?: when (op) {
        app.pdfa.engine.protocol.Op.OPEN -> openMs
        app.pdfa.engine.protocol.Op.RENDER_TILE -> tileMs
        app.pdfa.engine.protocol.Op.META, app.pdfa.engine.protocol.Op.PAGE_INFOS,
        app.pdfa.engine.protocol.Op.SEARCH_PAGE, app.pdfa.engine.protocol.Op.SELECT_TEXT,
        app.pdfa.engine.protocol.Op.OUTLINE, app.pdfa.engine.protocol.Op.LINKS,
        app.pdfa.engine.protocol.Op.LIST_FIELDS -> textMs
        app.pdfa.engine.protocol.Op.SAVE_INCREMENTAL, app.pdfa.engine.protocol.Op.SAVE_FULL,
        app.pdfa.engine.protocol.Op.SAVE_PROTECTED, app.pdfa.engine.protocol.Op.EXTRACT -> saveMs
        app.pdfa.engine.protocol.Op.MERGE, app.pdfa.engine.protocol.Op.SPLIT,
        app.pdfa.engine.protocol.Op.IMAGES_TO_PDF -> filesMs
        app.pdfa.engine.protocol.Op.DETECT_DOCUMENT_CORNERS,
        app.pdfa.engine.protocol.Op.RECTIFY_AND_CLEAN -> scanMs
        app.pdfa.engine.protocol.Op.PING, app.pdfa.engine.protocol.Op.SHUTDOWN -> pingMs
        else -> editMs
    }
}
