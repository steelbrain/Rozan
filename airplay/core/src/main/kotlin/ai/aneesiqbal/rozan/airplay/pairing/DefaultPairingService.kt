package ai.aneesiqbal.rozan.airplay.pairing

import ai.aneesiqbal.rozan.airplay.crypto.Crypto
import ai.aneesiqbal.rozan.airplay.crypto.Ed25519KeyPair
import ai.aneesiqbal.rozan.airplay.receiver.PinMode
import ai.aneesiqbal.rozan.airplay.receiver.PinResult
import java.net.InetAddress
import java.security.SecureRandom

/**
 * The receiver-wide pairing state of docs/protocol/02-pairing.md: identity key, codes, Mode P session
 * trust, rate limits and lockouts.
 *
 * **Identity.** In [PinMode.DIGEST] (Mode D) the identity comes from [identitySeed] and never changes. In
 * [PinMode.PIN_PAIRING] (Mode P) it is generated at construction, kept only in memory, and rotated when a
 * session that used PIN pairing ends ([sessionEnded]) or 60 s after the last PIN step 3 or active session
 * ([tick]), but never while a PIN code is live or a session is active (02 §8.2), nor within 10 s of a PIN
 * step 3 (the sender's pair-verify must see the key step 3 returned); a deferred rotation runs on a later
 * [tick]. Requests that authenticate nothing never count as activity, so other hosts cannot keep the
 * identity from rotating. Rotation clears the session trust and calls [onIdentityChanged] so the control
 * layer republishes `pk`. An identity no sender PIN-paired with leaves nothing for a sender to reuse, so it
 * does not rotate (that would only churn the TXT records).
 *
 * **Session trust** (Mode P). A key that completed PIN step 3 may pair-verify on another connection for
 * [AccessPolicy.TRUST_LIFETIME_NANOS] after its step 3, or while a session is active, and no longer. When a
 * session ends the trust is cleared at once, even when the key change has to wait: the next connection
 * needs a new PIN even before the new key is out (02 §8.1, 01 §4.4).
 *
 * **Codes and [display].** [CodeDisplay.show] is called whenever a code is issued: by `pair-pin-start`, by
 * a Digest challenge that needs a new code, and when a Digest code is replaced after 4 failures.
 * [CodeDisplay.finish] is called once per code: `ACCEPTED` when PIN step 3 or Digest succeeds, `REJECTED`
 * when failures use the code up, `LOCKED_OUT` when a failure locks the client out (or starts the global
 * suspension, which ends every prompt on screen), and `CANCELLED` when the code expires (120 s), is
 * superseded by a new one, or belonged to a Digest prompt whose connection closed. A wrong attempt that
 * leaves tries keeps the code on screen and calls nothing. A client refused before any code is shown
 * (lockout, throttles) gets no call at all.
 *
 * **One prompt at a time** (02 §9.1). While a code is live for one client, any other client asking for one
 * (`pair-pin-start`, a Digest challenge) is answered 409 and closed, as while another client streams, and
 * nothing is shown; the code's own client may still replace it.
 *
 * Callbacks run on the calling thread after the internal lock is released, in the order the state changed
 * across all threads.
 *
 * @param mode Mode P or Mode D.
 * @param identitySeed the 32-byte per-install Ed25519 seed, used in Mode D only.
 * @param display where codes are shown.
 * @param onIdentityChanged called after a Mode P rotation.
 * @param clock monotonic nanoseconds; tests inject a fake.
 * @param random source of codes, salts, nonces and keys; tests inject a scripted one.
 */
internal class DefaultPairingService(
    private val mode: PinMode,
    identitySeed: ByteArray,
    private val display: CodeDisplay,
    private val onIdentityChanged: () -> Unit,
    private val clock: () -> Long = System::nanoTime,
    internal val random: SecureRandom = Crypto.secureRandom,
) : PairingService {
    private val lock = Any()

    /** Taken before [lock] by every call that may make callbacks; see [withEffects]. */
    private val callbackOrder = Any()
    private var identity: Ed25519KeyPair =
        if (mode == PinMode.DIGEST) Ed25519KeyPair.fromSeed(identitySeed) else Ed25519KeyPair.generate(random)
    private val trust = ArrayList<TrustedKey>()
    private var rotatePending = false
    private var identityUsed = false
    private var sessionActive = false
    private var lastActivity = clock()
    private var lastPinSuccess: Long? = null
    private val pinCodes = HashMap<String, IssuedCode>()
    private val digestCodes = HashMap<String, IssuedCode>()
    private val issuance = CodeIssuance()
    private val lockouts = LockoutTable()
    private val globalFailures = GlobalFailures()

    /**
     * A code on screen. Connections hold on to the instance between PIN steps so that a later step only
     * counts while this exact code is still the live one. [owner] is the connection whose Digest prompt
     * shows it (Digest codes only).
     */
    class IssuedCode internal constructor(
        /** The 4 digits. */
        val code: String,
        internal val client: InetAddress,
        internal val issuedAt: Long,
        internal val deviceId: String?,
    ) {
        internal var failures = 0
        internal var starts = 0
        internal var proofs = 0
        internal var owner: DefaultConnectionPairing? = null
    }

    /** A sender key in the Mode P session trust, and when its PIN step 3 completed. */
    private class TrustedKey(
        val key: ByteArray,
        val since: Long,
    )

    /** The access mode this service runs. */
    val pinMode: PinMode get() = mode

    override val identityPublicKey: ByteArray get() = synchronized(lock) { identity.publicKey }

    /** The current identity key pair, for signatures and the PIN step 3 reply. */
    fun identity(): Ed25519KeyPair = synchronized(lock) { identity }

    override fun pinStart(
        client: InetAddress,
        clientName: String?,
    ): PairingReply =
        withEffects { effects ->
            val now = clock()
            expire(now, effects)
            val key = codeKey(client)
            val existing = pinCodes[key]
            when {
                lockouts.isLocked(lockoutKey(client), now) -> {
                    REFUSED
                }

                // A repeated pair-pin-start right after the last one keeps the code that is on screen.
                existing != null && now - existing.issuedAt < AccessPolicy.MIN_CODE_INTERVAL_NANOS -> {
                    EMPTY_OK
                }

                promptOfAnother(key) -> {
                    BUSY
                }

                !mayIssue(key, now) -> {
                    REFUSED
                }

                else -> {
                    val code = issue(client, key, now, deviceId = null, effects)
                    pinCodes[key] = code
                    effects += { display.show(code.code, client, clientName) }
                    EMPTY_OK
                }
            }
        }

    override fun openConnection(client: InetAddress): ConnectionPairing = DefaultConnectionPairing(this, client)

    override fun sessionEnded() {
        withEffects { effects ->
            val now = clock()
            lastActivity = now
            sessionActive = false
            // Trust ends with the session even when the key change must wait (a code on screen, or a step 3
            // moments ago whose pair-verify uses its own connection's key), 02 §8.2 rule 5.
            forgetTrust()
            if (mode == PinMode.PIN_PAIRING && (identityUsed || rotatePending)) {
                if (mayRotate(now)) rotate(effects) else rotatePending = true
            }
        }
    }

    override val needsTicks: Boolean
        get() =
            synchronized(lock) {
                pinCodes.isNotEmpty() || digestCodes.isNotEmpty() || trust.isNotEmpty() || identityUsed || rotatePending
            }

    override fun tick(sessionActive: Boolean) {
        withEffects { effects ->
            val now = clock()
            this.sessionActive = sessionActive
            if (sessionActive) lastActivity = now
            expire(now, effects)
            expireTrust(now)
            lockouts.prune(now)
            val idle = identityUsed && now - lastActivity >= AccessPolicy.IDLE_ROTATION_NANOS
            val due = !sessionActive && (rotatePending || idle)
            if (due && mayRotate(now)) {
                rotate(effects)
            }
        }
    }

    // ---- Used by DefaultConnectionPairing -------------------------------------------------------------

    /**
     * `pair-setup-pin` step 1 for [client]: the live `pair-pin-start` code to run SRP against, or null when
     * there is none, the client is locked out, or the code has been started too often.
     */
    fun codeForPinStart(client: InetAddress): IssuedCode? =
        withEffects { effects ->
            val now = clock()
            expire(now, effects)
            val code = pinCodes[codeKey(client)]
            when {
                lockouts.isLocked(lockoutKey(client), now) || code == null -> null
                ++code.starts > AccessPolicy.SRP_STARTS_PER_CODE -> null
                else -> code
            }
        }

    /**
     * Whether `pair-setup-pin` step 2 may check a proof made against [code], reserving one of the code's
     * [AccessPolicy.SRP_FAILURES_PER_CODE] proof checks if so: it is still the live code for [client], the
     * client is not locked out, and fewer proofs than that have been checked against it. The reservation is
     * taken under the lock before the (slow) SRP check runs, so proofs sent on parallel connections cannot
     * all pass this check before the first failure is counted and get more guesses than the code allows.
     */
    fun reservePinProof(
        client: InetAddress,
        code: IssuedCode,
    ): Boolean =
        withEffects { effects ->
            val now = clock()
            expire(now, effects)
            val live = !lockouts.isLocked(lockoutKey(client), now) && pinCodes[codeKey(client)] === code
            if (live && code.proofs < AccessPolicy.SRP_FAILURES_PER_CODE) {
                code.proofs++
                true
            } else {
                false
            }
        }

    /** A wrong SRP proof from [client] against [code]: counts against the code, the client and globally. */
    fun pinProofFailed(
        client: InetAddress,
        code: IssuedCode,
    ) {
        withEffects { effects ->
            val now = clock()
            val key = codeKey(client)
            if (!countFailure(client, now, effects) && pinCodes[key] === code &&
                ++code.failures >= AccessPolicy.SRP_FAILURES_PER_CODE
            ) {
                pinCodes.remove(key)
                effects += { display.finish(client, PinResult.REJECTED) }
            }
        }
    }

    /**
     * PIN step 3 decrypted [senderKey]: if [code] is still the live code for [client] (not expired,
     * superseded, used up by failures on other connections, or dropped by a lockout since step 2), consume
     * it (its prompt finishes `ACCEPTED`), clear the client's failures and, in Mode P, trust the key.
     * Returns false, changing nothing, when the code is no longer live.
     */
    fun pinSucceeded(
        client: InetAddress,
        code: IssuedCode,
        senderKey: ByteArray,
    ): Boolean =
        withEffects { effects ->
            val now = clock()
            expire(now, effects)
            val key = codeKey(client)
            val live = !lockouts.isLocked(lockoutKey(client), now) && pinCodes[key] === code
            if (live) {
                lastActivity = now
                lastPinSuccess = now
                pinCodes.remove(key)
                issuance.paired(key, code.issuedAt)
                effects += { display.finish(client, PinResult.ACCEPTED) }
                lockouts.recordSuccess(lockoutKey(client))
                if (mode == PinMode.PIN_PAIRING) {
                    identityUsed = true
                    trust.removeAll { it.key.contentEquals(senderKey) }
                    if (trust.size >= MAX_TRUSTED_KEYS) trust.removeAt(0).key.fill(0)
                    trust.add(TrustedKey(senderKey.copyOf(), now))
                }
            }
            live
        }

    /**
     * Whether [key] is in the Mode P session trust: it completed PIN step 3 less than
     * [AccessPolicy.TRUST_LIFETIME_NANOS] ago or while a session is active, and no rotation or session end
     * has cleared it since.
     */
    fun isTrusted(key: ByteArray): Boolean =
        synchronized(lock) {
            expireTrust(clock())
            trust.any { it.key.contentEquals(key) }
        }

    /** Outcome of checking a Digest response. */
    enum class DigestCheck { ACCEPTED, WRONG, NO_CODE, LOCKED }

    /**
     * Checks a Digest response from [connection] against the code for ([client], [deviceId]) with
     * [matches]. A wrong response counts as a failure; the fourth uses the code up.
     */
    fun checkDigest(
        connection: DefaultConnectionPairing,
        client: InetAddress,
        deviceId: String?,
        matches: (String) -> Boolean,
    ): DigestCheck =
        withEffects { effects ->
            val now = clock()
            expire(now, effects)
            val key = codeKey(client)
            val code = digestCodes[key]?.takeIf { it.deviceId == deviceId }
            when {
                lockouts.isLocked(lockoutKey(client), now) -> {
                    DigestCheck.LOCKED
                }

                code == null -> {
                    DigestCheck.NO_CODE
                }

                matches(code.code) -> {
                    digestCodes.remove(key)
                    issuance.paired(key, code.issuedAt)
                    lockouts.recordSuccess(lockoutKey(client))
                    effects += { display.finish(client, PinResult.ACCEPTED) }
                    DigestCheck.ACCEPTED
                }

                countFailure(client, now, effects) -> {
                    DigestCheck.LOCKED
                }

                else -> {
                    code.owner = connection
                    if (++code.failures >= AccessPolicy.DIGEST_FAILURES_PER_CODE) {
                        digestCodes.remove(key)
                        effects += { display.finish(client, PinResult.REJECTED) }
                    }
                    DigestCheck.WRONG
                }
            }
        }

    /** Whether a Digest prompt could be put on screen. */
    enum class DigestPrompt {
        /** A code for the client is on screen. */
        SHOWN,

        /** Lockout, throttles or the global suspension: no code. */
        REFUSED,

        /** Another client's code is on screen (02 §9.1: one prompt at a time). */
        BUSY,
    }

    /**
     * Makes sure a Digest code for ([client], [deviceId]) is on screen, owned by [connection], issuing a
     * new one if needed (02 §6.1); see [DigestPrompt] for when it cannot.
     */
    fun ensureDigestCode(
        connection: DefaultConnectionPairing,
        client: InetAddress,
        deviceId: String?,
        clientName: String?,
    ): DigestPrompt =
        withEffects { effects ->
            val now = clock()
            expire(now, effects)
            val key = codeKey(client)
            val existing = digestCodes[key]
            when {
                lockouts.isLocked(lockoutKey(client), now) -> {
                    DigestPrompt.REFUSED
                }

                existing != null && existing.deviceId == deviceId -> {
                    existing.owner = connection
                    DigestPrompt.SHOWN
                }

                promptOfAnother(key) -> {
                    DigestPrompt.BUSY
                }

                !mayIssue(key, now) -> {
                    DigestPrompt.REFUSED
                }

                else -> {
                    val code = issue(client, key, now, deviceId, effects)
                    code.owner = connection
                    digestCodes[key] = code
                    effects += { display.show(code.code, client, clientName) }
                    DigestPrompt.SHOWN
                }
            }
        }

    /** [connection] closed: a Digest prompt it owns is cancelled. */
    fun connectionClosed(
        connection: DefaultConnectionPairing,
        client: InetAddress,
    ) {
        withEffects { effects ->
            val key = codeKey(client)
            if (digestCodes[key]?.owner === connection) {
                digestCodes.remove(key)
                effects += { display.finish(client, PinResult.CANCELLED) }
            }
        }
    }

    // ---- Internals (call with the lock held) ----------------------------------------------------------

    /**
     * Mode P only, never while a code is on screen (02 §8.2 rule 5), and not within
     * [AccessPolicy.ROTATION_GRACE_NANOS] of a PIN step 3: the sender checks the pair-verify signature that
     * follows against the key step 3 gave it, so the identity must not change in between.
     */
    private fun mayRotate(now: Long): Boolean {
        val success = lastPinSuccess
        return mode == PinMode.PIN_PAIRING &&
            pinCodes.isEmpty() &&
            (success == null || now - success >= AccessPolicy.ROTATION_GRACE_NANOS)
    }

    /**
     * Whether [key] may get a new code (02 §9.1): not within [AccessPolicy.MIN_CODE_INTERVAL_NANOS] of its
     * code that is still on screen (once that prompt is over, at once), within its budget and the global
     * cap, and not during a global suspension.
     */
    private fun mayIssue(
        key: String,
        now: Long,
    ): Boolean {
        val live = pinCodes[key] ?: digestCodes[key]
        val spaced = live == null || now - live.issuedAt >= AccessPolicy.MIN_CODE_INTERVAL_NANOS
        return spaced && !globalFailures.isSuspended(now) && issuance.mayIssue(key, now)
    }

    /**
     * Whether a code for a client other than [key] is on screen. Only one prompt is shown at a time, so a
     * second client can neither replace the prompt a person is reading nor put a look-alike next to it.
     */
    private fun promptOfAnother(key: String): Boolean =
        pinCodes.keys.any { it != key } || digestCodes.keys.any { it != key }

    /**
     * A new code for [client]. Its code still on screen, PIN or Digest, is superseded first (finishing
     * `CANCELLED`), so an address has one code at a time and each `finish` is for the prompt on screen.
     */
    private fun issue(
        client: InetAddress,
        key: String,
        now: Long,
        deviceId: String?,
        effects: MutableList<() -> Unit>,
    ): IssuedCode {
        val pin = pinCodes.remove(key)
        val digest = digestCodes.remove(key)
        if (pin != null || digest != null) effects += { display.finish(client, PinResult.CANCELLED) }
        issuance.issued(key, now)
        return IssuedCode(newCode(random), client, now, deviceId)
    }

    /**
     * Counts one failure for [client]. When it locks the client out, the client's codes are dropped and
     * its prompt finishes `LOCKED_OUT`. When it starts a global suspension (02 §9.2), every code on screen
     * is dropped the same way, which is how the screen "says so". Returns true in either case: the code
     * the failure was made against is gone.
     */
    private fun countFailure(
        client: InetAddress,
        now: Long,
        effects: MutableList<() -> Unit>,
    ): Boolean {
        val suspended = globalFailures.record(now)
        val lockKey = lockoutKey(client)
        val locked = lockouts.recordFailure(lockKey, now)
        when {
            suspended -> dropCodes(client, effects) { true }
            locked -> dropCodes(client, effects) { lockoutKey(it) == lockKey }
        }
        return locked || suspended
    }

    /** Drops the codes of clients matching [which] and finishes their prompts, and [client]'s, `LOCKED_OUT`. */
    private fun dropCodes(
        client: InetAddress,
        effects: MutableList<() -> Unit>,
        which: (InetAddress) -> Boolean,
    ) {
        val dropped = (pinCodes.values + digestCodes.values).filter { which(it.client) }
        pinCodes.values.removeAll { which(it.client) }
        digestCodes.values.removeAll { which(it.client) }
        val clients = (dropped.map { it.client } + client).distinctBy { codeKey(it) }
        for (each in clients) effects += { display.finish(each, PinResult.LOCKED_OUT) }
    }

    private fun expire(
        now: Long,
        effects: MutableList<() -> Unit>,
    ) {
        for (codes in listOf(pinCodes, digestCodes)) {
            val expired = codes.values.filter { now - it.issuedAt >= AccessPolicy.CODE_LIFETIME_NANOS }
            for (code in expired) {
                codes.remove(codeKey(code.client))
                effects += { display.finish(code.client, PinResult.CANCELLED) }
            }
        }
    }

    private fun rotate(effects: MutableList<() -> Unit>) {
        identity = Ed25519KeyPair.generate(random)
        forgetTrust()
        identityUsed = false
        rotatePending = false
        lastActivity = clock()
        effects += onIdentityChanged
    }

    private fun forgetTrust() {
        trust.forEach { it.key.fill(0) }
        trust.clear()
    }

    /** Drops trust entries older than [AccessPolicy.TRUST_LIFETIME_NANOS], unless a session is active. */
    private fun expireTrust(now: Long) {
        if (sessionActive) return
        trust.removeAll { entry ->
            (now - entry.since >= AccessPolicy.TRUST_LIFETIME_NANOS).also { if (it) entry.key.fill(0) }
        }
    }

    /**
     * Runs [block] under the lock, then the callbacks it queued, outside the lock but inside [callbackOrder]:
     * callbacks of different calls are made in the order the calls changed the state, so a prompt's
     * `finish` can never reach the screen after the `show` of the prompt that replaced it.
     */
    private inline fun <T> withEffects(block: (MutableList<() -> Unit>) -> T): T =
        synchronized(callbackOrder) {
            val effects = ArrayList<() -> Unit>()
            val result = synchronized(lock) { block(effects) }
            for (effect in effects) effect()
            result
        }

    private companion object {
        /** Bound on the Mode P trust set; one sender normally adds one key. */
        const val MAX_TRUSTED_KEYS = 16

        val EMPTY_OK = PairingReply.Ok(ByteArray(0), null)
        val REFUSED = PairingReply.Error(PairingStatus.CONNECTION_AUTHORIZATION_REQUIRED, close = false)

        /** Another client's code is on screen: the reply to a concurrent session (01 §3.1). */
        val BUSY = PairingReply.Error(PairingStatus.CONFLICT, close = true)
    }
}

/** RTSP status codes this package answers with (01 §3.4). */
internal object PairingStatus {
    /** Malformed request. */
    const val BAD_REQUEST: Int = 400

    /** Digest challenge, or refusal under lockout. */
    const val UNAUTHORIZED: Int = 401

    /** Another client has the receiver: it streams, or its code is on screen. */
    const val CONFLICT: Int = 409

    /** Pairing failure or a request that needs pairing first. */
    const val CONNECTION_AUTHORIZATION_REQUIRED: Int = 470
}
