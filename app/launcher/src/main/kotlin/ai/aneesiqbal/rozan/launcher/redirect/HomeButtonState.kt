package ai.aneesiqbal.rozan.launcher.redirect

import android.content.ComponentName
import android.content.Context

/** What launcher settings shows about the Home button. */
class HomeButtonState(
    val setup: HomeSetup,
    /** The service is in the system's enabled list. */
    val enabled: Boolean,
    /** The service is connected and working (the user is unlocked). */
    val running: Boolean,
) {
    enum class Status { OPENS_ROZAN, ON, NOT_RUNNING, OFF }

    val status: Status
        get() =
            when {
                setup.mode == HomeMode.NATIVE -> Status.OPENS_ROZAN
                enabled && running && setup.mode == HomeMode.REDIRECT -> Status.ON
                enabled && !running -> Status.NOT_RUNNING
                else -> Status.OFF
            }

    /** The stock home screen, as `adb` prints it, or null outside redirect mode. */
    val stockHomeName: String?
        get() = setup.stockHome.firstOrNull()?.shortName()

    /** Stock home classes Rozan does not know, for diagnostics; empty when all are known. */
    val unrecognizedNames: List<String>
        get() = setup.unrecognized.map { it.shortName() }

    private fun ActivityId.shortName(): String = ComponentName(packageName, className).flattenToShortString()

    companion object {
        /** Reads the current state. Blocking (package manager and binder calls): call off the main thread. */
        fun read(context: Context): HomeButtonState {
            val setup = HomeSetupReader.read(context)
            val enabled = RedirectClient.isEnabled(context)
            // Only ask the :redirect process when the service is on, so this never starts it needlessly.
            val running = enabled && RedirectClient.status(context)?.running == true
            return HomeButtonState(setup, enabled, running)
        }
    }
}
