package ai.aneesiqbal.rozan.launcher.update

import org.junit.jupiter.api.Test
import java.security.MessageDigest
import kotlin.random.Random
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class Sha256SumsTest {
    private val launcher = sha256("launcher")
    private val airplay = sha256("airplay")

    // What sha256sum writes for the APKs in scripts/release/collect.sh: names start with "./".
    private val released =
        "${hexOf(airplay)}  ./rozan-airplay-0.2.0.apk\n" +
            "${hexOf(launcher)}  ./rozan-launcher-0.2.0.apk\n"

    @Test
    fun `reads the release's file`() {
        val sums = Sha256Sums.parse(released)
        assertEquals(listOf("rozan-airplay-0.2.0.apk", "rozan-launcher-0.2.0.apk"), sums.keys.toList())
        assertContentEquals(launcher, Sha256Sums.find(released, "rozan-launcher-0.2.0.apk"))
        assertNull(Sha256Sums.find(released, "rozan-launcher-0.1.0.apk"))
    }

    @Test
    fun `accepts binary mode, names without dot slash, uppercase hex, CRLF and blank lines`() {
        val text = "\r\n${hexOf(launcher).uppercase()} *rozan-launcher-0.2.0.apk\r\n\n   \n"
        assertContentEquals(launcher, Sha256Sums.find(text, "rozan-launcher-0.2.0.apk"))
        assertEquals(emptyMap(), Sha256Sums.parse(""))
    }

    @Test
    fun `rejects malformed lines`() {
        val hash = hexOf(launcher)
        val bad =
            listOf(
                "$hash rozan.apk",
                "$hash\trozan.apk",
                "$hash  ",
                "${hash.drop(1)}  rozan.apk",
                "${hash}0  rozan.apk",
                "${hash.replaceRange(0, 1, "g")}  rozan.apk",
                // ARABIC-INDIC DIGIT THREE, a digit to Character.digit but not hex here.
                "${hash.replaceRange(0, 1, "٣")}  rozan.apk",
                "\\$hash  rozan\\nname.apk",
                "$hash  dir/rozan.apk",
                "$hash  ../rozan.apk",
                "$hash  ./",
                "$hash  ..",
                "$hash  a\u0001b",
                "$hash  " + "a".repeat(Sha256Sums.MAX_NAME_LENGTH + 1),
                "not a checksum line",
            )
        for (line in bad) assertFailsWith<UpdateFormatException>(line) { Sha256Sums.parse(released + line + "\n") }
    }

    @Test
    fun `rejects a name listed twice`() {
        val twice = released + "${hexOf(airplay)}  rozan-launcher-0.2.0.apk\n"
        assertFailsWith<UpdateFormatException> { Sha256Sums.parse(twice) }
    }

    @Test
    fun `rejects oversized files and too many entries`() {
        val line = "${hexOf(launcher)}  a.apk\n"
        val tooLong = line + " ".repeat(Sha256Sums.MAX_BYTES)
        assertFailsWith<UpdateFormatException> { Sha256Sums.parse(tooLong) }
        val many = (0..Sha256Sums.MAX_ENTRIES).joinToString("") { "${hexOf(launcher)}  file-$it.apk\n" }
        assertFailsWith<UpdateFormatException> { Sha256Sums.parse(many) }
        val most = (0 until Sha256Sums.MAX_ENTRIES).joinToString("") { "${hexOf(launcher)}  file-$it.apk\n" }
        assertEquals(Sha256Sums.MAX_ENTRIES, Sha256Sums.parse(most).size)
    }

    @Test
    fun `a truncated file never yields a wrong digest for the APK`() {
        val name = "rozan-launcher-0.2.0.apk"
        val launcherLineStart = released.indexOf('\n') + 1
        for (length in 0..released.length) {
            val prefix = released.substring(0, length)
            val lastLine = prefix.substringAfterLast('\n')
            // Cut before the name: the line is malformed.
            if (lastLine.isNotEmpty() && lastLine.length < 67) {
                assertFailsWith<UpdateFormatException>("prefix $length") { Sha256Sums.parse(prefix) }
                continue
            }
            // Cut inside a name: that line is malformed or names another file. Only the whole line names the APK.
            val found =
                try {
                    Sha256Sums.find(prefix, name)
                } catch (expected: UpdateFormatException) {
                    null
                }
            if (length >= released.length - 1) {
                assertContentEquals(launcher, found)
            } else {
                assertNull(found, "prefix $length (launcher line starts at $launcherLineStart)")
            }
        }
    }

    @Test
    fun `garbage fails only with a format error`() {
        for (seed in 0 until 1_000) {
            val random = Random(seed)
            val text = String(random.nextBytes(random.nextInt(0, 512)), Charsets.ISO_8859_1)
            try {
                Sha256Sums.parse(text)
            } catch (expected: UpdateFormatException) {
                // The only failure allowed.
            }
        }
    }

    private fun sha256(text: String): ByteArray = MessageDigest.getInstance("SHA-256").digest(text.toByteArray())
}
