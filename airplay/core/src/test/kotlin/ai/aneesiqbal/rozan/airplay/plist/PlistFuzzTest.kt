package ai.aneesiqbal.rozan.airplay.plist

import ai.aneesiqbal.rozan.airplay.toHex
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.MethodSource
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.fail

/**
 * Mutated and random inputs must either decode or fail with [PlistException]: never another exception
 * type, never an out-of-memory error. Whatever decodes must survive re-encoding. Seeds are fixed so a
 * failure reproduces.
 */
class PlistFuzzTest {
    private fun checkBinary(input: ByteArray) {
        val result = runCatching { BinaryPlist.decode(input) }
        val error = result.exceptionOrNull()
        if (error != null && error !is PlistException) {
            fail("${error::class.simpleName} for input ${input.toHex()}", error)
        }
        val value = result.getOrNull() ?: return
        assertEquals(value, BinaryPlist.decode(BinaryPlist.encode(value)))
    }

    private fun checkXml(input: ByteArray) {
        val error = runCatching { XmlPlist.decode(input) }.exceptionOrNull()
        if (error != null && error !is PlistException) {
            fail("${error::class.simpleName} for input ${String(input, Charsets.UTF_8)}", error)
        }
    }

    @ParameterizedTest
    @MethodSource("binaryFixtures")
    fun `mutated binary fixtures fail only with PlistException`(name: String) {
        val original = resource("$name.bplist")
        val random = Random(name.hashCode())
        repeat(3_000) {
            val input = original.copyOf()
            repeat(1 + random.nextInt(4)) {
                // Half the mutations land in the offset table and trailer, where the structure lives.
                val position =
                    if (random.nextBoolean()) {
                        input.size - 1 - random.nextInt(minOf(input.size, 64))
                    } else {
                        random.nextInt(input.size)
                    }
                input[position] = random.nextInt(256).toByte()
            }
            checkBinary(input)
        }
    }

    @ParameterizedTest
    @MethodSource("binaryFixtures")
    fun `truncated and extended binary fixtures fail only with PlistException`(name: String) {
        val original = resource("$name.bplist")
        val random = Random(name.hashCode() + 1)
        val lengths =
            if (original.size <=
                2_048
            ) {
                (0 until original.size).toList()
            } else {
                List(300) { random.nextInt(original.size) }
            }
        for (length in lengths) {
            checkBinary(original.copyOf(length))
            checkBinary(original.copyOfRange(original.size - length, original.size))
        }
        checkBinary(original + ByteArray(1))
        checkBinary(original + original)
    }

    @Test
    fun `random trailer values fail only with PlistException`() {
        val original = resource("nested.bplist")
        val random = Random(7)
        repeat(5_000) {
            val input = original.copyOf()
            val trailer = input.size - 32
            val field = random.nextInt(5)
            when (field) {
                0 -> {
                    input[trailer + 6] = random.nextInt(256).toByte()
                }

                1 -> {
                    input[trailer + 7] = random.nextInt(256).toByte()
                }

                else -> {
                    val start = trailer + 8 * (field - 1)
                    val value = if (random.nextBoolean()) random.nextLong() else random.nextLong(0, 1024)
                    for (i in 0 until 8) input[start + i] = (value ushr (56 - 8 * i)).toByte()
                }
            }
            checkBinary(input)
        }
    }

    @Test
    fun `random structurally plausible binary plists fail only with PlistException`() {
        val random = Random(11)
        repeat(30_000) {
            val objectBytes = random.nextBytes(1 + random.nextInt(48))
            // Bias markers toward real types so parsing gets past the first byte.
            val markers =
                intArrayOf(
                    0x08,
                    0x09,
                    0x10,
                    0x13,
                    0x14,
                    0x23,
                    0x33,
                    0x42,
                    0x4F,
                    0x53,
                    0x5F,
                    0x62,
                    0x80,
                    0xA2,
                    0xAF,
                    0xD1,
                    0xDF,
                )
            for (i in objectBytes.indices) {
                if (random.nextInt(3) == 0) objectBytes[i] = markers[random.nextInt(markers.size)].toByte()
            }
            val count = 1 + random.nextInt(12)
            val offsets = List(count) { 8L + random.nextInt(objectBytes.size + 1) }
            val input =
                craftBplist(
                    listOf(objectBytes),
                    top = random.nextLong(0, count.toLong() + 1),
                    refSize = 1 + random.nextInt(2),
                    offsetSize = 1,
                    count = count.toLong(),
                    offsets = offsets,
                )
            checkBinary(input)
        }
    }

    @Test
    fun `random bytes after the magic fail only with PlistException`() {
        val random = Random(3)
        val magic = "bplist00".toByteArray()
        repeat(5_000) {
            checkBinary(magic + random.nextBytes(random.nextInt(200)))
        }
    }

    @ParameterizedTest
    @MethodSource("xmlFixtures")
    fun `mutated XML fixtures fail only with PlistException`(name: String) {
        val original = resource("$name.xml")
        val random = Random(name.hashCode())
        val alphabet =
            "<>/&;#x![]-?=\"' \t\r\nCDATAdictkeystringintegerrealdatedatatruefalseplistarray0123456789"
                .toByteArray()
        repeat(2_000) {
            val input = original.copyOf()
            repeat(1 + random.nextInt(4)) {
                val position = random.nextInt(input.size)
                input[position] =
                    if (random.nextInt(4) ==
                        0
                    ) {
                        random.nextInt(256).toByte()
                    } else {
                        alphabet[random.nextInt(alphabet.size)]
                    }
            }
            checkXml(input)
            checkXml(input.copyOf(random.nextInt(input.size)))
        }
    }

    @Test
    fun `random XML token soup fails only with PlistException`() {
        val random = Random(5)
        val tokens =
            listOf(
                "<plist>",
                "</plist>",
                "<dict>",
                "</dict>",
                "<dict/>",
                "<array>",
                "</array>",
                "<array/>",
                "<key>",
                "</key>",
                "<string>",
                "</string>",
                "<string/>",
                "<integer>",
                "</integer>",
                "<real>",
                "</real>",
                "<date>",
                "</date>",
                "<data>",
                "</data>",
                "<true/>",
                "<false/>",
                "<true>",
                "</true>",
                "&amp;",
                "&#x41;",
                "&#65;",
                "&#;",
                "&",
                "<![CDATA[",
                "]]>",
                "<!--",
                "-->",
                "<?xml version=\"1.0\"?>",
                "<!DOCTYPE plist>",
                "<",
                ">",
                "/",
                "\"",
                "x",
                "1",
                "-",
                "0x",
                "1.5e3",
                "nan",
                "AQID",
                "2024-01-01T00:00:00Z",
                " ",
                "\n",
                "\r",
                "CF\$UID",
                "<!DOCTYPE [",
            )
        repeat(20_000) {
            val text = buildString { repeat(1 + random.nextInt(30)) { append(tokens[random.nextInt(tokens.size)]) } }
            checkXml(text.toByteArray())
        }
    }

    companion object {
        @JvmStatic
        fun binaryFixtures(): List<String> = FIXTURES + "depth-33"

        @JvmStatic
        fun xmlFixtures(): List<String> = listOf("scalars", "nested", "uid", "depth-32")
    }
}
