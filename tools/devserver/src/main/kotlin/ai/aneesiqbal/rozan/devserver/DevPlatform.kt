package ai.aneesiqbal.rozan.devserver

import ai.aneesiqbal.rozan.airplay.receiver.AudioFormat
import ai.aneesiqbal.rozan.airplay.receiver.AudioSink
import ai.aneesiqbal.rozan.airplay.receiver.ClientInfo
import ai.aneesiqbal.rozan.airplay.receiver.NowPlaying
import ai.aneesiqbal.rozan.airplay.receiver.PinResult
import ai.aneesiqbal.rozan.airplay.receiver.ReceiverEvents
import ai.aneesiqbal.rozan.airplay.receiver.ReceiverPlatform
import ai.aneesiqbal.rozan.airplay.receiver.ServiceRecord
import ai.aneesiqbal.rozan.airplay.receiver.VideoCodec
import ai.aneesiqbal.rozan.airplay.receiver.VideoSink
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.time.LocalTime
import java.util.concurrent.atomic.AtomicInteger

/**
 * [ReceiverPlatform] that publishes with macOS `dns-sd` (Avahi's `avahi-publish` on Linux) and writes media
 * to files.
 */
internal class DevPlatform(
    private val out: File,
) : ReceiverPlatform {
    private val publishers = mutableListOf<Process>()
    private val streamCounter = AtomicInteger()
    private val avahi = System.getProperty("os.name").startsWith("Linux")

    @Synchronized
    override fun publish(records: List<ServiceRecord>) {
        unpublish()
        for (record in records) {
            log("publish ${record.serviceType} \"${record.instanceName}\" port ${record.port}")
            publishers +=
                ProcessBuilder(publishCommand(record))
                    .redirectErrorStream(true)
                    .redirectOutput(ProcessBuilder.Redirect.appendTo(File(out, "mdns.log")))
                    .start()
        }
    }

    private fun publishCommand(record: ServiceRecord): List<String> {
        val txt = record.txt.map { (key, value) -> "$key=$value" }
        val port = record.port.toString()
        return if (avahi) {
            listOf("avahi-publish", "-s", record.instanceName, record.serviceType, port) + txt
        } else {
            listOf("dns-sd", "-R", record.instanceName, record.serviceType, "local", port) + txt
        }
    }

    @Synchronized
    override fun unpublish() {
        publishers.forEach(Process::destroy)
        publishers.clear()
    }

    override fun createVideoSink(): VideoSink = FileVideoSink(out)

    override fun createAudioSink(format: AudioFormat): AudioSink {
        val index = streamCounter.incrementAndGet()
        log("audio stream $index: ${format::class.simpleName} ${format.sampleRate} Hz x${format.channels}")
        return FileAudioSink(File(out, "audio-$index.${extension(format)}"))
    }

    override val events: ReceiverEvents =
        object : ReceiverEvents {
            override fun onConnectionStarted(client: ClientInfo) = log("connection from ${client.describe()}")

            override fun onPinRequired(
                pin: String,
                client: ClientInfo,
            ) {
                log("PIN for ${client.describe()}:")
                println("\n        ┌──────────┐\n        │   $pin   │\n        └──────────┘\n")
            }

            override fun onPinFinished(
                client: ClientInfo,
                result: PinResult,
            ) = log("PIN prompt finished: $result")

            override fun onMirroringStarted(client: ClientInfo) = log("mirroring started from ${client.describe()}")

            override fun onMirroringStopped(client: ClientInfo) = log("mirroring stopped")

            override fun onAudioStarted(client: ClientInfo) = log("audio started from ${client.describe()}")

            override fun onAudioStopped(client: ClientInfo) = log("audio stopped")

            override fun onNowPlaying(
                client: ClientInfo,
                nowPlaying: NowPlaying?,
            ) = log("now playing: ${nowPlaying?.title} / ${nowPlaying?.artist} / ${nowPlaying?.album}")
        }

    private fun extension(format: AudioFormat): String =
        when (format) {
            is AudioFormat.Alac -> "alac.units"
            is AudioFormat.Aac -> if (format.eld) "aac-eld.units" else "aac.units"
            is AudioFormat.Pcm -> "s16be.pcm"
        }
}

/** Writes Annex-B video; `ffplay video.h264` plays it. */
private class FileVideoSink(
    private val out: File,
) : VideoSink {
    private var stream: BufferedOutputStream? = null
    private var frames = 0L

    override fun configure(
        codec: VideoCodec,
        parameterSets: ByteArray,
        width: Int,
        height: Int,
    ) {
        log("video configure $codec ${width}x$height (${parameterSets.size} bytes of parameter sets)")
        if (stream == null) {
            val name = if (codec == VideoCodec.HEVC) "video.h265" else "video.h264"
            stream = BufferedOutputStream(FileOutputStream(File(out, name)))
        }
        stream?.write(parameterSets)
    }

    override fun submit(
        accessUnit: ByteArray,
        offset: Int,
        length: Int,
        presentationNanos: Long,
    ) {
        stream?.write(accessUnit, offset, length)
        frames++
        if (frames % 300 == 0L) log("video: $frames frames")
    }

    override fun close() {
        log("video closed after $frames frames")
        stream?.close()
        stream = null
    }
}

/** Writes access units as `u32be length | bytes` records. */
private class FileAudioSink(
    file: File,
) : AudioSink {
    private val stream = BufferedOutputStream(FileOutputStream(file))
    private val header = ByteArray(4)
    private var units = 0L

    override fun submit(
        accessUnit: ByteArray,
        offset: Int,
        length: Int,
        presentationNanos: Long,
    ) {
        header[0] = (length ushr 24).toByte()
        header[1] = (length ushr 16).toByte()
        header[2] = (length ushr 8).toByte()
        header[3] = length.toByte()
        stream.write(header)
        stream.write(accessUnit, offset, length)
        units++
    }

    override fun setVolume(linear: Float) = log("volume $linear")

    override fun flush() = log("audio flush")

    override fun close() {
        log("audio closed after $units units")
        stream.close()
    }
}

private fun ClientInfo.describe(): String = listOfNotNull(name, model, address).joinToString(" / ")

private fun log(message: String) = println("${LocalTime.now()} $message")
