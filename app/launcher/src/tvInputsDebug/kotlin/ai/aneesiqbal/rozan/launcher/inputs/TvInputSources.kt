package ai.aneesiqbal.rozan.launcher.inputs

import ai.aneesiqbal.rozan.launcher.redirect.HomeSetupReader
import android.content.Context

/**
 * Where the home screen gets the TV's inputs and looks for its input pickers.
 * Debug builds only (see build.gradle.kts): the system's, unless an
 * instrumented test has set [testSource], [testPickerResolver] or
 * [testFireOs] before the home screen is created. Every other build uses
 * src/tvInputsRelease, which has no such hooks.
 */
internal object TvInputSources {
    /** Replaces the system's inputs for home screens created after it is set. */
    @Volatile
    var testSource: TvInputSource? = null

    /** Replaces the package manager's answers about input pickers for home screens created after it is set. */
    @Volatile
    var testPickerResolver: PickerResolver? = null

    /** Treats the device as Fire OS, or not, for home screens created after it is set; null asks the system. */
    @Volatile
    var testFireOs: Boolean? = null

    fun create(context: Context): TvInputSource = testSource ?: SystemTvInputSource.create(context)

    fun isFireOs(context: Context): Boolean = testFireOs ?: HomeSetupReader.isFireOs(context)

    fun pickerResolver(context: Context): PickerResolver =
        testPickerResolver ?: SystemPickerResolver(context.applicationContext)
}
