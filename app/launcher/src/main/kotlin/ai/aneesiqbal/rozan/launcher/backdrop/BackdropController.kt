package ai.aneesiqbal.rozan.launcher.backdrop

import ai.aneesiqbal.rozan.launcher.Background
import ai.aneesiqbal.rozan.launcher.DebugOverrides
import ai.aneesiqbal.rozan.ui.FloatAnimator
import android.graphics.Bitmap
import android.graphics.drawable.Drawable
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.animation.AccelerateDecelerateInterpolator
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Cycles full-screen backdrop photos with a crossfade.
 *
 * Two stacked [BackdropView]s: [lower] always shows the current photo fully
 * opaque; a new photo is decoded off the main thread into [upper], whose alpha
 * fades from 0 to 1, then it moves down into [lower] and [upper] is emptied.
 * So at most two decoded photos exist at any time, and at rest only one layer
 * draws. With animations off the change is an instant cut.
 */
class BackdropController(
    private val lower: BackdropView,
    private val upper: BackdropView,
    private val source: BackdropSource,
    private val scope: CoroutineScope,
    private val fallback: () -> Drawable,
    private val listener: Listener,
    /** Called with the photo that is now on screen (see [shownImage]). */
    private val onPhotoShown: (BackdropImage?) -> Unit = {},
) : FloatAnimator.Listener {
    /** Observes whether a photo now covers the whole screen. */
    fun interface Listener {
        /** [covered] is true when [lower] shows a photo or the fallback, opaque edge to edge. */
        fun onCoverageChanged(covered: Boolean)
    }

    private val handler = Handler(Looper.getMainLooper())
    private val fade = FloatAnimator(0f, CROSSFADE_MS, this, AccelerateDecelerateInterpolator())
    private val advance = Runnable { showNext() }
    private var images: List<BackdropImage>? = null
    private var shuffle: BackdropShuffle? = null
    private var job: Job? = null
    private var width = 0
    private var height = 0
    private var started = false
    private var lastChange = 0L

    /** The photo in [upper] while it fades in; it becomes [shownImage] halfway through. */
    private var incoming: BackdropImage? = null

    /**
     * The photo on screen: the one in [lower], or the incoming one once its crossfade is past the
     * middle. Null while none is shown or the fallback is.
     */
    var shownImage: BackdropImage? = null
        private set

    /** Time between photo changes. */
    var intervalMs: Long = DEFAULT_INTERVAL_MS
        set(value) {
            field = value
            if (started) schedule()
        }

    /** Photos and credits, once the manifest has been read. */
    val loadedImages: List<BackdropImage>? get() = images

    /** Whether [lower] shows a photo or the fallback, opaque edge to edge (see [Listener]). */
    val isCovered: Boolean get() = lower.bitmap != null || lower.fallback != null

    /** Draws the photos darkened by [color], as [BackdropView.tint]; 0 draws them as they are. */
    fun setTint(color: Int) {
        lower.tint = color
        upper.tint = color
    }

    /** The window size to decode at; call once laid out (and again if it changes). */
    fun setViewport(
        width: Int,
        height: Int,
    ) {
        if (width == this.width && height == this.height) return
        this.width = width
        this.height = height
        if (started && lower.bitmap == null && job?.isActive != true) showNext()
    }

    /** The home screen became visible. */
    fun start() {
        started = true
        if (lower.bitmap == null && lower.fallback == null) showNext() else schedule()
    }

    /** The home screen is no longer visible: no timers, no decoding. */
    fun stop() {
        started = false
        handler.removeCallbacks(advance)
        job?.cancel()
        if (fade.isRunning) fade.jumpTo(1f)
    }

    /** Drops the decoded photos (memory pressure); [start] decodes a new one. */
    fun release() {
        stop()
        upper.bitmap = null
        lower.bitmap = null
        incoming = null
        show(null)
        listener.onCoverageChanged(lower.fallback != null)
    }

    /** Changes to another photo now. */
    fun showNext() {
        handler.removeCallbacks(advance)
        if (width == 0 || height == 0 || job?.isActive == true) return
        // Finish a running crossfade first, so a third photo is never decoded alongside two.
        if (fade.isRunning) fade.jumpTo(1f)
        job =
            scope.launch {
                val list = images ?: withContext(Background.work) { source.loadImages() }.also { images = it }
                if (list.isEmpty()) {
                    showFallback()
                    return@launch
                }
                val image = pick(list)
                val bitmap = withContext(Background.decode) { source.decode(image, width, height) }
                if (bitmap != null) crossfadeTo(bitmap, image) else schedule()
            }
    }

    /** The next photo of the shuffle (or the one a debug build pinned). */
    private fun pick(list: List<BackdropImage>): BackdropImage {
        val pinned = DebugOverrides.backdropFile?.let { file -> list.firstOrNull { it.file == file } }
        if (pinned != null) return pinned
        val picker = shuffle ?: BackdropShuffle(list.size).also { shuffle = it }
        return list[picker.next()]
    }

    private fun showFallback() {
        if (lower.fallback == null) lower.fallback = fallback()
        show(null)
        listener.onCoverageChanged(true)
    }

    private fun crossfadeTo(
        bitmap: Bitmap,
        image: BackdropImage,
    ) {
        lastChange = SystemClock.uptimeMillis()
        upper.bitmap = bitmap
        fade.jumpTo(0f)
        incoming = image
        fade.animateTo(1f)
    }

    private fun show(image: BackdropImage?) {
        if (image == shownImage) return
        shownImage = image
        onPhotoShown(image)
    }

    override fun onValue(value: Float) {
        upper.alpha = value
        val next = incoming
        if (next != null && value >= HALFWAY) {
            incoming = null
            show(next)
        }
        if (value >= 1f && upper.bitmap != null) {
            lower.bitmap = upper.bitmap
            upper.bitmap = null
            upper.alpha = 0f
            listener.onCoverageChanged(true)
            schedule()
        }
    }

    private fun schedule() {
        handler.removeCallbacks(advance)
        if (!started || images?.isEmpty() == true) return
        val due = lastChange + intervalMs - SystemClock.uptimeMillis()
        handler.postDelayed(advance, due.coerceAtLeast(0L))
    }

    private companion object {
        const val CROSSFADE_MS = 1_500L
        const val HALFWAY = 0.5f
        const val DEFAULT_INTERVAL_MS = 10 * 60_000L
    }
}
