package ai.aneesiqbal.rozan.launcher.backdrop

import android.content.Context
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.graphics.Rect
import android.util.Log
import androidx.tracing.trace
import java.io.FileNotFoundException
import java.io.IOException
import kotlin.math.ceil
import kotlin.math.max

/**
 * Where backdrop photos come from: `assets/backdrops/` (a `manifest.json` and
 * the JPEGs beside it, described in docs/BACKDROPS.md).
 *
 * All methods block; call them off the main thread.
 */
class BackdropSource(
    context: Context,
) {
    private val assets = context.applicationContext.assets

    /** Reads the manifest; empty when there is none or it is unusable. */
    fun loadImages(): List<BackdropImage> =
        trace("Rozan.loadBackdropManifest") {
            try {
                val json = assets.open("$ASSET_DIR/$MANIFEST").bufferedReader().use { it.readText() }
                BackdropManifest.parse(json)
            } catch (e: FileNotFoundException) {
                Log.i(TAG, "No backdrop manifest; using the plain background", e)
                emptyList()
            } catch (e: IOException) {
                Log.w(TAG, "Could not read the backdrop manifest", e)
                emptyList()
            }
        }

    /**
     * Decodes [image] to exactly [width] x [height] pixels (scaled to cover,
     * centre-cropped) as a hardware bitmap. Returns null if it cannot be read.
     */
    fun decode(
        image: BackdropImage,
        width: Int,
        height: Int,
    ): Bitmap? =
        trace("Rozan.decodeBackdrop") {
            val source = ImageDecoder.createSource(assets, "$ASSET_DIR/${image.file}")
            try {
                ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
                    decoder.allocator = ImageDecoder.ALLOCATOR_HARDWARE
                    val scale = max(width / info.size.width.toFloat(), height / info.size.height.toFloat())
                    val scaledWidth = max(width, ceil(info.size.width * scale).toInt())
                    val scaledHeight = max(height, ceil(info.size.height * scale).toInt())
                    decoder.setTargetSize(scaledWidth, scaledHeight)
                    val left = (scaledWidth - width) / 2
                    val top = (scaledHeight - height) / 2
                    decoder.crop = Rect(left, top, left + width, top + height)
                }
            } catch (e: IOException) {
                Log.w(TAG, "Could not decode backdrop ${image.file}", e)
                null
            }
        }

    private companion object {
        const val TAG = "BackdropSource"
        const val ASSET_DIR = "backdrops"
        const val MANIFEST = "manifest.json"
    }
}
