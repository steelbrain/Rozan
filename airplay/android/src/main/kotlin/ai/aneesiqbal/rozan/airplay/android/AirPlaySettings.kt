package ai.aneesiqbal.rozan.airplay.android

import ai.aneesiqbal.rozan.airplay.DeviceNames
import ai.aneesiqbal.rozan.airplay.android.store.AirPlayFiles
import ai.aneesiqbal.rozan.airplay.android.store.CachedProperties
import ai.aneesiqbal.rozan.airplay.android.store.openSharedProperties
import android.content.Context

/**
 * Persistent AirPlay receiver settings, shared by the launcher and the
 * standalone AirPlay app. Only configuration lives here: pairing state is never
 * persisted, every connection needs a fresh on-screen PIN.
 *
 * The receiver runs in its own `:airplay` process while settings screens run
 * in the app's main process, so the settings are a small file rather than
 * SharedPreferences (which caches per process and never sees the other
 * process's writes). Each process keeps the file in memory: reads never touch
 * the disk after the first, and the copy is re-read when the file watcher
 * reports that a process replaced the file. Writes are atomic and locked
 * across processes; from the main thread they reach the disk on a background
 * thread, from any other thread before the setter returns. Listeners hear
 * about changes made by any process once they are on disk.
 */
public class AirPlaySettings(
    context: Context,
) {
    private val appContext: Context = context.applicationContext
    private val store = store(appContext)
    private val listeners = HashMap<Listener, Runnable>()

    /** Whether the receiver runs and advertises itself on the network. */
    public var enabled: Boolean
        get() = readBoolean(KEY_ENABLED, default = true)
        set(value) = write(KEY_ENABLED, value.toString())

    /**
     * Name shown in the AirPlay menu on Apple devices. Generated on first use as
     * four random fruit names and stored, so it stays the same across restarts.
     */
    public var deviceName: String
        get() {
            store.get(KEY_DEVICE_NAME)?.let(DeviceNames::sanitize)?.let { return it }
            // First use: under the file lock, so two processes asking at once agree on one generated name.
            return store.getOrCreate(KEY_DEVICE_NAME, DeviceNames.random()).let(DeviceNames::sanitize)
                ?: DeviceNames.random()
        }
        set(value) {
            val sanitized = requireNotNull(DeviceNames.sanitize(value)) { "Receiver name must not be blank" }
            write(KEY_DEVICE_NAME, sanitized)
        }

    /**
     * Wake the device (and, through HDMI-CEC, the TV) when someone starts
     * connecting while the screen is off. Keeping the receiver reachable while
     * the screen is off needs a partial wake lock, a Wi-Fi lock and a multicast lock.
     */
    public var wakeOnConnect: Boolean
        get() = readBoolean(KEY_WAKE_ON_CONNECT, default = true)
        set(value) = write(KEY_WAKE_ON_CONNECT, value.toString())

    /**
     * Registers [listener]; it is called on the main thread after any setting
     * changes, in this process or another one.
     */
    public fun addListener(listener: Listener) {
        if (listeners.containsKey(listener)) return
        val wrapper = Runnable { listener.onAirPlaySettingsChanged() }
        listeners[listener] = wrapper
        AirPlayFiles.watch(appContext, AirPlayFiles.SETTINGS, wrapper)
    }

    /** Unregisters a listener added with [addListener]. */
    public fun removeListener(listener: Listener) {
        listeners.remove(listener)?.let { AirPlayFiles.unwatch(AirPlayFiles.SETTINGS, it) }
    }

    /**
     * Runs [task] once every setting written in this process so far is on disk, so that another
     * process started now reads them: at once when nothing is pending, else on the writer thread.
     */
    public fun afterSaved(task: Runnable): Unit = store.afterSaved(task)

    /** Observer of setting changes. */
    public fun interface Listener {
        /** Called after any AirPlay setting changes. */
        public fun onAirPlaySettingsChanged()
    }

    private fun readBoolean(
        key: String,
        default: Boolean,
    ): Boolean = store.get(key)?.toBooleanStrictOrNull() ?: default

    private fun write(
        key: String,
        value: String,
    ) = store.set(key, value)

    private companion object {
        const val TAG = "AirPlaySettings"
        const val KEY_ENABLED = "enabled"
        const val KEY_DEVICE_NAME = "device_name"
        const val KEY_WAKE_ON_CONNECT = "wake_on_connect"

        @Volatile private var shared: CachedProperties? = null

        /** The settings of this process: one in-memory copy for every instance. */
        fun store(appContext: Context): CachedProperties =
            shared ?: synchronized(this) {
                shared ?: openSharedProperties(appContext, AirPlayFiles.SETTINGS, "airplay-settings", TAG)
                    .also { shared = it }
            }
    }
}
