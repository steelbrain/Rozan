package ai.aneesiqbal.rozan.launcher.inputs

import android.graphics.drawable.Drawable

/**
 * The TV's inputs, as the home screen needs them. [SystemTvInputSource] reads
 * them from `TvInputManager`; instrumented tests put a fake in its place
 * (debug builds only, see `TvInputSources`).
 */
internal interface TvInputSource {
    /** The inputs as the system lists them now. Off the main thread: it makes binder calls and reads settings. */
    fun read(): List<InputRecord>

    /** The input's icon, or null if it has none. Off the main thread. */
    fun loadIcon(id: String): Drawable?

    /**
     * Calls [onChanged] on the main thread whenever an input is added, removed
     * or updated, or its state changes, until [stop]. Calling it again replaces
     * the listener.
     */
    fun start(onChanged: Runnable)

    /** Stops the calls that [start] set up. */
    fun stop()

    /** A device without the TV input framework: no inputs, never a change. */
    object None : TvInputSource {
        override fun read(): List<InputRecord> = emptyList()

        override fun loadIcon(id: String): Drawable? = null

        override fun start(onChanged: Runnable) = Unit

        override fun stop() = Unit
    }
}
