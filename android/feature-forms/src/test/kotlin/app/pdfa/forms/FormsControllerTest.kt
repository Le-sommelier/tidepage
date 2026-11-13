package app.pdfa.forms

import app.pdfa.engine.DocHandle
import app.pdfa.engine.EditState
import app.pdfa.engine.EngineApi
import app.pdfa.engine.EngineError
import app.pdfa.engine.FieldKind
import app.pdfa.engine.FieldOption
import app.pdfa.engine.FieldWidget
import app.pdfa.engine.Rect
import app.pdfa.engine.fake.FakeEngine
import app.pdfa.engine.fake.FakeField
import app.pdfa.engine.fake.FakePage
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class FormsControllerTest {
    private val engine = FakeEngine()
    private var edits = 0

    private fun w(page: Int, l: Float, t: Float, r: Float = l + 100f, b: Float = t + 20f, on: String? = null) =
        FieldWidget(page, Rect(l, t, r, b), on)

    // Declared out of reading order on purpose.
    private val seed = listOf(
        FakeField("sig", FieldKind.SIGNATURE, widgets = listOf(w(1, 50f, 50f)), required = true),
        FakeField("country", FieldKind.COMBO, options = listOf(FieldOption("fr", "France"), FieldOption("de", "Germany")), widgets = listOf(w(0, 50f, 300f))),
        FakeField("notes", FieldKind.TEXT, multiline = true, widgets = listOf(w(0, 50f, 140f, b = 200f))),
        FakeField("name", FieldKind.TEXT, required = true, widgets = listOf(w(0, 50f, 100f))),
        FakeField("id", FieldKind.TEXT, value = listOf("A-1"), readOnly = true, widgets = listOf(w(0, 50f, 120f))),
        FakeField("code", FieldKind.TEXT, maxLen = 5, widgets = listOf(w(0, 200f, 100f))),
        FakeField("subscribe", FieldKind.CHECK_BOX, widgets = listOf(w(0, 50f, 220f, r = 70f, b = 240f, on = "Yes"))),
        FakeField("plan", FieldKind.RADIO, widgets = listOf(w(0, 50f, 260f, r = 70f, b = 280f, on = "basic"), w(0, 100f, 260f, r = 120f, b = 280f, on = "pro"))),
        FakeField("tags", FieldKind.LIST, options = listOf(FieldOption("a", "A"), FieldOption("b", "B"), FieldOption("c", "C")), widgets = listOf(w(0, 50f, 320f, b = 380f))),
        FakeField("go", FieldKind.PUSH_BUTTON, widgets = listOf(w(0, 50f, 400f))),
        FakeField("orphan", FieldKind.TEXT),
    )

    private var handle = DocHandle(0)
    private fun page() = FakePage(width = 612f, height = 792f)

    private suspend fun TestScope.setup(fields: List<FakeField> = seed): FormsController {
        handle = engine.open(FakeEngine.fakePdf(listOf(page(), page()), fields = fields))
        val c = FormsController(engine, handle, { change -> edits++; engine.change(handle) }, UnconfinedTestDispatcher(testScheduler))
        assertTrue(c.load())
        return c
    }

    private fun FormsController.f(name: String) = state.value.field(name)!!
    private fun FormsController.problem() = state.value.problem

    private suspend fun <T> EngineApi.listValue(name: String, pick: (app.pdfa.engine.FieldInfo) -> T): T =
        pick(listFields(handle).first { it.name == name })

    // ---- load / order ----

    @Test
    fun load_ordersByPageTopLeft_noWidgetLast() = runTest {
        val c = setup()
        // name(0,100,50) code(0,100,200) id(0,120) notes(0,140) subscribe(220) plan(260) country(300) tags(320) go(400) sig(page1) orphan(none)
        assertEquals(listOf("name", "code", "id", "notes", "subscribe", "plan", "country", "tags", "go", "sig", "orphan"), c.state.value.fields.map { it.name })
        assertTrue(c.state.value.loaded)
        assertNull(c.state.value.currentName)
    }

    @Test
    fun readingOrder_tiesBrokenByLeftThenName() {
        val f = listOf(
            FakeField("b", FieldKind.TEXT).copy(widgets = listOf(w(0, 10f, 10f))),
            FakeField("a", FieldKind.TEXT).copy(widgets = listOf(w(0, 10f, 10f))),
            FakeField("c", FieldKind.TEXT).copy(widgets = listOf(w(0, 5f, 10f))),
        )
        val infos = f.map { app.pdfa.engine.FieldInfo(it.name, it.kind, it.value, it.options, it.readOnly, it.required, it.multiline, it.maxLen, it.widgets) }
        assertEquals(listOf("c", "a", "b"), FormsController.inReadingOrder(infos).map { it.name })
    }

    // ---- set / clear every kind ----

    @Test
    fun text_setAndClear() = runTest {
        val c = setup()
        assertTrue(c.setText("name", "Ada"))
        assertEquals(listOf("Ada"), c.f("name").value)
        assertTrue(c.state.value.canUndo)
        assertTrue(c.clearField("name"))
        assertTrue(c.f("name").isEmpty)
    }

    @Test
    fun checkBox_setAndClear() = runTest {
        val c = setup()
        assertTrue(c.setChecked("subscribe", true))
        assertTrue(c.f("subscribe").isChecked)
        assertTrue(c.clearField("subscribe"))
        assertFalse(c.f("subscribe").isChecked)
        assertTrue(c.setChecked("subscribe", true))
        assertTrue(c.setChecked("subscribe", false))
        assertTrue(c.f("subscribe").isEmpty)
    }

    @Test
    fun radio_setSwitchAndClear() = runTest {
        val c = setup()
        assertEquals(listOf("basic", "pro"), c.f("plan").radioStates)
        assertTrue(c.setRadio("plan", "pro"))
        assertEquals("pro", c.f("plan").selectedRadio)
        assertTrue(c.setRadio("plan", "basic"))
        assertEquals("basic", c.f("plan").selectedRadio)
        assertTrue(c.clearField("plan"))
        assertNull(c.f("plan").selectedRadio)
        assertTrue(c.setRadio("plan", "pro"))
        assertTrue(c.setRadio("plan", null))
        assertTrue(c.f("plan").isEmpty)
    }

    @Test
    fun radio_unknownStateRefusedWithoutEngine() = runTest {
        val c = setup()
        val before = edits
        assertFalse(c.setRadio("plan", "gold"))
        assertEquals(FieldIssue.UnknownOption, c.state.value.validation["plan"])
        assertEquals(before, edits)
    }

    @Test
    fun combo_setClearAndOnlyOne() = runTest {
        val c = setup()
        assertTrue(c.setChoice("country", listOf("de")))
        assertEquals(listOf("de"), c.f("country").value)
        val before = edits
        assertFalse(c.setChoice("country", listOf("fr", "de")))
        assertEquals(FieldIssue.MultipleValues, c.state.value.validation["country"])
        assertFalse(c.setChoice("country", listOf("zz")))
        assertEquals(FieldIssue.UnknownOption, c.state.value.validation["country"])
        assertEquals(before, edits)
        assertEquals(listOf("de"), c.f("country").value)
        assertTrue(c.clearField("country"))
        assertTrue(c.f("country").isEmpty)
    }

    @Test
    fun list_multiSelectAndClear() = runTest {
        val c = setup()
        assertTrue(c.setChoice("tags", listOf("a", "c", "a")))
        assertEquals(listOf("a", "c"), c.f("tags").value)
        assertTrue(c.clearField("tags"))
        assertTrue(c.f("tags").value.isEmpty())
    }

    @Test
    fun acceptedValueClearsEarlierValidationMessage() = runTest {
        val c = setup()
        assertFalse(c.setText("code", "toolong"))
        assertTrue("code" in c.state.value.validation)
        assertTrue(c.setText("code", "ok"))
        assertTrue(c.state.value.validation.isEmpty())
    }

    // ---- text validation ----

    @Test
    fun maxLen_boundaryAcceptedOverRefused() = runTest {
        val c = setup()
        assertTrue(c.setText("code", "12345"))
        val before = edits
        assertFalse(c.setText("code", "123456"))
        assertEquals(FieldIssue.TooLong(5), c.state.value.validation["code"])
        assertEquals(FormProblem.INVALID_VALUE, c.problem())
        assertEquals(before, edits)
        assertEquals(listOf("12345"), c.f("code").value)
    }

    @Test
    fun newlines_strippedInSingleLine_keptInMultiline() = runTest {
        val c = setup()
        assertTrue(c.setText("name", "a\r\nb\nc\rd"))
        assertEquals(listOf("a b c d"), c.f("name").value)
        assertTrue(c.setText("notes", "line1\nline2"))
        assertEquals(listOf("line1\nline2"), c.f("notes").value)
    }

    @Test
    fun maxLen_countsAfterNewlineStripping() = runTest {
        val c = setup()
        assertFalse(c.setText("code", "1\n2345")) // "1 2345" is 6
        assertTrue(c.setText("code", "1\n234")) // "1 234" is 5
    }

    // ---- refusals ----

    @Test
    fun kindMismatch_refusedLocally() = runTest {
        val c = setup()
        val before = edits
        assertFalse(c.setChecked("name", true)); assertEquals(FormProblem.WRONG_KIND, c.problem())
        assertFalse(c.setText("subscribe", "x")); assertEquals(FormProblem.WRONG_KIND, c.problem())
        assertFalse(c.setRadio("country", "fr")); assertEquals(FormProblem.WRONG_KIND, c.problem())
        assertFalse(c.setChoice("plan", listOf("basic"))); assertEquals(FormProblem.WRONG_KIND, c.problem())
        assertEquals(before, edits)
    }

    @Test
    fun readOnly_everyEditRefused() = runTest {
        val c = setup()
        val before = edits
        assertFalse(c.setText("id", "new")); assertEquals(FormProblem.READ_ONLY, c.problem())
        assertFalse(c.clearField("id")); assertEquals(FormProblem.READ_ONLY, c.problem())
        assertFalse(c.clearFields(listOf("id"))); assertEquals(FormProblem.READ_ONLY, c.problem())
        assertEquals(before, edits)
        assertEquals(listOf("A-1"), c.f("id").value)
    }

    @Test
    fun signatureAndButton_refused() = runTest {
        val c = setup()
        val before = edits
        assertFalse(c.setText("sig", "x")); assertEquals(FormProblem.NOT_FILLABLE, c.problem())
        assertFalse(c.clearField("sig")); assertEquals(FormProblem.NOT_FILLABLE, c.problem())
        assertFalse(c.setChoice("go", listOf("x"))); assertEquals(FormProblem.NOT_FILLABLE, c.problem())
        assertFalse(c.clearFields(listOf("sig", "go"))); assertEquals(FormProblem.NOT_FILLABLE, c.problem())
        assertEquals(before, edits)
    }

    @Test
    fun unknownField_refused() = runTest {
        val c = setup()
        assertFalse(c.setText("nope", "x")); assertEquals(FormProblem.NO_SUCH_FIELD, c.problem())
        assertFalse(c.clearFields(listOf("nope"))); assertEquals(FormProblem.NO_SUCH_FIELD, c.problem())
        assertFalse(c.select("nope"))
        assertEquals(0, edits)
    }

    @Test
    fun editableFlags() = runTest {
        val c = setup()
        assertTrue(c.f("name").isEditable)
        assertFalse(c.f("id").isEditable)
        assertFalse(c.f("sig").isEditable)
        assertFalse(c.f("go").isEditable)
    }

    // ---- required ----

    @Test
    fun missingRequired_flagsEmptyEditableOnly() = runTest {
        val c = setup()
        assertEquals(listOf("name"), c.state.value.missingRequired) // the required signature cannot be filled here
        c.setText("name", "   ")
        assertEquals(listOf("name"), c.state.value.missingRequired) // blank is empty
        c.setText("name", "Ada")
        assertTrue(c.state.value.missingRequired.isEmpty())
        c.clearField("name")
        assertEquals(listOf("name"), c.state.value.missingRequired)
    }

    @Test
    fun requiredCheckBox_offValueCountsAsEmpty() {
        fun info(v: List<String>) = app.pdfa.engine.FieldInfo("c", FieldKind.CHECK_BOX, v, emptyList(), false, true, false, null, emptyList())
        assertTrue(info(listOf("Off")).isMissingRequired())
        assertTrue(info(emptyList()).isMissingRequired())
        assertFalse(info(listOf("Yes")).isMissingRequired())
        assertFalse(info(listOf("Off")).isChecked)
    }

    // ---- clear form ----

    @Test
    fun clearForm_resetsFilledFields_readOnlyUntouched() = runTest {
        val c = setup()
        c.setText("name", "Ada"); c.setChecked("subscribe", true); c.setRadio("plan", "pro"); c.setChoice("country", listOf("fr"))
        assertTrue(c.clearForm())
        for (n in listOf("name", "subscribe", "plan", "country")) assertTrue(c.f(n).isEmpty, n)
        assertEquals(listOf("A-1"), c.f("id").value)
        assertTrue(c.state.value.validation.isEmpty())
    }

    @Test
    fun clearFields_onlyNamed_skipsReadOnly() = runTest {
        val c = setup()
        c.setText("name", "Ada"); c.setText("code", "x")
        assertTrue(c.clearFields(listOf("name", "id", "sig", "nope")))
        assertTrue(c.f("name").isEmpty)
        assertEquals(listOf("x"), c.f("code").value)
        assertEquals(listOf("A-1"), c.f("id").value)
    }

    @Test
    fun clearForm_beforeLoad_doesNothing() = runTest {
        val h = engine.open(FakeEngine.fakePdf(listOf(page()), fields = seed.drop(1).take(2)))
        val c = FormsController(engine, h, { block -> edits++; engine.block(h) }, UnconfinedTestDispatcher(testScheduler))
        assertFalse(c.clearForm())
        assertEquals(0, edits)
    }

    // ---- navigation ----

    @Test
    fun nextPrevious_skipReadOnlySignatureButtonAndNoWidget() = runTest {
        val c = setup()
        assertTrue(c.next()); assertEquals("name", c.state.value.currentName)
        assertTrue(c.next()); assertEquals("code", c.state.value.currentName)
        assertTrue(c.next()); assertEquals("notes", c.state.value.currentName) // id is read-only
        assertTrue(c.next()); assertEquals("subscribe", c.state.value.currentName)
        assertTrue(c.next()); assertEquals("plan", c.state.value.currentName)
        assertTrue(c.next()); assertEquals("country", c.state.value.currentName)
        assertTrue(c.next()); assertEquals("tags", c.state.value.currentName)
        assertFalse(c.state.value.hasNext)
        assertFalse(c.next()) // go, sig, orphan skipped; no wrap
        assertEquals("tags", c.state.value.currentName)
        assertTrue(c.previous()); assertEquals("country", c.state.value.currentName)
    }

    @Test
    fun previous_fromNothingGoesToLastFillable_andStopsAtStart() = runTest {
        val c = setup()
        assertTrue(c.previous()); assertEquals("tags", c.state.value.currentName)
        c.select("name")
        assertFalse(c.state.value.hasPrevious)
        assertFalse(c.previous())
        assertEquals("name", c.state.value.currentName)
    }

    @Test
    fun next_fromReadOnlySelection_movesToFollowingFillable() = runTest {
        val c = setup()
        assertTrue(c.select("id"))
        assertTrue(c.next())
        assertEquals("notes", c.state.value.currentName)
        c.select("id")
        c.previous()
        assertEquals("code", c.state.value.currentName)
    }

    @Test
    fun select_anyField_includingReadOnly() = runTest {
        val c = setup()
        assertTrue(c.select("sig"))
        assertEquals("sig", c.state.value.current?.name)
        assertEquals(c.state.value.fields.indexOfFirst { it.name == "sig" }, c.state.value.currentIndex)
        c.deselect()
        assertNull(c.state.value.current)
        assertEquals(-1, c.state.value.currentIndex)
    }

    // ---- hitTest ----

    @Test
    fun hitTest_edgesInsideLeftTopOutsideRightBottom() = runTest {
        val c = setup()
        // name: left 50 top 100 right 150 bottom 120
        assertEquals("name", c.hitTest(0, 50f, 100f))
        assertEquals("name", c.hitTest(0, 149.9f, 119.9f))
        assertNull(c.hitTest(0, 49.9f, 110f))
        assertNull(c.hitTest(0, 100f, 99.9f))
        assertEquals("id", c.hitTest(0, 100f, 120f)) // id starts where name ends
        assertNull(c.hitTest(0, 150f, 110f)) // name ends at 150, code starts at 200
        assertEquals("code", c.hitTest(0, 200f, 100f))
    }

    @Test
    fun hitTest_pageAndKinds() = runTest {
        val c = setup()
        assertEquals("sig", c.hitTest(1, 60f, 60f))
        assertNull(c.hitTest(0, 60f, 60f))
        assertEquals("id", c.hitTest(0, 60f, 130f))
        assertEquals("go", c.hitTest(0, 60f, 410f))
        assertNull(c.hitTest(5, 60f, 110f))
    }

    @Test
    fun hitTest_overlap_smallerWins() = runTest {
        val c = setup(listOf(
            FakeField("big", FieldKind.TEXT, widgets = listOf(w(0, 0f, 0f, 200f, 200f))),
            FakeField("small", FieldKind.CHECK_BOX, widgets = listOf(w(0, 10f, 10f, 30f, 30f, on = "Yes"))),
        ))
        assertEquals("small", c.hitTest(0, 15f, 15f))
        assertEquals("big", c.hitTest(0, 100f, 100f))
    }

    // ---- undo / reload ----

    @Test
    fun reloadAfterUndo_refreshesValuesAndKeepsCurrent() = runTest {
        val c = setup()
        c.select("name")
        c.setText("name", "Ada")
        assertEquals(listOf("Ada"), c.f("name").value)
        val rev = c.state.value.revision
        engine.undo(handle)
        assertEquals(listOf("Ada"), c.f("name").value) // stale until reload
        assertTrue(c.load())
        assertTrue(c.f("name").isEmpty)
        assertEquals("name", c.state.value.currentName)
        assertFalse(c.state.value.canUndo)
        assertTrue(c.state.value.canRedo)
        assertTrue(c.state.value.revision > rev)
    }

    // ---- engine errors ----

    @Test
    fun invalidArgument_fromEngine_becomesState_andResyncs() = runTest {
        val c = setup()
        engine.failNext(EngineError.InvalidArgument("secret typed text"), "setField")
        assertFalse(c.setText("name", "Ada"))
        assertEquals(FormProblem.INVALID_VALUE, c.problem())
        assertFalse(c.state.value.busy)
        assertTrue(c.setText("name", "Ada")) // recovers
        assertNull(c.problem())
    }

    @Test
    fun notPermitted_becomesState() = runTest {
        val c = setup()
        engine.failNext(EngineError.NotPermitted("no"), "setField")
        assertFalse(c.setChecked("subscribe", true))
        assertEquals(FormProblem.NOT_PERMITTED, c.problem())
        assertTrue(c.f("subscribe").isEmpty)
    }

    @Test
    fun otherEngineError_isActionFailed() = runTest {
        val c = setup()
        engine.failNext(EngineError.Io("disk"), "resetForm")
        assertFalse(c.clearForm())
        assertEquals(FormProblem.ACTION_FAILED, c.problem())
    }

    @Test
    fun engineDied_becomesRestarted_noCrash() = runTest {
        val c = setup()
        engine.failNext(EngineError.EngineDied("gone"), "setField")
        assertFalse(c.setText("name", "Ada"))
        assertEquals(FormProblem.ENGINE_RESTARTED, c.problem())
        assertFalse(c.state.value.busy)
        assertEquals(11, c.state.value.fields.size) // last known fields stay shown
    }

    @Test
    fun timeout_becomesRestarted() = runTest {
        val c = setup()
        engine.failNext(EngineError.Timeout("slow"), "setField")
        assertFalse(c.setText("name", "Ada"))
        assertEquals(FormProblem.ENGINE_RESTARTED, c.problem())
    }

    @Test
    fun errorDuringLoad_isState() = runTest {
        val h = engine.open(FakeEngine.fakePdf(listOf(page()), fields = seed.drop(1).take(2)))
        val c = FormsController(engine, h, { block -> engine.block(h) }, UnconfinedTestDispatcher(testScheduler))
        engine.failNext(EngineError.Io("x"), "listFields")
        assertFalse(c.load())
        assertEquals(FormProblem.ACTION_FAILED, c.problem())
        assertFalse(c.state.value.loaded)
        assertTrue(c.load())
        assertNull(c.problem())
    }

    @Test
    fun errorInEditCallback_isState() = runTest {
        val h = engine.open(FakeEngine.fakePdf(listOf(page()), fields = seed.drop(1).take(3)))
        val c = FormsController(engine, h, { throw EngineError.NotPermitted("locked") }, UnconfinedTestDispatcher(testScheduler))
        c.load()
        val name = c.state.value.fields.first { it.kind == FieldKind.TEXT && !it.multiline }.name
        assertFalse(c.setText(name, "x"))
        assertEquals(FormProblem.NOT_PERMITTED, c.problem())
    }

    @Test
    fun dismissProblem_clears() = runTest {
        val c = setup()
        c.setText("id", "x")
        assertEquals(FormProblem.READ_ONLY, c.problem())
        c.dismissProblem()
        assertNull(c.problem())
    }

    @Test
    fun reload_dropsCurrentAndValidationOfVanishedFields() = runTest {
        val c = setup()
        c.select("code")
        c.setText("code", "toolong")
        assertEquals(setOf("code"), c.state.value.validation.keys)
        // The same controller now reads a document that no longer has the field.
        val c2 = FormsController(engine, handle, { block -> engine.block(handle) }, UnconfinedTestDispatcher(testScheduler))
        c2.load()
        c2.select("code")
        engine.close(handle)
        handle = engine.open(FakeEngine.fakePdf(listOf(page()), fields = listOf(seed[3])))
        val c3 = FormsController(engine, handle, { block -> engine.block(handle) }, UnconfinedTestDispatcher(testScheduler))
        assertTrue(c3.load())
        assertFalse(c3.select("code"))
        assertEquals(listOf("name"), c3.state.value.fields.map { it.name })
    }
}
