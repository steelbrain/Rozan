package ai.aneesiqbal.rozan.launcher

import ai.aneesiqbal.rozan.launcher.apps.AppEntry
import ai.aneesiqbal.rozan.launcher.inputs.PickerQuery
import ai.aneesiqbal.rozan.launcher.inputs.PickerTarget
import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.media.tv.TvContract
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.util.Log

/**
 * Every intent the launcher sends to the system, in one place, so
 * device-specific variants (Fire OS, vendor settings apps) are added here
 * without touching the UI. Each action is a list of candidates tried in order
 * (see `docs/research/home-button-override.md`, sections 2.2, 6.9 and A8).
 *
 * Fire OS: `android.settings.SETTINGS` resolves to a stub that starts the
 * Amazon home, which opens Settings a second or two later; launchers then see
 * a Home press and loop. So on Fire OS Settings is always opened by its
 * explicit component, and the generic settings actions are never sent.
 */
object SystemIntents {
    private const val TAG = "SystemIntents"

    /** Fire OS Settings screen, which lives in the stock launcher package. */
    private val FIRE_SETTINGS =
        ComponentName("com.amazon.tv.launcher", "com.amazon.tv.launcher.ui.MainSettingsActivity")

    /** Fire OS network page; exported on Fire OS 6, unverified later, so it falls back to [FIRE_SETTINGS]. */
    private val FIRE_NETWORK =
        ComponentName("com.amazon.tv.settings.v2", "com.amazon.tv.settings.v2.tv.network.NetworkActivity")
    private const val FIRE_ACTION_ADD_NETWORK = "com.amazon.device.settings.action.ADD_NETWORK"

    /** The system settings app. */
    fun systemSettings(context: Context): List<Intent> {
        val fire = explicit(context, FIRE_SETTINGS, Intent.ACTION_MAIN)
        return if (fire != null) listOf(fire) else listOf(Intent(Settings.ACTION_SETTINGS))
    }

    /** Network settings, falling back to the settings home. */
    fun networkSettings(context: Context): List<Intent> {
        val fire = explicit(context, FIRE_SETTINGS, Intent.ACTION_MAIN)
        if (fire != null) return listOfNotNull(explicit(context, FIRE_NETWORK, FIRE_ACTION_ADD_NETWORK), fire)
        return listOf(Intent(Settings.ACTION_WIFI_SETTINGS), Intent(Settings.ACTION_SETTINGS))
    }

    /**
     * The system's accessibility settings, where the Home button service is
     * turned on. None on Fire OS: its accessibility page needs an Amazon
     * permission (research doc 2.2), and 8.1.5.5 and later do not list
     * third-party services there anyway (2.5).
     */
    fun accessibilitySettings(context: Context): List<Intent> =
        if (explicit(context, FIRE_SETTINGS, Intent.ACTION_MAIN) != null) {
            emptyList()
        } else {
            listOf(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }

    /**
     * The stock home screen: what the system's Home intent resolves to while
     * another launcher is the home. [standIn] replaces it in instrumented tests.
     */
    fun stockHome(standIn: ComponentName? = null): List<Intent> =
        listOf(
            if (standIn != null) {
                Intent(Intent.ACTION_MAIN).setComponent(standIn)
            } else {
                Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
            },
        )

    /** The system "App info" screen for [packageName]. */
    fun appDetails(packageName: String): List<Intent> =
        listOf(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, packageUri(packageName)))

    /** The system uninstall confirmation for [packageName]. */
    fun uninstall(packageName: String): List<Intent> = listOf(Intent(Intent.ACTION_DELETE, packageUri(packageName)))

    /** Opens [app] the way a launcher does: its main activity, in its own task. */
    fun launchApp(app: AppEntry): List<Intent> {
        val category = if (app.leanback) Intent.CATEGORY_LEANBACK_LAUNCHER else Intent.CATEGORY_LAUNCHER
        val intent =
            Intent(Intent.ACTION_MAIN)
                .addCategory(category)
                .setComponent(ComponentName(app.packageName, app.activityName))
                .addFlags(Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
        return listOf(intent)
    }

    /**
     * Switches the TV to a passthrough input (HDMI and the like): the
     * documented `ACTION_VIEW` of the input's passthrough URI, which the TV's
     * live TV app handles (docs/research/tv-inputs.md, section 6). Any app
     * may claim the URI's type, so the system apps that handle it are named
     * first; the plain intent comes last.
     */
    fun passthroughInput(
        context: Context,
        inputId: String,
    ): List<Intent> {
        val uri = TvContract.buildChannelUriForPassthroughInput(inputId)
        val view = Intent(Intent.ACTION_VIEW, uri)
        // The TV provider gives passthrough URIs the channel item type; the TV app matches on it.
        val typed = Intent(Intent.ACTION_VIEW).setDataAndType(uri, context.contentResolver.getType(uri))
        val system =
            query(context.packageManager, typed)
                .filter { it.exported && it.applicationInfo.flags and ApplicationInfo.FLAG_SYSTEM != 0 }
                .map { Intent(view).setComponent(ComponentName(it.packageName, it.name)) }
        return system + view
    }

    /**
     * A system input picker as an intent: by component where [query] names one,
     * else restricted to its package, if any (inputs/InputPickers).
     */
    internal fun inputPicker(query: PickerQuery): Intent {
        val intent = if (query.action != null) Intent(query.action) else Intent()
        val packageName = query.packageName ?: return intent
        val className = query.className ?: return intent.setPackage(packageName)
        return intent.setComponent(ComponentName(packageName, className))
    }

    /**
     * Opens [target], one of the TV's input pickers, by component, so the
     * system's chooser never appears (docs/research/tv-inputs.md, section 9.2).
     */
    internal fun inputPicker(target: PickerTarget): Intent =
        inputPicker(PickerQuery(target.action, target.packageName, target.className))

    /**
     * Starts the first candidate that the system accepts. Returns false if
     * none did. A candidate that does not resolve, or is not exported to us,
     * moves on to the next one.
     */
    fun startFirst(
        context: Context,
        candidates: List<Intent>,
    ): Boolean {
        for (intent in candidates) {
            try {
                context.startActivity(Intent(intent).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                return true
            } catch (e: ActivityNotFoundException) {
                Log.i(TAG, "No activity for $intent", e)
            } catch (e: SecurityException) {
                Log.w(TAG, "Not allowed to start $intent", e)
            }
        }
        return false
    }

    /** An intent for [component] if it exists on this device (needs a `<queries>` entry for its package). */
    private fun explicit(
        context: Context,
        component: ComponentName,
        action: String,
    ): Intent? {
        val intent = Intent(action).setComponent(component)
        val pm = context.packageManager
        val resolved =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                pm.resolveActivity(intent, PackageManager.ResolveInfoFlags.of(0L))
            } else {
                // The int-flags overload is the only one before API 33; deprecated from 33 on.
                @Suppress("DEPRECATION")
                pm.resolveActivity(intent, 0)
            }
        return if (resolved != null) intent else null
    }

    /** The activities that [intent] resolves to (default category, as `startActivity` adds it). */
    private fun query(
        pm: PackageManager,
        intent: Intent,
    ): List<ActivityInfo> {
        val flags = PackageManager.MATCH_DEFAULT_ONLY
        val found =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                pm.queryIntentActivities(intent, PackageManager.ResolveInfoFlags.of(flags.toLong()))
            } else {
                // The int-flags overload is the only one before API 33; deprecated from 33 on.
                @Suppress("DEPRECATION")
                pm.queryIntentActivities(intent, flags)
            }
        return found.map { it.activityInfo }
    }

    private fun packageUri(packageName: String): Uri = Uri.fromParts("package", packageName, null)
}
