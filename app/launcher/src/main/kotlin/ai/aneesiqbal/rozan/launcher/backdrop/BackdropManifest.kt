package ai.aneesiqbal.rozan.launcher.backdrop

import android.util.Log
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

/** One backdrop photo and its credit line. */
data class BackdropImage(
    /** File name next to the manifest; never a path. */
    val file: String,
    val title: String,
    val author: String,
    val license: String,
    val sourceUrl: String,
)

/**
 * Parses `backdrops/manifest.json`: a JSON array of objects with the keys
 * `file`, `title`, `author`, `license` and `sourceUrl`. Entries without a
 * usable file name are skipped; malformed JSON yields an empty list, which the
 * home screen shows as its plain dark gradient.
 */
object BackdropManifest {
    /** Upper bound on entries read, so a broken manifest cannot balloon memory. */
    const val MAX_ENTRIES = 500

    private const val MAX_FILE_NAME_LENGTH = 200
    private const val TAG = "BackdropManifest"

    fun parse(json: String): List<BackdropImage> {
        val array =
            try {
                JSONArray(json)
            } catch (e: JSONException) {
                Log.w(TAG, "Ignoring malformed backdrop manifest", e)
                return emptyList()
            }
        return (0 until minOf(array.length(), MAX_ENTRIES)).mapNotNull { index ->
            array.optJSONObject(index)?.toImage()
        }
    }

    private fun JSONObject.toImage(): BackdropImage? {
        val file = text("file")
        if (!isSafeFileName(file)) return null
        return BackdropImage(
            file = file,
            title = text("title"),
            author = text("author"),
            license = text("license"),
            sourceUrl = text("sourceUrl"),
        )
    }

    /** A plain file name in the manifest's directory: no separators, no `.`/`..`. */
    fun isSafeFileName(name: String): Boolean =
        name.isNotEmpty() &&
            name.length <= MAX_FILE_NAME_LENGTH &&
            name != "." &&
            name != ".." &&
            name.none { it == '/' || it == '\\' || it.isISOControl() }

    private fun JSONObject.text(key: String): String = if (isNull(key)) "" else optString(key).trim()
}
