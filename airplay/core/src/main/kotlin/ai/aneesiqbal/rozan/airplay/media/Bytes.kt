package ai.aneesiqbal.rozan.airplay.media

// Fixed-width reads and writes for the media wire formats. RTP and NTP fields are big-endian; the
// mirroring header is little-endian. Callers check bounds before calling.

private const val BYTE_MASK = 0xFF

/** Unsigned byte at [offset]. */
internal fun ByteArray.u8(offset: Int): Int = this[offset].toInt() and BYTE_MASK

/** Unsigned 16-bit big-endian value at [offset]. */
internal fun ByteArray.u16Be(offset: Int): Int = (u8(offset) shl 8) or u8(offset + 1)

/** 32-bit big-endian value at [offset], as the raw bits. */
internal fun ByteArray.i32Be(offset: Int): Int =
    (u8(offset) shl 24) or (u8(offset + 1) shl 16) or (u8(offset + 2) shl 8) or u8(offset + 3)

/** 64-bit big-endian value at [offset], as the raw bits. */
internal fun ByteArray.i64Be(offset: Int): Long =
    (i32Be(offset).toLong() shl 32) or (i32Be(offset + 4).toLong() and 0xFFFF_FFFFL)

/** 32-bit little-endian value at [offset], as the raw bits. */
internal fun ByteArray.i32Le(offset: Int): Int =
    u8(offset) or (u8(offset + 1) shl 8) or (u8(offset + 2) shl 16) or (u8(offset + 3) shl 24)

/** 64-bit little-endian value at [offset], as the raw bits. */
internal fun ByteArray.i64Le(offset: Int): Long =
    (i32Le(offset).toLong() and 0xFFFF_FFFFL) or (i32Le(offset + 4).toLong() shl 32)

/** Writes the low 16 bits of [value] big-endian at [offset]. */
internal fun ByteArray.putU16Be(
    offset: Int,
    value: Int,
) {
    this[offset] = (value ushr 8).toByte()
    this[offset + 1] = value.toByte()
}

/** Writes [value] big-endian at [offset]. */
internal fun ByteArray.putI64Be(
    offset: Int,
    value: Long,
) {
    for (i in 0 until Long.SIZE_BYTES) {
        this[offset + i] = (value ushr (56 - 8 * i)).toByte()
    }
}
