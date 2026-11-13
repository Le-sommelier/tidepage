package app.pdfa.app.engine

import java.io.File

/**
 * Removes working copies the app no longer needs. :storage never deletes a complete document
 * (it cannot know which ones the app still uses), so the app does it here.
 *
 * Only directories that hold nothing but the imported original and its manifest are removed:
 * anything with an autosave, a staging file or any other file is kept for recovery.
 */
class WorkingCopies(workspaceRoot: File) {
    private val workDir = File(workspaceRoot, "work")

    /** Deletes the working copy of [docId] when it holds no user work. Returns true when removed. */
    fun discard(docId: String): Boolean {
        if (!DOC_ID.matches(docId)) return false
        val dir = File(workDir, docId)
        if (!dir.isDirectory || !isPristine(dir)) return false
        return dir.deleteRecursively()
    }

    /** Startup: removes every pristine copy (left by a process death). Returns how many went. */
    fun sweepPristine(): Int =
        workDir.listFiles().orEmpty().count { it.isDirectory && discard(it.name) }

    private fun isPristine(dir: File): Boolean {
        val names = dir.list()?.toSet() ?: return false
        return names.all { it in PRISTINE }
    }

    private companion object {
        val DOC_ID = Regex("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")
        val PRISTINE = setOf("original.pdf", "manifest.json")
    }
}
