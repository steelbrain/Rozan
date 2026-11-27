package ai.aneesiqbal.rozan.launcher

import ai.aneesiqbal.rozan.launcher.backdrop.BackdropView
import android.app.Activity
import android.app.Application
import android.content.ComponentCallbacks2
import android.graphics.Bitmap
import android.os.Bundle
import android.os.Debug
import android.os.SystemClock
import android.util.Log
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
import java.lang.ref.WeakReference

/**
 * Opens and closes the launcher's screens many times and checks that nothing
 * stays behind: every closed Activity and every replaced backdrop photo must
 * be garbage-collectable, the live Java and native heaps must not grow by
 * more than [MAX_HEAP_GROWTH_KB], and PSS by no more than
 * [MAX_PSS_GROWTH_KB], over the cycles. One warm-up cycle runs first, so
 * lazily created state (caches, thread pools, the clock's formatter) is not
 * counted as growth.
 */
@RunWith(AndroidJUnit4::class)
class LeakTest {
    private val app get() = targetContext.applicationContext as Application
    private val created = ArrayList<WeakReference<Activity>>()
    private val tracker =
        object : Application.ActivityLifecycleCallbacks {
            override fun onActivityCreated(
                activity: Activity,
                savedInstanceState: Bundle?,
            ) {
                synchronized(created) { created += WeakReference(activity) }
            }

            override fun onActivityStarted(activity: Activity) = Unit

            override fun onActivityResumed(activity: Activity) = Unit

            override fun onActivityPaused(activity: Activity) = Unit

            override fun onActivityStopped(activity: Activity) = Unit

            override fun onActivitySaveInstanceState(
                activity: Activity,
                outState: Bundle,
            ) = Unit

            override fun onActivityDestroyed(activity: Activity) = Unit
        }

    @Before
    fun setUp() {
        resetLauncherState()
        app.registerActivityLifecycleCallbacks(tracker)
    }

    @After
    fun tearDown() {
        app.unregisterActivityLifecycleCallbacks(tracker)
        resetLauncherState()
    }

    @Test
    fun settingsScreensAreReleased() {
        launchHome().use { scenario ->
            device.pressDPadUp()
            waitUntil("focus on the settings button") { scenario.read { it.currentFocus?.id == R.id.settings_button } }
            openAndCloseSettings(scenario)
            val before = MemorySample.take()

            repeat(CYCLES) { openAndCloseSettings(scenario) }

            assertOnlyLiveActivitiesRemain(expectedAlive = 1)
            MemorySample.take().assertGrowthFrom(before)
        }
    }

    @Test
    fun screensaverCyclesLeaveNothingBehind() {
        launchHome().use { scenario ->
            repeat(SCREENSAVER_COLUMN) { device.pressDPadRight() }
            waitUntil("focus on Screensaver") { scenario.focusedTileLabel() == "Screensaver" }
            enterAndLeaveScreensaver(scenario)
            val before = MemorySample.take()

            repeat(CYCLES) { enterAndLeaveScreensaver(scenario) }

            assertOnlyLiveActivitiesRemain(expectedAlive = 1)
            MemorySample.take().assertGrowthFrom(before)
        }
    }

    /**
     * Memory pressure while home is in the background drops the photo; coming
     * back decodes a new one. The dropped photos must not be held anywhere.
     */
    @Test
    fun backdropPhotosAreReleasedAfterTrimMemory() {
        launchHome().use { scenario ->
            // No local may hold a photo past its cycle: a debuggable app's frames keep locals alive.
            var shown: Bitmap? = waitForPhoto(scenario, replacing = null)
            val dropped = arrayListOf(WeakReference(shown))
            device.pressDPadUp()
            waitUntil("focus on the settings button") { scenario.read { it.currentFocus?.id == R.id.settings_button } }

            fun trimInBackgroundAndReturn() {
                device.pressDPadCenter()
                assertTrue(device.wait(Until.hasObject(By.res(PACKAGE, "settings_list")), TIMEOUT))
                scenario.onActivity { it.onTrimMemory(ComponentCallbacks2.TRIM_MEMORY_BACKGROUND) }
                device.pressBack()
                shown = waitForPhoto(scenario, replacing = shown)
                dropped += WeakReference(shown)
            }
            trimInBackgroundAndReturn()
            val before = MemorySample.take()

            repeat(CYCLES) { trimInBackgroundAndReturn() }

            val current = shown
            shown = null
            forceGc()
            // Photo i was shown before the (i + 1)th trim; the last one is still on screen.
            val retained =
                dropped.indices.filter {
                    dropped[it].get().let { photo ->
                        photo != null && photo !== current
                    }
                }
            assertEquals("dropped backdrop photos still reachable (by cycle)", emptyList<Int>(), retained)
            MemorySample.take().assertGrowthFrom(before)
        }
    }

    private fun openAndCloseSettings(scenario: ActivityScenario<HomeActivity>) {
        device.pressDPadCenter()
        assertTrue("settings opened", device.wait(Until.hasObject(By.res(PACKAGE, "settings_list")), TIMEOUT))
        device.pressBack()
        assertTrue("home is back", device.wait(Until.hasObject(By.res(PACKAGE, "app_grid")), TIMEOUT))
        waitUntil("settings button focused again") { scenario.read { it.currentFocus?.id == R.id.settings_button } }
    }

    private fun enterAndLeaveScreensaver(scenario: ActivityScenario<HomeActivity>) {
        device.pressDPadCenter()
        waitUntil("screensaver started") { scenario.read { it.isScreensaverActive } }
        device.pressBack()
        waitUntil("screensaver stopped") { scenario.read { !it.isScreensaverActive } }
    }

    /** Waits until the lower backdrop layer shows a photo other than [replacing] and returns it. */
    private fun waitForPhoto(
        scenario: ActivityScenario<HomeActivity>,
        replacing: Bitmap?,
    ): Bitmap {
        var photo: Bitmap? = null
        waitUntil("a backdrop photo", timeoutMs = TIMEOUT) {
            photo = scenario.read { it.findViewById<BackdropView>(R.id.backdrop_lower).bitmap }
            photo != null && photo !== replacing
        }
        return checkNotNull(photo)
    }

    /** Every Activity created during the test is collectable except the [expectedAlive] still open. */
    private fun assertOnlyLiveActivitiesRemain(expectedAlive: Int) {
        val deadline = SystemClock.uptimeMillis() + TIMEOUT
        var alive: List<String>
        do {
            forceGc()
            alive = synchronized(created) { created.mapNotNull { it.get()?.javaClass?.simpleName } }
        } while (alive.size > expectedAlive && SystemClock.uptimeMillis() < deadline)
        assertEquals("activities still reachable: $alive", expectedAlive, alive.size)
    }

    /** The process's memory after a full GC; [summary] is meminfo's App Summary (PSS, KB). */
    private class MemorySample(
        val javaKb: Long,
        val nativeKb: Long,
        val summary: Map<String, Long>,
    ) {
        fun assertGrowthFrom(before: MemorySample) {
            val growth =
                mapOf(
                    "Java heap" to javaKb - before.javaKb,
                    "native heap" to nativeKb - before.nativeKb,
                    "PSS" to (summary[TOTAL_PSS] ?: 0) - (before.summary[TOTAL_PSS] ?: 0),
                )
            val breakdown = summary.mapValues { (key, value) -> value - (before.summary[key] ?: 0) }
            Log.i(TAG, "growth over $CYCLES cycles: $growth KB, PSS by kind: $breakdown")
            val within =
                growth.getValue("Java heap") <= MAX_HEAP_GROWTH_KB &&
                    growth.getValue("native heap") <= MAX_HEAP_GROWTH_KB &&
                    growth.getValue("PSS") <= MAX_PSS_GROWTH_KB
            assertTrue("memory grew over $CYCLES cycles: $growth KB (PSS by kind: $breakdown)", within)
        }

        companion object {
            fun take(): MemorySample {
                forceGc()
                val runtime = Runtime.getRuntime()
                val info = Debug.MemoryInfo().also(Debug::getMemoryInfo)
                return MemorySample(
                    javaKb = (runtime.totalMemory() - runtime.freeMemory()) / KB,
                    nativeKb = Debug.getNativeHeapAllocatedSize() / KB,
                    summary =
                        info.memoryStats
                            .filterKeys { it.startsWith("summary.") }
                            .mapValues { it.value.toLongOrNull() ?: 0 },
                )
            }
        }
    }

    private companion object {
        const val CYCLES = 10
        const val SCREENSAVER_COLUMN = 3
        const val TOTAL_PSS = "summary.total-pss"
        const val TAG = "LeakTest"
        const val TIMEOUT = 10_000L
        const val KB = 1024L

        /**
         * Allowed growth of the live Java heap and of malloc'd native memory
         * over all cycles. One leaked settings screen holds its view tree
         * (about 250 views); ten would be well over this.
         */
        const val MAX_HEAP_GROWTH_KB = 1_024L

        /**
         * Allowed PSS growth over all cycles. Looser than the heaps: decoding a
         * photo goes through a full-screen native buffer (8 MB at 1080p) that
         * the allocator may keep after it is freed (measured +1 to +4 MB of
         * native-heap PSS with no growth in allocated bytes, API 31 emulator).
         * So the bound is one and a half such buffers; a photo leaked per cycle
         * would add 80 MB.
         */
        const val MAX_PSS_GROWTH_KB = 12_288L

        /** Like LeakCanary's GcTrigger: collect, give finalizers and reference queues time, collect again. */
        fun forceGc() {
            repeat(GC_ROUNDS) {
                Runtime.getRuntime().gc()
                System.runFinalization()
                SystemClock.sleep(GC_PAUSE_MS)
            }
        }

        const val GC_ROUNDS = 3
        const val GC_PAUSE_MS = 100L
    }
}
