import org.junit.jupiter.api.Test
import kotlin.random.Random
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AndroidStringsTest {
    // Built from code points, so that no invisible character sits in the source.
    private val nbsp = Char(0xA0).toString()
    private val narrowNbsp = Char(0x202F).toString()

    /** `\u` as resource XML writes it. */
    private val u = "\\" + "u"

    private fun parse(body: String): StringTable =
        AndroidStrings.parse("<resources>\n$body\n</resources>", "values/strings.xml")

    private fun decoded(xml: String): String =
        parse("<string name=\"s\">$xml</string>")
            .getValue("s")
            .texts
            .single()
            .text

    @Test
    fun whitespaceCollapsesAndIsTrimmedOutsideQuotes() {
        assertEquals("a b c", decoded("  a \n\t b   c  "))
        assertEquals("a  b", decoded("\"a  b\""))
        assertEquals("x  y z", decoded("x\"  y\" z"))
    }

    @Test
    fun androidEscapesBecomeTheirCharacters() {
        assertEquals("can't \"x\" \\ @ ?", decoded("""can\'t \"x\" \\ \@ \?"""))
        assertEquals("a\nb\tc", decoded("""a\nb\tc"""))
        assertEquals("a$nbsp:", decoded("a${u}00A0:"))
        assertEquals("a$narrowNbsp;", decoded("a${u}202f;"))
        assertEquals("u12", decoded("${u}12"), "a short \\u escape is not a character")
    }

    @Test
    fun noBreakSpacesAreKeptWhetherRawOrCharacterReferences() {
        assertEquals("a$nbsp: b$narrowNbsp!", decoded("a$nbsp: b&#8239;!"))
        assertEquals("a$nbsp:", decoded("a&#160;:"))
        assertEquals("a$nbsp:", decoded("  a$nbsp:  "))
    }

    @Test
    fun markupInsideAStringOnlyAddsItsText() {
        val xml =
            "<string name=\"s\" xmlns:xliff=\"urn:oasis:names:tc:xliff:document:1.2\">" +
                "Hi <xliff:g id=\"n\">%1\$s</xliff:g>, <b>bold</b></string>"
        assertEquals(
            "Hi %1\$s, bold",
            parse(xml)
                .getValue("s")
                .texts
                .single()
                .text,
        )
    }

    @Test
    fun pluralsArraysAndAttributes() {
        val table =
            parse(
                """
                <string name="a" translatable="false">A</string>
                <plurals name="p">
                    <item quantity="one">%d minute</item>
                    <item quantity="other">%d minutes</item>
                </plurals>
                <string-array name="arr"><item>x</item><item>y</item></string-array>
                <color name="c">#fff</color>
                """.trimIndent(),
            )
        assertEquals(setOf("a", "p", "arr"), table.keys)
        assertFalse(table.getValue("a").translatable)
        assertEquals(ResourceKind.PLURALS, table.getValue("p").kind)
        assertEquals(
            listOf("one" to "%d minute", "other" to "%d minutes"),
            table.getValue("p").texts.map { it.part to it.text },
        )
        assertEquals(listOf("0" to "x", "1" to "y"), table.getValue("arr").texts.map { it.part to it.text })
        assertEquals(3, table.getValue("p").line)
        assertEquals(
            4,
            table
                .getValue("p")
                .texts
                .first()
                .line,
        )
    }

    @Test
    fun brokenFilesFailWithTheirLocation() {
        val twice =
            assertFailsWith<IllegalArgumentException> {
                parse("<string name=\"a\">x</string>\n<string name=\"a\">y</string>")
            }
        assertTrue(twice.message.orEmpty().startsWith("values/strings.xml:3:"), twice.message)
        assertFailsWith<IllegalArgumentException> { parse("<string>no name</string>") }
        assertFailsWith<IllegalArgumentException> { parse("<plurals name=\"p\"><item>x</item></plurals>") }
        assertFailsWith<IllegalArgumentException> { AndroidStrings.parse("<resources><string name=\"a\">", "f") }
        assertFailsWith<IllegalArgumentException> { AndroidStrings.parse("", "f") }
        assertFailsWith<IllegalArgumentException>("no DTDs, so no entity expansion") {
            AndroidStrings.parse("""<?xml version="1.0"?><!DOCTYPE r [<!ENTITY e "x">]><resources/>""", "f")
        }
    }

    @Test
    fun randomGarbageFailsWithAnException() {
        val random = Random(7)
        repeat(200) {
            val bytes = String(CharArray(random.nextInt(0, 64)) { "<>&\"'/=ab \n\\%".random(random) })
            try {
                AndroidStrings.parse(bytes, "f")
            } catch (expected: IllegalArgumentException) {
                assertTrue(expected.message.orEmpty().startsWith("f:"))
            }
        }
    }

    @Test
    fun localeDirectories() {
        assertEquals("fr", AndroidStrings.localeOf("values-fr"))
        assertEquals("pt-BR", AndroidStrings.localeOf("values-pt-rBR"))
        assertEquals("zh-Hans", AndroidStrings.localeOf("values-b+zh+Hans"))
        assertEquals("es-419", AndroidStrings.localeOf("values-b+es+419"))
        assertEquals("es-419", AndroidStrings.localeOf("values-es-r419"))
        val others =
            listOf("values", "values-night", "values-v31", "values-fr-v31", "values-land", "values-car", "drawable-fr")
        for (other in others) assertNull(AndroidStrings.localeOf(other), other)
        assertEquals("values-pt-rBR", TranslationValidator.directoryFor("pt-BR"))
        assertEquals("values-b+zh+Hans", TranslationValidator.directoryFor("zh-Hans"))
        assertEquals("values-fr", TranslationValidator.directoryFor("fr"))
    }

    @Test
    fun formatSpecifiers() {
        assertEquals(listOf("%1\$s", "%2\$d"), AndroidStrings.formatSpecifiers("%1\$s has %2\$d"))
        assertEquals(listOf("%d", "%.1f", "%s"), AndroidStrings.formatSpecifiers("%d at %.1f by %s"))
        assertEquals(emptyList(), AndroidStrings.formatSpecifiers("100%% and %n"))
        assertEquals(listOf("%"), AndroidStrings.formatSpecifiers("50%"))
        assertEquals(listOf("%1\$s"), AndroidStrings.formatSpecifiers("（%1\$s）"))
    }

    @Test
    fun termsMatchWholeWordsOnly() {
        assertTrue(AndroidStrings.containsWord("If it prints null, leave", "null"))
        assertFalse(AndroidStrings.containsWord("nullable", "null"))
        assertFalse(AndroidStrings.containsWord("annul", "null"))
        assertFalse(AndroidStrings.containsWord("nullо", "null"), "a Cyrillic letter continues the word")
        assertTrue(AndroidStrings.containsWord("если выводится null, удалите", "null"))
        assertTrue(AndroidStrings.containsWord("AirPlayで受信", "AirPlay"), "Japanese runs words together")
        assertTrue(AndroidStrings.containsWord("启用AirPlay应用", "AirPlay"))
        assertTrue(AndroidStrings.containsWord("PIN-код", "PIN"))
        assertFalse(AndroidStrings.containsWord("PINs", "PIN"))
        assertTrue(AndroidStrings.containsWord("si HDMI-CEC est activé", "HDMI-CEC"))
        assertFalse(AndroidStrings.containsWord("sur Fire  TV", "Fire TV"))
        assertTrue(AndroidStrings.containsWord("(Fire OS 8.1.5.5 ou ultérieure)", "8.1.5.5"))
        assertFalse(AndroidStrings.containsWord("18.1.5.5", "8.1.5.5"))
        assertFalse(AndroidStrings.containsWord("airplay", "AirPlay"), "verbatim, case included")
        val versions = Regex("""\d+(?:\.\d+){2,}""")
        assertEquals(
            listOf("8.1.5.5", "0.2.0"),
            AndroidStrings.wordOccurrences("Fire OS 8.1.5.5, version 0.2.0, Wi-Fi 2.4", versions).toList(),
        )
    }
}
