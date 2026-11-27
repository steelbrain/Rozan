package ai.aneesiqbal.rozan.airplay.android.session

import ai.aneesiqbal.rozan.airplay.receiver.ClientInfo
import ai.aneesiqbal.rozan.airplay.receiver.NowPlaying

/**
 * What the receiver is doing right now, as far as the UI is concerned. Lives
 * on the main thread; the session activities observe it and finish
 * themselves when their part of the session ends. Only the current session
 * is held, nothing is remembered once it ends.
 */
internal class SessionState {
    /** Observer of any change, called on the main thread. */
    fun interface Listener {
        /** Something in the session changed; re-read what you show. */
        fun onSessionChanged()
    }

    /** A PIN on screen for [client]. */
    class PinPrompt(
        val pin: String,
        val client: ClientInfo,
    )

    private val listeners = ArrayList<Listener>()

    /** The PIN prompt being shown, if any. */
    var pinPrompt: PinPrompt? = null
        private set

    /** Client mirroring its screen, if any. */
    var mirroringClient: ClientInfo? = null
        private set

    /** Client streaming audio only, if any. */
    var audioClient: ClientInfo? = null
        private set

    /** Metadata of the audio stream, when the client sent some. */
    var nowPlaying: NowPlaying? = null
        private set

    /** Whether mirroring is active; readable from any thread (picks the audio content type). */
    @Volatile
    var isMirroring: Boolean = false
        private set

    fun addListener(listener: Listener) {
        listeners += listener
    }

    fun removeListener(listener: Listener) {
        listeners -= listener
    }

    /**
     * Shows [pin] for [client]. The receiver shows one prompt at a time, so a new one means the one on
     * screen is over, even if its finish has not arrived yet.
     */
    fun showPin(
        pin: String,
        client: ClientInfo,
    ) = change { pinPrompt = PinPrompt(pin, client) }

    /**
     * The prompt for [client] is over: clears it if it is the one on screen, matched by address (the only
     * part of [ClientInfo] the client does not choose). Returns whether it was. A finish for any other
     * client leaves the prompt on screen alone.
     */
    fun clearPin(client: ClientInfo): Boolean {
        if (pinPrompt?.client?.address != client.address) return false
        change { pinPrompt = null }
        return true
    }

    fun startMirroring(client: ClientInfo) =
        change {
            mirroringClient = client
            isMirroring = true
        }

    fun stopMirroring() =
        change {
            mirroringClient = null
            isMirroring = false
        }

    fun startAudio(client: ClientInfo) = change { audioClient = client }

    fun stopAudio() =
        change {
            audioClient = null
            nowPlaying = null
        }

    fun updateNowPlaying(metadata: NowPlaying?) = change { nowPlaying = metadata }

    private inline fun change(block: () -> Unit) {
        block()
        // Copy: a listener may remove itself (an activity finishing) while being notified.
        for (listener in listeners.toTypedArray()) listener.onSessionChanged()
    }
}
