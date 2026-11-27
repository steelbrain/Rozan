package ai.aneesiqbal.rozan.launcher.inputs

import ai.aneesiqbal.rozan.airplay.android.AppLanguage.Language
import ai.aneesiqbal.rozan.launcher.PACKAGE
import ai.aneesiqbal.rozan.launcher.R
import ai.aneesiqbal.rozan.launcher.device
import ai.aneesiqbal.rozan.launcher.launchHome
import ai.aneesiqbal.rozan.launcher.read
import ai.aneesiqbal.rozan.launcher.resetLauncherState
import ai.aneesiqbal.rozan.launcher.setAppLanguage
import ai.aneesiqbal.rozan.launcher.targetContext
import ai.aneesiqbal.rozan.launcher.waitUntil
import android.content.ComponentName
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Screenshots of the Inputs button and menu with a fake set of inputs, and of
 * the button opening the device's own input picker, for looking at, not for
 * comparing. Runs only when asked for:
 * `-Pandroid.testInstrumentationRunnerArguments.screenshots=true`. The PNGs go
 * to the app's external files directory, under `inputs-screenshots`.
 */
@RunWith(AndroidJUnit4::class)
class InputsScreenshots {
    @Before
    fun setUp() {
        assumeTrue(
            "screenshots only on request",
            InstrumentationRegistry.getArguments().getString("screenshots") == "true",
        )
        resetLauncherState()
    }

    @After
    fun tearDown() {
        TvInputSources.testSource = null
        TvInputSources.testPickerResolver = null
        TvInputSources.testFireOs = null
        setAppLanguage(null)
    }

    private val dir: File get() = File(targetContext.getExternalFilesDir(null), "inputs-screenshots").apply { mkdirs() }

    /**
     * No inputs Rozan can switch, as on Fire OS (which the emulator is told it
     * is): the button opens the emulator's real picker, then one that will not
     * open.
     */
    @Test
    fun systemPicker() {
        val pickers = InputPickers.find(SystemPickerResolver(targetContext))
        assumeTrue("no system input picker on this device", pickers.isNotEmpty())
        TvInputSources.testSource = FakeTvInputSource(emptyList())
        TvInputSources.testFireOs = true
        launchHome().use { scenario ->
            waitUntil("button shown") { scenario.inputsButtonVisible }
            scenario.focusInputsButton()
            settle()
            device.takeScreenshot(File(dir, "picker-1-button-focused.png"))

            device.pressDPadCenter()
            waitUntil(
                "picker in front",
                timeoutMs = 10_000,
            ) { device.currentPackageName == pickers.first().packageName }
            settle()
            device.takeScreenshot(File(dir, "picker-2-system-picker.png"))
            device.pressBack()
            waitUntil("back on Rozan", timeoutMs = 10_000) { device.currentPackageName == PACKAGE }
        }

        TvInputSources.testPickerResolver = FakePickerResolver(listOf(MISSING_PICKER))
        launchHome().use { scenario ->
            waitUntil("button shown") { scenario.inputsButtonVisible }
            scenario.focusInputsButton()
            expectToast(PICKER_FAILED) { device.pressDPadCenter() }
            waitUntil("button hidden") { !scenario.inputsButtonVisible }
            settle()
            device.takeScreenshot(File(dir, "picker-3-failed.png"))
        }
    }

    @Test
    fun english() {
        shoot(
            "en",
            null,
            listOf(
                hdmi("hdmi1", "HDMI 1", customLabel = "Apple TV"),
                hdmi("hdmi2", "HDMI 2", state = InputState.STANDBY),
                hdmi("cec-ps5", "PlayStation 5", parentId = "hdmi2", state = InputState.STANDBY),
                hdmi("hdmi3", "HDMI 3 (eARC)", state = InputState.DISCONNECTED),
                InputRecord("av", InputKind.COMPOSITE, true, "AV", null, false, null, InputState.CONNECTED),
            ),
        )
    }

    @Test
    fun russianWithLongNames() {
        shoot(
            "ru",
            Language.RUSSIAN,
            listOf(
                hdmi("hdmi1", "HDMI 1", customLabel = "Игровая приставка в гостиной у окна"),
                hdmi(
                    "hdmi2",
                    "HDMI 2",
                    customLabel = "Ресивер домашнего кинотеатра в спальне",
                    state = InputState.STANDBY,
                ),
                hdmi("cec", "Проигрыватель Blu-ray", parentId = "hdmi2", state = InputState.STANDBY),
                hdmi("hdmi3", "HDMI 3", state = InputState.DISCONNECTED),
                InputRecord(
                    "av",
                    InputKind.COMPONENT,
                    true,
                    "Компонентный",
                    null,
                    false,
                    null,
                    InputState.DISCONNECTED,
                ),
            ),
        )
    }

    private fun shoot(
        name: String,
        language: Language?,
        records: List<InputRecord>,
    ) {
        setAppLanguage(language)
        TvInputSources.testSource = FakeTvInputSource(records)
        // A stand-in for the TV's own list, so that the failed switch offers it.
        TvInputSources.testPickerResolver =
            FakePickerResolver(listOf(ComponentName("com.example.tv", "com.example.tv.InputsActivity")))
        launchHome().use { scenario ->
            waitUntil("button shown") { scenario.inputsButtonVisible }
            settle()
            device.takeScreenshot(File(dir, "$name-1-home.png"))

            device.pressDPadUp()
            device.pressDPadLeft()
            waitUntil("focus on Inputs") { scenario.focusedId() == R.id.inputs_button }
            settle()
            device.takeScreenshot(File(dir, "$name-2-button-focused.png"))

            device.pressDPadCenter()
            waitUntil("menu open") { scenario.menuShowing }
            settle()
            device.takeScreenshot(File(dir, "$name-3-menu.png"))

            device.pressDPadDown()
            device.pressDPadDown()
            settle()
            device.takeScreenshot(File(dir, "$name-4-menu-dimmed-focused.png"))

            recording(InputIntentRecorder(noTvApp = true)) {
                device.pressDPadCenter()
                waitUntil("message") {
                    scenario.read { it.findViewById<InputsPanel>(R.id.inputs_panel).messageText } != null
                }
                settle()
                device.takeScreenshot(File(dir, "$name-5-switch-failed.png"))
            }
        }
    }

    /** Lets the focus animation (180 ms) finish before a screenshot. */
    private fun settle() {
        device.waitForIdle()
        Thread.sleep(SETTLE_MS)
    }

    private companion object {
        const val SETTLE_MS = 400L
    }
}
