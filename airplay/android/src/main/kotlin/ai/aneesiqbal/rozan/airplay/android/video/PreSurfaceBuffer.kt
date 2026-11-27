package ai.aneesiqbal.rozan.airplay.android.video

import ai.aneesiqbal.rozan.airplay.android.session.PacketRing
import java.nio.ByteBuffer

/**
 * Holds the access units a decoder will need once it can start: everything
 * since the most recent key frame. Used while no surface exists yet (the
 * mirroring activity is still coming up) and while a freshly started decoder
 * catches up.
 *
 * A key frame discards everything before it, since nothing after it refers
 * back. Frames before the first key frame are useless and dropped. If the
 * bounds are hit, the buffer empties and waits for the next key frame
 * instead of keeping a run of frames that can no longer be decoded.
 */
internal class PreSurfaceBuffer(
    maxBytes: Int = DEFAULT_MAX_BYTES,
    maxUnits: Int = DEFAULT_MAX_UNITS,
    initialBytes: Int = DEFAULT_INITIAL_BYTES,
) {
    private val ring = PacketRing(maxBytes, maxUnits, initialBytes)

    /** True until a key frame has been stored; non-key frames are dropped meanwhile. */
    var awaitingKeyFrame: Boolean = true
        private set

    /** Access units discarded so far (before a key frame, or on overflow). */
    var droppedUnits: Long = 0L
        private set

    /** True when nothing is buffered. */
    val isEmpty: Boolean get() = ring.isEmpty

    /** Number of buffered access units. */
    val size: Int get() = ring.size

    /** Bytes currently allocated (grows on demand up to the bound). */
    val capacityBytes: Int get() = ring.capacityBytes

    /** Stores a copy of one access unit; returns false when it was dropped. */
    fun add(
        data: ByteArray,
        offset: Int,
        length: Int,
        presentationNanos: Long,
        keyFrame: Boolean,
    ): Boolean {
        if (keyFrame) {
            ring.clear()
            awaitingKeyFrame = false
        } else if (awaitingKeyFrame) {
            droppedUnits++
            return false
        }
        if (ring.offer(data, offset, length, presentationNanos, keyFrame)) return true
        droppedUnits += ring.size + 1L
        ring.clear()
        awaitingKeyFrame = true
        return false
    }

    /** Empties the buffer; with [awaitKeyFrame], frames are dropped until the next key frame. */
    fun reset(awaitKeyFrame: Boolean) {
        ring.clear()
        awaitingKeyFrame = awaitKeyFrame
    }

    /** Length in bytes of the oldest access unit. */
    fun peekLength(): Int = ring.peekLength()

    /** Presentation time of the oldest access unit. */
    fun peekPresentationNanos(): Long = ring.peekTimestamp()

    /** Whether the oldest access unit is a key frame. */
    fun peekKeyFrame(): Boolean = ring.peekFlag()

    /** Presentation time of the newest access unit. */
    fun lastPresentationNanos(): Long = ring.lastTimestamp()

    /** Copies the oldest access unit into [dst]; returns its length. */
    fun copyPeek(dst: ByteBuffer): Int = ring.copyPeek(dst)

    /** Copies the oldest access unit into [dst]; returns its length. */
    fun copyPeek(dst: ByteArray): Int = ring.copyPeek(dst)

    /** Removes the oldest access unit. */
    fun poll() = ring.poll()

    companion object {
        /** Bound on buffered bytes: several seconds of 1080p mirroring at typical bitrates. */
        const val DEFAULT_MAX_BYTES = 16 * 1024 * 1024

        /** Bound on buffered access units: 20 s at 60 fps. */
        const val DEFAULT_MAX_UNITS = 1200

        /** Storage allocated up front; grows by doubling only while frames pile up. */
        const val DEFAULT_INITIAL_BYTES = 1024 * 1024
    }
}
