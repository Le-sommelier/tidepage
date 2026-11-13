package app.pdfa.app

import android.content.Context
import app.pdfa.app.engine.RemoteEngineProvider
import app.pdfa.engine.impl.RecoveryProvider

/** Release: the real engine only. The fake is not on the release classpath. */
internal fun createEngineProvider(context: Context, recovery: RecoveryProvider): EngineProvider =
    RemoteEngineProvider(context, recovery)
