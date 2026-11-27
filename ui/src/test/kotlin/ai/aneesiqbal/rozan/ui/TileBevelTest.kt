package ai.aneesiqbal.rozan.ui

import org.junit.jupiter.api.Test
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.random.Random
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class TileBevelTest {
    private val width = 313
    private val height = 176
    private val radius = 20f
    private val band = 14f
    private val shift = 6f

    private fun argb(
        alpha: Int,
        red: Int,
        green: Int,
        blue: Int,
    ): Int = (alpha shl 24) or (red shl 16) or (green shl 8) or blue

    private fun channel(
        pixel: Int,
        shift: Int,
    ): Int = pixel shr shift and 0xFF

    private fun luma(pixel: Int): Int = channel(pixel, 16) + channel(pixel, 8) + channel(pixel, 0)

    private fun bake(src: IntArray): IntArray {
        val dst = src.copyOf()
        TileBevel.bake(src, dst, width, height, radius, band, shift)
        return dst
    }

    private fun at(
        x: Int,
        y: Int,
    ): Int = y * width + x

    @Test
    fun `the face beyond the band is untouched`() {
        val random = Random(3)
        val src = IntArray(width * height) { argb(0xFF, random.nextInt(256), random.nextInt(256), random.nextInt(256)) }
        val dst = bake(src)
        // Beyond 14 px from the straight edges; near a corner, beyond 14 px from its arc.
        for (y in 15 until height - 15) {
            for (x in 15 until width - 15) {
                val cx =
                    if (x < radius) {
                        radius
                    } else if (x > width - radius) {
                        width - radius
                    } else {
                        x + 0.5f
                    }
                val cy =
                    if (y < radius) {
                        radius
                    } else if (y > height - radius) {
                        height - radius
                    } else {
                        y + 0.5f
                    }
                val fromArc = radius - hypot(x + 0.5f - cx, y + 0.5f - cy)
                if (fromArc < band) continue
                assertEquals(src[at(x, y)], dst[at(x, y)], "($x, $y)")
            }
        }
    }

    @Test
    fun `alpha is kept and transparent pixels stay as they are`() {
        val src = IntArray(width * height) { argb(0xFF, 90, 90, 90) }
        src[at(0, 0)] = 0
        src[at(1, 0)] = argb(0x40, 90, 90, 90)
        val dst = bake(src)
        assertEquals(0, dst[at(0, 0)])
        for (i in src.indices) assertEquals(src[i] ushr 24, dst[i] ushr 24)
    }

    @Test
    fun `the edge facing the light is brighter than the one away from it`() {
        val src = IntArray(width * height) { argb(0xFF, 100, 100, 100) }
        val dst = bake(src)
        val topLeft = luma(dst[at(30, 4)]) + luma(dst[at(4, 30)])
        val bottomRight = luma(dst[at(width - 31, height - 5)]) + luma(dst[at(width - 5, height - 31)])
        assertTrue(topLeft > bottomRight, "top-left $topLeft, bottom-right $bottomRight")
        // On grey, light and shade change brightness, not hue.
        for (pixel in dst) assertTrue(abs(channel(pixel, 16) - channel(pixel, 0)) <= 1)
    }

    @Test
    fun `the edge shows the picture from further inside`() {
        // A red border two pixels deep around a blue face: at the very edge the bevel shows blue.
        val src =
            IntArray(width * height) { i ->
                val x = i % width
                val y = i / width
                val border = x < 2 || y < 2 || x >= width - 2 || y >= height - 2
                if (border) argb(0xFF, 200, 0, 0) else argb(0xFF, 0, 0, 200)
            }
        val dst = bake(src)
        for (pixel in listOf(dst[at(0, height / 2)], dst[at(width / 2, height - 1)], dst[at(width - 1, height / 2)])) {
            assertTrue(channel(pixel, 0) > channel(pixel, 16), "pixel ${pixel.toUInt().toString(16)}")
        }
    }

    @Test
    fun `the band meets the flat face without a step`() {
        val src = IntArray(width * height) { argb(0xFF, 120, 120, 120) }
        val dst = bake(src)
        // Along the middle of the left edge, from the edge inwards: no jump at the band's inner end.
        for (x in 10 until 18) {
            val step = abs(luma(dst[at(x, height / 2)]) - luma(dst[at(x + 1, height / 2)]))
            assertTrue(step <= 6, "step $step at x=$x")
        }
    }

    @Test
    fun `small or odd inputs do not fail`() {
        for ((w, h) in listOf(1 to 1, 2 to 3, 7 to 2, 40 to 40)) {
            val src = IntArray(w * h) { argb(0xFF, 50, 60, 70) }
            val dst = src.copyOf()
            TileBevel.bake(src, dst, w, h, cornerRadius = 1000f, band = band, shift = shift)
            assertTrue(dst.all { it ushr 24 == 0xFF })
        }
        TileBevel.bake(IntArray(0), IntArray(0), 0, 0, radius, band, shift)
        assertFailsWith<IllegalArgumentException> {
            TileBevel.bake(
                IntArray(4),
                IntArray(4),
                3,
                3,
                radius,
                band,
                shift,
            )
        }
    }
}
