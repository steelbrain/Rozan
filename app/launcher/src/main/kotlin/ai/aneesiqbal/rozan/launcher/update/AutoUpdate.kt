package ai.aneesiqbal.rozan.launcher.update

import android.content.Context

/** What the settings screen does with the updater. */
internal object AutoUpdate {
    /** Whether "Update automatically" is on. */
    fun isEnabled(context: Context): Boolean = UpdateState(context).enabled

    /**
     * Turns "Update automatically" on (the daily check, and one check now) or off (cancels the jobs and
     * any install session, deletes the downloaded files and forgets the last results).
     */
    fun setEnabled(
        context: Context,
        enabled: Boolean,
    ) {
        val app = context.applicationContext
        UpdateState(app).enabled = enabled
        if (enabled) {
            UpdateJobs.enable(app)
        } else {
            // Cancelled first, so a running check stops; the cleanup queues behind it on the update thread.
            UpdateJobs.cancelAll(app)
            UpdateWorker.execute { Updater(app).disable() }
        }
    }

    /** The user selected "Install update": install the pending update, letting Android ask to confirm. */
    fun installNow(context: Context) {
        val app = context.applicationContext
        UpdateWorker.execute { Updater(app).install(userInitiated = true) }
    }
}
