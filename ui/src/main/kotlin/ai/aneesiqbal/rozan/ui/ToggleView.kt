package ai.aneesiqbal.rozan.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View

/**
 * An on/off switch indicator. It is not focusable itself: it mirrors the state
 * of its row (`duplicateParentState`) and only draws.
 */
public class ToggleView
    @JvmOverloads
    constructor(
        context: Context,
        attrs: AttributeSet? = null,
    ) : View(context, attrs) {
        private val trackWidth = resources.getDimensionPixelSize(R.dimen.rozan_toggle_width)
        private val trackHeight = resources.getDimensionPixelSize(R.dimen.rozan_toggle_height)
        private val inset = resources.displayMetrics.density * THUMB_INSET_DP
        private val onColor = context.getColor(R.color.rozan_accent)
        private val offColor = context.getColor(R.color.rozan_toggle_off)
        private val offFocusedColor = context.getColor(R.color.rozan_toggle_off_focused)
        private val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val thumbPaint =
            Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color =
                    context.getColor(R.color.rozan_text_primary)
            }
        private val track = RectF()
        private var focusedState = false

        /** Whether the switch shows "on". */
        public var isChecked: Boolean = false
            set(value) {
                if (field != value) {
                    field = value
                    invalidate()
                }
            }

        init {
            importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        }

        override fun onMeasure(
            widthMeasureSpec: Int,
            heightMeasureSpec: Int,
        ) {
            setMeasuredDimension(
                resolveSize(trackWidth, widthMeasureSpec),
                resolveSize(trackHeight, heightMeasureSpec),
            )
        }

        override fun drawableStateChanged() {
            super.drawableStateChanged()
            val focused = drawableState.contains(android.R.attr.state_focused)
            if (focused != focusedState) {
                focusedState = focused
                invalidate()
            }
        }

        override fun onDraw(canvas: Canvas) {
            val top = (height - trackHeight) / 2f
            track.set(0f, top, trackWidth.toFloat(), top + trackHeight)
            val radius = trackHeight / 2f
            trackPaint.color =
                when {
                    isChecked -> onColor
                    focusedState -> offFocusedColor
                    else -> offColor
                }
            canvas.drawRoundRect(track, radius, radius, trackPaint)
            val thumbRadius = radius - inset
            val cx = if (isChecked) track.right - radius else track.left + radius
            canvas.drawCircle(cx, track.centerY(), thumbRadius, thumbPaint)
        }

        override fun hasOverlappingRendering(): Boolean = false

        private companion object {
            const val THUMB_INSET_DP = 3f
        }
    }
