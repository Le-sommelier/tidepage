package app.pdfa.engine.service

import app.pdfa.engine.MergeSource

/**
 * DEBUG ONLY. Simulates an engine call that never returns, so the watchdog and the client's
 * kill-and-restart can be proven on a device: `open` with the password [HANG], or `merge` with a
 * source named [HANG], blocks its worker thread forever (it ignores interrupts, like a native loop
 * would). Everything else goes to the real backend. The release variant has no such class; the build
 * checks that the string is absent from the release AAR.
 */
internal class HangingBackend(private val real: NativeEngineBackend) : NativeEngineBackend by real {
    override fun open(bytes: ByteArray, password: String?): NativeSession {
        if (password == HANG) park()
        return real.open(bytes, password)
    }

    override fun merge(sources: List<MergeSource>): ByteArray {
        if (sources.any { it.name == HANG }) park()
        return real.merge(sources)
    }

    private fun park(): Nothing {
        while (true) {
            try {
                Thread.sleep(Long.MAX_VALUE)
            } catch (_: InterruptedException) {
            }
        }
    }

    companion object {
        const val HANG = "__hang__"
    }
}
