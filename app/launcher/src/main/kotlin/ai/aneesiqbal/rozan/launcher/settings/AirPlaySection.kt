package ai.aneesiqbal.rozan.launcher.settings

import ai.aneesiqbal.rozan.airplay.DeviceNames
import ai.aneesiqbal.rozan.airplay.android.AirPlaySettings
import ai.aneesiqbal.rozan.airplay.android.OverlayPermission
import ai.aneesiqbal.rozan.airplay.android.ReceiverService
import ai.aneesiqbal.rozan.launcher.R
import ai.aneesiqbal.rozan.launcher.SystemIntents
import ai.aneesiqbal.rozan.launcher.apps.PackageChangeReceiver
import ai.aneesiqbal.rozan.ui.SettingsRowView
import ai.aneesiqbal.rozan.ui.SettingsTextFieldView
import ai.aneesiqbal.rozan.ui.SettingsWarningView
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.TextView
import androidx.core.view.isVisible

/** The standalone Rozan AirPlay app, which the launcher's built-in receiver makes unnecessary. */
internal const val STANDALONE_AIRPLAY_PACKAGE = "ai.aneesiqbal.rozan.airplay"

/**
 * The AirPlay receiver's rows: on/off, receiver name, wake on connect, and the "Show over other
 * apps" permission with its adb command while it is missing. Launcher settings shows them as one
 * section; the AirPlay tile opens a screen with only these rows
 * ([ai.aneesiqbal.rozan.launcher.AirPlaySettingsActivity]). The owning screen calls [start] and
 * [stop] from its own `onStart` and `onStop`.
 *
 * While [standaloneApp] (Rozan AirPlay) is installed too, the section starts with a warning that it
 * is not needed: Rozan has the receiver built in, and only one of the two can receive at a time.
 * Selecting the warning opens the system's uninstall confirmation for that app. It follows installs
 * and removals while started.
 */
internal class AirPlaySection(
    private val context: Context,
    items: SettingsList,
    withHeader: Boolean,
    private val standaloneApp: String = STANDALONE_AIRPLAY_PACKAGE,
) {
    private val airPlay = AirPlaySettings(context)
    private val standaloneRow: SettingsWarningView
    private val packages = PackageChangeReceiver { if (it == standaloneApp) renderStandalone() }
    private var watchingPackages = false
    private val nameField: SettingsTextFieldView
    private val nameError: TextView
    private val overlayRow: SettingsRowView
    private val overlayCommand: TextView
    private var overlayWatch: AutoCloseable? = null

    /** "Enable AirPlay", the section's first row. */
    val firstRow: SettingsRowView

    init {
        if (withHeader) items.header(R.string.settings_section_airplay)
        standaloneRow =
            items.warning(R.string.settings_airplay_app_installed, R.string.settings_airplay_app_installed_summary)
        standaloneRow.setOnClickListener { SystemIntents.startFirst(context, SystemIntents.uninstall(standaloneApp)) }
        firstRow =
            items.toggle(R.string.settings_airplay_enabled, null, { airPlay.enabled }) {
                airPlay.enabled = it
                if (it) ReceiverService.start(context)
            }
        nameField = SettingsTextFieldView(context)
        nameField.title = context.getString(R.string.settings_receiver_name)
        nameField.editText.setText(airPlay.deviceName)
        nameField.editText.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_DONE) commitReceiverName()
            false
        }
        nameField.editText.setOnFocusChangeListener { _, hasFocus -> if (!hasFocus) commitReceiverName() }
        items.add(nameField)
        nameError = items.note(R.string.settings_receiver_name_invalid).apply { visibility = View.GONE }
        items.toggle(R.string.settings_airplay_wake, null, { airPlay.wakeOnConnect }) { airPlay.wakeOnConnect = it }
        items.note(R.string.settings_airplay_pin_hint)
        overlayRow = items.row(R.string.settings_overlay)
        overlayCommand = items.codeNote(OverlayPermission.adbCommand(context))
        renderStandalone()
    }

    /** Follows the overlay permission and the standalone app's installs while the screen is started. */
    fun start() {
        overlayWatch = OverlayPermission.watch(context, ::renderOverlay)
        packages.register(context)
        watchingPackages = true
        renderOverlay()
        renderStandalone()
    }

    /** Stops following them. */
    fun stop() {
        overlayWatch?.close()
        overlayWatch = null
        if (watchingPackages) packages.unregister(context)
        watchingPackages = false
    }

    private fun renderStandalone() {
        val installed = isInstalled(context, standaloneApp)
        // Uninstalling from the row hides it while it has focus; focus stays in this section.
        if (!installed && standaloneRow.hasFocus()) firstRow.requestFocus()
        standaloneRow.isVisible = installed
    }

    /** Mirrors the standalone AirPlay app: the exact adb command while the permission is missing. */
    private fun renderOverlay() {
        val granted = OverlayPermission.isGranted(context)
        overlayRow.value =
            context.getString(
                if (granted) R.string.settings_overlay_allowed else R.string.settings_overlay_not_allowed,
            )
        overlayRow.summary =
            context.getString(
                if (granted) R.string.settings_overlay_allowed_summary else R.string.settings_overlay_missing_summary,
            )
        overlayCommand.isVisible = !granted
    }

    private fun commitReceiverName() {
        val field = nameField.editText
        val typed = field.text.toString()
        val sanitized = DeviceNames.sanitize(typed)
        if (sanitized == null) {
            nameError.visibility = View.VISIBLE
            field.setText(airPlay.deviceName)
            return
        }
        nameError.visibility = View.GONE
        if (sanitized != airPlay.deviceName) airPlay.deviceName = sanitized
        if (sanitized != typed) field.setText(sanitized)
    }

    companion object {
        /** Whether [packageName] is installed for this user. */
        fun isInstalled(
            context: Context,
            packageName: String,
        ): Boolean {
            val pm = context.packageManager
            return try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    pm.getApplicationInfo(packageName, PackageManager.ApplicationInfoFlags.of(0))
                } else {
                    // The int-flags overload is the only one before API 33; deprecated from 33 on.
                    @Suppress("DEPRECATION")
                    pm.getApplicationInfo(packageName, 0)
                }
                true
            } catch (ignored: PackageManager.NameNotFoundException) {
                false
            }
        }
    }
}
