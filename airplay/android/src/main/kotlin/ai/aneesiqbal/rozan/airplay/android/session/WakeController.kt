package ai.aneesiqbal.rozan.airplay.android.session

import ai.aneesiqbal.rozan.airplay.android.AirPlaySettings
import android.annotation.SuppressLint
import android.content.Context
import android.net.wifi.WifiManager
import android.os.Build
import android.os.PowerManager

/**
 * Wakes the screen when a sender asks for a PIN while it is off ("wake on
 * connect"). With HDMI-CEC the TV follows the player's screen state, so this
 * turns the TV on for the PIN. Only a PIN wakes it: connections alone do not,
 * since any host on the network can open one and Apple senders probe
 * receivers whenever their AirPlay menu is open. Main thread only.
 */
internal class WakeController(
    context: Context,
    private val settings: AirPlaySettings,
) {
    private val power: PowerManager = context.getSystemService(PowerManager::class.java)
    private var screenLock: PowerManager.WakeLock? = null

    /**
     * A PIN is about to be shown: wake the screen if it is off and the setting is on. Returns true
     * when the screen is to be woken, so the PIN goes on an activity that turns the screen on: the
     * wake lock below does not wake every device (the API 31 Android TV image keeps sleeping with
     * it held), while an activity with `setTurnScreenOn` does.
     */
    fun wakeForPin(): Boolean {
        if (!settings.wakeOnConnect || power.isInteractive) return false
        if (screenLock?.isHeld == true) return true
        // SCREEN_BRIGHT_WAKE_LOCK and ACQUIRE_CAUSES_WAKEUP are deprecated in favour of the window
        // flags of an activity. The PIN activity has them; this lock also covers the case where that
        // activity cannot be started (no overlay permission, so no background activity start).
        @Suppress("DEPRECATION")
        val flags = PowerManager.SCREEN_BRIGHT_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP
        val lock = power.newWakeLock(flags, "rozan:airplay-connect")
        lock.setReferenceCounted(false)
        // Released as soon as the PIN prompt ends or mirroring starts; the cap only bounds a session
        // that never gets that far.
        lock.acquire(SCREEN_WAKE_CAP_MS)
        screenLock = lock
        return true
    }

    /** The PIN prompt ended or mirroring took over the screen. */
    fun releaseScreen() {
        screenLock?.let { if (it.isHeld) it.release() }
        screenLock = null
    }

    private companion object {
        const val SCREEN_WAKE_CAP_MS = 2 * 60 * 1000L
    }
}

/**
 * Keeps the receiver reachable while the screen is off: a partial wake lock
 * (CPU), a Wi-Fi lock that keeps the radio out of power save, and a multicast
 * lock so mDNS queries reach the device. Held while "wake on connect" is on.
 */
internal class ReachabilityLocks(
    context: Context,
) {
    private val partial: PowerManager.WakeLock =
        context
            .getSystemService(PowerManager::class.java)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "rozan:airplay-reachable")
            .apply { setReferenceCounted(false) }
    private val wifiManager: WifiManager = context.getSystemService(WifiManager::class.java)
    private val wifi: WifiManager.WifiLock =
        wifiManager.createWifiLock(wifiLockMode(), "rozan:airplay-reachable").apply { setReferenceCounted(false) }
    private val multicast: WifiManager.MulticastLock =
        wifiManager.createMulticastLock("rozan:airplay-reachable").apply { setReferenceCounted(false) }

    /** Whether the locks are held. */
    val isHeld: Boolean get() = partial.isHeld

    // Held for as long as the user keeps "wake on connect" on; there is no end time to give.
    @SuppressLint("WakelockTimeout")
    fun hold() {
        if (!partial.isHeld) partial.acquire()
        if (!wifi.isHeld) wifi.acquire()
        if (!multicast.isHeld) multicast.acquire()
    }

    fun release() {
        if (multicast.isHeld) multicast.release()
        if (wifi.isHeld) wifi.release()
        if (partial.isHeld) partial.release()
    }

    private companion object {
        fun wifiLockMode(): Int =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                WifiManager.WIFI_MODE_FULL_LOW_LATENCY
            } else {
                // Deprecated (and inert) only from API 34, which takes the branch above. Below that it
                // is the lock that keeps Wi-Fi out of power save while the screen is off.
                @Suppress("DEPRECATION")
                WifiManager.WIFI_MODE_FULL_HIGH_PERF
            }
    }
}
