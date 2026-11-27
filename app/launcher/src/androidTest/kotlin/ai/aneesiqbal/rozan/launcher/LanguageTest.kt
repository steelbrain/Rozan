package ai.aneesiqbal.rozan.launcher

import ai.aneesiqbal.rozan.airplay.android.AppLanguage
import ai.aneesiqbal.rozan.airplay.android.AppLanguage.Language
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Until
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The language setting: the launcher's screens change at once, and the AirPlay
 * screens of the `:airplay` process (here the PIN) follow it, including a
 * change made after that process started. Runs on both mechanisms: the
 * platform's per-app language from API 33, Rozan's own below.
 */
@RunWith(AndroidJUnit4::class)
class LanguageTest {
    @Before
    fun setUp() {
        resetLauncherState()
        assumeTrue("the device shows English", AppLanguage.systemLanguage(targetContext) == Language.ENGLISH)
    }

    @After
    fun tearDown() {
        broadcast(ACTION_PIN_DONE, "--es result CANCELLED")
        shell("appops set $PACKAGE SYSTEM_ALERT_WINDOW default")
        resetLauncherState()
    }

    @Test
    fun choosingALanguageInSettingsTranslatesTheScreensAtOnce() {
        launchHome().use { scenario ->
            openSettings(scenario)
            // The language row is above the first row that has focus.
            device.pressDPadUp()
            assertFocusedRow("Language, System default (English)")
            device.pressDPadRight()
            assertFocusedRow("Language, English")
            assertEquals(Language.ENGLISH, AppLanguage.chosen(targetContext))

            // The screen is recreated in Chinese with the language row still focused, without a restart.
            device.pressDPadRight()
            assertFocusedRow("语言, 简体中文")
            assertTrue(device.hasObject(By.text("设置")))
            assertTrue(device.hasObject(By.desc("保持屏幕常亮, 开启")))
            assertEquals(Language.CHINESE_SIMPLIFIED, AppLanguage.chosen(targetContext))

            // Home, behind the settings, is in Chinese when it comes back.
            device.pressBack()
            assertTrue("home is back", device.wait(Until.hasObject(By.res(PACKAGE, "app_grid")), TIMEOUT))
            waitUntil("home in Chinese") { scenario.builtInLabels() == listOf("设置", "网络", "AirPlay", "屏保") }
            assertTrue(device.hasObject(By.desc("启动器设置")))

            // Left steps back: English, then the system language.
            openSettings(scenario)
            device.pressDPadUp()
            assertFocusedRow("语言, 简体中文")
            device.pressDPadLeft()
            assertFocusedRow("Language, English")
            device.pressDPadLeft()
            assertFocusedRow("Language, System default (English)")
            assertEquals(null, AppLanguage.chosen(targetContext))
            device.pressBack()
            waitUntil("home in English again") { scenario.builtInLabels() == BUILT_IN_LABELS }
        }
    }

    @Test
    fun theAirPlayPinUsesTheChosenLanguage() {
        shell("appops set $PACKAGE SYSTEM_ALERT_WINDOW allow")
        setAppLanguage(Language.FRENCH)
        assertPinReads("4817", "Anna's MacBook Pro veut utiliser AirPlay. Saisissez ce code sur votre appareil.")

        // The :airplay process keeps running; it follows a change made after it started.
        setAppLanguage(Language.JAPANESE)
        assertPinReads("2580", "Anna's MacBook Pro が AirPlay で接続しようとしています。デバイスでこのコードを入力してください。")

        setAppLanguage(null)
        assertPinReads("1357", "Anna's MacBook Pro wants to AirPlay. Enter this code on your device.")
    }

    /** From home: up to the gear, OK, and the settings list is showing. */
    private fun openSettings(scenario: ActivityScenario<HomeActivity>) {
        device.pressDPadUp()
        waitUntil("focus on the settings button") { scenario.read { it.currentFocus?.id == R.id.settings_button } }
        device.pressDPadCenter()
        assertTrue("settings opened", device.wait(Until.hasObject(By.res(PACKAGE, "settings_list")), TIMEOUT))
    }

    private fun assertFocusedRow(description: String) {
        assertTrue(description, device.wait(Until.hasObject(By.desc(description).focused(true)), TIMEOUT))
    }

    private fun ActivityScenario<HomeActivity>.builtInLabels(): List<String> =
        read { it.tileLabels() }.take(BUILT_IN_LABELS.size)

    /** Shows [pin] through the receiver's PIN event, checks the prompt reads [prompt], and ends it. */
    private fun assertPinReads(
        pin: String,
        prompt: String,
    ) {
        broadcast(ACTION_SHOW_PIN, "--es pin $pin")
        assertTrue("digits shown", device.wait(Until.hasObject(By.text(pin)), TIMEOUT))
        assertTrue(prompt, device.hasObject(By.text(prompt)))
        broadcast(ACTION_PIN_DONE, "--es result CANCELLED")
        assertTrue("digits gone", device.wait(Until.gone(By.text(pin)), TIMEOUT))
    }

    /** Fires a receiver event in the `:airplay` process through the debug hooks (adb's shell holds DUMP). */
    private fun broadcast(
        action: String,
        extras: String,
    ) {
        shell("am broadcast -p $PACKAGE -a $action $extras")
    }

    private companion object {
        const val TIMEOUT = 10_000L
        const val ACTION_SHOW_PIN = "ai.aneesiqbal.rozan.airplay.debug.SHOW_PIN"
        const val ACTION_PIN_DONE = "ai.aneesiqbal.rozan.airplay.debug.PIN_DONE"
    }
}
