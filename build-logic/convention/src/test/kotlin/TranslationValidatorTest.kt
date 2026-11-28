import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class TranslationValidatorTest {
    // Built from code points, so that no invisible character sits in the source.
    private val nbsp = Char(0xA0).toString()
    private val narrowNbsp = Char(0x202F).toString()

    /** The Android escapes of U+00A0 and U+202F, as resource XML writes them. */
    private val nbspEscape = "\\" + "u00A0"
    private val narrowEscape = "\\" + "u202F"

    private val english =
        """
        <string name="app_name" translatable="false">Rozan</string>
        <string name="receiver">AirPlay to %1${'$'}s</string>
        <string name="keep_awake">Keep screen awake</string>
        <string name="needs_keep_awake">Turn on Keep screen awake to use the screensaver</string>
        <string name="install">Install update %1${'$'}s</string>
        <string name="ask">Version %1${'$'}s is downloaded: select Install update.</string>
        <string name="adb_note">If it prints null, leave out LIST. Fire OS 8.1.5.5 or later.</string>
        <string name="wifi">Keeps Wi-Fi awake</string>
        <string name="audio">Audio</string>
        <plurals name="minutes">
            <item quantity="one">%d minute</item>
            <item quantity="other">%d minutes</item>
        </plurals>
        """.trimIndent()

    private val french =
        """
        <string name="receiver">AirPlay vers %1${'$'}s</string>
        <string name="keep_awake">Garder l\'écran allumé</string>
        <string name="needs_keep_awake">Activez «${nbspEscape}Garder l\'écran allumé$nbspEscape» pour la veille</string>
        <string name="install">Installer la mise à jour %1${'$'}s</string>
        <string name="ask">La version %1${'$'}s est téléchargée&#8239;: sélectionnez Installer la mise à jour.</string>
        <string name="adb_note">S\'il affiche null, retirez LIST. Fire OS 8.1.5.5 ou ultérieure.</string>
        <string name="wifi">Garde le Wi-Fi actif</string>
        <string name="audio">Audio</string>
        <plurals name="minutes">
            <item quantity="one">%d minute</item>
            <item quantity="many">%d de minutes</item>
            <item quantity="other">%d minutes</item>
        </plurals>
        """.trimIndent()

    private val japanese =
        """
        <string name="receiver">%1${'$'}s への AirPlay</string>
        <string name="keep_awake">画面をオンのままにする</string>
        <string name="needs_keep_awake">スクリーン セーバーを使うには「画面をオンのままにする」をオンにしてください</string>
        <string name="install">アップデートをインストール（%1${'$'}s）</string>
        <string name="ask">バージョン %1${'$'}s をダウンロードしました。「アップデートをインストール」を選択してください。</string>
        <string name="adb_note">出力がnullの場合は、LISTを省いてください。Fire OS 8.1.5.5 以降。</string>
        <string name="wifi">WLAN をオンのままにする</string>
        <string name="audio">オーディオ</string>
        <plurals name="minutes">
            <item quantity="other">%d 分</item>
        </plurals>
        """.trimIndent()

    private val rules =
        TranslationRules(
            terms = listOf("Rozan", "AirPlay", "null", "LIST", "Fire OS", "HDMI-CEC"),
            termPatterns = listOf(Regex("""\d+(?:\.\d+){2,}""")),
            quotes =
                listOf(
                    TranslationRules.Quote("needs_keep_awake", "keep_awake"),
                    TranslationRules.Quote("ask", "install"),
                ),
            sameAsEnglish =
                listOf(
                    TranslationRules.SameAsEnglish("fr", "audio"),
                    TranslationRules.SameAsEnglish("fr", "minutes"),
                ),
        )

    /** [rules] without the entries that name strings of [english]. */
    private val bare = rules.copy(quotes = emptyList(), sameAsEnglish = emptyList())

    private val languages =
        LanguageLists(listOf("en", "fr", "ja"), "locales_config.xml", listOf("en", "fr", "ja"), "AppLanguage.kt")

    private fun table(
        dir: String,
        body: String,
    ): StringTable = AndroidStrings.parse("<resources>\n$body\n</resources>", "res/$dir/strings.xml")

    private fun module(
        english: String = this.english,
        translations: Map<String, String> = mapOf("fr" to french, "ja" to japanese),
        path: String = ":app",
    ) = ModuleStrings(
        path,
        "res",
        table("values", english),
        translations.mapValues { (tag, body) -> table(TranslationValidator.directoryFor(tag), body) },
        translations.mapValues { (tag, _) -> "res/${TranslationValidator.directoryFor(tag)}" },
    )

    /** A module whose only translations are fr and ja, each [english] with [old] replaced by that language's word. */
    private fun moduleOf(
        english: String,
        old: String,
        fr: String,
        ja: String,
    ) = module(
        english = english,
        translations = mapOf("fr" to english.replace(old, fr), "ja" to english.replace(old, ja)),
    )

    private fun violations(
        modules: List<ModuleStrings> = listOf(module()),
        rules: TranslationRules = this.rules,
        languages: LanguageLists = this.languages,
    ): List<Violation> = TranslationValidator(rules).validate(modules, languages)

    /** (rule, locale, key) of each violation. */
    private fun found(
        modules: List<ModuleStrings> = listOf(module()),
        rules: TranslationRules = this.rules,
        languages: LanguageLists = this.languages,
    ): Set<Triple<String, String, String>> =
        violations(modules, rules, languages).map { Triple(it.rule, it.locale, it.key) }.toSet()

    /** The (rule, locale, key) found when [old] in [french] becomes [new]; [old] must occur. */
    private fun foundInFrench(
        old: String,
        new: String,
    ): Set<Triple<String, String, String>> {
        assertTrue(old in french, old)
        return found(listOf(module(translations = mapOf("fr" to french.replace(old, new), "ja" to japanese))))
    }

    private fun fr(
        rule: String,
        key: String,
    ) = setOf(Triple(rule, "fr", key))

    @Test
    fun goodTranslationsPass() {
        assertEquals(emptyList(), violations())
    }

    @Test
    fun localesMustMatchTheLocaleConfigInEveryModule() {
        val ui =
            module(
                english = "<string name=\"on\">On</string>",
                translations = mapOf("fr" to "<string name=\"on\">Activé</string>"),
                path = ":ui",
            )
        assertEquals(setOf(Triple("locales", "ja", "-")), found(listOf(module(), ui)))
        val extra = module(translations = mapOf("fr" to french, "ja" to japanese, "de" to japanese))
        assertEquals(setOf(Triple("locales", "de", "-")), found(listOf(extra)))
        val message = violations(listOf(module(), ui)).single().toString()
        assertTrue(":ui ja -" in message && "values-ja" in message, message)
    }

    @Test
    fun localeConfigAndEnumMustAgree() {
        val enumHasMore = languages.copy(enumTags = listOf("en", "fr", "ja", "zh-Hans"))
        assertEquals(setOf(Triple("locales", "zh-Hans", "-")), found(languages = enumHasMore))
        val configHasMore = languages.copy(localeConfig = listOf("en", "fr", "ja", "zh-hans"))
        assertEquals(setOf(Triple("locales", "zh-Hans", "-")), found(languages = configHasMore))
        assertEquals(2, violations(languages = configHasMore).size, "the config and the module are told")
        val noEnglish = languages.copy(localeConfig = listOf("fr", "ja"), enumTags = listOf("fr", "ja"))
        assertEquals(setOf(Triple("locales", "en", "-")), found(languages = noEnglish))
    }

    @Test
    fun aModuleWithOnlyUntranslatableStringsNeedsNoTranslations() {
        val names =
            module(
                english = "<string name=\"app_name\" translatable=\"false\">Rozan AirPlay</string>",
                translations = emptyMap(),
                path = ":b",
            )
        assertEquals(emptyList(), violations(listOf(module(), names)))
    }

    @Test
    fun formatSpecifiersMustMatchEnglish() {
        assertEquals(fr("format", "receiver"), foundInFrench("vers %1\$s", "vers %1\$d"))
        assertEquals(fr("format", "receiver"), foundInFrench("vers %1\$s", "vers %s"))
        assertEquals(fr("format", "receiver"), foundInFrench("vers %1\$s", "vers"))
        assertEquals(fr("format", "minutes[many]"), foundInFrench("%d de minutes", "beaucoup de minutes"))
    }

    @Test
    fun positionalSpecifiersMayMoveSequentialOnesMayNot() {
        val english = "<string name=\"two\">%1\$s at %2\$s</string><string name=\"seq\">%s has %d</string>"
        val french = "<string name=\"two\">à %2\$s, %1\$s</string><string name=\"seq\">%d pour %s</string>"
        val japanese = english.replace("at", "の").replace("has", "は")
        val modules = listOf(module(english = english, translations = mapOf("fr" to french, "ja" to japanese)))
        assertEquals(fr("format", "seq"), found(modules, rules = bare))
    }

    @Test
    fun protectedTermsMustStayVerbatim() {
        assertEquals(fr("term", "receiver"), foundInFrench("AirPlay vers", "Airplay vers"))
        assertEquals(fr("term", "adb_note"), foundInFrench("affiche null", "affiche nul"))
        assertEquals(fr("term", "adb_note"), foundInFrench("affiche null", "affiche nullement"))
        assertEquals(fr("term", "adb_note"), foundInFrench("retirez LIST", "retirez LISTE"))
        assertEquals(fr("term", "adb_note"), foundInFrench("8.1.5.5 ou", "8.1.5 ou"))
        assertEquals(fr("term", "adb_note"), foundInFrench("Fire OS 8", "FireOS 8"))
        val nul = french.replace("affiche null", "affiche nul")
        val broken = module(translations = mapOf("fr" to nul, "ja" to japanese))
        val message = violations(listOf(broken)).single().message
        assertTrue("\"null\"" in message, message)
    }

    @Test
    fun aTermOnlyCountsWhenEnglishHasItAsAWord() {
        val modules = listOf(moduleOf("<string name=\"n\">Values are nullable</string>", "Values are", "Valeurs", "値は"))
        assertEquals(emptyList(), violations(modules, rules = bare))
    }

    @Test
    fun quotesMustMatchTheQuotedTranslation() {
        assertEquals(fr("quote", "needs_keep_awake"), foundInFrench("${nbspEscape}Garder", "${nbspEscape}Laisser"))
        assertEquals(fr("quote", "ask"), foundInFrench("Installer la mise à jour %1\$s", "Mettre à jour (%1\$s)"))
        val renamed = french.replace("Installer la mise à jour %1\$s", "Mettre à jour (%1\$s)")
        val message = violations(listOf(module(translations = mapOf("fr" to renamed, "ja" to japanese)))).single()
        assertTrue("\"Mettre à jour\"" in message.message, message.message)
    }

    @Test
    fun quotesAreCheckedInEnglishToo() {
        val stale = english.replace("select Install update", "select Update")
        assertEquals(setOf(Triple("config", "-", "ask")), found(listOf(module(english = stale))))
        val missing = rules.copy(quotes = listOf(TranslationRules.Quote("ask", "no_such_string")))
        assertEquals(setOf(Triple("config", "-", "ask")), found(rules = missing))
        val plural = rules.copy(quotes = listOf(TranslationRules.Quote("ask", "minutes")))
        assertEquals(setOf(Triple("config", "-", "ask")), found(rules = plural))
    }

    @Test
    fun quotedTextLeavesOutSpecifiersAndTheirBrackets() {
        assertEquals("Install update", TranslationValidator.quotedText("Install update %1\$s"))
        assertEquals("アップデートをインストール", TranslationValidator.quotedText("アップデートをインストール（%1\$s）"))
        assertEquals("Mettre à jour", TranslationValidator.quotedText("Mettre à jour (%1\$s)"))
        assertEquals("Install update now", TranslationValidator.quotedText("Install %1\$s update now"))
        assertEquals("(note", TranslationValidator.quotedText("(note %1\$s"), "only brackets around the specifier")
    }

    @Test
    fun frenchNeedsNoBreakSpacesAroundPunctuation() {
        for (bad in listOf("téléchargée :", "téléchargée ;", "téléchargée ?", "téléchargée !")) {
            assertEquals(fr("french-spacing", "ask"), foundInFrench("téléchargée&#8239;:", bad), bad)
        }
        assertEquals(fr("french-spacing", "needs_keep_awake"), foundInFrench("«${nbspEscape}Garder", "« Garder"))
        assertEquals(fr("french-spacing", "needs_keep_awake"), foundInFrench("allumé$nbspEscape»", "allumé »"))
        // Raw U+00A0 and U+202F, and the escapes, are all fine.
        assertEquals(emptySet(), foundInFrench("«${nbspEscape}Garder", "«${nbsp}Garder"))
        assertEquals(emptySet(), foundInFrench("allumé$nbspEscape»", "allumé$narrowNbsp»"))
        assertEquals(emptySet(), foundInFrench("téléchargée&#8239;:", "téléchargée$narrowEscape:"))
        // An escaped ordinary space is still an ordinary space.
        assertEquals(fr("french-spacing", "ask"), foundInFrench("téléchargée&#8239;:", "téléchargée\\u0020:"))
    }

    @Test
    fun otherLanguagesMayUseOrdinarySpaces() {
        val japanese = japanese.replace("をダウンロードしました。", "をダウンロードしました : ")
        assertEquals(emptyList(), violations(listOf(module(translations = mapOf("fr" to french, "ja" to japanese)))))
    }

    @Test
    fun copiesOfEnglishNeedAnEntry() {
        assertEquals(fr("untranslated", "wifi"), foundInFrench("Garde le Wi-Fi actif", "Keeps Wi-Fi awake"))
        val unlisted = rules.copy(sameAsEnglish = listOf(TranslationRules.SameAsEnglish("fr", "minutes")))
        assertEquals(fr("untranslated", "audio"), found(rules = unlisted))
        // English's other item stands for quantities English does not have.
        val noAllow = rules.copy(sameAsEnglish = listOf(TranslationRules.SameAsEnglish("fr", "audio")))
        assertEquals(fr("untranslated", "minutes[one]") + fr("untranslated", "minutes[other]"), found(rules = noAllow))
    }

    @Test
    fun entriesThatNoLongerApplyFail() {
        val stale = rules.copy(sameAsEnglish = rules.sameAsEnglish + TranslationRules.SameAsEnglish("ja", "audio"))
        assertEquals(setOf(Triple("config", "ja", "audio")), found(rules = stale))
    }

    @Test
    fun untranslatableStringsMustNotBeTranslated() {
        assertEquals(
            fr("not-translatable", "app_name"),
            foundInFrench("</plurals>", "</plurals>\n<string name=\"app_name\">Rozan</string>"),
        )
    }

    @Test
    fun violationsNameModuleLanguageStringAndFile() {
        val broken = module(translations = mapOf("fr" to french.replace("vers %1\$s", "vers %1\$d"), "ja" to japanese))
        assertEquals(
            "format: :app fr receiver: format specifiers %1\$d, but English has %1\$s: " +
                "\"AirPlay vers %1\$d\" (res/values-fr/strings.xml:2)",
            violations(listOf(broken)).single().toString(),
        )
    }

    @Test
    fun loadsModulesFromDisk(
        @TempDir root: File,
    ) {
        fun write(
            path: String,
            body: String,
        ) = File(root, path).apply { parentFile.mkdirs() }.writeText("<resources>\n$body\n</resources>")
        write("a/src/main/res/values/strings.xml", english)
        write("a/src/main/res/values/colors.xml", "<color name=\"c\">#fff</color>")
        write("a/src/main/res/values-fr/strings.xml", french)
        write("a/src/main/res/values-b+ja/strings.xml", japanese)
        write("a/src/main/res/values-night/colors.xml", "<color name=\"c\">#000</color>")
        write("a/src/main/res/values-fr-v31/strings.xml", "<string name=\"wifi\">x</string>")
        write("b/src/main/res/values-ja/colors.xml", "<color name=\"c\">#000</color>")
        val directories = mapOf(":a" to "a/src/main/res", ":b" to "b/src/main/res", ":c" to "c/src/main/res")
        val modules = TranslationSources.load(root, directories)
        assertEquals(listOf(":a"), modules.map { it.path })
        assertEquals(setOf("fr", "ja"), modules.single().translations.keys)
        assertEquals("a/src/main/res/values-b+ja", modules.single().directories["ja"])
        assertEquals(emptyList(), TranslationValidator(rules).validate(modules, languages))

        write("a/src/main/res/values/more.xml", "<string name=\"audio\">Sound</string>")
        val twice = assertFailsWith<IllegalArgumentException> { TranslationSources.load(root, directories) }
        assertEquals(
            "a/src/main/res/values/strings.xml:10: audio is also defined in a/src/main/res/values/more.xml",
            twice.message,
        )
    }

    @Test
    fun readsTheLanguageLists(
        @TempDir root: File,
    ) {
        val config =
            File(root, "locales_config.xml").apply {
                writeText(
                    """
                    <locale-config xmlns:android="http://schemas.android.com/apk/res/android">
                        <locale android:name="en" />
                        <locale android:name="zh-Hans" />
                    </locale-config>
                    """.trimIndent(),
                )
            }
        assertEquals(listOf("en", "zh-Hans"), LanguageLists.parseLocaleConfig(config))
        val source =
            """
            public object AppLanguage {
                /** A language. */
                public enum class Language(
                    /** BCP 47 tag. */
                    public val tag: String,
                    public val nativeName: String,
                ) {
                    /** English. */
                    ENGLISH("en", "English"),

                    CHINESE_SIMPLIFIED("zh-Hans", "简体中文"),
                }

                private const val TAG = "AppLanguage"
                private enum class Other { X("x") }
            }
            """.trimIndent()
        assertEquals(listOf("en", "zh-Hans"), LanguageLists.parseEnumTags(source, "Language"))
        assertFailsWith<IllegalArgumentException> { LanguageLists.parseEnumTags(source, "Missing") }
        assertFailsWith<IllegalArgumentException> { LanguageLists.parseEnumTags("enum class Language { }", "Language") }
    }

    @Test
    fun readsTheConfig(
        @TempDir root: File,
    ) {
        val file =
            File(root, "translations.xml").apply {
                writeText(
                    """
                    <translations>
                        <!-- comment -->
                        <term>AirPlay</term>
                        <term-pattern>\d+(?:\.\d+){2,}</term-pattern>
                        <quote string="a" quotes="b" />
                        <same-as-english locale="zh-hans" string="c" />
                    </translations>
                    """.trimIndent(),
                )
            }
        val rules = TranslationRules.parse(file)
        assertEquals(listOf("AirPlay"), rules.terms)
        assertEquals(listOf("""\d+(?:\.\d+){2,}"""), rules.termPatterns.map { it.pattern })
        assertEquals(listOf(TranslationRules.Quote("a", "b")), rules.quotes)
        assertEquals(listOf(TranslationRules.SameAsEnglish("zh-Hans", "c")), rules.sameAsEnglish)
        file.writeText("<translations><terms>AirPlay</terms></translations>")
        assertFailsWith<IllegalArgumentException> { TranslationRules.parse(file) }
        file.writeText("<translations><quote string=\"a\" /></translations>")
        assertFailsWith<IllegalArgumentException> { TranslationRules.parse(file) }
    }
}
