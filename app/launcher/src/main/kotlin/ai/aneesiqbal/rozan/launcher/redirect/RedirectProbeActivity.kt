package ai.aneesiqbal.rozan.launcher.redirect

import android.app.Activity
import android.os.Build

/**
 * A transparent activity that finishes as soon as it has focus, so the
 * window underneath regains focus and sends a fresh
 * `TYPE_WINDOW_STATE_CHANGED` with its class (6.7; research A6). Started by
 * [HomeRedirectService] only after a (re)connect while the stock launcher is
 * in front. It runs in its own task, so finishing it never brings up Rozan.
 */
class RedirectProbeActivity : Activity() {
    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (!hasFocus || isFinishing) return
        finish()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            overrideActivityTransition(OVERRIDE_TRANSITION_CLOSE, 0, 0)
        } else {
            // The only way to drop the close animation before API 34; deprecated from 34 on.
            @Suppress("DEPRECATION")
            overridePendingTransition(0, 0)
        }
    }
}
