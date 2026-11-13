package app.pdfa.engine.protocol

import java.nio.ByteBuffer

/** Wire protocol version, carried in every frame and returned by PING. */
const val PROTOCOL_VERSION: Int = 2

/** One opcode per EngineApi method, plus [PING] and [SHUTDOWN]. Codes are frozen. */
enum class Op(val code: Int) {
    OPEN(1), CLOSE(2), META(3), PAGE_INFOS(4), RENDER_TILE(5), SEARCH_PAGE(6), SELECT_TEXT(7),
    OUTLINE(8), LINKS(9), STATE(10), ROTATE(11), DELETE(12), MOVE(13), DUPLICATE(14),
    INSERT_BLANK(15), UNDO(16), REDO(17), ADD_MARKUP(18), LIST_FIELDS(19), SET_FIELD(20),
    RESET_FORM(21), SAVE_INCREMENTAL(22), SAVE_FULL(23), CONFIRM_SAVED(24), SAVE_PROTECTED(25),
    REMOVE_PROTECTION(26), EXTRACT(27), SPLIT(28), MERGE(29), IMAGES_TO_PDF(30),
    DETECT_DOCUMENT_CORNERS(31), RECTIFY_AND_CLEAN(32), PING(33), SHUTDOWN(34);

    companion object {
        private val byCode: Map<Int, Op> = entries.associateBy { it.code }
        fun fromCode(code: Int): Op? = byCode[code]
    }
}

enum class ResponseStatus(val code: Int) {
    OK(0), ERROR(1);

    companion object {
        fun fromCode(code: Int): ResponseStatus? = entries.firstOrNull { it.code == code }
    }
}

/**
 * `requestId` is chosen by the client, unique per engine-process generation, and echoed back.
 * `deadlineMs` is the client's deadline for this call, relative to the moment the service receives
 * it (0 = none); the service watchdog uses it (see [ServiceWatchdog]).
 */
class Request(val requestId: Long, val op: Op, val payload: ByteArray, val deadlineMs: Int = 0) {
    /** Frame: u8 version, i64 requestId, i32 op, i32 deadlineMs, bytes payload. */
    fun encode(): ByteArray =
        WireWriter(payload.size + 32).u8(PROTOCOL_VERSION).i64(requestId).i32(op.code).i32(deadlineMs).bytes(payload).toByteArray()

    /** Size of [encode] without building it. */
    fun encodedSize(): Long = HEADER_BYTES.toLong() + payload.size

    /** Streams the frame without an intermediate full-size copy. */
    fun writeTo(out: java.io.OutputStream) {
        out.write(WireWriter(HEADER_BYTES).u8(PROTOCOL_VERSION).i64(requestId).i32(op.code).i32(deadlineMs).i32(payload.size).toByteArray())
        out.write(payload)
    }

    override fun toString(): String = "Request(id=$requestId, op=$op, deadline=$deadlineMs, payload=${payload.size} bytes)"

    companion object {
        const val HEADER_BYTES = 21

        fun decode(frame: ByteArray): Request {
            val r = WireReader(frame)
            val version = r.u8()
            if (version != PROTOCOL_VERSION) throw WireException("unsupported protocol version $version")
            val id = r.i64()
            val code = r.i32()
            val op = Op.fromCode(code) ?: throw WireException("unknown opcode $code")
            val deadline = r.i32()
            if (deadline < 0) throw WireException("negative deadline")
            val payload = r.bytes()
            r.finish()
            return Request(id, op, payload, deadline)
        }

        /** Reads one frame from a stream, allocating the payload once after validating its length. */
        fun readFrom(input: java.io.InputStream): Request {
            val r = WireReader(readExactly(input, HEADER_BYTES))
            val version = r.u8()
            if (version != PROTOCOL_VERSION) throw WireException("unsupported protocol version $version")
            val id = r.i64()
            val code = r.i32()
            val op = Op.fromCode(code) ?: throw WireException("unknown opcode $code")
            val deadline = r.i32()
            if (deadline < 0) throw WireException("negative deadline")
            return Request(id, op, readPayload(input, r.i32()), deadline)
        }
    }
}

internal fun readPayload(input: java.io.InputStream, len: Int): ByteArray {
    if (len < 0 || len > WireLimits.MAX_BLOB_BYTES) throw WireException("payload length $len is outside 0..${WireLimits.MAX_BLOB_BYTES}")
    return readExactly(input, len)
}

/** Reads exactly [n] bytes into one array of that size; EOF before that is a [WireException]. */
fun readExactly(input: java.io.InputStream, n: Int): ByteArray {
    val buf = ByteArray(n)
    var got = 0
    while (got < n) {
        val k = input.read(buf, got, n - got)
        if (k < 0) throw WireException("truncated stream: $got of $n bytes")
        got += k
    }
    return buf
}

/** The server answers EVERY request id exactly once, with OK or ERROR. */
class Response(val requestId: Long, val status: ResponseStatus, val payload: ByteArray) {
    /** Frame: u8 version, i64 requestId, u8 status, bytes payload. */
    fun encode(): ByteArray =
        WireWriter(payload.size + 24).u8(PROTOCOL_VERSION).i64(requestId).u8(status.code).bytes(payload).toByteArray()

    override fun toString(): String = "Response(id=$requestId, status=$status, payload=${payload.size} bytes)"

    companion object {
        fun decode(frame: ByteArray): Response {
            val r = WireReader(frame)
            val version = r.u8()
            if (version != PROTOCOL_VERSION) throw WireException("unsupported protocol version $version")
            val id = r.i64()
            val code = r.u8()
            val status = ResponseStatus.fromCode(code) ?: throw WireException("unknown status $code")
            val payload = r.bytes()
            r.finish()
            return Response(id, status, payload)
        }

        const val HEADER_BYTES = 14

        /** Reads one frame from a stream; the payload is allocated once, after its length is validated. */
        fun readFrom(input: java.io.InputStream): Response {
            val r = WireReader(readExactly(input, HEADER_BYTES))
            val version = r.u8()
            if (version != PROTOCOL_VERSION) throw WireException("unsupported protocol version $version")
            val id = r.i64()
            val code = r.u8()
            val status = ResponseStatus.fromCode(code) ?: throw WireException("unknown status $code")
            return Response(id, status, readPayload(input, r.i32()))
        }

        fun ok(requestId: Long, payload: ByteArray) = Response(requestId, ResponseStatus.OK, payload)
        fun error(requestId: Long, payload: ByteArray) = Response(requestId, ResponseStatus.ERROR, payload)
    }
}

/**
 * What a RENDER_TILE response carries instead of pixels: the geometry, the byte size, and the id of
 * the out-of-band transfer holding the pixels (premultiplied RGBA8, row-major, [size] bytes).
 */
data class TileInfo(val width: Int, val height: Int, val size: Int, val transferId: Long)

/**
 * The client-side end of a tile transfer, supplied by the transport next to the [Response]. On
 * Android it wraps a shared-memory or pipe stream; in JVM tests it is a heap buffer.
 */
interface TileBuffer : AutoCloseable {
    val transferId: Long

    /** Number of pixel bytes actually delivered. */
    val size: Int

    /** The pixels, positioned at 0. Valid until [close]. */
    fun asByteBuffer(): ByteBuffer

    override fun close() {}
}

class HeapTileBuffer(override val transferId: Long, private val pixels: ByteBuffer) : TileBuffer {
    override val size: Int get() = pixels.limit()
    override fun asByteBuffer(): ByteBuffer = pixels.duplicate().also { it.position(0) }
}
