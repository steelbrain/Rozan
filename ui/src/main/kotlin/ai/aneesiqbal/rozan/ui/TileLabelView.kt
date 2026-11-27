package ai.aneesiqbal.rozan.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import android.text.TextPaint
import android.text.TextUtils
import android.util.AttributeSet
import android.view.View
import kotlin.math.ceil

/**
 * The single-line name under a tile. It is laid out once (ellipsized to its
 * width) and then only its alpha changes, so showing it on focus costs nothing
 * but a RenderNode property update.
 */
public class TileLabelView
    @JvmOverloads
    constructor(
        context: Context,
        attrs: AttributeSet? = null,
    ) : View(context, attrs) {
        private val paint =
            TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
                color = context.getColor(R.color.rozan_text_primary)
                textSize = resources.getDimension(R.dimen.rozan_tile_label_text)
                typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            }
        private val baseline: Float
        private val lineHeight: Int
        private var shown = ""
        private var textX = 0f

        /** The full label; it is ellipsized to the view width when drawn. */
        public var text: CharSequence = ""
            set(value) {
                field = value
                prepare(width)
                invalidate()
            }

        init {
            val metrics = paint.fontMetrics
            baseline = -metrics.ascent
            lineHeight = ceil(metrics.descent - metrics.ascent).toInt()
            alpha = 0f
            importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
            // Above the focused tile, so its elevation shadow does not darken the text; no outline, so no shadow.
            outlineProvider = null
            translationZ =
                resources.getDimension(R.dimen.rozan_tile_focused_elevation) + resources.displayMetrics.density
        }

        override fun onMeasure(
            widthMeasureSpec: Int,
            heightMeasureSpec: Int,
        ) {
            setMeasuredDimension(
                getDefaultSize(suggestedMinimumWidth, widthMeasureSpec),
                resolveSize(lineHeight, heightMeasureSpec),
            )
        }

        override fun onSizeChanged(
            w: Int,
            h: Int,
            oldw: Int,
            oldh: Int,
        ) {
            super.onSizeChanged(w, h, oldw, oldh)
            prepare(w)
        }

        private fun prepare(width: Int) {
            shown =
                if (width <= 0) {
                    ""
                } else {
                    TextUtils.ellipsize(text, paint, width.toFloat(), TextUtils.TruncateAt.END).toString()
                }
            textX = (width - paint.measureText(shown)) / 2f
        }

        override fun onDraw(canvas: Canvas) {
            if (shown.isNotEmpty()) canvas.drawText(shown, textX, baseline, paint)
        }

        override fun hasOverlappingRendering(): Boolean = false
    }
