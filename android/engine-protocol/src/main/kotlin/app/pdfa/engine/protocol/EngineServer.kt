package app.pdfa.engine.protocol

import app.pdfa.engine.DocHandle
import app.pdfa.engine.EngineApi
import app.pdfa.engine.EngineError
import app.pdfa.engine.EngineEvent
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.filterIsInstance
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicLong

/** A response plus, for RENDER_TILE, the pixels the transport must deliver out of band. */
class ServerReply(val response: Response, val tilePixels: ByteBuffer? = null)

/**
 * Reference server-side dispatcher: decodes a [Request], calls the wrapped [EngineApi], encodes the
 * [Response]. The service wraps its UniFFI-backed EngineApi in this and only adds the
 * transport plumbing. It never trusts the payload: every parse failure becomes an ERROR response
 * (InvalidArgument), every unexpected throwable becomes Panic. It answers every request id.
 *
 * Handles are passed through unchanged: the wire handle IS `DocHandle.id`. The wrapped api must not
 * reuse a handle id across process lifetimes (see README, "stale handles").
 */
class EngineServer(
    private val api: EngineApi,
    private val watchdog: ServiceWatchdog? = null,
    /** Random per-boot id returned by PING; the client checks it changes across restarts. */
    val bootId: Long = java.util.concurrent.ThreadLocalRandom.current().nextLong(),
    /**
     * Per-boot random 31-bit nonce put in the high 32 bits of every handle sent on the wire; a handle
     * from another boot is rejected with InvalidArgument. null disables the prefix (tests only).
     */
    private val handleNonce: Int? = java.util.concurrent.ThreadLocalRandom.current().nextInt(1, Int.MAX_VALUE),
    private val pid: Int = 0,
) {
    private val transferIds = AtomicLong(1)
    private val inFlight = java.util.concurrent.ConcurrentHashMap<Long, Op>()

    private fun toWire(local: Long): Long = if (handleNonce == null) local else (handleNonce.toLong() shl 32) or (local and 0xFFFFFFFFL)

    private fun fromWire(wire: Long): Long {
        if (handleNonce == null) return wire
        if ((wire ushr 32).toInt() != handleNonce) throw EngineError.InvalidArgument("handle $wire does not belong to this engine process")
        return wire and 0xFFFFFFFFL
    }

    private fun h(wire: Long) = DocHandle(fromWire(wire))

    /** The transport calls this after the reply (and tile pixels) were fully written. */
    fun responseWritten(requestId: Long) {
        inFlight.remove(requestId)
        watchdog?.onResponseWritten(requestId)
    }

    /** The transport calls this on a cancel message. Cooperative = the op can stop on its own. */
    fun cancel(requestId: Long) {
        val op = inFlight[requestId] ?: return
        watchdog?.onCancel(requestId, op !in NON_COOPERATIVE)
    }

    private companion object {
        val NON_COOPERATIVE = setOf(Op.MERGE, Op.SPLIT, Op.IMAGES_TO_PDF, Op.SAVE_FULL)
    }

    /** Progress events to push to the client as `EngineWire.progress(opId, fraction)`. */
    val progress: Flow<EngineEvent.Progress> get() = api.events.filterIsInstance<EngineEvent.Progress>()

    suspend fun handle(request: Request): ServerReply {
        val id = request.requestId
        inFlight[id] = request.op
        watchdog?.onRequest(id, request.deadlineMs)
        return try {
            dispatch(request)
        } catch (e: CancellationException) {
            throw e
        } catch (e: WireException) {
            ServerReply(Response.error(id, ErrorCodec.encode(EngineError.InvalidArgument("malformed request: ${e.message}"))))
        } catch (e: EngineError) {
            // Client-only errors must never leave the server.
            val sent = if (ErrorCodec.isClientOnly(e)) EngineError.Panic("engine reported ${e.javaClass.simpleName}") else e
            ServerReply(Response.error(id, ErrorCodec.encode(sent)))
        } catch (e: OutOfMemoryError) {
            ServerReply(Response.error(id, ErrorCodec.encode(EngineError.TooLarge("out of memory"))))
        } catch (e: Throwable) {
            // Class name only: messages may contain document content.
            ServerReply(Response.error(id, ErrorCodec.encode(EngineError.Panic(e.javaClass.simpleName))))
        }
    }

    private fun ok(r: Request, payload: ByteArray) = ServerReply(Response.ok(r.requestId, payload))

    private suspend fun dispatch(r: Request): ServerReply {
        val p = r.payload
        return when (r.op) {
            Op.OPEN -> {
                val a = EngineWire.parseOpenArgs(p)
                ok(r, EngineWire.openResult(toWire(api.open(a.bytes, a.password).id)))
            }
            Op.CLOSE -> { api.close(h(EngineWire.parseHandleArgs(p))); ok(r, EngineWire.unitResult()) }
            Op.META -> ok(r, EngineWire.metaResult(api.meta(h(EngineWire.parseHandleArgs(p)))))
            Op.PAGE_INFOS -> ok(r, EngineWire.pageInfosResult(api.pageInfos(h(EngineWire.parseHandleArgs(p)))))
            Op.RENDER_TILE -> {
                val a = EngineWire.parseRenderTileArgs(p)
                val tile = api.renderTile(h(a.handle), a.request)
                val pixels = tile.premultipliedRgba.duplicate().also { it.position(0) }
                val size = pixels.limit()
                val info = TileInfo(tile.width, tile.height, size, transferIds.getAndIncrement())
                ServerReply(Response.ok(r.requestId, EngineWire.renderTileResult(info)), pixels)
            }
            Op.SEARCH_PAGE -> {
                val a = EngineWire.parseSearchPageArgs(p)
                ok(r, EngineWire.searchPageResult(api.searchPage(h(a.handle), a.page, a.query, a.caseSensitive, a.wholeWord)))
            }
            Op.SELECT_TEXT -> {
                val a = EngineWire.parseSelectTextArgs(p)
                ok(r, EngineWire.selectTextResult(api.selectText(h(a.handle), a.page, a.from, a.to)))
            }
            Op.OUTLINE -> {
                val a = EngineWire.parseOutlineArgs(p)
                ok(r, EngineWire.outlineResult(api.outline(h(a.handle), a.offset, a.limit)))
            }
            Op.LINKS -> {
                val a = EngineWire.parseLinksArgs(p)
                ok(r, EngineWire.linksResult(api.links(h(a.handle), a.page)))
            }
            Op.STATE -> state(r) { api.state(h(it)) }
            Op.UNDO -> state(r) { api.undo(h(it)) }
            Op.REDO -> state(r) { api.redo(h(it)) }
            Op.CONFIRM_SAVED -> state(r) { api.confirmSaved(h(it)) }
            Op.REMOVE_PROTECTION -> state(r) { api.removeProtection(h(it)) }
            Op.ROTATE -> {
                val a = EngineWire.parseRotateArgs(p)
                ok(r, EngineWire.editStateResult(api.rotate(h(a.handle), a.pages, a.by)))
            }
            Op.DELETE -> {
                val a = EngineWire.parsePagesArgs(p)
                ok(r, EngineWire.editStateResult(api.delete(h(a.handle), a.pages)))
            }
            Op.MOVE -> {
                val a = EngineWire.parseMoveArgs(p)
                ok(r, EngineWire.editStateResult(api.move(h(a.handle), a.pages, a.toIndex)))
            }
            Op.DUPLICATE -> {
                val a = EngineWire.parsePagesArgs(p)
                ok(r, EngineWire.editStateResult(api.duplicate(h(a.handle), a.pages)))
            }
            Op.INSERT_BLANK -> {
                val a = EngineWire.parseInsertBlankArgs(p)
                ok(r, EngineWire.editStateResult(api.insertBlank(h(a.handle), a.at, a.widthPt, a.heightPt)))
            }
            Op.ADD_MARKUP -> {
                val a = EngineWire.parseAddMarkupArgs(p)
                ok(r, EngineWire.editStateResult(api.addMarkup(h(a.handle), a.markup)))
            }
            Op.LIST_FIELDS -> ok(r, EngineWire.listFieldsResult(api.listFields(h(EngineWire.parseHandleArgs(p)))))
            Op.SET_FIELD -> {
                val a = EngineWire.parseSetFieldArgs(p)
                ok(r, EngineWire.editStateResult(api.setField(h(a.handle), a.name, a.value)))
            }
            Op.RESET_FORM -> {
                val a = EngineWire.parseResetFormArgs(p)
                ok(r, EngineWire.editStateResult(api.resetForm(h(a.handle), a.names)))
            }
            Op.SAVE_INCREMENTAL -> ok(r, EngineWire.bytesResult(api.saveIncremental(h(EngineWire.parseHandleArgs(p)))))
            Op.SAVE_FULL -> ok(r, EngineWire.bytesResult(api.saveFull(h(EngineWire.parseHandleArgs(p)))))
            Op.SAVE_PROTECTED -> {
                val a = EngineWire.parseSaveProtectedArgs(p)
                ok(r, EngineWire.bytesResult(api.saveProtected(h(a.handle), a.spec)))
            }
            Op.EXTRACT -> {
                val a = EngineWire.parsePagesArgs(p)
                ok(r, EngineWire.bytesResult(api.extract(h(a.handle), a.pages)))
            }
            Op.SPLIT -> {
                val a = EngineWire.parseSplitArgs(p)
                val parts = api.split(h(a.handle), a.spec)
                ok(r, EngineWire.splitResult(parts, parts.sumOf { it.bytes.size } + 64))
            }
            Op.MERGE -> ok(r, EngineWire.bytesResult(api.merge(EngineWire.parseMergeArgs(p))))
            Op.IMAGES_TO_PDF -> {
                val a = EngineWire.parseImagesToPdfArgs(p)
                ok(r, EngineWire.bytesResult(api.imagesToPdf(a.images, a.dpi)))
            }
            Op.DETECT_DOCUMENT_CORNERS ->
                ok(r, EngineWire.detectDocumentCornersResult(api.detectDocumentCorners(EngineWire.parseDetectDocumentCornersArgs(p))))
            Op.RECTIFY_AND_CLEAN -> {
                val a = EngineWire.parseRectifyAndCleanArgs(p)
                ok(r, EngineWire.rectifyAndCleanResult(api.rectifyAndClean(a.frame, a.quad, a.look, a.level)))
            }
            Op.PING -> { EngineWire.parseEmptyArgs(p); ok(r, EngineWire.pingResult(bootId, pid)) }
            Op.SHUTDOWN -> { EngineWire.parseEmptyArgs(p); ok(r, EngineWire.unitResult()) }
        }
    }

    private suspend fun state(r: Request, f: suspend (Long) -> app.pdfa.engine.EditState): ServerReply =
        ok(r, EngineWire.editStateResult(f(EngineWire.parseHandleArgs(r.payload))))
}
