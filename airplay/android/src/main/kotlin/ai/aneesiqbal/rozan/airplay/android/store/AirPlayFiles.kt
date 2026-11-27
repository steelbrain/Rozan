package ai.aneesiqbal.rozan.airplay.android.store

import android.content.Context
import android.os.Build
import android.os.FileObserver
import android.os.Handler
import android.os.Looper
import java.io.File

/**
 * Files shared between the app's processes (the UI process and the `:airplay`
 * receiver process). They live in `noBackupFilesDir`, so they are never backed
 * up or moved to another device.
 */
internal object AirPlayFiles {
    const val SETTINGS = "settings.properties"
    const val STATUS = "status.properties"
    const val IDENTITY = "identity"
    const val LANGUAGE = "language.properties"

    // An Application's base context has no application context yet while it is being attached.
    fun dir(context: Context): File = File((context.applicationContext ?: context).noBackupFilesDir, "airplay")

    fun file(
        context: Context,
        name: String,
    ): File = File(dir(context), name)

    private val main = Handler(Looper.getMainLooper())
    private val listeners = HashMap<String, ArrayList<Runnable>>()
    private val caches = HashMap<String, Runnable>()
    private var observer: FileObserver? = null

    /**
     * Runs [listener] on the main thread whenever the file [name] is replaced,
     * by any process. Changes arrive as inotify events, not by polling.
     */
    @Synchronized
    fun watch(
        context: Context,
        name: String,
        listener: Runnable,
    ) {
        listeners.getOrPut(name) { ArrayList() } += listener
        ensureObserver(context)
    }

    private fun ensureObserver(context: Context) {
        if (observer == null) {
            val dir = dir(context)
            dir.mkdirs()
            observer = createObserver(dir).also { it.startWatching() }
        }
    }

    /**
     * Runs [reload] on the file watcher's thread whenever the file [name] is replaced, before any
     * [watch] listener of [name] is posted, for the rest of the process's life. For in-memory copies of a
     * file: listeners then read the new content without touching the disk.
     */
    @Synchronized
    fun keepFresh(
        context: Context,
        name: String,
        reload: Runnable,
    ) {
        caches[name] = reload
        ensureObserver(context)
    }

    /** Stops calling [listener] for [name]. */
    @Synchronized
    fun unwatch(
        name: String,
        listener: Runnable,
    ) {
        val forName = listeners[name] ?: return
        forName -= listener
        if (forName.isEmpty()) listeners -= name
        if (listeners.isEmpty() && caches.isEmpty()) {
            observer?.stopWatching()
            observer = null
        }
    }

    private fun dispatch(name: String) {
        // The in-memory copy first, on this thread, so the listeners below already read the new content.
        synchronized(this) { caches[name] }?.run()
        val targets = synchronized(this) { listeners[name]?.toTypedArray() } ?: return
        main.post { for (target in targets) target.run() }
    }

    // One observer per process for the directory: before API 29 two FileObservers on the same path
    // in one process share an inotify watch and only the last one gets events.
    private fun createObserver(dir: File): FileObserver {
        val mask = FileObserver.MOVED_TO or FileObserver.CLOSE_WRITE or FileObserver.DELETE
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            object : FileObserver(dir, mask) {
                override fun onEvent(
                    event: Int,
                    path: String?,
                ) {
                    path?.let(::dispatch)
                }
            }
        } else {
            // The String constructor is deprecated from API 29, which takes the branch above.
            @Suppress("DEPRECATION")
            object : FileObserver(dir.path, mask) {
                override fun onEvent(
                    event: Int,
                    path: String?,
                ) {
                    path?.let(::dispatch)
                }
            }
        }
    }
}
