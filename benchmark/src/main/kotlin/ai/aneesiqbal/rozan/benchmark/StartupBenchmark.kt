package ai.aneesiqbal.rozan.benchmark

import androidx.benchmark.macro.BaselineProfileMode
import androidx.benchmark.macro.CompilationMode
import androidx.benchmark.macro.StartupMode
import androidx.benchmark.macro.StartupTimingMetric
import androidx.benchmark.macro.junit4.MacrobenchmarkRule
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized

/**
 * Time to the home screen's first frame (`timeToInitialDisplayMs`) and to
 * the app grid being filled (`timeToFullDisplayMs`, from `reportFullyDrawn`).
 *
 * Run with the baseline profile installed (what profileinstaller gives a
 * sideloaded install; `Require` fails the run if the profile is missing) and
 * with no AOT code at all (JIT only, the floor), so the profile's effect is
 * visible.
 */
@RunWith(Parameterized::class)
class StartupBenchmark(
    @Suppress("unused") private val label: String,
    private val compilation: CompilationMode,
) {
    @get:Rule
    val rule = MacrobenchmarkRule()

    @Test
    fun cold() = start(StartupMode.COLD)

    @Test
    fun warm() = start(StartupMode.WARM)

    private fun start(mode: StartupMode) =
        rule.measureRepeated(
            packageName = LAUNCHER,
            metrics = listOf(StartupTimingMetric()),
            compilationMode = compilation,
            startupMode = mode,
            iterations = ITERATIONS,
            setupBlock = { pressHome() },
        ) {
            startHome()
        }

    companion object {
        private const val ITERATIONS = 10

        @JvmStatic
        @Parameterized.Parameters(name = "{0}")
        fun modes(): List<Array<Any>> =
            listOf(
                arrayOf("baselineProfile", CompilationMode.Partial(BaselineProfileMode.Require)),
                arrayOf("jitOnly", CompilationMode.None()),
            )
    }
}
