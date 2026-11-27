package ai.aneesiqbal.rozan.airplay.android.session

/**
 * Ends the AirPlay session the user is looking at, e.g. when they press Back
 * on the mirroring screen. `ReceiverService` installs one next to the running
 * receiver that calls `AirPlayReceiver.disconnectAll()` off the main thread;
 * the sender may connect again and is asked for a new code.
 */
internal fun interface SessionEnder {
    /** Tears down the current mirroring/audio session, if any. Called on the main thread. */
    fun endCurrentSession()

    companion object {
        /** Does nothing: no receiver is running. */
        val NONE: SessionEnder = SessionEnder { }
    }
}
