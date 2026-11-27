package ai.aneesiqbal.rozan.airplay.plist

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class XmlPlistTest {
    private fun doc(body: String): String =
        "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n<plist version=\"1.0\">\n$body\n</plist>\n"

    private fun decodeBody(body: String): PlistValue = XmlPlist.decode(doc(body))

    private fun assertRejected(text: String) {
        assertFailsWith<PlistException> { XmlPlist.decode(text) }
    }

    @Test
    fun `writer output matches Apple's layout`() {
        val value =
            buildPlistDict {
                put("name", "Living Room")
                put("count", 3)
                put("ratio", 0.5)
                put("on", true)
                put("blob", byteArrayOf(1, 2, 3))
                put("list", PlistArray(listOf(PlistString("a"), PlistArray(emptyList()))))
                put("empty", PlistDict(emptyMap()))
            }
        val expected =
            """
            <?xml version="1.0" encoding="UTF-8"?>
            <!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
            <plist version="1.0">
            <dict>
            	<key>name</key>
            	<string>Living Room</string>
            	<key>count</key>
            	<integer>3</integer>
            	<key>ratio</key>
            	<real>0.5</real>
            	<key>on</key>
            	<true/>
            	<key>blob</key>
            	<data>AQID</data>
            	<key>list</key>
            	<array>
            		<string>a</string>
            		<array/>
            	</array>
            	<key>empty</key>
            	<dict/>
            </dict>
            </plist>

            """.trimIndent()
        assertEquals(expected, XmlPlist.encodeToString(value))
        assertContentEquals(expected.toByteArray(), XmlPlist.encode(value))
    }

    @Test
    fun `writer escapes markup and keeps carriage returns`() {
        val text = "a<b>&c\r\nd\t\"'"
        val xml = XmlPlist.encodeToString(PlistString(text))
        assertEquals(true, xml.contains("<string>a&lt;b&gt;&amp;c&#13;\nd\t\"'</string>"))
        assertEquals(PlistString(text), XmlPlist.decode(xml))
    }

    @Test
    fun `writer rejects characters XML cannot carry`() {
        val bads =
            listOf("\u0000", "\u0001", "\u000B", "\u001F", "\uD800", "\uDC00", "x\uD83C") +
                listOf(Char(0xFFFE), Char(0xFFFF)).map { it.toString() }
        for (bad in bads) {
            assertFailsWith<PlistException>(bad.toCharArray().joinToString { it.code.toString(16) }) {
                XmlPlist.encode(PlistString(bad))
            }
            assertFailsWith<PlistException> { XmlPlist.encode(PlistDict(mapOf(bad to PlistBool(true)))) }
        }
    }

    @Test
    fun `writer formats special numbers, large integers, dates and uids`() {
        fun body(value: PlistValue) = XmlPlist.encodeToString(value).lines()[3]
        assertEquals("<real>nan</real>", body(PlistReal(Double.NaN)))
        assertEquals("<real>+infinity</real>", body(PlistReal(Double.POSITIVE_INFINITY)))
        assertEquals("<real>-infinity</real>", body(PlistReal(Double.NEGATIVE_INFINITY)))
        assertEquals("<integer>18446744073709551615</integer>", body(PlistInteger.ofUnsigned(ULong.MAX_VALUE)))
        assertEquals("<integer>-9223372036854775808</integer>", body(PlistInteger(Long.MIN_VALUE)))
        assertEquals("<date>2001-01-01T00:00:00Z</date>", body(PlistDate(0.9)))
        assertEquals("<date>2000-12-31T23:59:59Z</date>", body(PlistDate(-0.5)))
        assertEquals("<date>0001-01-01T00:00:00Z</date>", body(PlistDate(-63_113_904_000.0)))
        assertEquals(PlistUid(42), XmlPlist.decode(XmlPlist.encode(PlistUid(42))))
        assertEquals("<false/>", body(PlistBool(false)))
    }

    @Test
    fun `writer rejects dates XML cannot express`() {
        for (seconds in listOf(
            Double.NaN,
            Double.POSITIVE_INFINITY,
            -1e20,
            1e20,
            -63_113_904_001.0,
            252_423_993_600.0,
        )) {
            assertFailsWith<PlistException>("$seconds") { XmlPlist.encode(PlistDate(seconds)) }
        }
    }

    @Test
    fun `reader accepts the XML features plists use`() {
        val text =
            "<?xml version=\"1.0\"?>\n<!-- c -->\n<!DOCTYPE plist SYSTEM 'x>y'>\n" +
                "<plist version='1.0'>\n<!-- before -->\n<dict>\n" +
                "  <key>a&#x26;b</key> <!-- between --> <string>&lt;&gt;&amp;&quot;&apos;&#65;&#x1F351;</string>\n" +
                "  <key>cdata</key><string><![CDATA[<raw> & ]]>tail</string>\n" +
                "  <key>crlf</key><string>x\r\ny\rz</string>\n" +
                "  <key>empty</key><string/>\n" +
                "  <key>int</key><integer> -42 </integer>\n" +
                "  <key>hex</key><integer>0x1F</integer>\n" +
                "  <key>real</key><real>\n1.5e3\n</real>\n" +
                "  <key>bool</key><true></true>\n" +
                "  <key>data</key><data>\n\tAQID\n\tBA==\n</data>\n" +
                "  <key>nodata</key><data/>\n" +
                "  <key>date</key><date>2024-02-29T12:34:56Z</date>\n" +
                "</dict>\n</plist>\n"
        val dict = XmlPlist.decode(text.toByteArray()) as PlistDict
        assertEquals("<>&\"'A🍑", dict.string("a&b"))
        assertEquals("<raw> & tail", dict.string("cdata"))
        assertEquals("x\ny\nz", dict.string("crlf"))
        assertEquals("", dict.string("empty"))
        assertEquals(-42L, dict.long("int"))
        assertEquals(31L, dict.long("hex"))
        assertEquals(1500.0, dict.double("real"))
        assertEquals(true, dict.bool("bool"))
        assertContentEquals(byteArrayOf(1, 2, 3, 4), dict.data("data"))
        assertContentEquals(ByteArray(0), dict.data("nodata"))
        assertEquals(PlistDate(1_709_210_096.0 - PlistDate.REFERENCE_DATE_UNIX_SECONDS), dict["date"])
    }

    @Test
    fun `reader accepts a value without the plist wrapper`() {
        assertEquals(PlistString("x"), XmlPlist.decode("<string>x</string>"))
        assertEquals(PlistArray(emptyList()), XmlPlist.decode("<?xml version=\"1.0\"?><array/>"))
    }

    @Test
    fun `reader parses reals strictly`() {
        assertEquals(PlistReal(Double.NEGATIVE_INFINITY), decodeBody("<real>-inf</real>"))
        assertEquals(PlistReal(Double.POSITIVE_INFINITY), decodeBody("<real>Infinity</real>"))
        assertEquals(PlistReal(Double.NaN), decodeBody("<real>NaN</real>"))
        assertEquals(PlistReal(0.25), decodeBody("<real>.25</real>"))
        assertEquals(PlistReal(3.0), decodeBody("<real>3.</real>"))
        for (bad in listOf("", "1.0f", "1.0d", "0x1p3", "1e", "--1", "1 2", "e5", "١")) {
            assertRejected(doc("<real>$bad</real>"))
        }
    }

    @Test
    fun `reader parses integers strictly`() {
        assertEquals(PlistInteger.ofUnsigned(ULong.MAX_VALUE), decodeBody("<integer>18446744073709551615</integer>"))
        assertEquals(PlistInteger.ofUnsigned(ULong.MAX_VALUE), decodeBody("<integer>0xFFFFFFFFFFFFFFFF</integer>"))
        assertEquals(PlistInteger(5), decodeBody("<integer>+5</integer>"))
        for (bad in listOf(
            "",
            "18446744073709551616",
            "-9223372036854775809",
            "1.0",
            "0x",
            "0x+1",
            "-0x1",
            "12a",
            "١",
        )) {
            assertRejected(doc("<integer>$bad</integer>"))
        }
        assertRejected(doc("<integer/>"))
    }

    @Test
    fun `reader rejects bad dates and base64`() {
        for (bad in listOf(
            "2024-02-30T00:00:00Z",
            "2024-13-01T00:00:00Z",
            "2024-01-01T24:00:00Z",
            "2024-01-01 00:00:00Z",
            "2024-01-01T00:00:00",
        )) {
            assertRejected(doc("<date>$bad</date>"))
        }
        for (bad in listOf("A===", "AQ!D", "AQID=", "A")) assertRejected(doc("<data>$bad</data>"))
    }

    @Test
    fun `reader rejects malformed documents`() {
        val bad =
            listOf(
                "",
                "   ",
                "<plist>",
                "<plist/>",
                "<plist></plist>",
                doc("<string>x</string><string>y</string>"),
                doc("<dict><key>a</key></dict>"),
                doc("<dict><string>a</string><string>b</string></dict>"),
                doc("<dict><key>a</key><true/><key>a</key><false/></dict>"),
                doc("<array><key>a</key></array>"),
                doc("<unknown/>"),
                doc("<string>a<b/></string>"),
                doc("<string>a</strin>"),
                doc("<string>a"),
                doc("<string>&bogus;</string>"),
                doc("<string>&#0;</string>"),
                doc("<string>&#xD800;</string>"),
                doc("<string>&#x110000;</string>"),
                doc("<string>&amp</string>"),
                doc("<string>\u0001</string>"),
                doc("<string><![CDATA[x</string>"),
                doc("<string>x</string><!-- unterminated"),
                doc("<true>x</true>"),
                doc("<dict attr=x/>"),
                doc("<dict attr=\"x/>"),
                "<plist><string>x</string></plist>trailing",
                "<?xml version=\"1.0\"",
                "<!DOCTYPE plist",
                "<!DOCTYPE plist [ <!ENTITY a \"aaaa\"> ]><plist><string>&a;</string></plist>",
            )
        for (text in bad) assertRejected(text)
    }

    @Test
    fun `reader rejects invalid UTF-8`() {
        val bytes = doc("<string>x</string>").toByteArray()
        val index = bytes.indexOf('x'.code.toByte())
        bytes[index] = 0xC3.toByte()
        assertFailsWith<PlistException> { XmlPlist.decode(bytes) }
    }

    @Test
    fun `reader enforces limits`() {
        val deep = "<array>".repeat(33) + "</array>".repeat(33)
        assertRejected(doc(deep))
        val ok = decodeBody("<array>".repeat(32) + "</array>".repeat(32))
        assertEquals(32, generateSequence(ok) { (it as? PlistArray)?.items?.firstOrNull() }.count())
        val many = "<array>" + "<true/>".repeat(10_000) + "</array>"
        assertRejected(doc(many))
        assertEquals(9_999, (decodeBody("<array>" + "<true/>".repeat(9_999) + "</array>") as PlistArray).size)
        val dictKeys = "<dict>" + (0 until 5).joinToString("") { "<key>k$it</key><true/>" } + "</dict>"
        assertFailsWith<PlistException> { XmlPlist.decode(doc(dictKeys), PlistLimits(maxObjects = 10)) }
        assertEquals(5, (XmlPlist.decode(doc(dictKeys), PlistLimits(maxObjects = 11)) as PlistDict).size)
    }

    @Test
    fun `uid dictionaries only convert when they are exactly one integer entry`() {
        assertEquals(PlistUid(3), decodeBody("<dict><key>CF\$UID</key><integer>3</integer></dict>"))
        val withString = decodeBody("<dict><key>CF\$UID</key><string>3</string></dict>")
        assertEquals(PlistDict(mapOf("CF\$UID" to PlistString("3"))), withString)
        val negative = decodeBody("<dict><key>CF\$UID</key><integer>-1</integer></dict>")
        assertEquals(PlistDict(mapOf("CF\$UID" to PlistInteger(-1))), negative)
        val twoKeys = decodeBody("<dict><key>CF\$UID</key><integer>3</integer><key>x</key><true/></dict>")
        assertEquals(2, (twoKeys as PlistDict).size)
    }
}
