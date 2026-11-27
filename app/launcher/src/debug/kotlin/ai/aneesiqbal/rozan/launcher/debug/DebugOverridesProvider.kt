package ai.aneesiqbal.rozan.launcher.debug

import ai.aneesiqbal.rozan.launcher.DebugOverrides
import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.net.Uri
import android.util.Log

/**
 * Debug and benchmark builds only. Content providers are created before the
 * first activity, so this puts the override that [DebugEventsReceiver] stored
 * into [DebugOverrides] before Home reads it. It serves no data.
 */
internal class DebugOverridesProvider : ContentProvider() {
    override fun onCreate(): Boolean {
        val context = context ?: return false
        apply(context)
        return true
    }

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?,
    ): Cursor? = null

    override fun getType(uri: Uri): String? = null

    override fun insert(
        uri: Uri,
        values: ContentValues?,
    ): Uri? = null

    override fun delete(
        uri: Uri,
        selection: String?,
        selectionArgs: Array<out String>?,
    ): Int = 0

    override fun update(
        uri: Uri,
        values: ContentValues?,
        selection: String?,
        selectionArgs: Array<out String>?,
    ): Int = 0

    companion object {
        private const val TAG = "LauncherDebug"
        private const val FILE = "debug_overrides"
        private const val KEY_BACKDROP = "backdrop"
        private const val KEY_UPDATE_URL = "update_url"

        /**
         * Stores the pinned [backdrop] file, empty for none, for the next start (synchronously: the caller
         * usually restarts the app next).
         */
        fun store(
            context: Context,
            backdrop: String,
        ) {
            val editor = context.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit()
            editor.putString(KEY_BACKDROP, backdrop)
            if (!editor.commit()) Log.w(TAG, "could not store the override")
            apply(context)
        }

        /** Stores the updater's metadata [url], empty for GitHub's, and applies it at once. */
        fun storeUpdateUrl(
            context: Context,
            url: String,
        ) {
            val editor = context.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit()
            editor.putString(KEY_UPDATE_URL, url)
            if (!editor.commit()) Log.w(TAG, "could not store the override")
            apply(context)
        }

        /** Reads the stored overrides into [DebugOverrides]. */
        fun apply(context: Context) {
            val prefs = context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
            DebugOverrides.backdropFile = prefs.getString(KEY_BACKDROP, null)?.takeIf { it.isNotEmpty() }
            DebugOverrides.updateMetadataUrl = prefs.getString(KEY_UPDATE_URL, null)?.takeIf { it.isNotEmpty() }
            Log.i(TAG, "overrides: backdrop ${DebugOverrides.backdropFile}, update ${DebugOverrides.updateMetadataUrl}")
        }
    }
}
