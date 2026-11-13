package app.pdfa.app.settings

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class SettingsStoreTest {

    @Test
    fun defaultsAreTheSafeChoices() {
        val defaults = AppSettings()
        assertFalse(defaults.dynamicColour, "dynamic colour is opt-in")
        assertEquals(SaveBehaviour.SAVE_COPY, defaults.saveBehaviour, "save a copy is the default")
        assertFalse(defaults.dimPages)
    }

    @Test
    fun inMemoryStoreStartsWithDefaults() {
        assertEquals(AppSettings(), InMemorySettingsStore().read())
    }

    @Test
    fun inMemoryStoreRoundTripsEveryField() {
        val store = InMemorySettingsStore()
        val changed = AppSettings(dynamicColour = true, saveBehaviour = SaveBehaviour.REPLACE, dimPages = true)
        store.write(changed)
        assertEquals(changed, store.read())
    }

    @Test
    fun inMemoryStoreKeepsTheLastWrite() {
        val store = InMemorySettingsStore(AppSettings(dimPages = true))
        store.write(AppSettings(dimPages = false))
        store.write(AppSettings(dimPages = true, dynamicColour = true))
        assertEquals(AppSettings(dimPages = true, dynamicColour = true), store.read())
    }

    @Test
    fun unknownOrMissingSaveKeyFallsBackToSaveCopy() {
        assertEquals(SaveBehaviour.SAVE_COPY, SaveBehaviour.fromKey(null))
        assertEquals(SaveBehaviour.SAVE_COPY, SaveBehaviour.fromKey(""))
        assertEquals(SaveBehaviour.SAVE_COPY, SaveBehaviour.fromKey("overwrite"))
        assertEquals(SaveBehaviour.SAVE_COPY, SaveBehaviour.fromKey("SAVE_COPY"))
        assertEquals(SaveBehaviour.REPLACE, SaveBehaviour.fromKey("replace"))
        assertEquals(SaveBehaviour.SAVE_COPY, SaveBehaviour.fromKey("save_copy"))
    }

    @Test
    fun holderPersistsEveryUpdateAndPublishesIt() {
        val store = InMemorySettingsStore()
        val holder = SettingsHolder(store)
        assertEquals(AppSettings(), holder.settings.value)

        holder.update { it.copy(dynamicColour = true) }
        holder.update { it.copy(saveBehaviour = SaveBehaviour.REPLACE) }

        assertEquals(AppSettings(dynamicColour = true, saveBehaviour = SaveBehaviour.REPLACE), holder.settings.value)
        assertEquals(holder.settings.value, store.read())
    }

    @Test
    fun holderReadsTheStoreAtConstruction() {
        val holder = SettingsHolder(InMemorySettingsStore(AppSettings(dimPages = true)))
        assertEquals(true, holder.settings.value.dimPages)
    }
}
