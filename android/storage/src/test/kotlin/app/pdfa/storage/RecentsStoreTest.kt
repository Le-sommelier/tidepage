package app.pdfa.storage

import java.io.File
import java.util.concurrent.atomic.AtomicLong
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.Rule
import org.junit.rules.TemporaryFolder

class RecentsStoreTest {

    @get:Rule val tmp = TemporaryFolder()

    private val clock = AtomicLong(1_000L)
    private val streams = FakeUriStreams()

    private fun dir(): File = File(tmp.root, "store").apply { mkdirs() }

    private fun store(root: File = dir(), fs: StorageFs = RealFs): RecentsStore =
        RecentsStore(root, streams, { clock.incrementAndGet() }, fs)

    private fun recordsFile(root: File = dir()): File = File(root, RecentsStore.FILE_NAME)

    @Test
    fun mostRecentlyOpenedComesFirst() {
        val s = store()
        s.recordOpened("content://p/a", "a.pdf")
        s.recordOpened("content://p/b", "b.pdf")
        s.recordOpened("content://p/c", "c.pdf")
        assertEquals(listOf("c.pdf", "b.pdf", "a.pdf"), s.list().map { it.displayName })
    }

    @Test
    fun twoUrisForOneDocumentShareARow() {
        streams.aliases["content://p/doc/1"] = "provider|1"
        streams.aliases["content://q/alias/9"] = "provider|1"
        val s = store()
        s.recordOpened("content://p/doc/1", "one.pdf")
        s.recordOpened("content://q/alias/9", "one.pdf")
        assertEquals(1, s.list().size)
        assertEquals("provider|1", s.list().single().docKey)
        assertEquals("content://q/alias/9", s.list().single().uri)
    }

    @Test
    fun aNullDocumentKeyFallsBackToTheRawUri() {
        streams.documentKeyNull = true
        val s = store()
        s.recordOpened("content://p/a", "a.pdf")
        s.recordOpened("content://p/a", "a again.pdf")
        s.recordOpened("content://p/b", "b.pdf")
        assertEquals(listOf("content://p/b", "content://p/a"), s.list().map { it.docKey })
    }

    @Test
    fun theListIsCappedAtFiftyKeepingTheNewest() {
        val s = store()
        repeat(55) { s.recordOpened("content://p/$it", "f$it.pdf") }
        assertEquals(RecentsCodec.MAX_ENTRIES, s.list().size)
        assertEquals("f54.pdf", s.list().first().displayName)
        assertNull(s.get("content://p/4"))
        assertNotNull(s.get("content://p/5"))
    }

    @Test
    fun reopeningMovesARowToTheFrontAndUpdatesItsFields() {
        val s = store()
        s.recordOpened("content://p/a", "a.pdf", pageCount = 3, sizeBytes = 10L, thumbnailRef = "t1")
        s.recordOpened("content://p/b", "b.pdf")
        s.recordOpened("content://p/a", "renamed.pdf", pageCount = 4)
        val first = s.list().first()
        assertEquals("renamed.pdf", first.displayName)
        assertEquals(4, first.pageCount)
        assertNull(first.sizeBytes)
        assertEquals(2, s.list().size)
    }

    @Test
    fun rowsPersistAcrossInstances() {
        val root = dir()
        store(root).recordOpened("content://p/a", "a.pdf", pageCount = 2, sizeBytes = 99L, thumbnailRef = "thumb-a")
        val reopened = store(root)
        val row = assertNotNull(reopened.list().singleOrNull())
        assertEquals("a.pdf", row.displayName)
        assertEquals(2, row.pageCount)
        assertEquals(99L, row.sizeBytes)
        assertEquals("thumb-a", row.thumbnailRef)
        assertFalse(reopened.loadedCorrupt)
    }

    @Test
    fun aMissingFileIsAnEmptyListNotCorrupt() {
        val s = store()
        assertTrue(s.list().isEmpty())
        assertFalse(s.loadedCorrupt)
    }

    @Test
    fun aCorruptFileIsEmptyFlaggedAndMovedAsideWithItsContentKept() {
        val root = dir()
        recordsFile(root).writeText("{not json")
        val s = store(root)
        assertTrue(s.loadedCorrupt)
        assertTrue(s.list().isEmpty())
        assertFalse(recordsFile(root).exists())
        val aside = root.listFiles().orEmpty().single { it.name.startsWith("recents.json.corrupt-") }
        assertEquals("{not json", aside.readText())
    }

    @Test
    fun writesWorkAgainAfterACorruptFileWasMovedAside() {
        val root = dir()
        recordsFile(root).writeText("[]")
        store(root).recordOpened("content://p/a", "a.pdf")
        val reopened = store(root)
        assertFalse(reopened.loadedCorrupt)
        assertEquals("a.pdf", reopened.list().single().displayName)
    }

    @Test
    fun ifTheCorruptFileCannotBeMovedAsideWritesAreRefusedAndItIsKept() {
        val root = dir()
        recordsFile(root).writeText("garbage")
        val fs = FaultFs().apply { failRenameNoReplace = true }
        val s = store(root, fs)
        assertTrue(s.loadedCorrupt)
        val result = s.recordOpened("content://p/a", "a.pdf")
        assertIs<RecentsResult.Failed>(result)
        assertEquals("garbage", recordsFile(root).readText())
    }

    @Test
    fun anOversizedFileIsTreatedAsCorrupt() {
        val root = dir()
        recordsFile(root).writeBytes(ByteArray((RecentsCodec.MAX_FILE_BYTES + 1).toInt()) { 0x20 })
        assertTrue(store(root).loadedCorrupt)
    }

    @Test
    fun deeplyNestedInputIsCorruptNotAStackProblem() {
        val root = dir()
        val nested = "{".repeat(200) + "}".repeat(200)
        recordsFile(root).writeText("{\"version\":1,\"entries\":{\"0\":{\"docKey\":$nested}}}")
        assertTrue(store(root).loadedCorrupt)
    }

    @Test
    fun invalidUtf8IsCorrupt() {
        val root = dir()
        recordsFile(root).writeBytes(byteArrayOf('{'.code.toByte(), 0xFF.toByte(), '}'.code.toByte()))
        assertTrue(store(root).loadedCorrupt)
    }

    @Test
    fun aWrongFieldTypeIsCorrupt() {
        val root = dir()
        recordsFile(root).writeText(
            "{\"version\":1,\"entries\":{\"0\":{\"docKey\":\"k\",\"uri\":\"u\",\"displayName\":null," +
                "\"lastOpenedAt\":1,\"pageCount\":\"three\",\"sizeBytes\":null,\"thumbnailRef\":null}}}",
        )
        assertTrue(store(root).loadedCorrupt)
    }

    @Test
    fun unicodeNamesRoundTrip() {
        val root = dir()
        val names = listOf("東京の地図.pdf", "🗼 Tokyo 🇯🇵.pdf", "Ｃａｆé́ menu.pdf", "שלום.pdf")
        val s = store(root)
        names.forEachIndexed { i, n -> s.recordOpened("content://p/$i", n) }
        assertEquals(names.reversed(), store(root).list().map { it.displayName })
    }

    @Test
    fun unpairedSurrogatesAreReplacedRatherThanRejected() {
        val root = dir()
        val s = store(root)
        assertEquals(RecentsResult.Written, s.recordOpened("content://p/bad", "bad\uD800name.pdf"))
        assertEquals("bad�name.pdf", store(root).list().single().displayName)
    }

    @Test
    fun aFailedAtomicWriteKeepsThePreviousFileAndRollsBackMemory() {
        val root = dir()
        val fs = FaultFs()
        val s = store(root, fs)
        s.recordOpened("content://p/a", "a.pdf")
        fs.failWriteOn = "recents.json.part"
        val result = s.recordOpened("content://p/b", "b.pdf")
        assertIs<RecentsResult.Failed>(result)
        assertEquals(listOf("a.pdf"), s.list().map { it.displayName })
        assertFalse(File(root, "recents.json.part").exists())
        assertEquals(listOf("a.pdf"), store(root).list().map { it.displayName })
    }

    @Test
    fun removeClearAndMarkUnavailablePersist() {
        val root = dir()
        val s = store(root)
        s.recordOpened("content://p/a", "a.pdf")
        s.recordOpened("content://p/b", "b.pdf")
        s.recordOpened("content://p/c", "c.pdf")
        assertEquals(RecentsResult.Written, s.remove("content://p/b"))
        assertEquals(RecentsResult.Written, s.markUnavailable("content://p/a"))
        val reopened = store(root)
        assertEquals(listOf("c.pdf", "a.pdf"), reopened.list().map { it.displayName })
        assertTrue(assertNotNull(reopened.get("content://p/a")).unavailable)
        assertEquals(RecentsResult.Written, reopened.clear())
        assertTrue(store(root).list().isEmpty())
    }

    @Test
    fun markingAnAbsentOrFlaggedRowIsUnchanged() {
        val s = store()
        assertEquals(RecentsResult.Unchanged, s.markUnavailable("content://p/none"))
        s.recordOpened("content://p/a", "a.pdf")
        assertEquals(RecentsResult.Written, s.markUnavailable("content://p/a"))
        assertEquals(RecentsResult.Unchanged, s.markUnavailable("content://p/a"))
        assertEquals(RecentsResult.Unchanged, s.remove("content://p/none"))
    }

    @Test
    fun reopeningAnUnavailableRowClearsTheFlag() {
        val s = store()
        s.recordOpened("content://p/a", "a.pdf")
        s.markUnavailable("content://p/a")
        s.recordOpened("content://p/a", "a.pdf")
        assertFalse(s.get("content://p/a")!!.unavailable)
    }

    private fun row(order: Long?, key: String, name: String): String {
        val orderPart = if (order == null) "" else "\"order\":$order,"
        return "{$orderPart\"docKey\":\"$key\",\"uri\":\"u-$key\",\"displayName\":\"$name\"," +
            "\"lastOpenedAt\":1,\"pageCount\":null,\"sizeBytes\":null,\"thumbnailRef\":null,\"unavailable\":false}"
    }

    @Test
    fun rowOrderComesFromTheOrderFieldNotFromTheKeyNames() {
        val root = dir()
        recordsFile(root).writeText(
            "{\"version\":1,\"entries\":{" +
                "\"0\":${row(2, "c", "C")}," +
                "\"1\":${row(0, "a", "A")}," +
                "\"2\":${row(1, "b", "B")}}}",
        )
        val s = store(root)
        assertFalse(s.loadedCorrupt)
        assertEquals(listOf("A", "B", "C"), s.list().map { it.displayName })
        // The rewritten file keeps the same sequence after a reload with fresh keys.
        s.recordOpened("content://p/d", "D")
        assertEquals(listOf("D", "A", "B", "C"), store(root).list().map { it.displayName })
    }

    @Test
    fun aRowWithoutAnOrderIsCorrupt() {
        val root = dir()
        recordsFile(root).writeText("{\"version\":1,\"entries\":{\"0\":${row(null, "a", "A")}}}")
        assertTrue(store(root).loadedCorrupt)
    }

    @Test
    fun concurrentRecordingKeepsTheFileValidAndCapped() {
        val root = dir()
        val s = store(root)
        val threads = (0 until 8).map { t ->
            Thread {
                repeat(10) { i -> s.recordOpened("content://p/$t-$i", "t$t-$i.pdf") }
            }
        }
        threads.forEach { it.start() }
        threads.forEach { it.join() }
        assertEquals(RecentsCodec.MAX_ENTRIES, s.list().size)
        val reopened = store(root)
        assertFalse(reopened.loadedCorrupt)
        assertEquals(s.list().map { it.docKey }, reopened.list().map { it.docKey })
        assertEquals(s.list().size, s.list().map { it.docKey }.toSet().size)
    }
}
