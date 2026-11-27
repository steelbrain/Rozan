package ai.aneesiqbal.rozan.airplay.receiver

/**
 * How senders prove they can see the TV (docs/protocol/02-pairing.md §1.2, §8). In both modes a fresh
 * 4-digit code is shown for every connection and nothing about senders is remembered.
 */
public enum class PinMode {
    /**
     * Mode P: legacy SRP PIN pairing, with a new receiver identity key after every session so the sender
     * cannot reuse a pairing. Senders that skip PIN pairing get the [DIGEST] challenge instead.
     */
    PIN_PAIRING,

    /** Mode D: the code is entered as the AirPlay password and checked with RTSP Digest on the first SETUP. */
    DIGEST,
}
