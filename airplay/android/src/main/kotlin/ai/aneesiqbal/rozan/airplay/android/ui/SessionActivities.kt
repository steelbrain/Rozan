package ai.aneesiqbal.rozan.airplay.android.ui

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.util.Log

/**
 * Starts the session screens from the receiver service. Android allows that
 * from the background when the app holds the overlay permission (or on API
 * 28); otherwise only while one of the app's screens is in front.
 */
internal object SessionActivities {
    private const val TAG = "AirPlayUi"

    fun startMirror(context: Context) = start(context, MirrorActivity::class.java)

    fun startPin(context: Context) = start(context, PinActivity::class.java)

    fun startNowPlaying(context: Context) = start(context, NowPlayingActivity::class.java)

    private fun start(
        context: Context,
        activity: Class<out Activity>,
    ) {
        val intent = Intent(context, activity).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        try {
            context.startActivity(intent)
        } catch (e: ActivityNotFoundException) {
            Log.e(TAG, "cannot start ${activity.simpleName}", e)
        } catch (e: SecurityException) {
            Log.e(TAG, "cannot start ${activity.simpleName}", e)
        }
    }
}
