package ai.aneesiqbal.rozan.launcher.redirect

/**
 * The redirect rules of docs/research/home-button-override.md, section 6.4:
 * an event-driven state machine with no timers. Pure logic; the accessibility
 * service feeds it events and carries out [Action.START_ROZAN].
 *
 * Inputs:
 * - `W(pkg, cls)`: [onWindowStateChanged], a `TYPE_WINDOW_STATE_CHANGED` event.
 * - `H`: [onHomePressed], evidence that Home was pressed (the "homekey"
 *   broadcast, where the platform delivers it).
 * - `K(code)`: [onKeyObserved], an observed key. The service has no key
 *   filter (6.6), so today nothing calls this; the rules are kept complete
 *   so the opt-in observation can feed them.
 * - `C`: [onRozanCrashed], a crash report from Rozan's UI process.
 * - `S`: [onStayOnStockHome], the user chose the stock home screen.
 * - `O`: [onRozanOpensPicker], Rozan is about to open the TV's input picker,
 *   which may be drawn by the stock home itself (Fire OS's inputs view);
 *   [onRozanPickerFailed] takes it back when nothing opened.
 *
 * The one invariant: [Action.START_ROZAN] is only ever returned while the
 * active window belongs to the stock launcher and the event was for one of
 * the exact `stockHome` activities (or was `H` on top of the stock launcher).
 * No Settings screen, panel, picker or dialog is in that set.
 */
class HomeRedirector(
    private val rozanHome: ActivityId,
) {
    /** What the service can ask the system about windows. */
    interface Windows {
        /** Whether [activity] names an activity (checked with the package manager, cached by the caller). */
        fun isActivity(activity: ActivityId): Boolean

        /** Package of the active window's root node (`getWindows()`), or null if unknown. */
        fun activeWindowPackage(): String?
    }

    /** What the caller must do after an input. */
    enum class Action {
        NOTHING,

        /** Start Rozan's home activity (6.5). */
        START_ROZAN,
    }

    /** The current mode and `stockHome` set; see [configure]. */
    var setup: HomeSetup = HomeSetup.NONE
        private set

    /** A redirect was sent and neither Rozan's home window nor any other activity has appeared since. */
    var awaitingRozan: Boolean = false
        private set

    /** Circuit breaker: Rozan crashed. Cleared only when Rozan's home window appears. */
    var tripped: Boolean = false
        private set

    /** The user chose the stock home; ends when an activity outside the stock launcher appears. */
    var userStay: Boolean = false
        private set

    /** The stock home is in front as a step towards something else (a known trampoline). */
    var handoverHold: Boolean = false
        private set

    /** The last activity that came to the front other than the stock home itself. */
    var lastActivity: ActivityId? = null
        private set

    // keysSinceLast, reduced to what the rules read.
    private var lastKey = NO_KEY
    private var sawInputKey = false
    private var sawProfileKey = false

    /** An `H` arrived since [lastActivity] came to the front. */
    private var homeSinceLast = false

    /** An `O` arrived since [lastActivity] came to the front: the stock home that follows is Rozan's picker. */
    private var pickerFromRozan = false

    /** Applies a new evaluation of what Home opens. The rest of the state is kept. */
    fun configure(setup: HomeSetup) {
        this.setup = setup
    }

    /** `W(pkg, cls)`. [contentChangeTypes] is the event's; non-zero means a pane, not a window root. */
    fun onWindowStateChanged(
        packageName: String,
        className: String,
        contentChangeTypes: Int,
        windows: Windows,
    ): Action {
        val window = ActivityId(packageName, className)
        when {
            // Step 1: only window roots of activities.
            contentChangeTypes != 0 || !isActivityWindow(window, windows) -> {
                return Action.NOTHING
            }

            // Step 2: Rozan's home is in front.
            window == rozanHome -> {
                awaitingRozan = false
                tripped = false
                cameToFront(window)
            }

            // Step 3: another activity came to the front; any redirect attempt is over.
            window !in setup.stockHome -> {
                awaitingRozan = false
                handoverHold = false
                if (packageName != setup.stockPackage) userStay = false
                cameToFront(window)
            }

            else -> {
                return onStockHome(windows)
            }
        }
        return Action.NOTHING
    }

    /** Steps 4 to 8: one of the exact stock home activities reported a window. */
    private fun onStockHome(windows: Windows): Action =
        when {
            // Step 4: the event is stale unless the stock launcher really is in front.
            windows.activeWindowPackage() != setup.stockPackage -> {
                Action.NOTHING
            }

            // Steps 5 and 6: one redirect per arrival of the stock home; none after a crash or by choice.
            awaitingRozan || tripped || userStay -> {
                Action.NOTHING
            }

            // Step 7: the stock home passed through on the way to something else.
            !homeSinceLast && isHandover() -> {
                handoverHold = true
                Action.NOTHING
            }

            // Step 8.
            else -> {
                awaitingRozan = true
                Action.START_ROZAN
            }
        }

    /** `H`: Home was pressed. Redirects when the stock launcher is already in front (no window event comes). */
    fun onHomePressed(windows: Windows): Action {
        handoverHold = false
        homeSinceLast = true
        val stock = setup.stockPackage ?: return Action.NOTHING
        if (tripped || userStay || awaitingRozan) return Action.NOTHING
        if (windows.activeWindowPackage() != stock) return Action.NOTHING
        awaitingRozan = true
        return Action.START_ROZAN
    }

    /** `K(code)`: a key observed (never consumed) since [lastActivity] came to the front. */
    fun onKeyObserved(keyCode: Int) {
        lastKey = keyCode
        if (keyCode == KEYCODE_TV_INPUT) sawInputKey = true
        if (keyCode == KEYCODE_PROFILE_SWITCH) sawProfileKey = true
    }

    /** `C`: Rozan's UI process crashed. No redirects until the user opens Rozan themselves. */
    fun onRozanCrashed() {
        tripped = true
        awaitingRozan = false
    }

    /** `S`: the user asked for the stock home screen. The caller starts it after this returns. */
    fun onStayOnStockHome() {
        userStay = true
    }

    /**
     * `O`: Rozan is about to open the TV's input picker. If the stock home
     * comes to the front before any other activity, it is that picker, drawn
     * inside the stock home (Fire OS 8 opens `NAVIGATE_TO_INPUTS` in
     * `HomeActivity_vNext`), so step 7 holds it like a trampoline's. Ends when
     * another activity comes to the front.
     */
    fun onRozanOpensPicker() {
        pickerFromRozan = true
    }

    /** No picker opened after `O`: the next arrival of the stock home is redirected as usual. */
    fun onRozanPickerFailed() {
        pickerFromRozan = false
    }

    private fun cameToFront(window: ActivityId) {
        lastActivity = window
        lastKey = NO_KEY
        sawInputKey = false
        sawProfileKey = false
        homeSinceLast = false
        pickerFromRozan = false
    }

    private fun isActivityWindow(
        window: ActivityId,
        windows: Windows,
    ): Boolean =
        when {
            window == rozanHome -> true

            // Rozan's other windows, the system UI and the framework's own windows never count.
            window.packageName == rozanHome.packageName || window.packageName in NOT_APPS -> false

            // The Fire OS 8 long-press panel is an overlay, not an activity, but it is a
            // trampoline (step 7), so its windows have to reach lastActivity.
            isQuickSettingsPanel(window) -> true

            else -> windows.isActivity(window)
        }

    private fun isHandover(): Boolean {
        val last = lastActivity
        if (sawInputKey || sawProfileKey || pickerFromRozan) return true
        if (last == null) return false
        if (isTrampoline(last)) return true
        // Back from a Settings page into the Settings tab Fire OS 7 draws inside its home.
        return lastKey == KEYCODE_BACK && (last.packageName == FIRE_SETTINGS || last.packageName == setup.stockPackage)
    }

    private companion object {
        const val NO_KEY = 0
        const val KEYCODE_BACK = 4 // android.view.KeyEvent.KEYCODE_BACK
        const val KEYCODE_TV_INPUT = 178 // android.view.KeyEvent.KEYCODE_TV_INPUT
        const val KEYCODE_PROFILE_SWITCH = 288 // android.view.KeyEvent.KEYCODE_PROFILE_SWITCH

        val NOT_APPS = setOf("android", "com.android.systemui")

        const val FIRE_SETTINGS = "com.amazon.tv.settings.v2"
        const val FIRE_LAUNCHER = "com.amazon.tv.launcher"
        const val QUICK_SETTINGS = "com.amazon.tv.quicksettings.ui"

        /** Screens that start the stock home themselves on the way to something else (6.4, step 7). */
        val TRAMPOLINES =
            setOf(
                ActivityId(FIRE_SETTINGS, "$FIRE_SETTINGS.hud.HudActivity"),
                ActivityId(FIRE_SETTINGS, "$FIRE_SETTINGS.compliance.CTSDummySettingsHandler"),
                ActivityId(
                    "com.amazon.tv.inputpreference.service",
                    "com.amazon.tv.inputpreference.service.StartAmazonInputActivity",
                ),
                ActivityId(FIRE_LAUNCHER, "$FIRE_LAUNCHER.navigation.NavigationActivity"),
                ActivityId(FIRE_LAUNCHER, "$FIRE_LAUNCHER.util.QuickSettingsProxyActivity"),
            )

        fun isQuickSettingsPanel(window: ActivityId): Boolean =
            window.packageName == QUICK_SETTINGS || window.className.startsWith("$QUICK_SETTINGS.")

        fun isTrampoline(activity: ActivityId): Boolean =
            activity in TRAMPOLINES ||
                isQuickSettingsPanel(activity) ||
                activity.packageName == "com.amazon.android.amazoninputswitch" ||
                (activity.packageName == "com.amazon.venezia" && "AppsGrid" in activity.className)
    }
}
