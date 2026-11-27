package ai.aneesiqbal.rozan.launcher.inputs

import ai.aneesiqbal.rozan.launcher.redirect.HomeSetupReader
import android.content.Context
import android.graphics.drawable.Drawable
import android.media.tv.TvInputInfo
import android.media.tv.TvInputManager
import android.os.Handler
import android.os.Looper
import android.util.Log

/**
 * [TvInputSource] over the system's `TvInputManager`. None of the calls needs
 * a permission (docs/research/tv-inputs.md, sections 3 to 5).
 */
internal class SystemTvInputSource private constructor(
    private val context: Context,
    private val manager: TvInputManager,
) : TvInputSource {
    private val handler = Handler(Looper.getMainLooper())
    private var callback: Callback? = null

    override fun read(): List<InputRecord> = manager.tvInputList.map(::record)

    /**
     * The input's own icon. `TvInputInfo.loadIcon` falls back to the icon of
     * the app behind the input (on a TV, its input service, the same for every
     * port); that one is left out. Both load through the package manager's icon
     * cache, so the fallback shares the service icon's constant state.
     */
    override fun loadIcon(id: String): Drawable? {
        val info = manager.getTvInputInfo(id) ?: return null
        val icon = info.loadIcon(context) ?: return null
        val state = icon.constantState ?: return icon
        val serviceIcon = info.serviceInfo.loadIcon(context.packageManager)
        return if (serviceIcon?.constantState === state) null else icon
    }

    override fun start(onChanged: Runnable) {
        stop()
        val callback = Callback(onChanged)
        this.callback = callback
        manager.registerCallback(callback, handler)
    }

    override fun stop() {
        val callback = callback ?: return
        this.callback = null
        manager.unregisterCallback(callback)
    }

    private fun record(info: TvInputInfo): InputRecord =
        InputRecord(
            id = info.id,
            kind = kindOf(info.type),
            passthrough = info.isPassthroughInput,
            label = info.loadLabel(context)?.toString().orEmpty(),
            customLabel = userSetting(info) { it.loadCustomLabel(context)?.toString() },
            hidden = userSetting(info) { it.isHidden(context) } == true,
            parentId = info.parentId,
            state = stateOf(manager.getInputState(info.id)),
        )

    /**
     * The custom name and the hidden flag are secure settings that the platform
     * reads and parses in our process (research doc, 4.4). If a system refuses
     * the read, or another app stored a malformed value, the input is listed
     * under its system name instead of failing the whole list.
     *
     * The platform splits the stored value and indexes the parts without a
     * length check, so a malformed value throws ArrayIndexOutOfBoundsException;
     * detekt counts that as too generic to catch, hence the suppression.
     */
    @Suppress("TooGenericExceptionCaught")
    private fun <T> userSetting(
        info: TvInputInfo,
        read: (TvInputInfo) -> T,
    ): T? =
        try {
            read(info)
        } catch (e: SecurityException) {
            Log.w(TAG, "Cannot read the TV's input settings", e)
            null
        } catch (e: ArrayIndexOutOfBoundsException) {
            Log.w(TAG, "Malformed TV input settings", e)
            null
        }

    /** Reports every change to the list or to an input's state. */
    private class Callback(
        private val onChanged: Runnable,
    ) : TvInputManager.TvInputCallback() {
        override fun onInputAdded(inputId: String) = onChanged.run()

        override fun onInputRemoved(inputId: String) = onChanged.run()

        override fun onInputUpdated(inputId: String) = onChanged.run()

        override fun onInputStateChanged(
            inputId: String,
            state: Int,
        ) = onChanged.run()

        override fun onTvInputInfoUpdated(inputInfo: TvInputInfo) = onChanged.run()
    }

    companion object {
        private const val TAG = "TvInputs"

        /**
         * The system's inputs, or [TvInputSource.None] where there is nothing to
         * switch to: a device without the TV input service, and Fire OS. Fire OS
         * lists a TV's inputs, but sends the passthrough intent to its Live TV
         * settings, and its own switcher needs an Amazon permission
         * (docs/research/tv-inputs.md, section 8.4).
         */
        fun create(context: Context): TvInputSource {
            if (HomeSetupReader.isFireOs(context)) return TvInputSource.None
            val manager = context.getSystemService(TvInputManager::class.java) ?: return TvInputSource.None
            return SystemTvInputSource(context.applicationContext, manager)
        }

        fun kindOf(type: Int): InputKind =
            when (type) {
                TvInputInfo.TYPE_TUNER -> InputKind.TUNER
                TvInputInfo.TYPE_HDMI -> InputKind.HDMI
                TvInputInfo.TYPE_DISPLAY_PORT -> InputKind.DISPLAY_PORT
                TvInputInfo.TYPE_DVI -> InputKind.DVI
                TvInputInfo.TYPE_COMPONENT -> InputKind.COMPONENT
                TvInputInfo.TYPE_COMPOSITE -> InputKind.COMPOSITE
                TvInputInfo.TYPE_SVIDEO -> InputKind.SVIDEO
                TvInputInfo.TYPE_SCART -> InputKind.SCART
                TvInputInfo.TYPE_VGA -> InputKind.VGA
                else -> InputKind.OTHER
            }

        fun stateOf(state: Int): InputState =
            when (state) {
                TvInputManager.INPUT_STATE_CONNECTED_STANDBY -> InputState.STANDBY
                TvInputManager.INPUT_STATE_DISCONNECTED -> InputState.DISCONNECTED
                else -> InputState.CONNECTED
            }
    }
}
