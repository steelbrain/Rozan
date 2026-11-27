package ai.aneesiqbal.rozan.benchmark.airplay

import ai.aneesiqbal.rozan.benchmark.MeminfoMetric
import ai.aneesiqbal.rozan.benchmark.MeminfoMetric.Companion.captureMeminfo
import ai.aneesiqbal.rozan.benchmark.ProcessMemoryMetric
import android.content.Intent
import android.os.SystemClock
import androidx.benchmark.macro.BaselineProfileMode
import androidx.benchmark.macro.CompilationMode
import androidx.benchmark.macro.ExperimentalMetricApi
import androidx.benchmark.macro.MacrobenchmarkScope
import androidx.benchmark.macro.MemoryUsageMetric
import androidx.benchmark.macro.MemoryUsageMetric.Mode
import androidx.benchmark.macro.StartupMode
import androidx.benchmark.macro.StartupTimingMetric
import androidx.benchmark.macro.junit4.BaselineProfileRule
import androidx.benchmark.macro.junit4.MacrobenchmarkRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Until
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Package of the standalone AirPlay app. */
private const val APP = "ai.aneesiqbal.rozan.airplay"
private const val RECEIVER_PROCESS = ":airplay"
private const val DEBUG_ACTION = "ai.aneesiqbal.rozan.airplay.debug."
private const val TIMEOUT_MS = 10_000L
private const val SESSION_MS = 10_000L
private const val SETTLE_MS = 6_000L

private fun MacrobenchmarkScope.startStatusScreen() {
    startActivityAndWait(Intent(Intent.ACTION_MAIN).setClassName(APP, "$APP.app.MainActivity"))
    check(device.wait(Until.hasObject(By.res(APP, "receiver_name")), TIMEOUT_MS)) { "status screen not shown" }
}

private fun MacrobenchmarkScope.mirror(seconds: Long) {
    device.executeShellCommand("appops set $APP SYSTEM_ALERT_WINDOW allow")
    device.executeShellCommand("am broadcast -p $APP -a ${DEBUG_ACTION}START_MIRROR")
    check(device.wait(Until.hasObject(By.res(APP, "airplay_mirror_surface")), TIMEOUT_MS)) { "mirroring not shown" }
    SystemClock.sleep(seconds)
}

private fun MacrobenchmarkScope.stopMirror() {
    device.executeShellCommand("am broadcast -p $APP -a ${DEBUG_ACTION}STOP_MIRROR")
    device.wait(Until.gone(By.res(APP, "airplay_mirror_surface")), TIMEOUT_MS)
}

/** Cold and warm start of the status screen, with the baseline profile and with JIT only. */
@RunWith(AndroidJUnit4::class)
class AirPlayAppStartupBenchmark {
    @get:Rule
    val rule = MacrobenchmarkRule()

    @Test
    fun coldWithBaselineProfile() = start(CompilationMode.Partial(BaselineProfileMode.Require))

    @Test
    fun coldJitOnly() = start(CompilationMode.None())

    private fun start(compilation: CompilationMode) =
        rule.measureRepeated(
            packageName = APP,
            metrics = listOf(StartupTimingMetric()),
            compilationMode = compilation,
            startupMode = StartupMode.COLD,
            iterations = 10,
            setupBlock = { pressHome() },
        ) {
            startStatusScreen()
        }
}

/** Both processes with the status screen open, and during a fake mirroring session. */
@OptIn(ExperimentalMetricApi::class)
@RunWith(AndroidJUnit4::class)
class AirPlayAppMemoryBenchmark {
    @get:Rule
    val rule = MacrobenchmarkRule()

    @Test
    fun statusScreen() =
        measure {
            startStatusScreen()
            SystemClock.sleep(SETTLE_MS)
            captureBoth()
        }

    @Test
    fun mirroring() =
        measure {
            startStatusScreen()
            mirror(SESSION_MS)
            captureBoth()
            stopMirror()
        }

    private fun measure(block: MacrobenchmarkScope.() -> Unit) =
        rule.measureRepeated(
            packageName = APP,
            metrics =
                listOf(
                    ProcessMemoryMetric(Mode.Max),
                    MeminfoMetric(),
                    MemoryUsageMetric(Mode.Max, processNameSuffix = RECEIVER_PROCESS),
                    MeminfoMetric(RECEIVER_PROCESS),
                ),
            iterations = 3,
            startupMode = StartupMode.COLD,
            setupBlock = { pressHome() },
            measureBlock = block,
        )

    private fun MacrobenchmarkScope.captureBoth() {
        captureMeminfo()
        captureMeminfo(RECEIVER_PROCESS)
    }
}

/**
 * Generates the standalone app's baseline profile into
 * `app/airplay/src/release/generated/baselineProfiles/`:
 * `./gradlew :app:airplay:generateBaselineProfile` with one API 33+ device
 * or emulator connected.
 */
@RunWith(AndroidJUnit4::class)
class AirPlayAppBaselineProfileGenerator {
    @get:Rule
    val rule = BaselineProfileRule()

    @Test
    fun startup() =
        rule.collect(packageName = APP, includeInStartupProfile = true, filterPredicate = ::notDebugHook) {
            pressHome()
            startStatusScreen()
        }

    @Test
    fun sessions() =
        rule.collect(packageName = APP, filterPredicate = ::notDebugHook) {
            pressHome()
            startStatusScreen()
            device.executeShellCommand("am broadcast -p $APP -a ${DEBUG_ACTION}SHOW_PIN")
            check(device.wait(Until.hasObject(By.res(APP, "airplay_pin_code")), TIMEOUT_MS)) { "PIN not shown" }
            device.executeShellCommand("am broadcast -p $APP -a ${DEBUG_ACTION}PIN_DONE")
            mirror(PROFILE_SESSION_MS)
            stopMirror()
            device.executeShellCommand("am broadcast -p $APP -a ${DEBUG_ACTION}START_AUDIO")
            check(device.wait(Until.hasObject(By.res(APP, "airplay_now_playing_title")), TIMEOUT_MS)) {
                "now playing not shown"
            }
            SystemClock.sleep(PROFILE_SESSION_MS)
            device.executeShellCommand("am broadcast -p $APP -a ${DEBUG_ACTION}STOP_AUDIO")
        }

    private companion object {
        const val PROFILE_SESSION_MS = 3_000L
    }
}

/** The debug hooks are only in measurement builds; rules for them would be dead weight. */
private fun notDebugHook(rule: String): Boolean = "/airplay/android/debug/" !in rule
