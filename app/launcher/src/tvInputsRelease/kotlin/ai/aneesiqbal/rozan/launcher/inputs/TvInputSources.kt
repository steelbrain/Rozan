package ai.aneesiqbal.rozan.launcher.inputs

import ai.aneesiqbal.rozan.launcher.redirect.HomeSetupReader
import android.content.Context

/**
 * Where the home screen gets the TV's inputs and looks for its input pickers:
 * always the system's. Every build but debug uses this file (see
 * build.gradle.kts); the debug version, in src/tvInputsDebug, lets
 * instrumented tests put fakes in their place.
 */
internal object TvInputSources {
    fun create(context: Context): TvInputSource = SystemTvInputSource.create(context)

    fun isFireOs(context: Context): Boolean = HomeSetupReader.isFireOs(context)

    fun pickerResolver(context: Context): PickerResolver = SystemPickerResolver(context.applicationContext)
}
