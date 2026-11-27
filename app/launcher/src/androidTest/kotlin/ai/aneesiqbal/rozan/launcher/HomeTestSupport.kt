package ai.aneesiqbal.rozan.launcher

import ai.aneesiqbal.rozan.airplay.android.AppLanguage
import ai.aneesiqbal.rozan.launcher.settings.LauncherSettings
import ai.aneesiqbal.rozan.ui.TileView
import android.app.UiAutomation
import android.content.Context
import android.os.Build
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.view.InputDevice
import android.view.KeyCharacterMap
import android.view.KeyEvent
import androidx.core.content.edit
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.Configurator
import androidx.test.uiautomator.UiDevice
import org.junit.Assert.fail
import java.io.File
import java.util.Properties
import java.util.concurrent.atomic.AtomicReference

internal const val PACKAGE = "ai.aneesiqbal.rozan.launcher"
internal val BUILT_IN_LABELS = listOf("Settings", "Network", "AirPlay", "Screensaver")

internal val instrumentation get() = InstrumentationRegistry.getInstrumentation()

/**
 * UiAutomation suppresses every accessibility service while it is connected,
 * unless told not to; the Home button redirect tests need Rozan's running.
 */
private const val UI_AUTOMATION_FLAGS = UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES

internal val device: UiDevice
    get() {
        Configurator.getInstance().uiAutomationFlags = UI_AUTOMATION_FLAGS
        return UiDevice.getInstance(instrumentation)
    }
internal val targetContext: Context get() = instrumentation.targetContext

/** Default settings, the system language, no test hooks, D-pad (non-touch) mode. */
internal fun resetLauncherState() {
    // apply() updates the shared in-memory instance at once, which is all the tests read.
    targetContext.getSharedPreferences(LauncherSettings.FILE_NAME, Context.MODE_PRIVATE).edit { clear() }
    LauncherSettings.screensaverDelayOverrideMs = null
    setAppLanguage(null)
    instrumentation.setInTouchMode(false)
}

/**
 * Chooses the app language as the settings row does, and waits until the `:airplay` process can see
 * it: from API 33 the system holds it at once; before, it is a file that the other process reloads
 * when it is replaced, so this waits for the file (the path is [AppLanguage]'s, not public).
 */
internal fun setAppLanguage(language: AppLanguage.Language?) {
    instrumentation.runOnMainSync { AppLanguage.choose(targetContext, language) }
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
        val file = File(targetContext.noBackupFilesDir, "airplay/language.properties")
        val stored = language?.tag.orEmpty()
        waitUntil("language saved") {
            val properties = Properties()
            if (file.isFile) file.inputStream().use(properties::load)
            properties.getProperty("language", "") == stored
        }
    }
    instrumentation.waitForIdleSync()
}

/** Opens the home screen and waits until the installed apps are on the grid. */
internal fun launchHome(): ActivityScenario<HomeActivity> {
    val scenario = ActivityScenario.launch(HomeActivity::class.java)
    waitUntil("apps loaded") { scenario.read { it.apps().isNotEmpty() } }
    device.waitForIdle()
    return scenario
}

/** Runs [block] on the main thread with the activity and returns its result. */
internal fun <T> ActivityScenario<HomeActivity>.read(block: (HomeActivity) -> T): T {
    val result = AtomicReference<T>()
    onActivity { result.set(block(it)) }
    return result.get()
}

/** Label of the focused tile, or null when focus is not on a tile. */
internal fun ActivityScenario<HomeActivity>.focusedTileLabel(): String? =
    read { (it.currentFocus as? TileView)?.label?.toString() }

internal fun uiAutomation(): UiAutomation = instrumentation.getUiAutomation(UI_AUTOMATION_FLAGS)

/**
 * Presses a game controller button: a down and an up from a gamepad source, [holdMs] apart.
 * Injected keys come from the virtual keyboard device, so the system's fallback keys
 * (BUTTON_A to DPAD_CENTER, BUTTON_B to BACK, BUTTON_SELECT to MENU) come from `Virtual.kcm`;
 * for those three buttons it agrees with `Generic.kcm`, which real controllers use.
 */
internal fun pressGamepadButton(
    keyCode: Int,
    holdMs: Long = 0,
) {
    val downTime = SystemClock.uptimeMillis()
    injectGamepadKey(downTime, downTime, KeyEvent.ACTION_DOWN, keyCode)
    if (holdMs > 0) SystemClock.sleep(holdMs)
    injectGamepadKey(downTime, SystemClock.uptimeMillis(), KeyEvent.ACTION_UP, keyCode)
}

private fun injectGamepadKey(
    downTime: Long,
    eventTime: Long,
    action: Int,
    keyCode: Int,
) {
    val event =
        KeyEvent(
            downTime,
            eventTime,
            action,
            keyCode,
            0,
            0,
            KeyCharacterMap.VIRTUAL_KEYBOARD,
            0,
            0,
            InputDevice.SOURCE_GAMEPAD,
        )
    if (!uiAutomation().injectInputEvent(event, true)) fail("could not inject $event")
}

/** Runs [command] as the shell user and returns its output once it has finished. */
internal fun shell(command: String): String =
    ParcelFileDescriptor.AutoCloseInputStream(uiAutomation().executeShellCommand(command)).use {
        it.bufferedReader().readText()
    }

/** Polls [condition] (on the test thread) until it holds, failing after [timeoutMs]. */
internal fun waitUntil(
    what: String,
    timeoutMs: Long = 5_000,
    condition: () -> Boolean,
) {
    val deadline = SystemClock.uptimeMillis() + timeoutMs
    while (!condition()) {
        if (SystemClock.uptimeMillis() > deadline) fail("Timed out waiting for: $what")
        SystemClock.sleep(POLL_MS)
    }
}

private const val POLL_MS = 50L
