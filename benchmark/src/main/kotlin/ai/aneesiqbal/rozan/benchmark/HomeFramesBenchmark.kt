package ai.aneesiqbal.rozan.benchmark

import android.os.SystemClock
import androidx.benchmark.macro.ExperimentalMetricApi
import androidx.benchmark.macro.FrameTimingMetric
import androidx.benchmark.macro.StartupMode
import androidx.benchmark.macro.TraceSectionMetric
import androidx.benchmark.macro.junit4.MacrobenchmarkRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Frame timing of the home screen's motion: D-pad focus moving across the
 * grid (tile scale, label fade, row scroll), the backdrop crossfade, and the
 * screensaver's chrome fade.
 *
 * `frameDurationCpuMs` is UI + render thread time per frame and is the
 * number to compare between builds. `frameOverrunMs` (API 31+) depends on the
 * GPU, which on an emulator is the host's through a translation layer, so it
 * says little about a Fire TV.
 *
 * `offscreenLayerCount` counts HWUI's "alpha caused saveLayer" sections: a
 * view faded with `alpha` that has overlapping rendering is drawn into an
 * offscreen buffer the size of the view first. That costs GPU fill and memory
 * on the device but no time an emulator can show, and the count is the same
 * on every machine. (Counting sections is still experimental API in
 * Macrobenchmark 1.5, hence the opt-in.)
 */
@OptIn(ExperimentalMetricApi::class)
@RunWith(AndroidJUnit4::class)
class HomeFramesBenchmark {
    @get:Rule
    val rule = MacrobenchmarkRule()

    private val metrics =
        listOf(
            FrameTimingMetric(),
            TraceSectionMetric("alpha caused saveLayer%", TraceSectionMetric.Mode.Count, label = "offscreenLayer"),
        )

    @Test
    fun gridNavigation() =
        rule.measureRepeated(
            packageName = LAUNCHER,
            metrics = metrics,
            iterations = ITERATIONS,
            setupBlock = {
                pressHome()
                startHome()
            },
        ) {
            device.browseGrid()
            device.browseGrid()
        }

    /**
     * The first photo fading in over the plain background right after a cold
     * start: the same per-frame work as a photo-to-photo crossfade (one
     * full-screen layer with changing alpha) except that the layer below is
     * the window background instead of the previous photo.
     */
    @Test
    fun backdropCrossfadeAfterStart() =
        rule.measureRepeated(
            packageName = LAUNCHER,
            metrics = metrics,
            iterations = ITERATIONS,
            startupMode = StartupMode.COLD,
            setupBlock = { pressHome() },
        ) {
            startHome()
            SystemClock.sleep(CROSSFADE_WINDOW_MS)
        }

    /**
     * Entering the screensaver, which fades the chrome out and (when the photo
     * is older than the screensaver interval, 45 s) crossfades to the next
     * photo between two full-screen photos at once; then leaving it with Back.
     * The launcher has no hook to shorten the 45 s, so each iteration first
     * leaves the home screen idle that long (see docs/PERFORMANCE.md).
     */
    @Test
    fun screensaverEnterAndExit() =
        rule.measureRepeated(
            packageName = LAUNCHER,
            metrics = metrics,
            iterations = SCREENSAVER_ITERATIONS,
            setupBlock = {
                killProcess()
                startHome()
                SystemClock.sleep(SCREENSAVER_BACKDROP_INTERVAL_MS + CROSSFADE_WINDOW_MS)
            },
        ) {
            device.startScreensaver()
            SystemClock.sleep(SCREENSAVER_SETTLE_MS)
            device.pressBack()
            SystemClock.sleep(CHROME_FADE_MS)
        }

    private companion object {
        const val ITERATIONS = 5
        const val SCREENSAVER_ITERATIONS = 3
        const val SCREENSAVER_BACKDROP_INTERVAL_MS = 45_000L
        const val CROSSFADE_WINDOW_MS = 3_000L
        const val CHROME_FADE_MS = 1_500L
    }
}
