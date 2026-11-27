package ai.aneesiqbal.rozan.launcher.home

import ai.aneesiqbal.rozan.launcher.Background
import ai.aneesiqbal.rozan.launcher.apps.AppEntry
import ai.aneesiqbal.rozan.launcher.apps.packageInfoOrNull
import ai.aneesiqbal.rozan.ui.TileBevel
import ai.aneesiqbal.rozan.ui.TileView
import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.content.res.Resources
import android.graphics.Bitmap
import android.graphics.Color
import android.os.Build
import android.util.Log
import android.util.LruCache
import androidx.tracing.trace
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import ai.aneesiqbal.rozan.ui.R as UiR

/** What a tile shows. */
sealed interface TileArt {
    /** Cache identity; app keys start with `package/`. */
    val key: String

    /** An installed app: its banner, or its icon and name. */
    data class App(
        val entry: AppEntry,
    ) : TileArt {
        override val key: String get() = entry.key
    }

    /** A built-in action: a glyph on a tinted tile. */
    data class Glyph(
        val builtIn: BuiltIn,
    ) : TileArt {
        override val key: String get() = "builtin:${builtIn.name}"
    }
}

/**
 * Loads tile art on background threads and hands it to tiles on the main
 * thread. Tiles draw a placeholder until then. Results are kept in a small
 * process-wide cache, so re-creating the home screen or refreshing the app list
 * does not render them again, and app art also in [TileArtDiskCache], so a cold
 * start or a return after [trimCache] decodes it instead of rendering it. Art
 * is cached with the tiles' glass edge already baked in ([TileBevel]).
 */
class TileArtLoader(
    context: Context,
    private val scope: CoroutineScope,
) {
    private val appContext = context.applicationContext
    private val pm: PackageManager = appContext.packageManager
    private val density = context.resources.displayMetrics.density
    private val cornerRadius = context.resources.getDimension(UiR.dimen.rozan_tile_corner_radius)
    private val renderer = TileArtRenderer(cornerRadius)
    private val disk = TileArtDiskCache(File(appContext.cacheDir, DISK_CACHE))
    private val jobs = HashMap<TileView, Job>()

    /**
     * What all stored art depends on besides the app: Rozan's build, the system's, the configuration and
     * the bevel baked into it.
     */
    private val renderIdentity by lazy {
        val config = appContext.resources.configuration
        val own = ownPackageStamp()
        "$own|${Build.FINGERPRINT}|${config.locales.toLanguageTags()}|${config.densityDpi}|${config.uiMode}|" +
            "$cornerRadius|${TileBevel.VERSION}"
    }

    /** Shows [art] on [tile] at [width] x [height] pixels, from the cache or once rendered. */
    fun load(
        tile: TileView,
        art: TileArt,
        width: Int,
        height: Int,
    ) {
        if (width <= 0 || height <= 0) return
        jobs.remove(tile)?.cancel()
        val cacheKey = memoryKey(art, width, height)
        val cached = cache.get(cacheKey)
        if (cached != null) {
            tile.art = cached
            return
        }
        jobs[tile] =
            scope.launch {
                val bitmap = withContext(Background.work) { trace("Rozan.loadTileArt") { produce(art, width, height) } }
                jobs.remove(tile)
                if (bitmap != null) {
                    cache.put(cacheKey, bitmap)
                    tile.art = bitmap
                }
            }
    }

    /** Drops cached art of [packageName] (it was updated or removed), in memory and on disk. */
    fun forgetPackage(packageName: String) {
        val prefix = "$packageName/"
        for (key in cache.snapshot().keys) {
            if (key.startsWith(prefix)) cache.remove(key)
        }
        // Housekeeping only: stored art is named after the app's version, so none of it is stale.
        Background.workExecutor.execute { disk.forgetPackage(packageName) }
    }

    /** Stops loading art for all tiles (their art is being released). */
    fun cancelAll() {
        for (job in jobs.values) job.cancel()
        jobs.clear()
    }

    private fun produce(
        art: TileArt,
        width: Int,
        height: Int,
    ): Bitmap? =
        when (art) {
            is TileArt.Glyph -> {
                trace("Rozan.renderTileArt") { renderGlyph(art.builtIn, width, height)?.let(::finish) }
                    ?.let(renderer::toHardware)
            }

            is TileArt.App -> {
                produceApp(art.entry, width, height)
            }
        }

    /** App art from the disk cache, or rendered and then stored there. */
    private fun produceApp(
        entry: AppEntry,
        width: Int,
        height: Int,
    ): Bitmap? {
        val name = diskName(entry, width, height)
        if (name != null) {
            val stored = trace("Rozan.readTileArt") { disk.read(name, width, height) }
            if (stored != null) return stored
        }
        val rendered = trace("Rozan.renderTileArt") { renderApp(entry, width, height)?.let(::finish) } ?: return null
        if (name == null) return renderer.toHardware(rendered)
        val shown = renderer.toHardware(rendered, keep = true)
        // After the tile has its art: the PNG encoding never delays a tile.
        Background.workExecutor.execute {
            trace("Rozan.writeTileArt") { disk.write(name, rendered) }
            if (shown !== rendered) rendered.recycle()
        }
        return shown
    }

    /** The disk cache name of [entry]'s art, or null when its version is unknown (it is not stored). */
    private fun diskName(
        entry: AppEntry,
        width: Int,
        height: Int,
    ): String? {
        if (entry.versionCode == 0L && entry.updatedAt == 0L) return null
        val identity = "${entry.key}|${entry.versionCode}|${entry.updatedAt}|${entry.label}|$renderIdentity"
        return TileArtDiskCache.nameFor(entry.packageName, width, height, identity)
    }

    /** [rendered] with the glass edge baked in ([TileBevel]); stored and shown like this. */
    private fun finish(rendered: Bitmap): Bitmap {
        val baked = trace("Rozan.bakeTileBevel") { TileBevel.apply(rendered, cornerRadius, density) }
        rendered.recycle()
        return baked
    }

    private fun ownPackageStamp(): String {
        val info = pm.packageInfoOrNull(appContext.packageName)
        if (info != null) return "${info.longVersionCode}.${info.lastUpdateTime}"
        // Our own package always exists; without its stamp nothing stored can be trusted.
        Log.w(TAG, "own package not found")
        return "unknown.${System.nanoTime()}"
    }

    private fun renderGlyph(
        builtIn: BuiltIn,
        width: Int,
        height: Int,
    ): Bitmap? {
        val glyph = appContext.getDrawable(builtIn.glyph)?.mutate() ?: return null
        glyph.setTint(Color.WHITE)
        return renderer.renderGlyphTile(glyph, appContext.getColor(builtIn.tint), width, height)
    }

    private fun renderApp(
        entry: AppEntry,
        width: Int,
        height: Int,
    ): Bitmap? {
        val component = ComponentName(entry.packageName, entry.activityName)
        return try {
            val banner = pm.getActivityBanner(component)
            if (banner != null) {
                renderer.renderBanner(banner, width, height)
            } else {
                renderer.renderIconTile(pm.getActivityIcon(component), entry.label, width, height)
            }
        } catch (e: PackageManager.NameNotFoundException) {
            Log.i(TAG, "${entry.key} disappeared before its tile was drawn", e)
            null
        } catch (e: Resources.NotFoundException) {
            Log.w(TAG, "${entry.key} has unreadable banner or icon resources", e)
            null
        }
    }

    companion object {
        private const val TAG = "TileArtLoader"
        private const val DISK_CACHE = "tiles"
        private const val CACHE_KB = 24 * 1024
        private const val BYTES_PER_PIXEL = 4
        private const val KB = 1024

        private val cache =
            object : LruCache<String, Bitmap>(CACHE_KB) {
                override fun sizeOf(
                    key: String,
                    value: Bitmap,
                ): Int = value.width * value.height * BYTES_PER_PIXEL / KB
            }

        /** Frees cached art that no tile holds any more (memory pressure). */
        fun trimCache() {
            cache.evictAll()
        }

        /** The in-memory cache key: art of an app changes with its version and label. */
        private fun memoryKey(
            art: TileArt,
            width: Int,
            height: Int,
        ): String =
            when (art) {
                is TileArt.Glyph -> "${art.key}@${width}x$height"
                is TileArt.App -> with(art.entry) { "$key@${width}x$height#$versionCode.$updatedAt.$label" }
            }
    }
}
