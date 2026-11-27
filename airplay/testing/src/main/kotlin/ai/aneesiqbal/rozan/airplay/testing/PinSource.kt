package ai.aneesiqbal.rozan.airplay.testing

/**
 * Where the test sender gets the PIN the receiver shows, playing the user who reads it off the TV and
 * types it. Called once per PIN pairing, after `/pair-pin-start` succeeded; it may block until the code
 * is visible (an instrumented test reads it off the screen).
 */
public fun interface PinSource {
    /** The four digits on screen. */
    public fun pin(): String
}
