package ai.aneesiqbal.rozan.launcher

import ai.aneesiqbal.rozan.airplay.android.AppLanguage
import ai.aneesiqbal.rozan.launcher.redirect.RedirectClient
import android.app.Application
import android.content.Context
import android.util.Log
import kotlin.system.exitProcess

/**
 * Installs the crash report for the Home button redirect before anything
 * else runs (docs/research/home-button-override.md, 6.4, `C`): when Rozan's
 * UI process dies from an uncaught exception, the redirect service (in its
 * own process) trips its breaker, so the user stays on the stock home, where
 * Settings works, instead of being sent back into a crashing Rozan.
 *
 * It also applies the app language ([AppLanguage]) to the UI and `:airplay`
 * processes. The `:redirect` process shows no text, and it can start before
 * the user unlocks the device, when the file that holds the choice cannot be
 * read yet.
 */
class RozanApplication : Application() {
    override fun attachBaseContext(base: Context) {
        // Before super.attachBaseContext this has no package name of its own; the base context has.
        super.attachBaseContext(if (showsText(base.packageName)) AppLanguage.wrap(base) else base)
    }

    override fun onCreate() {
        // Only the UI process; the :redirect and :airplay processes report nothing.
        if (getProcessName() == packageName) installCrashReport()
        super.onCreate()
        if (showsText(packageName)) AppLanguage.install(this)
    }

    private fun showsText(packageName: String) = getProcessName() != packageName + REDIRECT_PROCESS

    // The report must never stop the process from dying the normal way, whatever it throws.
    @Suppress("TooGenericExceptionCaught")
    private fun installCrashReport() {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            try {
                RedirectClient.reportCrash(this)
            } catch (reportError: Throwable) {
                Log.w(TAG, "Could not report the crash to the redirect", reportError)
            }
            if (previous != null) previous.uncaughtException(thread, error) else exitProcess(CRASH_EXIT_STATUS)
        }
    }

    private companion object {
        const val TAG = "RozanApplication"
        const val REDIRECT_PROCESS = ":redirect"
        const val CRASH_EXIT_STATUS = 10
    }
}
