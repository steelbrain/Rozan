package ai.aneesiqbal.rozan.airplay.android.store

import java.io.IOException
import java.util.Properties
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executor
import java.util.concurrent.FutureTask

/**
 * A [PropertiesFile] held in memory for this process.
 *
 * Reads come from memory; the file is read once, on first use, and again only in [reload], which the
 * owner calls when the file watcher reports that a process replaced the file. Writes change memory at
 * once and reach the file in order on [writer], through [PropertiesFile.update] (locked against the other
 * processes, atomic). A write made off the main thread waits until it is on disk, so callers on background
 * threads keep the synchronous behaviour; on the main thread it returns at once.
 *
 * [reload] keeps this process's writes that have not reached the disk yet, so a value written here never
 * reads back as the older one in between. A write that fails (I/O error) is reported to [onWriteFailed];
 * the value stays in memory for this process.
 */
internal class CachedProperties(
    private val file: PropertiesFile,
    private val writer: Executor,
    private val onMainThread: () -> Boolean,
    private val onWriteFailed: (IOException) -> Unit,
) {
    /** A write that has not reached the disk yet, in [unsaved] (identity matters, not the value). */
    private inner class Change(
        val key: String,
        val value: String,
    ) : Runnable {
        /** Commits it, on [writer]. */
        override fun run() = commit(this)
    }

    private val lock = Any()

    /** Orders file commits and reloads, so a reload never misses a write that just left [unsaved]. */
    private val commitLock = Any()

    // Guarded by lock.
    private var cached: Properties? = null
    private val unsaved = ArrayList<Change>()

    /** Threads currently running a commit on [writer]; their own writes must not wait for themselves. */
    private val onWriter = ThreadLocal<Boolean>()

    /** The value of [key], or null when it is not set. */
    fun get(key: String): String? = synchronized(lock) { loadedLocked().getProperty(key) }

    /** Sets [key] to [value]; does nothing (and writes nothing) when it already has that value. */
    fun set(
        key: String,
        value: String,
    ) {
        val change = Change(key, value)
        synchronized(lock) {
            val properties = loadedLocked()
            if (properties.getProperty(key) == value) return
            properties.setProperty(key, value)
            unsaved += change
        }
        val task = FutureTask<Unit>(change, null)
        writer.execute(task)
        if (!onMainThread() && onWriter.get() != true) {
            try {
                task.get()
            } catch (e: ExecutionException) {
                throw IOException("could not save $key", e)
            }
        }
    }

    /**
     * Returns the stored value of [key]; when there is none, stores [initial] first. The check and the
     * write happen under the file lock, so processes asking at once agree on one value. Touches the disk
     * only when [key] is not set yet.
     */
    fun getOrCreate(
        key: String,
        initial: String,
    ): String {
        get(key)?.let { return it }
        var result = ""
        synchronized(commitLock) {
            file.update { properties ->
                val stored = properties.getProperty(key)
                result = stored ?: initial
                if (stored == null) properties.setProperty(key, result)
                stored == null
            }
            synchronized(lock) { loadedLocked().setProperty(key, result) }
        }
        return result
    }

    /** Re-reads the file (another process replaced it), keeping writes of this process not saved yet. */
    fun reload() {
        synchronized(commitLock) {
            val fresh = file.read()
            synchronized(lock) {
                for (change in unsaved) fresh.setProperty(change.key, change.value)
                cached = fresh
            }
        }
    }

    /** Runs [task] once every write made so far is on disk: now, or on [writer] after them. */
    fun afterSaved(task: Runnable) {
        val pending = synchronized(lock) { unsaved.isNotEmpty() }
        if (pending) writer.execute(task) else task.run()
    }

    private fun commit(change: Change) {
        onWriter.set(true)
        try {
            synchronized(commitLock) {
                try {
                    file.update { properties ->
                        val changed = properties.getProperty(change.key) != change.value
                        if (changed) properties.setProperty(change.key, change.value)
                        changed
                    }
                } catch (e: IOException) {
                    onWriteFailed(e)
                    throw e
                } finally {
                    synchronized(lock) { unsaved.remove(change) }
                }
            }
        } finally {
            onWriter.remove()
        }
    }

    private fun loadedLocked(): Properties = cached ?: file.read().also { cached = it }
}
