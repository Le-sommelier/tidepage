package app.pdfa.app

import android.app.Application

class PdfaApplication : Application() {
    /** Built on first use, once per process. */
    val container: AppContainer by lazy { AppContainer(this) }
}
