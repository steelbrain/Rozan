package ai.aneesiqbal.rozan.launcher.home

import ai.aneesiqbal.rozan.launcher.targetContext
import android.graphics.Bitmap
import androidx.core.graphics.drawable.toDrawable
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.nio.ByteBuffer
import kotlin.random.Random

/** Tile art stored by [TileArtDiskCache] reads back pixel for pixel, and the cache stays bounded. */
@RunWith(AndroidJUnit4::class)
class TileArtDiskCacheTest {
    private val dir = File(targetContext.cacheDir, "tile-art-test")
    private val renderer = TileArtRenderer(CORNER_RADIUS)

    @After
    fun tearDown() {
        dir.deleteRecursively()
    }

    @Test
    fun aStoredTileReadsBackWithTheSamePixels() {
        val cache = TileArtDiskCache(dir)
        // A banner with every alpha value, so the rounded corners and the content both have partial
        // alpha: PNG stores unpremultiplied colour, the bitmap premultiplied.
        val banner = noise(WIDTH / 2, HEIGHT / 2, Random(1))
        val icon = noise(48, 48, Random(2)).toDrawable(targetContext.resources)
        for ((index, rendered) in listOf(
            renderer.renderBanner(banner.toDrawable(targetContext.resources), WIDTH, HEIGHT),
            renderer.renderIconTile(icon, "Example TV ✓", WIDTH, HEIGHT),
        ).withIndex()) {
            val name = "com.example.tv$index-${WIDTH}x$HEIGHT-0123"
            cache.write(name, rendered)
            val read = checkNotNull(cache.read(name, WIDTH, HEIGHT, hardware = false))
            assertArrayEquals("tile $index", pixels(rendered), pixels(read))
            val hardware = checkNotNull(cache.read(name, WIDTH, HEIGHT))
            assertEquals(Bitmap.Config.HARDWARE, hardware.config)
            assertEquals(WIDTH, hardware.width)
            assertEquals(HEIGHT, hardware.height)
        }
    }

    @Test
    fun anotherSizeOrADamagedFileIsNotUsed() {
        val cache = TileArtDiskCache(dir)
        val name = "com.example.tv-${WIDTH}x$HEIGHT-0123"
        cache.write(
            name,
            renderer.renderBanner(noise(8, 8, Random(3)).toDrawable(targetContext.resources), WIDTH, HEIGHT),
        )

        assertNull(cache.read(name, WIDTH, HEIGHT + 1))
        assertFalse("a file of another size is deleted", File(dir, name).exists())

        cache.write(
            name,
            renderer.renderBanner(noise(8, 8, Random(3)).toDrawable(targetContext.resources), WIDTH, HEIGHT),
        )
        val file = File(dir, name)
        file.writeBytes(file.readBytes().copyOf(file.length().toInt() / 2))
        assertNull(cache.read(name, WIDTH, HEIGHT))
        assertFalse("a damaged file is deleted", file.exists())
    }

    @Test
    fun aNewVersionReplacesTheOldAndTheCacheStaysBounded() {
        val art =
            renderer.renderBanner(
                noise(64, 36, Random(4)).toDrawable(targetContext.resources),
                WIDTH,
                HEIGHT,
            )
        val unbounded = TileArtDiskCache(dir)
        unbounded.write("com.example.tv-${WIDTH}x$HEIGHT-0001", art)
        unbounded.write("com.example.tv-${WIDTH}x$HEIGHT-0002", art)
        unbounded.write("com.example.tv-10x10-0001", art)
        assertEquals(
            setOf("com.example.tv-${WIDTH}x$HEIGHT-0002", "com.example.tv-10x10-0001"),
            dir.list()?.toSet(),
        )

        unbounded.forgetPackage("com.example.tv")
        assertEquals(0, dir.list()?.size)

        val size = File(dir, "probe-1x1-0001").also { unbounded.write(it.name, art) }.length()
        unbounded.forgetPackage("probe")
        val bounded = TileArtDiskCache(dir, maxBytes = size * 3)
        for (i in 0 until 6) bounded.write("app$i-${WIDTH}x$HEIGHT-0001", art)
        val files = dir.listFiles().orEmpty()
        assertTrue("${files.size} files", files.size <= 3)
        assertTrue(files.sumOf { it.length() } <= size * 3)
        assertTrue("the newest file is kept", File(dir, "app5-${WIDTH}x$HEIGHT-0001").exists())
    }

    private fun noise(
        width: Int,
        height: Int,
        random: Random,
    ): Bitmap {
        val colors = IntArray(width * height) { random.nextInt() }
        return Bitmap.createBitmap(colors, width, height, Bitmap.Config.ARGB_8888)
    }

    /** The bitmap's pixels as stored: premultiplied RGBA. */
    private fun pixels(bitmap: Bitmap): ByteArray {
        val buffer = ByteBuffer.allocate(bitmap.byteCount)
        bitmap.copyPixelsToBuffer(buffer)
        return buffer.array()
    }

    private companion object {
        const val WIDTH = 313
        const val HEIGHT = 176
        const val CORNER_RADIUS = 12f
    }
}
