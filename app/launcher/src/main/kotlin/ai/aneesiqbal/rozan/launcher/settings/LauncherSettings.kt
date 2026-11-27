package ai.aneesiqbal.rozan.launcher.settings

import android.content.Context
import android.content.SharedPreferences
import androidx.annotation.VisibleForTesting
import androidx.core.content.edit

/**
 * Typed launcher settings over [SharedPreferences]. Stored values outside the
 * allowed choices (from an older version, or edited by hand) read as the
 * default.
 */
class LauncherSettings(
    private val prefs: SharedPreferences,
) {
    constructor(context: Context) : this(
        context.applicationContext.getSharedPreferences(FILE_NAME, Context.MODE_PRIVATE),
    )

    /** Keep the screen on while the home screen is showing. */
    var keepScreenOn: Boolean
        get() = prefs.getBoolean(KEY_KEEP_SCREEN_ON, true)
        set(value) = prefs.edit { putBoolean(KEY_KEEP_SCREEN_ON, value) }

    /** Minutes without input on the home screen before the screensaver starts (needs [keepScreenOn]). */
    var screensaverMinutes: Int
        get() =
            prefs.getInt(KEY_SCREENSAVER_MINUTES, DEFAULT_SCREENSAVER_MINUTES).takeIf { it in SCREENSAVER_CHOICES }
                ?: DEFAULT_SCREENSAVER_MINUTES
        set(value) {
            require(value in SCREENSAVER_CHOICES) { "Unsupported screensaver delay: $value" }
            prefs.edit { putInt(KEY_SCREENSAVER_MINUTES, value) }
        }

    /** Minutes between backdrop changes on the home screen. */
    var backdropIntervalMinutes: Int
        get() =
            prefs.getInt(KEY_BACKDROP_INTERVAL_MINUTES, DEFAULT_BACKDROP_INTERVAL_MINUTES).takeIf {
                it in BACKDROP_INTERVAL_CHOICES
            } ?: DEFAULT_BACKDROP_INTERVAL_MINUTES
        set(value) {
            require(value in BACKDROP_INTERVAL_CHOICES) { "Unsupported backdrop interval: $value" }
            prefs.edit { putInt(KEY_BACKDROP_INTERVAL_MINUTES, value) }
        }

    /** Idle time before the screensaver, or 0 when it is off (keep-awake disabled). */
    val screensaverDelayMs: Long
        get() =
            when {
                !keepScreenOn -> 0L
                else -> screensaverDelayOverrideMs ?: (screensaverMinutes * MINUTE_MS)
            }

    /** Time between backdrop changes on the home screen. */
    val backdropIntervalMs: Long get() = backdropIntervalMinutes * MINUTE_MS

    companion object {
        const val FILE_NAME = "launcher_settings"
        const val KEY_KEEP_SCREEN_ON = "keep_screen_on"
        const val KEY_SCREENSAVER_MINUTES = "screensaver_minutes"
        const val KEY_BACKDROP_INTERVAL_MINUTES = "backdrop_interval_minutes"

        val SCREENSAVER_CHOICES: List<Int> = listOf(2, 5, 10, 15, 30)
        const val DEFAULT_SCREENSAVER_MINUTES = 5
        val BACKDROP_INTERVAL_CHOICES: List<Int> = listOf(5, 10, 15, 30, 60)
        const val DEFAULT_BACKDROP_INTERVAL_MINUTES = 10

        /** How often the backdrop changes while the screensaver runs. */
        const val SCREENSAVER_BACKDROP_INTERVAL_MS = 45_000L

        private const val MINUTE_MS = 60_000L

        /** Test hook: replaces the screensaver delay so instrumented tests need not wait minutes. */
        @VisibleForTesting
        @Volatile
        internal var screensaverDelayOverrideMs: Long? = null
    }
}
