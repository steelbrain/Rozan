package ai.aneesiqbal.rozan.launcher

import ai.aneesiqbal.rozan.airplay.android.AppLanguage
import ai.aneesiqbal.rozan.launcher.redirect.HomeButtonState
import ai.aneesiqbal.rozan.launcher.redirect.HomeMode
import ai.aneesiqbal.rozan.launcher.redirect.RedirectClient
import ai.aneesiqbal.rozan.launcher.settings.AirPlaySection
import ai.aneesiqbal.rozan.launcher.settings.LanguageRow
import ai.aneesiqbal.rozan.launcher.settings.LauncherSettings
import ai.aneesiqbal.rozan.launcher.settings.SettingsList
import ai.aneesiqbal.rozan.launcher.update.AutoUpdate
import ai.aneesiqbal.rozan.launcher.update.InstallPermission
import ai.aneesiqbal.rozan.launcher.update.UpdatesSection
import ai.aneesiqbal.rozan.ui.SettingsRowView
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.View
import android.widget.TextView
import androidx.core.view.doOnLayout
import androidx.core.view.isVisible
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Launcher settings: the app language, home screen behaviour, the Home button
 * redirect, AirPlay receiver, updates, and about (version, license, source code
 * repository, open-source notices, backdrop photo credits; the last two open
 * screens of their own). One vertical list of D-pad rows; every change is saved
 * immediately.
 */
class LauncherSettingsActivity : LocalizedActivity() {
    private val scope = MainScope()
    private lateinit var settings: LauncherSettings
    private lateinit var items: SettingsList
    private lateinit var keepAwakeRow: SettingsRowView
    private lateinit var screensaverRow: SettingsRowView
    private lateinit var homeButtonRow: SettingsRowView
    private lateinit var homeButtonDetail: TextView
    private lateinit var homeButtonCommands: TextView
    private lateinit var homeButtonAdbNote: TextView
    private lateinit var stockHomeRow: SettingsRowView
    private var homeButton: HomeButtonState? = null
    private lateinit var airPlay: AirPlaySection
    private lateinit var updates: UpdatesSection

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)
        settings = LauncherSettings(this)
        items = SettingsList(findViewById(R.id.settings_list))
        LanguageRow.bind(items.row(R.string.settings_language))
        buildHomeSection()
        buildHomeButtonSection()
        airPlay = AirPlaySection(this, items, withHeader = true)
        buildUpdatesSection()
        buildAboutSection()
        val section =
            if (intent.component?.className == REDIRECT_SETTINGS_ALIAS) {
                SECTION_HOME_BUTTON
            } else {
                intent.getStringExtra(EXTRA_SECTION)
            }
        val initial = if (section == SECTION_HOME_BUTTON) homeButtonRow else keepAwakeRow
        initial.requestFocus()
        if (section == SECTION_HOME_BUTTON && savedInstanceState == null) {
            // A section opened by name starts at its header, so as much of it as fits is on screen, not
            // only its first row at the bottom edge. The header is the row's previous sibling. Runs after
            // the scroller's own layout, which scrolls just far enough to show the focused row.
            val header = items.before(initial)
            findViewById<View>(R.id.settings_scroll).doOnLayout { it.scrollTo(0, header.top) }
        }
    }

    override fun onStart() {
        super.onStart()
        airPlay.start()
        updates.start()
    }

    override fun onResume() {
        super.onResume()
        // The service may have been turned on or off in the system's settings meanwhile.
        scope.launch {
            val state = withContext(Background.work) { HomeButtonState.read(this@LauncherSettingsActivity) }
            showHomeButton(state)
        }
    }

    override fun onStop() {
        airPlay.stop()
        updates.stop()
        super.onStop()
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private fun buildHomeSection() {
        items.header(R.string.settings_section_home)
        keepAwakeRow =
            items.toggle(
                R.string.settings_keep_awake,
                R.string.settings_keep_awake_summary,
                { settings.keepScreenOn },
            ) {
                settings.keepScreenOn = it
                updateScreensaverRow()
            }
        screensaverRow =
            items.choice(
                R.string.settings_screensaver_after,
                LauncherSettings.SCREENSAVER_CHOICES,
                { settings.screensaverMinutes },
            ) {
                settings.screensaverMinutes = it
            }
        updateScreensaverRow()
        items.choice(R.string.settings_backdrop_interval, LauncherSettings.BACKDROP_INTERVAL_CHOICES, {
            settings.backdropIntervalMinutes
        }) {
            settings.backdropIntervalMinutes = it
        }
    }

    private fun updateScreensaverRow() {
        val enabled = settings.keepScreenOn
        screensaverRow.isEnabled = enabled
        screensaverRow.isFocusable = enabled
        screensaverRow.summary = if (enabled) null else getString(R.string.settings_screensaver_needs_keep_awake)
    }

    private fun buildHomeButtonSection() {
        items.header(R.string.settings_section_home_button)
        homeButtonRow = items.row(R.string.settings_home_button)
        homeButtonRow.setOnClickListener {
            SystemIntents.startFirst(this, SystemIntents.accessibilitySettings(this))
        }
        // The explanation is longer than a row summary's two lines, so it goes below the row.
        homeButtonDetail = items.note(R.string.settings_home_button_off_detail)
        // The adb commands from the research doc, section 7: append to the list, never overwrite it.
        val service = RedirectClient.serviceComponent(this).flattenToShortString()
        homeButtonCommands =
            items.codeNote(
                "adb shell settings get secure enabled_accessibility_services\n" +
                    "adb shell settings put secure enabled_accessibility_services LIST:$service\n" +
                    "adb shell settings put secure accessibility_enabled 1",
            )
        homeButtonAdbNote = items.note(R.string.settings_home_button_adb_note)
        stockHomeRow = items.row(R.string.settings_open_stock_home)
        stockHomeRow.summary = getString(R.string.settings_open_stock_home_summary)
        stockHomeRow.setOnClickListener { openStockHome() }
        showHomeButton(null)
    }

    private fun showHomeButton(state: HomeButtonState?) {
        homeButton = state
        val status = state?.status
        homeButtonRow.value =
            getString(
                when (status) {
                    null -> R.string.settings_home_button_checking
                    HomeButtonState.Status.OPENS_ROZAN -> R.string.settings_home_button_opens_rozan
                    HomeButtonState.Status.ON -> R.string.settings_home_button_on
                    HomeButtonState.Status.NOT_RUNNING -> R.string.settings_home_button_not_running
                    HomeButtonState.Status.OFF -> R.string.settings_home_button_off
                },
            )
        val texts = state?.let(::homeButtonTexts)
        homeButtonRow.summary = texts?.first
        homeButtonDetail.text = texts?.second
        homeButtonDetail.isVisible = texts?.second != null
        val redirect = state?.setup?.mode == HomeMode.REDIRECT
        val needsSetup =
            redirect && (status == HomeButtonState.Status.OFF || status == HomeButtonState.Status.NOT_RUNNING)
        homeButtonCommands.isVisible = needsSetup
        homeButtonAdbNote.isVisible = needsSetup
        stockHomeRow.isVisible = redirect
    }

    /** The Home button row's summary, and the longer explanation shown below the row (if any). */
    private fun homeButtonTexts(state: HomeButtonState): Pair<String, String?> {
        val summary =
            when {
                state.status == HomeButtonState.Status.OPENS_ROZAN -> {
                    getString(R.string.settings_home_button_native_summary)
                }

                state.setup.mode != HomeMode.REDIRECT -> {
                    getString(R.string.settings_home_button_none_summary)
                }

                else -> {
                    getString(R.string.settings_home_button_stock, state.stockHomeName.orEmpty())
                }
            }
        // The redirect service as the system's accessibility settings name it: they read the label from
        // the manifest in the system language, which need not be the app language.
        val service = AppLanguage.inSystemLanguage(this).getString(R.string.redirect_service_label)
        val detail =
            when {
                state.status == HomeButtonState.Status.OPENS_ROZAN || state.setup.mode != HomeMode.REDIRECT -> {
                    null
                }

                state.status == HomeButtonState.Status.ON -> {
                    getString(R.string.settings_home_button_on_detail, service)
                }

                state.status == HomeButtonState.Status.NOT_RUNNING -> {
                    getString(R.string.settings_home_button_not_running_detail, service)
                }

                else -> {
                    getString(R.string.settings_home_button_off_detail, service)
                }
            }
        val unknown = state.unrecognizedNames
        if (unknown.isEmpty()) return summary to detail
        val unrecognized = getString(R.string.settings_home_button_unrecognized, unknown.joinToString())
        return summary to listOfNotNull(detail, unrecognized).joinToString("\n")
    }

    /** `S` (research doc 6.4): tell the redirect first, so the stock home is not sent straight back to Rozan. */
    private fun openStockHome() {
        val enabled = homeButton?.enabled == true
        val context = this
        scope.launch {
            val standIn =
                if (enabled) {
                    withContext(
                        Background.work,
                    ) { RedirectClient.stayOnStockHome(context) }
                } else {
                    null
                }
            SystemIntents.startFirst(context, SystemIntents.stockHome(standIn))
        }
    }

    /** Updates: the switch and its status, "Install update" while one waits for the user, and the permission. */
    private fun buildUpdatesSection() {
        items.header(R.string.settings_section_updates)
        val auto =
            items.toggle(
                R.string.settings_update_auto,
                null,
                { AutoUpdate.isEnabled(this) },
            ) { AutoUpdate.setEnabled(this, it) }
        val install = items.row(R.string.settings_update_install)
        val permission = items.row(R.string.settings_update_permission)
        updates = UpdatesSection(this, auto, install, permission, items.codeNote(InstallPermission.adbCommand(this)))
    }

    private fun buildAboutSection() {
        items.header(R.string.settings_section_about)
        items.row(R.string.settings_version).value = versionName()
        items.row(R.string.settings_license).value = getString(R.string.settings_license_value)
        items.row(R.string.settings_source_code).value = getString(R.string.settings_source_code_url)
        items.row(R.string.settings_notices).apply {
            summary = getString(R.string.settings_notices_summary)
            setOnClickListener {
                startActivity(Intent(this@LauncherSettingsActivity, OpenSourceNoticesActivity::class.java))
            }
        }
        items.row(R.string.settings_section_credits).apply {
            summary = getString(R.string.settings_credits_summary)
            setOnClickListener {
                startActivity(Intent(this@LauncherSettingsActivity, BackdropCreditsActivity::class.java))
            }
        }
    }

    private fun versionName(): String {
        val info =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                packageManager.getPackageInfo(packageName, PackageManager.PackageInfoFlags.of(0L))
            } else {
                // The int-flags overload is the only one before API 33; deprecated from 33 on.
                @Suppress("DEPRECATION")
                packageManager.getPackageInfo(packageName, 0)
            }
        return info.versionName.orEmpty()
    }

    companion object {
        /** Intent extra naming the section to focus first. */
        const val EXTRA_SECTION = "ai.aneesiqbal.rozan.launcher.extra.SECTION"

        /** [EXTRA_SECTION] value for the Home button section. */
        const val SECTION_HOME_BUTTON = "home_button"

        /** The alias the accessibility service's settings link opens (manifest). */
        private const val REDIRECT_SETTINGS_ALIAS = "ai.aneesiqbal.rozan.launcher.RedirectSettingsActivity"
    }
}
