package ai.aneesiqbal.rozan.airplay.android

import ai.aneesiqbal.rozan.airplay.android.debug.DebugEventsReceiver
import android.view.KeyEvent
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The session screens, driven end to end through the debug event hooks: the
 * broadcast reaches the `:airplay` process, which raises the same receiver
 * events a real client would.
 */
@RunWith(AndroidJUnit4::class)
class SessionUiTest {
    private val device = UiDevice.getInstance(instrumentation)
    private val pkg = testContext.packageName

    @Before
    fun setUp() {
        AirPlaySettings(testContext).deviceName = "Mango Kiwi Plum Fig"
        device.pressHome()
    }

    @After
    fun tearDown() {
        event(DebugEventsReceiver.ACTION_PIN_DONE)
        event(DebugEventsReceiver.ACTION_STOP_MIRROR)
        event(DebugEventsReceiver.ACTION_STOP_AUDIO)
        AirPlaySettings(testContext).enabled = false
        setOverlay(true)
    }

    @Test
    fun pinOverlayAppearsOverOtherAppsAndGoes() {
        setOverlay(true)
        event(DebugEventsReceiver.ACTION_SHOW_PIN, "--es pin 4817")
        assertTrue("digits shown", device.wait(Until.hasObject(By.text("4817")), TIMEOUT))
        assertTrue(device.hasObject(By.text("Anna's MacBook Pro wants to AirPlay. Enter this code on your device.")))
        assertTrue(device.hasObject(By.text("AirPlay to Mango Kiwi Plum Fig")))
        event(DebugEventsReceiver.ACTION_PIN_DONE, "--es result ACCEPTED")
        assertTrue("digits gone", device.wait(Until.gone(By.text("4817")), TIMEOUT))
    }

    @Test
    fun aFreshPinReplacesTheOldOne() {
        setOverlay(true)
        event(DebugEventsReceiver.ACTION_SHOW_PIN, "--es pin 1111")
        assertTrue(device.wait(Until.hasObject(By.text("1111")), TIMEOUT))
        event(DebugEventsReceiver.ACTION_SHOW_PIN, "--es pin 2222")
        assertTrue(device.wait(Until.hasObject(By.text("2222")), TIMEOUT))
        assertFalse(device.hasObject(By.text("1111")))
        event(DebugEventsReceiver.ACTION_PIN_DONE, "--es result CANCELLED")
        assertTrue(device.wait(Until.gone(By.text("2222")), TIMEOUT))
    }

    @Test
    fun aFinishForAnotherClientLeavesTheCodeOnScreen() {
        setOverlay(true)
        event(DebugEventsReceiver.ACTION_SHOW_PIN, "--es pin 3141")
        assertTrue(device.wait(Until.hasObject(By.text("3141")), TIMEOUT))
        event(DebugEventsReceiver.ACTION_PIN_DONE, "--es result CANCELLED --es address 192.0.2.99")
        assertFalse("still on screen", device.wait(Until.gone(By.text("3141")), NEGATIVE_WAIT))
        event(DebugEventsReceiver.ACTION_PIN_DONE, "--es result ACCEPTED")
        assertTrue(device.wait(Until.gone(By.text("3141")), TIMEOUT))
    }

    @Test
    fun withoutTheOverlayPermissionThePinIsAnActivity() {
        setOverlay(false)
        event(DebugEventsReceiver.ACTION_SHOW_PIN, "--es pin 5309")
        assertTrue("digits shown", device.wait(Until.hasObject(By.text("5309")), TIMEOUT))
        event(DebugEventsReceiver.ACTION_PIN_DONE, "--es result REJECTED")
        assertTrue("digits gone", device.wait(Until.gone(By.text("5309")), TIMEOUT))
    }

    @Test
    fun mirroringTakesOverTheScreenAndBackEndsIt() {
        setOverlay(true)
        AirPlaySettings(testContext).enabled = true
        event(DebugEventsReceiver.ACTION_START_MIRROR)
        val surface = By.res(pkg, "airplay_mirror_surface")
        assertTrue("mirror screen in front", device.wait(Until.hasObject(surface), TIMEOUT))
        assertTrue(device.hasObject(By.desc("Screen mirrored from Anna's MacBook Pro")))
        device.pressBack()
        assertTrue("closed on Back", device.wait(Until.gone(surface), TIMEOUT))
        event(DebugEventsReceiver.ACTION_STOP_MIRROR)
    }

    @Test
    fun aControllerAKeepsMirroringAndBEndsIt() {
        setOverlay(true)
        AirPlaySettings(testContext).enabled = true
        event(DebugEventsReceiver.ACTION_START_MIRROR)
        val surface = By.res(pkg, "airplay_mirror_surface")
        assertTrue("mirror screen in front", device.wait(Until.hasObject(surface), TIMEOUT))
        pressGamepadButton(KeyEvent.KEYCODE_BUTTON_A)
        assertFalse("A does not end mirroring", device.wait(Until.gone(surface), NEGATIVE_WAIT))
        pressGamepadButton(KeyEvent.KEYCODE_BUTTON_B)
        assertTrue("closed on B", device.wait(Until.gone(surface), TIMEOUT))
        event(DebugEventsReceiver.ACTION_STOP_MIRROR)
    }

    @Test
    fun mirroringScreenClosesWhenTheSenderStops() {
        setOverlay(true)
        AirPlaySettings(testContext).enabled = true
        event(DebugEventsReceiver.ACTION_START_MIRROR)
        val surface = By.res(pkg, "airplay_mirror_surface")
        assertTrue(device.wait(Until.hasObject(surface), TIMEOUT))
        event(DebugEventsReceiver.ACTION_STOP_MIRROR)
        assertTrue(device.wait(Until.gone(surface), TIMEOUT))
    }

    @Test
    fun audioShowsNowPlayingAndBackLeavesItPlaying() {
        setOverlay(true)
        AirPlaySettings(testContext).enabled = true
        event(DebugEventsReceiver.ACTION_START_AUDIO)
        assertTrue(device.wait(Until.hasObject(By.text("Test tone, 440 Hz")), TIMEOUT))
        assertTrue(device.hasObject(By.text("Playing from Anna's MacBook Pro")))
        device.pressBack()
        assertTrue(device.wait(Until.gone(By.text("Test tone, 440 Hz")), TIMEOUT))
        event(DebugEventsReceiver.ACTION_STOP_AUDIO)
    }

    @Test
    fun aControllerBLeavesNowPlaying() {
        setOverlay(true)
        AirPlaySettings(testContext).enabled = true
        event(DebugEventsReceiver.ACTION_START_AUDIO)
        assertTrue(device.wait(Until.hasObject(By.text("Test tone, 440 Hz")), TIMEOUT))
        pressGamepadButton(KeyEvent.KEYCODE_BUTTON_B)
        assertTrue(device.wait(Until.gone(By.text("Test tone, 440 Hz")), TIMEOUT))
        event(DebugEventsReceiver.ACTION_STOP_AUDIO)
    }

    private fun setOverlay(allowed: Boolean) {
        shell("appops set $pkg SYSTEM_ALERT_WINDOW ${if (allowed) "allow" else "deny"}")
        assertTrue(eventually { OverlayPermission.isGranted(testContext) == allowed })
    }

    private fun event(
        action: String,
        extras: String = "",
    ) {
        shell("am broadcast -p $pkg -a $action $extras")
    }

    private companion object {
        const val TIMEOUT = 10_000L
        const val NEGATIVE_WAIT = 2_000L
    }
}
