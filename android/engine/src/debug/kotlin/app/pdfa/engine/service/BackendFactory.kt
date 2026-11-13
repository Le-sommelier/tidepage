package app.pdfa.engine.service

import app.pdfa.engine.service.ffi.UniffiBackend

/** Debug: the native engine behind the test-only [HangingBackend]. Never compiled into release. */
internal object BackendFactory {
    fun create(): NativeEngineBackend = HangingBackend(UniffiBackend())
}
