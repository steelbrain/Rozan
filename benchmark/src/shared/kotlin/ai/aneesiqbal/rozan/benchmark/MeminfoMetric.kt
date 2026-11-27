package ai.aneesiqbal.rozan.benchmark

import androidx.benchmark.macro.ExperimentalMetricApi
import androidx.benchmark.macro.MacrobenchmarkScope
import androidx.benchmark.macro.TraceMetric
import androidx.benchmark.traceprocessor.TraceProcessor
import java.util.concurrent.ConcurrentHashMap

/**
 * PSS breakdown of one process from `dumpsys meminfo` (the numbers the
 * budgets in `config/perf-budgets.json` use), reported as benchmark metrics
 * next to [androidx.benchmark.macro.MemoryUsageMetric]'s RSS and heap.
 *
 * Macrobenchmark metrics only see the trace, so the measure block takes the
 * snapshot with [captureMeminfo] and this metric hands it over when the
 * iteration's results are collected (right after that iteration).
 *
 * Unlike `MemoryUsageMetric`, which matches `<package>` and `<package>:*`
 * together, this reads exactly one process.
 */
@OptIn(ExperimentalMetricApi::class)
internal class MeminfoMetric(
    private val processSuffix: String = "",
) : TraceMetric() {
    override fun getMeasurements(
        captureInfo: CaptureInfo,
        traceSession: TraceProcessor.Session,
    ): List<Measurement> {
        val values = snapshots.remove(processSuffix) ?: error("captureMeminfo(\"$processSuffix\") was not called")
        val prefix = "meminfo" + processSuffix.replace(':', '_')
        return values.map { (name, value) -> Measurement("$prefix$name", value) }
    }

    companion object {
        private val snapshots = ConcurrentHashMap<String, Map<String, Double>>()

        /** Snapshots the process with [processSuffix] for this iteration's [MeminfoMetric]. */
        fun MacrobenchmarkScope.captureMeminfo(processSuffix: String = "") {
            val pid = device.executeShellCommand("pidof $packageName$processSuffix").trim().substringBefore(' ')
            check(pid.isNotEmpty()) { "$packageName$processSuffix is not running" }
            // Each dump makes the app run a GC; the first one clears garbage that
            // would still count (as in scripts/perf/meminfo.sh), the second is measured.
            device.executeShellCommand("dumpsys meminfo $pid")
            val meminfo = device.executeShellCommand("dumpsys meminfo $pid")
            val gfxinfo = device.executeShellCommand("dumpsys gfxinfo $pid")
            snapshots[processSuffix] = Meminfo.parse(meminfo) + Meminfo.grallocEstimate(gfxinfo)
        }
    }
}

/** Parsers for `dumpsys meminfo` and `dumpsys gfxinfo`, matching `scripts/perf/meminfo.sh`. */
internal object Meminfo {
    private val summaryRows =
        listOf(
            "Java Heap" to "JavaHeapKb",
            "Native Heap" to "NativeHeapKb",
            "Code" to "CodeKb",
            "Stack" to "StackKb",
            "Graphics" to "GraphicsKb",
            "Private Other" to "PrivateOtherKb",
            "System" to "SystemKb",
        )
    private val whitespace = Regex("\\s+")

    /** The "App Summary" PSS column and the Activities count, by metric name. */
    fun parse(meminfo: String): Map<String, Double> {
        val values = LinkedHashMap<String, Double>()
        var section = ""
        for (raw in meminfo.lineSequence()) {
            val line = raw.trim()
            when {
                line.startsWith("App Summary") -> {
                    section = "summary"
                }

                line.startsWith("Objects") -> {
                    section = "objects"
                }

                section == "summary" && line.startsWith("TOTAL") -> {
                    totalPss(line)?.let { values["TotalPssKb"] = it }
                }

                section == "summary" -> {
                    summaryRow(line)?.let { values[it.first] = it.second }
                }

                section == "objects" && "Activities:" in line -> {
                    line.substringAfter("Activities:").trim().split(whitespace).first().toDoubleOrNull()?.let {
                        values["Activities"] = it
                    }
                }
            }
        }
        return values
    }

    /**
     * Size of every graphic buffer in gfxinfo's GraphicBufferAllocator table
     * (hardware bitmaps, window surfaces) from width x height x bytes per
     * pixel. Emulators keep these on the host and report Graphics as 0.
     */
    fun grallocEstimate(gfxinfo: String): Map<String, Double> {
        // handle | size | W (stride) x H | layers | format | usage | requestor; the header has no "0x".
        val rows =
            gfxinfo
                .lineSequence()
                .dropWhile { !it.startsWith("GraphicBufferAllocator buffers:") }
                .drop(1)
                .map { it.split('|') }
                .takeWhile { it.size >= COLUMNS }
                .filter { it[0].contains("0x") }
        var bytes = 0.0
        for (columns in rows) {
            val size =
                columns[2]
                    .replace(
                        Regex("\\([^)]*\\)"),
                        " ",
                    ).split('x')
                    .map { it.trim().toDoubleOrNull() ?: 0.0 }
            val layers = columns[3].trim().toDoubleOrNull()?.coerceAtLeast(1.0) ?: 1.0
            bytes += size[0] * size.getOrElse(1) { 0.0 } * layers * bytesPerPixel(columns[4].trim().toIntOrNull() ?: 1)
        }
        return mapOf("GrallocEstimateKb" to bytes / KB)
    }

    private fun summaryRow(line: String): Pair<String, Double>? {
        for ((label, name) in summaryRows) {
            if (line.startsWith("$label:")) {
                val value =
                    line
                        .substringAfter(':')
                        .trim()
                        .split(whitespace)
                        .first()
                        .toDoubleOrNull() ?: return null
                return name to value
            }
        }
        return null
    }

    // "TOTAL PSS:  33965  TOTAL RSS: ..." (API 30+) or "TOTAL:  33965 ..." (API 28-29).
    private fun totalPss(line: String): Double? {
        val words = line.split(whitespace)
        val at =
            if (words[0] == "TOTAL:") {
                1
            } else if (words.getOrNull(1) == "PSS:") {
                2
            } else {
                return null
            }
        return words.getOrNull(at)?.toDoubleOrNull()
    }

    // android_pixel_format_t values, as in meminfo.sh.
    private fun bytesPerPixel(format: Int): Double =
        when (format) {
            RGB_888 -> 3.0
            RGB_565, RAW16 -> 2.0
            RGBA_FP16 -> 8.0
            YCRCB_420_SP, YCBCR_420_888, YV12 -> 1.5
            BLOB -> 1.0
            else -> 4.0
        }

    private const val COLUMNS = 7
    private const val KB = 1024.0
    private const val RGB_888 = 3
    private const val RGB_565 = 4
    private const val RAW16 = 32
    private const val RGBA_FP16 = 22
    private const val YCRCB_420_SP = 17
    private const val YCBCR_420_888 = 35
    private const val YV12 = 842094169
    private const val BLOB = 33
}
