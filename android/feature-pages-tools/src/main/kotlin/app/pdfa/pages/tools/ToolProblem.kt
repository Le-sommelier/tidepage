package app.pdfa.pages.tools

import app.pdfa.engine.EngineError

/** What went wrong with an engine call, in the terms the user needs. */
enum class ToolProblem {
    TOO_LARGE,
    NOT_PERMITTED,
    TIMEOUT,
    ENGINE_DIED,

    /** A file is not a valid PDF or is damaged. */
    DAMAGED,

    /** Anything else. The engine's own text is in the state's `problemDetail`. */
    FAILED,
}

internal fun EngineError.toProblem(): ToolProblem = when (this) {
    is EngineError.TooLarge -> ToolProblem.TOO_LARGE
    is EngineError.NotPermitted -> ToolProblem.NOT_PERMITTED
    is EngineError.Timeout -> ToolProblem.TIMEOUT
    is EngineError.EngineDied -> ToolProblem.ENGINE_DIED
    is EngineError.InvalidPdf -> ToolProblem.DAMAGED
    else -> ToolProblem.FAILED
}
