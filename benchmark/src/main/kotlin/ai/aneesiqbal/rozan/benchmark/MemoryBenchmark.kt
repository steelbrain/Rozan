package ai.aneesiqbal.rozan.benchmark

import ai.aneesiqbal.rozan.benchmark.MeminfoMetric.Companion.captureMeminfo
import android.os.SystemClock
import androidx.benchmark.macro.ExperimentalMetricApi
import androidx.benchmark.macro.MacrobenchmarkScope
import androidx.benchmark.macro.MemoryUsageMetric
import androidx.benchmark.macro.MemoryUsageMetric.Mode
import androidx.benchmark.macro.StartupMode
import androidx.benchmark.macro.junit4.MacrobenchmarkRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Until
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Memory of both of the launcher's processes in the scenarios the budgets
 * cover, from cold processes every iteration. Home starts the AirPlay
 * receiver (AirPlay is on by default), so the `:airplay` process runs in
 * every scenario.
 *
 * - `MemoryUsageMetric` (`:airplay`) and [ProcessMemoryMetric] (main
 *   process, same counters matched to exactly that process): RSS anon and
 *   file, ART heap size and GPU memory (where the device reports it) from the
 *   Perfetto trace, the maximum over the scenario and the last value.
 * - [MeminfoMetric]: the PSS breakdown of `dumpsys meminfo` at the end of the
 *   scenario, plus the estimated size of the process's graphic buffers.
 */
@OptIn(ExperimentalMetricApi::class)
@RunWith(AndroidJUnit4::class)
class MemoryBenchmark {
    @get:Rule
    val rule = MacrobenchmarkRule()

    @Test
    fun homeIdle() =
        home {
            SystemClock.sleep(SETTLE_MS)
        }

    @Test
    fun homeAfterBrowsing() =
        home {
            repeat(BROWSE_PASSES) { device.browseGrid() }
            SystemClock.sleep(SETTLE_MS)
        }

    @Test
    fun screensaver() =
        home {
            device.startScreensaver()
            SystemClock.sleep(SETTLE_MS)
        }

    @Test
    fun airplayMirroring() =
        airplay(start = "START_MIRROR", stop = "STOP_MIRROR") {
            check(device.wait(Until.hasObject(By.res(LAUNCHER, "airplay_mirror_surface")), UI_TIMEOUT_MS)) {
                "mirroring screen not shown"
            }
        }

    @Test
    fun airplayAudio() =
        airplay(start = "START_AUDIO", stop = "STOP_AUDIO") {
            check(device.wait(Until.hasObject(By.res(LAUNCHER, "airplay_now_playing_title")), UI_TIMEOUT_MS)) {
                "now-playing screen not shown"
            }
        }

    private fun home(scenario: MacrobenchmarkScope.() -> Unit) =
        rule.measureRepeated(
            packageName = LAUNCHER,
            metrics = metrics(),
            iterations = ITERATIONS,
            startupMode = StartupMode.COLD,
            setupBlock = { pressHome() },
        ) {
            startHome()
            scenario()
            captureBoth()
        }

    /** A fake session from the debug hooks over Home: the bundled 720p H.264 clip, or a PCM tone with artwork. */
    private fun airplay(
        start: String,
        stop: String,
        shown: MacrobenchmarkScope.() -> Unit,
    ) = rule.measureRepeated(
        packageName = LAUNCHER,
        metrics = metrics(),
        iterations = ITERATIONS,
        startupMode = StartupMode.COLD,
        setupBlock = {
            pressHome()
            device.allowOverlay()
        },
    ) {
        startHome()
        device.airplayEvent(start)
        shown()
        SystemClock.sleep(SESSION_MS)
        captureBoth()
        device.airplayEvent(stop)
    }

    private fun MacrobenchmarkScope.captureBoth() {
        captureMeminfo()
        waitFor("the :airplay process (Home starts the receiver)") { device.isRunning(AIRPLAY_PROCESS) }
        captureMeminfo(AIRPLAY_PROCESS)
    }

    private fun metrics() =
        listOf(
            ProcessMemoryMetric(Mode.Max),
            ProcessMemoryMetric(Mode.Last),
            MeminfoMetric(),
            MemoryUsageMetric(Mode.Max, processNameSuffix = AIRPLAY_PROCESS),
            MemoryUsageMetric(Mode.Last, processNameSuffix = AIRPLAY_PROCESS),
            MeminfoMetric(AIRPLAY_PROCESS),
        )

    private companion object {
        const val ITERATIONS = 3
        const val BROWSE_PASSES = 2

        /** Long enough for the first photo's decode and crossfade and the GCs that follow startup. */
        const val SETTLE_MS = 6_000L

        /** Ten seconds of a session: 300 video frames, past any decoder warm-up. */
        const val SESSION_MS = 10_000L
    }
}
