package ai.aneesiqbal.rozan.airplay.android

import ai.aneesiqbal.rozan.airplay.android.debug.DebugEventsReceiver
import android.os.Build
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Runs the session screens (PIN overlay, mirroring, now playing) many times
 * through the debug hooks and checks that the `:airplay` process lets go of
 * them: no Activity, window or view left once every session has ended, and
 * memory growth over the cycles within [MAX_GROWTH_KB] per kind.
 *
 * The screens live in the `:airplay` process, not in this test's process, so
 * the counts come from `dumpsys meminfo`: ART counts Activity instances after
 * a GC, and the window manager's ViewRootImpl count covers the overlay window.
 */
@RunWith(AndroidJUnit4::class)
class SessionLeakTest {
    private val device = UiDevice.getInstance(instrumentation)
    private val pkg = testContext.packageName
    private val surface = By.res(pkg, "airplay_mirror_surface")

    @Before
    fun setUp() {
        AirPlaySettings(testContext).deviceName = "Mango Kiwi Plum Fig"
        AirPlaySettings(testContext).enabled = true
        shell("appops set $pkg SYSTEM_ALERT_WINDOW allow")
        assertTrue(eventually { OverlayPermission.isGranted(testContext) })
        device.pressHome()
    }

    @After
    fun tearDown() {
        event(DebugEventsReceiver.ACTION_PIN_DONE)
        event(DebugEventsReceiver.ACTION_STOP_MIRROR)
        event(DebugEventsReceiver.ACTION_STOP_AUDIO)
        AirPlaySettings(testContext).enabled = false
    }

    @Test
    fun sessionScreensAreReleased() {
        // The first sessions fill caches that stay (codec and graphics plumbing, JIT code): on the
        // API 31 image native heap grows ~800 KB over the first ten sessions, then under 20 KB per
        // session. Measuring starts after them, so the bound catches what keeps growing.
        repeat(WARM_UP_CYCLES) { cycle() }
        val before = settled()

        repeat(CYCLES) { cycle() }

        val after = settled()
        assertEquals("Activities left in :airplay", 0, after.activities)
        assertEquals("windows left in :airplay", 0, after.viewRoots)
        val growth =
            mapOf(
                "Java heap" to after.javaKb - before.javaKb,
                "native heap" to after.nativeKb - before.nativeKb,
                "PSS" to after.pssKb - before.pssKb,
            )
        Log.i("SessionLeakTest", "growth of :airplay over $CYCLES sessions: $growth KB")
        // Native heap and PSS are judged on ARM only. CI's x86_64 API 36 image grows the native
        // heap of :airplay by about 450 KB per session even after the warm-up, while the ARM images
        // stay flat over 40 sessions (API 36 with the same SwiftShader GPU: +244 KB in total), so
        // that growth is the x86 emulator's media and graphics stack, not ours. Every TV this ships
        // to is ARM. The Java heap, which holds everything this code allocates, is judged everywhere.
        val judged = if (isArm) growth else growth.filterKeys { it == "Java heap" }
        assertTrue(
            "memory grew over $CYCLES sessions by more than $MAX_GROWTH_KB KB: $growth",
            judged.values.all { it <= MAX_GROWTH_KB },
        )
    }

    /** One PIN prompt, one mirroring session and one audio session, each shown and ended. */
    private fun cycle() {
        event(DebugEventsReceiver.ACTION_SHOW_PIN, "--es pin 4817")
        assertTrue("PIN shown", device.wait(Until.hasObject(By.text("4817")), TIMEOUT))
        event(DebugEventsReceiver.ACTION_PIN_DONE, "--es result ACCEPTED")
        assertTrue("PIN gone", device.wait(Until.gone(By.text("4817")), TIMEOUT))

        event(DebugEventsReceiver.ACTION_START_MIRROR)
        assertTrue("mirroring shown", device.wait(Until.hasObject(surface), TIMEOUT))
        event(DebugEventsReceiver.ACTION_STOP_MIRROR)
        assertTrue("mirroring gone", device.wait(Until.gone(surface), TIMEOUT))

        event(DebugEventsReceiver.ACTION_START_AUDIO)
        assertTrue("now playing shown", device.wait(Until.hasObject(By.text("Test tone, 440 Hz")), TIMEOUT))
        device.pressBack()
        assertTrue("now playing gone", device.wait(Until.gone(By.text("Test tone, 440 Hz")), TIMEOUT))
        event(DebugEventsReceiver.ACTION_STOP_AUDIO)
    }

    /** The `:airplay` process once its screens are gone (destroy is asynchronous) and after a GC. */
    private fun settled(): AirPlayMemory {
        var memory = AirPlayMemory.read(pkg)
        eventually(TIMEOUT) {
            memory = AirPlayMemory.read(pkg)
            memory.activities == 0 && memory.viewRoots == 0
        }
        // The Objects counts above made ART collect; read again so PSS reflects the collected heap.
        return AirPlayMemory.read(pkg)
    }

    private fun event(
        action: String,
        extras: String = "",
    ) {
        shell("am broadcast -p $pkg -a $action $extras")
    }

    /** `dumpsys meminfo` of the `:airplay` process: App Summary PSS and the Objects counts. */
    private class AirPlayMemory(
        val pssKb: Long,
        val javaKb: Long,
        val nativeKb: Long,
        val activities: Int,
        val viewRoots: Int,
    ) {
        companion object {
            fun read(pkg: String): AirPlayMemory {
                val pid = shell("pidof $pkg:airplay").trim().substringBefore(' ')
                check(pid.isNotEmpty()) { "$pkg:airplay is not running" }
                val text = shell("dumpsys meminfo $pid")
                return AirPlayMemory(
                    pssKb = value(text, "TOTAL PSS:") ?: value(text, "TOTAL:") ?: error("no TOTAL in meminfo"),
                    javaKb = value(text, "Java Heap:") ?: error("no Java Heap in meminfo"),
                    nativeKb = value(text, "Native Heap:") ?: error("no Native Heap in meminfo"),
                    activities = value(text, "Activities:")?.toInt() ?: error("no Activities in meminfo"),
                    viewRoots = value(text, "ViewRootImpl:")?.toInt() ?: error("no ViewRootImpl in meminfo"),
                )
            }

            /**
             * The number after [label] on the first line with it. With the colon these labels
             * only occur in the App Summary (PSS column) and Objects sections.
             */
            private fun value(
                text: String,
                label: String,
            ): Long? =
                text
                    .lineSequence()
                    .firstOrNull { label in it }
                    ?.substringAfter(label)
                    ?.trim()
                    ?.substringBefore(' ')
                    ?.toLongOrNull()
        }
    }

    private companion object {
        val isArm: Boolean = Build.SUPPORTED_ABIS.firstOrNull()?.startsWith("arm") == true
        const val WARM_UP_CYCLES = 5
        const val CYCLES = 10
        const val TIMEOUT = 10_000L

        /** Allowed growth per kind over all cycles; one leaked mirroring screen alone holds more. */
        const val MAX_GROWTH_KB = 3_072L
    }
}
