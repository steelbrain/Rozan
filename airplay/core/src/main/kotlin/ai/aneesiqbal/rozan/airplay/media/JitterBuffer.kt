package ai.aneesiqbal.rozan.airplay.media

/**
 * The audio jitter buffer of 03c §4.3: a ring of [MediaLimits.JITTER_SLOTS] entries indexed by
 * `seq & (slots − 1)`, each with a [MediaLimits.MAX_AUDIO_PAYLOAD_BYTES] payload slot in [data]. All
 * storage is allocated in the constructor. Sequence numbers compare with 16-bit serial arithmetic.
 *
 * While the buffer is not empty, [head] is the next sequence number to play and [tail] the highest one
 * received, and the [tail] entry is always present. When playout drains it, the sequence number after the
 * last one played becomes the floor: anything before it is late.
 *
 * Not thread-safe; the audio stream guards it with its lock.
 */
internal class JitterBuffer {
    private val mask = MediaLimits.JITTER_SLOTS - 1
    private val present = BooleanArray(MediaLimits.JITTER_SLOTS)
    private val playable = BooleanArray(MediaLimits.JITTER_SLOTS)
    private val sequences = IntArray(MediaLimits.JITTER_SLOTS)
    private val timestamps = IntArray(MediaLimits.JITTER_SLOTS)
    private val lengths = IntArray(MediaLimits.JITTER_SLOTS)

    /** Payload storage: entry `i` occupies `[slotOffset(i), slotOffset(i) + length(i))`. */
    val data: ByteArray = ByteArray(MediaLimits.JITTER_SLOTS * MediaLimits.MAX_AUDIO_PAYLOAD_BYTES)

    /** Whether no entry is stored. */
    var isEmpty: Boolean = true
        private set

    /** Next sequence number to play; meaningful while not [isEmpty]. */
    var head: Int = 0
        private set

    /** Highest sequence number received; meaningful while not [isEmpty]. */
    var tail: Int = 0
        private set

    private var floorValid = false
    private var floor = 0

    /** First sequence number of the gap the last [insert] opened, if [gapCount] > 0. */
    var gapFirst: Int = 0
        private set

    /** Packets missing between the old tail and the packet the last [insert] stored; 0 for none. */
    var gapCount: Int = 0
        private set

    /** Whether the last [insert] reset the buffer because the sequence number jumped out of the window. */
    var wasReset: Boolean = false
        private set

    /**
     * Stores the packet [sequence] with RTP time [timestamp] and payload `source[offset, offset + length)`
     * (at most [MediaLimits.MAX_AUDIO_PAYLOAD_BYTES]), applying the rules of 03c §4.3.
     *
     * @return the entry index, or [LATE] or [DUPLICATE] when the packet was not stored.
     */
    fun insert(
        sequence: Int,
        timestamp: Int,
        source: ByteArray,
        offset: Int,
        length: Int,
    ): Int {
        require(length in 0..MediaLimits.MAX_AUDIO_PAYLOAD_BYTES) { "payload too long" }
        gapCount = 0
        wasReset = false
        if (isEmpty) {
            if (!startAt(sequence)) return LATE
        } else {
            val distance = serial16(sequence, head)
            if (distance < -MediaLimits.JITTER_SLOTS || distance >= MediaLimits.JITTER_SLOTS) {
                // Far outside the window: the sender restarted or jumped (03c §4.3 step 3, 03 §8.3).
                clear()
                startAt(sequence)
                wasReset = true
            } else if (distance < 0) {
                return LATE
            }
        }
        val index = sequence and mask
        if (present[index] && sequences[index] == sequence) return DUPLICATE
        source.copyInto(data, slotOffset(index), offset, offset + length)
        present[index] = true
        playable[index] = true
        sequences[index] = sequence
        timestamps[index] = timestamp
        lengths[index] = length
        val ahead = serial16(sequence, tail)
        if (ahead > 1) {
            gapFirst = (tail + 1) and SEQUENCE_MASK
            gapCount = ahead - 1
        }
        if (ahead > 0) tail = sequence
        return index
    }

    /** Whether the [head] entry is present. */
    fun isHeadPresent(): Boolean = !isEmpty && present[head and mask]

    /** Whether the entry for [sequence] is present. */
    fun isPresent(sequence: Int): Boolean {
        val index = sequence and mask
        return !isEmpty && present[index] && sequences[index] == sequence
    }

    /** Entry index of [head]. */
    fun headIndex(): Int = head and mask

    /** RTP time of entry [index]. */
    fun timestamp(index: Int): Int = timestamps[index]

    /** Payload length of entry [index]. */
    fun length(index: Int): Int = lengths[index]

    /** Whether entry [index] goes to the decoder (false for no-data packets and undecryptable ones). */
    fun isPlayable(index: Int): Boolean = playable[index]

    /** Excludes entry [index] from playout while keeping its place in the sequence. */
    fun markUnplayable(index: Int) {
        playable[index] = false
    }

    /** Offset of entry [index] in [data]. */
    fun slotOffset(index: Int): Int = index * MediaLimits.MAX_AUDIO_PAYLOAD_BYTES

    /**
     * RTP time of the packet [sequence] estimated from the [tail] entry, assuming [framesPerPacket] frames
     * per packet. Exact for present entries of a regular stream; used for missing ones.
     */
    fun estimateTimestamp(
        sequence: Int,
        framesPerPacket: Int,
    ): Int = timestamps[tail and mask] - serial16(tail, sequence) * framesPerPacket

    /** Present entries after [head], counting at most [limit]. */
    fun presentAfterHead(limit: Int): Int {
        if (isEmpty) return 0
        var count = 0
        val span = serial16(tail, head)
        var k = 1
        while (k <= span && count < limit) {
            if (present[(head + k) and mask]) count++
            k++
        }
        return count
    }

    /** Drops the [head] entry (played or given up) and moves to the next sequence number. */
    fun advance() {
        if (isEmpty) return
        present[head and mask] = false
        if (head == tail) {
            isEmpty = true
            floor = (head + 1) and SEQUENCE_MASK
            floorValid = true
        } else {
            head = (head + 1) and SEQUENCE_MASK
        }
    }

    /**
     * FLUSH (03 §6.2): drops every entry whose RTP time is before [nextTimestamp] and whose sequence number
     * is before [nextSequence]; a missing value does not restrict. With neither, drops everything. The
     * stream continues at [nextSequence]: earlier packets that arrive later are late.
     */
    fun flush(
        nextSequence: Int?,
        nextTimestamp: Int?,
    ) {
        if (nextSequence == null && nextTimestamp == null) {
            clear()
            return
        }
        if (!isEmpty) dropFlushed(nextSequence, nextTimestamp)
        if (isEmpty) {
            clear()
            if (nextSequence != null) {
                floor = nextSequence
                floorValid = true
            }
            return
        }
        // Wait for the packets between the RTP-Info sequence number and the first one kept.
        val waitFor = if (nextSequence == null) 0 else serial16(head, nextSequence)
        if (nextSequence != null && waitFor in 1 until MediaLimits.JITTER_SLOTS - serial16(tail, head)) {
            head = nextSequence
        }
    }

    /** Drops the entries [flush] drops; leaves [head] and [tail] on the first and last kept, or empties. */
    private fun dropFlushed(
        nextSequence: Int?,
        nextTimestamp: Int?,
    ) {
        var first = -1
        var last = -1
        for (k in 0..serial16(tail, head)) {
            val sequence = (head + k) and SEQUENCE_MASK
            val index = sequence and mask
            if (!present[index]) continue
            val sequenceBefore = nextSequence == null || serial16(sequence, nextSequence) < 0
            val timeBefore = nextTimestamp == null || timestamps[index] - nextTimestamp < 0
            if (sequenceBefore && timeBefore) {
                present[index] = false
            } else {
                if (first < 0) first = sequence
                last = sequence
            }
        }
        if (first < 0) {
            isEmpty = true
        } else {
            head = first
            tail = last
        }
    }

    /** Drops everything, including the floor. */
    fun clear() {
        present.fill(false)
        isEmpty = true
        floorValid = false
        gapCount = 0
    }

    private fun startAt(sequence: Int): Boolean {
        if (floorValid) {
            val distance = serial16(sequence, floor)
            if (distance < 0 && distance >= -MediaLimits.JITTER_SLOTS) return false
            floorValid = false
            if (distance in 0 until MediaLimits.JITTER_SLOTS) {
                // Packets between the floor and this one are missing: wait for them like any gap.
                head = floor
                tail = (floor - 1) and SEQUENCE_MASK
                isEmpty = false
                return true
            }
        }
        head = sequence
        tail = sequence
        isEmpty = false
        return true
    }

    /** Codes and helpers. */
    companion object {
        /** [insert] result: the packet is before [head] (or the floor). */
        const val LATE: Int = -1

        /** [insert] result: the entry is already present. */
        const val DUPLICATE: Int = -2

        private const val SEQUENCE_MASK = 0xFFFF

        /** `sdiff16(a, b)`: signed distance from [b] to [a] in 16-bit serial arithmetic. */
        fun serial16(
            a: Int,
            b: Int,
        ): Int = ((a - b) and SEQUENCE_MASK).toShort().toInt()
    }
}
