package app.pdfa.engine.impl.binder

import android.os.ParcelFileDescriptor
import android.system.Os
import android.system.OsConstants
import app.pdfa.engine.impl.BlobFeeder
import app.pdfa.engine.impl.BlobPipe
import app.pdfa.engine.impl.BlobPipeFactory
import app.pdfa.engine.impl.BlobReader
import app.pdfa.engine.impl.ScheduledBlobTimer
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

/**
 * Android side of out-of-band byte transfer (frames and tile pixels larger than Binder allows).
 * All policy (deadline abort, leak rules, bounded size checks) is in the JVM-tested `BlobIo.kt`;
 * this only supplies socket-pair ParcelFileDescriptors. A socket pair (not a pipe) is used because
 * `shutdown(2)` is what reliably unblocks a thread stuck writing to a peer that never reads.
 * Shared by the client transport and the service: the service must use [feeder] for large
 * replies, which writes on its own thread.
 */
object BlobPipes {
    /** Frames up to this size are sent inline in the Binder transaction. */
    const val INLINE_LIMIT: Int = 64 * 1024

    private fun pool(name: String) = ThreadPoolExecutor(
        4, 4, 10, TimeUnit.SECONDS, LinkedBlockingQueue(16),
        { r -> Thread(r, name).also { it.isDaemon = true } },
    ).also { it.allowCoreThreadTimeOut(true) }

    private val timer = ScheduledBlobTimer()

    @JvmField
    val feeder: BlobFeeder<ParcelFileDescriptor> = BlobFeeder(BlobPipeFactory { SocketPipe() }, pool("engine-blob-writer"), timer)

    @JvmField
    val reader: BlobReader = BlobReader(pool("engine-blob-reader"), timer)

    @JvmStatic
    fun input(pfd: ParcelFileDescriptor): InputStream = ParcelFileDescriptor.AutoCloseInputStream(pfd)

    /** Unblocks any thread reading or writing [pfd] and closes it. */
    @JvmStatic
    fun abort(pfd: ParcelFileDescriptor?) {
        if (pfd == null) return
        try { Os.shutdown(pfd.fileDescriptor, OsConstants.SHUT_RDWR) } catch (_: Exception) { }
        try { pfd.close() } catch (_: Exception) { }
    }

    @JvmStatic
    fun closeQuietly(vararg pfds: ParcelFileDescriptor?) {
        for (p in pfds) try { p?.close() } catch (_: Exception) { }
    }

    private class SocketPipe : BlobPipe<ParcelFileDescriptor> {
        private val pair = ParcelFileDescriptor.createSocketPair()
        private val local = pair[0]
        override val reader: ParcelFileDescriptor = pair[1]
        override fun openWriter(): OutputStream = ParcelFileDescriptor.AutoCloseOutputStream(local)
        override fun closeLocalReader() = closeQuietly(reader)
        override fun closeWriter() = closeQuietly(local)
        override fun abort() {
            abort(local)
            closeQuietly(reader)
        }
    }
}
