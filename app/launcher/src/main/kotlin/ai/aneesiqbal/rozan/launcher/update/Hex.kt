package ai.aneesiqbal.rozan.launcher.update

/**
 * The value of an ASCII hex digit (`0-9`, `a-f`, `A-F`), or -1. Unlike
 * [Character.digit] it accepts no other Unicode digits.
 */
internal fun asciiHexValue(c: Char): Int =
    when (c) {
        in '0'..'9' -> c - '0'
        in 'a'..'f' -> c - 'a' + DECIMAL_DIGITS
        in 'A'..'F' -> c - 'A' + DECIMAL_DIGITS
        else -> -1
    }

/** Lowercase hex of [bytes]. */
internal fun hexOf(bytes: ByteArray): String =
    buildString(bytes.size * 2) {
        for (b in bytes) {
            val v = b.toInt() and BYTE_MASK
            append(HEX_CHARS[v ushr NIBBLE_BITS])
            append(HEX_CHARS[v and NIBBLE_MASK])
        }
    }

private const val DECIMAL_DIGITS = 10
private const val BYTE_MASK = 0xFF
private const val NIBBLE_BITS = 4
private const val NIBBLE_MASK = 0x0F
private const val HEX_CHARS = "0123456789abcdef"
