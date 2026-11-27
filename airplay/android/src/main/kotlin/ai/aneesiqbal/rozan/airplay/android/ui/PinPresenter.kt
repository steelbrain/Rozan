package ai.aneesiqbal.rozan.airplay.android.ui

import ai.aneesiqbal.rozan.airplay.DeviceNames
import ai.aneesiqbal.rozan.airplay.android.AirPlaySettings
import ai.aneesiqbal.rozan.airplay.android.AppLanguage
import ai.aneesiqbal.rozan.airplay.android.OverlayPermission
import ai.aneesiqbal.rozan.airplay.android.R
import ai.aneesiqbal.rozan.airplay.receiver.ClientInfo
import android.annotation.SuppressLint
import android.content.Context
import android.graphics.PixelFormat
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.WindowManager
import android.widget.TextView

/**
 * Shows the pairing PIN. With the overlay permission it is a window over
 * whatever app is in front; without it, or when the screen has to be turned on
 * for it, [PinActivity] (without the permission Android only lets it come to
 * the front when this app is already in front, or on API 28). Main thread only.
 */
internal class PinPresenter(
    private val context: Context,
    private val settings: AirPlaySettings,
) {
    private val windowManager: WindowManager = context.getSystemService(WindowManager::class.java)
    private var overlay: View? = null

    /**
     * Shows [pin] for [client], replacing any PIN on screen. With [turnScreenOn] (the screen is
     * off and is to be woken for it) the PIN is shown by [PinActivity], which turns the screen on;
     * an overlay window cannot.
     */
    fun show(
        pin: String,
        client: ClientInfo,
        turnScreenOn: Boolean,
    ) {
        hide()
        if (!turnScreenOn && OverlayPermission.isGranted(context) && showOverlay(pin, client)) return
        SessionActivities.startPin(context)
    }

    /** Removes the overlay; [PinActivity] finishes itself when the session state clears. */
    fun hide() {
        val view = overlay ?: return
        overlay = null
        windowManager.removeViewImmediate(view)
    }

    // A window's root view has no parent to inflate against.
    @SuppressLint("InflateParams")
    private fun showOverlay(
        pin: String,
        client: ClientInfo,
    ): Boolean {
        // The application context was localized when the process started; the PIN uses the current language.
        val view = LayoutInflater.from(AppLanguage.wrap(context)).inflate(R.layout.airplay_pin, null)
        PinViews.bind(view, pin, client, settings.deviceName)
        val params =
            WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                // Not focusable: remote keys keep going to the app underneath. Touchable on purpose:
                // non-touchable windows are left out of the accessibility tree, and screen readers
                // must be able to read the code out.
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT,
            )
        params.title = "AirPlay PIN"
        return try {
            windowManager.addView(view, params)
            overlay = view
            PinViews.fadeIn(view)
            true
        } catch (e: WindowManager.BadTokenException) {
            Log.w(TAG, "overlay refused; falling back to an activity", e)
            false
        } catch (e: SecurityException) {
            Log.w(TAG, "overlay permission revoked; falling back to an activity", e)
            false
        }
    }

    private companion object {
        const val TAG = "AirPlayPin"
    }
}

/** Fills the PIN layout; shared by the overlay and [PinActivity]. */
internal object PinViews {
    private const val FADE_MS = 150L

    fun bind(
        root: View,
        pin: String,
        client: ClientInfo,
        receiverName: String,
    ) {
        val resources = root.resources
        // The client's name is untrusted text shown on screen: clean it and bound its length.
        val clientName = client.name?.let(DeviceNames::sanitize) ?: resources.getString(R.string.airplay_unknown_client)
        root.findViewById<TextView>(R.id.airplay_pin_receiver).text =
            resources.getString(R.string.airplay_receiver_line, receiverName)
        root.findViewById<TextView>(R.id.airplay_pin_prompt).text =
            resources.getString(R.string.airplay_pin_prompt, clientName)
        root.findViewById<TextView>(R.id.airplay_pin_code).text = pin
    }

    /** Short fade; the animator scale (0 with reduced motion) makes it instant when animations are off. */
    fun fadeIn(view: View) {
        view.alpha = 0f
        view
            .animate()
            .alpha(1f)
            .setDuration(FADE_MS)
            .start()
    }
}
