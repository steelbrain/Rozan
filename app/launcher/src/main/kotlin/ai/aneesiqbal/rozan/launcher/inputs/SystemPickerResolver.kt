package ai.aneesiqbal.rozan.launcher.inputs

import ai.aneesiqbal.rozan.launcher.SystemIntents
import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.content.pm.ResolveInfo
import android.os.Build

/**
 * [PickerResolver] over the system's package manager. The candidates'
 * packages and actions are in the manifest's `<queries>`; Rozan also holds
 * `QUERY_ALL_PACKAGES` for the Home button redirect. Each call is a binder
 * call: off the main thread.
 */
internal class SystemPickerResolver(
    private val context: Context,
) : PickerResolver {
    private val pm: PackageManager = context.packageManager

    /**
     * No `MATCH_DEFAULT_ONLY`: Rozan starts every picker by component, and
     * only an implicit start needs the default category.
     */
    override fun query(query: PickerQuery): List<PickerActivity> =
        queryActivities(SystemIntents.inputPicker(query), 0).map { pickerActivity(it.activityInfo) }

    override fun systemDefault(query: PickerQuery): PickerActivity? =
        resolveActivity(SystemIntents.inputPicker(query))?.activityInfo?.let(::pickerActivity)

    override fun isGranted(permission: String): Boolean =
        context.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED

    override fun homePackages(): Set<String> =
        queryActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME), PackageManager.MATCH_DEFAULT_ONLY)
            .mapTo(HashSet()) { it.activityInfo.packageName }

    private fun pickerActivity(info: ActivityInfo): PickerActivity =
        PickerActivity(
            packageName = info.packageName,
            className = info.name,
            exported = info.exported,
            // The package manager fills in the application's permission for an activity that names none.
            permission = info.permission,
            system = info.applicationInfo.flags and ApplicationInfo.FLAG_SYSTEM != 0,
        )

    private fun queryActivities(
        intent: Intent,
        flags: Int,
    ): List<ResolveInfo> =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            pm.queryIntentActivities(intent, PackageManager.ResolveInfoFlags.of(flags.toLong()))
        } else {
            // The int-flags overload is the only one before API 33; deprecated from 33 on.
            @Suppress("DEPRECATION")
            pm.queryIntentActivities(intent, flags)
        }

    /** What a plain `startActivity` would open, as it resolves it (default category). */
    private fun resolveActivity(intent: Intent): ResolveInfo? {
        val flags = PackageManager.MATCH_DEFAULT_ONLY
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            pm.resolveActivity(intent, PackageManager.ResolveInfoFlags.of(flags.toLong()))
        } else {
            // The int-flags overload is the only one before API 33; deprecated from 33 on.
            @Suppress("DEPRECATION")
            pm.resolveActivity(intent, flags)
        }
    }
}
