package app.pdfa.engine.protocol

/** Periodic task runner, injectable so the watchdog is testable without threads. */
interface WatchdogTicker {
    fun start(periodMs: Long, task: Runnable)
    fun stop()
}

/** Daemon-thread ticker for production. */
class ThreadTicker : WatchdogTicker {
    @Volatile private var thread: Thread? = null

    override fun start(periodMs: Long, task: Runnable) {
        val t = Thread({
            try {
                while (!Thread.currentThread().isInterrupted) {
                    Thread.sleep(periodMs)
                    task.run()
                }
            } catch (_: InterruptedException) {
            }
        }, "engine-watchdog")
        t.isDaemon = true
        thread = t
        t.start()
    }

    override fun stop() { thread?.interrupt() }
}

/**
 * SERVICE-SIDE WATCHDOG. Upstream has no cancel or deadline, so a hung call can only be ended by
 * ending the process. The client enforces deadlines too, but its kill relies on unbind, which is not
 * trusted: this watchdog makes a hung engine die by itself.
 *
 * Contract: the service calls [onRequest] for EVERY request as soon as it is received (before any
 * work), [onResponseWritten] once the reply (and tile pixels) were fully handed to the transport,
 * and [onCancel] on a cancel message. A request is OVERDUE once `now > receivedAt + deadlineMs +
 * graceMs` (grace default 2 s, so the client's typed Timeout normally wins) and has not been
 * written. Any overdue request makes the watchdog call [killSelf] exactly once. A non-cooperative
 * op (merge, split, imagesToPdf, big saveFull: upstream cannot stop them) that is cancelled arms a
 * short fuse ([cancelFuseMs]): if its reply is not written within the fuse the process is killed.
 * Requests with deadlineMs = 0 are not watched. [killSelf] is supplied by the service package
 * (`Process.killProcess(Process.myPid())`, or `Runtime.halt(137)`); it must not return normally in
 * production but the watchdog does not rely on that.
 */
class ServiceWatchdog(
    private val clock: () -> Long,
    private val killSelf: () -> Unit,
    private val graceMs: Long = 2_000,
    private val cancelFuseMs: Long = 500,
    private val tickMs: Long = 250,
    private val ticker: WatchdogTicker = ThreadTicker(),
) {
    private val lock = Any()
    private val limits = HashMap<Long, Long>()
    private var fired = false

    fun start() { ticker.start(tickMs) { tick() } }
    fun stop() { ticker.stop() }

    fun onRequest(id: Long, deadlineMs: Int) {
        if (deadlineMs <= 0) return
        synchronized(lock) { limits[id] = clock() + deadlineMs + graceMs }
    }

    fun onResponseWritten(id: Long) {
        synchronized(lock) { limits.remove(id) }
    }

    fun onCancel(id: Long, cooperative: Boolean) {
        if (cooperative) return
        synchronized(lock) {
            val fuse = clock() + cancelFuseMs
            limits[id] = minOf(limits[id] ?: fuse, fuse)
        }
    }

    /** Number of requests being watched (test hook). */
    fun watched(): Int = synchronized(lock) { limits.size }

    /** One watchdog pass; called by the ticker. */
    fun tick() {
        val kill = synchronized(lock) {
            if (fired) return
            val now = clock()
            if (limits.values.any { now > it }) { fired = true; true } else false
        }
        if (kill) killSelf()
    }
}
