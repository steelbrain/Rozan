package ai.aneesiqbal.rozan.airplay.fairplay

/** Decodes a hex string. For compile-time constants only; [text] must be well formed. */
internal fun hex(text: String): ByteArray {
    require(text.length % 2 == 0) { "odd-length hex constant" }
    return ByteArray(text.length / 2) { i ->
        val high = Character.digit(text[2 * i], HEX_RADIX)
        val low = Character.digit(text[2 * i + 1], HEX_RADIX)
        require(high >= 0 && low >= 0) { "bad hex constant" }
        ((high shl 4) or low).toByte()
    }
}

private const val HEX_RADIX = 16

/** The byte at [index] as 0..255. */
internal fun ByteArray.u8(index: Int): Int = this[index].toInt() and 0xFF

/** Big-endian 32-bit word at [offset]. */
internal fun ByteArray.be32(offset: Int): Int =
    (u8(offset) shl 24) or (u8(offset + 1) shl 16) or (u8(offset + 2) shl 8) or u8(offset + 3)

/** Little-endian 32-bit word at [offset]. */
internal fun ByteArray.le32(offset: Int): Int =
    u8(offset) or (u8(offset + 1) shl 8) or (u8(offset + 2) shl 16) or (u8(offset + 3) shl 24)

/** Stores [value] little-endian at [offset]. */
internal fun ByteArray.putLe32(
    offset: Int,
    value: Int,
) {
    this[offset] = value.toByte()
    this[offset + 1] = (value ushr 8).toByte()
    this[offset + 2] = (value ushr 16).toByte()
    this[offset + 3] = (value ushr 24).toByte()
}

/** XORs [length] bytes of [source] from [sourceOffset] into this array from [offset]. */
internal fun ByteArray.xorInPlace(
    offset: Int,
    source: ByteArray,
    sourceOffset: Int,
    length: Int,
) {
    for (i in 0 until length) {
        this[offset + i] = (this[offset + i].toInt() xor source[sourceOffset + i].toInt()).toByte()
    }
}

/** True when this array holds [expected] at [offset]. Not constant time; for public framing bytes only. */
internal fun ByteArray.startsWithAt(
    offset: Int,
    expected: ByteArray,
): Boolean {
    for (i in expected.indices) {
        if (this[offset + i] != expected[i]) return false
    }
    return true
}

/**
 * Compares [length] bytes of [a] from [aOffset] with [b] from [bOffset] in time
 * that depends only on [length], so a mismatching tag leaks nothing about where
 * it differs.
 */
internal fun constantTimeEquals(
    a: ByteArray,
    aOffset: Int,
    b: ByteArray,
    bOffset: Int,
    length: Int,
): Boolean {
    var difference = 0
    for (i in 0 until length) {
        difference = difference or (a[aOffset + i].toInt() xor b[bOffset + i].toInt())
    }
    return difference == 0
}
