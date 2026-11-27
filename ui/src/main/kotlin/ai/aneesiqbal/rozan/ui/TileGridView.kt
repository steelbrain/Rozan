package ai.aneesiqbal.rozan.ui

import android.content.Context
import android.graphics.Rect
import android.util.AttributeSet
import android.view.View
import android.view.ViewGroup
import kotlin.math.max
import kotlin.math.min

/**
 * A grid of [TileView]s in rows of [columnCount], each tile with its
 * [TileLabelView] underneath.
 *
 * Tiles are grouped into sections; every section starts on a new row. The grid
 * measures itself as tall as its content (taller than the screen when there are
 * many rows) and places the first row near the bottom of the viewport, with the
 * next row peeking in. Scrolling is left to the parent, which translates the
 * grid; the grid only reports which row holds focus.
 *
 * D-pad movement is resolved by row and column arithmetic in [focusSearch]:
 * it is deterministic, allocation-free and ignores the translation (which the
 * platform's geometric search would get wrong).
 */
public class TileGridView
    @JvmOverloads
    constructor(
        context: Context,
        attrs: AttributeSet? = null,
    ) : ViewGroup(context, attrs) {
        /** Observes focus moving between rows. */
        public fun interface Listener {
            /** Called when focus enters a tile on a different row than before. */
            public fun onFocusedRowChanged(row: Int)
        }

        /** Notified when the focused row changes. */
        public var listener: Listener? = null

        /** Number of tiles per row. */
        public var columnCount: Int = DEFAULT_COLUMNS
            set(value) {
                require(value > 0) { "columnCount must be positive" }
                field = value
                rebuildRows()
                requestLayout()
            }

        private val columnGap = resources.getDimensionPixelSize(R.dimen.rozan_grid_column_gap)
        private val rowGap = resources.getDimensionPixelSize(R.dimen.rozan_grid_row_gap)
        private val peek = resources.getDimensionPixelSize(R.dimen.rozan_grid_peek)
        private val labelGap = resources.getDimensionPixelSize(R.dimen.rozan_tile_label_gap)

        private val tiles = ArrayList<TileView>()
        private var sectionSizes = IntArray(0)
        private var rowStarts = IntArray(0)
        private var tileRows = IntArray(0)
        private var firstRowTop = 0
        private var rowPitch = 0

        /** Width of one tile in pixels, known after measure. */
        public var tileWidth: Int = 0
            private set

        /** Height of one tile in pixels, known after measure. */
        public var tileHeight: Int = 0
            private set

        /** Index of the focused tile, or of the last one that had focus. */
        public var focusedIndex: Int = 0
            private set

        /** Row of [focusedIndex]. */
        public var focusedRow: Int = 0
            private set

        /** Number of tiles in the grid. */
        public val tileCount: Int get() = tiles.size

        /** Number of rows in the grid. */
        public val rowCount: Int get() = rowStarts.size

        init {
            descendantFocusability = FOCUS_AFTER_DESCENDANTS
            clipToPadding = false
        }

        /** Returns the tile at [index]. */
        public fun tileAt(index: Int): TileView = tiles[index]

        /** Returns the row that holds the tile at [index]. */
        public fun rowOf(index: Int): Int = tileRows[index]

        /** Top of [row] relative to the top of this view, valid after layout. */
        public fun rowTop(row: Int): Int = firstRowTop + row * rowPitch

        /**
         * Replaces the tiles. Each inner list is a section that starts on a new
         * row. Tile instances may be reused between calls; their labels are kept.
         * If a tile had focus and is still present, it keeps focus.
         */
        public fun setTiles(sections: List<List<TileView>>) {
            val focusedTile = if (hasFocus()) findFocus() as? TileView else null
            removeAllViews()
            tiles.clear()
            sectionSizes = IntArray(sections.size) { sections[it].size }
            for (section in sections) {
                for (tile in section) {
                    tile.gridIndex = tiles.size
                    tiles.add(tile)
                    addView(tile)
                    tile.sheenView.alpha = if (tile.isFocused) 1f else 0f
                    addView(tile.sheenView)
                    val label = tile.labelView ?: TileLabelView(context).also { tile.labelView = it }
                    label.text = tile.label
                    label.alpha = if (tile.isFocused) 1f else 0f
                    addView(label)
                }
            }
            rebuildRows()
            val restored = focusedTile?.takeIf { it.parent === this }?.gridIndex ?: focusedIndex
            focusedIndex = if (tiles.isEmpty()) 0 else restored.coerceIn(0, tiles.size - 1)
            if (focusedTile != null && tiles.isNotEmpty()) tiles[focusedIndex].requestFocus()
        }

        /** Moves focus to the tile at [index]. */
        public fun focusTile(index: Int): Boolean = index in tiles.indices && tiles[index].requestFocus()

        /** Column of the tile at [index] within its row. */
        public fun columnOf(index: Int): Int = index - rowStarts[tileRows[index]]

        /** Moves focus to [row], at [column] or the last tile of a shorter row. */
        public fun focusCell(
            row: Int,
            column: Int,
        ): Boolean = row in rowStarts.indices && focusTile(cellIndex(row, column.coerceAtLeast(0)))

        private fun rebuildRows() {
            val starts = ArrayList<Int>()
            val rows = IntArray(tiles.size)
            var index = 0
            for (size in sectionSizes) {
                for (offset in 0 until size) {
                    if (offset % columnCount == 0) starts.add(index)
                    rows[index] = starts.size - 1
                    index++
                }
            }
            rowStarts = starts.toIntArray()
            tileRows = rows
        }

        private fun rowSize(row: Int): Int {
            val end = if (row + 1 < rowStarts.size) rowStarts[row + 1] else tiles.size
            return end - rowStarts[row]
        }

        private fun cellIndex(
            row: Int,
            column: Int,
        ): Int = rowStarts[row] + min(column, rowSize(row) - 1)

        override fun focusSearch(
            focused: View?,
            direction: Int,
        ): View? {
            val tile = focused as? TileView
            if (tile == null || tile.parent !== this) return super.focusSearch(focused, direction)
            return when (val target = targetIndex(tile.gridIndex, direction)) {
                NO_TILE -> focused
                LEAVE_GRID -> super.focusSearch(focused, direction)
                else -> tiles[target]
            }
        }

        /** Index of the tile [direction] from [index], [NO_TILE] at an edge, or [LEAVE_GRID] above the first row. */
        private fun targetIndex(
            index: Int,
            direction: Int,
        ): Int {
            val row = tileRows[index]
            val column = index - rowStarts[row]
            val lastRow = rowCount - 1
            return when {
                direction == FOCUS_LEFT -> if (column > 0) index - 1 else NO_TILE
                direction == FOCUS_RIGHT -> if (column + 1 < rowSize(row)) index + 1 else NO_TILE
                direction == FOCUS_DOWN -> if (row < lastRow) cellIndex(row + 1, column) else NO_TILE
                direction == FOCUS_UP && row > 0 -> cellIndex(row - 1, column)
                else -> LEAVE_GRID
            }
        }

        override fun onRequestFocusInDescendants(
            direction: Int,
            previouslyFocusedRect: Rect?,
        ): Boolean {
            if (tiles.isEmpty()) return false
            return tiles[focusedIndex.coerceIn(0, tiles.size - 1)].requestFocus(direction, previouslyFocusedRect)
        }

        override fun requestChildFocus(
            child: View?,
            focused: View?,
        ) {
            super.requestChildFocus(child, focused)
            val tile = focused as? TileView ?: return
            if (tile.parent !== this) return
            focusedIndex = tile.gridIndex
            val row = tileRows[focusedIndex]
            if (row != focusedRow) {
                focusedRow = row
                listener?.onFocusedRowChanged(row)
            }
        }

        override fun onMeasure(
            widthMeasureSpec: Int,
            heightMeasureSpec: Int,
        ) {
            val width = MeasureSpec.getSize(widthMeasureSpec)
            val viewport = MeasureSpec.getSize(heightMeasureSpec)
            val inner = width - paddingLeft - paddingRight
            tileWidth = max(0, (inner - columnGap * (columnCount - 1)) / columnCount)
            tileHeight = (tileWidth / TileView.ASPECT_RATIO).toInt()
            rowPitch = tileHeight + rowGap
            firstRowTop = max(paddingTop, viewport - peek - rowPitch)

            val tileWidthSpec = MeasureSpec.makeMeasureSpec(tileWidth, MeasureSpec.EXACTLY)
            val tileHeightSpec = MeasureSpec.makeMeasureSpec(tileHeight, MeasureSpec.EXACTLY)
            val labelWidthSpec = MeasureSpec.makeMeasureSpec(tileWidth + columnGap, MeasureSpec.EXACTLY)
            val labelHeightSpec = MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED)
            for (tile in tiles) {
                tile.measure(tileWidthSpec, tileHeightSpec)
                tile.sheenView.measure(tileWidthSpec, tileHeightSpec)
                tile.labelView?.measure(labelWidthSpec, labelHeightSpec)
            }
            val content = rowTop(rowCount) + paddingBottom
            setMeasuredDimension(width, max(viewport, content))
        }

        override fun onLayout(
            changed: Boolean,
            l: Int,
            t: Int,
            r: Int,
            b: Int,
        ) {
            val overhang = (tileHeight * (TileView.FOCUSED_SCALE - 1f) / 2f).toInt()
            for (index in tiles.indices) {
                val tile = tiles[index]
                val row = tileRows[index]
                val left = paddingLeft + (index - rowStarts[row]) * (tileWidth + columnGap)
                val top = rowTop(row)
                tile.layout(left, top, left + tileWidth, top + tileHeight)
                tile.sheenView.layout(left, top, left + tileWidth, top + tileHeight)
                val label = tile.labelView ?: continue
                val labelTop = top + tileHeight + overhang + labelGap
                val labelLeft = left - columnGap / 2
                label.layout(labelLeft, labelTop, labelLeft + label.measuredWidth, labelTop + label.measuredHeight)
            }
        }

        // Children are drawn one by one when the grid fades; no offscreen layer for the whole grid.
        override fun hasOverlappingRendering(): Boolean = false

        private companion object {
            const val DEFAULT_COLUMNS = 5
            const val NO_TILE = -1
            const val LEAVE_GRID = -2
        }
    }
