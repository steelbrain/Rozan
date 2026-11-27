package ai.aneesiqbal.rozan.airplay.android.video

import ai.aneesiqbal.rozan.airplay.receiver.VideoCodec
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaFormat
import android.os.Build
import android.util.Log
import android.view.Surface
import androidx.annotation.RequiresApi
import java.io.IOException
import java.nio.ByteBuffer

/** Creates started video decoders that render to a surface. */
internal fun interface VideoDecoderFactory {
    /**
     * Creates a decoder for [codec], configures it with [format] to render to
     * [surface] and starts it. Returns null when no decoder accepts the stream.
     */
    fun start(
        codec: VideoCodec,
        format: MediaFormat,
        surface: Surface,
    ): MediaCodec?
}

/** Decoder selection and format building for mirroring streams. */
internal object VideoDecoders : VideoDecoderFactory {
    private const val TAG = "AirPlayVideo"

    /** Largest access unit accepted (key frames of a 1080p stream stay well below this). */
    const val MAX_INPUT_BYTES = 2 * 1024 * 1024

    private const val DEFAULT_WIDTH = 1920
    private const val DEFAULT_HEIGHT = 1080

    fun mimeType(codec: VideoCodec): String =
        when (codec) {
            VideoCodec.H264 -> MediaFormat.MIMETYPE_VIDEO_AVC
            VideoCodec.HEVC -> MediaFormat.MIMETYPE_VIDEO_HEVC
        }

    /** Builds the decoder format, with the parameter sets as codec-specific data. */
    fun format(
        codec: VideoCodec,
        parameterSets: ByteArray,
        width: Int,
        height: Int,
    ): MediaFormat {
        val format =
            MediaFormat.createVideoFormat(
                mimeType(codec),
                if (width > 0) width else DEFAULT_WIDTH,
                if (height > 0) height else DEFAULT_HEIGHT,
            )
        val avc = if (codec == VideoCodec.H264) AnnexB.splitAvcParameterSets(parameterSets) else null
        if (avc != null) {
            format.setByteBuffer("csd-0", ByteBuffer.wrap(avc.first))
            format.setByteBuffer("csd-1", ByteBuffer.wrap(avc.second))
        } else if (parameterSets.isNotEmpty()) {
            format.setByteBuffer("csd-0", ByteBuffer.wrap(parameterSets))
        }
        format.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, MAX_INPUT_BYTES)
        // 0 = real-time priority: decode as fast as frames arrive.
        format.setInteger(MediaFormat.KEY_PRIORITY, 0)
        // Low-latency mode is set per decoder in startFirst: decoders without the feature can
        // refuse the whole configuration because of it (OMX.google.h264.decoder on API 31 does).
        return format
    }

    override fun start(
        codec: VideoCodec,
        format: MediaFormat,
        surface: Surface,
    ): MediaCodec? = startFirst(candidates(mimeType(codec)), format, surface)

    /** Starts the first of [names] that accepts [format] and [surface]; null when none does. */
    fun startFirst(
        names: List<String>,
        format: MediaFormat,
        surface: Surface,
    ): MediaCodec? {
        for (name in names) {
            val decoder = startNamed(name, format, surface)
            if (decoder != null) return decoder
        }
        return null
    }

    private fun startNamed(
        name: String,
        format: MediaFormat,
        surface: Surface,
    ): MediaCodec? {
        val decoder = create(name) ?: return null
        val lowLatency = Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && requestLowLatency(decoder, format)
        if (configureAndStart(decoder, name, format, surface)) return decoder
        if (!lowLatency || Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return null
        // Some decoders list the feature and still refuse the key: once more without it.
        format.removeKey(MediaFormat.KEY_LOW_LATENCY)
        return create(name)?.takeIf { configureAndStart(it, name, format, surface) }
    }

    /**
     * Asks [decoder] for low-latency output (frames leave as soon as they are
     * decoded, no reordering delay) when it lists the feature, and makes sure
     * the key is absent otherwise. Returns whether the key is set.
     */
    @RequiresApi(Build.VERSION_CODES.R)
    private fun requestLowLatency(
        decoder: MediaCodec,
        format: MediaFormat,
    ): Boolean {
        val mime = format.getString(MediaFormat.KEY_MIME)
        val supported =
            mime != null &&
                try {
                    decoder.codecInfo
                        .getCapabilitiesForType(mime)
                        .isFeatureSupported(MediaCodecInfo.CodecCapabilities.FEATURE_LowLatency)
                } catch (e: IllegalArgumentException) {
                    Log.w(TAG, "no capabilities for $mime", e)
                    false
                }
        if (supported) {
            format.setInteger(
                MediaFormat.KEY_LOW_LATENCY,
                1,
            )
        } else {
            format.removeKey(MediaFormat.KEY_LOW_LATENCY)
        }
        return supported
    }

    /** A codec instance by name, or null when the platform refuses to create it. */
    fun create(name: String): MediaCodec? =
        try {
            MediaCodec.createByCodecName(name)
        } catch (e: IOException) {
            Log.w(TAG, "cannot create $name", e)
            null
        } catch (e: IllegalArgumentException) {
            Log.w(TAG, "cannot create $name", e)
            null
        }

    private fun configureAndStart(
        decoder: MediaCodec,
        name: String,
        format: MediaFormat,
        surface: Surface,
    ): Boolean =
        try {
            decoder.configure(format, surface, null, 0)
            decoder.setVideoScalingMode(MediaCodec.VIDEO_SCALING_MODE_SCALE_TO_FIT)
            decoder.start()
            // Logged for device checks (docs/INTEROP.md): which decoder, and whether low latency was asked for.
            val lowLatency =
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && format.containsKey(MediaFormat.KEY_LOW_LATENCY)
            Log.i(TAG, "decoding with $name, low latency ${if (lowLatency) "requested" else "not requested"}")
            true
        } catch (e: IllegalStateException) {
            Log.w(TAG, "$name rejected the stream", e)
            decoder.release()
            false
        } catch (e: IllegalArgumentException) {
            Log.w(TAG, "$name rejected the stream", e)
            decoder.release()
            false
        }

    /** Decoder names for [mime], hardware first (they render through the display overlay). */
    fun candidates(mime: String): List<String> {
        val infos =
            MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos.filter { info ->
                !info.isEncoder && info.supportedTypes.any { it.equals(mime, ignoreCase = true) }
            }
        return infos.sortedBy { if (isHardware(it)) 0 else 1 }.map { it.name }
    }

    private fun isHardware(info: MediaCodecInfo): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) return info.isHardwareAccelerated && !info.isSoftwareOnly
        val name = info.name.lowercase()
        return !name.startsWith("omx.google.") && !name.startsWith("c2.android.") && !name.contains(".sw.")
    }
}
