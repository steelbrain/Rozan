package ai.aneesiqbal.rozan.airplay.android.ui

import ai.aneesiqbal.rozan.airplay.android.AirPlayRuntime
import ai.aneesiqbal.rozan.airplay.android.AppLanguage
import ai.aneesiqbal.rozan.airplay.android.R
import ai.aneesiqbal.rozan.airplay.android.session.SessionState
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.WindowManager

/**
 * The PIN as a screen: when the overlay permission is missing, and when the
 * screen is off and "wake on connect" turns it on for the PIN (an activity
 * with `setTurnScreenOn` wakes the device; an overlay window cannot). Shows
 * the current prompt from [SessionState] and finishes when it ends.
 */
internal class PinActivity :
    Activity(),
    SessionState.Listener {
    private lateinit var runtime: AirPlayRuntime

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(AppLanguage.wrap(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        runtime = AirPlayRuntime.get(this)
        setShowWhenLocked(true)
        setTurnScreenOn(true)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setContentView(R.layout.airplay_pin)
        runtime.session.addListener(this)
        onSessionChanged()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        onSessionChanged()
    }

    override fun onDestroy() {
        runtime.session.removeListener(this)
        super.onDestroy()
    }

    override fun onSessionChanged() {
        val prompt = runtime.session.pinPrompt
        if (prompt == null) {
            finish()
            return
        }
        PinViews.bind(findViewById(android.R.id.content), prompt.pin, prompt.client, runtime.settings.deviceName)
    }
}
