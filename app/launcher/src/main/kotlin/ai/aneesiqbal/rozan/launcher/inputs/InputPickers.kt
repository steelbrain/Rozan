package ai.aneesiqbal.rozan.launcher.inputs

/**
 * One way to reach a system input picker: an action, a package, a class, or
 * a mix. A query with a class names one activity; one without asks the
 * package manager which activities answer the action.
 */
internal data class PickerQuery(
    val action: String?,
    val packageName: String? = null,
    val className: String? = null,
)

/** An activity that answers a [PickerQuery], as the package manager describes it. */
internal data class PickerActivity(
    val packageName: String,
    val className: String,
    val exported: Boolean,
    /** The permission a caller needs to start it (the app's, if the activity names none), or null. */
    val permission: String?,
    /** Part of the system image (`ApplicationInfo.FLAG_SYSTEM`, which updated system apps keep). */
    val system: Boolean,
)

/** A system input picker that Rozan may start: [action] (none for a bare component) sent to one activity. */
internal data class PickerTarget(
    val action: String?,
    val packageName: String,
    val className: String,
)

/** What the package manager says about the picker candidates. [SystemPickerResolver] asks the real one. */
internal interface PickerResolver {
    /** The activities that answer [query] and are enabled. */
    fun query(query: PickerQuery): List<PickerActivity>

    /**
     * The activity a plain start of [query] would open without asking, or
     * null. Where several activities answer and none is preferred, the system
     * would show its chooser; that is not one of [query]'s activities.
     */
    fun systemDefault(query: PickerQuery): PickerActivity?

    /** Whether Rozan holds [permission]. */
    fun isGranted(permission: String): Boolean

    /** The packages with a Home activity: the stock home's picker wins a tie. */
    fun homePackages(): Set<String>
}

/**
 * Finds the TV's own input pickers that Rozan may open, best first
 * (docs/research/tv-inputs.md, section 9.2). Pure, so the rules are tested on
 * the JVM.
 *
 * - The candidates are tried in [CANDIDATES] order: the AOSP Live TV action,
 *   which vendor pickers and the classic Android TV home also answer, then
 *   Fire TV Edition's inputs screen, then the Fire TV Cube's input chooser.
 * - Only activities of system apps count: the button opens the TV's own
 *   picker, never an app that claims the action.
 * - An activity counts only if it is exported and needs no permission, or one
 *   that Rozan holds.
 * - Every target is started by component, so the system's chooser never
 *   appears. Where several system activities answer one action, the one a
 *   plain start would open comes first, then the stock home's, then the rest
 *   in the package manager's order.
 * - TvSettings' Inputs screen (`com.android.tv.settings/.system.InputsActivity`)
 *   is left out: it renames and hides inputs but cannot switch to one
 *   (research doc 7.2).
 */
internal object InputPickers {
    /** The AOSP Live TV app's picker action (research doc 9.2); not a public SDK constant. */
    const val ACTION_VIEW_INPUTS = "com.android.tv.action.VIEW_INPUTS"

    /** Fire TV Edition's inputs screen in the Fire TV home (research doc 9.2, UNCONFIRMED). */
    const val ACTION_FIRE_NAVIGATE_TO_INPUTS = "com.amazon.tv.action.NAVIGATE_TO_INPUTS"
    const val FIRE_LAUNCHER = "com.amazon.tv.launcher"

    /** The Fire TV Cube's input chooser (research doc 8.5, UNCONFIRMED), started like `am start -n`. */
    const val FIRE_INPUT_SERVICE = "com.amazon.tv.inputpreference.service"
    const val FIRE_INPUT_CHOOSER = "com.amazon.tv.inputpreference.player.InputChooserActivity"

    val CANDIDATES: List<PickerQuery> =
        listOf(
            PickerQuery(ACTION_VIEW_INPUTS),
            PickerQuery(ACTION_FIRE_NAVIGATE_TO_INPUTS, packageName = FIRE_LAUNCHER),
            PickerQuery(action = null, packageName = FIRE_INPUT_SERVICE, className = FIRE_INPUT_CHOOSER),
        )

    /** Every picker Rozan may start, best first; empty if there is none. */
    fun find(resolver: PickerResolver): List<PickerTarget> = CANDIDATES.flatMap { query -> targets(resolver, query) }

    private fun targets(
        resolver: PickerResolver,
        query: PickerQuery,
    ): List<PickerTarget> {
        val usable = resolver.query(query).filter { usable(resolver, it) }
        val ordered =
            if (usable.size < 2) {
                usable
            } else {
                val default = resolver.systemDefault(query)?.let { it.packageName to it.className }
                val homes = resolver.homePackages()
                // sortedBy is stable: within a rank, the package manager's order stays.
                usable.sortedBy {
                    when {
                        (it.packageName to it.className) == default -> 0
                        it.packageName in homes -> 1
                        else -> 2
                    }
                }
            }
        return ordered.map { PickerTarget(query.action, it.packageName, it.className) }
    }

    private fun usable(
        resolver: PickerResolver,
        activity: PickerActivity,
    ): Boolean {
        if (!activity.system || !activity.exported) return false
        val permission = activity.permission ?: return true
        return resolver.isGranted(permission)
    }
}

/** What the Inputs button does, from what the home screen found. */
internal enum class InputsButtonMode {
    /** Opens Rozan's own list, which switches inputs itself. */
    MENU,

    /** Opens the TV's own input picker: Fire OS, where Rozan cannot switch the inputs. */
    PICKER,

    /** Neither works: no button. */
    HIDDEN,
    ;

    companion object {
        /**
         * Rozan's list when it has inputs to switch to ([entries], always empty
         * on Fire OS); else, on Fire OS ([fireOs]), the TV's picker when one may
         * be opened ([pickers]); else no button.
         *
         * Off Fire OS, the pickers (AOSP Live TV's, the classic home's panel)
         * read the same TvInputManager list as Rozan, so where Rozan finds no
         * passthrough input they are empty too: on the emulator the panel is
         * empty and Live TV's picker closes at once (docs/research/tv-inputs.md,
         * 10.1). There the button stays hidden, as on the Shield and other
         * boxes, and the pickers are offered only from the menu, after a failed
         * switch. Dropping `fireOs &&` below would offer them on every device,
         * should a box turn out to have a picker that switches the TV over CEC.
         */
        fun of(
            entries: List<InputEntry>,
            pickers: List<PickerTarget>,
            fireOs: Boolean,
        ): InputsButtonMode =
            when {
                entries.isNotEmpty() -> MENU
                fireOs && pickers.isNotEmpty() -> PICKER
                else -> HIDDEN
            }
    }
}

/**
 * The pickers found by the last search, minus those that failed to start
 * since the apps on the device last changed. A picker that resolves can still
 * refuse to start (a permission check the package manager does not show, an
 * app that went away); it is not offered again until an app is installed,
 * updated, removed, enabled or disabled. Main thread only.
 */
internal class SystemPickers {
    private var found: List<PickerTarget> = emptyList()
    private val failed = HashSet<PickerTarget>()

    /** The pickers to offer, best first. */
    val targets: List<PickerTarget> get() = found.filterNot { it in failed }

    /** Replaces the search result. */
    fun update(found: List<PickerTarget>) {
        this.found = found
    }

    /** The apps changed: a picker that failed may start now. */
    fun forgetFailures() {
        failed.clear()
    }

    /**
     * Starts the first picker that [start] accepts and returns true; a picker
     * that it refuses is remembered and skipped from then on. False if none
     * started, which leaves [targets] empty.
     */
    fun open(start: (PickerTarget) -> Boolean): Boolean {
        for (target in targets) {
            if (start(target)) return true
            failed += target
        }
        return false
    }
}
