package ai.aneesiqbal.rozan.launcher.update

import android.app.AppOpsManager
import android.content.Context
import android.os.Handler
import android.os.Looper
import java.util.concurrent.atomic.AtomicReference

/**
 * The "install unknown apps" permission (`REQUEST_INSTALL_PACKAGES` app-op),
 * which Rozan needs to install the updates it downloads. Like the overlay
 * permission it is granted from a computer with [adbCommand].
 */
internal object InstallPermission {
    /** The app-op's name for [AppOpsManager.startWatchingMode]; the platform has no public constant for it. */
    private const val OP_REQUEST_INSTALL_PACKAGES = "android:request_install_packages"

    /** Whether Rozan may install apps (its own updates). */
    fun isGranted(context: Context): Boolean = context.packageManager.canRequestPackageInstalls()

    /** The command that grants the permission over adb. */
    fun adbCommand(context: Context): String =
        "adb shell appops set ${context.packageName} REQUEST_INSTALL_PACKAGES allow"

    /**
     * Calls [onChanged] on the main thread whenever the permission changes,
     * e.g. right after the adb command runs. Returns a handle to stop watching.
     * Built like `OverlayPermission.watch`: nothing the system keeps reaches
     * the caller once closed.
     */
    fun watch(
        context: Context,
        onChanged: Runnable,
    ): AutoCloseable {
        val appOps = context.applicationContext.getSystemService(AppOpsManager::class.java)
        val packageName = context.packageName
        val main = Handler(Looper.getMainLooper())
        val target = AtomicReference(onChanged)
        val callback =
            AppOpsManager.OnOpChangedListener { _, changed ->
                if (changed == packageName) target.get()?.let(main::post)
            }
        appOps.startWatchingMode(OP_REQUEST_INSTALL_PACKAGES, packageName, callback)
        return AutoCloseable {
            target.set(null)
            appOps.stopWatchingMode(callback)
        }
    }
}
