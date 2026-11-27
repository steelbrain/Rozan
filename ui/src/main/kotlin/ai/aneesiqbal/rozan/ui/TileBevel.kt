package ai.aneesiqbal.rozan.ui

import android.graphics.Bitmap
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sign
import kotlin.math.sqrt

/**
 * Bakes the tiles' glass edge into their art: the picture as seen through
 * a slab of glass whose edge is rounded off. Within a band along the edge the
 * picture is pulled in from further inside (magnified, most at the very edge,
 * the way a lens edge bends it), the slope facing the top-left light is
 * brighter and the opposite one darker, a specular highlight sits on the slope
 * at the top-left with a weaker reflection at the bottom-right, and the very
 * edge carries a thin bright line.
 *
 * It works on the CPU, once per tile, off the main thread; only pixels in the
 * band are touched (about a quarter of a 313x176 tile). Drawing the result costs
 * the same as the plain art.
 */
public object TileBevel {
    /**
     * Names what [apply] produces. Art stored with the bevel baked in is keyed by it, so change it
     * whenever the bake's output changes.
     */
    public const val VERSION: String = "bevel-1"

    /**
     * [art] (a software bitmap whose corners are rounded to [cornerRadius] pixels) with the bevel
     * applied, as a new software bitmap; [art] itself is left as it is. [density] scales the band.
     */
    public fun apply(
        art: Bitmap,
        cornerRadius: Float,
        density: Float,
    ): Bitmap {
        val width = art.width
        val height = art.height
        val pixels = IntArray(width * height)
        art.getPixels(pixels, 0, width, 0, 0, width, height)
        val out = pixels.copyOf()
        bake(pixels, out, width, height, cornerRadius, BAND_DP * density, SHIFT_DP * density)
        return Bitmap.createBitmap(out, width, height, Bitmap.Config.ARGB_8888)
    }

    /**
     * The bevel on [src] (unpremultiplied ARGB, [width] x [height], row-major) written to [dst], which
     * must start as a copy of [src]: pixels outside the band and fully transparent ones keep their
     * value. [band] is the bevel's width and [shift] how far in the picture is pulled at the very
     * edge, both in pixels; [shift] below half of [band] keeps the mapping monotonic (no mirroring).
     */
    internal fun bake(
        src: IntArray,
        dst: IntArray,
        width: Int,
        height: Int,
        cornerRadius: Float,
        band: Float,
        shift: Float,
    ) {
        require(src.size >= width * height && dst.size >= width * height) { "pixel arrays too small" }
        if (width <= 0 || height <= 0 || band <= 0f) return
        val baker = Baker(src, width, height, cornerRadius, band, shift)
        for (y in 0 until height) {
            for (x in 0 until width) {
                val index = y * width + x
                val pixel = src[index]
                if (pixel ushr ALPHA_SHIFT != 0) dst[index] = baker.bake(pixel, x, y)
            }
        }
    }

    /** The picture being baked and the band's shape. */
    private class Baker(
        private val src: IntArray,
        private val width: Int,
        private val height: Int,
        cornerRadius: Float,
        private val band: Float,
        private val shift: Float,
    ) {
        private val halfWidth = width / 2f
        private val halfHeight = height / 2f
        private val radius = cornerRadius.coerceIn(0f, min(halfWidth, halfHeight))
        private val straightX = halfWidth - radius
        private val straightY = halfHeight - radius

        /** The baked [pixel] at ([x], [y]); unchanged where it lies beyond the band. */
        fun bake(
            pixel: Int,
            x: Int,
            y: Int,
        ): Int {
            val px = x + HALF - halfWidth
            val py = y + HALF - halfHeight
            val qx = abs(px) - straightX
            val qy = abs(py) - straightY
            // Signed distance to the rounded rectangle's edge (negative inside), and its outward normal.
            val ox = max(qx, 0f)
            val oy = max(qy, 0f)
            val corner = ox > 0f && oy > 0f
            val outside = if (corner) sqrt(ox * ox + oy * oy) else max(ox, oy)
            val depth = -(outside + min(max(qx, qy), 0f) - radius)
            if (depth >= band) return pixel
            val nx: Float
            val ny: Float
            when {
                corner -> {
                    nx = ox / outside * sign(px)
                    ny = oy / outside * sign(py)
                }

                qx > qy -> {
                    nx = sign(px)
                    ny = 0f
                }

                else -> {
                    nx = 0f
                    ny = sign(py)
                }
            }
            return shade(pixel, px + halfWidth, py + halfHeight, nx, ny, depth)
        }

        /** One bevel pixel at ([cx], [cy]) (continuous coordinates of its centre), [depth] pixels inside. */
        private fun shade(
            pixel: Int,
            cx: Float,
            cy: Float,
            nx: Float,
            ny: Float,
            depth: Float,
        ): Int {
            // 1 at the very edge, 0 where the bevel meets the flat face.
            val u = 1f - max(depth, 0f) / band
            val u2 = u * u
            val pull = shift * u2
            val color = sample(src, width, height, cx - nx * pull, cy - ny * pull)
            return lightPixel(pixel, color, nx, ny, u2)
        }
    }

    /**
     * [color] (the refracted sample) lit as the bevel's surface at [u2] (the square of the position
     * across the band, 1 at the edge) with outward normal ([nx], [ny]); alpha comes from [pixel].
     */
    private fun lightPixel(
        pixel: Int,
        color: Int,
        nx: Float,
        ny: Float,
        u2: Float,
    ): Int {
        // The surface tilts outwards towards the edge: normal (n * slope, nz).
        val slope = MAX_SLOPE * u2
        val nz = sqrt(1f - slope * slope)
        val facing = (nx * LIGHT_X + ny * LIGHT_Y) * slope
        // Blinn-Phong against the light and against its mirror image (the reflection inside the glass),
        // less what the flat face would get, so the band meets the untouched interior without a step.
        val across = (nx * HALF_X + ny * HALF_Y) * slope
        val toLight = power(max(across + nz * HALF_Z, 0f)) - FLAT_HIGHLIGHT
        val toReflection = power(max(nz * HALF_Z - across, 0f)) - FLAT_HIGHLIGHT
        val specular = SPECULAR * max(toLight, 0f) + REFLECTION * max(toReflection, 0f)
        val edge = u2 * u2 * u2 * u2 * EDGE
        val highlight = (specular + edge).coerceIn(0f, 1f)
        val lit = 1f + DIFFUSE * facing

        val red = light((color shr RED_SHIFT) and CHANNEL, lit, highlight)
        val green = light((color shr GREEN_SHIFT) and CHANNEL, lit, highlight)
        val blue = light(color and CHANNEL, lit, highlight)
        return (pixel and ALPHA_MASK) or (red shl RED_SHIFT) or (green shl GREEN_SHIFT) or blue
    }

    /** [channel] shaded by [lit], then screened towards white by [highlight]. */
    private fun light(
        channel: Int,
        lit: Float,
        highlight: Float,
    ): Int {
        val shaded = (channel * lit).coerceIn(0f, MAX_CHANNEL)
        return (shaded + (MAX_CHANNEL - shaded) * highlight + HALF).toInt().coerceIn(0, CHANNEL)
    }

    /** `value` to the power of 32: a tight highlight. */
    private fun power(value: Float): Float {
        var p = value * value
        p *= p
        p *= p
        p *= p
        return p * p
    }

    /** Bilinear sample (RGB only) at continuous coordinates ([x], [y]), clamped to the bitmap. */
    private fun sample(
        src: IntArray,
        width: Int,
        height: Int,
        x: Float,
        y: Float,
    ): Int {
        val gx = (x - HALF).coerceIn(0f, (width - 1).toFloat())
        val gy = (y - HALF).coerceIn(0f, (height - 1).toFloat())
        val x0 = floor(gx).toInt()
        val y0 = floor(gy).toInt()
        val x1 = min(x0 + 1, width - 1)
        val y1 = min(y0 + 1, height - 1)
        val fx = gx - x0
        val fy = gy - y0
        val a = src[y0 * width + x0]
        val b = src[y0 * width + x1]
        val c = src[y1 * width + x0]
        val d = src[y1 * width + x1]
        var result = 0
        var shift = 0
        while (shift <= RED_SHIFT) {
            val top = ((a shr shift) and CHANNEL) * (1f - fx) + ((b shr shift) and CHANNEL) * fx
            val bottom = ((c shr shift) and CHANNEL) * (1f - fx) + ((d shr shift) and CHANNEL) * fx
            val value = (top * (1f - fy) + bottom * fy + HALF).toInt().coerceIn(0, CHANNEL)
            result = result or (value shl shift)
            shift += CHANNEL_BITS
        }
        return result
    }

    private const val BAND_DP = 6f
    private const val SHIFT_DP = 2.6f
    private const val MAX_SLOPE = 0.92f
    private const val SPECULAR = 0.85f
    private const val REFLECTION = 0.5f
    private const val EDGE = 0.55f
    private const val DIFFUSE = 0.14f

    // Direction to the light (top-left, raised about 38 degrees; y points down) and the half vector
    // between it and the viewer (0, 0, 1).
    private const val LIGHT_X = -0.448f
    private const val LIGHT_Y = -0.647f
    private const val HALF_X = -0.249f
    private const val HALF_Y = -0.360f
    private const val HALF_Z = 0.899f

    /** What the flat face would get from the highlight term. */
    private val FLAT_HIGHLIGHT = power(HALF_Z)

    private const val HALF = 0.5f
    private const val CHANNEL = 0xFF
    private const val MAX_CHANNEL = 255f
    private const val CHANNEL_BITS = 8
    private const val GREEN_SHIFT = 8
    private const val RED_SHIFT = 16
    private const val ALPHA_SHIFT = 24
    private const val ALPHA_MASK = -0x1000000
}
