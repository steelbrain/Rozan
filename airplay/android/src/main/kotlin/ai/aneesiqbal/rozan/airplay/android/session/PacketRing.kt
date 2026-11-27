package ai.aneesiqbal.rozan.airplay.android.session

import java.nio.ByteBuffer

/**
 * A bounded FIFO of variable-size packets (each with a timestamp) in one byte
 * array. Packets are stored contiguously, never split; when the tail reaches
 * the end, live packets are compacted to the front. Storage starts at
 * [initialBytes] and doubles up to [maxBytes]; after that, [offer] refuses.
 *
 * Not thread-safe: callers hold their own lock. No allocation except growth.
 */
internal class PacketRing(
    private val maxBytes: Int,
    private val maxPackets: Int,
    initialBytes: Int = maxBytes,
) {
    private var data = ByteArray(initialBytes.coerceIn(1, maxBytes))
    private val offsets = IntArray(maxPackets)
    private val lengths = IntArray(maxPackets)
    private val timestamps = LongArray(maxPackets)
    private val flags = BooleanArray(maxPackets)
    private var head = 0
    private var count = 0
    private var tail = 0

    /** Number of queued packets. */
    val size: Int get() = count

    /** True when no packets are queued. */
    val isEmpty: Boolean get() = count == 0

    /** Bytes currently allocated for packet storage. */
    val capacityBytes: Int get() = data.size

    /**
     * Appends a copy of `src[offset, offset + length)`. Returns false, leaving
     * the ring unchanged, when the packet does not fit in the bounds.
     */
    fun offer(
        src: ByteArray,
        offset: Int,
        length: Int,
        timestamp: Long,
        flag: Boolean = false,
    ): Boolean {
        val acceptable = length in 0..maxBytes && count < maxPackets
        if (!acceptable || !ensureRoom(length)) return false
        System.arraycopy(src, offset, data, tail, length)
        val slot = (head + count) % maxPackets
        offsets[slot] = tail
        lengths[slot] = length
        timestamps[slot] = timestamp
        flags[slot] = flag
        tail += length
        count++
        return true
    }

    /** Length of the oldest packet. Requires a non-empty ring. */
    fun peekLength(): Int = lengths[checkedHead()]

    /** Timestamp of the oldest packet. Requires a non-empty ring. */
    fun peekTimestamp(): Long = timestamps[checkedHead()]

    /** Flag stored with the oldest packet. Requires a non-empty ring. */
    fun peekFlag(): Boolean = flags[checkedHead()]

    /** Timestamp of the newest packet. Requires a non-empty ring. */
    fun lastTimestamp(): Long {
        check(count > 0) { "empty" }
        return timestamps[(head + count - 1) % maxPackets]
    }

    /** Copies the oldest packet into [dst] at [dstOffset]; returns its length. */
    fun copyPeek(
        dst: ByteArray,
        dstOffset: Int = 0,
    ): Int {
        val slot = checkedHead()
        val length = lengths[slot]
        System.arraycopy(data, offsets[slot], dst, dstOffset, length)
        return length
    }

    /** Puts the oldest packet into [dst] at its position; returns its length. */
    fun copyPeek(dst: ByteBuffer): Int {
        val slot = checkedHead()
        val length = lengths[slot]
        dst.put(data, offsets[slot], length)
        return length
    }

    /** Removes the oldest packet. */
    fun poll() {
        checkedHead()
        head = (head + 1) % maxPackets
        count--
        if (count == 0) {
            head = 0
            tail = 0
        }
    }

    /** Removes every packet (storage is kept). */
    fun clear() {
        head = 0
        count = 0
        tail = 0
    }

    private fun checkedHead(): Int {
        check(count > 0) { "empty" }
        return head
    }

    private fun ensureRoom(length: Int): Boolean {
        if (tail + length > data.size) compact()
        val needed = tail + length
        if (needed > data.size && data.size < maxBytes) {
            var newSize = data.size
            while (newSize < needed && newSize < maxBytes) {
                newSize = (newSize.toLong() * 2).coerceAtMost(maxBytes.toLong()).toInt()
            }
            if (newSize >= needed) data = data.copyOf(newSize)
        }
        return needed <= data.size
    }

    private fun compact() {
        if (count == 0) {
            tail = 0
            return
        }
        val start = offsets[head]
        if (start == 0) return
        System.arraycopy(data, start, data, 0, tail - start)
        for (i in 0 until count) {
            val slot = (head + i) % maxPackets
            offsets[slot] -= start
        }
        tail -= start
    }
}
