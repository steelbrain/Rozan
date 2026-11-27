package ai.aneesiqbal.rozan.launcher.redirect

import ai.aneesiqbal.rozan.launcher.redirect.RedirectContract.KEY_STOCK_HOME
import ai.aneesiqbal.rozan.launcher.redirect.RedirectContract.KEY_STOCK_PACKAGE
import ai.aneesiqbal.rozan.launcher.redirect.RedirectContract.METHOD_CRASH
import ai.aneesiqbal.rozan.launcher.redirect.RedirectContract.METHOD_OPENS_PICKER
import ai.aneesiqbal.rozan.launcher.redirect.RedirectContract.METHOD_PICKER_FAILED
import ai.aneesiqbal.rozan.launcher.redirect.RedirectContract.METHOD_STATUS
import ai.aneesiqbal.rozan.launcher.redirect.RedirectContract.METHOD_STAY
import ai.aneesiqbal.rozan.launcher.redirect.RedirectContract.METHOD_TEST_SETUP
import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.content.pm.ApplicationInfo
import android.database.Cursor
import android.net.Uri
import android.os.Bundle

/**
 * Entry point into the `:redirect` process for Rozan's other processes (see
 * [RedirectContract]). Not exported: only Rozan's own uid can reach it. It
 * serves `call()` only; the table methods are unused.
 */
class RedirectProvider : ContentProvider() {
    override fun onCreate(): Boolean = true

    override fun call(
        method: String,
        arg: String?,
        extras: Bundle?,
    ): Bundle? {
        val context = context ?: return null
        return when (method) {
            METHOD_CRASH -> {
                RedirectRuntime.onRozanCrashed(context)
                null
            }

            METHOD_STAY -> {
                RedirectRuntime.onStayOnStockHome(context)
            }

            METHOD_OPENS_PICKER -> {
                RedirectRuntime.onRozanOpensPicker(context)
                null
            }

            METHOD_PICKER_FAILED -> {
                RedirectRuntime.onRozanPickerFailed(context)
                null
            }

            METHOD_STATUS -> {
                RedirectRuntime.status(context)
            }

            METHOD_TEST_SETUP -> {
                if (isDebuggable(context)) RedirectRuntime.overrideSetup(context, testSetup(extras))
                null
            }

            else -> {
                null
            }
        }
    }

    private fun isDebuggable(context: Context): Boolean =
        context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0

    private fun testSetup(extras: Bundle?): HomeSetup? {
        val stockPackage = extras?.getString(KEY_STOCK_PACKAGE) ?: return null
        val classes = extras.getStringArrayList(KEY_STOCK_HOME).orEmpty()
        val stockHome = classes.mapTo(LinkedHashSet()) { ActivityId(stockPackage, it) }
        return HomeSetup(HomeMode.REDIRECT, stockPackage, stockHome, emptySet())
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
}
