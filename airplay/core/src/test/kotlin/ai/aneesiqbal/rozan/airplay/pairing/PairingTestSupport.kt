package ai.aneesiqbal.rozan.airplay.pairing

import ai.aneesiqbal.rozan.airplay.crypto.Crypto
import ai.aneesiqbal.rozan.airplay.receiver.PinMode
import ai.aneesiqbal.rozan.airplay.receiver.PinResult
import java.net.InetAddress
import java.security.SecureRandom
import java.util.concurrent.TimeUnit
import kotlin.test.assertIs

/*
 * Deterministic stand-ins for the pairing service's clock, randomness and screen.
 */

/**
 * A [SecureRandom] that serves queued byte strings, so tests can force codes, salts, SRP b, nonces and
 * ephemeral keys. Each `nextBytes` call takes the next queued chunk when its size matches the request, and
 * otherwise fills the buffer from a fixed SHA-512 stream without consuming the queue. [drained] tells a
 * test that every chunk it queued was used.
 */
class ScriptedRandom(
    vararg chunks: ByteArray,
) : SecureRandom() {
    private val queue = ArrayDeque(chunks.toList())
    private var counter = 0L

    /** Queues [chunks] after anything already queued. */
    fun enqueue(vararg chunks: ByteArray) {
        queue.addAll(chunks)
    }

    /** Whether every queued chunk has been served. */
    val drained: Boolean get() = queue.isEmpty()

    override fun nextBytes(bytes: ByteArray) {
        val head = queue.firstOrNull()
        if (head != null && head.size == bytes.size) {
            queue.removeFirst()
            head.copyInto(bytes)
            return
        }
        var offset = 0
        while (offset < bytes.size) {
            val block = Crypto.sha512("scripted-random".toByteArray(), (counter++).toString().toByteArray())
            val n = minOf(block.size, bytes.size - offset)
            block.copyInto(bytes, offset, 0, n)
            offset += n
        }
    }

    companion object {
        /** The two random bytes that make the code [code] (codes are `value % 10000` of a 16-bit value). */
        fun codeBytes(code: String): ByteArray {
            val value = code.toInt()
            return byteArrayOf((value shr 8).toByte(), value.toByte())
        }
    }
}

/** A monotonic clock under test control, in nanoseconds. */
class FakeClock(
    var now: Long = 7_000_000_000L,
) {
    /** Moves the clock forward by [seconds]. */
    fun advanceSeconds(seconds: Long) {
        now += TimeUnit.SECONDS.toNanos(seconds)
    }

    /** Moves the clock forward by [millis]. */
    fun advanceMillis(millis: Long) {
        now += TimeUnit.MILLISECONDS.toNanos(millis)
    }

    /** The reader to pass as the service's `clock`. */
    val source: () -> Long = { now }
}

/** A [CodeDisplay] that records every call in order. */
internal class RecordingDisplay : CodeDisplay {
    /** One display call. */
    sealed interface Event {
        /** The client the call was about. */
        val client: InetAddress
    }

    /** [CodeDisplay.show]. */
    data class Shown(
        val code: String,
        override val client: InetAddress,
        val clientName: String?,
    ) : Event

    /** [CodeDisplay.finish]. */
    data class Finished(
        override val client: InetAddress,
        val result: PinResult,
    ) : Event

    private val recorded = ArrayList<Event>()

    /** Every call so far. */
    val events: List<Event> get() = synchronized(recorded) { recorded.toList() }

    /** Called inside [show] and [finish], after recording; tests use it to probe the service's lock. */
    var onEvent: (Event) -> Unit = {}

    /** The code of the last [Shown] event. */
    val lastCode: String get() = assertIs<Shown>(events.last { it is Shown }).code

    /** The codes shown so far, in order. */
    val shownCodes: List<String> get() = events.filterIsInstance<Shown>().map { it.code }

    /** The results of [Finished] events so far, in order. */
    val results: List<PinResult> get() = events.filterIsInstance<Finished>().map { it.result }

    /** Forgets the events so far. */
    fun clear() {
        synchronized(recorded) { recorded.clear() }
    }

    override fun show(
        code: String,
        client: InetAddress,
        clientName: String?,
    ) {
        record(Shown(code, client, clientName))
    }

    override fun finish(
        client: InetAddress,
        result: PinResult,
    ) {
        record(Finished(client, result))
    }

    private fun record(event: Event) {
        synchronized(recorded) { recorded.add(event) }
        onEvent(event)
    }
}

/** A service under test with its fakes. */
internal class PairingFixture(
    mode: PinMode,
    val random: ScriptedRandom = ScriptedRandom(),
    identitySeed: ByteArray = ByteArray(32) { (0x80 + it).toByte() },
) {
    /** The fake clock the service reads. */
    val clock = FakeClock()

    /** The screen. */
    val display = RecordingDisplay()

    /** How often the identity rotated. */
    var rotations = 0
        private set

    /** Called inside `onIdentityChanged`, after counting. */
    var onRotation: () -> Unit = {}

    /** The service under test. */
    val service =
        DefaultPairingService(
            mode = mode,
            identitySeed = identitySeed,
            display = display,
            onIdentityChanged = {
                rotations++
                onRotation()
            },
            clock = clock.source,
            random = random,
        )
}

/** Test addresses. */
object Addresses {
    /** A sender on IPv4. */
    val sender: InetAddress = InetAddress.getByName("192.168.1.30")

    /** Another sender on IPv4. */
    val other: InetAddress = InetAddress.getByName("192.168.1.31")

    /** The [n]th IPv4 address in 10.0.0.0/16. */
    fun ipv4(n: Int): InetAddress = InetAddress.getByAddress(byteArrayOf(10, 0, (n shr 8).toByte(), n.toByte()))

    /** An IPv6 address in `2001:db8:0:[subnet]::/64` with interface id [host]. */
    fun ipv6(
        subnet: Int,
        host: Int,
    ): InetAddress = InetAddress.getByName("2001:db8:0:${Integer.toHexString(subnet)}::${Integer.toHexString(host)}")
}
