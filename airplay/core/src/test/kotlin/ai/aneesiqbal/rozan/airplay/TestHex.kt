package ai.aneesiqbal.rozan.airplay

/** Decodes a hex string for test vectors. Whitespace (as in RFC listings) is ignored. */
fun hex(text: String): ByteArray {
    val digits = text.filterNot { it.isWhitespace() }
    require(digits.length % 2 == 0) { "odd number of hex digits" }
    return ByteArray(digits.length / 2) { i ->
        ((Character.digit(digits[2 * i], 16) shl 4) or Character.digit(digits[2 * i + 1], 16)).toByte()
    }
}

/** Lower-case hex encoding for assertion messages and comparisons. */
fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it.toInt() and 0xff) }
