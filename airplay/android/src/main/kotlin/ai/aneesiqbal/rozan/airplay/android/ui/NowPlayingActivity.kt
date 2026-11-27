package ai.aneesiqbal.rozan.airplay.android.ui

import ai.aneesiqbal.rozan.airplay.DeviceNames
import ai.aneesiqbal.rozan.airplay.android.AirPlayRuntime
import ai.aneesiqbal.rozan.airplay.android.AppLanguage
import ai.aneesiqbal.rozan.airplay.android.R
import ai.aneesiqbal.rozan.airplay.android.session.SessionState
import android.app.Activity
import android.content.Context
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.ImageView
import android.widget.TextView
import java.io.IOException
import java.nio.ByteBuffer
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * What is playing during an audio-only session: artwork, title, artist,
 * album. Back closes the screen while the audio keeps playing; the screen
 * also closes when the stream ends.
 */
internal class NowPlayingActivity :
    Activity(),
    SessionState.Listener {
    private lateinit var runtime: AirPlayRuntime
    private lateinit var artwork: ImageView
    private lateinit var source: TextView
    private lateinit var title: TextView
    private lateinit var artist: TextView
    private lateinit var album: TextView
    private val artworkLoader = ArtworkLoader()
    private var shownArtwork: ByteArray? = null

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(AppLanguage.wrap(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        runtime = AirPlayRuntime.get(this)
        setShowWhenLocked(true)
        setTurnScreenOn(true)
        setContentView(R.layout.airplay_now_playing)
        artwork = findViewById(R.id.airplay_now_playing_artwork)
        source = findViewById(R.id.airplay_now_playing_source)
        title = findViewById(R.id.airplay_now_playing_title)
        artist = findViewById(R.id.airplay_now_playing_artist)
        album = findViewById(R.id.airplay_now_playing_album)
        runtime.session.addListener(this)
        onSessionChanged()
    }

    override fun onDestroy() {
        runtime.session.removeListener(this)
        artworkLoader.shutdown()
        super.onDestroy()
    }

    override fun onSessionChanged() {
        val client = runtime.session.audioClient
        if (client == null) {
            if (!isFinishing) finish()
            return
        }
        val metadata = runtime.session.nowPlaying
        val clientName = client.name?.let(DeviceNames::sanitize) ?: getString(R.string.airplay_unknown_client)
        source.text = getString(R.string.airplay_now_playing_from, clientName)
        title.text = metadata?.title?.takeIf { it.isNotBlank() } ?: getString(R.string.airplay_now_playing_untitled)
        artist.text = metadata?.artist.orEmpty()
        album.text = metadata?.album.orEmpty()
        val bytes = metadata?.artwork
        if (bytes !== shownArtwork) {
            shownArtwork = bytes
            artwork.setImageDrawable(null)
            if (bytes != null) {
                artworkLoader.load(bytes, resources.getDimensionPixelSize(R.dimen.airplay_artwork)) { bitmap ->
                    artwork.setImageBitmap(bitmap)
                }
            } else {
                artworkLoader.cancel()
            }
        }
    }
}

/**
 * Decodes client-supplied artwork off the main thread with [ImageDecoder],
 * downsampled to the size it is drawn at, into a hardware bitmap. Input is
 * untrusted: oversized files are ignored and decode errors mean no artwork.
 */
internal class ArtworkLoader {
    private val executor: ExecutorService = Executors.newSingleThreadExecutor { Thread(it, "airplay-artwork") }
    private val main = Handler(Looper.getMainLooper())
    private var generation = 0

    /** Decodes [bytes] to fit [maxPixels]; [onLoaded] runs on the main thread unless superseded. */
    fun load(
        bytes: ByteArray,
        maxPixels: Int,
        onLoaded: (Bitmap) -> Unit,
    ) {
        val ticket = ++generation
        executor.execute {
            val bitmap = decode(bytes, maxPixels) ?: return@execute
            main.post { if (ticket == generation) onLoaded(bitmap) }
        }
    }

    /** Drops any pending result. */
    fun cancel() {
        generation++
    }

    fun shutdown() {
        cancel()
        executor.shutdownNow()
    }

    companion object {
        /** Larger files are not artwork a client should send; ignore them. */
        const val MAX_BYTES = 8 * 1024 * 1024

        fun decode(
            bytes: ByteArray,
            maxPixels: Int,
        ): Bitmap? {
            if (bytes.isEmpty() || bytes.size > MAX_BYTES || maxPixels <= 0) return null
            return try {
                ImageDecoder.decodeBitmap(ImageDecoder.createSource(ByteBuffer.wrap(bytes))) { decoder, info, _ ->
                    val longest = max(info.size.width, info.size.height).coerceAtLeast(1)
                    val scale = (maxPixels.toFloat() / longest).coerceAtMost(1f)
                    decoder.setTargetSize(
                        (info.size.width * scale).roundToInt().coerceAtLeast(1),
                        (info.size.height * scale).roundToInt().coerceAtLeast(1),
                    )
                    decoder.allocator = ImageDecoder.ALLOCATOR_HARDWARE
                }
            } catch (ignored: IOException) {
                null
            } catch (ignored: IllegalArgumentException) {
                null
            }
        }
    }
}
