package ai.aneesiqbal.rozan.launcher.apps

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter

/**
 * Reports app installs, updates, removals and component changes while
 * registered (the home activity registers it for its whole lifetime).
 */
class PackageChangeReceiver(
    private val onPackageChanged: (packageName: String) -> Unit,
) : BroadcastReceiver() {
    fun register(context: Context) {
        val filter =
            IntentFilter().apply {
                addAction(Intent.ACTION_PACKAGE_ADDED)
                addAction(Intent.ACTION_PACKAGE_REMOVED)
                addAction(Intent.ACTION_PACKAGE_CHANGED)
                addDataScheme("package")
            }
        // Only the system sends these protected broadcasts, so no export flag is involved.
        context.registerReceiver(this, filter)
    }

    fun unregister(context: Context) {
        context.unregisterReceiver(this)
    }

    override fun onReceive(
        context: Context,
        intent: Intent,
    ) {
        // An update sends REMOVED(replacing) then ADDED(replacing); react to the ADDED only.
        if (intent.action == Intent.ACTION_PACKAGE_REMOVED && intent.getBooleanExtra(Intent.EXTRA_REPLACING, false)) {
            return
        }
        val packageName = intent.data?.schemeSpecificPart ?: return
        onPackageChanged(packageName)
    }
}
