package ai.aneesiqbal.rozan.launcher.update

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.os.Build
import android.util.Log
import androidx.core.content.IntentCompat
import java.io.File
import java.io.FileInputStream
import java.io.IOException

/**
 * Hands a verified APK to Android's package installer: a full-install
 * session for this package, committed with a [PendingIntent] to
 * [UpdateInstallReceiver]. From API 31 the session asks for no user action
 * (`USER_ACTION_NOT_REQUIRED`); Android grants that to an app updating
 * itself that may install apps and declares
 * `UPDATE_PACKAGES_WITHOUT_USER_ACTION`, and asks the user otherwise
 * (`STATUS_PENDING_USER_ACTION`).
 */
internal object UpdateInstaller {
    private const val TAG = "RozanUpdate"
    private const val BASE_APK = "base.apk"
    private const val BUFFER_BYTES = 64 * 1024

    /** Extra on the status intent: the user selected "Install update". */
    const val EXTRA_USER_INITIATED = "ai.aneesiqbal.rozan.launcher.update.extra.USER_INITIATED"

    /** Writes [apk] into a new session and commits it. Throws [IOException] or [SecurityException]. */
    fun commit(
        context: Context,
        apk: File,
        userInitiated: Boolean,
    ) {
        val installer = context.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
        params.setAppPackageName(context.packageName)
        params.setSize(apk.length())
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            params.setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
        }
        val id = installer.createSession(params)
        try {
            installer.openSession(id).use { session ->
                FileInputStream(apk).use { input ->
                    session.openWrite(BASE_APK, 0, apk.length()).use { out ->
                        input.copyTo(out, BUFFER_BYTES)
                        session.fsync(out)
                    }
                }
                session.commit(statusIntent(context, id, userInitiated).intentSender)
            }
        } catch (e: IOException) {
            abandon(context, id)
            throw e
        } catch (e: SecurityException) {
            abandon(context, id)
            throw e
        }
    }

    /** Abandons session [id] if it is still open. */
    fun abandon(
        context: Context,
        id: Int,
    ) {
        try {
            context.packageManager.packageInstaller.abandonSession(id)
        } catch (e: SecurityException) {
            // Already finished, or not ours.
            Log.i(TAG, "session $id: ${e.message}")
        }
    }

    /**
     * Whether a session committed earlier is still being installed or waits for the user. Sessions that
     * were never committed (a process that died while writing one) are abandoned. Before API 29 a
     * session does not tell whether it was committed, so every open one is abandoned.
     */
    fun inProgress(context: Context): Boolean {
        var committed = false
        for (session in context.packageManager.packageInstaller.mySessions) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && session.isCommitted) {
                committed = true
            } else {
                abandon(context, session.sessionId)
            }
        }
        return committed
    }

    /** Abandons every session Rozan has open (the switch was turned off). */
    fun abandonAll(context: Context) {
        for (session in context.packageManager.packageInstaller.mySessions) abandon(context, session.sessionId)
    }

    private fun statusIntent(
        context: Context,
        sessionId: Int,
        userInitiated: Boolean,
    ): PendingIntent {
        val intent =
            Intent(context, UpdateInstallReceiver::class.java)
                .setPackage(context.packageName)
                .putExtra(EXTRA_USER_INITIATED, userInitiated)
        // The installer adds the status extras, so the intent must be mutable (explicit, to our own receiver).
        val mutable = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_MUTABLE else 0
        return PendingIntent.getBroadcast(context, sessionId, intent, PendingIntent.FLAG_UPDATE_CURRENT or mutable)
    }
}

/**
 * The package installer's result for a session from [UpdateInstaller]. Not
 * exported: only the installer reaches it, through the [PendingIntent].
 */
class UpdateInstallReceiver : BroadcastReceiver() {
    override fun onReceive(
        context: Context,
        intent: Intent,
    ) {
        val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)
        val sessionId = intent.getIntExtra(PackageInstaller.EXTRA_SESSION_ID, -1)
        val userInitiated = intent.getBooleanExtra(UpdateInstaller.EXTRA_USER_INITIATED, false)
        val confirm = IntentCompat.getParcelableExtra(intent, Intent.EXTRA_INTENT, Intent::class.java)
        val message = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)
        val app = context.applicationContext
        val result = goAsync()
        UpdateWorker.execute {
            try {
                Updater(app).onInstallStatus(status, sessionId, userInitiated, confirm, message)
            } finally {
                result.finish()
            }
        }
    }
}

/**
 * After Rozan was updated (by the updater, or with adb): forgets the update
 * that is now installed and makes sure the daily check is still scheduled.
 */
class UpdateReplacedReceiver : BroadcastReceiver() {
    override fun onReceive(
        context: Context,
        intent: Intent,
    ) {
        if (intent.action != Intent.ACTION_MY_PACKAGE_REPLACED) return
        val app = context.applicationContext
        val result = goAsync()
        UpdateWorker.execute {
            try {
                Updater(app).onPackageReplaced()
            } finally {
                result.finish()
            }
        }
    }
}
