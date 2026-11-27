package ai.aneesiqbal.rozan.airplay.media

import kotlin.math.pow

/** The `SET_PARAMETER volume:` mapping from dB to linear gain (docs/protocol/03-media-streams.md §6.1). */
internal object Volume {
    /** The value senders use for mute. */
    const val MUTE_DB: Float = -144f

    /** Quietest audible value; anything at or below it is mute. */
    const val MIN_DB: Float = -30f

    /** Full volume. */
    const val MAX_DB: Float = 0f

    /**
     * [db] clamped as the spec says: above 0 becomes 0, below -30 (other than the mute value -144) becomes -30.
     * Null when [db] is not finite, which the spec says to ignore.
     */
    fun normalizeDb(db: Float): Float? =
        when {
            !db.isFinite() -> null
            db == MUTE_DB -> MUTE_DB
            db > MAX_DB -> MAX_DB
            db < MIN_DB -> MIN_DB
            else -> db
        }

    /**
     * Linear gain 0..1 for [db] with the "flat" profile: -144 and anything at or below -30 dB mute, otherwise
     * `10^(dB/20)`. Null when [db] is not finite.
     */
    fun linearFromDb(db: Float): Float? {
        val value = normalizeDb(db) ?: return null
        if (value <= MIN_DB) return 0f
        return 10.0.pow(value / 20.0).toFloat()
    }
}
