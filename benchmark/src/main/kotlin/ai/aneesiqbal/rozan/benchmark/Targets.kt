package ai.aneesiqbal.rozan.benchmark

import android.content.Intent
import android.os.SystemClock
import androidx.benchmark.macro.MacrobenchmarkScope
import androidx.test.uiautomator.By
import androidx.test.uiautomator.StaleObjectException
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until

/** Package of the launcher under test. */
internal const val LAUNCHER = "ai.aneesiqbal.rozan.launcher"

/** The receiver's process (service, PIN, mirroring and now-playing screens). */
internal const val AIRPLAY_PROCESS = ":airplay"

/** How long any one UI wait may take before the benchmark fails. */
internal const val UI_TIMEOUT_MS = 10_000L

/**
 * Pause between D-pad presses: a quick but human pace (about 7 keys a
 * second), so focus animations overlap the way they do for a real user.
 */
private const val KEY_INTERVAL_MS = 150L

/** Settings, Network, AirPlay, Screensaver: the built-in first row. */
private const val BUILT_IN_TILES = 4

/** Column of the Screensaver built-in. */
private const val SCREENSAVER_COLUMN = 3

/** Chrome fade-out plus the first backdrop change the screensaver may start. */
internal const val SCREENSAVER_SETTLE_MS = 4_000L

private const val HOME_ACTIVITY = "$LAUNCHER.HomeActivity"
private const val DEBUG_ACTION = "ai.aneesiqbal.rozan.airplay.debug."

internal fun homeIntent(): Intent = Intent(Intent.ACTION_MAIN).setClassName(LAUNCHER, HOME_ACTIVITY)

/** Opens the home screen and waits until the installed apps are on the grid. */
internal fun MacrobenchmarkScope.startHome() {
    startActivityAndWait(homeIntent())
    device.waitForApps()
}

/**
 * Waits for the app tiles (they load off the main thread after the first
 * frame; the built-in row is there from the start).
 */
internal fun UiDevice.waitForApps() {
    val grid = wait(Until.findObject(By.res(LAUNCHER, "app_grid")), UI_TIMEOUT_MS) ?: error("home grid not shown")
    waitFor("app tiles") {
        try {
            grid.children.count { !it.contentDescription.isNullOrEmpty() } > BUILT_IN_TILES
        } catch (_: StaleObjectException) {
            false
        }
    }
}

/**
 * Browses the grid the way someone looks for an app: along a row, down, back
 * along the next row, for [rows] rows (the built-in row and two rows of apps
 * on a stock TV emulator), then back up to the first row. Presses past the
 * end of a short row just leave focus where it is.
 */
internal fun UiDevice.browseGrid(
    rows: Int = 3,
    columns: Int = 5,
) {
    repeat(rows) { row ->
        repeat(columns - 1) { press(if (row % 2 == 0) RIGHT else LEFT) }
        if (row < rows - 1) press(DOWN)
    }
    repeat(rows - 1) { press(UP) }
}

/** From the first tile: to the Screensaver built-in and press it. */
internal fun UiDevice.startScreensaver() {
    repeat(SCREENSAVER_COLUMN) { press(RIGHT) }
    pressDPadCenter()
}

/** Fires one of the AirPlay debug hooks (benchmark and debug builds), e.g. `START_MIRROR`. */
internal fun UiDevice.airplayEvent(action: String) {
    executeShellCommand("am broadcast -p $LAUNCHER -a $DEBUG_ACTION$action")
}

/** Lets the receiver put its screens over other apps, as the install guide's adb step does. */
internal fun UiDevice.allowOverlay() {
    executeShellCommand("appops set $LAUNCHER SYSTEM_ALERT_WINDOW allow")
}

/** Whether a process of the launcher's package with [suffix] (e.g. `:airplay`) is running. */
internal fun UiDevice.isRunning(suffix: String): Boolean = executeShellCommand("pidof $LAUNCHER$suffix").isNotBlank()

/** Polls [condition] until it holds; fails after [UI_TIMEOUT_MS]. */
internal fun waitFor(
    what: String,
    condition: () -> Boolean,
) {
    val deadline = SystemClock.uptimeMillis() + UI_TIMEOUT_MS
    while (!condition()) {
        check(SystemClock.uptimeMillis() < deadline) { "Timed out waiting for $what" }
        SystemClock.sleep(POLL_MS)
    }
}

private const val POLL_MS = 50L
private const val RIGHT = 0
private const val LEFT = 1
private const val DOWN = 2
private const val UP = 3

private fun UiDevice.press(direction: Int) {
    when (direction) {
        RIGHT -> pressDPadRight()
        LEFT -> pressDPadLeft()
        DOWN -> pressDPadDown()
        else -> pressDPadUp()
    }
    SystemClock.sleep(KEY_INTERVAL_MS)
}
