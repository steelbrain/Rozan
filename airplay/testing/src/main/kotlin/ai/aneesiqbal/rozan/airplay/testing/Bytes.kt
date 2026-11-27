package ai.aneesiqbal.rozan.airplay.testing

/** Decodes a well-formed hex constant. */
internal fun hex(text: String): ByteArray {
    require(text.length % 2 == 0) { "odd-length hex constant" }
    return ByteArray(text.length / 2) { i ->
        val high = Character.digit(text[2 * i], 16)
        val low = Character.digit(text[2 * i + 1], 16)
        require(high >= 0 && low >= 0) { "bad hex constant" }
        ((high shl 4) or low).toByte()
    }
}

/** The byte at [index] as 0..255. */
internal fun ByteArray.u8(index: Int): Int = this[index].toInt() and 0xFF

/** Big-endian unsigned 16-bit value at [offset]. */
internal fun ByteArray.u16Be(offset: Int): Int = (u8(offset) shl 8) or u8(offset + 1)

/** Big-endian 32-bit value at [offset]. */
internal fun ByteArray.i32Be(offset: Int): Int =
    (u8(offset) shl 24) or (u8(offset + 1) shl 16) or (u8(offset + 2) shl 8) or u8(offset + 3)

/** Big-endian 64-bit value at [offset]. */
internal fun ByteArray.i64Be(offset: Int): Long =
    (i32Be(offset).toLong() shl 32) or (i32Be(offset + 4).toLong() and 0xFFFF_FFFFL)

/** Stores the low 16 bits of [value] big-endian at [offset]. */
internal fun ByteArray.putU16Be(
    offset: Int,
    value: Int,
) {
    this[offset] = (value ushr 8).toByte()
    this[offset + 1] = value.toByte()
}

/** Stores [value] big-endian at [offset]. */
internal fun ByteArray.putI32Be(
    offset: Int,
    value: Int,
) {
    for (i in 0 until 4) this[offset + i] = (value ushr (24 - 8 * i)).toByte()
}

/** Stores [value] big-endian at [offset]. */
internal fun ByteArray.putI64Be(
    offset: Int,
    value: Long,
) {
    for (i in 0 until 8) this[offset + i] = (value ushr (56 - 8 * i)).toByte()
}

/** Stores [value] little-endian at [offset]. */
internal fun ByteArray.putI32Le(
    offset: Int,
    value: Int,
) {
    for (i in 0 until 4) this[offset + i] = (value ushr (8 * i)).toByte()
}

/** Stores [value] little-endian at [offset]. */
internal fun ByteArray.putI64Le(
    offset: Int,
    value: Long,
) {
    for (i in 0 until 8) this[offset + i] = (value ushr (8 * i)).toByte()
}

/** XOR of two arrays of the same size. */
internal fun xor(
    a: ByteArray,
    b: ByteArray,
): ByteArray {
    require(a.size == b.size) { "sizes differ" }
    return ByteArray(a.size) { (a[it].toInt() xor b[it].toInt()).toByte() }
}
