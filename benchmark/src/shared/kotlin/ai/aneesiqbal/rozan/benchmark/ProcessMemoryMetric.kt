package ai.aneesiqbal.rozan.benchmark

import androidx.benchmark.macro.ExperimentalMetricApi
import androidx.benchmark.macro.MemoryUsageMetric
import androidx.benchmark.macro.TraceMetric
import androidx.benchmark.traceprocessor.TraceProcessor

/**
 * [MemoryUsageMetric]'s counters (RSS anon/file, ART heap size, GPU memory)
 * for exactly one process. `MemoryUsageMetric` matches `<package>` and
 * `<package>:*` together, so for the launcher's main process it would mix in
 * the `:airplay` receiver, which Home starts. Same metric names as
 * `MemoryUsageMetric`, e.g. `memoryRssAnonMaxKb`.
 */
@OptIn(ExperimentalMetricApi::class)
internal class ProcessMemoryMetric(
    private val mode: MemoryUsageMetric.Mode,
    private val processSuffix: String = "",
) : TraceMetric() {
    override fun getMeasurements(
        captureInfo: CaptureInfo,
        traceSession: TraceProcessor.Session,
    ): List<Measurement> {
        val process = captureInfo.targetPackageName + processSuffix
        val select =
            when (mode) {
                MemoryUsageMetric.Mode.Last -> "SELECT track.name AS counter_name, MAX(ts), value"
                MemoryUsageMetric.Mode.Max -> "SELECT track.name AS counter_name, MAX(value) AS value"
            }
        // Perfetto may only know a name's last 15 characters (from sched events).
        val query =
            """
            $select
            FROM counter
                LEFT JOIN process_counter_track AS track ON counter.track_id = track.id
                LEFT JOIN process USING (upid)
            WHERE (process.name = "$process" OR process.name = "${process.takeLast(TRUNCATED_NAME)}")
                AND track.name IN (${COUNTERS.keys.joinToString { "\"$it\"" }})
            GROUP BY counter_name
            """.trimIndent()
        val suffix = processSuffix.replace(':', '_')
        return traceSession.query(query).toList().mapNotNull { row ->
            val counter = row.string("counter_name")
            val (name, bytes) = COUNTERS[counter] ?: return@mapNotNull null
            val value = row.double("value")
            Measurement("memory$suffix$name${mode}Kb", if (bytes) value / KB else value)
        }
    }

    private companion object {
        const val TRUNCATED_NAME = 15
        const val KB = 1024.0

        /** Trace counter to (metric name, whether the counter is in bytes rather than KB). */
        val COUNTERS =
            mapOf(
                "Heap size (KB)" to ("HeapSize" to false),
                "mem.rss.anon" to ("RssAnon" to true),
                "mem.rss.file" to ("RssFile" to true),
                "GPU Memory" to ("Gpu" to true),
            )
    }
}
