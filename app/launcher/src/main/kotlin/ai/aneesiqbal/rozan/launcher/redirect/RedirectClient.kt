package ai.aneesiqbal.rozan.launcher.redirect

import ai.aneesiqbal.rozan.launcher.redirect.RedirectContract.KEY_LAST_ACTIVITY
import ai.aneesiqbal.rozan.launcher.redirect.RedirectContract.KEY_LAST_WINDOW
import ai.aneesiqbal.rozan.launcher.redirect.RedirectContract.KEY_READY
import ai.aneesiqbal.rozan.launcher.redirect.RedirectContract.KEY_REDIRECTS
import ai.aneesiqbal.rozan.launcher.redirect.RedirectContract.KEY_START_COMPONENT
import ai.aneesiqbal.rozan.launcher.redirect.RedirectContract.KEY_TRIPPED
import ai.aneesiqbal.rozan.launcher.redirect.RedirectContract.KEY_USER_STAY
import ai.aneesiqbal.rozan.launcher.redirect.RedirectContract.KEY_WINDOW_EVENTS
import ai.aneesiqbal.rozan.launcher.redirect.RedirectContract.METHOD_CRASH
import ai.aneesiqbal.rozan.launcher.redirect.RedirectContract.METHOD_OPENS_PICKER
import ai.aneesiqbal.rozan.launcher.redirect.RedirectContract.METHOD_PICKER_FAILED
import ai.aneesiqbal.rozan.launcher.redirect.RedirectContract.METHOD_STATUS
import ai.aneesiqbal.rozan.launcher.redirect.RedirectContract.METHOD_STAY
import android.content.ComponentName
import android.content.Context
import android.os.Bundle
import android.provider.Settings
import android.util.Log

/**
 * The UI process's side of the redirect: reports crashes, records the user's
 * choice of the stock home, and reads the service's state. Every call is a
 * synchronous binder call that may start the `:redirect` process, so call
 * it off the main thread (except [reportCrash], which has no choice).
 */
object RedirectClient {
    private const val TAG = "RedirectClient"

    /** The service as `enabled_accessibility_services` lists it (short form). */
    fun serviceComponent(context: Context): ComponentName = ComponentName(context, HomeRedirectService::class.java)

    /** Whether the service is in the system's list of enabled accessibility services. */
    fun isEnabled(context: Context): Boolean {
        val enabled =
            Settings.Secure.getString(context.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
                ?: return false
        val ours = serviceComponent(context)
        return enabled.split(':').any { ComponentName.unflattenFromString(it) == ours }
    }

    /** `C` (6.4): trips the breaker so the stock home is left alone until the user opens Rozan. */
    fun reportCrash(context: Context) {
        call(context, METHOD_CRASH)
    }

    /**
     * `S` (6.4): the user chose the stock home; the caller starts it next.
     * Returns the activity to start instead of the system's home, which only
     * the instrumented tests' stand-in sets.
     */
    fun stayOnStockHome(context: Context): ComponentName? =
        call(context, METHOD_STAY)?.getString(KEY_START_COMPONENT)?.let(ComponentName::unflattenFromString)

    /**
     * `O` (6.4): Rozan is about to open the TV's input picker, which may be
     * drawn by the stock home itself; call before starting it. Nothing to do
     * while the service is off.
     */
    fun opensPicker(context: Context) {
        if (isEnabled(context)) call(context, METHOD_OPENS_PICKER)
    }

    /** No picker opened after [opensPicker]. */
    fun pickerFailed(context: Context) {
        if (isEnabled(context)) call(context, METHOD_PICKER_FAILED)
    }

    /** The service's state, or null if the `:redirect` process could not be reached. */
    fun status(context: Context): Status? = call(context, METHOD_STATUS)?.let(::Status)

    internal fun call(
        context: Context,
        method: String,
        extras: Bundle? = null,
    ): Bundle? =
        try {
            context.contentResolver.call(RedirectContract.uri(context), method, null, extras)
        } catch (e: IllegalArgumentException) {
            Log.w(TAG, "Redirect process unavailable for $method", e)
            null
        }

    /** A snapshot of the service's state in the `:redirect` process. */
    class Status internal constructor(
        bundle: Bundle,
    ) {
        /** The service is connected and the user is unlocked. */
        val running: Boolean = bundle.getBoolean(KEY_READY)
        val tripped: Boolean = bundle.getBoolean(KEY_TRIPPED)
        val userStay: Boolean = bundle.getBoolean(KEY_USER_STAY)
        val lastActivity: String? = bundle.getString(KEY_LAST_ACTIVITY)
        val lastWindow: String? = bundle.getString(KEY_LAST_WINDOW)
        val windowEvents: Int = bundle.getInt(KEY_WINDOW_EVENTS)
        val redirects: Int = bundle.getInt(KEY_REDIRECTS)
    }
}
