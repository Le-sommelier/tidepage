package app.pdfa.storage

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream

/**
 * [UriStreams] over the app's ContentResolver. Not exercised by JVM unit tests (android.* is a
 * stub there); verified only on a device or emulator.
 */
class ContentResolverStreams(context: Context) : UriStreams {

    private val resolver = context.contentResolver

    override fun openInput(uri: String): InputStream =
        resolver.openInputStream(Uri.parse(uri)) ?: throw IOException("no input stream")

    override fun openOutputTruncating(uri: String): OutputStreamWithSync {
        val pfd = resolver.openFileDescriptor(Uri.parse(uri), "wt") ?: throw IOException("no descriptor")
        val fos = try {
            FileOutputStream(pfd.fileDescriptor)
        } catch (e: Exception) {
            pfd.close()
            throw e
        }
        return object : OutputStreamWithSync() {
            override fun write(b: Int) = fos.write(b)
            override fun write(b: ByteArray, off: Int, len: Int) = fos.write(b, off, len)
            override fun flush() = fos.flush()
            override fun sync() = pfd.fileDescriptor.sync()
            // Closes the descriptor once. The FileOutputStream wraps it without owning it.
            override fun close() = pfd.close()
        }
    }

    override fun stat(uri: String): SourceStat {
        val parsed = Uri.parse(uri)
        var name: String? = null
        var size: Long? = null
        var lastModified: Long? = null
        resolver.query(parsed, null, null, null, null)?.use { c ->
            if (c.moveToFirst()) {
                name = c.columnString(OpenableColumns.DISPLAY_NAME)
                size = c.columnLong(OpenableColumns.SIZE)
                lastModified = c.columnLong(DocumentsContract.Document.COLUMN_LAST_MODIFIED)
            }
        }
        val resolvedSize = size ?: resolver.openFileDescriptor(parsed, "r")?.use { it.statSize }
            ?.takeIf { it >= 0 }
            ?: throw IOException("size unknown")
        return SourceStat(resolvedSize, lastModified, name)
    }

    /**
     * `authority|documentId` for document URIs, so two URI forms of one document compare equal. Returns
     * null for anything else (not a document URI, or an error). SafWriter then refuses the write.
     */
    override fun documentKey(uri: String): String? = try {
        val parsed = Uri.parse(uri)
        "${parsed.authority}|${DocumentsContract.getDocumentId(parsed)}"
    } catch (e: Exception) {
        null
    }

    private fun android.database.Cursor.columnString(column: String): String? {
        val idx = getColumnIndex(column)
        return if (idx >= 0 && !isNull(idx)) getString(idx) else null
    }

    private fun android.database.Cursor.columnLong(column: String): Long? {
        val idx = getColumnIndex(column)
        return if (idx >= 0 && !isNull(idx)) getLong(idx) else null
    }
}
