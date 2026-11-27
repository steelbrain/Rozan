package ai.aneesiqbal.rozan.airplay.session

import ai.aneesiqbal.rozan.airplay.media.AudioStreamRequest
import ai.aneesiqbal.rozan.airplay.plist.BinaryPlist
import ai.aneesiqbal.rozan.airplay.plist.PlistArray
import ai.aneesiqbal.rozan.airplay.plist.PlistData
import ai.aneesiqbal.rozan.airplay.plist.PlistDict
import ai.aneesiqbal.rozan.airplay.plist.PlistException
import ai.aneesiqbal.rozan.airplay.plist.PlistInteger
import ai.aneesiqbal.rozan.airplay.plist.PlistLimits
import ai.aneesiqbal.rozan.airplay.plist.PlistValue
import ai.aneesiqbal.rozan.airplay.rtsp.RtspException

/**
 * The session part of a SETUP body (docs/protocol/03-media-streams.md §1.2). Sizes of [ekey] and [eiv]
 * are checked after access control (01 §6.8 step 2), so they are not checked here.
 *
 * @property timingPort the sender's NTP port, or 0 when absent, out of range, or not NTP timing.
 * @property deviceId `deviceID`, untrusted.
 * @property name `name`, untrusted.
 * @property model `model`, untrusted.
 */
internal class SessionSetup(
    val ekey: ByteArray,
    val eiv: ByteArray,
    val timingPort: Int,
    val deviceId: String?,
    val name: String?,
    val model: String?,
    val screenMirroring: Boolean,
)

/** One element of a SETUP `streams` array (03 §1.3). */
internal sealed interface StreamSetup {
    /** Type 110 with its raw 64-bit `streamConnectionID` (unsigned semantics). */
    class Mirroring(
        val streamConnectionId: Long,
    ) : StreamSetup

    /** Type 96, validated. */
    class Audio(
        val request: AudioStreamRequest,
    ) : StreamSetup

    /** Any other type: left out of the response (03 §1.3). */
    class Unsupported(
        val type: Long,
    ) : StreamSetup
}

/**
 * A parsed and validated SETUP body (03 §1.1, §8.1).
 *
 * @property session the session part, when the body has both `ekey` and `eiv` as data.
 * @property streams the `streams` elements in request order, or null when the body has none.
 */
internal class SetupRequest(
    val session: SessionSetup?,
    val streams: List<StreamSetup>?,
) {
    companion object {
        /** Most elements in `streams` (03 §8.1). */
        const val MAX_STREAMS = 4

        /** Stream type of screen mirroring video. */
        const val TYPE_MIRRORING = 110L

        /** Stream type of realtime audio. */
        const val TYPE_AUDIO = 96L

        /** ALAC, the audio-only codec. */
        const val CT_ALAC = 2

        /** AAC-ELD, the mirroring audio codec. */
        const val CT_AAC_ELD = 8

        /** ALAC packets carry 352 frames unless `spf` says otherwise. */
        const val ALAC_DEFAULT_SPF = 352

        /** AAC-ELD packets always carry 480 frames. */
        const val AAC_ELD_SPF = 480

        /** Largest ALAC `spf` (03 §1.3.2). */
        const val MAX_ALAC_SPF = 4096

        /** The only sample rate accepted. */
        const val SAMPLE_RATE = 44_100

        /** `latencyMin`/`latencyMax` clamp, 4 s of frames (03 §8.1). */
        const val MAX_LATENCY_FRAMES = 176_400

        private const val MAX_PORT = 65_535

        /** SETUP bodies hold a few dozen values; this leaves ample room and bounds the tree. */
        private val LIMITS = PlistLimits(maxObjects = 2_000, maxDepth = 16)

        /**
         * Parses [body].
         *
         * @throws RtspException (400) for an empty or unparsable body, a body that is not a dictionary,
         *   a body with neither part, or a `streams` element that breaks 03 §1.3 / §8.1.
         */
        fun parse(body: ByteArray): SetupRequest {
            val root = decode(body) as? PlistDict ?: bad("SETUP body is not a dictionary")
            val ekey = root.entries["ekey"] as? PlistData
            val eiv = root.entries["eiv"] as? PlistData
            val session = if (ekey != null && eiv != null) session(root, ekey, eiv) else null
            val streamsValue = root.entries["streams"]
            val streams =
                when (streamsValue) {
                    null -> null
                    is PlistArray -> streams(streamsValue)
                    else -> bad("SETUP streams is not an array")
                }
            if (session == null && streams == null) bad("SETUP has neither ekey/eiv nor streams")
            return SetupRequest(session, streams)
        }

        private fun decode(body: ByteArray): PlistValue {
            if (body.isEmpty()) bad("SETUP without a body")
            return try {
                BinaryPlist.decode(body, LIMITS)
            } catch (e: PlistException) {
                throw RtspException("SETUP body is not a valid binary plist", cause = e)
            }
        }

        private fun session(
            root: PlistDict,
            ekey: PlistData,
            eiv: PlistData,
        ): SessionSetup {
            val protocol = root.string("timingProtocol")
            // PTP or anything else but NTP: continue without timing (03 §1.2).
            val timingPort = if (protocol == null || protocol == "NTP") port(root.long("timingPort")) else 0
            return SessionSetup(
                ekey = ekey.toByteArray(),
                eiv = eiv.toByteArray(),
                timingPort = timingPort,
                deviceId = root.string("deviceID"),
                name = root.string("name"),
                model = root.string("model"),
                screenMirroring = root.bool("isScreenMirroringSession") ?: false,
            )
        }

        private fun streams(array: PlistArray): List<StreamSetup> {
            if (array.size > MAX_STREAMS) bad("SETUP has more than $MAX_STREAMS streams")
            val seen = HashSet<Long>()
            return array.items.map { item ->
                val stream = item as? PlistDict ?: bad("SETUP stream is not a dictionary")
                val type = stream.long("type") ?: bad("SETUP stream without an integer type")
                val known = type == TYPE_MIRRORING || type == TYPE_AUDIO
                if (known && !seen.add(type)) bad("SETUP lists stream type $type twice")
                when (type) {
                    TYPE_MIRRORING -> mirroring(stream)
                    TYPE_AUDIO -> StreamSetup.Audio(audio(stream))
                    else -> StreamSetup.Unsupported(type)
                }
            }
        }

        private fun mirroring(stream: PlistDict): StreamSetup.Mirroring {
            // The raw low 64 bits, whether it arrived as a negative 8-byte or a 16-byte integer (03 §1.3.1).
            val id = stream.entries["streamConnectionID"] as? PlistInteger ?: bad("type 110 without streamConnectionID")
            return StreamSetup.Mirroring(id.value)
        }

        private fun audio(stream: PlistDict): AudioStreamRequest {
            val ct = stream.long("ct") ?: bad("type 96 without ct")
            val framesPerPacket = framesPerPacket(ct, stream.long("spf"))
            val sampleRate = stream.long("sr")
            if (sampleRate != null && sampleRate != SAMPLE_RATE.toLong()) bad("unsupported audio sample rate")
            return AudioStreamRequest(
                compressionType = ct.toInt(),
                framesPerPacket = framesPerPacket,
                sampleRate = SAMPLE_RATE,
                clientControlPort = port(stream.long("controlPort")),
                latencyMinFrames = latency(stream.long("latencyMin")),
                latencyMaxFrames = latency(stream.long("latencyMax")),
                usingScreen = stream.bool("usingScreen") ?: false,
            )
        }

        /** `spf` per 03 §1.3.2: 480 for AAC-ELD, 1..4096 for ALAC; an absent value takes the codec's default. */
        private fun framesPerPacket(
            ct: Long,
            spf: Long?,
        ): Int =
            when (ct) {
                CT_AAC_ELD.toLong() -> {
                    if (spf == null || spf == AAC_ELD_SPF.toLong()) AAC_ELD_SPF else bad("AAC-ELD spf must be 480")
                }

                CT_ALAC.toLong() -> {
                    when {
                        spf == null -> ALAC_DEFAULT_SPF
                        spf in 1..MAX_ALAC_SPF -> spf.toInt()
                        else -> bad("ALAC spf out of range")
                    }
                }

                else -> {
                    bad("unsupported audio compression type")
                }
            }

        private fun port(value: Long?): Int = value?.takeIf { it in 1..MAX_PORT }?.toInt() ?: 0

        private fun latency(value: Long?): Int? = value?.coerceIn(0L, MAX_LATENCY_FRAMES.toLong())?.toInt()

        private fun bad(message: String): Nothing = throw RtspException(message)
    }
}
