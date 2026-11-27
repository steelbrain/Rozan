package ai.aneesiqbal.rozan.ui

import android.content.Context
import android.graphics.Canvas
import android.view.View

/**
 * The focused tile's sheen ([GlassEdge.drawSheen]), a sibling the grid lays
 * out over its [TileView]. It is recorded once; focus only changes its alpha
 * and scale (RenderNode properties, set by the tile), and at alpha 0 the
 * renderer skips it, so tiles without focus pay nothing for it.
 */
internal class TileSheenView(
    context: Context,
) : View(context) {
    private val cornerRadius = resources.getDimension(R.dimen.rozan_tile_corner_radius)
    private val edge = GlassEdge(resources.displayMetrics.density)

    init {
        alpha = 0f
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        // Above the focused tile (like its label), with no outline, so it casts no shadow of its own.
        outlineProvider = null
        translationZ = resources.getDimension(R.dimen.rozan_tile_focused_elevation) + resources.displayMetrics.density
    }

    override fun onSizeChanged(
        w: Int,
        h: Int,
        oldw: Int,
        oldh: Int,
    ) {
        super.onSizeChanged(w, h, oldw, oldh)
        edge.resize(w, h, cornerRadius)
    }

    override fun onDraw(canvas: Canvas) {
        edge.drawSheen(canvas)
    }

    // Two translucent white draws; per-op alpha looks the same as a layer here and needs none.
    override fun hasOverlappingRendering(): Boolean = false
}
