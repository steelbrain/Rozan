package ai.aneesiqbal.rozan.launcher.inputs

import android.media.tv.TvInputService

/**
 * A TV input in the test APK. The system lists it through TvInputManager like
 * any app's input (as a tuner); nothing ever tunes to it.
 */
class TestTunerInputService : TvInputService() {
    override fun onCreateSession(inputId: String): Session? = null
}
