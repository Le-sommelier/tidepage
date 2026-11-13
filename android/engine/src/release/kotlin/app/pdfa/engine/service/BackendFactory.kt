package app.pdfa.engine.service

import app.pdfa.engine.service.ffi.UniffiBackend

/** Release: the native engine and nothing else. The debug source set has its own copy. */
internal object BackendFactory {
    fun create(): NativeEngineBackend = UniffiBackend()
}
