package ai.aneesiqbal.rozan.launcher

import ai.aneesiqbal.rozan.airplay.android.AppLanguage
import android.app.Activity
import android.content.Context

/**
 * An Activity whose resources are in the app language: the system's or the one chosen in the settings
 * ([AppLanguage]). Every launcher screen that shows text extends it.
 */
abstract class LocalizedActivity : Activity() {
    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(AppLanguage.wrap(newBase))
    }
}
