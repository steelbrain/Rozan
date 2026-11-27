package ai.aneesiqbal.rozan.launcher.update

import android.content.Context
import java.io.File

/**
 * The updater's files, in `noBackupFilesDir/update` (private to Rozan, never
 * backed up). Any process can read them; only the updater's thread in the UI
 * process writes them.
 */
internal object UpdateFiles {
    private const val DIR = "update"

    /** Directory of the update files. */
    fun dir(context: Context): File = File(context.applicationContext.noBackupFilesDir, DIR)

    /** The verified APK that waits to be installed. */
    fun apk(context: Context): File = File(dir(context), "update.apk")

    /** A download in progress; renamed to [apk] once verified. */
    fun download(context: Context): File = File(dir(context), "download.tmp")

    /**
     * Exists while an install waits for no Rozan screen to be showing or for
     * AirPlay to be free. The events that end the wait look for it, from the
     * UI and `:airplay` processes, before they schedule the install job.
     */
    fun waiting(context: Context): File = File(dir(context), "waiting")

    /** Deletes every update file. */
    fun clear(context: Context) {
        dir(context).listFiles()?.forEach { it.delete() }
    }
}
