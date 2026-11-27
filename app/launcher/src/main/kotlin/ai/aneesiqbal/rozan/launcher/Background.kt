package ai.aneesiqbal.rozan.launcher

import android.os.Process
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.asCoroutineDispatcher
import java.util.concurrent.Executor
import java.util.concurrent.Executors
import java.util.concurrent.ThreadFactory
import java.util.concurrent.atomic.AtomicInteger

/**
 * Background threads for the launcher, at background priority so they never
 * compete with the UI and render threads on a quad-core A35.
 */
internal object Background {
    /** The threads of [work], for plain tasks that need no coroutine (cache writes). */
    val workExecutor: Executor = Executors.newFixedThreadPool(WORK_THREADS, factory("rozan-work"))

    /** App list queries and tile art rendering. */
    val work: CoroutineDispatcher = workExecutor.asCoroutineDispatcher()

    /** Backdrop decoding: one at a time, so at most one extra full-screen bitmap is in flight. */
    val decode: CoroutineDispatcher =
        Executors.newSingleThreadExecutor(factory("rozan-decode")).asCoroutineDispatcher()

    private const val WORK_THREADS = 2

    private fun factory(name: String): ThreadFactory {
        val count = AtomicInteger()
        return ThreadFactory { runnable ->
            Thread(
                {
                    Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND)
                    runnable.run()
                },
                "$name-${count.incrementAndGet()}",
            ).apply { isDaemon = true }
        }
    }
}
