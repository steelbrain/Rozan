package ai.aneesiqbal.rozan.benchmark

import android.os.SystemClock
import androidx.benchmark.macro.junit4.BaselineProfileRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Until
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Generates the launcher's baseline profile into
 * `app/launcher/src/release/generated/baselineProfiles/`:
 *
 * ```
 * ./gradlew :app:launcher:generateBaselineProfile
 * ```
 *
 * with one API 33+ device or emulator connected (older ones need root).
 * [startup] also becomes the startup profile, which R8 uses to put the
 * classes needed before the first frame together in the primary dex.
 *
 * ART keeps one profile per app, written by each of its processes, so
 * [airplaySessions] adds the receiver's screens in the `:airplay` process.
 * It drives them with the debug hooks, which the profiling build
 * (nonMinifiedRelease) contains; their own classes are filtered out.
 */
@RunWith(AndroidJUnit4::class)
class BaselineProfileGenerator {
    @get:Rule
    val rule = BaselineProfileRule()

    @Test
    fun startup() =
        rule.collect(packageName = LAUNCHER, includeInStartupProfile = true, filterPredicate = ::notDebugHook) {
            pressHome()
            startHome()
        }

    @Test
    fun browsing() =
        rule.collect(packageName = LAUNCHER, filterPredicate = ::notDebugHook) {
            pressHome()
            startHome()
            device.browseGrid()

            // Settings from the gear button (above the first tile), down its rows and back.
            startActivityAndWait(homeIntent())
            device.pressDPadUp()
            device.pressDPadCenter()
            check(
                device.wait(Until.hasObject(By.res(LAUNCHER, "settings_list")), UI_TIMEOUT_MS),
            ) { "settings not shown" }
            repeat(SETTINGS_ROWS) { device.pressDPadDown() }
            device.pressBack()
            device.waitForApps()

            // The options panel of an app tile (Menu), then the screensaver.
            device.pressDPadDown()
            device.pressMenu()
            device.waitForIdle()
            device.pressBack()
            startActivityAndWait(homeIntent())
            device.startScreensaver()
            SystemClock.sleep(SCREENSAVER_SETTLE_MS)
            device.pressBack()
        }

    @Test
    fun airplaySessions() =
        rule.collect(packageName = LAUNCHER, filterPredicate = ::notDebugHook) {
            pressHome()
            startHome()
            device.allowOverlay()

            device.airplayEvent("SHOW_PIN")
            check(device.wait(Until.hasObject(By.res(LAUNCHER, "airplay_pin_code")), UI_TIMEOUT_MS)) { "PIN not shown" }
            device.airplayEvent("PIN_DONE")

            device.airplayEvent("START_MIRROR")
            check(device.wait(Until.hasObject(By.res(LAUNCHER, "airplay_mirror_surface")), UI_TIMEOUT_MS)) {
                "mirroring screen not shown"
            }
            SystemClock.sleep(SESSION_MS)
            device.airplayEvent("STOP_MIRROR")
            check(device.wait(Until.gone(By.res(LAUNCHER, "airplay_mirror_surface")), UI_TIMEOUT_MS))

            device.airplayEvent("START_AUDIO")
            check(device.wait(Until.hasObject(By.res(LAUNCHER, "airplay_now_playing_title")), UI_TIMEOUT_MS)) {
                "now-playing screen not shown"
            }
            SystemClock.sleep(SESSION_MS)
            device.airplayEvent("STOP_AUDIO")
        }

    private companion object {
        const val SETTINGS_ROWS = 8
        const val SESSION_MS = 3_000L
    }
}

/** The debug hooks are only in measurement builds; rules for them would be dead weight. */
private fun notDebugHook(rule: String): Boolean = "/airplay/android/debug/" !in rule && "/launcher/debug/" !in rule
