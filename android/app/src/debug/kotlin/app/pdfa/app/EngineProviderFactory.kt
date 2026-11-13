package app.pdfa.app

import android.content.Context
import app.pdfa.app.engine.RemoteEngineProvider
import app.pdfa.engine.EngineApi
import app.pdfa.engine.fake.FakeEngine
import app.pdfa.engine.impl.RecoveryProvider

/** In-memory fake for UI development. Only reachable with `-Ppdfa.fakeEngine=true` on a debug build. */
private class FakeEngineProvider : EngineProvider {
    override val engine: EngineApi by lazy { FakeEngine() }
    override fun shutdown() = Unit
}

/** Debug: the real engine, unless the build asked for the fake. */
internal fun createEngineProvider(context: Context, recovery: RecoveryProvider): EngineProvider =
    if (BuildConfig.USE_FAKE_ENGINE) FakeEngineProvider() else RemoteEngineProvider(context, recovery)
