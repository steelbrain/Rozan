package ai.aneesiqbal.rozan.launcher.apps

import java.text.Collator
import java.util.Locale

/** Builds the ordered app list from package manager query results. Pure logic, no Android APIs. */
object AppCatalog {
    /**
     * Merges TV (leanback) and phone-style (launcher) entry points into one
     * list: one entry per package, preferring the leanback activity, without
     * [hiddenPackages] (Rozan itself, and the settings app that the built-in
     * Settings tile already opens), sorted alphabetically by label for
     * [locale] (case-insensitive). Ties fall back to the package name so the
     * order is stable.
     */
    fun merge(
        leanback: List<AppEntry>,
        launcher: List<AppEntry>,
        hiddenPackages: Set<String>,
        locale: Locale,
    ): List<AppEntry> {
        val byPackage = LinkedHashMap<String, AppEntry>()
        for (entry in leanback) {
            if (entry.packageName !in hiddenPackages) byPackage.putIfAbsent(entry.packageName, entry)
        }
        for (entry in launcher) {
            if (entry.packageName !in hiddenPackages) byPackage.putIfAbsent(entry.packageName, entry)
        }
        return sortByLabel(byPackage.values.map(::withUsableLabel), locale)
    }

    /** Sorts [entries] by label with a locale-aware, case-insensitive collator. */
    fun sortByLabel(
        entries: Collection<AppEntry>,
        locale: Locale,
    ): List<AppEntry> {
        // SECONDARY strength ignores case but still orders accented letters
        // next to their base letter ("écran" sorts among the e's).
        val collator = Collator.getInstance(locale).apply { strength = Collator.SECONDARY }
        val keyed = entries.map { it to collator.getCollationKey(it.label) }
        return keyed
            .sortedWith { a, b ->
                val byLabel = a.second.compareTo(b.second)
                if (byLabel != 0) byLabel else a.first.packageName.compareTo(b.first.packageName)
            }.map { it.first }
    }

    private fun withUsableLabel(entry: AppEntry): AppEntry {
        val trimmed = entry.label.trim()
        return when {
            trimmed.isEmpty() -> entry.copy(label = entry.packageName)
            trimmed != entry.label -> entry.copy(label = trimmed)
            else -> entry
        }
    }
}
