package ai.aneesiqbal.rozan.launcher.inputs

import ai.aneesiqbal.rozan.launcher.PACKAGE
import ai.aneesiqbal.rozan.launcher.R
import ai.aneesiqbal.rozan.launcher.device
import ai.aneesiqbal.rozan.launcher.instrumentation
import ai.aneesiqbal.rozan.launcher.launchHome
import ai.aneesiqbal.rozan.launcher.read
import ai.aneesiqbal.rozan.launcher.redirect.HomeSetupReader
import ai.aneesiqbal.rozan.launcher.resetLauncherState
import ai.aneesiqbal.rozan.launcher.shell
import ai.aneesiqbal.rozan.launcher.targetContext
import ai.aneesiqbal.rozan.launcher.waitUntil
import android.content.ComponentName
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeFalse
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The Inputs button where Rozan cannot list or switch inputs itself. On Fire
 * OS it opens the TV's own input picker; the emulator is not Fire OS, so these
 * tests say it is through a debug-only hook ([TvInputSources.testFireOs]),
 * except the one that checks the button stays hidden elsewhere. The device's
 * real pickers are found through its package manager (on the API 36 emulator,
 * Live TV's and the classic home's, docs/research/tv-inputs.md 10.1) and
 * really started; a fake picker stands in for one that will not start. The
 * inputs are always a fake empty list, as on Fire OS or a box.
 */
@RunWith(AndroidJUnit4::class)
class InputPickerTest {
    private val testPackage: String get() = instrumentation.context.packageName
    private val restore = ArrayList<String>()

    @Before
    fun setUp() {
        resetLauncherState()
        TvInputSources.testSource = FakeTvInputSource(emptyList())
        TvInputSources.testFireOs = true
    }

    @After
    fun tearDown() {
        TvInputSources.testSource = null
        TvInputSources.testPickerResolver = null
        TvInputSources.testFireOs = null
        for (command in restore.asReversed()) shell(command)
    }

    /** The device's own pickers, best first; skips the test where there is none. */
    private fun systemPickers(): List<PickerTarget> {
        val pickers = InputPickers.find(SystemPickerResolver(targetContext))
        Log.i(TAG, "System input pickers: ${pickers.map { it.component.flattenToShortString() }}")
        assumeTrue("no system input picker on this device", pickers.isNotEmpty())
        return pickers
    }

    @Test
    fun offFireOsAPickerAloneShowsNoButton() {
        // The emulator as it is: not Fire OS, no input Rozan can switch, two pickers that resolve.
        TvInputSources.testFireOs = null
        assumeFalse("this device is Fire OS", HomeSetupReader.isFireOs(targetContext))
        systemPickers()
        launchHome().use { scenario ->
            waitUntil("inputs and pickers looked up") { scenario.read { it.inputsRead } }
            assertEquals(InputsButtonMode.HIDDEN, scenario.read { it.inputsMode })
            assertFalse("no button", scenario.inputsButtonVisible)

            device.pressDPadUp()
            waitUntil("focus on the gear") { scenario.focusedId() == R.id.settings_button }
            device.pressDPadLeft()
            device.waitForIdle()
            assertEquals("left stays on the gear", R.id.settings_button, scenario.focusedId())
        }
    }

    @Test
    fun theButtonOpensTheSystemsPickerByComponent() {
        val picker = systemPickers().first()
        launchHome().use { scenario ->
            waitUntil("button shown") { scenario.inputsButtonVisible }
            assertEquals(InputsButtonMode.PICKER, scenario.read { it.inputsMode })
            scenario.focusInputsButton()

            shell("logcat -c")
            device.pressDPadCenter()
            val name = picker.component.flattenToShortString()
            waitUntil("the system started $name", timeoutMs = 10_000) {
                activityStarts().any { it.contains("cmp=$name") }
            }
            waitUntil("${picker.packageName} in front, with no chooser", timeoutMs = 10_000) {
                device.currentPackageName == picker.packageName
            }
            assertFalse("Rozan's menu stays closed", scenario.menuShowing)
            assertTrue(
                "only the picker was started",
                activityStarts().none { it.contains("ResolverActivity") || it.contains("ChooserActivity") },
            )
            device.pressBack()
            waitUntil("Back returns to Rozan", timeoutMs = 10_000) { device.currentPackageName == PACKAGE }
        }
    }

    @Test
    fun withoutAPickerTheButtonGoesAndComesBackWithIt() {
        // Every app with a picker, Live TV and the classic home on the emulator, switched off and on again
        // while the home screen shows: the package manager's broadcasts make it look again.
        val packages = systemPickers().map { it.packageName }.distinct()
        assumeTrue("a picker in Rozan's own test", packages.none { it == PACKAGE || it == testPackage })
        launchHome().use { scenario ->
            waitUntil("button shown") { scenario.inputsButtonVisible }
            scenario.focusInputsButton()

            for (name in packages) {
                restore += "pm enable $name"
                shell("pm disable-user --user 0 $name")
            }
            waitUntil("no picker left") { InputPickers.find(SystemPickerResolver(targetContext)).isEmpty() }
            waitUntil("button hidden", timeoutMs = 15_000) { !scenario.inputsButtonVisible }
            assertEquals(InputsButtonMode.HIDDEN, scenario.read { it.inputsMode })
            assertEquals("focus moved to the gear", R.id.settings_button, scenario.focusedId())

            for (name in packages) shell("pm enable $name")
            waitUntil("button back", timeoutMs = 15_000) { scenario.inputsButtonVisible }
            assertEquals(InputsButtonMode.PICKER, scenario.read { it.inputsMode })
        }
    }

    @Test
    fun aPickerThatWillNotStartIsReportedAndTheButtonGoesUntilTheAppsChange() {
        // The package manager lists a picker that no app on the device has, so the start really fails.
        TvInputSources.testPickerResolver = FakePickerResolver(listOf(MISSING_PICKER))
        launchHome().use { scenario ->
            waitUntil("button shown") { scenario.inputsButtonVisible }
            scenario.focusInputsButton()

            expectToast(PICKER_FAILED) { device.pressDPadCenter() }
            waitUntil("button hidden") { !scenario.inputsButtonVisible }
            assertEquals("focus moved to the gear", R.id.settings_button, scenario.focusedId())
            assertEquals(PACKAGE, device.currentPackageName)

            // Coming back to the front looks again, and finds the same picker: it stays away.
            scenario.pauseAndResume()
            assertFalse("still hidden after coming back", scenario.inputsButtonVisible)
            assertEquals(InputsButtonMode.HIDDEN, scenario.read { it.inputsMode })

            // An app changing (here the test APK, switched off and on) is when it may start again.
            restore += "pm enable $testPackage"
            shell("pm disable-user --user 0 $testPackage")
            shell("pm enable $testPackage")
            waitUntil("button back after the apps changed", timeoutMs = 15_000) { scenario.inputsButtonVisible }
        }
    }

    @Test
    fun aPickerThatRefusesRozanIsSkippedForTheNext() {
        val refused = ComponentName("com.example.vendor.tv", "com.example.vendor.tv.InputSelectionActivity")
        val next = ComponentName("com.example.tv", "com.example.tv.SelectInputActivity")
        TvInputSources.testPickerResolver = FakePickerResolver(listOf(refused, next))
        val recorder = InputIntentRecorder(refusedPickers = setOf(refused))
        launchHome().use { scenario ->
            waitUntil("button shown") { scenario.inputsButtonVisible }
            scenario.focusInputsButton()

            recording(recorder) {
                device.pressDPadCenter()
                waitUntil("a picker opened") { recorder.intents.size == 2 }
                assertEquals(listOf(refused, next), recorder.intents.map { it.component })
                assertTrue("the button stays: a picker opened", scenario.inputsButtonVisible)

                device.pressDPadCenter()
                waitUntil("opened again") { recorder.intents.size == 3 }
                assertEquals("the refused picker is not tried again", next, recorder.intents.last().component)
            }
        }
    }

    private companion object {
        const val TAG = "InputPickerTest"
    }
}
