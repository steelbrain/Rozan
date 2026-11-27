package ai.aneesiqbal.rozan.launcher.home

import org.junit.jupiter.api.Test
import java.nio.ByteBuffer
import kotlin.random.Random
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TileArtDiskCacheNamesTest {
    @Test
    fun `names start with the package and size and change with the identity`() {
        val name = TileArtDiskCache.nameFor("com.example.tv", 313, 176, "com.example.tv/.Main|42|1|Example")

        assertEquals(true, name?.startsWith("com.example.tv-313x176-"))
        assertEquals(name, TileArtDiskCache.nameFor("com.example.tv", 313, 176, "com.example.tv/.Main|42|1|Example"))
        assertNotEquals(name, TileArtDiskCache.nameFor("com.example.tv", 313, 176, "com.example.tv/.Main|43|1|Example"))
        assertEquals(true, name?.matches(Regex("[a-z.]+-313x176-[0-9a-f]{32}")))
    }

    @Test
    fun `an overlong package name gets no name`() {
        assertNull(TileArtDiskCache.nameFor("a".repeat(161), 1, 1, "x"))
    }

    @Test
    fun `a PNG header of the right size is accepted`() {
        assertTrue(TileArtDiskCache.isPng(pngHead(313, 176), 313, 176))
    }

    @Test
    fun `another size, a truncated header or garbage is refused`() {
        assertFalse(TileArtDiskCache.isPng(pngHead(313, 177), 313, 176))
        assertFalse(TileArtDiskCache.isPng(pngHead(100_000, 176), 313, 176))
        val head = pngHead(313, 176)
        for (length in 0 until head.size) assertFalse(TileArtDiskCache.isPng(head.copyOf(length), 313, 176))
        val random = Random(3)
        repeat(1_000) { assertFalse(TileArtDiskCache.isPng(random.nextBytes(random.nextInt(0, 64)), 313, 176)) }
    }

    private fun pngHead(
        width: Int,
        height: Int,
    ): ByteArray {
        val signature = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)
        return ByteBuffer
            .allocate(24)
            .put(signature)
            .putInt(13)
            .put("IHDR".toByteArray())
            .putInt(width)
            .putInt(height)
            .array()
    }
}
