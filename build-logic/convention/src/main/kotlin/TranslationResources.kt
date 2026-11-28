import org.w3c.dom.Document
import org.xml.sax.Attributes
import org.xml.sax.InputSource
import org.xml.sax.Locator
import org.xml.sax.SAXParseException
import org.xml.sax.helpers.DefaultHandler
import java.io.File
import java.io.StringReader
import java.util.Locale
import javax.xml.XMLConstants
import javax.xml.parsers.DocumentBuilderFactory
import javax.xml.parsers.SAXParserFactory

/** What a string resource is: `<string>`, `<plurals>` or `<string-array>`. */
internal enum class ResourceKind { STRING, PLURALS, ARRAY }

/**
 * One text of a resource: the whole `<string>`, one `<item>` of a `<plurals>` (keyed by quantity) or
 * of a `<string-array>` (keyed by index).
 */
internal data class ResourceText(
    /** Empty for a `<string>`, the quantity for a plural item, the index for an array item. */
    val part: String,
    /** The text as the app shows it: XML decoded, whitespace collapsed, Android escapes resolved. */
    val text: String,
    /** Line of the element in its file. */
    val line: Int,
)

/** A `<string>`, `<plurals>` or `<string-array>` of one resource file. */
internal data class StringResource(
    val name: String,
    val kind: ResourceKind,
    val translatable: Boolean,
    val texts: List<ResourceText>,
    /** File path as reports show it (relative to the repository root). */
    val file: String,
    val line: Int,
) {
    /** The text of [part], or null if this resource has none. */
    fun text(part: String): ResourceText? = texts.firstOrNull { it.part == part }
}

/** The string resources of one `values*` directory, by name. */
internal typealias StringTable = Map<String, StringResource>

/** The strings of one module: English from `values/`, and each translation by BCP 47 tag. */
internal data class ModuleStrings(
    /** Gradle path, e.g. `:app:launcher`. */
    val path: String,
    /** Its `src/main/res` as reports show it. */
    val resourceDirectory: String,
    val english: StringTable,
    val translations: Map<String, StringTable>,
    /** For each translation tag, its directory as reports show it. */
    val directories: Map<String, String>,
)

/** Reading resource files the way aapt2 reads them, as far as the text of a string goes. */
internal object AndroidStrings {
    private val nonLocaleQualifiers = setOf("car", "hdr")
    private val languageRegion = Regex("""([a-z]{2,3})(?:-r([A-Z]{2}|[0-9]{3}))?""")
    private val bcp47 = Regex("""b\+([a-zA-Z]{2,8})((?:\+[a-zA-Z0-9]{1,8})*)""")

    /**
     * The BCP 47 tag of a `values-*` directory whose only qualifier is a locale (`values-fr` → `fr`,
     * `values-pt-rBR` → `pt-BR`, `values-b+zh+Hans` → `zh-Hans`), or null for `values` and for
     * directories with other qualifiers (`values-night`, `values-v31`, `values-fr-v31`).
     */
    fun localeOf(directoryName: String): String? {
        val qualifiers = directoryName.removePrefix("values").takeIf { it.startsWith("-") }?.substring(1) ?: return null
        languageRegion.matchEntire(qualifiers)?.let { match ->
            val (language, region) = match.destructured
            if (language in nonLocaleQualifiers) return null
            return normalizeTag(if (region.isEmpty()) language else "$language-$region")
        }
        bcp47.matchEntire(qualifiers)?.let { match ->
            return normalizeTag(match.value.removePrefix("b+").replace('+', '-'))
        }
        return null
    }

    /** [tag] in its canonical case (`zh-hans` → `zh-Hans`). */
    fun normalizeTag(tag: String): String = Locale.forLanguageTag(tag).toLanguageTag()

    /**
     * The text aapt2 compiles from the character content of a string element: runs of unquoted ASCII
     * whitespace collapse to one space, leading and trailing whitespace goes, `"` toggles quoting
     * (inside which whitespace is kept) and is dropped, and `\n`, `\t`, `\uXXXX` and `\`+any other
     * character (`\'`, `\"`, `\\`, `\@`, `\?`) become that character. U+00A0 and U+202F are not
     * whitespace to aapt2 and stay.
     */
    fun decode(raw: String): String {
        val out = StringBuilder(raw.length)
        var quoted = false
        var pendingSpace = false

        fun emit(c: Char) {
            if (pendingSpace) {
                out.append(' ')
                pendingSpace = false
            }
            out.append(c)
        }

        var i = 0
        while (i < raw.length) {
            val c = raw[i]
            when {
                c == '\\' && i + 1 < raw.length -> {
                    val escaped = raw[i + 1]
                    i += 2
                    when (escaped) {
                        'n' -> {
                            emit('\n')
                        }

                        't' -> {
                            emit('\t')
                        }

                        'u' -> {
                            var end = i
                            while (end < raw.length && end < i + 4 && raw[end].isHexDigit()) end++
                            if (end == i + 4) {
                                emit(raw.substring(i, end).toInt(16).toChar())
                                i = end
                            } else {
                                emit('u')
                            }
                        }

                        else -> {
                            emit(escaped)
                        }
                    }
                }

                c == '"' -> {
                    quoted = !quoted
                    i++
                }

                !quoted && c.isAsciiWhitespace() -> {
                    if (out.isNotEmpty()) pendingSpace = true
                    i++
                }

                else -> {
                    emit(c)
                    i++
                }
            }
        }
        return out.toString()
    }

    private val formatSpecifier = Regex("""%(\d+\$)?[-#+ 0,(<]*\d*(?:\.\d+)?[tT]?[a-zA-Z%]""")

    /**
     * The format specifiers of [text] in order (`%1$s`, `%d`, `%.1f`), without `%%` and `%n`, which
     * take no argument. A `%` that starts no valid specifier is listed as `%`.
     */
    fun formatSpecifiers(text: String): List<String> {
        val found = ArrayList<String>()
        var i = text.indexOf('%')
        while (i >= 0) {
            val match = formatSpecifier.matchAt(text, i)
            val token = match?.value ?: "%"
            if (token != "%%" && token != "%n") found += token
            i = text.indexOf('%', i + token.length)
        }
        return found
    }

    /**
     * Whether [term] occurs in [text] as a whole word: not directly after or before a letter or digit
     * of a script that separates words with spaces. Chinese and Japanese text runs Latin words
     * straight into its own characters (`AirPlay で`, `AirPlay应用`), so those count as boundaries.
     */
    fun containsWord(
        text: String,
        term: String,
    ): Boolean = wordOccurrences(text, Regex(Regex.escape(term))).any()

    /** The whole-word matches of [pattern] in [text] (see [containsWord]). */
    fun wordOccurrences(
        text: String,
        pattern: Regex,
    ): Sequence<String> =
        pattern
            .findAll(text)
            .filter { match ->
                val value = match.value
                val before = text.getOrNull(match.range.first - 1)
                val after = text.getOrNull(match.range.last + 1)
                value.isNotEmpty() &&
                    !(isWordChar(value.first()) && before != null && isWordChar(before)) &&
                    !(isWordChar(value.last()) && after != null && isWordChar(after))
            }.map { it.value }

    private val spacedScripts =
        setOf(Character.UnicodeScript.LATIN, Character.UnicodeScript.GREEK, Character.UnicodeScript.CYRILLIC)

    private fun isWordChar(c: Char): Boolean =
        Character.isDigit(c) ||
            Character.getType(c) == Character.NON_SPACING_MARK.toInt() ||
            (Character.isLetter(c) && Character.UnicodeScript.of(c.code) in spacedScripts)

    private fun Char.isHexDigit(): Boolean = this in '0'..'9' || this in 'a'..'f' || this in 'A'..'F'

    private fun Char.isAsciiWhitespace(): Boolean =
        this == ' ' || this == '\t' || this == '\n' || this == '\r' || this == '\u000B' || this == '\u000C'

    /** Parses the string resources of one `values*` XML file; [label] names it in reports and errors. */
    fun parse(
        file: File,
        label: String,
    ): StringTable = file.inputStream().use { parse(InputSource(it), label) }

    /** Parses resource XML from [xml] (for tests). */
    fun parse(
        xml: String,
        label: String,
    ): StringTable = parse(InputSource(StringReader(xml)), label)

    private fun parse(
        source: InputSource,
        label: String,
    ): StringTable {
        val handler = ResourceHandler(label)
        val parser = secureSaxFactory().newSAXParser()
        try {
            parser.parse(source, handler)
        } catch (e: SAXParseException) {
            throw IllegalArgumentException("$label:${e.lineNumber}: ${e.message}", e)
        }
        return handler.resources
    }

    /** A namespace-aware DOM parser that refuses DTDs and external entities. */
    fun parseDocument(file: File): Document {
        val factory = DocumentBuilderFactory.newInstance()
        factory.isNamespaceAware = true
        factory.isExpandEntityReferences = false
        factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true)
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
        return factory.newDocumentBuilder().parse(file)
    }

    private fun secureSaxFactory(): SAXParserFactory {
        val factory = SAXParserFactory.newInstance()
        factory.isNamespaceAware = true
        factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true)
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
        factory.setFeature("http://xml.org/sax/features/external-general-entities", false)
        factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false)
        return factory
    }

    /** Collects `<string>`, `<plurals>` and `<string-array>` children of `<resources>`. */
    private class ResourceHandler(
        private val label: String,
    ) : DefaultHandler() {
        val resources = LinkedHashMap<String, StringResource>()
        private var locator: Locator? = null
        private var depth = 0

        // The resource being read (depth 2) and the text being collected (string or item).
        private var name: String? = null
        private var kind: ResourceKind? = null
        private var translatable = true
        private var line = 0
        private val texts = ArrayList<ResourceText>()
        private var text: StringBuilder? = null
        private var textDepth = 0
        private var part = ""
        private var textLine = 0

        override fun setDocumentLocator(locator: Locator) {
            this.locator = locator
        }

        override fun startElement(
            uri: String,
            localName: String,
            qName: String,
            attributes: Attributes,
        ) {
            depth++
            val here = locator?.lineNumber ?: 0
            when {
                depth == 2 && uri.isEmpty() -> {
                    startResource(localName, attributes, here)
                }

                depth == 3 && kind == ResourceKind.PLURALS && localName == "item" -> {
                    startText(attributes.getValue("quantity") ?: fail(here, "<item> without quantity"), here)
                }

                depth == 3 && kind == ResourceKind.ARRAY && localName == "item" -> {
                    startText(texts.size.toString(), here)
                }
            }
        }

        private fun startResource(
            element: String,
            attributes: Attributes,
            here: Int,
        ) {
            kind =
                when (element) {
                    "string" -> ResourceKind.STRING
                    "plurals" -> ResourceKind.PLURALS
                    "string-array" -> ResourceKind.ARRAY
                    else -> return
                }
            name = attributes.getValue("name") ?: fail(here, "<$element> without name")
            translatable = attributes.getValue("translatable") != "false"
            line = here
            texts.clear()
            if (kind == ResourceKind.STRING) startText("", here)
        }

        private fun startText(
            part: String,
            here: Int,
        ) {
            this.part = part
            text = StringBuilder()
            textDepth = depth
            textLine = here
        }

        override fun characters(
            ch: CharArray,
            start: Int,
            length: Int,
        ) {
            text?.append(ch, start, length)
        }

        override fun endElement(
            uri: String,
            localName: String,
            qName: String,
        ) {
            val collected = text
            // Markup inside a text (<xliff:g>, <b>) only adds its characters.
            if (collected != null && depth == textDepth) {
                texts += ResourceText(part, decode(collected.toString()), textLine)
                text = null
            }
            if (depth == 2) {
                val resourceName = name
                val resourceKind = kind
                if (resourceName != null && resourceKind != null) {
                    if (resourceName in resources) fail(line, "$resourceName is defined twice")
                    resources[resourceName] =
                        StringResource(resourceName, resourceKind, translatable, texts.toList(), label, line)
                }
                name = null
                kind = null
            }
            depth--
        }

        private fun fail(
            line: Int,
            message: String,
        ): Nothing = throw IllegalArgumentException("$label:$line: $message")
    }
}
