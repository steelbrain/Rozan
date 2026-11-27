package ai.aneesiqbal.rozan.airplay

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DeviceNamesTest {
    @Test
    fun `random names have four distinct fruits`() {
        repeat(1_000) { seed ->
            val words = DeviceNames.random(Random(seed)).split(" ")
            assertEquals(DeviceNames.WORD_COUNT, words.size)
            assertEquals(words.size, words.toSet().size)
            assertTrue(words.all { it in DeviceNames.FRUITS })
        }
    }

    @Test
    fun `random names fit in a DNS-SD label`() {
        val longest =
            DeviceNames.FRUITS
                .sortedByDescending { it.length }
                .take(DeviceNames.WORD_COUNT)
                .joinToString(" ")
        assertTrue(longest.toByteArray().size <= DeviceNames.MAX_LENGTH_BYTES)
    }

    @Test
    fun `sanitize trims, collapses whitespace and drops control characters`() {
        assertEquals("Living Room", DeviceNames.sanitize("  Living \t\n Room\u0007 "))
    }

    @Test
    fun `sanitize rejects blank names`() {
        assertNull(DeviceNames.sanitize(" \t "))
        assertNull(DeviceNames.sanitize("\u0000\u0001"))
    }

    @Test
    fun `sanitize cuts at 63 UTF-8 bytes without splitting characters`() {
        val name = "é".repeat(40) // 80 bytes
        val cleaned = DeviceNames.sanitize(name)!!
        assertEquals(31, cleaned.length)
        assertTrue(cleaned.toByteArray().size <= DeviceNames.MAX_LENGTH_BYTES)
        val emoji = "🍑".repeat(20) // 4 bytes each
        assertEquals(15, DeviceNames.sanitize(emoji)!!.codePointCount(0, DeviceNames.sanitize(emoji)!!.length))
    }
}
