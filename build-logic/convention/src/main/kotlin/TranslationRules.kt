import org.w3c.dom.Element
import java.io.File
import java.util.Locale

/**
 * The checked-in part of the translation check (`config/translations.xml`): protected terms, quoted
 * cross-references and translations allowed to equal English. See docs/DEVELOPMENT.md.
 */
internal data class TranslationRules(
    /** Words that every translation keeps verbatim when the English string has them. */
    val terms: List<String>,
    /** Like [terms], for each whole-word match of a pattern (version numbers). */
    val termPatterns: List<Regex>,
    /** Strings that quote the text of another string. */
    val quotes: List<Quote>,
    /** Translations allowed to be identical to English. */
    val sameAsEnglish: List<SameAsEnglish>,
) {
    /** [string] contains the text of [quoted], without its format specifiers. */
    data class Quote(
        val string: String,
        val quoted: String,
    )

    /** The [locale] translation of [string] may equal English. */
    data class SameAsEnglish(
        val locale: String,
        val string: String,
    )

    companion object {
        /** Reads `config/translations.xml`. */
        fun parse(file: File): TranslationRules {
            val root = AndroidStrings.parseDocument(file).documentElement
            require(root.tagName == "translations") { "$file: the root element must be <translations>" }
            val terms = ArrayList<String>()
            val patterns = ArrayList<Regex>()
            val quotes = ArrayList<Quote>()
            val same = ArrayList<SameAsEnglish>()
            val children = root.childNodes
            for (i in 0 until children.length) {
                val element = children.item(i) as? Element ?: continue
                when (element.tagName) {
                    "term" -> {
                        terms += element.requiredText(file)
                    }

                    "term-pattern" -> {
                        patterns += Regex(element.requiredText(file))
                    }

                    "quote" -> {
                        quotes += Quote(element.required("string", file), element.required("quotes", file))
                    }

                    "same-as-english" -> {
                        same +=
                            SameAsEnglish(
                                AndroidStrings.normalizeTag(element.required("locale", file)),
                                element.required("string", file),
                            )
                    }

                    else -> {
                        throw IllegalArgumentException("$file: unknown element <${element.tagName}>")
                    }
                }
            }
            return TranslationRules(terms, patterns, quotes, same)
        }

        private fun Element.requiredText(file: File): String =
            textContent.trim().ifEmpty { throw IllegalArgumentException("$file: empty <$tagName>") }

        private fun Element.required(
            attribute: String,
            file: File,
        ): String =
            getAttribute(attribute).ifEmpty {
                throw IllegalArgumentException("$file: <$tagName> needs $attribute=\"…\"")
            }
    }
}

/** The app's language list in its two other places: the launcher's `locales_config.xml` and `AppLanguage.Language`. */
internal data class LanguageLists(
    val localeConfig: List<String>,
    val localeConfigFile: String,
    val enumTags: List<String>,
    val enumFile: String,
) {
    companion object {
        private const val ANDROID_NS = "http://schemas.android.com/apk/res/android"

        /** The `android:name` of each `<locale>` in a `locale-config` file. */
        fun parseLocaleConfig(file: File): List<String> {
            val locales = AndroidStrings.parseDocument(file).getElementsByTagName("locale")
            return (0 until locales.length).map { (locales.item(it) as Element).getAttributeNS(ANDROID_NS, "name") }
        }

        /**
         * The first constructor argument (the tag) of each entry of `enum class [enumName]` in Kotlin
         * [source]: entries are written `NAME("tag", ...)`, one per line.
         */
        fun parseEnumTags(
            source: String,
            enumName: String,
        ): List<String> {
            val declaration =
                Regex("""\benum\s+class\s+${Regex.escape(enumName)}\b""").find(source)
                    ?: throw IllegalArgumentException("no enum class $enumName")
            val open = source.indexOf('{', declaration.range.last)
            require(open >= 0) { "enum class $enumName has no body" }
            var depth = 0
            var close = open
            while (close < source.length) {
                when (source[close]) {
                    '{' -> depth++
                    '}' -> if (--depth == 0) break
                }
                close++
            }
            val body = source.substring(open + 1, close)
            val tags =
                Regex(
                    """(?m)^\s*[A-Z][A-Z0-9_]*\s*\(\s*"([^"]*)"""",
                ).findAll(body).map { it.groupValues[1] }.toList()
            require(tags.isNotEmpty()) { "enum class $enumName: no entries of the form NAME(\"tag\", ...)" }
            return tags
        }
    }
}

/** One thing wrong with a translation, or with the check's configuration. */
internal data class Violation(
    /** Short rule name, as docs/DEVELOPMENT.md lists them. */
    val rule: String,
    /** Gradle path of the module, or `-`. */
    val module: String,
    /** BCP 47 tag, or `-`. */
    val locale: String,
    /** Resource name, with `[quantity]` or `[index]` for a plural or array item, or `-`. */
    val key: String,
    val message: String,
    /** `file:line` or file. */
    val location: String,
) : Comparable<Violation> {
    override fun compareTo(other: Violation): Int =
        compareValuesBy(this, other, { it.rule }, { it.module }, { it.locale }, { it.key }, { it.message })

    override fun toString(): String = "$rule: $module $locale $key: $message ($location)"
}

/** Reads the string resources of the modules from disk. */
internal object TranslationSources {
    /**
     * The strings of each module in [resourceDirectories] (Gradle path → `src/main/res`, relative to
     * [root]) that has any, sorted by path.
     */
    fun load(
        root: File,
        resourceDirectories: Map<String, String>,
    ): List<ModuleStrings> =
        resourceDirectories.toSortedMap().mapNotNull { (path, relative) ->
            val res = File(root, relative)
            if (!res.isDirectory) return@mapNotNull null
            val english = readDirectory(File(res, "values"), "$relative/values")
            val translations = LinkedHashMap<String, StringTable>()
            val directories = LinkedHashMap<String, String>()
            for (dir in res
                .listFiles()
                .orEmpty()
                .filter { it.isDirectory }
                .sortedBy { it.name }) {
                val tag = AndroidStrings.localeOf(dir.name) ?: continue
                val label = "$relative/${dir.name}"
                val table = readDirectory(dir, label)
                if (table.isEmpty()) continue
                require(tag !in translations) { "$label: ${directories[tag]} already holds the $tag translation" }
                translations[tag] = table
                directories[tag] = label
            }
            if (english.isEmpty() &&
                translations.isEmpty()
            ) {
                null
            } else {
                ModuleStrings(path, relative, english, translations, directories)
            }
        }

    private fun readDirectory(
        dir: File,
        label: String,
    ): StringTable {
        val table = LinkedHashMap<String, StringResource>()
        val files = dir.listFiles { file -> file.isFile && file.name.endsWith(".xml") }.orEmpty().sortedBy { it.name }
        for (file in files) {
            for ((name, resource) in AndroidStrings.parse(file, "$label/${file.name}")) {
                require(
                    name !in table,
                ) { "${resource.file}:${resource.line}: $name is also defined in ${table.getValue(name).file}" }
                table[name] = resource
            }
        }
        return table
    }
}

/**
 * The translation rules Android Lint does not check (MissingTranslation, ExtraTranslation,
 * MissingQuantity and the format checks it does run are left to it):
 *
 * - `locales`: every module with translatable strings has the same translations, those of
 *   `locales_config.xml` (minus `en`), and `AppLanguage.Language` has the same tags as that file;
 * - `format`: a translation has the same format specifiers as English (a plural item: as English's
 *   `other` item);
 * - `term`: a protected term (or version number) of the English text is in the translation verbatim;
 * - `quote`: a string that quotes another string contains that string's translation;
 * - `french-spacing`: French has no ordinary space before `:` `;` `?` `!` `»` or after `«`;
 * - `untranslated`: a translation is not identical to English unless the config allows it;
 * - `not-translatable`: a string English marks `translatable="false"` has no translations;
 * - `config`: entries of the config that no longer apply.
 */
internal class TranslationValidator(
    private val rules: TranslationRules,
) {
    private val violations = ArrayList<Violation>()

    /** Every violation in [modules] and [languages], sorted. */
    fun validate(
        modules: List<ModuleStrings>,
        languages: LanguageLists,
    ): List<Violation> {
        violations.clear()
        checkLocales(modules, languages)
        val identical = HashSet<TranslationRules.SameAsEnglish>()
        for (module in modules) {
            for ((locale, table) in module.translations) {
                for (translated in table.values) checkTranslation(module, locale, translated, identical)
            }
        }
        for (entry in rules.sameAsEnglish - identical) {
            add(
                "config",
                "-",
                entry.locale,
                entry.string,
                "<same-as-english> allows a copy of English, but this translation is none; remove the entry",
                CONFIG_FILE,
            )
        }
        for (quote in rules.quotes) checkQuote(modules, quote)
        return violations.sorted()
    }

    private fun add(
        rule: String,
        module: String,
        locale: String,
        key: String,
        message: String,
        location: String,
    ) {
        violations += Violation(rule, module, locale, key, message, location)
    }

    // --- locales

    private fun checkLocales(
        modules: List<ModuleStrings>,
        languages: LanguageLists,
    ) {
        val configured = languages.localeConfig.map(AndroidStrings::normalizeTag)
        val enum = languages.enumTags.map(AndroidStrings::normalizeTag)
        for (tag in configured.duplicates()) add("locales", "-", tag, "-", "listed twice", languages.localeConfigFile)
        for (tag in enum.duplicates()) {
            add(
                "locales",
                "-",
                tag,
                "-",
                "AppLanguage.Language has it twice",
                languages.enumFile,
            )
        }
        for (tag in enum.toSet() - configured.toSet()) {
            add(
                "locales",
                "-",
                tag,
                "-",
                "AppLanguage.Language has it, but locales_config.xml does not list it",
                languages.enumFile,
            )
        }
        for (tag in configured.toSet() - enum.toSet()) {
            add(
                "locales",
                "-",
                tag,
                "-",
                "locales_config.xml lists it, but AppLanguage.Language does not have it",
                languages.localeConfigFile,
            )
        }
        if (ENGLISH !in configured) {
            add(
                "locales",
                "-",
                ENGLISH,
                "-",
                "locales_config.xml must list en, the language of res/values",
                languages.localeConfigFile,
            )
        }
        val expected = configured.toSet() - ENGLISH
        for (module in modules) {
            val translated = module.translations.keys
            if (module.english.values.none { it.translatable }) {
                for (tag in translated) {
                    add(
                        "locales",
                        module.path,
                        tag,
                        "-",
                        "translations, but values/ has no translatable string",
                        module.directories.getValue(tag),
                    )
                }
                continue
            }
            for (tag in (expected - translated).sorted()) {
                add(
                    "locales",
                    module.path,
                    tag,
                    "-",
                    "no translation (${directoryFor(
                        tag,
                    )}/ with the module's strings), but locales_config.xml lists $tag",
                    module.resourceDirectory,
                )
            }
            for (tag in translated - expected) {
                add(
                    "locales",
                    module.path,
                    tag,
                    "-",
                    "translated, but locales_config.xml does not list $tag",
                    module.directories.getValue(tag),
                )
            }
        }
    }

    // --- per translated resource

    private fun checkTranslation(
        module: ModuleStrings,
        locale: String,
        translated: StringResource,
        identical: MutableSet<TranslationRules.SameAsEnglish>,
    ) {
        // Resources English does not have, or has as another kind, are Lint's (ExtraTranslation).
        val english = module.english[translated.name] ?: return
        if (!english.translatable) {
            add(
                "not-translatable",
                module.path,
                locale,
                translated.name,
                "English marks it translatable=\"false\", so it must not be translated; remove it",
                "${translated.file}:${translated.line}",
            )
            return
        }
        if (english.kind != translated.kind) return
        val french = Locale.forLanguageTag(locale).language == "fr"
        val allowed = TranslationRules.SameAsEnglish(locale, translated.name)
        for (item in translated.texts) {
            val key = if (item.part.isEmpty()) translated.name else "${translated.name}[${item.part}]"
            val location = "${translated.file}:${item.line}"
            val report = { rule: String, message: String -> add(rule, module.path, locale, key, message, location) }
            val source = english.counterpart(item.part) ?: continue
            checkFormat(english, item, report)
            checkTerms(source.text, item.text, report)
            if (french) checkFrenchSpacing(item.text, report)
            if (item.text == source.text) {
                if (allowed in rules.sameAsEnglish) {
                    identical += allowed
                } else {
                    report(
                        "untranslated",
                        "same as English: ${quoted(item.text)}; translate it, or allow it in $CONFIG_FILE",
                    )
                }
            }
        }
    }

    /** The English text [part] of a translation corresponds to (a plural quantity English lacks: `other`). */
    private fun StringResource.counterpart(part: String): ResourceText? =
        text(part) ?: if (kind == ResourceKind.PLURALS) text(OTHER) else null

    private fun checkFormat(
        english: StringResource,
        item: ResourceText,
        report: (String, String) -> Unit,
    ) {
        val reference =
            (if (english.kind == ResourceKind.PLURALS) english.text(OTHER) else null) ?: english.counterpart(item.part)
                ?: return
        val expected = AndroidStrings.formatSpecifiers(reference.text)
        val actual = AndroidStrings.formatSpecifiers(item.text)
        if (canonical(actual) != canonical(expected)) {
            val what = if (english.kind == ResourceKind.PLURALS) "English's $OTHER item" else "English"
            report("format", "format specifiers ${list(actual)}, but $what has ${list(expected)}: ${quoted(item.text)}")
        }
    }

    /** Positional specifiers in any order, the others in their order. */
    private fun canonical(specifiers: List<String>): Pair<List<String>, List<String>> {
        val (positional, sequential) = specifiers.partition { POSITIONAL.matches(it) }
        return positional.sorted() to sequential
    }

    private fun checkTerms(
        english: String,
        translation: String,
        report: (String, String) -> Unit,
    ) {
        val required = LinkedHashSet<String>()
        rules.terms.filterTo(required) { AndroidStrings.containsWord(english, it) }
        for (pattern in rules.termPatterns) required += AndroidStrings.wordOccurrences(english, pattern)
        for (term in required) {
            if (!AndroidStrings.containsWord(translation, term)) {
                report("term", "English has ${quoted(term)}, which must appear verbatim: ${quoted(translation)}")
            }
        }
    }

    private fun checkFrenchSpacing(
        text: String,
        report: (String, String) -> Unit,
    ) {
        val problems = LinkedHashSet<String>()
        for ((i, c) in text.withIndex()) {
            if (c in SPACED_BEFORE && i > 0 && text[i - 1] == ' ') problems += "before \"$c\""
            if (c == '«' && text.getOrNull(i + 1) == ' ') problems += "after \"«\""
        }
        if (problems.isNotEmpty()) {
            report(
                "french-spacing",
                "ordinary space ${problems.joinToString(
                    ", ",
                )}; use U+00A0 or U+202F (\\u00A0, \\u202F): ${quoted(text)}",
            )
        }
    }

    // --- quotes

    private fun checkQuote(
        modules: List<ModuleStrings>,
        quote: TranslationRules.Quote,
    ) {
        val entry = "<quote string=\"${quote.string}\" quotes=\"${quote.quoted}\">"
        val configError = { message: String -> add("config", "-", "-", quote.string, "$entry: $message", CONFIG_FILE) }
        val module = modules.singleOrNull { quote.string in it.english }
        val quotedModule =
            module?.takeIf { quote.quoted in it.english } ?: modules.singleOrNull { quote.quoted in it.english }
        if (module == null || quotedModule == null) {
            configError("each string must exist in English in exactly one module")
            return
        }
        val string = module.english.getValue(quote.string)
        val quoted = quotedModule.english.getValue(quote.quoted)
        if (string.kind != ResourceKind.STRING || quoted.kind != ResourceKind.STRING || !string.translatable ||
            !quoted.translatable
        ) {
            configError("both must be translatable <string> resources")
            return
        }
        val englishQuote = quotedText(quoted.texts.single().text)
        if (!normalizeSpaces(string.texts.single().text).contains(englishQuote)) {
            configError("the English text does not contain ${quoted(englishQuote)}; update or remove the entry")
            return
        }
        for ((locale, table) in module.translations) {
            val translated = table[quote.string] ?: continue
            val target = quotedModule.translations[locale]?.get(quote.quoted) ?: continue
            if (translated.kind != ResourceKind.STRING || target.kind != ResourceKind.STRING) continue
            val expected = quotedText(target.texts.single().text)
            val text = translated.texts.single().text
            if (!normalizeSpaces(text).contains(expected)) {
                add(
                    "quote",
                    module.path,
                    locale,
                    quote.string,
                    "must quote ${quote.quoted} as translated, ${quoted(expected)}: ${quoted(text)}",
                    "${translated.file}:${translated.line}",
                )
            }
        }
    }

    internal companion object {
        const val CONFIG_FILE = "config/translations.xml"
        private const val ENGLISH = "en"
        private const val OTHER = "other"
        private const val SPACED_BEFORE = ":;?!»"
        private val POSITIONAL = Regex("""%\d+\$.*""")
        private const val SPECIFIER = """%(?:\d+\$)?[-#+ 0,(<]*\d*(?:\.\d+)?[tT]?[a-zA-Z]"""
        private val SPECIFIER_WITH_BRACKETS = Regex("""\s*(?:[(（\[［]\s*$SPECIFIER\s*[)）\]］]|$SPECIFIER)\s*""")

        /** A string's text as another string quotes it: without its format specifiers and brackets around them. */
        fun quotedText(text: String): String = normalizeSpaces(SPECIFIER_WITH_BRACKETS.replace(text, " "))

        /** Every kind of space as one ordinary space, runs collapsed, trimmed. */
        fun normalizeSpaces(text: String): String =
            buildString {
                for (c in text) {
                    if (Character.isWhitespace(c) || Character.isSpaceChar(c)) {
                        if (isNotEmpty() && last() != ' ') append(' ')
                    } else {
                        append(c)
                    }
                }
            }.trimEnd()

        /** The `values-*` directory name of [tag] (`pt-BR` → `values-pt-rBR`, `zh-Hans` → `values-b+zh+Hans`). */
        fun directoryFor(tag: String): String {
            val match = Regex("""([a-z]{2,3})(?:-([A-Z]{2}|[0-9]{3}))?""").matchEntire(tag)
            return when {
                match == null -> "values-b+" + tag.replace('-', '+')
                match.groupValues[2].isEmpty() -> "values-$tag"
                else -> "values-${match.groupValues[1]}-r${match.groupValues[2]}"
            }
        }

        private fun quoted(text: String): String = "\"" + text.replace("\n", "\\n") + "\""

        private fun list(specifiers: List<String>): String =
            if (specifiers.isEmpty()) "(none)" else specifiers.joinToString(" ")

        private fun <T> List<T>.duplicates(): Set<T> = groupingBy { it }.eachCount().filterValues { it > 1 }.keys
    }
}
