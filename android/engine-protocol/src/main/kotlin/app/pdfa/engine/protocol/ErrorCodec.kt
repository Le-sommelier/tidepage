package app.pdfa.engine.protocol

import app.pdfa.engine.EngineError

/**
 * ERROR response payload: u8 code, string detail. Codes are frozen. [Timeout] and [EngineDied] are
 * client-only: a server never sends them, and a client that receives one treats it as a protocol
 * error. They are encodable only so every EngineError subclass has a code.
 */
object ErrorCodec {
    const val INVALID_PDF = 1
    const val PASSWORD_REQUIRED = 2
    const val WRONG_PASSWORD = 3
    const val TOO_LARGE = 4
    const val INVALID_ARGUMENT = 5
    const val RENDER = 6
    const val PANIC = 7
    const val UNSUPPORTED = 8
    const val IO = 9
    const val CANCELLED = 10
    const val NOT_PERMITTED = 11
    const val TIMEOUT = 12
    const val ENGINE_DIED = 13

    private const val MAX_DETAIL_CHARS = 16 * 1024

    fun codeOf(e: EngineError): Int = when (e) {
        is EngineError.InvalidPdf -> INVALID_PDF
        EngineError.PasswordRequired -> PASSWORD_REQUIRED
        EngineError.WrongPassword -> WRONG_PASSWORD
        is EngineError.TooLarge -> TOO_LARGE
        is EngineError.InvalidArgument -> INVALID_ARGUMENT
        is EngineError.Render -> RENDER
        is EngineError.Panic -> PANIC
        is EngineError.Unsupported -> UNSUPPORTED
        is EngineError.Io -> IO
        EngineError.Cancelled -> CANCELLED
        is EngineError.NotPermitted -> NOT_PERMITTED
        is EngineError.Timeout -> TIMEOUT
        is EngineError.EngineDied -> ENGINE_DIED
    }

    fun isClientOnly(e: EngineError): Boolean = e is EngineError.Timeout || e is EngineError.EngineDied

    fun encode(e: EngineError): ByteArray {
        val detail = if (e.detail.length > MAX_DETAIL_CHARS) e.detail.substring(0, MAX_DETAIL_CHARS) else e.detail
        return WireWriter().u8(codeOf(e)).string(detail).toByteArray()
    }

    /** @throws WireException on a malformed payload or an unknown code. */
    fun decode(payload: ByteArray): EngineError {
        val r = WireReader(payload)
        val code = r.u8()
        val detail = r.string()
        r.finish()
        return when (code) {
            INVALID_PDF -> EngineError.InvalidPdf(detail)
            PASSWORD_REQUIRED -> EngineError.PasswordRequired
            WRONG_PASSWORD -> EngineError.WrongPassword
            TOO_LARGE -> EngineError.TooLarge(detail)
            INVALID_ARGUMENT -> EngineError.InvalidArgument(detail)
            RENDER -> EngineError.Render(detail)
            PANIC -> EngineError.Panic(detail)
            UNSUPPORTED -> EngineError.Unsupported(detail)
            IO -> EngineError.Io(detail)
            CANCELLED -> EngineError.Cancelled
            NOT_PERMITTED -> EngineError.NotPermitted(detail)
            TIMEOUT -> EngineError.Timeout(detail)
            ENGINE_DIED -> EngineError.EngineDied(detail)
            else -> throw WireException("unknown error code $code")
        }
    }
}
