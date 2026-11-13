package app.pdfa.viewer.ui

import android.app.ActivityManager
import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import app.pdfa.engine.EngineApi
import app.pdfa.viewer.Clock
import app.pdfa.viewer.DocumentSource
import app.pdfa.viewer.TileBudget
import app.pdfa.viewer.ViewerViewModel

/** Builds a [ViewerViewModel] whose tile memory is sized from the device's real memory class. */
class ViewerViewModelFactory(
    private val engine: EngineApi,
    private val source: DocumentSource,
    context: Context,
    private val clock: Clock = Clock.System,
) : ViewModelProvider.Factory {
    private val budget: TileBudget = budgetFor(context)

    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        require(modelClass.isAssignableFrom(ViewerViewModel::class.java)) { "unsupported ViewModel" }
        return ViewerViewModel(engine, source, clock, budget = budget) as T
    }

    companion object {
        /** min(64 MiB, memoryClass / 8), from [ActivityManager.getMemoryClass]. */
        fun budgetFor(context: Context): TileBudget {
            val am = context.applicationContext.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
            return TileBudget.forMemoryClass(am.memoryClass)
        }
    }
}
