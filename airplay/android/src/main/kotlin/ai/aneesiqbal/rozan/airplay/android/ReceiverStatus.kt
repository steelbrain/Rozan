package ai.aneesiqbal.rozan.airplay.android

import ai.aneesiqbal.rozan.airplay.android.store.AirPlayFiles
import ai.aneesiqbal.rozan.airplay.android.store.PropertiesFile
import android.app.ActivityManager
import android.content.Context
import android.os.Process
import java.util.Properties

/**
 * Live state of the receiver for status screens. The receiver runs in the
 * app's `:airplay` process and publishes its state to a shared file; this
 * class reads it from any process and reports changes.
 */
public class ReceiverStatus(
    context: Context,
) {
    /** Whether Apple devices on the network can see the receiver. */
    public enum class Advertising {
        /** The receiver is not running. */
        OFF,

        /** The receiver is running and has not asked to be advertised yet. */
        NOT_PUBLISHED,

        /** No network connection; advertising resumes when one appears. */
        WAITING_FOR_NETWORK,

        /** Registration with the system's mDNS responder is in progress. */
        REGISTERING,

        /** All service records are registered. */
        ADVERTISING,

        /** The system refused a registration; retried on the next network change. */
        FAILED,
    }

    /** Why the receiver is not listening although the service runs. */
    public enum class Failure {
        /** Another app holds the control port ([Snapshot.port]); retried when an app starts the receiver. */
        PORT_IN_USE,

        /** The receiver could not start for another reason (see the log). */
        START_FAILED,
    }

    /** One consistent view of the receiver's state. */
    public data class Snapshot(
        /** Whether the receiver service is running. */
        val running: Boolean,
        /** Advertising state. */
        val advertising: Advertising,
        /** Name the receiver runs under, once running. */
        val receiverName: String?,
        /** This install's device id as `AA:BB:CC:DD:EE:FF`, once running. */
        val deviceId: String?,
        /** Why the receiver is not listening, or null when it is (or is not running at all). */
        val failure: Failure? = null,
        /** TCP port the receiver listens on, or tried to; 0 when unknown. */
        val port: Int = 0,
    )

    /** Observer of status changes. */
    public fun interface Listener {
        /** The status changed to [status]. Main thread. */
        public fun onReceiverStatusChanged(status: Snapshot)
    }

    private val appContext: Context = context.applicationContext
    private val file = PropertiesFile(AirPlayFiles.file(appContext, AirPlayFiles.STATUS))
    private val listeners = HashMap<Listener, Runnable>()

    /** The current status, read fresh. A receiver process that died counts as not running. */
    public val current: Snapshot
        get() {
            val properties = file.read()
            val stored = read(properties)
            val pid = properties.getProperty(KEY_PID)?.toIntOrNull()
            return if (stored.running && (pid == null || !isAlive(pid))) STOPPED else stored
        }

    /** Adds [listener]; it is called on the main thread whenever the status changes. */
    public fun addListener(listener: Listener) {
        if (listeners.containsKey(listener)) return
        val wrapper = Runnable { listener.onReceiverStatusChanged(current) }
        listeners[listener] = wrapper
        AirPlayFiles.watch(appContext, AirPlayFiles.STATUS, wrapper)
    }

    /** Removes a listener added with [addListener]. */
    public fun removeListener(listener: Listener) {
        listeners.remove(listener)?.let { AirPlayFiles.unwatch(AirPlayFiles.STATUS, it) }
    }

    private fun isAlive(pid: Int): Boolean {
        val processes = appContext.getSystemService(ActivityManager::class.java).runningAppProcesses ?: return false
        return processes.any { it.pid == pid }
    }

    internal companion object {
        private const val KEY_RUNNING = "running"
        private const val KEY_ADVERTISING = "advertising"
        private const val KEY_NAME = "name"
        private const val KEY_DEVICE_ID = "device_id"
        private const val KEY_PID = "pid"
        private const val KEY_FAILURE = "failure"
        private const val KEY_PORT = "port"

        private val STOPPED =
            Snapshot(running = false, advertising = Advertising.OFF, receiverName = null, deviceId = null)

        /** Applies [transform] to the stored status, from the receiver process. */
        fun update(
            context: Context,
            transform: (Snapshot) -> Snapshot,
        ) {
            PropertiesFile(AirPlayFiles.file(context, AirPlayFiles.STATUS)).update { properties ->
                val before = read(properties)
                val after = transform(before)
                val changed = after != before || properties.getProperty(KEY_PID) != Process.myPid().toString()
                if (changed) store(properties, after)
                changed
            }
        }

        private fun read(properties: Properties): Snapshot {
            val running = properties.getProperty(KEY_RUNNING)?.toBooleanStrictOrNull() ?: false
            if (!running) return STOPPED
            val advertising =
                Advertising.entries.firstOrNull { it.name == properties.getProperty(KEY_ADVERTISING) }
                    ?: Advertising.NOT_PUBLISHED
            val failure = Failure.entries.firstOrNull { it.name == properties.getProperty(KEY_FAILURE) }
            return Snapshot(
                running = true,
                advertising = advertising,
                receiverName = properties.getProperty(KEY_NAME),
                deviceId = properties.getProperty(KEY_DEVICE_ID),
                failure = failure,
                port = properties.getProperty(KEY_PORT)?.toIntOrNull() ?: 0,
            )
        }

        private fun store(
            properties: Properties,
            snapshot: Snapshot,
        ) {
            properties.clear()
            properties.setProperty(KEY_RUNNING, snapshot.running.toString())
            properties.setProperty(KEY_ADVERTISING, snapshot.advertising.name)
            snapshot.receiverName?.let { properties.setProperty(KEY_NAME, it) }
            snapshot.deviceId?.let { properties.setProperty(KEY_DEVICE_ID, it) }
            snapshot.failure?.let { properties.setProperty(KEY_FAILURE, it.name) }
            if (snapshot.port != 0) properties.setProperty(KEY_PORT, snapshot.port.toString())
            properties.setProperty(KEY_PID, Process.myPid().toString())
        }
    }
}
