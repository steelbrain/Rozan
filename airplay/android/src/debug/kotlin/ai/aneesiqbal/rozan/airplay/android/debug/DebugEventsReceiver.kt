package ai.aneesiqbal.rozan.airplay.android.debug

import ai.aneesiqbal.rozan.airplay.android.AirPlayRuntime
import ai.aneesiqbal.rozan.airplay.android.ReceiverService
import ai.aneesiqbal.rozan.airplay.android.session.MediaCounters
import ai.aneesiqbal.rozan.airplay.receiver.ClientInfo
import ai.aneesiqbal.rozan.airplay.receiver.NowPlaying
import ai.aneesiqbal.rozan.airplay.receiver.PinResult
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

/**
 * Debug builds only: fires the same receiver events a real client would, so
 * the PIN, mirroring and now-playing screens can be exercised without a Mac.
 * Runs in the `:airplay` process and needs the sender to hold `DUMP`, which
 * adb's shell has and apps do not. From a computer:
 *
 * ```
 * P=ai.aneesiqbal.rozan.airplay   # or the launcher's package
 * adb shell am broadcast -p $P -a ai.aneesiqbal.rozan.airplay.debug.SHOW_PIN --es pin 4817 --es client "Anna's MacBook Pro"
 * adb shell am broadcast -p $P -a ai.aneesiqbal.rozan.airplay.debug.PIN_DONE --es result ACCEPTED
 * adb shell am broadcast -p $P -a ai.aneesiqbal.rozan.airplay.debug.START_MIRROR
 * adb shell am broadcast -p $P -a ai.aneesiqbal.rozan.airplay.debug.STOP_MIRROR
 * adb shell am broadcast -p $P -a ai.aneesiqbal.rozan.airplay.debug.START_AUDIO
 * adb shell am broadcast -p $P -a ai.aneesiqbal.rozan.airplay.debug.STOP_AUDIO
 * adb shell am broadcast -p $P -a ai.aneesiqbal.rozan.airplay.debug.STATS
 * ```
 *
 * Every event comes from 192.0.2.10 unless `--es address` names another address, which is how the
 * receiver tells clients apart (a `PIN_DONE` only ends the prompt of its own address).
 *
 * `STATS` answers in the broadcast's result data (`am broadcast` prints it) with the media totals of this
 * process since it started: `video_frames_rendered=<n> audio_frames_written=<n>`. Instrumented tests read
 * it to check that a real session reached the screen and the speaker.
 */
internal class DebugEventsReceiver : BroadcastReceiver() {
    override fun onReceive(
        context: Context,
        intent: Intent,
    ) {
        val runtime = AirPlayRuntime.get(context)
        val events = runtime.platform.events
        val client =
            ClientInfo(
                intent.getStringExtra(EXTRA_ADDRESS) ?: "192.0.2.10",
                intent.getStringExtra(EXTRA_CLIENT) ?: "Anna's MacBook Pro",
                "MacBookPro18,3",
            )
        Log.i(TAG, "debug event ${intent.action}")
        when (intent.action) {
            ACTION_SHOW_PIN -> {
                events.onConnectionStarted(client)
                events.onPinRequired(intent.getStringExtra(EXTRA_PIN) ?: "4817", client)
            }

            ACTION_PIN_DONE -> {
                val result =
                    PinResult.entries.firstOrNull { it.name == intent.getStringExtra(EXTRA_RESULT) }
                        ?: PinResult.ACCEPTED
                events.onPinFinished(client, result)
            }

            ACTION_START_MIRROR -> {
                ReceiverService.start(context)
                synchronized(LOCK) {
                    mirror?.stop()
                    mirror = FakeMirrorSession(runtime.platform, TestClip.load(context)).also { it.start() }
                }
                events.onMirroringStarted(client)
            }

            ACTION_STOP_MIRROR -> {
                synchronized(LOCK) {
                    mirror?.stop()
                    mirror = null
                }
                events.onMirroringStopped(client)
            }

            ACTION_START_AUDIO -> {
                ReceiverService.start(context)
                synchronized(LOCK) {
                    audio?.stop()
                    audio = FakeAudioSession(runtime.platform).also { it.start() }
                }
                events.onAudioStarted(client)
                events.onNowPlaying(
                    client,
                    NowPlaying("Test tone, 440 Hz", "Rozan", "Debug hooks", FakeAudioSession.artwork()),
                )
            }

            ACTION_STOP_AUDIO -> {
                synchronized(LOCK) {
                    audio?.stop()
                    audio = null
                }
                events.onAudioStopped(client)
            }

            ACTION_STATS -> {
                resultData =
                    "video_frames_rendered=${MediaCounters.videoFramesRendered.get()} " +
                    "audio_frames_written=${MediaCounters.audioFramesWritten.get()}"
            }
        }
    }

    companion object {
        private const val TAG = "AirPlayDebug"
        private const val PREFIX = "ai.aneesiqbal.rozan.airplay.debug."
        const val ACTION_SHOW_PIN = PREFIX + "SHOW_PIN"
        const val ACTION_PIN_DONE = PREFIX + "PIN_DONE"
        const val ACTION_START_MIRROR = PREFIX + "START_MIRROR"
        const val ACTION_STOP_MIRROR = PREFIX + "STOP_MIRROR"
        const val ACTION_START_AUDIO = PREFIX + "START_AUDIO"
        const val ACTION_STOP_AUDIO = PREFIX + "STOP_AUDIO"
        const val ACTION_STATS = PREFIX + "STATS"
        const val EXTRA_PIN = "pin"
        const val EXTRA_CLIENT = "client"
        const val EXTRA_RESULT = "result"
        const val EXTRA_ADDRESS = "address"

        private val LOCK = Any()
        private var mirror: FakeMirrorSession? = null
        private var audio: FakeAudioSession? = null
    }
}
