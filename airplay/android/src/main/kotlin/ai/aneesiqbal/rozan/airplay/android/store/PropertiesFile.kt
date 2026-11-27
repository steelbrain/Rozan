package ai.aneesiqbal.rozan.airplay.android.store

import java.io.File
import java.io.FileInputStream
import java.io.FileNotFoundException
import java.io.FileOutputStream
import java.io.IOException
import java.io.RandomAccessFile
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.Properties

/**
 * A small key/value file shared by several processes of the app.
 *
 * Reads always go to disk, so a process sees what another one wrote. Writes
 * are read-modify-write under an exclusive file lock (so two processes never
 * lose each other's change) and land atomically: the new content goes to a
 * temporary file that is renamed over the old one. Readers therefore see
 * either the old or the new file, never a partial one, and watchers get one
 * `MOVED_TO` event per change.
 */
internal class PropertiesFile(
    val file: File,
) {
    private val dir: File = requireNotNull(file.parentFile) { "needs a parent directory" }
    private val lockFile = File(dir, file.name + ".lock")
    private val tempFile = File(dir, file.name + ".tmp")

    /** Current content; empty when the file does not exist or cannot be read. */
    fun read(): Properties {
        val properties = Properties()
        try {
            FileInputStream(file).use(properties::load)
        } catch (ignored: FileNotFoundException) {
            // Never written: every key has its default.
        } catch (ignored: IOException) {
            // Unreadable (I/O error): fall back to defaults rather than failing the caller.
        } catch (ignored: IllegalArgumentException) {
            // Malformed escape in a hand-edited file: same as unreadable.
        }
        return properties
    }

    /**
     * Applies [transform] to the current content; writes the result if
     * [transform] returns true. Atomic with respect to other processes.
     */
    fun update(transform: (Properties) -> Boolean) {
        synchronized(PROCESS_LOCK) {
            if (!dir.isDirectory && !dir.mkdirs() && !dir.isDirectory) throw IOException("cannot create $dir")
            RandomAccessFile(lockFile, "rw").use { lock ->
                // Blocks until no other process is in its update.
                lock.channel.lock().use {
                    val properties = read()
                    if (transform(properties)) write(properties)
                }
            }
        }
    }

    private fun write(properties: Properties) {
        FileOutputStream(tempFile).use { out ->
            properties.store(out, null)
            out.fd.sync()
        }
        Files.move(
            tempFile.toPath(),
            file.toPath(),
            StandardCopyOption.ATOMIC_MOVE,
            StandardCopyOption.REPLACE_EXISTING,
        )
    }

    private companion object {
        // FileChannel locks are per process; threads of one process serialise here first.
        val PROCESS_LOCK = Any()
    }
}
