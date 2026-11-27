package ai.aneesiqbal.rozan.launcher.home

import ai.aneesiqbal.rozan.launcher.apps.AppEntry
import ai.aneesiqbal.rozan.ui.TileGridView
import ai.aneesiqbal.rozan.ui.TileView
import android.content.Context
import android.view.View

/**
 * Owns the home grid's tiles: the built-in row and one tile per app. App tiles
 * are reused across list refreshes (keyed by activity), so their art and focus
 * survive an install or update elsewhere in the list.
 */
class HomeTiles(
    private val context: Context,
    private val grid: TileGridView,
    private val art: TileArtLoader,
    private val actions: Actions,
) : View.OnClickListener,
    View.OnLongClickListener {
    /** What happens when tiles are used. */
    interface Actions {
        fun onBuiltInSelected(builtIn: BuiltIn)

        fun onAppSelected(app: AppEntry)

        fun onAppOptionsRequested(
            tile: TileView,
            app: AppEntry,
        )
    }

    private val builtInTiles: List<TileView> = BuiltIn.entries.map { newTile(context.getString(it.label), it) }
    private var appTiles = HashMap<String, TileView>()

    /** Apps currently shown, in grid order. */
    var apps: List<AppEntry> = emptyList()
        private set

    private var artWidth = 0
    private var artHeight = 0
    private var artReleased = false

    /** Shows [entries] after the built-in row. */
    fun setApps(entries: List<AppEntry>) {
        val next = HashMap<String, TileView>(entries.size * 2)
        val tiles =
            entries.map { entry ->
                val tile = appTiles[entry.key] ?: newTile(entry.label, entry)
                tile.tag = entry
                if (tile.label != entry.label) tile.label = entry.label
                next[entry.key] = tile
                tile
            }
        appTiles = next
        apps = entries
        grid.setTiles(if (tiles.isEmpty()) listOf(builtInTiles) else listOf(builtInTiles, tiles))
        requestArt()
    }

    /**
     * Forgets the art of [packageName]'s tiles (it was updated, changed or removed). The next [setApps]
     * loads it again: an update changes the entry's version, so its art is rendered anew.
     */
    fun refreshPackage(packageName: String) {
        art.forgetPackage(packageName)
    }

    /**
     * Drops every tile's art and the art cache (memory pressure while Home is in the background), so
     * the GPU memory can be freed; [restoreArt] loads it again.
     */
    fun releaseArt() {
        art.cancelAll()
        for (index in 0 until grid.tileCount) grid.tileAt(index).art = null
        TileArtLoader.trimCache()
        artReleased = true
    }

    /** Loads the art dropped by [releaseArt] again: app art from the disk cache, not rendered again. */
    fun restoreArt() {
        if (!artReleased) return
        artReleased = false
        requestArt()
    }

    /** The grid measured its tiles; render art at that size. */
    fun onTileSizeChanged(
        width: Int,
        height: Int,
    ) {
        if (width == artWidth && height == artHeight) return
        artWidth = width
        artHeight = height
        requestArt()
    }

    /** The app shown by [view], if it is an app tile. */
    fun appFor(view: View?): AppEntry? = (view as? TileView)?.tag as? AppEntry

    /** Labels of all tiles in grid order (used by tests). */
    fun labels(): List<String> = List(grid.tileCount) { grid.tileAt(it).label.toString() }

    private fun requestArt() {
        if (artWidth <= 0) return
        for (index in 0 until grid.tileCount) {
            val tile = grid.tileAt(index)
            val tileArt =
                when (val tag = tile.tag) {
                    is BuiltIn -> TileArt.Glyph(tag)
                    is AppEntry -> TileArt.App(tag)
                    else -> continue
                }
            art.load(tile, tileArt, artWidth, artHeight)
        }
    }

    private fun newTile(
        label: String,
        tag: Any,
    ): TileView =
        TileView(context).also {
            it.label = label
            it.tag = tag
            it.setOnClickListener(this)
            it.setOnLongClickListener(this)
        }

    override fun onClick(view: View) {
        when (val tag = view.tag) {
            is BuiltIn -> actions.onBuiltInSelected(tag)
            is AppEntry -> actions.onAppSelected(tag)
        }
    }

    override fun onLongClick(view: View): Boolean {
        val app = view.tag as? AppEntry ?: return false
        actions.onAppOptionsRequested(view as TileView, app)
        return true
    }
}
