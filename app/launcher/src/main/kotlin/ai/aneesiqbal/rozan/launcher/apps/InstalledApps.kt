package ai.aneesiqbal.rozan.launcher.apps

import ai.aneesiqbal.rozan.launcher.SystemIntents
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.content.pm.ResolveInfo
import android.os.Build
import androidx.tracing.trace
import java.io.File
import java.util.Locale

/** Queries the package manager for launchable apps. Blocking: call off the main thread. */
class InstalledApps(
    context: Context,
) {
    private val appContext = context.applicationContext
    private val pm: PackageManager = appContext.packageManager
    private val labels = AppLabelCache(File(appContext.cacheDir, LABEL_CACHE))

    /**
     * TV entry points plus phone-style launcher activities, one per package,
     * alphabetical. Rozan itself and the settings app behind the built-in
     * Settings tile are left out, so Settings is not listed twice.
     *
     * Labels come from [AppLabelCache] while the app is unchanged since it was
     * stored; only new or updated apps (and a new locale) have their resources
     * loaded. Activities that the list leaves out need no label at all.
     */
    fun load(): List<AppEntry> =
        trace("Rozan.loadApps") {
            val hidden = settingsPackages() + appContext.packageName
            val locale = Locale.getDefault()
            val languageTag = locale.toLanguageTag()
            val cached = labels.read(languageTag)
            val fresh = ArrayList<AppLabelCache.Entry>()
            var reused = 0
            val entries = ArrayList<AppEntry>()
            val seen = HashSet<String>()
            for (category in CATEGORIES) {
                val leanback = category == Intent.CATEGORY_LEANBACK_LAUNCHER
                for (info in query(category)) {
                    val activity = info.activityInfo
                    // The list keeps the first activity of each package, leanback ones first (as
                    // AppCatalog.merge does): the others are not listed and need no label.
                    if (activity.packageName in hidden || !seen.add(activity.packageName)) continue
                    val packageInfo = pm.packageInfoOrNull(activity.packageName)
                    val versionCode = packageInfo?.longVersionCode ?: 0L
                    val updatedAt = packageInfo?.lastUpdateTime ?: 0L
                    val key = "${activity.packageName}/${activity.name}"
                    val hit = storedLabel(cached[key], packageInfo)
                    val label = hit ?: trace("Rozan.loadLabel") { info.loadLabel(pm).toString() }
                    if (hit != null) reused++
                    if (packageInfo != null) fresh += AppLabelCache.Entry(key, versionCode, updatedAt, label)
                    entries +=
                        AppEntry(
                            packageName = activity.packageName,
                            activityName = activity.name,
                            label = label,
                            leanback = leanback,
                            removable = isRemovable(activity.applicationInfo.flags),
                            versionCode = versionCode,
                            updatedAt = updatedAt,
                        )
                }
            }
            // Rewritten unless every label came from the cache unchanged.
            if (reused != cached.size || fresh.size != cached.size) labels.write(languageTag, fresh)
            AppCatalog.merge(entries, emptyList(), hidden, locale)
        }

    /**
     * The package(s) the Settings tile opens: what its intents resolve to
     * (`com.android.tv.settings` on Android TV and Google TV, the Amazon
     * launcher on Fire OS). An ambiguous intent resolves to the system's
     * chooser in package `android`, which is not an app tile, so it is skipped.
     */
    private fun settingsPackages(): Set<String> =
        SystemIntents
            .systemSettings(appContext)
            .mapNotNull { resolve(it)?.activityInfo?.packageName }
            .filterTo(HashSet()) { it != CHOOSER_PACKAGE }

    private fun resolve(intent: Intent): ResolveInfo? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            pm.resolveActivity(intent, PackageManager.ResolveInfoFlags.of(PackageManager.MATCH_DEFAULT_ONLY.toLong()))
        } else {
            // The int-flags overload is the only one before API 33; deprecated from 33 on.
            @Suppress("DEPRECATION")
            pm.resolveActivity(intent, PackageManager.MATCH_DEFAULT_ONLY)
        }

    private fun query(category: String): List<ResolveInfo> {
        val intent = Intent(Intent.ACTION_MAIN).addCategory(category)
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            pm.queryIntentActivities(intent, PackageManager.ResolveInfoFlags.of(0L))
        } else {
            // The int-flags overload is the only one before API 33; deprecated from 33 on.
            @Suppress("DEPRECATION")
            pm.queryIntentActivities(intent, 0)
        }
    }

    private companion object {
        const val CHOOSER_PACKAGE = "android"
        const val LABEL_CACHE = "apps/labels"
        val CATEGORIES = arrayOf(Intent.CATEGORY_LEANBACK_LAUNCHER, Intent.CATEGORY_LAUNCHER)

        /** The label in [stored] while the package still has its version stamps ([current]), else null. */
        fun storedLabel(
            stored: AppLabelCache.Entry?,
            current: PackageInfo?,
        ): String? =
            stored?.label?.takeIf {
                current != null && stored.versionCode == current.longVersionCode &&
                    stored.updatedAt == current.lastUpdateTime
            }

        /** Whether an app with these [ApplicationInfo.flags] can be uninstalled: not a system app without updates. */
        fun isRemovable(flags: Int): Boolean =
            flags and ApplicationInfo.FLAG_SYSTEM == 0 || flags and ApplicationInfo.FLAG_UPDATED_SYSTEM_APP != 0
    }
}

/** The package's info (version code, last update time), or null when it is not installed. */
internal fun PackageManager.packageInfoOrNull(packageName: String): PackageInfo? =
    try {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            getPackageInfo(packageName, PackageManager.PackageInfoFlags.of(0L))
        } else {
            // The int-flags overload is the only one before API 33; deprecated from 33 on.
            @Suppress("DEPRECATION")
            getPackageInfo(packageName, 0)
        }
    } catch (ignored: PackageManager.NameNotFoundException) {
        // Removed since it was listed.
        null
    }
