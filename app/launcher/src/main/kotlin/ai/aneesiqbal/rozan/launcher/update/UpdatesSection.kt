package ai.aneesiqbal.rozan.launcher.update

import ai.aneesiqbal.rozan.launcher.R
import ai.aneesiqbal.rozan.ui.SettingsRowView
import android.content.Context
import android.content.SharedPreferences
import android.text.format.DateFormat
import android.widget.TextView
import androidx.core.view.isVisible
import java.text.SimpleDateFormat
import java.util.Date

/**
 * The "Updates" section of the launcher settings, over rows the settings
 * screen built: the "Update automatically" switch, whose summary is the
 * updater's status; "Install update X.Y.Z", shown only while an update waits
 * for the user; and "Install unknown apps" with the adb command below it
 * while the permission is missing. Live while [start]ed: the status follows
 * [UpdateState] and the permission its app-op.
 */
internal class UpdatesSection(
    private val context: Context,
    private val toggleRow: SettingsRowView,
    private val installRow: SettingsRowView,
    private val permissionRow: SettingsRowView,
    private val permissionCommand: TextView,
) {
    private val state = UpdateState(context)
    private val stateListener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ -> render() }
    private var permissionWatch: AutoCloseable? = null

    init {
        installRow.summary = context.getString(R.string.settings_update_install_summary)
        installRow.setOnClickListener { AutoUpdate.installNow(context) }
        render()
    }

    /** Follows the state and the permission until [stop]. */
    fun start() {
        state.addListener(stateListener)
        permissionWatch = InstallPermission.watch(context, ::render)
        render()
    }

    /** Stops following; nothing the system holds reaches this section afterwards. */
    fun stop() {
        state.removeListener(stateListener)
        permissionWatch?.close()
        permissionWatch = null
    }

    private fun render() {
        val enabled = state.enabled
        toggleRow.checked = enabled
        toggleRow.summary = status(enabled)
        val pending = state.pending
        val ask = enabled && pending != null && pending.stage == UpdateState.Stage.ASK_USER
        // Selecting "Install update" hides it while the install starts; focus stays in this section instead
        // of falling to the first row of the screen.
        if (!ask && installRow.hasFocus()) toggleRow.requestFocus()
        installRow.isVisible = ask
        if (pending != null) installRow.title = context.getString(R.string.settings_update_install, pending.versionName)
        val granted = InstallPermission.isGranted(context)
        permissionRow.value = context.getString(if (granted) PERMISSION_ALLOWED else PERMISSION_NOT_ALLOWED)
        permissionRow.summary =
            context.getString(if (granted) PERMISSION_ALLOWED_SUMMARY else PERMISSION_MISSING_SUMMARY)
        permissionCommand.isVisible = !granted
    }

    private fun status(enabled: Boolean): String {
        if (!enabled) return context.getString(R.string.settings_update_off_summary)
        val pending = state.pending
        if (pending != null) {
            val text =
                when (pending.stage) {
                    null -> R.string.settings_update_downloaded
                    UpdateState.Stage.WAIT_FOR_SCREEN -> R.string.settings_update_waiting_screen
                    UpdateState.Stage.WAIT_FOR_AIRPLAY -> R.string.settings_update_waiting_airplay
                    UpdateState.Stage.ASK_USER -> R.string.settings_update_ask
                    UpdateState.Stage.INSTALLING -> R.string.settings_update_installing
                }
            return context.getString(text, pending.versionName)
        }
        val time = formatTime(state.lastCheckAt)
        val version = state.lastVersion.orEmpty()
        return when (state.lastResult) {
            null -> context.getString(R.string.settings_update_not_checked)
            UpdateState.Result.UP_TO_DATE -> context.getString(R.string.settings_update_up_to_date, version, time)
            UpdateState.Result.UPDATED -> context.getString(R.string.settings_update_updated, version, time)
            UpdateState.Result.DOWNLOADED -> context.getString(R.string.settings_update_downloaded, version)
            UpdateState.Result.FAILED -> context.getString(R.string.settings_update_failed, time, failureText())
        }
    }

    private fun failureText(): String =
        when (state.failure) {
            UpdateState.Failure.NETWORK, null -> {
                context.getString(R.string.settings_update_failure_network)
            }

            UpdateState.Failure.SERVER -> {
                context.getString(
                    R.string.settings_update_failure_server,
                    state.failureStatus,
                )
            }

            UpdateState.Failure.RELEASE -> {
                context.getString(R.string.settings_update_failure_release)
            }

            UpdateState.Failure.CHECKSUM -> {
                context.getString(R.string.settings_update_failure_checksum)
            }

            UpdateState.Failure.PACKAGE -> {
                context.getString(R.string.settings_update_failure_package)
            }

            UpdateState.Failure.STORAGE -> {
                context.getString(R.string.settings_update_failure_storage)
            }

            UpdateState.Failure.INSTALL -> {
                context.getString(R.string.settings_update_failure_install)
            }
        }

    /** Day, month and time in the app language, on the system's 12- or 24-hour clock. */
    private fun formatTime(millis: Long): String {
        val locale = context.resources.configuration.locales[0]
        val skeleton = if (DateFormat.is24HourFormat(context)) SKELETON_24 else SKELETON_12
        return SimpleDateFormat(DateFormat.getBestDateTimePattern(locale, skeleton), locale).format(Date(millis))
    }

    private companion object {
        const val SKELETON_24 = "dMMMHHmm"
        const val SKELETON_12 = "dMMMhmma"
        val PERMISSION_ALLOWED = R.string.settings_update_permission_allowed
        val PERMISSION_NOT_ALLOWED = R.string.settings_update_permission_not_allowed
        val PERMISSION_ALLOWED_SUMMARY = R.string.settings_update_permission_allowed_summary
        val PERMISSION_MISSING_SUMMARY = R.string.settings_update_permission_missing_summary
    }
}
