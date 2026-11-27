package ai.aneesiqbal.rozan.airplay.android.video

import android.os.Handler
import android.os.Looper
import android.view.Surface

/**
 * Connects the mirroring surface (owned by `MirrorActivity`, main thread) with
 * the current video sink (created by the receiver on its mirroring thread).
 * Either side may appear first; the sink gets the surface as soon as both
 * exist, and loses it before the surface is destroyed.
 */
internal class SurfaceBridge {
    /** The side that renders into the surface. */
    interface Consumer {
        /** [surface] is ready to render into. Called on the main thread or the registering thread. */
        fun attachSurface(surface: Surface)

        /** The surface is going away; return only once nothing renders into it any more. */
        fun detachSurface()
    }

    /** Observer of the decoded picture size, called on the main thread. */
    fun interface VideoSizeListener {
        /** The decoder's visible picture is [width]×[height] pixels. */
        fun onVideoSizeChanged(
            width: Int,
            height: Int,
        )
    }

    private val main = Handler(Looper.getMainLooper())
    private var consumer: Consumer? = null
    private var surface: Surface? = null

    // Main thread only.
    private var sizeListener: VideoSizeListener? = null
    private var videoWidth = 0
    private var videoHeight = 0

    /** Makes [newConsumer] the current sink, handing it the surface if one exists. */
    @Synchronized
    fun register(newConsumer: Consumer) {
        consumer = newConsumer
        surface?.let(newConsumer::attachSurface)
    }

    /** Removes [old] if it is still the current sink. */
    @Synchronized
    fun unregister(old: Consumer) {
        if (consumer === old) consumer = null
    }

    /** The activity's surface was created (main thread). */
    @Synchronized
    fun surfaceAvailable(newSurface: Surface) {
        surface = newSurface
        consumer?.attachSurface(newSurface)
    }

    /** The activity's surface is being destroyed (main thread); blocks until the sink let go. */
    @Synchronized
    fun surfaceDestroyed() {
        surface = null
        consumer?.detachSurface()
    }

    /** Reports the decoded picture size; any thread. */
    fun onVideoSize(
        width: Int,
        height: Int,
    ) {
        if (width <= 0 || height <= 0) return
        main.post {
            videoWidth = width
            videoHeight = height
            sizeListener?.onVideoSizeChanged(width, height)
        }
    }

    /** Sets the size listener (main thread); it is called at once with the last known size. */
    fun setVideoSizeListener(listener: VideoSizeListener?) {
        sizeListener = listener
        if (listener != null && videoWidth > 0) listener.onVideoSizeChanged(videoWidth, videoHeight)
    }
}
