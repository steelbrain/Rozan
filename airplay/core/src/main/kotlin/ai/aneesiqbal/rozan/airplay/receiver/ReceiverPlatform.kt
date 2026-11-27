package ai.aneesiqbal.rozan.airplay.receiver

/**
 * Everything the receiver needs from the platform it runs on (Android, or the
 * desktop dev server). Implementations must be thread-safe: methods are called
 * from receiver threads, never from the platform's main thread.
 */
public interface ReceiverPlatform {
    /** Publishes [records] on the local network (mDNS/DNS-SD), replacing any previous set. */
    public fun publish(records: List<ServiceRecord>)

    /** Withdraws everything published with [publish]. */
    public fun unpublish()

    /** Creates the sink for one mirroring session. Called once per session. */
    public fun createVideoSink(): VideoSink

    /** Creates the sink for one audio stream of the given [format]. */
    public fun createAudioSink(format: AudioFormat): AudioSink

    /** Session lifecycle notifications. */
    public val events: ReceiverEvents
}

/** One DNS-SD service instance to advertise. */
public data class ServiceRecord(
    /** Instance name, e.g. "Mango Kiwi Plum Fig" or "AABBCCDDEEFF@Mango Kiwi Plum Fig". */
    val instanceName: String,
    /** Service type without domain, e.g. `_airplay._tcp`. */
    val serviceType: String,
    /** TCP port of the service. */
    val port: Int,
    /** TXT record entries, in the order they should be published. */
    val txt: List<Pair<String, String>>,
)

/** Lifecycle callbacks. Called on receiver threads; implementations should hand off quickly. */
public interface ReceiverEvents {
    /**
     * A client opened a control connection and sent its first request. This fires for any host on the
     * network before it has paired, `GET /info` probes included, so it must not wake the screen or show
     * anything: [onPinRequired] is the first event that a session is starting.
     */
    public fun onConnectionStarted(client: ClientInfo)

    /**
     * A control connection that raised [onConnectionStarted] has closed, after the stopped events of
     * anything it streamed. A PIN prompt can outlive its connection: in PIN pairing the sender asks for
     * the code on one connection and enters it on the next (docs/protocol/02-pairing.md §5.1), so a
     * prompt ends with [onPinFinished], not here. Does nothing unless overridden.
     */
    public fun onConnectionEnded(client: ClientInfo) {
        // Optional: implementations that track connections override this.
    }

    /** The client asked to pair; show [pin] on screen until [onPinFinished]. */
    public fun onPinRequired(
        pin: String,
        client: ClientInfo,
    )

    /** The PIN prompt for [client] is over, with [result]. */
    public fun onPinFinished(
        client: ClientInfo,
        result: PinResult,
    )

    /** Screen mirroring from [client] has started; bring the mirroring surface to the front. */
    public fun onMirroringStarted(client: ClientInfo)

    /** Screen mirroring has stopped. */
    public fun onMirroringStopped(client: ClientInfo)

    /** Audio-only streaming from [client] has started. */
    public fun onAudioStarted(client: ClientInfo)

    /** Audio-only streaming has stopped. */
    public fun onAudioStopped(client: ClientInfo)

    /** Track metadata changed, or null when it was cleared. */
    public fun onNowPlaying(
        client: ClientInfo,
        nowPlaying: NowPlaying?,
    )
}

/** What is known about a connected client. Everything except [address] is client-supplied and untrusted. */
public data class ClientInfo(
    /** Remote IP address as text. */
    val address: String,
    /** Device name the client reported, if any (e.g. "Anna's MacBook Pro"). */
    val name: String?,
    /** Model identifier the client reported, if any (e.g. "MacBookPro18,3"). */
    val model: String?,
)

/** Outcome of a PIN prompt. */
public enum class PinResult {
    /** The client proved knowledge of the PIN. */
    ACCEPTED,

    /** The client sent a wrong PIN. */
    REJECTED,

    /** The client went away or the prompt was superseded. */
    CANCELLED,

    /** Too many failures; further attempts are refused for a while. */
    LOCKED_OUT,
}

/** Track metadata sent by audio clients. */
public data class NowPlaying(
    val title: String?,
    val artist: String?,
    val album: String?,
    /** Encoded image (JPEG or PNG) as sent by the client, if any. Untrusted: decode with size limits. */
    val artwork: ByteArray?,
) {
    override fun equals(other: Any?): Boolean =
        other is NowPlaying &&
            title == other.title &&
            artist == other.artist &&
            album == other.album &&
            artwork.contentEquals(other.artwork)

    override fun hashCode(): Int = listOf(title, artist, album, artwork?.contentHashCode()).hashCode()
}
