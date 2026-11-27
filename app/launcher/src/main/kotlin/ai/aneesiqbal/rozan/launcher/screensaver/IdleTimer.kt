package ai.aneesiqbal.rozan.launcher.screensaver

/**
 * Decides when the home screen's screensaver starts and stops.
 *
 * While the home screen is resumed, a countdown of [resume]'s timeout restarts
 * on every key press; when it runs out the screensaver starts. Any key stops
 * it again, and that key (its down, repeats and up) is consumed so it does not
 * also act on the UI underneath, e.g. launch the focused app.
 *
 * The idle countdown is a product feature (a user-chosen delay), so a plain
 * resettable timer is the right tool. Scheduling is injected so the state
 * machine can be tested without Android.
 */
class IdleTimer(
    private val scheduler: Scheduler,
    private val listener: Listener,
) {
    /** Runs delayed tasks; on Android a main-thread `Handler`. */
    interface Scheduler {
        fun schedule(
            task: Runnable,
            delayMs: Long,
        )

        fun cancel(task: Runnable)
    }

    /** Receives screensaver transitions. */
    interface Listener {
        fun onScreensaverStarted()

        fun onScreensaverStopped()
    }

    /** Whether the screensaver is showing. */
    var isScreensaverActive: Boolean = false
        private set

    private var resumed = false
    private var timeoutMs = 0L
    private var swallowedKey = NO_KEY
    private val timeout = Runnable { if (resumed && !isScreensaverActive) start() }

    /** The home screen is in front. [timeoutMs] of zero or less disables the countdown. */
    fun resume(timeoutMs: Long) {
        resumed = true
        this.timeoutMs = timeoutMs
        restartCountdown()
    }

    /** The home screen left the foreground: stop counting and stop the screensaver. */
    fun pause() {
        resumed = false
        scheduler.cancel(timeout)
        swallowedKey = NO_KEY
        if (isScreensaverActive) stop()
    }

    /** Starts the screensaver right away (the Screensaver tile), even with the countdown disabled. */
    fun startNow() {
        if (!resumed || isScreensaverActive) return
        scheduler.cancel(timeout)
        start()
    }

    /** Stops the screensaver without consuming a key (e.g. the HOME button), and restarts the countdown. */
    fun stopNow() {
        if (isScreensaverActive) stop()
        restartCountdown()
    }

    /** User input that does not arrive as a key event here (e.g. Back routed through the platform). */
    fun onUserActivity() {
        if (!isScreensaverActive) restartCountdown()
    }

    /**
     * Feeds a key event. Returns true when the event must be consumed: it
     * stopped the screensaver, or it belongs to the key press that did.
     */
    fun onKey(
        keyCode: Int,
        down: Boolean,
    ): Boolean {
        if (swallowedKey == keyCode) {
            if (!down) swallowedKey = NO_KEY
            return true
        }
        if (isScreensaverActive) {
            if (down) {
                swallowedKey = keyCode
                stop()
                restartCountdown()
            }
            return true
        }
        if (down) restartCountdown()
        return false
    }

    private fun restartCountdown() {
        scheduler.cancel(timeout)
        if (resumed && timeoutMs > 0 && !isScreensaverActive) scheduler.schedule(timeout, timeoutMs)
    }

    private fun start() {
        isScreensaverActive = true
        listener.onScreensaverStarted()
    }

    private fun stop() {
        isScreensaverActive = false
        listener.onScreensaverStopped()
    }

    private companion object {
        const val NO_KEY = Int.MIN_VALUE
    }
}
