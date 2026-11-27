package ai.aneesiqbal.rozan.ui

import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader

/**
 * The light on a focused glass tile's edge, as vector shapes drawn over the
 * bevel baked into its art ([TileBevel]): a bright line along the edge and a
 * gloss across the top ([drawSheen]). Light comes from the top-left; glass
 * catches it on the edges facing it and, reflected inside, on the opposite
 * corner, so the line is brightest at the top-left and bottom-right corners and
 * fades out at the other two.
 *
 * Shaders are built in [resize] (once per tile size), so drawing allocates
 * nothing.
 */
internal class GlassEdge(
    density: Float,
) {
    private val sheenPaint =
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = SHEEN_WIDTH_DP * density
        }
    private val glossPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val sheenRect = RectF()
    private val glossRect = RectF()
    private var sheenRadius = 0f
    private var radius = 0f

    /** Lays the shapes out for a [width] x [height] tile with corners of [cornerRadius]. */
    fun resize(
        width: Int,
        height: Int,
        cornerRadius: Float,
    ) {
        val w = width.toFloat()
        val h = height.toFloat()
        // The gloss fades out at GLOSS_DEPTH: drawing only that part saves blending the transparent rest.
        glossRect.set(0f, 0f, w, h * GLOSS_DEPTH)
        radius = cornerRadius
        val sheenWidth = sheenPaint.strokeWidth
        sheenRect.set(sheenWidth / 2f, sheenWidth / 2f, w - sheenWidth / 2f, h - sheenWidth / 2f)
        sheenRadius = (cornerRadius - sheenWidth / 2f).coerceAtLeast(0f)

        sheenPaint.shader = LinearGradient(0f, 0f, w, h, SHEEN_COLORS, STOPS, Shader.TileMode.CLAMP)
        glossPaint.shader =
            LinearGradient(0f, 0f, 0f, h * GLOSS_DEPTH, GLOSS_TOP, 0, Shader.TileMode.CLAMP)
    }

    /** The focused edge: a gloss across the top and a bright line along the edge. */
    fun drawSheen(canvas: Canvas) {
        canvas.drawRoundRect(glossRect, radius, radius, glossPaint)
        canvas.drawRoundRect(sheenRect, sheenRadius, sheenRadius, sheenPaint)
    }

    private companion object {
        const val SHEEN_WIDTH_DP = 2f

        /** Fraction of the tile's height the gloss reaches down to. */
        const val GLOSS_DEPTH = 0.55f
        const val GLOSS_TOP = 0x2EFFFFFF

        /**
         * Positions along the top-left to bottom-right diagonal for [SHEEN_COLORS]. On a 16:9 tile the
         * top-right corner projects to 0.76 and the bottom-left one to 0.24: the light gathers at the
         * top-left and bottom-right corners and is nearly gone along the middle of every side.
         */
        val STOPS = floatArrayOf(0f, 0.1f, 0.3f, 0.7f, 0.9f, 1f)
        val SHEEN_COLORS =
            intArrayOf(
                0xFFFFFFFF.toInt(),
                0xB3FFFFFF.toInt(),
                0x2EFFFFFF,
                0x2EFFFFFF,
                0x80FFFFFF.toInt(),
                0xE6FFFFFF.toInt(),
            )
    }
}
