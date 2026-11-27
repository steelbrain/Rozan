package ai.aneesiqbal.rozan.launcher.update

import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.ComponentName
import android.content.Context
import android.os.Process
import android.util.Log
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executor
import java.util.concurrent.Executors

/**
 * The updater's jobs. All exist only while "Update automatically" is on:
 *
 * - [DAILY]: a check once a day while a network is connected (persisted
 *   across restarts).
 * - [CHECK_NOW]: one check as soon as a network is connected, scheduled
 *   when the switch is turned on.
 * - [INSTALL]: one install attempt, scheduled when what held a downloaded
 *   update back is over (the last Rozan screen stopped, or AirPlay is free).
 */
internal object UpdateJobs {
    /** Job id of the daily check. */
    const val DAILY = 0x52555001

    /** Job id of the check right after the switch is turned on. */
    const val CHECK_NOW = 0x52555002

    /** Job id of an install attempt. */
    const val INSTALL = 0x52555003

    private const val DAY_MS = 24L * 60 * 60 * 1000

    /** Schedules the daily check (from now) and a check as soon as there is a network. */
    fun enable(context: Context) {
        val scheduler = scheduler(context)
        scheduler.schedule(daily(context))
        scheduler.schedule(
            builder(context, CHECK_NOW).setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY).build(),
        )
    }

    /** Schedules the daily check if it is missing (after a force stop, which cancels jobs). */
    fun ensureDaily(context: Context) {
        val scheduler = scheduler(context)
        // schedule() would restart the period of a pending job, so only when there is none.
        if (scheduler.getPendingJob(DAILY) == null) scheduler.schedule(daily(context))
    }

    /** Schedules an install attempt. Installing needs room for the new APK, hence the storage constraint. */
    fun scheduleInstall(context: Context) {
        scheduler(context).schedule(builder(context, INSTALL).setRequiresStorageNotLow(true).build())
    }

    /** Cancels every update job; a running one gets `onStopJob`. */
    fun cancelAll(context: Context) {
        val scheduler = scheduler(context)
        scheduler.cancel(DAILY)
        scheduler.cancel(CHECK_NOW)
        scheduler.cancel(INSTALL)
    }

    private fun daily(context: Context): JobInfo =
        builder(context, DAILY)
            .setPeriodic(DAY_MS)
            .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
            .setPersisted(true)
            .build()

    private fun builder(
        context: Context,
        id: Int,
    ) = JobInfo.Builder(id, ComponentName(context, UpdateJobService::class.java))

    private fun scheduler(context: Context): JobScheduler = context.getSystemService(JobScheduler::class.java)
}

/** Runs the update jobs on [UpdateWorker], one at a time. */
class UpdateJobService : JobService() {
    private val running = ConcurrentHashMap<Int, UpdateHttp>()

    override fun onStartJob(params: JobParameters): Boolean {
        val app = applicationContext
        val http = Updater.newHttp(app)
        running[params.jobId] = http
        UpdateWorker.execute {
            try {
                val updater = Updater(app)
                if (params.jobId == UpdateJobs.INSTALL) updater.install(userInitiated = false) else updater.check(http)
            } finally {
                running.remove(params.jobId)
                // Ignored by the system when onStopJob came first; it then reschedules as asked there.
                jobFinished(params, false)
            }
        }
        return true
    }

    override fun onStopJob(params: JobParameters): Boolean {
        running[params.jobId]?.cancel()
        return true
    }
}

/**
 * The updater's thread: one at a time, at background priority, so a check,
 * an install and the switch being turned off never overlap. A task that
 * throws is logged; it never takes the process down.
 */
internal object UpdateWorker {
    private const val TAG = "RozanUpdate"

    private val executor: Executor =
        Executors.newSingleThreadExecutor { runnable ->
            Thread(
                {
                    Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND)
                    runnable.run()
                },
                "rozan-update",
            ).apply { isDaemon = true }
        }

    /**
     * Runs [task] after the ones before it. Whatever it throws is caught here (hence the generic catch):
     * a failed update must leave Rozan running.
     */
    @Suppress("TooGenericExceptionCaught")
    fun execute(task: () -> Unit) {
        executor.execute {
            try {
                task()
            } catch (e: Exception) {
                Log.e(TAG, "update task failed", e)
            }
        }
    }
}
