package ai.aneesiqbal.rozan.launcher.home

import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.text.TextPaint
import android.text.TextUtils
import androidx.core.graphics.ColorUtils
import androidx.core.graphics.createBitmap
import androidx.core.graphics.get
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Renders tile artwork off the main thread: banners scaled to cover the tile,
 * or an icon/glyph with the app name on a tinted tile. Corners are rounded
 * here, once, so drawing a tile is a single bitmap blit with no clipping.
 * Results are software bitmaps, so they can also be stored; [toHardware]
 * uploads one for drawing (GPU memory, not the Java heap).
 */
class TileArtRenderer(
    private val cornerRadius: Float,
) {
    /** A banner drawable scaled to cover [width] x [height], centre-cropped. */
    fun renderBanner(
        banner: Drawable,
        width: Int,
        height: Int,
    ): Bitmap {
        val content = createBitmap(width, height)
        val canvas = Canvas(content)
        val intrinsicWidth = banner.intrinsicWidth
        val intrinsicHeight = banner.intrinsicHeight
        if (intrinsicWidth > 0 && intrinsicHeight > 0) {
            val scale = max(width / intrinsicWidth.toFloat(), height / intrinsicHeight.toFloat())
            val drawnWidth = (intrinsicWidth * scale).roundToInt()
            val drawnHeight = (intrinsicHeight * scale).roundToInt()
            val left = (width - drawnWidth) / 2
            val top = (height - drawnHeight) / 2
            banner.setBounds(left, top, left + drawnWidth, top + drawnHeight)
        } else {
            banner.setBounds(0, 0, width, height)
        }
        banner.draw(canvas)
        val rounded = createBitmap(width, height)
        val paint =
            Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG).apply {
                shader = BitmapShader(content, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP)
            }
        Canvas(
            rounded,
        ).drawRoundRect(RectF(0f, 0f, width.toFloat(), height.toFloat()), cornerRadius, cornerRadius, paint)
        content.recycle()
        return rounded
    }

    /** A built-in glyph centred on a tile filled with [background]. */
    fun renderGlyphTile(
        glyph: Drawable,
        background: Int,
        width: Int,
        height: Int,
    ): Bitmap {
        val bitmap = createBitmap(width, height)
        val canvas = Canvas(bitmap)
        val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = background }
        canvas.drawRoundRect(RectF(0f, 0f, width.toFloat(), height.toFloat()), cornerRadius, cornerRadius, fill)
        val size = (height * GLYPH_FRACTION).roundToInt()
        val left = (width - size) / 2
        val top = (height - size) / 2
        glyph.setBounds(left, top, left + size, top + size)
        glyph.draw(canvas)
        return bitmap
    }

    /**
     * An app icon centred above [label] on a tile tinted from the icon's
     * colours, for apps without a TV banner.
     */
    fun renderIconTile(
        icon: Drawable,
        label: String,
        width: Int,
        height: Int,
    ): Bitmap {
        val bitmap = createBitmap(width, height)
        val canvas = Canvas(bitmap)
        val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = tintFor(icon) }
        canvas.drawRoundRect(RectF(0f, 0f, width.toFloat(), height.toFloat()), cornerRadius, cornerRadius, fill)

        val iconSize = (height * ICON_FRACTION).roundToInt()
        val iconLeft = (width - iconSize) / 2
        val iconTop = (height * ICON_TOP_FRACTION).roundToInt()
        icon.setBounds(iconLeft, iconTop, iconLeft + iconSize, iconTop + iconSize)
        icon.draw(canvas)

        val text =
            TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.WHITE
                textSize = height * TEXT_FRACTION
                typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            }
        val maxWidth = width * TEXT_WIDTH_FRACTION
        val shown = TextUtils.ellipsize(label, text, maxWidth, TextUtils.TruncateAt.END).toString()
        val x = (width - text.measureText(shown)) / 2f
        canvas.drawText(shown, x, height * BASELINE_FRACTION, text)
        return bitmap
    }

    /** Average colour of [icon] (weighted by opacity), darkened to sit behind white text. */
    private fun tintFor(icon: Drawable): Int {
        val sample = createBitmap(SAMPLE_SIZE, SAMPLE_SIZE)
        icon.setBounds(0, 0, SAMPLE_SIZE, SAMPLE_SIZE)
        icon.draw(Canvas(sample))
        var red = 0L
        var green = 0L
        var blue = 0L
        var weight = 0L
        for (y in 0 until SAMPLE_SIZE) {
            for (x in 0 until SAMPLE_SIZE) {
                val pixel = sample[x, y]
                val alpha = Color.alpha(pixel).toLong()
                red += Color.red(pixel) * alpha
                green += Color.green(pixel) * alpha
                blue += Color.blue(pixel) * alpha
                weight += alpha
            }
        }
        sample.recycle()
        if (weight == 0L) return NEUTRAL_TILE
        val average = Color.rgb((red / weight).toInt(), (green / weight).toInt(), (blue / weight).toInt())
        return ColorUtils.blendARGB(average, Color.BLACK, DARKEN)
    }

    /**
     * [bitmap] uploaded as a hardware bitmap, or [bitmap] itself (prepared for drawing) when the upload
     * fails. With [keep] false the software copy is recycled after the upload.
     */
    fun toHardware(
        bitmap: Bitmap,
        keep: Boolean = false,
    ): Bitmap {
        val hardware = bitmap.copy(Bitmap.Config.HARDWARE, false)
        if (hardware == null) {
            bitmap.prepareToDraw()
            return bitmap
        }
        if (!keep) bitmap.recycle()
        return hardware
    }

    private companion object {
        const val GLYPH_FRACTION = 0.4f
        const val ICON_FRACTION = 0.42f
        const val ICON_TOP_FRACTION = 0.16f
        const val TEXT_FRACTION = 0.135f
        const val TEXT_WIDTH_FRACTION = 0.86f
        const val BASELINE_FRACTION = 0.84f
        const val SAMPLE_SIZE = 16
        const val DARKEN = 0.6f
        const val NEUTRAL_TILE = 0xFF2C2C2E.toInt()
    }
}
