package app.pdfa.app.tools

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import app.pdfa.app.intent.IntentRouter
import app.pdfa.app.recents.DisplayNames
import app.pdfa.pages.tools.PartSink
import app.pdfa.pages.tools.SourceLoad
import app.pdfa.storage.MAX_DOCUMENT_BYTES
import app.pdfa.storage.UriStreams
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.IOException

/**
 * The file reading and writing the merge and split tools need, off the main thread. The pickers'
 * results and the tests both call these. Nothing here logs a name, a URI or any content.
 */
internal class ToolsIo(private val streams: UriStreams) {

    /** Reads a picked PDF: at most [MAX_DOCUMENT_BYTES], and `%PDF-` within the first 1024 bytes. */
    suspend fun readSource(uri: String): SourceLoad = withContext(Dispatchers.IO) {
        val name = DisplayNames.fromUri(uri)
        if (IntentRouter.acceptDocUri(uri) == null) return@withContext SourceLoad.Failed(name, SourceLoad.Reason.UNREADABLE)
        try {
            streams.openInput(uri).use { input ->
                val out = ByteArrayOutputStream()
                val buffer = ByteArray(64 * 1024)
                var total = 0L
                while (true) {
                    val n = input.read(buffer)
                    if (n < 0) break
                    total += n
                    if (total > MAX_DOCUMENT_BYTES) return@withContext SourceLoad.Failed(name, SourceLoad.Reason.TOO_LARGE)
                    out.write(buffer, 0, n)
                }
                val bytes = out.toByteArray()
                val head = String(bytes, 0, minOf(bytes.size, 1024), Charsets.ISO_8859_1)
                if (!head.contains("%PDF-")) SourceLoad.Failed(name, SourceLoad.Reason.NOT_PDF)
                else SourceLoad.Loaded(name, bytes)
            }
        } catch (e: IOException) {
            SourceLoad.Failed(name, SourceLoad.Reason.UNREADABLE)
        } catch (e: SecurityException) {
            SourceLoad.Failed(name, SourceLoad.Reason.UNREADABLE)
        }
    }

    /**
     * Writes [bytes] to a file the user just created (a create-document result), then reads the file
     * back and compares. True only when the file holds exactly [bytes]: the callers let go of the
     * merged file or the scanned pages on true, so "the write did not throw" is not enough.
     *
     * A failing `sync()` or `close()` is not a failure by itself (pipe-backed providers, such as cloud
     * storage, cannot sync): the read-back decides, as in :storage's SafWriter.
     */
    suspend fun writeFile(uri: String, bytes: ByteArray): Boolean = withContext(Dispatchers.IO) {
        try {
            val out = streams.openOutputTruncating(uri)
            try {
                out.write(bytes)
                out.flush()
                try {
                    out.sync()
                } catch (e: IOException) {
                    // The read-back below decides.
                }
            } catch (e: IOException) {
                try { out.close() } catch (ignored: IOException) { }
                return@withContext false
            }
            try {
                out.close()
            } catch (e: IOException) {
                // The read-back below decides.
            }
            holdsExactly(uri, bytes)
        } catch (e: IOException) {
            false
        } catch (e: SecurityException) {
            false
        }
    }

    private fun holdsExactly(uri: String, expected: ByteArray): Boolean =
        streams.openInput(uri).use { input ->
            val buffer = ByteArray(64 * 1024)
            var at = 0
            while (true) {
                val n = input.read(buffer)
                if (n < 0) break
                if (at + n > expected.size) return@use false
                for (i in 0 until n) if (buffer[i] != expected[at + i]) return@use false
                at += n
            }
            at == expected.size
        }
}

/**
 * A sink that creates each part as a new file inside the folder [tree] (an OpenDocumentTree result)
 * and writes it with [write]. A provider that refuses to create the file counts as a failed part.
 */
internal fun treePartSink(context: Context, tree: Uri, write: suspend (uri: String, bytes: ByteArray) -> Boolean): PartSink =
    PartSink { _, name, bytes ->
        val created = withContext(Dispatchers.IO) {
            try {
                val parent = DocumentsContract.buildDocumentUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree))
                DocumentsContract.createDocument(context.contentResolver, parent, "application/pdf", name)
            } catch (e: Exception) {
                null // FileNotFound, IllegalArgument, SecurityException, or a provider that dies
            }
        }
        created != null && write(created.toString(), bytes)
    }
