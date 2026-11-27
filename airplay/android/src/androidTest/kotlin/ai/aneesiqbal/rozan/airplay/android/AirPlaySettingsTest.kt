package ai.aneesiqbal.rozan.airplay.android

import android.os.StrictMode
import android.os.SystemClock
import android.os.strictmode.Violation
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import java.util.Locale
import java.util.concurrent.CopyOnWriteArrayList

/**
 * [AirPlaySettings] on the main thread, where the settings screens and the receiver service use it:
 * once loaded, reading and writing settings there touches no disk (StrictMode reports every disk read and
 * write the thread makes), and a value written is read back at once by every instance in the process.
 */
@RunWith(AndroidJUnit4::class)
class AirPlaySettingsTest {
    private val settings = AirPlaySettings(testContext)

    @After
    fun tearDown() {
        settings.wakeOnConnect = true
    }

    @Test
    fun theMainThreadReadsAndWritesSettingsWithoutDiskAccess() {
        settings.deviceName = "Mango Kiwi Plum Fig"
        val violations = CopyOnWriteArrayList<Violation>()
        var previous: StrictMode.ThreadPolicy? = null
        var readMicros = 0.0
        var writeMicros = 0.0
        instrumentation.runOnMainSync {
            // Loaded before the policy: the first read in a process loads the file, once.
            AirPlaySettings(testContext).enabled
            previous = StrictMode.getThreadPolicy()
            StrictMode.setThreadPolicy(
                StrictMode.ThreadPolicy
                    .Builder()
                    .detectDiskReads()
                    .detectDiskWrites()
                    .penaltyListener({ it.run() }) { violations += it }
                    .build(),
            )
            val reading = SystemClock.elapsedRealtimeNanos()
            repeat(READS) {
                val other = AirPlaySettings(testContext)
                other.enabled
                other.deviceName
                other.wakeOnConnect
            }
            readMicros = (SystemClock.elapsedRealtimeNanos() - reading) / 1_000.0 / READS
            val writing = SystemClock.elapsedRealtimeNanos()
            settings.wakeOnConnect = false
            writeMicros = (SystemClock.elapsedRealtimeNanos() - writing) / 1_000.0
            assertEquals(
                "another instance reads the new value at once",
                false,
                AirPlaySettings(testContext).wakeOnConnect,
            )
        }
        // StrictMode reports a main thread's violations from a message after the current one, to the
        // listener of the policy then in force: keep it until the thread is idle.
        instrumentation.waitForIdleSync()
        instrumentation.runOnMainSync { StrictMode.setThreadPolicy(previous) }
        Log.i(
            TAG,
            String.format(
                Locale.ROOT,
                "main thread: %.1f µs per read of all three settings, %.1f µs per write, %d disk accesses",
                readMicros,
                writeMicros,
                violations.size,
            ),
        )
        assertEquals(
            "disk accesses on the main thread: ${violations.map { it.javaClass.simpleName }}",
            0,
            violations.size,
        )
    }

    private companion object {
        const val TAG = "AirPlaySettingsTest"
        const val READS = 100
    }
}
