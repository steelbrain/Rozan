package ai.aneesiqbal.rozan.airplay.receiver

import ai.aneesiqbal.rozan.airplay.session.ControlServer
import ai.aneesiqbal.rozan.airplay.session.DefaultParts
import ai.aneesiqbal.rozan.airplay.session.ReceiverCore
import ai.aneesiqbal.rozan.airplay.session.ReceiverParts
import java.io.IOException

/** Static facts about the display, reported to clients so they pick a matching stream size. */
public data class DisplayInfo(
    val widthPixels: Int,
    val heightPixels: Int,
    val refreshRateHz: Int,
)

/** Configuration of one receiver instance. */
public class ReceiverConfig(
    /** Name shown in the AirPlay menu. */
    public val name: String,
    /** 6-byte device identifier, stable per install (formatted like a MAC address on the wire). */
    public val deviceId: ByteArray,
    /**
     * 32-byte Ed25519 private key seed: this receiver's own long-term identity, stable per install. Used in
     * [PinMode.DIGEST]; [PinMode.PIN_PAIRING] uses a fresh random identity for every session instead
     * (docs/protocol/02-pairing.md §8.2).
     */
    public val identitySeed: ByteArray,
    /** Display the stream will be shown on. */
    public val display: DisplayInfo,
    /** How senders prove they can see the TV; see [PinMode]. */
    public val pinMode: PinMode = PinMode.PIN_PAIRING,
    /**
     * TCP port of the control server, advertised for both services. [DEFAULT_CONTROL_PORT] is what Apple
     * TVs use; 0 picks an ephemeral port (read it back from [AirPlayReceiver.controlPort]).
     */
    public val controlPort: Int = DEFAULT_CONTROL_PORT,
) {
    init {
        require(deviceId.size == 6) { "deviceId must be 6 bytes" }
        require(identitySeed.size == 32) { "identitySeed must be 32 bytes" }
        require(controlPort in 0..MAX_PORT) { "controlPort must be in 0..$MAX_PORT" }
    }

    /** Defaults. */
    public companion object {
        /** The AirPlay control port, 7000 (docs/protocol/01 §2.1). */
        public const val DEFAULT_CONTROL_PORT: Int = 7000

        private const val MAX_PORT = 65_535
    }
}

/**
 * The AirPlay receiver (docs/protocol/01-04): advertises itself through the [platform], listens for
 * senders on [ReceiverConfig.controlPort], runs pairing and the on-screen code, FairPlay and sessions,
 * and feeds mirrored video and audio to the platform's sinks. Every connection asks for a fresh code
 * and nothing about senders is remembered (see [PinMode]).
 *
 * Events reach [ReceiverPlatform.events] on one receiver thread, in order; callbacks may call back into
 * the receiver. All methods are thread-safe.
 */
public class AirPlayReceiver internal constructor(
    private val config: ReceiverConfig,
    private val platform: ReceiverPlatform,
    private val fairPlay: FairPlayHandlerFactory?,
    private val log: ReceiverLog,
    private val parts: ReceiverParts,
) {
    /**
     * A receiver for [config] on [platform].
     *
     * @param fairPlay creates the FairPlay state of each control connection. `:airplay:core` does not
     *   contain FairPlay; the app adapts `:airplay:fairplay` to [FairPlayHandler]. Without one, every
     *   `fp-setup` fails, so no sender can connect; [start] logs a warning.
     * @param log diagnostics; see [ReceiverLog].
     */
    public constructor(
        config: ReceiverConfig,
        platform: ReceiverPlatform,
        fairPlay: FairPlayHandlerFactory? = null,
        log: ReceiverLog = ReceiverLog.NONE,
    ) : this(config, platform, fairPlay, log, DefaultParts.create(log))

    private val lock = Any()

    @Volatile private var currentName: String = config.name

    private var running: Running? = null

    private class Running(
        val core: ReceiverCore,
        val server: ControlServer,
    )

    /** The bound control port while running (useful with [ReceiverConfig.controlPort] 0), else 0. */
    public val controlPort: Int get() = synchronized(lock) { running?.server?.port ?: 0 }

    /** The name currently advertised (before the DNS-SD length rules), see [rename]. */
    public val name: String get() = currentName

    /**
     * Starts listening and publishes the service records. Idempotent.
     *
     * @throws IOException when the control port cannot be bound (for example, it is in use).
     */
    public fun start() {
        synchronized(lock) {
            if (running != null) return
            check(currentName.isNotBlank()) { "the receiver name must not be blank" }
            if (fairPlay == null) log.log(ReceiverLog.Level.WARN, "no FairPlay handler: senders cannot connect")
            val core = ReceiverCore(config, platform, fairPlay ?: NoFairPlay, log, parts) { currentName }
            val server = ControlServer(core)
            try {
                server.start(config.controlPort)
            } catch (e: IOException) {
                server.close()
                core.unpublishAndStop()
                core.events.shutdown()
                throw e
            }
            core.port = server.port
            running = Running(core, server)
            core.republish()
        }
    }

    /** Stops all sessions (firing the stopped events), closes every socket and unpublishes. Idempotent. */
    public fun stop() {
        val current = synchronized(lock) { running.also { running = null } } ?: return
        current.server.close()
        current.core.unpublishAndStop()
        current.core.events.shutdown()
    }

    /**
     * Ends every current connection and session, for example when the user leaves the mirroring screen
     * on the TV. Streams stop and [ReceiverEvents.onMirroringStopped] / [ReceiverEvents.onAudioStopped]
     * are delivered before this returns (unless called from an event callback, where they follow it).
     * The receiver keeps listening; a sender may connect again, and is asked for a new code.
     */
    public fun disconnectAll() {
        val current = synchronized(lock) { running } ?: return
        current.server.disconnectAll("disconnected by the receiver")
        current.core.events.drain()
    }

    /** Changes the advertised name and republishes both services when running. */
    public fun rename(name: String) {
        require(name.isNotBlank()) { "the receiver name must not be blank" }
        currentName = name
        synchronized(lock) { running }?.core?.republish()
    }
}
