package ai.aneesiqbal.rozan.airplay.alac

import java.io.ByteArrayOutputStream

/** MSB-first bit writer for building ALAC packets by hand in tests. */
class BitWriter {
    private val bytes = ByteArrayOutputStream()
    private var current = 0
    private var used = 0

    /** Bits written so far. */
    val bitCount: Int
        get() = bytes.size() * 8 + used

    /** Writes the low [count] bits (0..32) of [value]. */
    fun write(
        value: Int,
        count: Int,
    ): BitWriter {
        for (i in count - 1 downTo 0) bit((value ushr i) and 1)
        return this
    }

    /** Copies bits [from] until [to] of [source]. */
    fun copyBits(
        source: ByteArray,
        from: Int,
        to: Int,
    ): BitWriter {
        for (i in from until to) bit(bitAt(source, i))
        return this
    }

    /** Pads with zero bits to a byte boundary and returns the bytes. */
    fun toByteArray(): ByteArray {
        val copy = ByteArrayOutputStream()
        copy.write(bytes.toByteArray())
        if (used > 0) copy.write(current shl (8 - used))
        return copy.toByteArray()
    }

    private fun bit(b: Int) {
        current = (current shl 1) or b
        used++
        if (used == 8) {
            bytes.write(current)
            current = 0
            used = 0
        }
    }
}

/** Bit [index] (MSB first) of [bytes]. */
fun bitAt(
    bytes: ByteArray,
    index: Int,
): Int = (bytes[index ushr 3].toInt() ushr (7 - (index and 7))) and 1

/** Unsigned value of [count] bits at bit [index] of [bytes]. */
fun bitsAt(
    bytes: ByteArray,
    index: Int,
    count: Int,
): Int {
    var value = 0
    for (i in index until index + count) value = (value shl 1) or bitAt(bytes, i)
    return value
}

/** ALAC element types. */
object Element {
    const val SCE = 0
    const val CPE = 1
    const val CCE = 2
    const val LFE = 3
    const val DSE = 4
    const val PCE = 5
    const val FIL = 6
    const val END = 7
}

/**
 * Writes an uncompressed ("escape") element of [tag] holding [channels] (one array for SCE/LFE, two for CPE) as
 * [bitDepth]-bit samples. With [frameCount] the header carries the partial-frame flag and that count.
 */
fun BitWriter.uncompressedElement(
    tag: Int,
    bitDepth: Int,
    channels: List<IntArray>,
    frameCount: Int? = null,
): BitWriter {
    write(tag, 3)
    write(0, 4) // element instance tag
    write(0, 12) // reserved
    val partial = if (frameCount != null) 1 else 0
    write((partial shl 3) or 1, 4) // partial, bytesShifted 0, escape
    if (frameCount != null) write(frameCount, 32)
    for (i in channels[0].indices) {
        for (channel in channels) write(channel[i], bitDepth)
    }
    return this
}

/** Writes the END element. */
fun BitWriter.end(): BitWriter = write(Element.END, 3)
