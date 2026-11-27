package ai.aneesiqbal.rozan.airplay.plist

import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.util.Base64
import java.util.Locale

/**
 * Apple XML property lists (`<plist version="1.0">`), UTF-8 encoded.
 *
 * The writer produces what `plutil -lint` accepts. The reader is a small, strict parser for the plist
 * subset of XML: no document type internal subset (so no entity definitions), only the five predefined
 * entities and character references, and the same limits as [BinaryPlist.decode].
 */
public object XmlPlist {
    private const val HEADER =
        "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n" +
            "<!DOCTYPE plist PUBLIC \"-//Apple//DTD PLIST 1.0//EN\" " +
            "\"http://www.apple.com/DTDs/PropertyList-1.0.dtd\">\n" +
            "<plist version=\"1.0\">\n"
    private const val FOOTER = "</plist>\n"

    /**
     * Encodes [value] as an XML plist document.
     *
     * Dates keep whole seconds only, as in Apple's format.
     *
     * @throws PlistException when a string holds characters XML 1.0 cannot carry (most C0 controls, U+FFFE,
     *   U+FFFF, unpaired surrogates), a date is outside years 1 to 9999, or containers nest deeper than
     *   [maxDepth].
     */
    public fun encodeToString(
        value: PlistValue,
        maxDepth: Int = PlistLimits.DEFAULT_MAX_DEPTH,
    ): String {
        val out = StringBuilder(HEADER)
        XmlPlistWriter(out, maxDepth).write(value, enclosing = 0)
        return out.append(FOOTER).toString()
    }

    /** Encodes [value] as a UTF-8 XML plist document; see [encodeToString]. */
    public fun encode(
        value: PlistValue,
        maxDepth: Int = PlistLimits.DEFAULT_MAX_DEPTH,
    ): ByteArray = encodeToString(value, maxDepth).toByteArray(Charsets.UTF_8)

    /**
     * Decodes an XML plist from untrusted UTF-8 [bytes] (a leading byte order mark is allowed).
     *
     * @throws PlistException for malformed UTF-8 or XML, unknown elements or entities, a document type with
     *   an internal subset, bad numbers, dates or base64, repeated keys, or a breach of [limits].
     */
    public fun decode(
        bytes: ByteArray,
        limits: PlistLimits = PlistLimits.DEFAULT,
    ): PlistValue {
        val decoder =
            Charsets.UTF_8
                .newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
        val text =
            try {
                decoder.decode(ByteBuffer.wrap(bytes)).toString()
            } catch (e: CharacterCodingException) {
                throw PlistException("XML plist is not valid UTF-8", e)
            }
        return decode(text, limits)
    }

    /** Decodes an XML plist document from [text]; see the `ByteArray` overload. */
    public fun decode(
        text: String,
        limits: PlistLimits = PlistLimits.DEFAULT,
    ): PlistValue = XmlPlistReader(text.removePrefix(""), limits).read()
}

/** Seconds from the Unix epoch to Apple's reference date, 2001-01-01T00:00:00Z. */
internal const val REFERENCE_DATE = PlistDate.REFERENCE_DATE_UNIX_SECONDS
private const val MAX_YEAR = 9999
internal const val UID_KEY = "CF\$UID"

private class XmlPlistWriter(
    private val out: StringBuilder,
    private val maxDepth: Int,
) {
    fun write(
        value: PlistValue,
        enclosing: Int,
    ) {
        indent(enclosing)
        when (value) {
            is PlistDict -> writeDict(value, enclosing)
            is PlistArray -> writeArray(value, enclosing)
            is PlistString -> element("string", escape(value.value))
            is PlistInteger -> element("integer", integerText(value))
            is PlistReal -> element("real", realText(value.value))
            is PlistBool -> out.append(if (value.value) "<true/>\n" else "<false/>\n")
            is PlistDate -> element("date", dateText(value))
            is PlistData -> element("data", Base64.getEncoder().encodeToString(value.unsafeBytes()))
            is PlistUid -> writeDict(PlistDict(mapOf(UID_KEY to PlistInteger(value.value))), enclosing)
        }
    }

    private fun writeDict(
        dict: PlistDict,
        enclosing: Int,
    ) {
        checkDepth(enclosing)
        if (dict.size == 0) {
            out.append("<dict/>\n")
            return
        }
        out.append("<dict>\n")
        for ((key, item) in dict.entries) {
            indent(enclosing + 1)
            element("key", escape(key))
            write(item, enclosing + 1)
        }
        indent(enclosing)
        out.append("</dict>\n")
    }

    private fun writeArray(
        array: PlistArray,
        enclosing: Int,
    ) {
        checkDepth(enclosing)
        if (array.size == 0) {
            out.append("<array/>\n")
            return
        }
        out.append("<array>\n")
        for (item in array.items) write(item, enclosing + 1)
        indent(enclosing)
        out.append("</array>\n")
    }

    private fun checkDepth(enclosing: Int) {
        if (enclosing + 1 > maxDepth) malformed("containers nest deeper than $maxDepth")
    }

    private fun element(
        name: String,
        content: String,
    ) {
        out
            .append('<')
            .append(name)
            .append('>')
            .append(content)
            .append("</")
            .append(name)
            .append(">\n")
    }

    private fun indent(depth: Int) {
        repeat(depth) { out.append('\t') }
    }

    private fun integerText(value: PlistInteger): String =
        if (value.isLargeUnsigned) value.value.toULong().toString() else value.value.toString()

    private fun realText(value: Double): String =
        when {
            value.isNaN() -> "nan"
            value == Double.POSITIVE_INFINITY -> "+infinity"
            value == Double.NEGATIVE_INFINITY -> "-infinity"
            else -> value.toString()
        }

    private fun dateText(date: PlistDate): String {
        val seconds = Math.floor(date.secondsSinceReferenceDate)
        // Years 1..9999 span about ±6.4e10 s around 2001; this bound keeps the Long conversion exact.
        if (!(seconds > -1.0e12 && seconds < 1.0e12)) malformed("date is out of range for XML")
        val time = LocalDateTime.ofEpochSecond(seconds.toLong() + REFERENCE_DATE, 0, ZoneOffset.UTC)
        if (time.year !in 1..MAX_YEAR) malformed("date is out of range for XML")
        return String.format(
            Locale.ROOT,
            "%04d-%02d-%02dT%02d:%02d:%02dZ",
            time.year,
            time.monthValue,
            time.dayOfMonth,
            time.hour,
            time.minute,
            time.second,
        )
    }

    private fun escape(text: String): String {
        checkXmlText(text)
        val sb = StringBuilder(text.length)
        for (c in text) {
            when (c) {
                '&' -> sb.append("&amp;")

                '<' -> sb.append("&lt;")

                '>' -> sb.append("&gt;")

                // XML parsers turn a raw CR into LF; a character reference keeps it.
                '\r' -> sb.append("&#13;")

                else -> sb.append(c)
            }
        }
        return sb.toString()
    }

    /** Rejects text XML 1.0 cannot carry: most C0 controls, U+FFFE, U+FFFF and unpaired surrogates. */
    private fun checkXmlText(text: String) {
        var i = 0
        while (i < text.length) {
            val codePoint = text.codePointAt(i)
            if (!isXmlChar(codePoint)) malformed("string holds a character XML cannot carry")
            i += Character.charCount(codePoint)
        }
    }
}

/** Whether XML 1.0 allows [codePoint] in a document (its `Char` production). */
internal fun isXmlChar(codePoint: Int): Boolean =
    when (codePoint) {
        0x9, 0xA, 0xD, in 0x20..0xD7FF, in 0xE000..0xFFFD, in 0x10000..0x10FFFF -> true
        else -> false
    }

/** Whether [c] is XML white space. */
internal fun isXmlSpace(c: Char): Boolean =
    when (c) {
        ' ', '\t', '\n', '\r' -> true
        else -> false
    }
