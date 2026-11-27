package ai.aneesiqbal.rozan.launcher.redirect

/** An activity by package and fully qualified class name, as accessibility events report it. */
data class ActivityId(
    val packageName: String,
    val className: String,
) {
    /** `package/class`, the form `adb` and `dumpsys` print. */
    override fun toString(): String = "$packageName/$className"
}

/** How the Home button reaches Rozan (docs/research/home-button-override.md, 6.1). */
enum class HomeMode {
    /** Rozan is what Home opens; the system delivers Home presses itself. */
    NATIVE,

    /** Another launcher is the home; the accessibility service redirects from it. */
    REDIRECT,

    /** Home opens nothing Rozan may redirect from (a chooser, setup, a fallback screen). */
    NONE,
}

/**
 * What Home opens on this device and, in [HomeMode.REDIRECT], the exact
 * activities that count as the stock home screen (`stockHome`, 6.2). Pure
 * logic: the package manager queries are done by the caller.
 */
data class HomeSetup(
    val mode: HomeMode,
    /** The stock launcher's package; null unless [mode] is [HomeMode.REDIRECT]. */
    val stockPackage: String?,
    /** The stock launcher's home activities. Only these are ever redirected from. */
    val stockHome: Set<ActivityId>,
    /** Members of [stockHome] missing from Rozan's list of known home screens, for diagnostics. */
    val unrecognized: Set<ActivityId>,
) {
    companion object {
        /** No redirect: the state before the first evaluation, and the answer for unusable homes. */
        val NONE = HomeSetup(HomeMode.NONE, null, emptySet(), emptySet())

        /**
         * Home screens named in the research (6.2). A stock home outside this
         * list is still used, but shows up in Rozan's diagnostics.
         */
        val KNOWN_STOCK_HOMES: Set<ActivityId> =
            setOf(
                ActivityId("com.amazon.tv.launcher", "com.amazon.tv.launcher.ui.HomeActivity_vNext"),
                ActivityId(
                    "com.google.android.apps.tv.launcherx",
                    "com.google.android.apps.tv.launcherx.home.HomeActivity",
                ),
                ActivityId("com.google.android.tvlauncher", "com.google.android.tvlauncher.MainActivity"),
                ActivityId("com.google.android.leanbacklauncher", "com.google.android.leanbacklauncher.MainActivity"),
            )

        /**
         * Packages that answer HOME but are never the home screen (6.2): the
         * system's chooser, the boot starter, setup, and Amazon Kids home,
         * which Rozan must not offer a way out of.
         */
        private val NEVER_STOCK_PACKAGES =
            setOf(
                "android",
                "com.amazon.firehomestarter",
                "com.amazon.tv.oobe",
                "com.google.android.tungsten.setupwraith",
                "com.amazon.tahoe",
            )

        /** `FallbackHome` (a blank screen shown until the user is unlocked) lives in each settings app. */
        private const val FALLBACK_HOME = "FallbackHome"

        /**
         * Decides the mode from [resolvedHome], what
         * `resolveActivity(MAIN/HOME, MATCH_DEFAULT_ONLY)` returned (alias
         * already resolved to its target class), and [homeActivitiesInPackage],
         * every activity of that package that declares MAIN/HOME (6.2 b).
         */
        fun decide(
            resolvedHome: ActivityId?,
            homeActivitiesInPackage: Collection<ActivityId>,
            rozanHome: ActivityId,
        ): HomeSetup {
            if (resolvedHome == null) return NONE
            if (resolvedHome == rozanHome) return HomeSetup(HomeMode.NATIVE, null, emptySet(), emptySet())
            if (!canBeStockHome(resolvedHome) || resolvedHome.packageName == rozanHome.packageName) return NONE
            val stockHome =
                (homeActivitiesInPackage + resolvedHome)
                    .filterTo(LinkedHashSet()) { it.packageName == resolvedHome.packageName && canBeStockHome(it) }
            return HomeSetup(HomeMode.REDIRECT, resolvedHome.packageName, stockHome, stockHome - KNOWN_STOCK_HOMES)
        }

        private fun canBeStockHome(activity: ActivityId): Boolean =
            activity.packageName !in NEVER_STOCK_PACKAGES &&
                activity.className.substringAfterLast('.') != FALLBACK_HOME
    }
}
