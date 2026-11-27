package ai.aneesiqbal.rozan.airplay.session

/**
 * Track metadata from a `SET_PARAMETER` with `application/x-dmap-tagged` (docs/protocol/01 §6.11,
 * 03 §8.1). Every field is null when absent.
 */
internal class TrackMetadata(
    val title: String?,
    val artist: String?,
    val album: String?,
)

/**
 * A bounded reader for DMAP (DAAP-style tagged data): a sequence of items, each a 4-byte ASCII tag, a
 * 4-byte big-endian length and that many value bytes. The specs name the tags used (`minm` title,
 * `asar` artist, `asal` album) but not the layout; this reader follows the public DAAP format, where
 * container items such as `mlit` hold further items.
 *
 * Hostile input: every length is checked against the bytes left before use, containers nest at most
 * [MAX_DEPTH] deep, at most [MAX_ITEMS] items are visited, and only the three strings are copied out,
 * each cut to [MAX_TEXT_CHARS]. A structural error rejects the whole body (03 §8.1: ignore it).
 */
internal object Dmap {
    /** Largest body accepted (03 §8.1). */
    const val MAX_BODY_BYTES = 256 * 1024

    /** Deepest container nesting followed. */
    const val MAX_DEPTH = 4

    /** Most items visited in one body. */
    const val MAX_ITEMS = 4096

    /** Longest string kept, in UTF-16 units. */
    const val MAX_TEXT_CHARS = 256

    private const val HEADER_BYTES = 8
    private val CONTAINERS = setOf("mlit", "mlcl", "mcon")

    /** The metadata in [body], or null when it is too large or malformed. */
    fun parse(body: ByteArray): TrackMetadata? {
        if (body.size > MAX_BODY_BYTES) return null
        val state = State()
        if (!walk(body, 0, body.size, 1, state)) return null
        return TrackMetadata(state.title, state.artist, state.album)
    }

    private class State {
        var items = 0
        var title: String? = null
        var artist: String? = null
        var album: String? = null
    }

    private fun walk(
        body: ByteArray,
        start: Int,
        end: Int,
        depth: Int,
        state: State,
    ): Boolean {
        var offset = start
        while (offset < end) {
            if (end - offset < HEADER_BYTES || ++state.items > MAX_ITEMS) return false
            val tag = String(body, offset, 4, Charsets.ISO_8859_1)
            val length = readInt(body, offset + 4)
            val valueStart = offset + HEADER_BYTES
            if (length < 0 || length > end - valueStart) return false
            if (tag in CONTAINERS) {
                if (depth >= MAX_DEPTH || !walk(body, valueStart, valueStart + length, depth + 1, state)) return false
            } else {
                take(tag, body, valueStart, length, state)
            }
            offset = valueStart + length
        }
        return true
    }

    private fun take(
        tag: String,
        body: ByteArray,
        start: Int,
        length: Int,
        state: State,
    ) {
        when (tag) {
            "minm" -> state.title = text(body, start, length)
            "asar" -> state.artist = text(body, start, length)
            "asal" -> state.album = text(body, start, length)
        }
    }

    private fun text(
        body: ByteArray,
        start: Int,
        length: Int,
    ): String? = ClientText.clean(String(body, start, length, Charsets.UTF_8), MAX_TEXT_CHARS)

    private fun readInt(
        bytes: ByteArray,
        offset: Int,
    ): Int =
        ((bytes[offset].toInt() and 0xFF) shl 24) or
            ((bytes[offset + 1].toInt() and 0xFF) shl 16) or
            ((bytes[offset + 2].toInt() and 0xFF) shl 8) or
            (bytes[offset + 3].toInt() and 0xFF)
}

/** Cleans sender-supplied text before it reaches the UI or [ai.aneesiqbal.rozan.airplay.receiver.ClientInfo]. */
internal object ClientText {
    /** Longest sender name or model kept. */
    const val MAX_NAME_CHARS = 64

    /**
     * [text] without control characters, trimmed and cut to [maxChars] on a code point boundary; null
     * when nothing is left.
     */
    fun clean(
        text: String?,
        maxChars: Int = MAX_NAME_CHARS,
    ): String? {
        if (text == null) return null
        val out = StringBuilder()
        var index = 0
        while (index < text.length) {
            val codePoint = text.codePointAt(index)
            index += Character.charCount(codePoint)
            val keep = !Character.isISOControl(codePoint) && codePoint != REPLACEMENT
            if (keep && out.length + Character.charCount(codePoint) > maxChars) break
            if (keep) out.appendCodePoint(codePoint)
        }
        return out.toString().trim().ifEmpty { null }
    }

    private const val REPLACEMENT = 0xFFFD
}
