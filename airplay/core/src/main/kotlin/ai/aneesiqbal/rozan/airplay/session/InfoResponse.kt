package ai.aneesiqbal.rozan.airplay.session

import ai.aneesiqbal.rozan.airplay.AirPlay
import ai.aneesiqbal.rozan.airplay.plist.BinaryPlist
import ai.aneesiqbal.rozan.airplay.plist.PlistArray
import ai.aneesiqbal.rozan.airplay.plist.PlistDict
import ai.aneesiqbal.rozan.airplay.plist.PlistException
import ai.aneesiqbal.rozan.airplay.plist.PlistLimits
import ai.aneesiqbal.rozan.airplay.plist.PlistString
import ai.aneesiqbal.rozan.airplay.plist.buildPlistDict
import ai.aneesiqbal.rozan.airplay.receiver.DisplayInfo
import ai.aneesiqbal.rozan.airplay.rtsp.RtspException
import ai.aneesiqbal.rozan.airplay.rtsp.RtspRequest

/** The two forms of the `GET /info` response (docs/protocol/01-discovery-and-session.md §6.1, §7). */
internal object InfoResponse {
    /** Qualifier naming the `_airplay._tcp` TXT rdata. */
    const val TXT_AIRPLAY = "txtAirPlay"

    /** Qualifier naming the `_raop._tcp` TXT rdata. */
    const val TXT_RAOP = "txtRAOP"

    /** Largest display the receiver offers: H.264 only, so never taller than 1080 (03 §7). */
    const val MAX_WIDTH = 1920

    /** See [MAX_WIDTH]. */
    const val MAX_HEIGHT = 1080

    /** `displays[0].maxFPS` (01 §6.1, 03 §7: 30 keeps weak TVs smooth). */
    const val MAX_FPS = 30

    private const val AUDIO_FORMATS = 0x3FF_FFFC
    private const val DISPLAY_FEATURES = 14
    private const val DEFAULT_REFRESH_HZ = 60
    private val REFRESH_RANGE = 24..240
    private val QUALIFIER_LIMITS = PlistLimits(maxObjects = 64, maxDepth = 4)

    /**
     * The qualifiers asked for, from the body `{"qualifier": [..]}` or the query `?txtAirPlay&txtRAOP`,
     * or null for the full form. Unknown qualifier names are kept and later ignored.
     *
     * @throws RtspException (400) when the body is present but not a qualifier plist.
     */
    fun qualifiers(request: RtspRequest): Set<String>? {
        val query = request.target.substringAfter('?', missingDelimiterValue = "")
        if (request.body.isEmpty()) {
            return if (query.isEmpty()) null else query.split('&').filter { it.isNotEmpty() }.toSet()
        }
        val root =
            try {
                BinaryPlist.decode(request.body, QUALIFIER_LIMITS)
            } catch (e: PlistException) {
                throw RtspException("GET /info body is not a binary plist", cause = e)
            }
        val list = (root as? PlistDict)?.array("qualifier") ?: throw RtspException("GET /info body has no qualifier")
        return list.items.mapNotNull { (it as? PlistString)?.value }.toSet()
    }

    /** The qualifier form: only the requested TXT blobs. */
    fun qualified(
        advertisement: Advertisement,
        qualifiers: Set<String>,
    ): PlistDict =
        buildPlistDict {
            if (TXT_AIRPLAY in qualifiers) put(TXT_AIRPLAY, advertisement.airplayRdata())
            if (TXT_RAOP in qualifiers) put(TXT_RAOP, advertisement.raopRdata())
        }

    /**
     * The full form with every key of 01 §6.1, in the order of the §7 sample.
     *
     * @param initialVolumeDb the current volume in AirPlay dB (-30..0, -144 mute).
     */
    fun full(
        advertisement: Advertisement,
        display: DisplayInfo,
        displayUuid: String,
        initialVolumeDb: Double,
    ): PlistDict =
        buildPlistDict {
            put("deviceID", advertisement.deviceIdText)
            put("macAddress", advertisement.deviceIdText)
            put("pk", advertisement.publicKey)
            put("features", advertisement.features)
            put("name", advertisement.name)
            put("pi", advertisement.pi)
            put("vv", Profile.VV)
            put("statusFlags", Profile.infoStatusFlags(advertisement.mode))
            put("keepAliveLowPower", true)
            put("sourceVersion", AirPlay.SOURCE_VERSION)
            put("keepAliveSendStatsAsBody", true)
            put("model", Profile.MODEL)
            put("initialVolume", initialVolumeDb)
            put("audioLatencies", PlistArray(listOf(audioLatency(100), audioLatency(101))))
            put("audioFormats", PlistArray(listOf(audioFormat(100), audioFormat(101))))
            put("displays", PlistArray(listOf(display(display, displayUuid))))
        }

    /**
     * The size offered to senders: [display] scaled down, keeping its aspect ratio, to fit
     * [MAX_WIDTH] x [MAX_HEIGHT]; 1920x1080 when the display reports nonsense.
     */
    fun offeredSize(display: DisplayInfo): IntArray {
        val width = display.widthPixels
        val height = display.heightPixels
        if (width <= 0 || height <= 0) return intArrayOf(MAX_WIDTH, MAX_HEIGHT)
        val scale = minOf(1.0, MAX_WIDTH.toDouble() / width, MAX_HEIGHT.toDouble() / height)
        return intArrayOf((width * scale).toInt().coerceAtLeast(1), (height * scale).toInt().coerceAtLeast(1))
    }

    private fun audioLatency(type: Int): PlistDict =
        buildPlistDict {
            put("type", type)
            put("audioType", "default")
            put("inputLatencyMicros", 0)
            put("outputLatencyMicros", false)
        }

    private fun audioFormat(type: Int): PlistDict =
        buildPlistDict {
            put("type", type)
            put("audioInputFormats", AUDIO_FORMATS)
            put("audioOutputFormats", AUDIO_FORMATS)
        }

    private fun display(
        display: DisplayInfo,
        uuid: String,
    ): PlistDict {
        val size = offeredSize(display)
        val hz = display.refreshRateHz.takeIf { it in REFRESH_RANGE } ?: DEFAULT_REFRESH_HZ
        return buildPlistDict {
            put("uuid", uuid)
            put("widthPhysical", 0)
            put("heightPhysical", 0)
            put("width", size[0])
            put("height", size[1])
            put("widthPixels", size[0])
            put("heightPixels", size[1])
            put("rotation", false)
            put("refreshRate", 1.0 / hz)
            put("maxFPS", minOf(MAX_FPS, hz))
            put("overscanned", false)
            put("features", DISPLAY_FEATURES)
        }
    }
}
