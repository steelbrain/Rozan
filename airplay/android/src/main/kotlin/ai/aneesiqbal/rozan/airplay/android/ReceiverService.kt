package ai.aneesiqbal.rozan.airplay.android

import ai.aneesiqbal.rozan.airplay.android.identity.IdentityStore
import ai.aneesiqbal.rozan.airplay.android.identity.ReceiverIdentity
import ai.aneesiqbal.rozan.airplay.android.session.DisplayFit
import ai.aneesiqbal.rozan.airplay.android.session.ReachabilityLocks
import ai.aneesiqbal.rozan.airplay.android.session.SessionEnder
import ai.aneesiqbal.rozan.airplay.android.session.SessionState
import ai.aneesiqbal.rozan.airplay.android.store.AirPlayFiles
import ai.aneesiqbal.rozan.airplay.receiver.AirPlayReceiver
import ai.aneesiqbal.rozan.airplay.receiver.DisplayInfo
import ai.aneesiqbal.rozan.airplay.receiver.ReceiverConfig
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.hardware.display.DisplayManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.util.Log
import android.view.Display
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import java.io.IOException
import java.net.BindException

/**
 * Foreground service that runs the AirPlay receiver, in the app's `:airplay`
 * process so a receiver crash never takes the launcher's process with it.
 *
 * It owns one `AirPlayReceiver`, built from [AirPlaySettings], this install's
 * identity and the display, with FairPlay from `:airplay:fairplay` and its
 * diagnostics in Logcat, and restarts it when the name changes. If the
 * receiver cannot start (the control port is taken by another app), the
 * service keeps running, reports why in [ReceiverStatus], and tries again
 * whenever an app calls [start]. While "wake on connect" is on it holds the
 * locks that keep the device reachable with the screen off. It stops itself
 * when the receiver is disabled.
 */
public class ReceiverService :
    Service(),
    AirPlaySettings.Listener,
    SessionState.Listener {
    private lateinit var runtime: AirPlayRuntime
    private lateinit var locks: ReachabilityLocks
    private lateinit var control: Handler
    private val controlThread = HandlerThread("airplay-control")

    // Main thread.
    private var appliedName: String? = null
    private var mediaForeground = false
    private var publishedInUse = false
    private var languageWatch: AutoCloseable? = null

    // Control thread.
    private var receiver: AirPlayReceiver? = null
    private var identity: ReceiverIdentity? = null

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(AppLanguage.wrap(newBase))
    }

    override fun onCreate() {
        super.onCreate()
        runtime = AirPlayRuntime.get(this)
        locks = ReachabilityLocks(this)
        createChannel()
        if (!enterForeground(media = false, name = runtime.settings.deviceName)) {
            stopSelf()
            return
        }
        // The notification and its channel are text in the app language: shown again in a new one.
        languageWatch =
            AppLanguage.watch(this) {
                createChannel()
                enterForeground(mediaForeground, appliedName ?: runtime.settings.deviceName)
            }
        controlThread.start()
        control = Handler(controlThread.looper)
        runtime.settings.addListener(this)
        runtime.session.addListener(this)
        applySettings()
    }

    override fun onStartCommand(
        intent: Intent?,
        flags: Int,
        startId: Int,
    ): Int {
        if (::control.isInitialized) applySettings()
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        languageWatch?.close()
        languageWatch = null
        if (::control.isInitialized) {
            runtime.settings.removeListener(this)
            runtime.session.removeListener(this)
            // After any receiver (re)start still queued, so "stopped" is the last word in the status file.
            control.post {
                stopReceiver()
                ReceiverStatus.update(this) { it.copy(running = false) }
            }
            controlThread.quitSafely()
        } else {
            ReceiverStatus.update(this) { it.copy(running = false) }
        }
        locks.release()
        runtime.wake.releaseScreen()
        super.onDestroy()
    }

    override fun onAirPlaySettingsChanged(): Unit = applySettings()

    override fun onSessionChanged() {
        val media = runtime.session.isMirroring || runtime.session.audioClient != null
        if (media != mediaForeground) enterForeground(media, appliedName ?: runtime.settings.deviceName)
        // Other processes (the launcher's updater) read this from the status file; written off the main thread.
        val inUse = media || runtime.session.pinPrompt != null
        if (inUse != publishedInUse) {
            publishedInUse = inUse
            control.post { ReceiverStatus.update(this) { if (it.running) it.copy(inUse = inUse) else it } }
        }
    }

    private fun applySettings() {
        val settings = runtime.settings
        if (!settings.enabled) {
            stopSelf()
            return
        }
        if (settings.wakeOnConnect) locks.hold() else locks.release()
        val name = settings.deviceName
        if (name == appliedName) {
            // Same name: start again only if the last start failed (port taken, now maybe free).
            control.post { if (receiver == null) restartReceiver(name) }
            return
        }
        appliedName = name
        enterForeground(mediaForeground, name)
        control.post { restartReceiver(name) }
    }

    /**
     * Control thread: replaces the receiver with one running under [name].
     * Whatever the receiver throws while starting, the service survives and reports it.
     */
    @Suppress("TooGenericExceptionCaught")
    private fun restartReceiver(name: String) {
        stopReceiver()
        val id = identity ?: IdentityStore(AirPlayFiles.file(this, AirPlayFiles.IDENTITY)).loadOrCreate()
        identity = id
        val config = ReceiverConfig(name, id.deviceId, id.identitySeed, currentDisplay())
        val next = AirPlayReceiver(config, runtime.platform, FairPlayAdapter.FACTORY, LogcatReceiverLog)
        ReceiverStatus.update(this) {
            ReceiverStatus.Snapshot(
                running = true,
                advertising = if (it.running) it.advertising else ReceiverStatus.Advertising.NOT_PUBLISHED,
                receiverName = name,
                deviceId = id.deviceIdText(),
                port = config.controlPort,
                inUse = it.inUse,
            )
        }
        val failure =
            try {
                next.start()
                null
            } catch (e: BindException) {
                // Expected while another app holds the port; one line, no stack trace.
                Log.w(TAG, "control port ${config.controlPort} is in use (${e.message})")
                ReceiverStatus.Failure.PORT_IN_USE
            } catch (e: IOException) {
                Log.e(TAG, "receiver failed to start", e)
                ReceiverStatus.Failure.START_FAILED
            } catch (e: RuntimeException) {
                Log.e(TAG, "receiver failed to start", e)
                ReceiverStatus.Failure.START_FAILED
            }
        if (failure != null) {
            ReceiverStatus.update(this) { it.copy(failure = failure) }
            return
        }
        receiver = next
        // Back on the mirroring screen ends every session. disconnectAll blocks until the stopped
        // events are out, so it runs here on the control thread, never on the main thread.
        runtime.sessionEnder = SessionEnder { control.post { receiver?.disconnectAll() } }
    }

    private fun stopReceiver() {
        receiver?.stop()
        receiver = null
        runtime.sessionEnder = SessionEnder.NONE
    }

    private fun currentDisplay(): DisplayInfo {
        val display = getSystemService(DisplayManager::class.java).getDisplay(Display.DEFAULT_DISPLAY)
        val mode = display?.mode
        val offered = DisplayFit.fit(mode?.physicalWidth ?: 0, mode?.physicalHeight ?: 0, mode?.refreshRate ?: 0f)
        // Logged for device checks (docs/INTEROP.md): the output mode, and the stream size senders are offered.
        Log.i(
            TAG,
            "display mode ${mode?.physicalWidth}x${mode?.physicalHeight} at ${mode?.refreshRate} Hz; " +
                "offering ${offered.widthPixels}x${offered.heightPixels} at ${offered.refreshRateHz} Hz",
        )
        return offered
    }

    private fun createChannel() {
        // Creating an existing channel again only renames it.
        val title = AppLanguage.wrap(this).getString(R.string.airplay_notification_channel)
        val channel = NotificationChannel(CHANNEL_ID, title, NotificationManager.IMPORTANCE_LOW)
        channel.setShowBadge(false)
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    /**
     * Enters (or updates) the foreground state. `mediaPlayback` is added only
     * while something plays: since Android 15 a service started at boot may not
     * use that type, and outside a session the receiver is a connected-device
     * service only.
     */
    private fun enterForeground(
        media: Boolean,
        name: String,
    ): Boolean {
        val types =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE or
                    (if (media) ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK else 0)
            } else {
                0
            }
        return try {
            ServiceCompat.startForeground(this, NOTIFICATION_ID, notification(name), types)
            mediaForeground = media
            true
        } catch (e: IllegalStateException) {
            // ForegroundServiceStartNotAllowedException (API 31+) is an IllegalStateException.
            Log.w(TAG, "cannot enter the foreground (media=$media)", e)
            false
        } catch (e: SecurityException) {
            Log.w(TAG, "cannot enter the foreground (media=$media)", e)
            false
        }
    }

    private fun notification(name: String): Notification {
        val launch =
            packageManager.getLeanbackLaunchIntentForPackage(packageName)
                ?: packageManager.getLaunchIntentForPackage(packageName)
        val content =
            launch?.let {
                val flags = PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
                PendingIntent.getActivity(this, 0, it, flags)
            }
        // This service lives longer than one language: the text comes from a context in the current one.
        val text = AppLanguage.wrap(this)
        return Notification
            .Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.airplay_ic_notification)
            .setContentTitle(text.getString(R.string.airplay_notification_title))
            .setContentText(text.getString(R.string.airplay_notification_text, name))
            .setCategory(Notification.CATEGORY_SERVICE)
            .setOngoing(true)
            .setContentIntent(content)
            .build()
    }

    /** Starting the receiver from the apps. */
    public companion object {
        private const val TAG = "AirPlayService"
        private const val CHANNEL_ID = "airplay_receiver"
        private const val NOTIFICATION_ID = 0x41504C59

        /**
         * Starts the receiver service if the receiver is enabled in
         * [AirPlaySettings]. Call it when an app's screen comes up and after the
         * user enables the receiver; calling it again is harmless.
         */
        public fun start(context: Context) {
            val settings = AirPlaySettings(context)
            if (!settings.enabled) return
            val appContext = context.applicationContext
            // Once the settings written in this process are on disk: the service reads them in its own.
            settings.afterSaved {
                try {
                    ContextCompat.startForegroundService(appContext, Intent(appContext, ReceiverService::class.java))
                } catch (e: IllegalStateException) {
                    // Not allowed from the background on API 31+; the next foreground call or boot starts it.
                    Log.w(TAG, "cannot start the receiver now", e)
                }
            }
        }
    }
}
