package app.pdfa.app.viewer

import app.pdfa.engine.DocHandle
import app.pdfa.engine.EngineApi
import app.pdfa.engine.EngineEvent
import app.pdfa.engine.fake.FakeEngine
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals

class DocEngineTest {
    private class Scripted(override val events: Flow<EngineEvent>, fake: FakeEngine = FakeEngine()) : EngineApi by fake

    @Test
    fun eventsFromBeforeTheViewerExistedAreHidden() = runBlocking {
        val old = EngineEvent.SafeModeRestarted(atEpochMs = 999, reason = "old")
        val oldLost = EngineEvent.EditsLost(DocHandle(1), atEpochMs = 999, originalRestored = true)
        val fresh = EngineEvent.SafeModeRestarted(atEpochMs = 1_000, reason = "new")
        val freshLost = EngineEvent.EditsLost(DocHandle(2), atEpochMs = 1_500, originalRestored = false)
        val progress = EngineEvent.Progress("op", 0.5f)
        val engine = DocEngine(Scripted(flowOf(old, oldLost, fresh, progress, freshLost)), { null }, sinceEpochMs = 1_000)

        assertEquals(listOf(fresh, progress, freshLost), engine.events.toList())
    }

    @Test
    fun aNonRemoteEngineIsOpenedPlainly() = runBlocking {
        val fake = FakeEngine()
        val engine = DocEngine(fake, { "some-id" }, sinceEpochMs = 0)
        val handle = engine.open(FakeEngine.fakePdf(pageCount = 2, text = listOf("a", "b")))
        assertEquals(2, engine.pageInfos(handle).size)
    }
}
