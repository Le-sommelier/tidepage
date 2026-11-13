package app.pdfa.app.tools

import app.pdfa.storage.OutputStreamWithSync
import app.pdfa.storage.SourceStat
import app.pdfa.storage.UriStreams
import kotlinx.coroutines.runBlocking
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.SyncFailedException
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** A provider with switchable faults. */
private class FaultyStreams : UriStreams {
    val docs = HashMap<String, ByteArray>()
    var syncFails = false
    var closeFails = false
    var writeFails = false
    /** The provider silently keeps only this many bytes. */
    var keepOnly: Int? = null
    var readFails = false

    override fun openInput(uri: String): InputStream {
        if (readFails) throw IOException("read refused")
        return ByteArrayInputStream(docs[uri] ?: throw IOException("no such document"))
    }

    override fun openOutputTruncating(uri: String): OutputStreamWithSync {
        val buffer = ByteArrayOutputStream()
        docs[uri] = ByteArray(0)
        return object : OutputStreamWithSync() {
            override fun write(b: Int) = write(byteArrayOf(b.toByte()), 0, 1)
            override fun write(b: ByteArray, off: Int, len: Int) {
                if (writeFails) throw IOException("write refused")
                buffer.write(b, off, len)
                val all = buffer.toByteArray()
                docs[uri] = keepOnly?.let { all.copyOf(minOf(it, all.size)) } ?: all
            }
            override fun sync() { if (syncFails) throw SyncFailedException("pipe") }
            override fun close() { if (closeFails) throw IOException("close failed") }
        }
    }

    override fun stat(uri: String): SourceStat = SourceStat(docs.getValue(uri).size.toLong(), null, null)
}

class ToolsIoTest {
    private val streams = FaultyStreams()
    private val io = ToolsIo(streams)
    private val uri = "content://provider/document/out"
    private val bytes = ByteArray(200_000) { (it % 251).toByte() }

    @Test
    fun aWrittenFileIsReadBackAndConfirmed() = runBlocking<Unit> {
        assertTrue(io.writeFile(uri, bytes))
        assertContentEquals(bytes, streams.docs[uri])
    }

    @Test
    fun aProviderThatCannotSyncOrFailsOnCloseStillSavesWhenTheBytesAreThere() = runBlocking<Unit> {
        streams.syncFails = true
        streams.closeFails = true
        assertTrue(io.writeFile(uri, bytes), "the read-back decides, not sync or close")
    }

    @Test
    fun aSilentlyShortFileIsNotReportedAsSaved() = runBlocking<Unit> {
        streams.keepOnly = 1000
        assertFalse(io.writeFile(uri, bytes))
    }

    @Test
    fun aRefusedWriteOrAnUnreadableResultIsAFailure() = runBlocking<Unit> {
        streams.writeFails = true
        assertFalse(io.writeFile(uri, bytes))
        streams.writeFails = false
        streams.readFails = true
        assertFalse(io.writeFile(uri, bytes), "a file that cannot be checked is never 'saved'")
    }
}
