package ai.aneesiqbal.rozan.launcher.inputs

import ai.aneesiqbal.rozan.launcher.Background
import ai.aneesiqbal.rozan.launcher.R
import ai.aneesiqbal.rozan.launcher.SystemIntents
import ai.aneesiqbal.rozan.launcher.redirect.RedirectClient
import android.app.Activity
import android.graphics.drawable.Drawable
import android.view.View
import android.widget.Toast
import androidx.core.view.isVisible
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The Inputs button and its menu ([InputsButtonMode]):
 *
 * - Where the TV has an input that Rozan can switch to, the button opens
 *   Rozan's menu, which offers the TV's own picker ([InputPickers]) when a
 *   switch fails.
 * - Otherwise, on Fire OS, where Rozan cannot switch inputs, the button opens
 *   the TV's own picker if one may be opened.
 * - Otherwise there is no button.
 *
 * The inputs and the pickers are looked up again when the home screen comes
 * to the front, when the system reports a change to the inputs, and when an
 * app is installed, updated, removed, enabled or disabled. A picker that fails
 * to start is not offered again until the apps change.
 */
internal class InputsController(
    private val activity: Activity,
    private val source: TvInputSource,
    private val pickerResolver: PickerResolver,
    /** Fire OS: the only devices where the button opens the TV's picker itself. */
    private val fireOs: Boolean,
    private val scope: CoroutineScope,
    private val button: View,
    /** The gear beside the button: takes focus when the button disappears while focused. */
    private val gear: View,
    private val panel: InputsPanel,
) : InputsPanel.Listener {
    private var entries: List<InputEntry> = emptyList()
    private var icons: Map<String, Drawable> = emptyMap()
    private val pickers = SystemPickers()
    private var readJob: Job? = null
    private var opening: Job? = null
    private var stale = false
    private var started = false

    /** What the button does now. */
    var mode: InputsButtonMode = InputsButtonMode.HIDDEN
        private set

    init {
        panel.listener = this
        panel.anchor = button
        button.setOnClickListener { onButton() }
        applyButton()
    }

    /** Starts following the system's inputs (the home screen is visible). */
    fun start() {
        started = true
        source.start(::refresh)
    }

    /** Looks again: the home screen is in front (changes while it was stopped are not reported to it). */
    fun resume() {
        refresh()
    }

    /** Stops following them (the home screen is stopped). */
    fun stop() {
        started = false
        source.stop()
        readJob?.cancel()
        readJob = null
        // Left before the picker was started: do not start it behind whatever is in front now.
        opening?.cancel()
        opening = null
    }

    /** An app was installed, updated, removed, enabled or disabled: the pickers may have changed. */
    fun onPackagesChanged() {
        pickers.forgetFailures()
        // While stopped, resume() looks again.
        if (started) refresh()
    }

    /** Whether the menu is open. */
    val isShowing: Boolean get() = panel.isShowing

    /** How many times the inputs and pickers have been looked up and applied (for tests). */
    var reads = 0
        private set

    /** Closes the menu. */
    fun hide() {
        panel.hide()
    }

    /**
     * Left and right from [focused], the button or the gear: between the two
     * while the button shows, and never out of the top bar.
     */
    fun topBarNeighbour(
        focused: View,
        direction: Int,
    ): View {
        val rtl = focused.layoutDirection == View.LAYOUT_DIRECTION_RTL
        val towardsStart = if (rtl) View.FOCUS_RIGHT else View.FOCUS_LEFT
        val towardsEnd = if (rtl) View.FOCUS_LEFT else View.FOCUS_RIGHT
        return when {
            focused === gear && direction == towardsStart && button.isVisible -> button
            focused === button && direction == towardsEnd -> gear
            else -> focused
        }
    }

    private fun onButton() {
        when (mode) {
            InputsButtonMode.MENU -> {
                panel.show(entries, icons)
                // Showing an input again, or clearing its name, in the TV's settings is not reported: read again.
                refresh()
            }

            InputsButtonMode.PICKER -> {
                openPicker { opened ->
                    if (!opened) {
                        // None will open: say so, and take the button away until the apps change.
                        Toast.makeText(activity, R.string.inputs_picker_failed, Toast.LENGTH_LONG).show()
                        applyButton()
                    }
                }
            }

            // Not shown, so not pressed.
            InputsButtonMode.HIDDEN -> {}
        }
    }

    /**
     * Opens the first of the TV's pickers that starts, then calls [done] with
     * whether one did. The Home button redirect hears of it first (a binder
     * call, so off the main thread): Fire OS draws its inputs view inside the
     * stock home, which the redirect would otherwise replace with Rozan.
     */
    private fun openPicker(done: (opened: Boolean) -> Unit) {
        if (opening?.isActive == true) return
        val appContext = activity.applicationContext
        opening =
            scope.launch {
                withContext(Background.work) { RedirectClient.opensPicker(appContext) }
                val opened = pickers.open(::startPicker)
                if (!opened) launch(Background.work) { RedirectClient.pickerFailed(appContext) }
                done(opened)
            }
    }

    private fun startPicker(target: PickerTarget): Boolean =
        SystemIntents.startFirst(activity, listOf(SystemIntents.inputPicker(target)))

    /** Reads the inputs and finds the pickers again; a change during a read reads once more after it. */
    private fun refresh() {
        if (readJob?.isActive == true) {
            stale = true
            return
        }
        stale = false
        readJob =
            scope.launch {
                val found = withContext(Background.work) { lookUp() }
                entries = found.entries
                icons = found.icons
                pickers.update(found.pickers)
                reads++
                applyButton()
                if (panel.isShowing) {
                    if (entries.isEmpty()) panel.hide() else panel.update(entries, icons)
                }
                if (stale) refresh()
            }
    }

    /** Off the main thread: binder calls to the TV input service and the package manager. */
    private fun lookUp(): Found {
        val entries = InputMenu.entries(source.read())
        val icons = entries.mapNotNull { entry -> source.loadIcon(entry.id)?.let { entry.id to it } }.toMap()
        return Found(entries, icons, InputPickers.find(pickerResolver))
    }

    private fun applyButton() {
        mode = InputsButtonMode.of(entries, pickers.targets, fireOs)
        val show = mode != InputsButtonMode.HIDDEN
        if (show == button.isVisible) return
        if (!show && button.hasFocus()) gear.requestFocus()
        button.isVisible = show
        panel.anchor = if (show) button else gear
    }

    override fun onInputSelected(entry: InputEntry) {
        if (SystemIntents.startFirst(activity, SystemIntents.passthroughInput(activity, entry.id))) {
            panel.hide()
        } else {
            panel.showSwitchFailed(entry, canOpenPicker = pickers.targets.isNotEmpty())
        }
    }

    override fun onOpenSystemPicker() {
        openPicker { opened -> if (opened) panel.hide() else panel.showPickerFailed() }
    }

    private class Found(
        val entries: List<InputEntry>,
        val icons: Map<String, Drawable>,
        val pickers: List<PickerTarget>,
    )
}
