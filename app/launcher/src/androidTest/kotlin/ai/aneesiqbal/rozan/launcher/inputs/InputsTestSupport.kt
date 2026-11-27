package ai.aneesiqbal.rozan.launcher.inputs

import ai.aneesiqbal.rozan.launcher.HomeActivity
import ai.aneesiqbal.rozan.launcher.R
import ai.aneesiqbal.rozan.launcher.device
import ai.aneesiqbal.rozan.launcher.instrumentation
import ai.aneesiqbal.rozan.launcher.read
import ai.aneesiqbal.rozan.launcher.shell
import ai.aneesiqbal.rozan.launcher.uiAutomation
import ai.aneesiqbal.rozan.launcher.waitUntil
import ai.aneesiqbal.rozan.ui.SettingsRowView
import android.app.Activity
import android.app.Instrumentation
import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Intent
import android.graphics.drawable.Drawable
import android.media.tv.TvContract
import android.view.View
import android.view.accessibility.AccessibilityEvent
import androidx.core.view.isVisible
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import java.util.concurrent.CopyOnWriteArrayList

/** The intent action that opens the system's input list (InputPickers). */
internal const val ACTION_VIEW_INPUTS = InputPickers.ACTION_VIEW_INPUTS

/** The toast after the TV's input list would not open. */
internal const val PICKER_FAILED = "This TV doesn't let Rozan open its input list."

/**
 * Stands in for the system's inputs (installed through the debug-only
 * [TvInputSources.testSource]). [emit] changes the list and reports it the
 * way the system's callback does, on the main thread.
 */
internal class FakeTvInputSource(
    records: List<InputRecord> = emptyList(),
    private val icons: Map<String, Drawable> = emptyMap(),
) : TvInputSource {
    @Volatile
    private var records: List<InputRecord> = records

    /** Touched on the main thread only. */
    private var listener: Runnable? = null

    override fun read(): List<InputRecord> = records

    override fun loadIcon(id: String): Drawable? = icons[id]

    override fun start(onChanged: Runnable) {
        listener = onChanged
    }

    override fun stop() {
        listener = null
    }

    fun emit(newRecords: List<InputRecord>) {
        records = newRecords
        instrumentation.runOnMainSync { listener?.run() }
    }
}

/**
 * Stands in for the package manager's answers about the TV's input pickers
 * (installed through the debug-only [TvInputSources.testPickerResolver]):
 * [pickers] answer the Live TV action, as system activities that anyone may
 * start. None by default, as on a device without a picker.
 */
internal class FakePickerResolver(
    private val pickers: List<ComponentName> = emptyList(),
) : PickerResolver {
    override fun query(query: PickerQuery): List<PickerActivity> =
        if (query == PickerQuery(ACTION_VIEW_INPUTS)) {
            pickers.map {
                PickerActivity(
                    it.packageName,
                    it.className,
                    exported = true,
                    permission = null,
                    system = true,
                )
            }
        } else {
            emptyList()
        }

    override fun systemDefault(query: PickerQuery): PickerActivity? = null

    override fun isGranted(permission: String): Boolean = false

    override fun homePackages(): Set<String> = emptySet()
}

/** A picker the package manager would list that no app on the device has: starting it fails. */
internal val MISSING_PICKER = ComponentName("com.example.missing.tv", "com.example.missing.tv.InputPickerActivity")

/** The component of [target], as the system logs it. */
internal val PickerTarget.component: ComponentName get() = ComponentName(packageName, className)

internal fun hdmi(
    id: String,
    label: String,
    customLabel: String? = null,
    state: InputState = InputState.CONNECTED,
    hidden: Boolean = false,
    parentId: String? = null,
) = InputRecord(id, InputKind.HDMI, passthrough = true, label, customLabel, hidden, parentId, state)

/**
 * Records the intents for switching inputs and for the system's input list
 * instead of starting them, so a test sees exactly what Rozan sends whatever
 * apps the device has. With [noTvApp], switching fails as it does on a
 * device where nothing handles it. [refusedPickers] fail with a
 * SecurityException, as a picker behind a permission does. With
 * [passPickers], input list starts go on to the system instead.
 */
internal class InputIntentRecorder(
    private val noTvApp: Boolean = false,
    private val refusedPickers: Set<ComponentName> = emptySet(),
    private val passPickers: Boolean = false,
) : Instrumentation.ActivityMonitor() {
    val intents = CopyOnWriteArrayList<Intent>()

    override fun onStartActivity(intent: Intent): Instrumentation.ActivityResult? {
        val switch = intent.action == Intent.ACTION_VIEW && intent.data?.authority == TvContract.AUTHORITY
        val picker = intent.action == ACTION_VIEW_INPUTS
        if (!switch && !picker) return null
        intents += Intent(intent)
        if (switch && noTvApp) throw ActivityNotFoundException("No activity found to handle $intent (test)")
        if (picker && intent.component in refusedPickers) throw SecurityException("Permission Denial: $intent (test)")
        if (picker && passPickers) return null
        return Instrumentation.ActivityResult(Activity.RESULT_OK, null)
    }
}

/** Runs [block] with [recorder] catching the activity starts it is for. */
internal fun <T> recording(
    recorder: InputIntentRecorder,
    block: () -> T,
): T {
    instrumentation.addMonitor(recorder)
    try {
        return block()
    } finally {
        instrumentation.removeMonitor(recorder)
    }
}

/** Runs [action] and waits until a toast says [text]; fails after [timeoutMs]. */
internal fun expectToast(
    text: String,
    timeoutMs: Long = 5_000,
    action: () -> Unit,
) {
    uiAutomation().executeAndWaitForEvent(
        { action() },
        { event ->
            event.eventType == AccessibilityEvent.TYPE_NOTIFICATION_STATE_CHANGED &&
                event.text.any { it.toString() == text }
        },
        timeoutMs,
    )
}

/** The system's log lines for activity starts since the last `logcat -c`. */
internal fun activityStarts(): List<String> =
    shell("logcat -d -s ActivityTaskManager:I").lines().filter { it.contains("START u0") }

/** Looks the inputs and pickers up again, as when the home screen comes back to the front, and waits for it. */
internal fun ActivityScenario<HomeActivity>.pauseAndResume() {
    val before = read { it.inputsReads }
    moveToState(Lifecycle.State.STARTED)
    moveToState(Lifecycle.State.RESUMED)
    waitUntil("inputs looked up again") { read { it.inputsReads } > before }
}

/** From the first tile: Up to the gear, Left to Inputs. */
internal fun ActivityScenario<HomeActivity>.focusInputsButton() {
    device.pressDPadUp()
    waitUntil("focus on the gear") { focusedId() == R.id.settings_button }
    device.pressDPadLeft()
    waitUntil("focus on Inputs") { focusedId() == R.id.inputs_button }
}

internal val ActivityScenario<HomeActivity>.inputsButtonVisible: Boolean
    get() = read { it.findViewById<View>(R.id.inputs_button).isVisible }

internal val ActivityScenario<HomeActivity>.menuShowing: Boolean
    get() = read { it.findViewById<InputsPanel>(R.id.inputs_panel).isShowing }

internal fun ActivityScenario<HomeActivity>.focusedId(): Int? = read { it.currentFocus?.id }

/** The menu's rows as (title, detail, state word, dimmed). */
internal fun ActivityScenario<HomeActivity>.menuRows(): List<List<Any?>> =
    read { home ->
        home.findViewById<InputsPanel>(R.id.inputs_panel).inputRows().map { row ->
            val entry = row.entry
            listOf(entry?.title, entry?.detail, row.stateText?.toString(), row.isDimmed)
        }
    }

/** The panel's message after a failed switch or list, or null. */
internal fun ActivityScenario<HomeActivity>.panelMessage(): String? =
    read { it.findViewById<InputsPanel>(R.id.inputs_panel).messageText?.toString() }

/** Title of the focused settings-style row (the TV's input list), or null. */
internal fun ActivityScenario<HomeActivity>.focusedSettingsRowTitle(): String? =
    read { (it.currentFocus as? SettingsRowView)?.title?.toString() }

/** Title of the focused menu row, or null when focus is not on one. */
internal fun ActivityScenario<HomeActivity>.focusedRowTitle(): String? =
    read { (it.currentFocus as? InputRowView)?.entry?.title }

/** From the first tile: Up to the gear, Left to Inputs, Select. Waits until the menu is open. */
internal fun ActivityScenario<HomeActivity>.openMenuWithDpad() {
    focusInputsButton()
    device.pressDPadCenter()
    waitUntil("menu open") { menuShowing }
    device.waitForIdle()
}
