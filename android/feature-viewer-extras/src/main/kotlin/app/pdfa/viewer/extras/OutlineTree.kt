package app.pdfa.viewer.extras

import app.pdfa.engine.OutlineRow

/** One node of the outline. Identity equality, so deep trees never recurse through equals or hashCode. */
class OutlineNode internal constructor(
    val path: List<Int>,
    val title: String,
    val page: Int?,
    val depth: Int,
    /** The engine's `open` flag: the default expansion until the user toggles the row. */
    val defaultOpen: Boolean,
) {
    internal val childNodes = ArrayList<OutlineNode>()
    val children: List<OutlineNode> get() = childNodes
}

/** A row as the LazyColumn shows it: already flattened, with its depth and expansion state. */
data class VisibleOutlineRow(
    val path: List<Int>,
    val title: String,
    val page: Int?,
    val depth: Int,
    val expandable: Boolean,
    val expanded: Boolean,
) {
    /** Rows without a page are shown but cannot be tapped. */
    val navigable: Boolean get() = page != null
}

/**
 * The outline as a tree, built from the flat rows the engine returns. Built without recursion,
 * so a 10k-row outline, or one 10k levels deep, is safe.
 *
 * Rules:
 *  - Rows are placed in document order (sorted by index path), whatever order they arrived in.
 *  - A row whose parent is missing (an orphan) attaches to its nearest present ancestor, or
 *    to the root when there is none.
 *  - A duplicate path keeps the first row. An empty path is skipped. Both are counted in [skipped].
 */
class OutlineTree private constructor(
    val roots: List<OutlineNode>,
    private val byPath: Map<List<Int>, OutlineNode>,
    val nodeCount: Int,
    val skipped: Int,
) {
    fun nodeAt(path: List<Int>): OutlineNode? = byPath[path]

    /**
     * The rows to show, in order. [isExpanded] decides whether a node with children shows them.
     * Iterative: no recursion over depth.
     */
    fun visible(isExpanded: (OutlineNode) -> Boolean): List<VisibleOutlineRow> {
        val out = ArrayList<VisibleOutlineRow>()
        val stack = ArrayDeque<OutlineNode>()
        for (i in roots.indices.reversed()) stack.addLast(roots[i])
        while (stack.isNotEmpty()) {
            val node = stack.removeLast()
            val expandable = node.childNodes.isNotEmpty()
            val expanded = expandable && isExpanded(node)
            out += VisibleOutlineRow(node.path, node.title, node.page, node.depth, expandable, expanded)
            if (expanded) {
                for (i in node.childNodes.indices.reversed()) stack.addLast(node.childNodes[i])
            }
        }
        return out
    }

    companion object {
        const val TITLE_CAP: Int = 500

        /** Titles longer than [TITLE_CAP] are cut and end with an ellipsis. */
        fun capTitle(title: String): String =
            if (title.length <= TITLE_CAP) title else title.take(TITLE_CAP - 1) + "…"

        val EMPTY: OutlineTree = OutlineTree(emptyList(), emptyMap(), 0, 0)

        fun build(rows: List<OutlineRow>): OutlineTree {
            var skipped = 0
            val candidates = ArrayList<OutlineRow>(rows.size)
            for (row in rows) {
                if (row.path.isEmpty()) skipped++ else candidates += row
            }
            candidates.sortWith(PathOrder)

            val byPath = HashMap<List<Int>, OutlineNode>(candidates.size * 2)
            val roots = ArrayList<OutlineNode>()
            // The ancestor chain of the row being placed: each entry is a prefix of the next.
            val chain = ArrayList<OutlineNode>()
            for (row in candidates) {
                if (byPath.containsKey(row.path)) {
                    skipped++
                    continue
                }
                while (chain.isNotEmpty() && !isPrefix(chain[chain.size - 1].path, row.path)) {
                    chain.removeAt(chain.size - 1)
                }
                val parent = chain.lastOrNull()
                val node = OutlineNode(
                    path = row.path,
                    title = capTitle(row.title),
                    page = row.page,
                    depth = if (parent == null) 0 else parent.depth + 1,
                    defaultOpen = row.open,
                )
                if (parent == null) roots += node else parent.childNodes += node
                byPath[row.path] = node
                chain += node
            }
            return OutlineTree(roots, byPath, byPath.size, skipped)
        }

        private fun isPrefix(prefix: List<Int>, path: List<Int>): Boolean {
            if (prefix.size >= path.size) return false
            for (i in prefix.indices) if (prefix[i] != path[i]) return false
            return true
        }
    }
}

/** Lexicographic order on index paths; a parent sorts before its children. */
private object PathOrder : Comparator<OutlineRow> {
    override fun compare(a: OutlineRow, b: OutlineRow): Int {
        val pa = a.path
        val pb = b.path
        val n = minOf(pa.size, pb.size)
        for (i in 0 until n) {
            val c = pa[i].compareTo(pb[i])
            if (c != 0) return c
        }
        return pa.size.compareTo(pb.size)
    }
}
