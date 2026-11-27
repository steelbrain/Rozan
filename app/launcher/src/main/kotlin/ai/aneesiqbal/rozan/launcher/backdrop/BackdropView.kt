package ai.aneesiqbal.rozan.launcher.backdrop

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffColorFilter
import android.graphics.Rect
import android.graphics.drawable.Drawable
import android.util.AttributeSet
import android.view.View

/**
 * One full-screen backdrop layer. Draws a bitmap decoded at the view's size
 * (a single unscaled blit), or the [fallback] drawable when there is none.
 */
class BackdropView
    @JvmOverloads
    constructor(
        context: Context,
        attrs: AttributeSet? = null,
    ) : View(context, attrs) {
        private val plainPaint = Paint()
        private val scaledPaint = Paint(Paint.FILTER_BITMAP_FLAG)
        private val bounds = Rect()
        private var filter: PorterDuffColorFilter? = null

        /** The decoded photo, ideally exactly the view's size. */
        var bitmap: Bitmap? = null
            set(value) {
                if (field !== value) {
                    field = value
                    invalidate()
                }
            }

        /** Drawn when [bitmap] is null; null draws nothing. */
        var fallback: Drawable? = null
            set(value) {
                // Mutated: the tint below must not reach other users of the same drawable.
                field = value?.mutate()
                field?.bounds = bounds
                field?.colorFilter = filter
                invalidate()
            }

        /**
         * A colour drawn over the backdrop as part of its own draw, or 0 for none. Composited `SRC_ATOP`,
         * so on the opaque photo it gives the pixels a view of that colour drawn on top would, without
         * blending a second full-screen layer.
         */
        var tint: Int = 0
            set(value) {
                if (field == value) return
                field = value
                filter = if (value == 0) null else PorterDuffColorFilter(value, PorterDuff.Mode.SRC_ATOP)
                plainPaint.colorFilter = filter
                scaledPaint.colorFilter = filter
                fallback?.colorFilter = filter
                invalidate()
            }

        init {
            importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        }

        override fun onSizeChanged(
            w: Int,
            h: Int,
            oldw: Int,
            oldh: Int,
        ) {
            super.onSizeChanged(w, h, oldw, oldh)
            bounds.set(0, 0, w, h)
            fallback?.bounds = bounds
        }

        override fun onDraw(canvas: Canvas) {
            val photo = bitmap
            when {
                photo == null -> fallback?.draw(canvas)
                photo.width == width && photo.height == height -> canvas.drawBitmap(photo, 0f, 0f, plainPaint)
                else -> canvas.drawBitmap(photo, null, bounds, scaledPaint)
            }
        }

        // A single draw op: alpha applies directly, no offscreen layer.
        override fun hasOverlappingRendering(): Boolean = false
    }
