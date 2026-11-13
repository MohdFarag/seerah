package app.seerah

import android.app.Application
import android.os.StrictMode
import dagger.hilt.android.HiltAndroidApp

/** Application entry point and root of the Hilt dependency graph. */
@HiltAndroidApp
class SeerahApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        if (BuildConfig.DEBUG) enableStrictMode()
    }

    /** Debug builds log disk or network work on the main thread, and leaked resources (ADR 0004). */
    private fun enableStrictMode() {
        StrictMode.setThreadPolicy(
            StrictMode.ThreadPolicy
                .Builder()
                .detectAll()
                .penaltyLog()
                .build(),
        )
        StrictMode.setVmPolicy(
            StrictMode.VmPolicy
                .Builder()
                .detectAll()
                .penaltyLog()
                .build(),
        )
    }
}
