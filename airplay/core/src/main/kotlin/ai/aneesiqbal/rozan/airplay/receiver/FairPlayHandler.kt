package ai.aneesiqbal.rozan.airplay.receiver

import ai.aneesiqbal.rozan.airplay.ProtocolException

/**
 * The receiver side of FairPlay SAP for one control connection (docs/protocol/04-fairplay.md).
 *
 * `:airplay:core` does not depend on the FairPlay implementation; the app or dev server adapts it to this
 * interface. One instance serves one control connection: [handshake] and [decryptKey] depend on the state
 * left by the earlier calls. Calls come from that connection's thread, one at a time.
 *
 * Implementations report unacceptable input by throwing [FairPlayFailure] (wrapping their own exception).
 */
public interface FairPlayHandler {
    /** `POST /fp-setup` step 1: the 16-byte request (starts with `FPLY`) to the 142-byte reply. */
    public fun setup(request: ByteArray): ByteArray

    /** `POST /fp-setup` step 2: the 164-byte request to the 32-byte reply. */
    public fun handshake(request: ByteArray): ByteArray

    /** The 72-byte SETUP `ekey`, or classic ANNOUNCE `fpaeskey` (01 §6.17), to the 16-byte AES key it wraps. */
    public fun decryptKey(encryptedKey: ByteArray): ByteArray
}

/** Creates one [FairPlayHandler] per control connection. Must be thread-safe. */
public fun interface FairPlayHandlerFactory {
    /** A handler with fresh state. */
    public fun create(): FairPlayHandler
}

/** Stands in when the app gave no FairPlay implementation: every step fails, so senders cannot connect. */
internal object NoFairPlay : FairPlayHandlerFactory, FairPlayHandler {
    override fun create(): FairPlayHandler = this

    override fun setup(request: ByteArray): ByteArray = unavailable()

    override fun handshake(request: ByteArray): ByteArray = unavailable()

    override fun decryptKey(encryptedKey: ByteArray): ByteArray = unavailable()

    private fun unavailable(): Nothing =
        throw FairPlayFailure("no FairPlay implementation was given to AirPlayReceiver")
}

/**
 * FairPlay rejected its input (wrong length, unknown version, failed decryption).
 *
 * @param message what was wrong.
 * @param cause the implementation's own exception, if any.
 */
public class FairPlayFailure(
    message: String,
    cause: Throwable? = null,
) : ProtocolException(message, cause)
