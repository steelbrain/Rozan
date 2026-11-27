package ai.aneesiqbal.rozan.airplay.pairing

import ai.aneesiqbal.rozan.airplay.receiver.PinResult
import java.net.InetAddress

/*
 * Contract between the control layer (session package) and pairing/access control (this package),
 * implementing docs/protocol/02-pairing.md §3-§6, §8 and §9.
 *
 * The control layer parses RTSP, routes requests, answers 409 while another client streams (01 §3.1)
 * before calling in here, and turns a PairingReply into an RTSP response. This package owns the receiver
 * identity, codes, SRP, pair-verify, Digest, rate limits and lockouts, and decides when the identity
 * rotates (Mode P).
 */

/** How codes reach the screen. The control layer maps these to ReceiverEvents. Must not block. */
internal interface CodeDisplay {
    /** Show [code] (4 digits) for [client]; [clientName] is the untrusted name the sender gave, if any. */
    fun show(
        code: String,
        client: InetAddress,
        clientName: String?,
    )

    /** The code prompt for [client] is over with [result]. */
    fun finish(
        client: InetAddress,
        result: PinResult,
    )
}

/** The control layer's answer to a pairing request. */
internal sealed interface PairingReply {
    /** 200 with [body] (empty for none) of [contentType] (null for none). */
    class Ok(
        val body: ByteArray,
        val contentType: String?,
    ) : PairingReply

    /** An error status (400, 470, ...); close the connection after replying when [close] is set. */
    class Error(
        val status: Int,
        val close: Boolean,
    ) : PairingReply
}

/** Outcome of the access check on a session SETUP (02 §6, §8.2 rule 4). */
internal sealed interface SetupAuthorization {
    /** Run the SETUP. */
    object Authorized : SetupAuthorization

    /** Reply 401 with this `WWW-Authenticate` header value. */
    class Challenge(
        val wwwAuthenticate: String,
    ) : SetupAuthorization

    /** Reply with [status] (a plain 401 under lockout) and show nothing; close when [close] is set. */
    class Refused(
        val status: Int,
        val close: Boolean,
    ) : SetupAuthorization
}

/**
 * Receiver-wide pairing state: the identity key, codes keyed by client, Mode P session trust, lockouts.
 * Thread-safe.
 */
internal interface PairingService {
    /** The current receiver Ed25519 public key (32 bytes): TXT `pk`, `/info` `pk`. */
    val identityPublicKey: ByteArray

    /** `POST /pair-pin-start` (02 §5.1) from [client]. Shows a code through [CodeDisplay] on success. */
    fun pinStart(
        client: InetAddress,
        clientName: String?,
    ): PairingReply

    /** Per-connection state for a new control connection from [client]. */
    fun openConnection(client: InetAddress): ConnectionPairing

    /**
     * The streaming session owned by some client ended (full TEARDOWN, or its last control connection
     * closed). In Mode P this rotates the identity unless a PIN flow is in progress (then it rotates when
     * that flow ends or times out).
     */
    fun sessionEnded()

    /**
     * Housekeeping, called every few seconds: expires codes and lockout records, and in Mode P rotates an
     * identity that has been idle for 60 s (02 §8.2 rule 5) unless [sessionActive].
     */
    fun tick(sessionActive: Boolean)

    /**
     * Whether a later [tick] has anything to do: a code is on screen, trusted keys may expire, or an identity
     * waits for its rotation. While false (and no connection is open) the receiver does not tick at all;
     * lockouts and rate limits are checked against the clock when the next request asks.
     */
    val needsTicks: Boolean
}

/** Pairing state of one control connection. Used from that connection's thread only. */
internal interface ConnectionPairing : AutoCloseable {
    /** `POST /pair-setup` (02 §3). */
    fun pairSetup(body: ByteArray): PairingReply

    /** `POST /pair-setup-pin`, any of the three steps (02 §5.3-§5.5); [body] is the raw bplist. */
    fun pairSetupPin(body: ByteArray): PairingReply

    /** `POST /pair-verify`, either step (02 §4). */
    fun pairVerify(body: ByteArray): PairingReply

    /**
     * Access check for the session SETUP (the one with `ekey`/`eiv`). [authorization] is the
     * `Authorization` header, [uri] the request URI, [senderDeviceId] and [senderName] the SETUP
     * `deviceID` and `name` (untrusted, for code keying and display).
     */
    fun authorizeSetup(
        method: String,
        uri: String,
        authorization: String?,
        senderDeviceId: String?,
        senderName: String?,
    ): SetupAuthorization

    /**
     * Whether media requests may run (01 §5): Digest succeeded on this connection, or PIN pairing and
     * pair-verify with the PIN-authenticated key did (02 §8.2 rule 4).
     */
    val isAuthenticated: Boolean

    /** The pair-verify X25519 shared secret (32 bytes) once this connection is VERIFIED, else null. */
    val verifiedSharedSecret: ByteArray?

    /**
     * A whole-session TEARDOWN (01 §6.13): forget this connection's authentication and pairing state, so a
     * new session on it needs a new code like a new connection would (one code buys one session, 02 §8.1).
     * Zeroes the shared secret. A code already on screen for the connection is left alone.
     */
    fun endAuthentication()

    /** The connection closed: cancel any code shown for it, zero secrets. Idempotent. */
    override fun close()
}
