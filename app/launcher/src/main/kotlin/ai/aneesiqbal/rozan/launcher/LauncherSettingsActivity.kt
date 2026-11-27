package ai.aneesiqbal.rozan.launcher

import ai.aneesiqbal.rozan.airplay.DeviceNames
import ai.aneesiqbal.rozan.airplay.android.AirPlaySettings
import ai.aneesiqbal.rozan.airplay.android.AppLanguage
import ai.aneesiqbal.rozan.airplay.android.OverlayPermission
import ai.aneesiqbal.rozan.airplay.android.ReceiverService
import ai.aneesiqbal.rozan.launcher.backdrop.BackdropImage
import ai.aneesiqbal.rozan.launcher.backdrop.BackdropSource
import ai.aneesiqbal.rozan.launcher.redirect.HomeButtonState
import ai.aneesiqbal.rozan.launcher.redirect.HomeMode
import ai.aneesiqbal.rozan.launcher.redirect.RedirectClient
import ai.aneesiqbal.rozan.launcher.settings.LanguageRow
import ai.aneesiqbal.rozan.launcher.settings.LauncherSettings
import ai.aneesiqbal.rozan.ui.SettingsRowView
import ai.aneesiqbal.rozan.ui.SettingsTextFieldView
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Typeface
import android.os.Build
import android.os.Bundle
import android.text.Spanned
import android.text.style.LeadingMarginSpan
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.LinearLayout
import android.widget.TextView
import androidx.annotation.StringRes
import androidx.core.text.toSpannable
import androidx.core.view.doOnLayout
import androidx.core.view.isVisible
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import ai.aneesiqbal.rozan.ui.R as UiR

/**
 * Launcher settings: the app language, home screen behaviour, the Home button
 * redirect, AirPlay receiver, and about (version, license, backdrop credits,
 * open-source notices). One vertical list of D-pad rows; every change is saved
 * immediately.
 */
class LauncherSettingsActivity : LocalizedActivity() {
    private val scope = MainScope()
    private lateinit var settings: LauncherSettings
    private lateinit var airPlay: AirPlaySettings
    private lateinit var list: LinearLayout
    private lateinit var keepAwakeRow: SettingsRowView
    private lateinit var screensaverRow: SettingsRowView
    private lateinit var homeButtonRow: SettingsRowView
    private lateinit var homeButtonDetail: TextView
    private lateinit var homeButtonCommands: TextView
    private lateinit var homeButtonAdbNote: TextView
    private lateinit var stockHomeRow: SettingsRowView
    private var homeButton: HomeButtonState? = null
    private lateinit var airPlayRow: SettingsRowView
    private lateinit var overlayRow: SettingsRowView
    private lateinit var overlayCommand: TextView
    private var overlayWatch: AutoCloseable? = null
    private lateinit var nameField: SettingsTextFieldView
    private lateinit var nameError: TextView
    private lateinit var creditsAnchor: View

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)
        settings = LauncherSettings(this)
        airPlay = AirPlaySettings(this)
        list = findViewById(R.id.settings_list)
        LanguageRow.bind(row(R.string.settings_language))
        buildHomeSection()
        buildHomeButtonSection()
        buildAirPlaySection()
        buildAboutSection()
        loadCredits()
        val section =
            if (intent.component?.className == REDIRECT_SETTINGS_ALIAS) {
                SECTION_HOME_BUTTON
            } else {
                intent.getStringExtra(EXTRA_SECTION)
            }
        val initial =
            when (section) {
                SECTION_AIRPLAY -> airPlayRow
                SECTION_HOME_BUTTON -> homeButtonRow
                else -> keepAwakeRow
            }
        initial.requestFocus()
        if (section != null && savedInstanceState == null) {
            // A section opened by name starts at its header, so as much of it as fits is on screen, not
            // only its first row at the bottom edge. The header is the row's previous sibling. Runs after
            // the scroller's own layout, which scrolls just far enough to show the focused row.
            val header = list.getChildAt(list.indexOfChild(initial) - 1)
            findViewById<View>(R.id.settings_scroll).doOnLayout { it.scrollTo(0, header.top) }
        }
    }

    override fun onStart() {
        super.onStart()
        overlayWatch = OverlayPermission.watch(this, ::renderOverlay)
        renderOverlay()
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
        overlayWatch?.close()
        overlayWatch = null
        super.onStop()
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private fun buildHomeSection() {
        header(R.string.settings_section_home)
        keepAwakeRow =
            toggle(R.string.settings_keep_awake, R.string.settings_keep_awake_summary, { settings.keepScreenOn }) {
                settings.keepScreenOn = it
                updateScreensaverRow()
            }
        screensaverRow =
            choice(
                R.string.settings_screensaver_after,
                LauncherSettings.SCREENSAVER_CHOICES,
                { settings.screensaverMinutes },
            ) {
                settings.screensaverMinutes = it
            }
        updateScreensaverRow()
        choice(R.string.settings_backdrop_interval, LauncherSettings.BACKDROP_INTERVAL_CHOICES, {
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
        header(R.string.settings_section_home_button)
        homeButtonRow = row(R.string.settings_home_button)
        homeButtonRow.setOnClickListener {
            SystemIntents.startFirst(this, SystemIntents.accessibilitySettings(this))
        }
        // The explanation is longer than a row summary's two lines, so it goes below the row.
        homeButtonDetail = note(R.string.settings_home_button_off_detail)
        // The adb commands from the research doc, section 7: append to the list, never overwrite it.
        val service = RedirectClient.serviceComponent(this).flattenToShortString()
        homeButtonCommands =
            codeNote(
                "adb shell settings get secure enabled_accessibility_services\n" +
                    "adb shell settings put secure enabled_accessibility_services LIST:$service\n" +
                    "adb shell settings put secure accessibility_enabled 1",
            )
        homeButtonAdbNote = note(R.string.settings_home_button_adb_note)
        stockHomeRow = row(R.string.settings_open_stock_home)
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

    private fun buildAirPlaySection() {
        header(R.string.settings_section_airplay)
        airPlayRow =
            toggle(R.string.settings_airplay_enabled, null, { airPlay.enabled }) {
                airPlay.enabled = it
                if (it) ReceiverService.start(this)
            }
        nameField = SettingsTextFieldView(this)
        nameField.title = getString(R.string.settings_receiver_name)
        nameField.editText.setText(airPlay.deviceName)
        nameField.editText.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_DONE) commitReceiverName()
            false
        }
        nameField.editText.setOnFocusChangeListener { _, hasFocus -> if (!hasFocus) commitReceiverName() }
        list.addView(nameField, rowParams())
        nameError = note(R.string.settings_receiver_name_invalid).apply { visibility = View.GONE }
        toggle(R.string.settings_airplay_wake, null, { airPlay.wakeOnConnect }) { airPlay.wakeOnConnect = it }
        note(R.string.settings_airplay_pin_hint)
        overlayRow = row(R.string.settings_overlay)
        overlayCommand = codeNote(OverlayPermission.adbCommand(this))
    }

    /** Mirrors the standalone AirPlay app: the exact adb command while the permission is missing. */
    private fun renderOverlay() {
        val granted = OverlayPermission.isGranted(this)
        overlayRow.value =
            getString(if (granted) R.string.settings_overlay_allowed else R.string.settings_overlay_not_allowed)
        overlayRow.summary =
            getString(
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

    private fun buildAboutSection() {
        header(R.string.settings_section_about)
        row(R.string.settings_version).value = versionName()
        row(R.string.settings_license).value = getString(R.string.settings_license_value)
        row(R.string.settings_notices).apply {
            summary = getString(R.string.settings_notices_summary)
            setOnClickListener {
                startActivity(
                    Intent(this@LauncherSettingsActivity, OpenSourceNoticesActivity::class.java),
                )
            }
        }
        header(R.string.settings_section_credits)
        creditsAnchor = note(R.string.settings_credits_loading)
    }

    private fun loadCredits() {
        scope.launch {
            val images = withContext(Background.work) { BackdropSource(this@LauncherSettingsActivity).loadImages() }
            showCredits(images)
        }
    }

    private fun showCredits(images: List<BackdropImage>) {
        val index = list.indexOfChild(creditsAnchor)
        list.removeView(creditsAnchor)
        if (images.isEmpty()) {
            creditsAnchor = note(R.string.settings_credits_none, index)
            return
        }
        images.forEachIndexed { offset, image ->
            val credit = SettingsRowView(this)
            credit.title = image.title.ifEmpty { image.file }
            val line = listOf(image.author, image.license).filter { it.isNotEmpty() }.joinToString(" · ")
            credit.summary = if (image.sourceUrl.isEmpty()) line else "$line\n${image.sourceUrl}"
            list.addView(credit, index + offset, rowParams())
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

    private fun header(
        @StringRes text: Int,
    ) {
        val view =
            TextView(this).apply {
                setTextAppearance(UiR.style.TextAppearance_Rozan_SectionHeader)
                setText(text)
                val side = resources.getDimensionPixelSize(UiR.dimen.rozan_row_padding_horizontal)
                setPaddingRelative(side, resources.getDimensionPixelSize(R.dimen.settings_header_top), side, 0)
            }
        list.addView(view, rowParams())
    }

    private fun note(
        @StringRes text: Int,
        index: Int = -1,
    ): TextView {
        val view =
            TextView(this).apply {
                setText(text)
                setTextColor(getColor(UiR.color.rozan_text_secondary))
                textSize = NOTE_TEXT_SP
                val side = resources.getDimensionPixelSize(UiR.dimen.rozan_row_padding_horizontal)
                setPaddingRelative(side, 0, side, resources.getDimensionPixelSize(R.dimen.settings_note_bottom))
            }
        list.addView(view, index, rowParams())
        return view
    }

    /**
     * Shell commands, one per line, shown verbatim in a monospace font. A command that wraps
     * continues on indented lines, so each new command is the one that starts at the margin.
     */
    private fun codeNote(text: String): TextView {
        val view =
            TextView(this).apply {
                typeface = Typeface.MONOSPACE
                // Small enough that the longest unbreakable word (LIST:<service>) fits on one line.
                textSize = CODE_TEXT_SP
                val indent = paint.measureText(CODE_CONTINUATION_INDENT).toInt()
                this.text =
                    text.toSpannable().apply {
                        setSpan(LeadingMarginSpan.Standard(0, indent), 0, length, Spanned.SPAN_INCLUSIVE_INCLUSIVE)
                    }
                setTextColor(getColor(UiR.color.rozan_text_primary))
                val side = resources.getDimensionPixelSize(UiR.dimen.rozan_row_padding_horizontal)
                setPaddingRelative(side, 0, side, resources.getDimensionPixelSize(R.dimen.settings_note_bottom))
            }
        list.addView(view, rowParams())
        return view
    }

    private fun row(
        @StringRes title: Int,
    ): SettingsRowView {
        val row = SettingsRowView(this)
        row.title = getString(title)
        list.addView(row, rowParams())
        return row
    }

    private fun toggle(
        @StringRes title: Int,
        @StringRes summary: Int?,
        read: () -> Boolean,
        write: (Boolean) -> Unit,
    ): SettingsRowView {
        val row = row(title)
        if (summary != null) row.summary = getString(summary)
        row.checked = read()
        row.setOnClickListener {
            write(!read())
            row.checked = read()
        }
        return row
    }

    private fun choice(
        @StringRes title: Int,
        choices: List<Int>,
        read: () -> Int,
        write: (Int) -> Unit,
    ): SettingsRowView {
        val row = row(title)

        fun show() {
            row.value = resources.getQuantityString(R.plurals.minutes, read(), read())
        }

        fun step(delta: Int) {
            val index = choices.indexOf(read()).coerceAtLeast(0)
            write(choices[(index + delta + choices.size) % choices.size])
            show()
        }
        show()
        row.setOnClickListener { step(1) }
        row.adjustListener = SettingsRowView.AdjustListener { step(it) }
        return row
    }

    private fun rowParams() =
        LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)

    companion object {
        /** Intent extra naming the section to focus first. */
        const val EXTRA_SECTION = "ai.aneesiqbal.rozan.launcher.extra.SECTION"

        /** [EXTRA_SECTION] value for the AirPlay section. */
        const val SECTION_AIRPLAY = "airplay"

        /** [EXTRA_SECTION] value for the Home button section. */
        const val SECTION_HOME_BUTTON = "home_button"

        /** The alias the accessibility service's settings link opens (manifest). */
        private const val REDIRECT_SETTINGS_ALIAS = "ai.aneesiqbal.rozan.launcher.RedirectSettingsActivity"

        private const val NOTE_TEXT_SP = 14f
        private const val CODE_TEXT_SP = 13f
        private const val CODE_CONTINUATION_INDENT = "  "
    }
}
