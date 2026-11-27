package ai.aneesiqbal.rozan.launcher.redirect

import ai.aneesiqbal.rozan.launcher.redirect.RedirectContract.KEY_AWAITING
import ai.aneesiqbal.rozan.launcher.redirect.RedirectContract.KEY_HANDOVER
import ai.aneesiqbal.rozan.launcher.redirect.RedirectContract.KEY_LAST_ACTIVITY
import ai.aneesiqbal.rozan.launcher.redirect.RedirectContract.KEY_LAST_WINDOW
import ai.aneesiqbal.rozan.launcher.redirect.RedirectContract.KEY_MODE
import ai.aneesiqbal.rozan.launcher.redirect.RedirectContract.KEY_READY
import ai.aneesiqbal.rozan.launcher.redirect.RedirectContract.KEY_REDIRECTS
import ai.aneesiqbal.rozan.launcher.redirect.RedirectContract.KEY_START_COMPONENT
import ai.aneesiqbal.rozan.launcher.redirect.RedirectContract.KEY_STOCK_HOME
import ai.aneesiqbal.rozan.launcher.redirect.RedirectContract.KEY_STOCK_PACKAGE
import ai.aneesiqbal.rozan.launcher.redirect.RedirectContract.KEY_TRIPPED
import ai.aneesiqbal.rozan.launcher.redirect.RedirectContract.KEY_UNRECOGNIZED
import ai.aneesiqbal.rozan.launcher.redirect.RedirectContract.KEY_USER_STAY
import ai.aneesiqbal.rozan.launcher.redirect.RedirectContract.KEY_WINDOW_EVENTS
import android.content.Context
import android.os.Bundle

/**
 * The redirect's state in the `:redirect` process. The accessibility service
 * feeds it events on the main thread; [RedirectProvider] reads and changes it
 * from binder threads, so every access holds [lock]. It lives as long as the
 * process, so a crash report (`C`) or the user's choice (`S`) received while
 * the service is reconnecting is still there when it is back.
 */
internal object RedirectRuntime {
    private val lock = Any()
    private var redirector: HomeRedirector? = null
    private var systemSetup = HomeSetup.NONE
    private var testSetup: HomeSetup? = null
    private var ready = false
    private var windowEvents = 0
    private var redirects = 0
    private var lastWindow: ActivityId? = null

    /** The service is connected and the user is unlocked (true), or it went away (false). */
    fun setReady(ready: Boolean) =
        synchronized(lock) {
            this.ready = ready
        }

    /** A fresh answer to "what does Home open"; a test setup, if any, takes precedence. */
    fun applySystemSetup(
        context: Context,
        setup: HomeSetup,
    ) = synchronized(lock) {
        systemSetup = setup
        redirector(context).configure(testSetup ?: setup)
    }

    /** The stock launcher's package in the setup in force, if Home is redirected at all. */
    fun stockPackage(): String? = synchronized(lock) { (testSetup ?: systemSetup).stockPackage }

    fun onWindowStateChanged(
        context: Context,
        window: ActivityId,
        contentChangeTypes: Int,
        windows: HomeRedirector.Windows,
    ): HomeRedirector.Action =
        synchronized(lock) {
            windowEvents++
            lastWindow = window
            val redirector = redirector(context)
            count(redirector.onWindowStateChanged(window.packageName, window.className, contentChangeTypes, windows))
        }

    fun onHomePressed(
        context: Context,
        windows: HomeRedirector.Windows,
    ): HomeRedirector.Action = synchronized(lock) { count(redirector(context).onHomePressed(windows)) }

    fun onRozanCrashed(context: Context) = synchronized(lock) { redirector(context).onRozanCrashed() }

    fun onRozanOpensPicker(context: Context) = synchronized(lock) { redirector(context).onRozanOpensPicker() }

    fun onRozanPickerFailed(context: Context) = synchronized(lock) { redirector(context).onRozanPickerFailed() }

    /** `S`. Under the test seam, also says which activity stands in for the stock home. */
    fun onStayOnStockHome(context: Context): Bundle =
        synchronized(lock) {
            redirector(context).onStayOnStockHome()
            Bundle().apply {
                testSetup?.stockHome?.firstOrNull()?.let { putString(KEY_START_COMPONENT, it.toString()) }
            }
        }

    /** Test seam: [setup] replaces the system's answer (null restores it), and the state starts over. */
    fun overrideSetup(
        context: Context,
        setup: HomeSetup?,
    ) = synchronized(lock) {
        testSetup = setup
        redirector = HomeRedirector(HomeSetupReader.rozanHome(context)).also { it.configure(setup ?: systemSetup) }
        windowEvents = 0
        redirects = 0
        lastWindow = null
    }

    fun status(context: Context): Bundle =
        synchronized(lock) {
            val r = redirector(context)
            Bundle().apply {
                putBoolean(KEY_READY, ready)
                putString(KEY_MODE, r.setup.mode.name)
                putString(KEY_STOCK_PACKAGE, r.setup.stockPackage)
                putStringArrayList(KEY_STOCK_HOME, ArrayList(r.setup.stockHome.map(ActivityId::toString)))
                putStringArrayList(KEY_UNRECOGNIZED, ArrayList(r.setup.unrecognized.map(ActivityId::toString)))
                putBoolean(KEY_AWAITING, r.awaitingRozan)
                putBoolean(KEY_TRIPPED, r.tripped)
                putBoolean(KEY_USER_STAY, r.userStay)
                putBoolean(KEY_HANDOVER, r.handoverHold)
                putString(KEY_LAST_ACTIVITY, r.lastActivity?.toString())
                putString(KEY_LAST_WINDOW, lastWindow?.toString())
                putInt(KEY_WINDOW_EVENTS, windowEvents)
                putInt(KEY_REDIRECTS, redirects)
            }
        }

    private fun count(action: HomeRedirector.Action): HomeRedirector.Action {
        if (action == HomeRedirector.Action.START_ROZAN) redirects++
        return action
    }

    private fun redirector(context: Context): HomeRedirector =
        redirector ?: HomeRedirector(HomeSetupReader.rozanHome(context)).also {
            it.configure(testSetup ?: systemSetup)
            redirector = it
        }
}
