package ai.aneesiqbal.rozan.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Outline
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import android.view.ViewOutlineProvider

/**
 * A wide 16:9 app tile.
 *
 * Draws one bitmap (pre-rendered with rounded corners at exactly the tile size)
 * or a rounded placeholder until the art arrives. Focus scales the tile up and
 * lifts it with an elevation shadow; both are RenderNode properties, so a focus
 * change never re-records or re-lays out anything.
 *
 * The glass edge at rest is baked into the art ([TileBevel]). The focused
 * tile's brighter edge and gloss are a sibling view that its [TileGridView]
 * lays out over it; focus changes only that view's alpha and scale, so the
 * same holds for them.
 */
public class TileView
    @JvmOverloads
    constructor(
        context: Context,
        attrs: AttributeSet? = null,
    ) : View(context, attrs),
        FloatAnimator.Listener {
        /** Corner radius of the tile, in pixels; art should be rendered with the same radius. */
        public val cornerRadius: Float = resources.getDimension(R.dimen.rozan_tile_corner_radius)

        private val focusedElevation = resources.getDimension(R.dimen.rozan_tile_focused_elevation)
        private val placeholderPaint =
            Paint(Paint.ANTI_ALIAS_FLAG).apply { color = context.getColor(R.color.rozan_tile_placeholder) }
        private val artPaint = Paint(Paint.FILTER_BITMAP_FLAG)
        private val bounds = RectF()
        private val focus = FloatAnimator(0f, FOCUS_DURATION_MS, this)

        /** Text shown under the tile while it has focus; also its accessibility label. */
        public var label: CharSequence = ""
            set(value) {
                field = value
                contentDescription = value
                labelView?.text = value
            }

        /** Tile artwork, drawn to fill the tile. Null draws the placeholder. */
        public var art: Bitmap? = null
            set(value) {
                if (field !== value) {
                    field = value
                    invalidate()
                }
            }

        internal var labelView: TileLabelView? = null
        internal val sheenView: TileSheenView = TileSheenView(context)
        internal var gridIndex: Int = -1

        init {
            isFocusable = true
            isClickable = true
            outlineProvider = RoundedOutline
        }

        override fun onFocusChanged(
            gainFocus: Boolean,
            direction: Int,
            previouslyFocusedRect: Rect?,
        ) {
            super.onFocusChanged(gainFocus, direction, previouslyFocusedRect)
            focus.animateTo(if (gainFocus) 1f else 0f)
        }

        override fun onValue(value: Float) {
            val scale = 1f + (FOCUSED_SCALE - 1f) * value
            scaleX = scale
            scaleY = scale
            translationZ = focusedElevation * value
            labelView?.alpha = value
            sheenView.scaleX = scale
            sheenView.scaleY = scale
            sheenView.alpha = value
        }

        override fun onSizeChanged(
            w: Int,
            h: Int,
            oldw: Int,
            oldh: Int,
        ) {
            super.onSizeChanged(w, h, oldw, oldh)
            bounds.set(0f, 0f, w.toFloat(), h.toFloat())
        }

        override fun onDraw(canvas: Canvas) {
            val bitmap = art
            if (bitmap != null) {
                canvas.drawBitmap(bitmap, null, bounds, artPaint)
            } else {
                canvas.drawRoundRect(bounds, cornerRadius, cornerRadius, placeholderPaint)
            }
        }

        // One draw op: alpha can be applied to it directly, no offscreen layer needed.
        override fun hasOverlappingRendering(): Boolean = false

        private object RoundedOutline : ViewOutlineProvider() {
            override fun getOutline(
                view: View,
                outline: Outline,
            ) {
                outline.setRoundRect(0, 0, view.width, view.height, (view as TileView).cornerRadius)
            }
        }

        /** Tile geometry shared with the grid. */
        public companion object {
            /** Scale of the focused tile. */
            public const val FOCUSED_SCALE: Float = 1.1f

            /** Width:height of a tile, the Android TV banner ratio. */
            public const val ASPECT_RATIO: Float = 16f / 9f

            private const val FOCUS_DURATION_MS = 180L
        }
    }
