package ai.aneesiqbal.rozan.airplay.android.audio

import ai.aneesiqbal.rozan.airplay.receiver.AudioFormat
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaFormat
import android.util.Log
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Signed 16-bit big-endian interleaved PCM, as sent by the client. */
internal class AudioTrackPcmSink(
    format: AudioFormat.Pcm,
    contentType: DecodingAudioSink.ContentType,
) : DecodingAudioSink(format.sampleRate, format.channels, contentType, "airplay-audio-pcm") {
    private val pcm = ShortArray(MAX_UNIT_BYTES / 2)

    override fun decode(
        unit: ByteArray,
        length: Int,
        presentationNanos: Long,
    ) {
        val samples = length / 2
        for (i in 0 until samples) {
            val hi = unit[2 * i].toInt() shl 8
            val lo = unit[2 * i + 1].toInt() and 0xFF
            pcm[i] = (hi or lo).toShort()
        }
        render(pcm, samples / outputChannels, presentationNanos)
    }
}

/**
 * Apple Lossless through `:airplay:alac`. The decoder's configuration (from
 * the magic cookie) decides the output rate and channel count.
 */
internal class AlacAudioSink private constructor(
    private val decoder: AlacPacketDecoder,
    fallbackRate: Int,
    contentType: DecodingAudioSink.ContentType,
) : DecodingAudioSink(
        if (decoder.sampleRate > 0) decoder.sampleRate else fallbackRate,
        decoder.channels,
        contentType,
        "airplay-audio-alac",
    ) {
    /**
     * Sink for [format]; throws if the magic cookie is invalid (the stream
     * cannot be decoded at all).
     */
    constructor(
        format: AudioFormat.Alac,
        contentType: DecodingAudioSink.ContentType,
        decoderFactory: AlacDecoderFactory = AlacDecoders,
    ) : this(decoderFactory.create(format.magicCookie), format.sampleRate, contentType)

    private val pcm = ShortArray(decoder.minOutputSize)

    override fun decode(
        unit: ByteArray,
        length: Int,
        presentationNanos: Long,
    ) {
        val frames = decoder.decode(unit, 0, length, pcm)
        render(pcm, frames.coerceAtMost(pcm.size / outputChannels), presentationNanos)
    }
}

/**
 * AAC-LC and AAC-ELD through the platform decoder (`audio/mp4a-latm`) with
 * the stream's AudioSpecificConfig as `csd-0`. Mirroring sessions send
 * AAC-ELD; a decoder that lists the ELD profile is preferred for those.
 */
internal class AacAudioSink(
    private val format: AudioFormat.Aac,
    contentType: DecodingAudioSink.ContentType,
) : DecodingAudioSink(format.sampleRate, format.channels, contentType, "airplay-audio-aac") {
    private var codec: MediaCodec? = null
    private var codecFailed = false
    private val info = MediaCodec.BufferInfo()

    // HE-AAC doubles the frame count; 2048 frames of up to 8 channels covers every AirPlay stream.
    private val pcm = ShortArray(MAX_OUTPUT_FRAMES * MAX_OUTPUT_CHANNELS)

    override fun decode(
        unit: ByteArray,
        length: Int,
        presentationNanos: Long,
    ) {
        val decoder = codec ?: startCodec() ?: return
        var attempts = 0
        var queued = false
        while (!queued && attempts < MAX_INPUT_ATTEMPTS) {
            val index = decoder.dequeueInputBuffer(INPUT_TIMEOUT_US)
            if (index >= 0) {
                val buffer = decoder.getInputBuffer(index)
                if (buffer != null && length <= buffer.capacity()) {
                    buffer.clear()
                    buffer.put(unit, 0, length)
                    decoder.queueInputBuffer(index, 0, length, presentationNanos / NANOS_PER_MICRO, 0)
                } else {
                    decoder.queueInputBuffer(index, 0, 0, 0L, 0)
                }
                queued = true
            }
            drain(decoder)
            attempts++
        }
    }

    private fun drain(decoder: MediaCodec) {
        var index = decoder.dequeueOutputBuffer(info, 0L)
        while (index >= 0 || index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
            if (index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                val output = decoder.outputFormat
                changeOutputFormat(
                    output.getInteger(MediaFormat.KEY_SAMPLE_RATE),
                    output.getInteger(MediaFormat.KEY_CHANNEL_COUNT),
                )
            } else {
                val buffer = decoder.getOutputBuffer(index)
                val samples = if (buffer == null) 0 else copyPcm(buffer)
                decoder.releaseOutputBuffer(index, false)
                render(pcm, samples / outputChannels, info.presentationTimeUs * NANOS_PER_MICRO)
            }
            index = decoder.dequeueOutputBuffer(info, 0L)
        }
    }

    private fun copyPcm(buffer: ByteBuffer): Int {
        buffer.order(ByteOrder.nativeOrder())
        val samples = (info.size / 2).coerceAtMost(pcm.size)
        val base = info.offset
        for (i in 0 until samples) pcm[i] = buffer.getShort(base + 2 * i)
        return samples
    }

    private fun startCodec(): MediaCodec? {
        if (codecFailed) return null
        val mediaFormat =
            MediaFormat.createAudioFormat(
                MediaFormat.MIMETYPE_AUDIO_AAC,
                format.sampleRate,
                format.channels,
            )
        mediaFormat.setByteBuffer("csd-0", ByteBuffer.wrap(format.audioSpecificConfig.copyOf()))
        mediaFormat.setInteger(MediaFormat.KEY_IS_ADTS, 0)
        for (name in candidates(format.eld)) {
            val decoder =
                try {
                    MediaCodec.createByCodecName(name)
                } catch (e: IOException) {
                    Log.w(TAG, "cannot create $name", e)
                    null
                } ?: continue
            try {
                decoder.configure(mediaFormat, null, null, 0)
                decoder.start()
                codec = decoder
                return decoder
            } catch (e: IllegalStateException) {
                Log.w(TAG, "$name rejected the AAC configuration", e)
                decoder.release()
            } catch (e: IllegalArgumentException) {
                Log.w(TAG, "$name rejected the AAC configuration", e)
                decoder.release()
            }
        }
        Log.e(TAG, "no AAC decoder accepts this stream (eld=${format.eld})")
        codecFailed = true
        return null
    }

    override fun onFlush() {
        try {
            codec?.flush()
        } catch (e: IllegalStateException) {
            Log.w(TAG, "AAC decoder flush failed; recreating it", e)
            onRelease()
        }
    }

    override fun onRelease() {
        val decoder = codec ?: return
        codec = null
        try {
            decoder.stop()
        } catch (ignored: IllegalStateException) {
            // Already in the error state; release() still frees it.
        }
        decoder.release()
    }

    companion object {
        private const val TAG = "AirPlayAudio"
        private const val NANOS_PER_MICRO = 1_000L
        private const val INPUT_TIMEOUT_US = 5_000L
        private const val MAX_INPUT_ATTEMPTS = 20
        private const val MAX_OUTPUT_FRAMES = 2048
        private const val MAX_OUTPUT_CHANNELS = 8

        /** AAC decoder names; for ELD the ones that list the ELD profile come first. */
        fun candidates(eld: Boolean): List<String> {
            val mime = MediaFormat.MIMETYPE_AUDIO_AAC
            val infos =
                MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos.filter { info ->
                    !info.isEncoder && info.supportedTypes.any { it.equals(mime, ignoreCase = true) }
                }
            if (!eld) return infos.map { it.name }
            return infos
                .sortedBy { info ->
                    val profiles = info.getCapabilitiesForType(mime).profileLevels
                    if (profiles.any { it.profile == MediaCodecInfo.CodecProfileLevel.AACObjectELD }) 0 else 1
                }.map { it.name }
        }
    }
}
