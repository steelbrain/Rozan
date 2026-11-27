package ai.aneesiqbal.rozan.airplay.plist

import java.time.DateTimeException
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.util.Base64
import java.util.Locale

/**
 * A small, strict parser for the plist subset of XML. It never resolves external or declared entities: a
 * document type with an internal subset is rejected outright.
 */
internal class XmlPlistReader(
    private val text: String,
    private val limits: PlistLimits,
) {
    private var pos = 0
    private var valueCount = 0

    private class Tag(
        val name: String,
        val selfClosing: Boolean,
    )

    fun read(): PlistValue {
        skipProlog()
        val first = readStartTag()
        val value =
            if (first.name == "plist") {
                if (first.selfClosing) malformed("empty plist element")
                skipMisc()
                val inner = readValue(readStartTag(), enclosing = 0)
                skipMisc()
                expectEndTag("plist")
                inner
            } else {
                readValue(first, enclosing = 0)
            }
        skipMisc()
        if (pos != text.length) malformed("content after the plist")
        return value
    }

    private fun readValue(
        tag: Tag,
        enclosing: Int,
    ): PlistValue {
        countValue()
        return when (tag.name) {
            "dict" -> readDict(tag, enclosing)
            "array" -> readArray(tag, enclosing)
            else -> readScalarValue(tag)
        }
    }

    private fun readScalarValue(tag: Tag): PlistValue =
        when (tag.name) {
            "string" -> PlistString(if (tag.selfClosing) "" else readText("string"))
            "integer" -> XmlScalars.parseInteger(readScalar(tag))
            "real" -> PlistReal(XmlScalars.parseReal(readScalar(tag)))
            "true" -> PlistBool(true).also { readEmpty(tag) }
            "false" -> PlistBool(false).also { readEmpty(tag) }
            "date" -> PlistDate(XmlScalars.parseDate(readScalar(tag)))
            "data" -> PlistData.adopt(XmlScalars.parseData(if (tag.selfClosing) "" else readText("data")))
            else -> malformed("unexpected element")
        }

    private fun readDict(
        tag: Tag,
        enclosing: Int,
    ): PlistValue {
        if (enclosing + 1 > limits.maxDepth) depthError()
        val entries = LinkedHashMap<String, PlistValue>()
        if (!tag.selfClosing) {
            while (true) {
                skipMisc()
                if (text.startsWith("</", pos)) break
                val keyTag = readStartTag()
                if (keyTag.name != "key") malformed("dictionary entry without a key")
                countValue()
                val key = if (keyTag.selfClosing) "" else readText("key")
                skipMisc()
                val value = readValue(readStartTag(), enclosing + 1)
                if (entries.put(key, value) != null) malformed("repeated dictionary key")
            }
            expectEndTag("dict")
        }
        return asUid(entries) ?: PlistDict(entries)
    }

    /** Apple's XML form of a keyed-archiver UID: a dictionary whose only entry maps `CF$UID` to an integer. */
    private fun asUid(entries: Map<String, PlistValue>): PlistUid? {
        if (entries.size != 1) return null
        val value = entries[UID_KEY] as? PlistInteger ?: return null
        return if (!value.isLargeUnsigned && value.value in 0..PlistUid.MAX_VALUE) PlistUid(value.value) else null
    }

    private fun readArray(
        tag: Tag,
        enclosing: Int,
    ): PlistValue {
        if (enclosing + 1 > limits.maxDepth) depthError()
        val items = ArrayList<PlistValue>()
        if (!tag.selfClosing) {
            while (true) {
                skipMisc()
                if (text.startsWith("</", pos)) break
                items.add(readValue(readStartTag(), enclosing + 1))
            }
            expectEndTag("array")
        }
        return PlistArray(items)
    }

    private fun countValue() {
        valueCount++
        if (valueCount > limits.maxObjects) malformed("plist has more than ${limits.maxObjects} values")
    }

    private fun depthError(): Nothing = malformed("containers nest deeper than ${limits.maxDepth}")

    private fun readScalar(tag: Tag): String {
        if (tag.selfClosing) malformed("empty <${tag.name}> element")
        return readText(tag.name).trim(::isXmlSpace)
    }

    /** Consumes the rest of `<true/>` or `<false/>`, also accepted with an empty end tag. */
    private fun readEmpty(tag: Tag) {
        if (!tag.selfClosing) {
            skipMisc()
            expectEndTag(tag.name)
        }
    }

    /** Reads character data up to the end tag `</[name]>`, resolving references and CDATA sections. */
    private fun readText(name: String): String {
        val sb = StringBuilder()
        while (!text.startsWith("</", pos)) {
            if (pos >= text.length) malformed("unterminated <$name> element")
            when (text[pos]) {
                '<' -> {
                    readMarkupInText(sb, name)
                }

                '&' -> {
                    sb.appendCodePoint(readReference())
                }

                else -> {
                    var end = pos + 1
                    while (end < text.length && text[end] != '<' && text[end] != '&') end++
                    appendChars(sb, pos, end)
                    pos = end
                }
            }
        }
        expectEndTag(name)
        return sb.toString()
    }

    /** Handles a `<` inside character data: a CDATA section or a comment; anything else is an error. */
    private fun readMarkupInText(
        sb: StringBuilder,
        name: String,
    ) {
        when {
            text.startsWith(CDATA_START, pos) -> {
                val end = text.indexOf("]]>", pos + CDATA_START.length)
                if (end < 0) malformed("unterminated CDATA section")
                appendChars(sb, pos + CDATA_START.length, end)
                pos = end + 3
            }

            text.startsWith("<!--", pos) -> {
                skipComment()
            }

            else -> {
                malformed("unexpected element inside <$name>")
            }
        }
    }

    /** Appends text[from, to), normalising line ends as XML requires and rejecting forbidden characters. */
    private fun appendChars(
        sb: StringBuilder,
        from: Int,
        to: Int,
    ) {
        var i = from
        while (i < to) {
            val c = text[i]
            when {
                c == '\r' -> {
                    sb.append('\n')
                    if (i + 1 < to && text[i + 1] == '\n') i++
                }

                // Surrogates arrive in pairs: the UTF-8 decoder rejects anything else.
                isXmlChar(c.code) || Character.isSurrogate(c) -> {
                    sb.append(c)
                }

                else -> {
                    malformed("character not allowed in XML text")
                }
            }
            i++
        }
    }

    private fun readReference(): Int {
        val end = text.indexOf(';', pos)
        if (end < 0 || end - pos > MAX_REFERENCE_LENGTH) malformed("bad entity reference")
        val name = text.substring(pos + 1, end)
        pos = end + 1
        val codePoint = resolveReference(name) ?: malformed("unknown entity reference")
        if (!isXmlChar(codePoint)) malformed("character reference to a forbidden character")
        return codePoint
    }

    /** The code point for a predefined entity or character reference name (between `&` and `;`). */
    private fun resolveReference(name: String): Int? =
        when {
            name.startsWith("#x") -> name.substring(2).takeIf { HEX_DIGITS.matches(it) }?.toInt(16)
            name.startsWith("#") -> name.substring(1).takeIf { DECIMAL_DIGITS.matches(it) }?.toInt()
            else -> PREDEFINED_ENTITIES[name]
        }

    private fun readStartTag(): Tag {
        if (pos >= text.length || text[pos] != '<') malformed("expected an element")
        pos++
        val name = readName()
        while (true) {
            skipWhitespace()
            if (pos >= text.length) malformed("unterminated tag")
            when {
                text.startsWith("/>", pos) -> {
                    pos += 2
                    return Tag(name, selfClosing = true)
                }

                text[pos] == '>' -> {
                    pos++
                    return Tag(name, selfClosing = false)
                }

                else -> {
                    skipAttribute()
                }
            }
        }
    }

    private fun skipAttribute() {
        readName()
        skipWhitespace()
        if (pos >= text.length || text[pos] != '=') malformed("bad attribute")
        pos++
        skipWhitespace()
        if (pos >= text.length || (text[pos] != '"' && text[pos] != '\'')) malformed("bad attribute")
        val end = text.indexOf(text[pos], pos + 1)
        if (end < 0 || text.indexOf('<', pos + 1) in 0 until end) malformed("bad attribute")
        pos = end + 1
    }

    private fun expectEndTag(name: String) {
        if (!text.startsWith("</", pos)) malformed("expected </$name>")
        pos += 2
        if (readName() != name) malformed("mismatched end tag")
        skipWhitespace()
        if (pos >= text.length || text[pos] != '>') malformed("unterminated end tag")
        pos++
    }

    private fun readName(): String {
        val start = pos
        while (pos < text.length && pos - start <= MAX_NAME_LENGTH && isNameChar(text[pos])) pos++
        if (pos == start || pos - start > MAX_NAME_LENGTH) malformed("bad element name")
        return text.substring(start, pos)
    }

    private fun isNameChar(c: Char): Boolean =
        when (c) {
            in 'a'..'z', in 'A'..'Z', in '0'..'9', '_', '-', '.', ':' -> true
            else -> false
        }

    /** Skips the XML declaration, processing instructions, comments and the document type. */
    private fun skipProlog() {
        while (true) {
            skipWhitespace()
            when {
                text.startsWith("<?", pos) -> {
                    val end = text.indexOf("?>", pos + 2)
                    if (end < 0) malformed("unterminated processing instruction")
                    pos = end + 2
                }

                text.startsWith("<!--", pos) -> {
                    skipComment()
                }

                text.startsWith("<!DOCTYPE", pos) -> {
                    skipDoctype()
                }

                else -> {
                    return
                }
            }
        }
    }

    private fun skipDoctype() {
        pos += "<!DOCTYPE".length
        while (true) {
            if (pos >= text.length) malformed("unterminated document type")
            when (val c = text[pos]) {
                '>' -> {
                    pos++
                    return
                }

                '[' -> {
                    malformed("document type internal subsets are not supported")
                }

                '"', '\'' -> {
                    val end = text.indexOf(c, pos + 1)
                    if (end < 0) malformed("unterminated document type")
                    pos = end + 1
                }

                else -> {
                    pos++
                }
            }
        }
    }

    /** Skips whitespace and comments between elements. */
    private fun skipMisc() {
        while (true) {
            skipWhitespace()
            if (!text.startsWith("<!--", pos)) return
            skipComment()
        }
    }

    private fun skipComment() {
        val end = text.indexOf("-->", pos + 4)
        if (end < 0) malformed("unterminated comment")
        pos = end + 3
    }

    private fun skipWhitespace() {
        while (pos < text.length && isXmlSpace(text[pos])) pos++
    }

    private companion object {
        const val MAX_NAME_LENGTH = 64
        const val MAX_REFERENCE_LENGTH = 10
        val HEX_DIGITS = Regex("[0-9a-fA-F]{1,6}")
        val DECIMAL_DIGITS = Regex("[0-9]{1,7}")
        const val CDATA_START = "<![CDATA["
        val PREDEFINED_ENTITIES =
            mapOf("lt" to '<'.code, "gt" to '>'.code, "amp" to '&'.code, "quot" to '"'.code, "apos" to '\''.code)
    }
}

/** Parsers for the text of `<integer>`, `<real>`, `<date>` and `<data>` elements. */
private object XmlScalars {
    private val DECIMAL = Regex("[+-]?[0-9]+")
    private val HEX = Regex("0[xX][0-9a-fA-F]+")
    private val REAL = Regex("[+-]?([0-9]+(\\.[0-9]*)?|\\.[0-9]+)([eE][+-]?[0-9]+)?")
    private val DATE = Regex("([0-9]{4})-([0-9]{2})-([0-9]{2})T([0-9]{2}):([0-9]{2}):([0-9]{2})Z")

    fun parseInteger(value: String): PlistInteger {
        val parsed =
            when {
                HEX.matches(value) -> {
                    value.substring(2).toULongOrNull(16)?.let(PlistInteger::ofUnsigned)
                }

                !DECIMAL.matches(value) -> {
                    null
                }

                else -> {
                    value.toLongOrNull()?.let(::PlistInteger)
                        ?: value.toULongOrNull()?.let(PlistInteger::ofUnsigned)
                }
            }
        return parsed ?: malformed("bad or out-of-range integer")
    }

    fun parseReal(value: String): Double {
        when (value.lowercase(Locale.ROOT)) {
            "nan" -> return Double.NaN
            "inf", "+inf", "infinity", "+infinity" -> return Double.POSITIVE_INFINITY
            "-inf", "-infinity" -> return Double.NEGATIVE_INFINITY
        }
        if (!REAL.matches(value)) malformed("bad real")
        return value.toDouble()
    }

    fun parseDate(value: String): Double {
        val match = DATE.matchEntire(value) ?: malformed("bad date")
        val fields = match.groupValues.drop(1).map { it.toInt() }
        val seconds =
            try {
                LocalDateTime
                    .of(fields[0], fields[1], fields[2], fields[3], fields[4], fields[5])
                    .toEpochSecond(ZoneOffset.UTC)
            } catch (e: DateTimeException) {
                throw PlistException("bad date", e)
            }
        return (seconds - REFERENCE_DATE).toDouble()
    }

    fun parseData(value: String): ByteArray {
        val compact = value.filterNot(::isXmlSpace)
        return try {
            Base64.getDecoder().decode(compact)
        } catch (e: IllegalArgumentException) {
            throw PlistException("bad base64 data", e)
        }
    }
}
