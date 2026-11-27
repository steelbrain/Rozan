package ai.aneesiqbal.rozan.airplay.session

import ai.aneesiqbal.rozan.airplay.media.MediaSessionFactory
import ai.aneesiqbal.rozan.airplay.pairing.CodeDisplay
import ai.aneesiqbal.rozan.airplay.pairing.PairingService
import ai.aneesiqbal.rozan.airplay.receiver.FairPlayHandlerFactory
import ai.aneesiqbal.rozan.airplay.receiver.PinMode
import ai.aneesiqbal.rozan.airplay.receiver.ReceiverConfig
import ai.aneesiqbal.rozan.airplay.receiver.ReceiverLog
import ai.aneesiqbal.rozan.airplay.receiver.ReceiverPlatform
import ai.aneesiqbal.rozan.airplay.receiver.ServiceRecord
import java.net.InetAddress
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.TimeUnit

/** Creates the pairing service of a running receiver. */
internal fun interface PairingFactory {
    /** A service for [mode] whose first identity comes from [identitySeed]. */
    fun create(
        mode: PinMode,
        identitySeed: ByteArray,
        display: CodeDisplay,
        onIdentityChanged: () -> Unit,
    ): PairingService
}

/** Everything a running receiver is built from; tests replace the media and pairing layers. */
internal class ReceiverParts(
    val mediaFactory: MediaSessionFactory,
    val pairingFactory: PairingFactory,
    val limits: ControlLimits = ControlLimits(),
    val clock: () -> Long = System::nanoTime,
)

/**
 * State shared by every connection of a running receiver: the pairing service, the event thread, the
 * advertisement and its publication, and which connection owns the streaming session.
 *
 * [sessionLock] guards every media session transition on every connection (open, stream start/stop,
 * teardown, takeover). There is at most one session at a time, so one lock costs nothing and gives a
 * single lock order: [sessionLock], then the pairing service's lock, then the media layer's.
 */
internal class ReceiverCore(
    val config: ReceiverConfig,
    val platform: ReceiverPlatform,
    val fairPlay: FairPlayHandlerFactory,
    val log: ReceiverLog,
    parts: ReceiverParts,
    private val currentName: () -> String,
) {
    val mediaFactory: MediaSessionFactory = parts.mediaFactory
    val limits: ControlLimits = parts.limits
    val clock: () -> Long = parts.clock
    val events = EventDispatcher(platform.events, log)

    /** Per-install `pi` (01 §1), derived from the device id. */
    val pi: String = IdentityText.derivedUuid(config.deviceId, "pi")

    /** Per-install display UUID (01 §6.1), derived from the device id. */
    val displayUuid: String = IdentityText.derivedUuid(config.deviceId, "display")

    /** Guards media session transitions; see the class comment. */
    val sessionLock = Any()

    /** The connection whose session is streaming, written under [sessionLock]. */
    @Volatile var owner: ControlConnection? = null
        private set

    /** The bound control port, set once the server listens. */
    @Volatile var port: Int = 0

    private val publisher = Executors.newSingleThreadExecutor(daemonThreads("airplay-publish"))
    private val infoLimiter = InfoLimiter(limits.infoRequestsPerSecond)

    @Volatile private var cached: Advertisement? = null
    private var published: List<ServiceRecord>? = null

    /** Pairing and access control; its identity changes republish the records. */
    val pairing: PairingService =
        parts.pairingFactory.create(config.pinMode, config.identitySeed, EventCodeDisplay(events)) { republish() }

    /** The advertisement for the current name and identity key. */
    fun advertisement(): Advertisement {
        val name = currentName()
        val key = pairing.identityPublicKey
        val current = cached
        if (current != null && current.requestedName == name && current.hasPublicKey(key)) return current
        return Advertisement(name, config.deviceId, key, config.pinMode, pi).also { cached = it }
    }

    /** Publishes the current records on the publish thread, unless they are already published. */
    fun republish() =
        submit("publish") {
            val records = advertisement().records(port)
            if (records != published) {
                platform.publish(records)
                published = records
                log.log(ReceiverLog.Level.INFO, "published both services on port $port")
            }
        }

    /** Withdraws the records, then stops the publish thread, waiting up to 5 s for it. */
    fun unpublishAndStop() {
        submit("unpublish") {
            platform.unpublish()
            published = null
        }
        publisher.shutdown()
        publisher.awaitTermination(SHUTDOWN_WAIT_SECONDS, TimeUnit.SECONDS)
    }

    /** Whether [client] is another address than the streaming session's owner (01 §3.1: 409). */
    fun conflicts(client: InetAddress): Boolean {
        val current = owner ?: return false
        return current.client != client
    }

    /**
     * Makes [connection] the session owner and returns the previous owner, if another connection; the
     * caller ends that session. Call under [sessionLock].
     */
    fun claim(connection: ControlConnection): ControlConnection? {
        val previous = owner
        owner = connection
        return previous?.takeIf { it !== connection }
    }

    /** Clears the owner if it is [connection]. Call under [sessionLock]. */
    fun release(connection: ControlConnection) {
        if (owner === connection) owner = null
    }

    /** Whether [client] may have another `GET /info` now (02 §9.1). */
    fun infoAllowed(client: InetAddress): Boolean = infoLimiter.allow(client, clock())

    /**
     * Whether [housekeeping] has work beyond the connections' own deadlines: a streaming session (its
     * feedback timeout) or pairing state that expires.
     */
    fun needsHousekeeping(): Boolean = owner != null || pairing.needsTicks

    /** Periodic work: pairing expiries and rotation, the feedback timeout (03 §6.4). */
    fun housekeeping() {
        val now = clock()
        val current = owner
        pairing.tick(sessionActive = current != null)
        if (current != null && current.feedbackExpired(now)) current.terminate("no /feedback for too long")
        infoLimiter.prune(now)
    }

    /** Logs [message] at [level]. */
    fun log(
        level: ReceiverLog.Level,
        message: String,
    ) = log.log(level, message)

    private fun submit(
        what: String,
        task: () -> Unit,
    ) {
        try {
            publisher.execute { guarded(log, what, task) }
        } catch (e: RejectedExecutionException) {
            log.log(ReceiverLog.Level.DEBUG, "$what skipped: ${describe(e)}")
        }
    }

    private companion object {
        const val SHUTDOWN_WAIT_SECONDS = 5L
    }
}

/**
 * Per-address `GET /info` rate limit (02 §9.1): at most [perSecond] in each one-second window. Holds at
 * most [MAX_ADDRESSES] windows; when full, it forgets expired windows, then refuses new addresses until
 * one expires.
 */
internal class InfoLimiter(
    private val perSecond: Int,
) {
    private val windows = HashMap<InetAddress, LongArray>()

    /** Counts a request from [client] at [now]; false when over the limit. */
    @Synchronized
    fun allow(
        client: InetAddress,
        now: Long,
    ): Boolean {
        var window = windows[client]
        if (window == null) {
            if (windows.size >= MAX_ADDRESSES) prune(now)
            if (windows.size >= MAX_ADDRESSES) return false
            window = longArrayOf(now, 0)
            windows[client] = window
        }
        if (now - window[0] >= WINDOW_NANOS) {
            window[0] = now
            window[1] = 0
        }
        if (window[1] >= perSecond) return false
        window[1]++
        return true
    }

    /** Drops windows that have ended. */
    @Synchronized
    fun prune(now: Long) {
        windows.values.removeIf { now - it[0] >= WINDOW_NANOS }
    }

    companion object {
        /** Most addresses tracked. */
        const val MAX_ADDRESSES = 256

        private val WINDOW_NANOS = TimeUnit.SECONDS.toNanos(1)
    }
}
