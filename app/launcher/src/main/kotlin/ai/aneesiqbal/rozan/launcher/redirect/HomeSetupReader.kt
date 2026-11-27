package ai.aneesiqbal.rozan.launcher.redirect

import ai.aneesiqbal.rozan.launcher.HomeActivity
import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.content.pm.ResolveInfo
import android.os.Build

/** Asks the package manager what Home opens (docs/research/home-button-override.md, 6.1 and 6.2). */
object HomeSetupReader {
    /** System feature every Fire TV reports (research 2.0). */
    private const val FIRE_TV_FEATURE = "amazon.hardware.fire_tv"

    /** Rozan's home activity as accessibility events name it. */
    fun rozanHome(context: Context): ActivityId = ActivityId(context.packageName, HomeActivity::class.java.name)

    /** Whether this device runs Fire OS. */
    fun isFireOs(context: Context): Boolean = context.packageManager.hasSystemFeature(FIRE_TV_FEATURE)

    /** The current mode and `stockHome` set. Needs the `<queries>` entry for MAIN/HOME. */
    fun read(context: Context): HomeSetup {
        val pm = context.packageManager
        val homeIntent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
        val resolved = resolve(pm, homeIntent)?.activityInfo?.toActivityId()
        val inPackage =
            if (resolved == null) {
                emptyList()
            } else {
                query(pm, Intent(homeIntent).setPackage(resolved.packageName)).mapNotNull {
                    it.activityInfo?.toActivityId()
                }
            }
        return HomeSetup.decide(resolved, inPackage, rozanHome(context))
    }

    /** The real class behind an activity-alias, since events carry the class, not the alias (A6). */
    private fun ActivityInfo.toActivityId(): ActivityId = ActivityId(packageName, targetActivity ?: name)

    private fun resolve(
        pm: PackageManager,
        intent: Intent,
    ): ResolveInfo? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            pm.resolveActivity(intent, PackageManager.ResolveInfoFlags.of(PackageManager.MATCH_DEFAULT_ONLY.toLong()))
        } else {
            // The int-flags overload is the only one before API 33; deprecated from 33 on.
            @Suppress("DEPRECATION")
            pm.resolveActivity(intent, PackageManager.MATCH_DEFAULT_ONLY)
        }

    private fun query(
        pm: PackageManager,
        intent: Intent,
    ): List<ResolveInfo> =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            pm.queryIntentActivities(
                intent,
                PackageManager.ResolveInfoFlags.of(PackageManager.MATCH_DISABLED_COMPONENTS.toLong()),
            )
        } else {
            // The int-flags overload is the only one before API 33; deprecated from 33 on.
            @Suppress("DEPRECATION")
            pm.queryIntentActivities(intent, PackageManager.MATCH_DISABLED_COMPONENTS)
        }
}
