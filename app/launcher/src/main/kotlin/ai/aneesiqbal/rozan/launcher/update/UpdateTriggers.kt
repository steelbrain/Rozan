package ai.aneesiqbal.rozan.launcher.update

import ai.aneesiqbal.rozan.airplay.android.ReceiverStatus
import android.app.Activity
import android.app.Application
import android.content.Context
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import java.util.concurrent.ExecutionException
import java.util.concurrent.FutureTask

/**
 * The events that end an install's wait ([UpdateFiles.waiting]), each of
 * which schedules the install job when an install waits:
 *
 * - UI process: the last started Rozan activity stops (another app came to
 *   the front, or the screen went off), tracked with activity lifecycle
 *   callbacks. The same count answers [rozanOnScreen].
 * - `:airplay` process: the receiver is no longer in use
 *   ([ReceiverStatus.Snapshot.inUse] turns false); the session screens
 *   (code, mirroring, now playing) are shown only while it is.
 *
 * The `:redirect` process shows only a transparent probe that finishes at
 * once, and is not tracked.
 */
internal object UpdateTriggers {
    private const val TAG = "RozanUpdate"
    private const val AIRPLAY_PROCESS = ":airplay"

    private val main = Handler(Looper.getMainLooper())

    // Main thread only.
    private var started = 0

    /** Installs the triggers of [processName]'s process; call once from `Application.onCreate`. */
    fun install(
        app: Application,
        processName: String,
    ) {
        when (processName) {
            app.packageName -> {
                app.registerActivityLifecycleCallbacks(ScreenTracker)
                // A force stop cancels every job; the next start puts the daily check back.
                UpdateWorker.execute { if (UpdateState(app).enabled) UpdateJobs.ensureDaily(app) }
            }

            // The status file's watcher holds the listener for the rest of the process's life.
            app.packageName + AIRPLAY_PROCESS -> {
                ReceiverStatus(app).addListener(AirPlayWatch(app))
            }
        }
    }

    /**
     * Whether a Rozan activity of the UI process is started (visible). Read on the main thread, so it
     * never lands between the stop and the start of an activity being recreated. Call it off the main
     * thread (it blocks until the main thread has answered) or on it.
     */
    fun rozanOnScreen(): Boolean {
        if (Looper.myLooper() == Looper.getMainLooper()) return started > 0
        val task = FutureTask { started > 0 }
        main.post(task)
        return try {
            task.get()
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            Log.w(TAG, "interrupted", e)
            true
        } catch (e: ExecutionException) {
            Log.w(TAG, "cannot read the activity count", e)
            true
        }
    }

    /** Something that held an install back is over: schedule the install job if one waits. */
    private fun unblocked(context: Context) {
        val app = context.applicationContext
        UpdateWorker.execute { if (UpdateFiles.waiting(app).exists()) UpdateJobs.scheduleInstall(app) }
    }

    private object ScreenTracker : Application.ActivityLifecycleCallbacks {
        override fun onActivityStarted(activity: Activity) {
            started++
        }

        override fun onActivityStopped(activity: Activity) {
            started--
            // A recreated activity starts again right after; it did not leave the screen.
            if (started == 0 && !activity.isChangingConfigurations) unblocked(activity)
        }

        override fun onActivityCreated(
            activity: Activity,
            savedInstanceState: Bundle?,
        ) = Unit

        override fun onActivityResumed(activity: Activity) = Unit

        override fun onActivityPaused(activity: Activity) = Unit

        override fun onActivitySaveInstanceState(
            activity: Activity,
            outState: Bundle,
        ) = Unit

        override fun onActivityDestroyed(activity: Activity) = Unit
    }

    private class AirPlayWatch(
        private val context: Context,
    ) : ReceiverStatus.Listener {
        // Main thread. The process starts with the receiver idle.
        private var inUse = false

        override fun onReceiverStatusChanged(status: ReceiverStatus.Snapshot) {
            val wasInUse = inUse
            inUse = status.inUse
            if (wasInUse && !inUse) unblocked(context)
        }
    }
}
