package app.pdfa.app.engine

import android.content.ComponentName
import android.content.Context
import android.os.SystemClock
import app.pdfa.app.EngineProvider
import app.pdfa.engine.EngineApi
import app.pdfa.engine.impl.EngineConfig
import app.pdfa.engine.impl.RecoveryProvider
import app.pdfa.engine.impl.RemoteEngine
import app.pdfa.engine.impl.binder.BinderEngineTransport
import app.pdfa.engine.service.EngineService

/**
 * The one [RemoteEngine] of the process: a client of the isolated `:engine` service with the
 * watchdog and restart policy of :engine-impl. Created on first use (binding is lazy inside the
 * transport, so nothing here blocks). [shutdown] kills the engine process; the next access builds
 * a fresh client, because a closed [RemoteEngine] cannot be reused.
 */
class RemoteEngineProvider(
    context: Context,
    private val recovery: RecoveryProvider,
    private val config: EngineConfig = EngineConfig(),
) : EngineProvider {
    private val appContext = context.applicationContext
    private var current: RemoteEngine? = null

    override val engine: EngineApi
        @Synchronized get() = current ?: create().also { current = it }

    @Synchronized
    override fun shutdown() {
        current?.close()
        current = null
    }

    private fun create(): RemoteEngine = RemoteEngine(
        transport = BinderEngineTransport(appContext, ComponentName(appContext, EngineService::class.java)),
        config = config,
        recovery = recovery,
        monotonicMs = SystemClock::elapsedRealtime,
    )
}
