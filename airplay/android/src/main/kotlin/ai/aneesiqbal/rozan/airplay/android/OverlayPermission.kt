package ai.aneesiqbal.rozan.airplay.android

import android.app.AppOpsManager
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import java.util.concurrent.atomic.AtomicReference

/**
 * The "draw over other apps" permission (`SYSTEM_ALERT_WINDOW` app-op). With
 * it the PIN appears over whatever app is in front and mirroring can take the
 * screen from other apps. Android TV and Fire TV often have no settings page
 * for it, so it is granted from a computer with [adbCommand].
 */
public object OverlayPermission {
    /** Whether this app may draw over other apps. */
    public fun isGranted(context: Context): Boolean = Settings.canDrawOverlays(context)

    /** The command that grants the permission over adb. */
    public fun adbCommand(context: Context): String =
        "adb shell appops set ${context.packageName} SYSTEM_ALERT_WINDOW allow"

    /**
     * Calls [onChanged] on the main thread whenever the permission changes,
     * e.g. right after the adb command runs. Returns a handle to stop watching.
     */
    public fun watch(
        context: Context,
        onChanged: Runnable,
    ): AutoCloseable {
        // The system service holds the binder stub behind the listener until its own GC runs, long
        // after stopWatchingMode. So nothing on that chain may reach the caller once closed: the
        // manager comes from the application context (one from an Activity holds the Activity),
        // and the listener reaches onChanged only through a reference that close() clears.
        val appOps = context.applicationContext.getSystemService(AppOpsManager::class.java)
        val packageName = context.packageName
        val main = Handler(Looper.getMainLooper())
        val target = AtomicReference(onChanged)
        val callback =
            AppOpsManager.OnOpChangedListener { _, changed ->
                if (changed == packageName) target.get()?.let(main::post)
            }
        appOps.startWatchingMode(AppOpsManager.OPSTR_SYSTEM_ALERT_WINDOW, packageName, callback)
        return AutoCloseable {
            target.set(null)
            appOps.stopWatchingMode(callback)
        }
    }
}
