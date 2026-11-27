package ai.aneesiqbal.rozan.launcher.inputs

import ai.aneesiqbal.rozan.launcher.HomeActivity
import ai.aneesiqbal.rozan.launcher.PACKAGE
import ai.aneesiqbal.rozan.launcher.R
import ai.aneesiqbal.rozan.launcher.SystemIntents
import ai.aneesiqbal.rozan.launcher.device
import ai.aneesiqbal.rozan.launcher.focusedTileLabel
import ai.aneesiqbal.rozan.launcher.launchHome
import ai.aneesiqbal.rozan.launcher.pressGamepadButton
import ai.aneesiqbal.rozan.launcher.read
import ai.aneesiqbal.rozan.launcher.resetLauncherState
import ai.aneesiqbal.rozan.launcher.targetContext
import ai.aneesiqbal.rozan.launcher.waitUntil
import android.content.ComponentName
import android.content.Intent
import android.graphics.Color
import android.media.tv.TvContract
import android.view.KeyEvent
import android.widget.ImageView
import androidx.core.graphics.drawable.toDrawable
import androidx.core.view.isVisible
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The Inputs button and menu, with a fake list of inputs in place of the
 * system's (TvInputFrameworkTest covers the real TvInputManager), and no
 * system input list unless a test gives a fake one (InputPickerTest covers
 * the real ones). Runs on any device: the intents it sends are recorded, not
 * started.
 */
@RunWith(AndroidJUnit4::class)
class InputsMenuTest {
    private val inputs =
        listOf(
            hdmi("hdmi1", "HDMI 1", customLabel = "Apple TV"),
            hdmi("hdmi2", "HDMI 2", state = InputState.STANDBY),
            hdmi("cec-ps5", "PlayStation 5", parentId = "hdmi2", state = InputState.STANDBY),
            hdmi("hdmi3", "HDMI 3", state = InputState.DISCONNECTED),
            hdmi("hdmi4", "HDMI 4", hidden = true),
            InputRecord("tuner", InputKind.TUNER, false, "TV", null, false, null, InputState.CONNECTED),
        )

    @Before
    fun setUp() {
        resetLauncherState()
        // No system input list unless a test gives one: the emulator has two.
        TvInputSources.testPickerResolver = FakePickerResolver()
    }

    @After
    fun tearDown() {
        TvInputSources.testSource = null
        TvInputSources.testPickerResolver = null
    }

    private fun install(source: FakeTvInputSource): FakeTvInputSource = source.also { TvInputSources.testSource = it }

    @Test
    fun theButtonShowsOnlyWhileThereIsAnInputToSwitchTo() {
        val source = install(FakeTvInputSource(listOf(inputs.last())))
        launchHome().use { scenario ->
            device.waitForIdle()
            assertFalse("a tuner alone shows no button", scenario.inputsButtonVisible)
            assertEquals(InputsButtonMode.HIDDEN, scenario.read { it.inputsMode })

            device.pressDPadUp()
            waitUntil("focus on the gear") { scenario.focusedId() == R.id.settings_button }
            device.pressDPadLeft()
            device.waitForIdle()
            assertEquals("left stays on the gear without the button", R.id.settings_button, scenario.focusedId())

            source.emit(inputs)
            waitUntil("button shown when an HDMI input appears") { scenario.inputsButtonVisible }
            device.pressDPadLeft()
            waitUntil("left moves to Inputs") { scenario.focusedId() == R.id.inputs_button }
            device.pressDPadLeft()
            device.waitForIdle()
            assertEquals("focus stays in the top bar", R.id.inputs_button, scenario.focusedId())

            source.emit(listOf(hdmi("hdmi1", "HDMI 1", hidden = true)))
            waitUntil("button hidden when every input is hidden") { !scenario.inputsButtonVisible }
            assertEquals("focus moved to the gear", R.id.settings_button, scenario.focusedId())

            source.emit(listOf(hdmi("hdmi1", "HDMI 1")))
            waitUntil("button back") { scenario.inputsButtonVisible }
            device.pressDPadLeft()
            waitUntil("focus on Inputs") { scenario.focusedId() == R.id.inputs_button }
            device.pressDPadDown()
            waitUntil("down from Inputs goes to the tiles") { scenario.focusedTileLabel() == "Settings" }
        }
    }

    @Test
    fun theMenuListsTheInputsWithStatesAndKeepsFocus() {
        val icon = Color.RED.toDrawable()
        install(FakeTvInputSource(inputs, icons = mapOf("cec-ps5" to icon)))
        launchHome().use { scenario ->
            waitUntil("button shown") { scenario.inputsButtonVisible }
            scenario.openMenuWithDpad()

            assertEquals(
                listOf(
                    listOf("Apple TV", null, null, false),
                    listOf("HDMI 2", null, "Standby", true),
                    listOf("PlayStation 5", "HDMI 2", "Standby", true),
                    listOf("HDMI 3", null, "Not connected", true),
                ),
                scenario.menuRows(),
            )
            assertEquals("focus starts on the first input", "Apple TV", scenario.focusedRowTitle())
            assertEquals(
                "only the input with its own icon shows one",
                listOf(false, false, true, false),
                scenario.read { home ->
                    home.findViewById<InputsPanel>(R.id.inputs_panel).inputRows().map {
                        it.findViewById<ImageView>(R.id.input_icon).isVisible
                    }
                },
            )
            assertEquals(
                "Apple TV",
                scenario.read { it.findViewById<InputsPanel>(R.id.inputs_panel).inputRows()[0].contentDescription },
            )
            assertEquals(
                "PlayStation 5, HDMI 2, Standby",
                scenario.read { it.findViewById<InputsPanel>(R.id.inputs_panel).inputRows()[2].contentDescription },
            )

            repeat(3) { device.pressDPadDown() }
            waitUntil("focus on the last input") { scenario.focusedRowTitle() == "HDMI 3" }
            device.pressDPadDown()
            device.pressDPadLeft()
            device.pressDPadRight()
            device.pressDPadUp()
            waitUntil("focus stays inside the menu") { scenario.focusedRowTitle() == "PlayStation 5" }
            repeat(3) { device.pressDPadUp() }
            waitUntil("up from the first row stays in the menu") { scenario.focusedRowTitle() == "Apple TV" }

            device.pressBack()
            waitUntil("Back closes the menu") { !scenario.menuShowing }
            waitUntil("focus back on Inputs") { scenario.focusedId() == R.id.inputs_button }
            assertEquals(PACKAGE, device.currentPackageName)

            device.pressDPadCenter()
            waitUntil("menu open again") { scenario.menuShowing }
            pressGamepadButton(KeyEvent.KEYCODE_BUTTON_B)
            waitUntil("B closes it") { !scenario.menuShowing }

            device.pressDPadCenter()
            waitUntil("menu open again") { scenario.menuShowing }
            device.pressKeyCode(KeyEvent.KEYCODE_MENU)
            waitUntil("Menu closes it") { !scenario.menuShowing }
        }
    }

    @Test
    fun selectingAnInputSendsItsPassthroughIntent() {
        install(FakeTvInputSource(inputs))
        val recorder = InputIntentRecorder()
        launchHome().use { scenario ->
            waitUntil("button shown") { scenario.inputsButtonVisible }
            scenario.openMenuWithDpad()
            repeat(2) { device.pressDPadDown() }
            waitUntil("focus on PlayStation 5") { scenario.focusedRowTitle() == "PlayStation 5" }

            recording(recorder) {
                device.pressDPadCenter()
                waitUntil("intent sent") { recorder.intents.isNotEmpty() }
            }

            val intent = recorder.intents.single()
            val expected = SystemIntents.passthroughInput(targetContext, "cec-ps5").first()
            assertEquals("a system TV app by name where there is one", expected.component, intent.component)
            assertEquals(Intent.ACTION_VIEW, intent.action)
            assertEquals(TvContract.buildChannelUriForPassthroughInput("cec-ps5"), intent.data)
            assertEquals("content://android.media.tv/passthrough/cec-ps5", intent.data.toString())
            assertTrue(intent.flags and Intent.FLAG_ACTIVITY_NEW_TASK != 0)
            waitUntil("the menu closes once the TV switches") { !scenario.menuShowing }
        }
    }

    @Test
    fun whenNothingSwitchesTheMenuSaysSoAndOffersTheSystemList() {
        install(FakeTvInputSource(inputs))
        val picker = ComponentName("com.example.tv", "com.example.tv.InputsActivity")
        TvInputSources.testPickerResolver = FakePickerResolver(listOf(picker))
        val recorder = InputIntentRecorder(noTvApp = true)
        launchHome().use { scenario ->
            waitUntil("button shown") { scenario.inputsButtonVisible }
            assertEquals("Rozan's menu wins over the TV's list", InputsButtonMode.MENU, scenario.read { it.inputsMode })
            scenario.openMenuWithDpad()

            recording(recorder) {
                device.pressDPadCenter()
                waitUntil("message shown") { scenario.panelMessage() != null }
                assertEquals("Rozan can't switch to Apple TV on this TV.", scenario.panelMessage())
                assertTrue("the menu stays open", scenario.menuShowing)
                waitUntil("focus on the system list row") {
                    scenario.focusedSettingsRowTitle() ==
                        "Open the TV's input list"
                }
                device.pressDPadCenter()
                waitUntil("system list opened") { recorder.intents.any { it.action == ACTION_VIEW_INPUTS } }
                val sent = recorder.intents.single { it.action == ACTION_VIEW_INPUTS }
                assertEquals("opened by component, never through a chooser", picker, sent.component)
                waitUntil("menu closed") { !scenario.menuShowing }
            }
        }
    }

    @Test
    fun withoutASystemListTheMenuOnlySaysItCannotSwitch() {
        install(FakeTvInputSource(inputs))
        launchHome().use { scenario ->
            waitUntil("button shown") { scenario.inputsButtonVisible }
            scenario.openMenuWithDpad()
            recording(InputIntentRecorder(noTvApp = true)) {
                device.pressDPadCenter()
                waitUntil("message shown") { scenario.panelMessage() != null }
            }
            assertEquals("Apple TV", scenario.focusedRowTitle())
            assertNull(
                "no row for a list there is not",
                scenario.read {
                    it.findViewById<InputsPanel>(R.id.inputs_panel).pickerRowShown
                },
            )
        }
    }

    @Test
    fun whenTheTvListWillNotOpenEitherTheMenuSaysSoAndDropsTheRow() {
        install(FakeTvInputSource(inputs))
        TvInputSources.testPickerResolver = FakePickerResolver(listOf(MISSING_PICKER))
        launchHome().use { scenario ->
            waitUntil("button shown") { scenario.inputsButtonVisible }
            scenario.openMenuWithDpad()
            // Switching fails (recorded); the list's start goes to the system, which has no such app.
            recording(InputIntentRecorder(noTvApp = true, passPickers = true)) {
                device.pressDPadCenter()
                waitUntil("focus on the system list row") {
                    scenario.focusedSettingsRowTitle() ==
                        "Open the TV's input list"
                }
                device.pressDPadCenter()
                waitUntil("second message") { scenario.panelMessage() == PICKER_FAILED }
            }
            assertTrue("the menu stays open", scenario.menuShowing)
            assertEquals("focus back on the first input", "Apple TV", scenario.focusedRowTitle())
            assertEquals(PACKAGE, device.currentPackageName)

            // The list is not offered again: selecting an input that fails shows no row.
            recording(InputIntentRecorder(noTvApp = true)) {
                device.pressDPadDown()
                waitUntil("focus on HDMI 2") { scenario.focusedRowTitle() == "HDMI 2" }
                device.pressDPadCenter()
                waitUntil(
                    "message for HDMI 2",
                ) { scenario.panelMessage() == "Rozan can't switch to HDMI 2 on this TV." }
            }
            assertEquals("HDMI 2", scenario.focusedRowTitle())
            assertEquals("the menu still works", InputsButtonMode.MENU, scenario.read { it.inputsMode })
        }
    }

    @Test
    fun theMenuFollowsChangesWhileOpen() {
        val source = install(FakeTvInputSource(inputs))
        launchHome().use { scenario ->
            waitUntil("button shown") { scenario.inputsButtonVisible }
            scenario.openMenuWithDpad()
            device.pressDPadDown()
            waitUntil("focus on HDMI 2") { scenario.focusedRowTitle() == "HDMI 2" }

            // The PlayStation wakes up and a new input appears: focus stays on HDMI 2.
            source.emit(
                inputs.map {
                    if (it.id == "cec-ps5" ||
                        it.id == "hdmi2"
                    ) {
                        it.copy(state = InputState.CONNECTED)
                    } else {
                        it
                    }
                } +
                    InputRecord("av", InputKind.COMPOSITE, true, "AV", null, false, null, InputState.CONNECTED),
            )
            waitUntil("rows updated") { scenario.menuRows().size == 5 }
            assertEquals(listOf("HDMI 2", null, null, false), scenario.menuRows()[1])
            assertEquals("AV", scenario.menuRows().last()[0])
            assertEquals("HDMI 2", scenario.focusedRowTitle())

            // The focused input goes away: focus moves to the first row.
            source.emit(inputs.filter { it.id != "hdmi2" && it.id != "cec-ps5" })
            waitUntil("rows updated") { scenario.menuRows().size == 2 }
            waitUntil("focus on the first row") { scenario.focusedRowTitle() == "Apple TV" }

            // Every input goes away: the menu closes and focus goes to the gear.
            source.emit(emptyList())
            waitUntil("menu closed") { !scenario.menuShowing }
            waitUntil("button hidden") { !scenario.inputsButtonVisible }
            waitUntil("focus on the gear") { scenario.focusedId() == R.id.settings_button }
        }
    }

    @Test
    fun homeAndTheScreensaverCloseTheMenu() {
        install(FakeTvInputSource(inputs))
        launchHome().use { scenario ->
            waitUntil("button shown") { scenario.inputsButtonVisible }
            scenario.openMenuWithDpad()

            scenario.onActivity { it.onScreensaverStarted() }
            waitUntil("screensaver closed the menu") { !scenario.menuShowing }
            scenario.onActivity { it.onScreensaverStopped() }

            scenario.read { it.findViewById<android.view.View>(R.id.inputs_button).requestFocus() }
            device.pressDPadCenter()
            waitUntil("menu open") { scenario.menuShowing }
            // What a Home press delivers while Rozan is the home screen.
            targetContext.startActivity(
                Intent(targetContext, HomeActivity::class.java)
                    .setAction(Intent.ACTION_MAIN)
                    .addCategory(Intent.CATEGORY_HOME)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
            waitUntil("Home closed the menu") { !scenario.menuShowing }
            assertNull("no message left over", scenario.panelMessage())
        }
    }
}
