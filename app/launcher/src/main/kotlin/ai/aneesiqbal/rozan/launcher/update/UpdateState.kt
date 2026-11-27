package ai.aneesiqbal.rozan.launcher.update

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit

/**
 * The auto-update switch and what the updater last did, for the updater
 * (UI process) and the settings screen. Writes from the updater are
 * synchronous ([SharedPreferences.Editor.commit]): an install can end the
 * process right after.
 */
internal class UpdateState(
    private val prefs: SharedPreferences,
) {
    constructor(context: Context) : this(
        context.applicationContext.getSharedPreferences(FILE_NAME, Context.MODE_PRIVATE),
    )

    /** How the last check ended. */
    enum class Result {
        /** No newer release; [lastVersion] is the latest release. */
        UP_TO_DATE,

        /** [lastVersion] was downloaded and verified. */
        DOWNLOADED,

        /** Rozan was updated to [lastVersion]. */
        UPDATED,

        /** The check, download, verification or install failed: [failure]. */
        FAILED,
    }

    /** Why the last check failed. */
    enum class Failure {
        /** No connection, or it broke. */
        NETWORK,

        /** An HTTP status other than 200 ([failureStatus]). */
        SERVER,

        /** The release information or SHA256SUMS could not be read, or lacked the APK. */
        RELEASE,

        /** The APK's size or SHA-256 did not match. */
        CHECKSUM,

        /** The APK is not a newer Rozan signed with the installed app's key. */
        PACKAGE,

        /** The download could not be written. */
        STORAGE,

        /** Android did not install the verified APK. */
        INSTALL,
    }

    /** Where a downloaded, verified update stands. */
    enum class Stage {
        /** Waits until no Rozan screen is showing. */
        WAIT_FOR_SCREEN,

        /** Waits until AirPlay is no longer in use. */
        WAIT_FOR_AIRPLAY,

        /** Waits for the user to select "Install update". */
        ASK_USER,

        /** Handed to Android's package installer. */
        INSTALLING,
    }

    /** A verified update in [UpdateFiles.apk]. */
    data class Pending(
        val versionName: String,
        val versionCode: Long,
        /** Lowercase hex SHA-256 of the file, from SHA256SUMS. */
        val sha256: String,
        /** Null right after verification, before the install was tried. */
        val stage: Stage?,
    )

    /** Whether Rozan checks for and installs updates. Off by default. */
    var enabled: Boolean
        get() = prefs.getBoolean(KEY_ENABLED, false)
        set(value) = prefs.edit { putBoolean(KEY_ENABLED, value) }

    /** When the last check ended (wall clock, ms), or 0. */
    val lastCheckAt: Long get() = prefs.getLong(KEY_LAST_AT, 0L)

    /** How the last check ended, or null before the first. */
    val lastResult: Result? get() = enumOrNull<Result>(prefs.getString(KEY_RESULT, null))

    /** The version [lastResult] is about. */
    val lastVersion: String? get() = prefs.getString(KEY_VERSION, null)

    /** Why the last check failed, when [lastResult] is [Result.FAILED]. */
    val failure: Failure? get() = enumOrNull<Failure>(prefs.getString(KEY_FAILURE, null))

    /** The HTTP status of a [Failure.SERVER]. */
    val failureStatus: Int get() = prefs.getInt(KEY_FAILURE_STATUS, 0)

    /** The verified update waiting to be installed, if any. */
    val pending: Pending?
        get() {
            val name = prefs.getString(KEY_PENDING_NAME, null) ?: return null
            val hash = prefs.getString(KEY_PENDING_SHA256, null) ?: return null
            return Pending(
                versionName = name,
                versionCode = prefs.getLong(KEY_PENDING_CODE, 0L),
                sha256 = hash,
                stage = enumOrNull<Stage>(prefs.getString(KEY_PENDING_STAGE, null)),
            )
        }

    /** Records the end of a check at [now]. */
    fun record(
        now: Long,
        result: Result,
        version: String?,
        failure: Failure? = null,
        failureStatus: Int = 0,
    ) {
        prefs.edit(commit = true) {
            putLong(KEY_LAST_AT, now)
            putString(KEY_RESULT, result.name)
            putString(KEY_VERSION, version)
            putString(KEY_FAILURE, failure?.name)
            putInt(KEY_FAILURE_STATUS, failureStatus)
        }
    }

    /** Stores [pending] as the update waiting to be installed. */
    fun setPending(pending: Pending) {
        prefs.edit(commit = true) {
            putString(KEY_PENDING_NAME, pending.versionName)
            putLong(KEY_PENDING_CODE, pending.versionCode)
            putString(KEY_PENDING_SHA256, pending.sha256)
            putString(KEY_PENDING_STAGE, pending.stage?.name)
        }
    }

    /** Moves the pending update to [stage]. */
    fun setStage(stage: Stage) {
        prefs.edit(commit = true) { putString(KEY_PENDING_STAGE, stage.name) }
    }

    /** Forgets the pending update (its file is deleted separately). */
    fun clearPending() {
        prefs.edit(commit = true) {
            remove(KEY_PENDING_NAME)
            remove(KEY_PENDING_CODE)
            remove(KEY_PENDING_SHA256)
            remove(KEY_PENDING_STAGE)
        }
    }

    /** Forgets everything but [enabled]. */
    fun clearHistory() {
        val keep = enabled
        prefs.edit(commit = true) {
            clear()
            putBoolean(KEY_ENABLED, keep)
        }
    }

    /** Calls [listener] on the main thread after every change. Held weakly by the preferences. */
    fun addListener(listener: SharedPreferences.OnSharedPreferenceChangeListener) {
        prefs.registerOnSharedPreferenceChangeListener(listener)
    }

    /** Stops calling [listener]. */
    fun removeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener) {
        prefs.unregisterOnSharedPreferenceChangeListener(listener)
    }

    companion object {
        /** The preferences file. */
        const val FILE_NAME = "updates"
        private const val KEY_ENABLED = "enabled"
        private const val KEY_LAST_AT = "last_at"
        private const val KEY_RESULT = "result"
        private const val KEY_VERSION = "version"
        private const val KEY_FAILURE = "failure"
        private const val KEY_FAILURE_STATUS = "failure_status"
        private const val KEY_PENDING_NAME = "pending_name"
        private const val KEY_PENDING_CODE = "pending_code"
        private const val KEY_PENDING_SHA256 = "pending_sha256"
        private const val KEY_PENDING_STAGE = "pending_stage"

        private inline fun <reified T : Enum<T>> enumOrNull(name: String?): T? =
            enumValues<T>().firstOrNull { it.name == name }
    }
}
