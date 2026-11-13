package app.pdfa.viewer.extras

import app.pdfa.engine.EngineError

/** True when the engine process died or missed its deadline: every handle is invalid after this. */
internal fun EngineError.isEngineLost(): Boolean =
    this is EngineError.EngineDied || this is EngineError.Timeout
