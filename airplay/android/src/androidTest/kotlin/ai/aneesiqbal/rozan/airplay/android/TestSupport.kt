package ai.aneesiqbal.rozan.airplay.android

import android.content.Context
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.view.InputDevice
import android.view.KeyCharacterMap
import android.view.KeyEvent
import androidx.test.platform.app.InstrumentationRegistry
import java.io.DataInputStream
import java.util.concurrent.locks.LockSupport

internal val instrumentation get() = InstrumentationRegistry.getInstrumentation()

internal val testContext: Context get() = instrumentation.targetContext

/** Runs [command] through adb's shell user (which holds DUMP, appops, …) and returns its output. */
internal fun shell(command: String): String {
    val descriptor = instrumentation.uiAutomation.executeShellCommand(command)
    return ParcelFileDescriptor.AutoCloseInputStream(descriptor).use { it.readBytes().decodeToString() }
}

/**
 * Presses a game controller button (a down and an up from a gamepad source). The system turns an
 * unhandled BUTTON_B into BACK and BUTTON_A into DPAD_CENTER, as it does for a real controller.
 */
internal fun pressGamepadButton(keyCode: Int) {
    val downTime = SystemClock.uptimeMillis()
    for (action in intArrayOf(KeyEvent.ACTION_DOWN, KeyEvent.ACTION_UP)) {
        val event =
            KeyEvent(
                downTime,
                SystemClock.uptimeMillis(),
                action,
                keyCode,
                0,
                0,
                KeyCharacterMap.VIRTUAL_KEYBOARD,
                0,
                0,
                InputDevice.SOURCE_GAMEPAD,
            )
        check(instrumentation.uiAutomation.injectInputEvent(event, true)) { "could not inject $event" }
    }
}

/** Waits for [condition] by re-checking it, up to [timeoutMillis]; returns its last value. Tests only. */
internal fun eventually(
    timeoutMillis: Long = 10_000,
    condition: () -> Boolean,
): Boolean {
    val deadline = System.nanoTime() + timeoutMillis * 1_000_000
    while (System.nanoTime() < deadline) {
        if (condition()) return true
        LockSupport.parkNanos(50_000_000)
    }
    return condition()
}

/** Parks the calling thread until [nanoTime] on the `System.nanoTime()` clock (a paced test source). */
internal fun sleepUntil(nanoTime: Long) {
    var remaining = nanoTime - System.nanoTime()
    while (remaining > 0) {
        LockSupport.parkNanos(remaining)
        remaining = nanoTime - System.nanoTime()
    }
}

/**
 * An audio fixture written by `scripts/generate-test-fixtures.sh`: the codec
 * configuration and the raw access units, as the receiver hands them to a sink.
 */
internal class AudioFixture(
    val sampleRate: Int,
    val channels: Int,
    val config: ByteArray,
    val units: List<ByteArray>,
) {
    companion object {
        fun load(asset: String): AudioFixture =
            DataInputStream(testContext.assets.open(asset)).use { input ->
                val magic = ByteArray(4).also(input::readFully)
                check(magic.decodeToString() == "RZAU") { "not an RZAU file" }
                check(input.readUnsignedByte() == 1) { "unknown version" }
                val rate = input.readInt()
                val channels = input.readUnsignedByte()
                val config = ByteArray(input.readUnsignedShort()).also(input::readFully)
                val units = List(input.readInt()) { ByteArray(input.readInt()).also(input::readFully) }
                AudioFixture(rate, channels, config, units)
            }
    }
}
