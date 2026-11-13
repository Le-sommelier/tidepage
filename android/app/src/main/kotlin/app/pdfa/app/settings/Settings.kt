package app.pdfa.app.settings

import android.annotation.SuppressLint
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/** What Save does to the file the user opened. Save a copy is the default: no silent overwrite. */
enum class SaveBehaviour(val key: String) {
    SAVE_COPY("save_copy"),
    REPLACE("replace");

    companion object {
        /** Unknown or missing keys fall back to the default, never to REPLACE. */
        fun fromKey(key: String?): SaveBehaviour = entries.firstOrNull { it.key == key } ?: SAVE_COPY
    }
}

data class AppSettings(
    /** Opt-in. Off keeps the recognisable Tidewater palette and its contrast guarantees. */
    val dynamicColour: Boolean = false,
    val saveBehaviour: SaveBehaviour = SaveBehaviour.SAVE_COPY,
    /** Dark theme only: tints the page sheets. Light theme pages stay white. */
    val dimPages: Boolean = false,
)

/** Persistence behind an interface so tests use [InMemorySettingsStore]. */
interface SettingsStore {
    fun read(): AppSettings
    fun write(settings: AppSettings)
}

/** Test fake with the same contract as the real store. */
class InMemorySettingsStore(initial: AppSettings = AppSettings()) : SettingsStore {
    private var current = initial
    override fun read(): AppSettings = current
    override fun write(settings: AppSettings) {
        current = settings
    }
}

/** SharedPreferences-backed store. Only the keys below are written. */
class PrefsSettingsStore(private val prefs: SharedPreferences) : SettingsStore {
    override fun read(): AppSettings {
        val defaults = AppSettings()
        return AppSettings(
            dynamicColour = prefs.getBoolean(KEY_DYNAMIC_COLOUR, defaults.dynamicColour),
            saveBehaviour = SaveBehaviour.fromKey(prefs.getString(KEY_SAVE_BEHAVIOUR, null)),
            dimPages = prefs.getBoolean(KEY_DIM_PAGES, defaults.dimPages),
        )
    }

    @SuppressLint("UseKtx") // core-ktx is not in the version catalog; the plain call is the same.
    override fun write(settings: AppSettings) {
        prefs.edit()
            .putBoolean(KEY_DYNAMIC_COLOUR, settings.dynamicColour)
            .putString(KEY_SAVE_BEHAVIOUR, settings.saveBehaviour.key)
            .putBoolean(KEY_DIM_PAGES, settings.dimPages)
            .apply()
    }

    companion object {
        const val PREFS_NAME = "pdfa_settings"
        const val KEY_DYNAMIC_COLOUR = "dynamic_colour"
        const val KEY_SAVE_BEHAVIOUR = "save_behaviour"
        const val KEY_DIM_PAGES = "dim_pages"
    }
}

/** Holds the settings the UI observes. Writes go to the store first, then to the flow. */
class SettingsHolder(private val store: SettingsStore) {
    private val state = MutableStateFlow(store.read())

    val settings: StateFlow<AppSettings> get() = state.asStateFlow()

    fun update(transform: (AppSettings) -> AppSettings) {
        state.update { old ->
            val new = transform(old)
            store.write(new)
            new
        }
    }
}
