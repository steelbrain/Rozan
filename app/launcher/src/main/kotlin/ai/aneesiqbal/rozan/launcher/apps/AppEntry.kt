package ai.aneesiqbal.rozan.launcher.apps

/** One launchable app on the home screen. */
data class AppEntry(
    val packageName: String,
    val activityName: String,
    val label: String,
    /** True when the activity was found through `CATEGORY_LEANBACK_LAUNCHER` (the TV entry point). */
    val leanback: Boolean,
    /** False for system apps without updates, which cannot be uninstalled. */
    val removable: Boolean,
    /** The package's version code when the entry was loaded, or 0 when it is not known. */
    val versionCode: Long = 0L,
    /** The package's last install or update time (epoch milliseconds), or 0 when it is not known. */
    val updatedAt: Long = 0L,
) {
    /** Stable identity of the activity, `package/class`. */
    val key: String = "$packageName/$activityName"
}
