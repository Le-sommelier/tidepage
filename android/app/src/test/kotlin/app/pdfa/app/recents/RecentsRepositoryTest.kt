package app.pdfa.app.recents

import app.pdfa.app.intent.DocRef
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RecentsRepositoryTest {

    private fun doc(n: Int) = DocRef("content://com.example.provider/document/$n")

    private var now = 1_000L

    private fun repo(capacity: Int = InMemoryRecentsRepository.MAX_RECENTS) =
        InMemoryRecentsRepository(clock = { now++ }, capacity = capacity)

    @Test
    fun startsEmpty() = runTest {
        assertEquals(emptyList(), repo().observe().first())
    }

    @Test
    fun newestFirst() = runTest {
        val r = repo()
        r.add(doc(1), "one.pdf")
        r.add(doc(2), "two.pdf")
        r.add(doc(3), "three.pdf")
        assertEquals(listOf(doc(3), doc(2), doc(1)), r.observe().first().map { it.doc })
    }

    @Test
    fun readdingMovesToTheTopWithoutDuplicating() = runTest {
        val r = repo()
        r.add(doc(1), "one.pdf")
        r.add(doc(2), "two.pdf")
        r.add(doc(1), "one.pdf")
        val items = r.observe().first()
        assertEquals(listOf(doc(1), doc(2)), items.map { it.doc })
    }

    @Test
    fun readdingRefreshesTheTimestampAndName() = runTest {
        val r = repo()
        r.add(doc(1), "old name.pdf")
        r.add(doc(1), "new name.pdf")
        val only = r.observe().first().single()
        assertEquals("new name.pdf", only.name)
        assertTrue(only.lastOpenedMs > 1_000L)
    }

    @Test
    fun capacityDropsTheOldest() = runTest {
        val r = repo(capacity = 3)
        (1..5).forEach { r.add(doc(it), "$it.pdf") }
        assertEquals(listOf(doc(5), doc(4), doc(3)), r.observe().first().map { it.doc })
    }

    @Test
    fun defaultCapacityIsTwenty() = runTest {
        val r = repo()
        (1..25).forEach { r.add(doc(it), "$it.pdf") }
        val items = r.observe().first()
        assertEquals(20, InMemoryRecentsRepository.MAX_RECENTS)
        assertEquals(20, items.size)
        assertEquals(doc(25), items.first().doc)
        assertEquals(doc(6), items.last().doc)
    }

    @Test
    fun removeDropsOnlyThatDocument() = runTest {
        val r = repo()
        r.add(doc(1), "1.pdf")
        r.add(doc(2), "2.pdf")
        r.remove(doc(1))
        assertEquals(listOf(doc(2)), r.observe().first().map { it.doc })
        r.remove(doc(99)) // unknown: no-op
        assertEquals(1, r.observe().first().size)
    }

    @Test
    fun clearEmptiesTheList() = runTest {
        val r = repo()
        r.add(doc(1), "1.pdf")
        r.clear()
        assertTrue(r.observe().first().isEmpty())
    }

    @Test
    fun markUnavailableKeepsThePositionAndFlagsTheEntry() = runTest {
        val r = repo()
        r.add(doc(1), "1.pdf")
        r.add(doc(2), "2.pdf")
        r.markUnavailable(doc(1))
        val items = r.observe().first()
        assertEquals(listOf(doc(2), doc(1)), items.map { it.doc })
        assertFalse(items.last().available)
        assertTrue(items.first().available)
    }

    @Test
    fun reopeningAnUnavailableDocumentMarksItAvailableAgain() = runTest {
        val r = repo()
        r.add(doc(1), "1.pdf")
        r.markUnavailable(doc(1))
        r.add(doc(1), "1.pdf")
        assertTrue(r.observe().first().single().available)
    }

    @Test
    fun markUnavailableOfUnknownDocumentChangesNothing() = runTest {
        val r = repo()
        r.add(doc(1), "1.pdf")
        r.markUnavailable(doc(7))
        val only = r.observe().first().single()
        assertEquals(doc(1), only.doc)
        assertTrue(only.available)
    }

    @Test
    fun capacityMustBePositive() {
        assertFailsWith<IllegalArgumentException> { InMemoryRecentsRepository(capacity = 0) }
    }

    @Test
    fun displayNameComesFromTheLastDecodedSegment() {
        assertEquals("Lease.pdf", DisplayNames.fromUri("content://com.example/document/Lease.pdf"))
        assertEquals(
            "Q3+report.pdf",
            DisplayNames.fromUri("content://com.android.externalstorage.documents/document/primary%3ADocs%2FQ3+report.pdf"),
        )
        assertEquals("a+b.pdf", DisplayNames.fromUri("content://x/document/a%2Bb.pdf"))
        assertEquals("a.pdf", DisplayNames.fromUri("content://x/document/a.pdf?x=1"))
    }

    @Test
    fun displayNameNeverThrowsAndIsBounded() {
        assertEquals("bad%zz", DisplayNames.fromUri("content://x/document/bad%zz"))
        assertEquals(DisplayNames.FALLBACK, DisplayNames.fromUri("content://x/"))
        assertEquals(DisplayNames.FALLBACK, DisplayNames.fromUri(""))
        assertEquals(120, DisplayNames.fromUri("content://x/" + "a".repeat(200)).length)
    }
}
