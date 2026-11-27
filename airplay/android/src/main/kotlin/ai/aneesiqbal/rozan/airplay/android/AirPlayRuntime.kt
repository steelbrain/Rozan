package ai.aneesiqbal.rozan.airplay.android

import ai.aneesiqbal.rozan.airplay.android.audio.AacAudioSink
import ai.aneesiqbal.rozan.airplay.android.audio.AlacAudioSink
import ai.aneesiqbal.rozan.airplay.android.audio.AudioTrackPcmSink
import ai.aneesiqbal.rozan.airplay.android.audio.DecodingAudioSink
import ai.aneesiqbal.rozan.airplay.android.net.NsdPublisher
import ai.aneesiqbal.rozan.airplay.android.session.SessionEnder
import ai.aneesiqbal.rozan.airplay.android.session.SessionState
import ai.aneesiqbal.rozan.airplay.android.session.WakeController
import ai.aneesiqbal.rozan.airplay.android.ui.PinPresenter
import ai.aneesiqbal.rozan.airplay.android.ui.SessionActivities
import ai.aneesiqbal.rozan.airplay.android.video.MediaCodecVideoSink
import ai.aneesiqbal.rozan.airplay.android.video.SurfaceBridge
import ai.aneesiqbal.rozan.airplay.receiver.AudioFormat
import ai.aneesiqbal.rozan.airplay.receiver.AudioSink
import ai.aneesiqbal.rozan.airplay.receiver.ClientInfo
import ai.aneesiqbal.rozan.airplay.receiver.NowPlaying
import ai.aneesiqbal.rozan.airplay.receiver.PinResult
import ai.aneesiqbal.rozan.airplay.receiver.ReceiverEvents
import ai.aneesiqbal.rozan.airplay.receiver.ReceiverPlatform
import ai.aneesiqbal.rozan.airplay.receiver.ServiceRecord
import ai.aneesiqbal.rozan.airplay.receiver.VideoSink
import android.annotation.SuppressLint
import android.content.Context
import android.os.Handler
import android.os.Looper

/**
 * Everything the receiver shares inside the `:airplay` process: settings,
 * session state, the surface bridge, the PIN presenter and the
 * [ReceiverPlatform] handed to `AirPlayReceiver`. One per process.
 */
internal class AirPlayRuntime private constructor(
    val context: Context,
) {
    val settings = AirPlaySettings(context)
    val session = SessionState()
    val surfaces = SurfaceBridge()
    val wake = WakeController(context, settings)
    val pins = PinPresenter(context, settings)
    val publisher =
        NsdPublisher(context) { advertising ->
            ReceiverStatus.update(context) { if (it.running) it.copy(advertising = advertising) else it }
        }
    val events: ReceiverEvents = MainThreadEvents()
    val platform: ReceiverPlatform = Platform()

    /** Ends the session on Back; installed by [ReceiverService] next to the receiver. */
    @Volatile
    var sessionEnder: SessionEnder = SessionEnder.NONE

    private val main = Handler(Looper.getMainLooper())

    private inner class Platform : ReceiverPlatform {
        override val events: ReceiverEvents get() = this@AirPlayRuntime.events

        override fun publish(records: List<ServiceRecord>) = publisher.publish(records)

        override fun unpublish() = publisher.unpublish()

        override fun createVideoSink(): VideoSink = MediaCodecVideoSink(surfaces)

        override fun createAudioSink(format: AudioFormat): AudioSink {
            val contentType = DecodingAudioSink.ContentType { DecodingAudioSink.contentTypeFor(session.isMirroring) }
            return when (format) {
                is AudioFormat.Alac -> AlacAudioSink(format, contentType)
                is AudioFormat.Aac -> AacAudioSink(format, contentType)
                is AudioFormat.Pcm -> AudioTrackPcmSink(format, contentType)
            }
        }
    }

    /**
     * Receiver callbacks arrive in order on one receiver thread; each one only
     * posts its UI work to the main thread, so the receiver never waits on the
     * UI and nothing here calls back into the receiver.
     */
    private inner class MainThreadEvents : ReceiverEvents {
        override fun onConnectionStarted(client: ClientInfo) {
            // Nothing to do: any host on the network opens connections, and Macs and iPhones probe
            // receivers with GET /info whenever their AirPlay menu is open. The screen wakes for a PIN.
        }

        override fun onPinRequired(
            pin: String,
            client: ClientInfo,
        ) {
            main.post {
                // Every session starts with a code on screen in both PIN modes, so this is the moment to
                // turn the TV on, and only a sender that asks for a code can do it.
                val turnScreenOn = wake.wakeForPin()
                session.showPin(pin, client)
                pins.show(pin, client, turnScreenOn)
            }
        }

        override fun onPinFinished(
            client: ClientInfo,
            result: PinResult,
        ) {
            main.post {
                // Only the prompt this is for: a late finish for an earlier client must not hide the code
                // that is on screen now.
                if (session.clearPin(client)) {
                    pins.hide()
                    wake.releaseScreen()
                }
            }
        }

        override fun onMirroringStarted(client: ClientInfo) {
            main.post {
                wake.releaseScreen()
                session.startMirroring(client)
                SessionActivities.startMirror(context)
            }
        }

        override fun onMirroringStopped(client: ClientInfo) {
            main.post { session.stopMirroring() }
        }

        override fun onAudioStarted(client: ClientInfo) {
            main.post {
                session.startAudio(client)
                if (!session.isMirroring) SessionActivities.startNowPlaying(context)
            }
        }

        override fun onAudioStopped(client: ClientInfo) {
            main.post { session.stopAudio() }
        }

        override fun onNowPlaying(
            client: ClientInfo,
            nowPlaying: NowPlaying?,
        ) {
            main.post { session.updateNowPlaying(nowPlaying) }
        }
    }

    companion object {
        // Holds only the application context, which lives as long as the process anyway.
        @SuppressLint("StaticFieldLeak")
        @Volatile
        private var instance: AirPlayRuntime? = null

        /** The runtime of this process, created on first use. */
        fun get(context: Context): AirPlayRuntime =
            instance ?: synchronized(this) {
                instance ?: AirPlayRuntime(context.applicationContext).also { instance = it }
            }
    }
}
