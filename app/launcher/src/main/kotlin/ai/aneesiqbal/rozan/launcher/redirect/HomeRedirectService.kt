package ai.aneesiqbal.rozan.launcher.redirect

import ai.aneesiqbal.rozan.launcher.HomeActivity
import android.accessibilityservice.AccessibilityService
import android.content.ActivityNotFoundException
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import android.os.UserManager
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import androidx.core.content.ContextCompat
import androidx.tracing.trace

/**
 * Opens Rozan when the stock launcher's home screen comes up
 * (docs/research/home-button-override.md, section 6). It listens only to
 * `TYPE_WINDOW_STATE_CHANGED`, reads only the active window's package, never
 * filters or consumes keys, and never calls `GLOBAL_ACTION_HOME`. The rules
 * are in [HomeRedirector]; this class feeds them and starts Rozan.
 *
 * Runs in the `:redirect` process, so a crash of Rozan's UI cannot take it
 * down, and is direct-boot aware so the system binds it before the user is
 * unlocked; it does nothing until [Intent.ACTION_USER_UNLOCKED] (6.3).
 */
class HomeRedirectService :
    AccessibilityService(),
    HomeRedirector.Windows {
    private val receivers = ArrayList<BroadcastReceiver>()
    private val activityCache = HashMap<ActivityId, Boolean>()
    private var unlocked = false
    private var fireOs = false

    override fun onServiceConnected() {
        super.onServiceConnected()
        fireOs = HomeSetupReader.isFireOs(this)
        if (getSystemService(UserManager::class.java).isUserUnlocked) {
            onUnlocked()
        } else {
            register(IntentFilter(Intent.ACTION_USER_UNLOCKED)) { onUnlocked() }
        }
    }

    private fun onUnlocked() {
        if (unlocked) return
        unlocked = true
        evaluate()
        // Any package change can change what Home opens or which classes are activities.
        val packages =
            IntentFilter().apply {
                addAction(Intent.ACTION_PACKAGE_ADDED)
                addAction(Intent.ACTION_PACKAGE_CHANGED)
                addAction(Intent.ACTION_PACKAGE_REPLACED)
                addAction(Intent.ACTION_PACKAGE_REMOVED)
                addDataScheme("package")
            }
        register(packages) { intent ->
            val changed = intent.data?.schemeSpecificPart
            activityCache.keys.removeAll { it.packageName == changed }
            evaluate()
        }
        // H: AOSP broadcasts this with reason "homekey" on every Home press (A2). Fire OS 7 and 8
        // may not deliver it to apps (2.3); the rules work without it. The constant is deprecated
        // because apps may no longer send it (API 31); the system still does.
        @Suppress("DEPRECATION")
        val closeSystemDialogs = IntentFilter(Intent.ACTION_CLOSE_SYSTEM_DIALOGS)
        register(closeSystemDialogs) { intent ->
            if (intent.getStringExtra(EXTRA_REASON) == REASON_HOME_KEY) {
                act(RedirectRuntime.onHomePressed(this, this))
            }
        }
        RedirectRuntime.setReady(true)
        probeIfStockHomeIsShowing()
    }

    private fun evaluate() = RedirectRuntime.applySystemSetup(this, HomeSetupReader.read(this))

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        if (!unlocked || event.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return
        val packageName = event.packageName ?: return
        val className = event.className ?: return
        trace("Rozan.redirectEvent") {
            val window = ActivityId(packageName.toString(), className.toString())
            act(RedirectRuntime.onWindowStateChanged(this, window, event.contentChangeTypes, this))
        }
    }

    override fun onInterrupt() = Unit

    override fun onUnbind(intent: Intent?): Boolean {
        shutDown()
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        shutDown()
        super.onDestroy()
    }

    private fun shutDown() {
        RedirectRuntime.setReady(false)
        receivers.forEach(::unregisterReceiver)
        receivers.clear()
        unlocked = false
    }

    /** Step 1 of 6.4: whether the window's class is an activity. Cached per pair. */
    override fun isActivity(activity: ActivityId): Boolean =
        activityCache.getOrPut(activity) {
            try {
                val component = ComponentName(activity.packageName, activity.className)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    packageManager.getActivityInfo(component, PackageManager.ComponentInfoFlags.of(0L))
                } else {
                    // The int-flags overload is the only one before API 33; deprecated from 33 on.
                    @Suppress("DEPRECATION")
                    packageManager.getActivityInfo(component, 0)
                }
                true
            } catch (ignored: PackageManager.NameNotFoundException) {
                // Dialogs, popups and IME windows carry a class that is not an activity.
                false
            }
        }

    /** Step 4 of 6.4: the package of the active window's root, from `getWindows()`. */
    override fun activeWindowPackage(): String? {
        val all = windows
        val active = all.firstOrNull { it.isActive }
        val root = active?.root
        val packageName = root?.packageName?.toString()
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            // Pooled before API 33; recycling is a no-op from 33 on.
            @Suppress("DEPRECATION")
            root?.recycle()
            @Suppress("DEPRECATION")
            all.forEach { it.recycle() }
        }
        return packageName
    }

    private fun act(action: HomeRedirector.Action) {
        if (action == HomeRedirector.Action.START_ROZAN) startRozan()
    }

    /**
     * 6.5: an explicit start from the service's own context, never `GLOBAL_ACTION_HOME`. Without the
     * open animation: the system's cross-fade into Rozan would keep the stock home on screen for its
     * whole length.
     */
    private fun startRozan() {
        val intent =
            Intent(Intent.ACTION_MAIN)
                .setComponent(ComponentName(this, HomeActivity::class.java))
                .addFlags(
                    Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED or
                        Intent.FLAG_ACTIVITY_NO_ANIMATION,
                ).putExtra(HomeActivity.EXTRA_FROM_REDIRECT, true)
        // HOME as the only category makes Rozan's task a home task on Android 11+, so Back from
        // an app returns to Rozan. Fire OS only; untested on Google TV (6.5, A5).
        if (fireOs) intent.addCategory(Intent.CATEGORY_HOME)
        try {
            startActivity(intent)
        } catch (e: ActivityNotFoundException) {
            // Not retried: the next attempt needs the user to leave the stock home (6.4).
            Log.w(TAG, "Could not start Rozan", e)
        } catch (e: SecurityException) {
            Log.w(TAG, "Not allowed to start Rozan", e)
        }
    }

    /**
     * 6.7: after a (re)connect no window event comes for a window that is
     * already showing. If the stock launcher is in front, a transparent probe
     * activity takes focus and gives it back, and the window underneath then
     * reports its class like any other event. Rozan itself is never started
     * from here.
     */
    private fun probeIfStockHomeIsShowing() {
        val stockPackage = RedirectRuntime.stockPackage() ?: return
        if (activeWindowPackage() != stockPackage) return
        val probe =
            Intent(this, RedirectProbeActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION)
        try {
            startActivity(probe)
        } catch (e: ActivityNotFoundException) {
            Log.w(TAG, "Could not start the probe", e)
        }
    }

    private fun register(
        filter: IntentFilter,
        onReceive: (Intent) -> Unit,
    ) {
        val receiver =
            object : BroadcastReceiver() {
                override fun onReceive(
                    context: Context,
                    intent: Intent,
                ) = onReceive(intent)
            }
        // System broadcasts still reach a receiver that other apps cannot send to.
        ContextCompat.registerReceiver(this, receiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
        receivers += receiver
    }

    private companion object {
        const val TAG = "HomeRedirect"
        const val EXTRA_REASON = "reason"
        const val REASON_HOME_KEY = "homekey"
    }
}
