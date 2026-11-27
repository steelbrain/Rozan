package ai.aneesiqbal.rozan.launcher

import ai.aneesiqbal.rozan.launcher.home.AppOptionsPanel
import ai.aneesiqbal.rozan.launcher.settings.LauncherSettings
import android.view.KeyEvent
import android.view.ViewConfiguration
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Until
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * A game controller (such as the NVIDIA Shield controller) on the home screen
 * and in the settings. Rozan has no gamepad code of its own: the system turns
 * an unhandled BUTTON_A into DPAD_CENTER, BUTTON_B into BACK and BUTTON_SELECT
 * into MENU (docs/research/nvidia-shield.md, section 4). These tests make sure
 * nothing in Rozan swallows the button before that happens.
 */
@RunWith(AndroidJUnit4::class)
class GamepadTest {
    private val settings get() = LauncherSettings(targetContext)

    @Before
    fun setUp() {
        resetLauncherState()
    }

    @After
    fun tearDown() {
        resetLauncherState()
    }

    @Test
    fun aSelectsTheFocusedTileAndBLeavesTheScreensaverBeforeAnythingElse() {
        launchHome().use { scenario ->
            repeat(3) { device.pressDPadRight() }
            waitUntil("focus on Screensaver") { scenario.focusedTileLabel() == "Screensaver" }

            pressGamepadButton(KeyEvent.KEYCODE_BUTTON_A)
            waitUntil("A started the screensaver") { scenario.read { it.isScreensaverActive } }

            pressGamepadButton(KeyEvent.KEYCODE_BUTTON_B)
            waitUntil("B stopped the screensaver") { scenario.read { !it.isScreensaverActive } }
            device.waitForIdle()
            assertEquals("that B did nothing else", "Screensaver", scenario.focusedTileLabel())

            pressGamepadButton(KeyEvent.KEYCODE_BUTTON_B)
            waitUntil("the next B returns to the first tile") { scenario.focusedTileLabel() == "Settings" }
            assertEquals(PACKAGE, device.currentPackageName)
        }
    }

    @Test
    fun holdingAOpensTheAppPanelAndBClosesIt() {
        launchHome().use { scenario ->
            val app = moveToFirstApp(scenario)

            pressGamepadButton(KeyEvent.KEYCODE_BUTTON_A, holdMs = ViewConfiguration.getLongPressTimeout() * 2L)
            waitUntil("panel open") { scenario.panelShowing() }
            assertEquals("the hold did not open the app", PACKAGE, device.currentPackageName)

            pressGamepadButton(KeyEvent.KEYCODE_BUTTON_B)
            waitUntil("panel closed") { !scenario.panelShowing() }
            assertEquals(app, scenario.focusedTileLabel())
        }
    }

    @Test
    fun selectOpensTheAppPanel() {
        launchHome().use { scenario ->
            moveToFirstApp(scenario)

            pressGamepadButton(KeyEvent.KEYCODE_BUTTON_SELECT)
            waitUntil("panel open") { scenario.panelShowing() }

            pressGamepadButton(KeyEvent.KEYCODE_BUTTON_B)
            waitUntil("panel closed") { !scenario.panelShowing() }
        }
    }

    @Test
    fun aTogglesASettingAndBLeavesTheSettings() {
        launchHome().use { scenario ->
            device.pressDPadUp()
            waitUntil("focus on the settings button") { scenario.read { it.currentFocus?.id == R.id.settings_button } }
            pressGamepadButton(KeyEvent.KEYCODE_BUTTON_A)
            assertTrue("settings opened", device.wait(Until.hasObject(By.res(PACKAGE, "settings_list")), TIMEOUT))
            assertTrue(device.wait(Until.hasObject(By.desc("Keep screen awake, On").focused(true)), TIMEOUT))

            pressGamepadButton(KeyEvent.KEYCODE_BUTTON_A)
            waitUntil("keep awake saved as off") { !settings.keepScreenOn }

            pressGamepadButton(KeyEvent.KEYCODE_BUTTON_B)
            assertTrue("home is back", device.wait(Until.hasObject(By.res(PACKAGE, "app_grid")), TIMEOUT))
        }
    }

    /** Moves focus to the first app tile (the row under the built-ins) and returns its label. */
    private fun moveToFirstApp(scenario: ActivityScenario<HomeActivity>): String {
        val first = scenario.read { it.apps().first().label }
        device.pressDPadDown()
        waitUntil("focus on $first") { scenario.focusedTileLabel() == first }
        return first
    }

    private fun ActivityScenario<HomeActivity>.panelShowing(): Boolean =
        read { it.findViewById<AppOptionsPanel>(R.id.app_options).isShowing }

    private companion object {
        const val TIMEOUT = 5_000L
    }
}
