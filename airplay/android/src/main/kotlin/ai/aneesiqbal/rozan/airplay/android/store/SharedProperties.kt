package ai.aneesiqbal.rozan.airplay.android.store

import android.content.Context
import android.os.Looper
import android.util.Log
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

/**
 * Opens this process's in-memory copy of the shared file [name] ([AirPlayFiles]): writes reach the
 * disk on one writer thread named [threadName], and the copy is re-read whenever another process
 * replaces the file. Call it once per process and file, and keep the result.
 */
internal fun openSharedProperties(
    context: Context,
    name: String,
    threadName: String,
    logTag: String,
): CachedProperties {
    // At most one writer thread, and none while nothing is being written.
    val writer =
        ThreadPoolExecutor(0, 1, WRITER_KEEP_ALIVE_SECONDS, TimeUnit.SECONDS, LinkedBlockingQueue()) {
            Thread(it, threadName).apply { isDaemon = true }
        }
    val store =
        CachedProperties(
            PropertiesFile(AirPlayFiles.file(context, name)),
            writer,
            onMainThread = { Looper.myLooper() === Looper.getMainLooper() },
            onWriteFailed = { Log.w(logTag, "could not save $name", it) },
        )
    AirPlayFiles.keepFresh(context, name, store::reload)
    return store
}

/** Idle time after which a writer thread ends; the next write starts a new one. */
private const val WRITER_KEEP_ALIVE_SECONDS = 5L
