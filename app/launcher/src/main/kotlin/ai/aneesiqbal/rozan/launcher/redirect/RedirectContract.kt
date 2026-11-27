package ai.aneesiqbal.rozan.launcher.redirect

import android.content.Context
import android.net.Uri
import androidx.core.net.toUri

/**
 * Calls from Rozan's UI process to the `:redirect` process, through
 * [RedirectProvider]. An accessibility service cannot be bound by its own
 * app (`onBind` is final), and a provider `call()` is one synchronous binder
 * call, which is what a crash report needs (6.4, `C`).
 */
internal object RedirectContract {
    fun uri(context: Context): Uri = "content://${context.packageName}.redirect".toUri()

    /** `C`: the UI process is about to die from an uncaught exception. */
    const val METHOD_CRASH = "crash"

    /** `S`: the user chose the stock home. Returns [KEY_START_COMPONENT] only under the test seam. */
    const val METHOD_STAY = "stay"

    /** `O`: Rozan is about to open the TV's input picker. */
    const val METHOD_OPENS_PICKER = "opensPicker"

    /** No picker opened after [METHOD_OPENS_PICKER]. */
    const val METHOD_PICKER_FAILED = "pickerFailed"

    /** The service's state, for launcher settings and tests. */
    const val METHOD_STATUS = "status"

    /**
     * Debuggable builds only: replaces what Home opens with [KEY_STOCK_PACKAGE]
     * and [KEY_STOCK_HOME], or restores the system's answer when they are
     * missing, and resets the state. Instrumented tests use it because a test
     * APK cannot become the device's stock launcher.
     */
    const val METHOD_TEST_SETUP = "testSetup"

    const val KEY_READY = "ready"
    const val KEY_MODE = "mode"
    const val KEY_STOCK_PACKAGE = "stockPackage"
    const val KEY_STOCK_HOME = "stockHome"
    const val KEY_UNRECOGNIZED = "unrecognized"
    const val KEY_AWAITING = "awaitingRozan"
    const val KEY_TRIPPED = "tripped"
    const val KEY_USER_STAY = "userStay"
    const val KEY_HANDOVER = "handoverHold"
    const val KEY_LAST_ACTIVITY = "lastActivity"
    const val KEY_LAST_WINDOW = "lastWindow"
    const val KEY_WINDOW_EVENTS = "windowEvents"
    const val KEY_REDIRECTS = "redirects"
    const val KEY_START_COMPONENT = "startComponent"
}
