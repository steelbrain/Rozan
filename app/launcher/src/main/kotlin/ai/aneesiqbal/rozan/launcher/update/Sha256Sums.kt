package ai.aneesiqbal.rozan.launcher.update

/**
 * The release's `SHA256SUMS`, as `sha256sum` writes it
 * (`scripts/release/collect.sh`): one line per file, 64 lowercase or
 * uppercase hex digits, a space, a space or `*` (text or binary mode), and
 * the file name, which may start with `./`. Blank lines are skipped; any
 * other line that does not have this form, a name listed twice or more than
 * [MAX_ENTRIES] lines make the whole file invalid ([UpdateFormatException]).
 */
internal object Sha256Sums {
    /** Largest file accepted, in bytes (the download is capped at the same size). */
    const val MAX_BYTES = 64 * 1024

    /** Most entries; a release lists two APKs today. */
    const val MAX_ENTRIES = 256

    /** Longest file name accepted. */
    const val MAX_NAME_LENGTH = 255

    private const val HASH_HEX_LENGTH = 64
    private const val HASH_BYTES = 32
    private const val HEX_RADIX = 16

    /** Parses [text] into file name to SHA-256 digest (32 bytes). */
    fun parse(text: String): Map<String, ByteArray> {
        if (text.length > MAX_BYTES) formatError("SHA256SUMS longer than $MAX_BYTES")
        val entries = LinkedHashMap<String, ByteArray>()
        for (raw in text.split('\n')) {
            val line = raw.removeSuffix("\r")
            if (line.isBlank()) continue
            if (entries.size == MAX_ENTRIES) formatError("more than $MAX_ENTRIES entries")
            val (name, digest) = entry(line)
            if (entries.put(name, digest) != null) formatError("$name is listed twice")
        }
        return entries
    }

    /** The digest of [name] in [text], or null when it is not listed. */
    fun find(
        text: String,
        name: String,
    ): ByteArray? = parse(text)[name]

    private fun entry(line: String): Pair<String, ByteArray> {
        if (line.length < HASH_HEX_LENGTH + 3) formatError("short SHA256SUMS line")
        val digest = decodeHex(line.substring(0, HASH_HEX_LENGTH))
        val mode = line[HASH_HEX_LENGTH + 1]
        if (line[HASH_HEX_LENGTH] != ' ' ||
            (mode != ' ' && mode != '*')
        ) {
            formatError("SHA256SUMS line without separator")
        }
        val name = line.substring(HASH_HEX_LENGTH + 2).removePrefix("./")
        // sha256sum escapes names with a backslash or newline and marks the line with a leading '\'
        // (rejected above as a bad digest); names with separators are not release assets.
        val valid =
            name.isNotEmpty() &&
                name.length <= MAX_NAME_LENGTH &&
                name.none { it == '/' || it == '\\' || it < ' ' } &&
                name != "." &&
                name != ".."
        if (!valid) formatError("bad file name in SHA256SUMS")
        return name to digest
    }

    private fun decodeHex(hex: String): ByteArray {
        val out = ByteArray(HASH_BYTES)
        for (i in 0 until HASH_BYTES) {
            val high = asciiHexValue(hex[2 * i])
            val low = asciiHexValue(hex[2 * i + 1])
            if (high < 0 || low < 0) formatError("bad digest in SHA256SUMS")
            out[i] = (high * HEX_RADIX + low).toByte()
        }
        return out
    }
}
