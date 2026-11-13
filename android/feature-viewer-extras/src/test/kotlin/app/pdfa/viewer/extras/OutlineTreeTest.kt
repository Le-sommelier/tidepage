package app.pdfa.viewer.extras

import app.pdfa.engine.OutlineRow
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class OutlineTreeTest {

    private fun row(path: List<Int>, title: String = path.joinToString("."), page: Int? = 0, open: Boolean = false) =
        OutlineRow(path, title, page, open)

    private fun OutlineTree.titlesOpen(): List<String> = visible { true }.map { it.title }

    @Test
    fun `flat rows become roots at depth zero`() {
        val tree = OutlineTree.build(listOf(row(listOf(0)), row(listOf(1)), row(listOf(2))))

        assertEquals(3, tree.nodeCount)
        assertEquals(listOf(0, 0, 0), tree.visible { true }.map { it.depth })
        assertEquals(0, tree.skipped)
    }

    @Test
    fun `children attach under their parent`() {
        val tree = OutlineTree.build(
            listOf(row(listOf(0)), row(listOf(0, 0)), row(listOf(0, 1)), row(listOf(1))),
        )

        val visible = tree.visible { true }
        assertEquals(listOf("0", "0.0", "0.1", "1"), visible.map { it.title })
        assertEquals(listOf(0, 1, 1, 0), visible.map { it.depth })
    }

    @Test
    fun `rows that arrive out of order are placed in document order`() {
        val tree = OutlineTree.build(listOf(row(listOf(1)), row(listOf(0, 1)), row(listOf(0)), row(listOf(0, 0))))

        assertEquals(listOf("0", "0.0", "0.1", "1"), tree.titlesOpen())
    }

    @Test
    fun `an orphan attaches to its nearest present ancestor`() {
        val tree = OutlineTree.build(listOf(row(listOf(0)), row(listOf(0, 1, 2), title = "deep")))

        val visible = tree.visible { true }
        assertEquals(listOf("0", "deep"), visible.map { it.title })
        assertEquals(listOf(0, 1), visible.map { it.depth })
    }

    @Test
    fun `an orphan with no present ancestor becomes a root`() {
        val tree = OutlineTree.build(listOf(row(listOf(3, 4), title = "lonely")))

        val only = tree.visible { true }.single()
        assertEquals("lonely", only.title)
        assertEquals(0, only.depth)
    }

    @Test
    fun `a duplicate path keeps the first row and is counted as skipped`() {
        val tree = OutlineTree.build(listOf(row(listOf(0), title = "first"), row(listOf(0), title = "second")))

        assertEquals(listOf("first"), tree.titlesOpen())
        assertEquals(1, tree.skipped)
        assertEquals(1, tree.nodeCount)
    }

    @Test
    fun `an empty path is skipped`() {
        val tree = OutlineTree.build(listOf(row(emptyList(), title = "bad"), row(listOf(0), title = "good")))

        assertEquals(listOf("good"), tree.titlesOpen())
        assertEquals(1, tree.skipped)
    }

    @Test
    fun `a collapsed node hides its children and reports itself expandable`() {
        val tree = OutlineTree.build(listOf(row(listOf(0)), row(listOf(0, 0))))

        val visible = tree.visible { false }

        assertEquals(1, visible.size)
        assertTrue(visible[0].expandable)
        assertFalse(visible[0].expanded)
    }

    @Test
    fun `the engine open flag is the default expansion`() {
        val tree = OutlineTree.build(
            listOf(
                row(listOf(0), open = true),
                row(listOf(0, 0)),
                row(listOf(1), open = false),
                row(listOf(1, 0)),
            ),
        )

        val byDefault = tree.visible { it.defaultOpen }

        assertEquals(listOf("0", "0.0", "1"), byDefault.map { it.title })
    }

    @Test
    fun `a row without a page is shown but not navigable`() {
        val tree = OutlineTree.build(listOf(row(listOf(0), page = null)))

        val only = tree.visible { true }.single()
        assertNull(only.page)
        assertFalse(only.navigable)
    }

    @Test
    fun `a row with a page is navigable`() {
        val tree = OutlineTree.build(listOf(row(listOf(0), page = 4)))

        assertTrue(tree.visible { true }.single().navigable)
        assertEquals(4, tree.nodeAt(listOf(0))!!.page)
    }

    @Test
    fun `node lookup by path finds the node and nothing else`() {
        val tree = OutlineTree.build(listOf(row(listOf(0)), row(listOf(0, 1))))

        assertEquals(listOf(0, 1), tree.nodeAt(listOf(0, 1))!!.path)
        assertNull(tree.nodeAt(listOf(0, 2)))
    }

    @Test
    fun `ten thousand flat rows build and flatten`() {
        val rows = (0 until 10_000).map { row(listOf(it), title = "t$it") }

        val tree = OutlineTree.build(rows)

        assertEquals(10_000, tree.nodeCount)
        val visible = tree.visible { true }
        assertEquals(10_000, visible.size)
        assertEquals("t9999", visible.last().title)
    }

    @Test
    fun `ten thousand rows in reverse order keep document order`() {
        val rows = (9_999 downTo 0).map { row(listOf(it), title = "t$it") }

        val visible = OutlineTree.build(rows).visible { true }

        assertEquals("t0", visible.first().title)
        assertEquals("t9999", visible.last().title)
    }

    @Test
    fun `a path ten thousand levels deep is handled without recursion`() {
        val deep = row(List(10_000) { 0 }, title = "deep")

        val tree = OutlineTree.build(listOf(deep))

        val only = tree.visible { true }.single()
        assertEquals("deep", only.title)
        assertEquals(0, only.depth)
    }

    @Test
    fun `a chain of nested rows keeps its depths and flattens without recursion`() {
        val rows = (1..1_500).map { k -> row(List(k) { 0 }, title = "c$k") }

        val visible = OutlineTree.build(rows).visible { true }

        assertEquals(1_500, visible.size)
        assertEquals(1_499, visible.last().depth)
        assertEquals("c1500", visible.last().title)
    }

    @Test
    fun `collapsing the top of a deep chain hides the whole chain`() {
        val rows = (1..1_500).map { k -> row(List(k) { 0 }, title = "c$k") }
        val tree = OutlineTree.build(rows)

        val visible = tree.visible { it.path.size > 1 }

        assertEquals(1, visible.size)
        assertTrue(visible[0].expandable)
    }

    @Test
    fun `a 10 MB title is cut to the title cap with an ellipsis`() {
        val huge = "x".repeat(10 * 1024 * 1024)
        val tree = OutlineTree.build(listOf(row(listOf(0), title = huge)))

        val title = tree.visible { true }.single().title
        assertEquals(OutlineTree.TITLE_CAP, title.length)
        assertTrue(title.endsWith("…"))
    }

    @Test
    fun `a title at the cap is kept whole`() {
        val exact = "y".repeat(OutlineTree.TITLE_CAP)
        assertEquals(exact, OutlineTree.capTitle(exact))
    }
}
