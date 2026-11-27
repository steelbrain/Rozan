package ai.aneesiqbal.rozan.launcher.home

import ai.aneesiqbal.rozan.launcher.AirPlaySettingsActivity
import ai.aneesiqbal.rozan.launcher.LauncherSettingsActivity
import ai.aneesiqbal.rozan.launcher.SystemIntents
import ai.aneesiqbal.rozan.launcher.apps.AppEntry
import ai.aneesiqbal.rozan.ui.TileView
import android.app.Activity
import android.content.Intent

/** What the home screen's tiles and the app options panel do when used. */
class HomeActions(
    private val activity: Activity,
    private val options: AppOptionsPanel,
    private val startScreensaver: () -> Unit,
) : HomeTiles.Actions,
    AppOptionsPanel.Listener {
    override fun onBuiltInSelected(builtIn: BuiltIn) {
        when (builtIn) {
            BuiltIn.SETTINGS -> SystemIntents.startFirst(activity, SystemIntents.systemSettings(activity))
            BuiltIn.NETWORK -> SystemIntents.startFirst(activity, SystemIntents.networkSettings(activity))
            BuiltIn.AIRPLAY -> activity.startActivity(Intent(activity, AirPlaySettingsActivity::class.java))
            BuiltIn.SCREENSAVER -> startScreensaver()
        }
    }

    override fun onAppSelected(app: AppEntry) {
        SystemIntents.startFirst(activity, SystemIntents.launchApp(app))
    }

    override fun onAppOptionsRequested(
        tile: TileView,
        app: AppEntry,
    ) {
        options.show(tile, app)
    }

    override fun onAppInfo(app: AppEntry) {
        SystemIntents.startFirst(activity, SystemIntents.appDetails(app.packageName))
    }

    override fun onUninstall(app: AppEntry) {
        SystemIntents.startFirst(activity, SystemIntents.uninstall(app.packageName))
    }

    /** Opens launcher settings, optionally focused on [section]. */
    fun openLauncherSettings(section: String? = null) {
        val intent = Intent(activity, LauncherSettingsActivity::class.java)
        if (section != null) intent.putExtra(LauncherSettingsActivity.EXTRA_SECTION, section)
        activity.startActivity(intent)
    }
}
