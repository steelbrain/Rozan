package ai.aneesiqbal.rozan.airplay.pairing

import java.net.Inet6Address
import java.net.InetAddress
import java.security.SecureRandom
import java.util.concurrent.TimeUnit

/*
 * Rate limits and lockouts of docs/protocol/02-pairing.md §9. None of these classes is thread-safe:
 * DefaultPairingService calls them under its lock. Times are System.nanoTime()-style values and are only
 * ever compared by subtraction, so they survive wraparound.
 */

/** The limits of 02 §5.1, §6.2 and §9, in one place. */
internal object AccessPolicy {
    /** A code is valid for 120 s (02 §5.1, §6.1, §9.2). */
    val CODE_LIFETIME_NANOS: Long = TimeUnit.SECONDS.toNanos(120)

    /**
     * No new code for a client within 5 s of its code that is still on screen; a repeated `pair-pin-start`
     * keeps that code (02 §9.1). Once the prompt is over, the client may get a new code at once.
     */
    val MIN_CODE_INTERVAL_NANOS: Long = TimeUnit.SECONDS.toNanos(5)

    /** At most this many codes per [GLOBAL_CODE_WINDOW_NANOS] across all clients (02 §9.1). */
    const val GLOBAL_CODES_PER_WINDOW: Int = 6

    /** Window for [GLOBAL_CODES_PER_WINDOW]. */
    val GLOBAL_CODE_WINDOW_NANOS: Long = TimeUnit.MINUTES.toNanos(1)

    /**
     * At most this many codes per client in [CLIENT_CODE_WINDOW_NANOS] that did not pair (expired,
     * superseded, used up or cancelled), so that no one address can take the global budget (02 §9.1).
     */
    const val CLIENT_CODES_PER_WINDOW: Int = 3

    /** Window for [CLIENT_CODES_PER_WINDOW]. */
    val CLIENT_CODE_WINDOW_NANOS: Long = TimeUnit.MINUTES.toNanos(10)

    /** Failed SRP proofs a code survives (02 §9.2): the third uses it up. */
    const val SRP_FAILURES_PER_CODE: Int = 3

    /** Failed Digest responses a code survives (02 §6.2): the fourth uses it up. */
    const val DIGEST_FAILURES_PER_CODE: Int = 4

    /** `pair-setup-pin` step 1 runs (two 2048-bit exponentiations each) allowed per code. */
    const val SRP_STARTS_PER_CODE: Int = 10

    /** Failures per client that cost nothing (02 §9.2); the next one locks the client out. */
    const val FREE_FAILURES: Int = 5

    /** First lockout; each later failure doubles it up to [MAX_LOCKOUT_NANOS]. */
    val FIRST_LOCKOUT_NANOS: Long = TimeUnit.SECONDS.toNanos(30)

    /** Longest lockout. */
    val MAX_LOCKOUT_NANOS: Long = TimeUnit.MINUTES.toNanos(10)

    /** A client record with no activity for this long is dropped. */
    val RECORD_IDLE_NANOS: Long = TimeUnit.HOURS.toNanos(1)

    /** Size of the per-client failure table. */
    const val LOCKOUT_TABLE_SIZE: Int = 64

    /** More than this many failures in [GLOBAL_FAILURE_WINDOW_NANOS] stops new codes (02 §9.2). */
    const val GLOBAL_FAILURE_THRESHOLD: Int = 20

    /** Window for [GLOBAL_FAILURE_THRESHOLD]. */
    val GLOBAL_FAILURE_WINDOW_NANOS: Long = TimeUnit.MINUTES.toNanos(10)

    /** How long no new codes are issued after [GLOBAL_FAILURE_THRESHOLD] is crossed. */
    val GLOBAL_SUSPENSION_NANOS: Long = TimeUnit.MINUTES.toNanos(10)

    /**
     * Mode P: an identity used for PIN pairing rotates after this long without a PIN step 3 or an active
     * session (02 §8.2 rule 5).
     */
    val IDLE_ROTATION_NANOS: Long = TimeUnit.SECONDS.toNanos(60)

    /**
     * Mode P: how long after its PIN step 3 a sender key may pair-verify on another connection, outside an
     * active session (02 §8.2 rule 3).
     */
    val TRUST_LIFETIME_NANOS: Long = TimeUnit.SECONDS.toNanos(30)

    /**
     * Mode P: no rotation this soon after a PIN step 3, so the pair-verify that follows is signed with the
     * key step 3 returned (02 §8.1: the identity is stable through the PIN flow and pair-verify).
     */
    val ROTATION_GRACE_NANOS: Long = TimeUnit.SECONDS.toNanos(10)
}

/** The key codes are stored under: the full address. */
internal fun codeKey(client: InetAddress): String = client.hostAddress

/**
 * The key failures are counted under (02 §9.2): the full address, IPv4 or IPv6. Not an IPv6 prefix: every
 * host on a link shares `fe80::/64`, so one host's failures would lock all of them out. The scope of a
 * link-local address is left out, so one host is one key whichever interface it was seen on.
 */
internal fun lockoutKey(client: InetAddress): String {
    val bytes = client.address
    if (client !is Inet6Address) return bytes.joinToString(".") { (it.toInt() and 0xFF).toString() }
    return bytes.joinToString(":", prefix = "v6:") { Integer.toHexString(it.toInt() and 0xFF) }
}

/**
 * A new 4-digit code, uniform over 0000-9999 (02 §5.1): two random bytes, rejecting values of 60 000 and
 * above so that the reduction has no bias.
 */
internal fun newCode(random: SecureRandom): String {
    val bytes = ByteArray(2)
    while (true) {
        random.nextBytes(bytes)
        val value = ((bytes[0].toInt() and 0xFF) shl 8) or (bytes[1].toInt() and 0xFF)
        if (value < UNBIASED_LIMIT) return (value % CODE_SPACE).toString().padStart(CODE_DIGITS, '0')
    }
}

private const val CODE_SPACE = 10_000
private const val CODE_DIGITS = 4
private const val UNBIASED_LIMIT = 60_000

/**
 * Per-client failure records (02 §9.2): failures 1 to [AccessPolicy.FREE_FAILURES] are free, the next
 * locks the client out for 30 s, and each failure after that doubles the lockout up to 10 min. A success
 * clears the record; a record idle for an hour is dropped. The table has a fixed size and evicts only
 * unlocked records, so an attacker cannot flush a lockout by spraying addresses.
 */
internal class LockoutTable(
    private val capacity: Int = AccessPolicy.LOCKOUT_TABLE_SIZE,
) {
    private class Record(
        var failures: Int,
        var lockedUntil: Long,
        var lastActivity: Long,
    )

    private val records = HashMap<String, Record>()

    /** Whether [key] is locked out at [now]. */
    fun isLocked(
        key: String,
        now: Long,
    ): Boolean = records[key]?.let { it.lockedUntil - now > 0 } ?: false

    /**
     * Counts a failure for [key]. Returns true when this failure locks it out. When the table is full of
     * locked records the failure is not recorded (the global counter still sees it).
     */
    fun recordFailure(
        key: String,
        now: Long,
    ): Boolean {
        prune(now)
        val record =
            records[key] ?: if (records.size < capacity || evictUnlocked(now)) {
                Record(0, now, now).also { records[key] = it }
            } else {
                return false
            }
        record.failures++
        record.lastActivity = now
        if (record.failures <= AccessPolicy.FREE_FAILURES) return false
        val doublings = minOf(record.failures - AccessPolicy.FREE_FAILURES - 1, MAX_DOUBLINGS)
        record.lockedUntil = now + minOf(AccessPolicy.FIRST_LOCKOUT_NANOS shl doublings, AccessPolicy.MAX_LOCKOUT_NANOS)
        return true
    }

    /** A success from [key] clears its record. */
    fun recordSuccess(key: String) {
        records.remove(key)
    }

    /** Drops unlocked records idle for [AccessPolicy.RECORD_IDLE_NANOS]. */
    fun prune(now: Long) {
        records.values.removeAll {
            now - it.lastActivity >= AccessPolicy.RECORD_IDLE_NANOS && it.lockedUntil - now <= 0
        }
    }

    /** Number of records kept, for tests. */
    val size: Int get() = records.size

    private fun evictUnlocked(now: Long): Boolean {
        var oldestKey: String? = null
        var oldest = 0L
        for ((key, record) in records) {
            if (record.lockedUntil - now > 0) continue
            if (oldestKey == null || record.lastActivity - oldest < 0) {
                oldestKey = key
                oldest = record.lastActivity
            }
        }
        val victim = oldestKey ?: return false
        records.remove(victim)
        return true
    }

    private companion object {
        // 30 s doubled 5 times is past the 10 min cap, so more doublings never matter (and cannot overflow).
        const val MAX_DOUBLINGS = 5
    }
}

/**
 * The receiver-wide failure counter (02 §9.2): more than 20 failures in 10 minutes across all clients
 * stops new codes for 10 minutes, against attackers that rotate addresses.
 */
internal class GlobalFailures {
    private val times = ArrayDeque<Long>()
    private var suspendedUntil = 0L
    private var suspended = false

    /** Counts a failure. Returns true when it starts a suspension. */
    fun record(now: Long): Boolean {
        drop(now)
        times.addLast(now)
        if (times.size <= AccessPolicy.GLOBAL_FAILURE_THRESHOLD) return false
        times.clear()
        val started = !isSuspended(now)
        suspended = true
        suspendedUntil = now + AccessPolicy.GLOBAL_SUSPENSION_NANOS
        return started
    }

    /** Whether new codes are suspended at [now]. */
    fun isSuspended(now: Long): Boolean = suspended && suspendedUntil - now > 0

    private fun drop(now: Long) {
        while (times.isNotEmpty() &&
            now - times.first() >= AccessPolicy.GLOBAL_FAILURE_WINDOW_NANOS
        ) {
            times.removeFirst()
        }
    }
}

/**
 * The code budgets of 02 §9.1: 6 codes per minute overall, and at most
 * [AccessPolicy.CLIENT_CODES_PER_WINDOW] per client in 10 minutes that did not pair. A code counts against
 * its client from the moment it is issued until it pairs ([paired]) or the window passes, so codes that
 * expire, are superseded, used up or cancelled all count. The per-client records only exist for clients
 * given a code in the last 10 minutes, and the global cap bounds those to 60. (The 5 s spacing applies to
 * a client's code on screen and is checked by the service, which knows the codes.)
 */
internal class CodeIssuance {
    private val recent = ArrayDeque<Long>()
    private val unpairedByClient = HashMap<String, ArrayDeque<Long>>()

    /** Whether the budgets allow a code for [key] at [now]. */
    fun mayIssue(
        key: String,
        now: Long,
    ): Boolean {
        prune(now)
        val unpaired = unpairedByClient[key]?.size ?: 0
        return recent.size < AccessPolicy.GLOBAL_CODES_PER_WINDOW && unpaired < AccessPolicy.CLIENT_CODES_PER_WINDOW
    }

    /** Records that a code was issued to [key] at [now]. */
    fun issued(
        key: String,
        now: Long,
    ) {
        recent.addLast(now)
        unpairedByClient.getOrPut(key, ::ArrayDeque).addLast(now)
    }

    /** The code issued to [key] at [issuedAt] paired: it no longer counts against [key]. */
    fun paired(
        key: String,
        issuedAt: Long,
    ) {
        val times = unpairedByClient[key] ?: return
        times.remove(issuedAt)
        if (times.isEmpty()) unpairedByClient.remove(key)
    }

    private fun prune(now: Long) {
        while (recent.isNotEmpty() &&
            now - recent.first() >= AccessPolicy.GLOBAL_CODE_WINDOW_NANOS
        ) {
            recent.removeFirst()
        }
        for (times in unpairedByClient.values) {
            while (times.isNotEmpty() && now - times.first() >= AccessPolicy.CLIENT_CODE_WINDOW_NANOS) {
                times.removeFirst()
            }
        }
        unpairedByClient.values.removeAll { it.isEmpty() }
    }
}
