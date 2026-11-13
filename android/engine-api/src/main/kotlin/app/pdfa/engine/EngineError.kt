package app.pdfa.engine

/**
 * Every failure an [EngineApi] call can raise. The first group mirrors Rust's `PdfError`; the
 * second group is raised by the Kotlin client when the engine PROCESS misbehaves. All carry
 * their text in `detail` (never `message`).
 */
sealed class EngineError(val detail: String) : Exception(detail) {
    // From the Rust side (PdfError).
    class InvalidPdf(detail: String) : EngineError(detail)
    object PasswordRequired : EngineError("password required") {
        private fun readResolve(): Any = PasswordRequired
    }
    object WrongPassword : EngineError("wrong password") {
        private fun readResolve(): Any = WrongPassword
    }
    class TooLarge(detail: String) : EngineError(detail)
    class InvalidArgument(detail: String) : EngineError(detail)
    class Render(detail: String) : EngineError(detail)
    class Panic(detail: String) : EngineError(detail)
    class Unsupported(detail: String) : EngineError(detail)
    class Io(detail: String) : EngineError(detail)
    object Cancelled : EngineError("cancelled") {
        private fun readResolve(): Any = Cancelled
    }

    /** The document's permissions or the caller's credentials do not allow the operation. */
    class NotPermitted(detail: String) : EngineError(detail)

    // From the Kotlin client (engine process supervision).
    /** The watchdog deadline for the call passed; the process was killed and restarted. */
    class Timeout(detail: String) : EngineError(detail)

    /** The engine process died during the call (OOM, abort, kill). Handles are now invalid. */
    class EngineDied(detail: String) : EngineError(detail)
}
