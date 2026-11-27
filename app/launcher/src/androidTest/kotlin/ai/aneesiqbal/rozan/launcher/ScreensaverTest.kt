package ai.aneesiqbal.rozan.launcher

import ai.aneesiqbal.rozan.launcher.settings.LauncherSettings
import android.os.SystemClock
import android.view.View
import android.widget.TextView
import androidx.lifecycle.Lifecycle
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ScreensaverTest {
    @Before
    fun setUp() {
        resetLauncherState()
    }

    @After
    fun tearDown() {
        resetLauncherState()
    }

    @Test
    fun startsAfterIdleAndTheExitKeyDoesNotLaunchTheFocusedTile() {
        LauncherSettings.screensaverDelayOverrideMs = IDLE_MS
        launchHome().use { scenario ->
            // Focus is on the Settings tile: if OK leaked through, the system settings app would open.
            assertEquals("Settings", scenario.focusedTileLabel())

            waitUntil("screensaver started", timeoutMs = IDLE_MS * 4) { scenario.read { it.isScreensaverActive } }
            device.pressDPadCenter()

            waitUntil("screensaver stopped") { scenario.read { !it.isScreensaverActive } }
            device.waitForIdle()
            assertEquals(PACKAGE, device.currentPackageName)
            assertEquals(Lifecycle.State.RESUMED, scenario.state)
            assertEquals("Settings", scenario.focusedTileLabel())
            assertTrue(scenario.read { it.hasWindowFocus() })
        }
    }

    @Test
    fun screensaverTileStartsItAndBackStopsIt() {
        launchHome().use { scenario ->
            repeat(3) { device.pressDPadRight() }
            waitUntil("focus on Screensaver") { scenario.focusedTileLabel() == "Screensaver" }

            device.pressDPadCenter()
            waitUntil("screensaver started") { scenario.read { it.isScreensaverActive } }
            // The photo's title fades in with the screensaver.
            waitUntil("the photo's title is shown") {
                scenario.read {
                    val caption = it.findViewById<TextView>(R.id.screensaver_caption)
                    val band = it.findViewById<View>(R.id.screensaver_caption_band)
                    val title = it.shownBackdropTitle
                    band.alpha == 1f && !title.isNullOrEmpty() && caption.text.toString() == title
                }
            }

            device.pressBack()
            waitUntil("screensaver stopped") { scenario.read { !it.isScreensaverActive } }
            waitUntil("the title is hidden again") {
                scenario.read { it.findViewById<View>(R.id.screensaver_caption_band).alpha == 0f }
            }
            assertEquals(PACKAGE, device.currentPackageName)
            assertEquals("Screensaver", scenario.focusedTileLabel())
        }
    }

    @Test
    fun noScreensaverWhileKeepAwakeIsOff() {
        LauncherSettings(targetContext).keepScreenOn = false
        LauncherSettings.screensaverDelayOverrideMs = IDLE_MS
        launchHome().use { scenario ->
            // Twice the delay without input: still no screensaver.
            SystemClock.sleep(IDLE_MS * 2)
            assertEquals(false, scenario.read { it.isScreensaverActive })
        }
    }

    private companion object {
        const val IDLE_MS = 1_500L
    }
}
