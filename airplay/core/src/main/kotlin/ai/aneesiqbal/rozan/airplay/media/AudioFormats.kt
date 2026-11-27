package ai.aneesiqbal.rozan.airplay.media

import ai.aneesiqbal.rozan.airplay.receiver.AudioFormat

/** Decoder configurations for the type-96 codecs (03c §4.8, 03d §H). */
internal object AudioFormats {
    /** `ct` of Apple Lossless. */
    const val CT_ALAC: Int = 2

    /** `ct` of AAC-ELD. */
    const val CT_AAC_ELD: Int = 8

    /** Channel count of every AirPlay realtime stream (`audioFormat` 0x40000 and the ELD config). */
    const val CHANNELS: Int = 2

    /** Frames per AAC-ELD access unit. */
    const val ELD_FRAMES_PER_PACKET: Int = 480

    private const val ALAC_COOKIE_BYTES = 24
    private const val ALAC_BIT_DEPTH = 16
    private const val ALAC_PB = 40
    private const val ALAC_MB = 10
    private const val ALAC_KB = 14
    private const val ALAC_MAX_RUN = 255

    /**
     * The 24-byte `ALACSpecificConfig` for a stream of [framesPerPacket] frames per packet at
     * [sampleRate]: `frameLength, 0, 16, 40, 10, 14, 2, 255, 0, 0, sampleRate`, big-endian (03c §4.8.1).
     */
    fun alacCookie(
        framesPerPacket: Int,
        sampleRate: Int,
    ): ByteArray {
        val cookie = ByteArray(ALAC_COOKIE_BYTES)
        putU32Be(cookie, 0, framesPerPacket)
        cookie[5] = ALAC_BIT_DEPTH.toByte()
        cookie[6] = ALAC_PB.toByte()
        cookie[7] = ALAC_MB.toByte()
        cookie[8] = ALAC_KB.toByte()
        cookie[9] = CHANNELS.toByte()
        cookie.putU16Be(10, ALAC_MAX_RUN)
        // maxFrameBytes and avgBitRate (offsets 12 and 16) are 0: unknown.
        putU32Be(cookie, 20, sampleRate)
        return cookie
    }

    /** The AudioSpecificConfig `F8 E8 50 00`: AOT 39 (ER AAC ELD), 44.1 kHz, 2 channels, 480 frames. */
    fun aacEldConfig(): ByteArray = byteArrayOf(0xF8.toByte(), 0xE8.toByte(), 0x50, 0x00)

    /** The sink format for [request], or null when its codec is not supported. */
    fun formatFor(request: AudioStreamRequest): AudioFormat? =
        when (request.compressionType) {
            CT_ALAC -> {
                AudioFormat.Alac(
                    sampleRate = request.sampleRate,
                    channels = CHANNELS,
                    framesPerPacket = request.framesPerPacket,
                    magicCookie = alacCookie(request.framesPerPacket, request.sampleRate),
                )
            }

            CT_AAC_ELD -> {
                AudioFormat.Aac(
                    sampleRate = request.sampleRate,
                    channels = CHANNELS,
                    framesPerPacket = request.framesPerPacket,
                    eld = true,
                    audioSpecificConfig = aacEldConfig(),
                )
            }

            else -> {
                null
            }
        }

    /**
     * Whether [firstByte] is a plausible first byte of a decrypted payload of codec [compressionType]
     * (03c §4.2): `0x20` for ALAC, `0x8C`–`0x8E` or `0x80`–`0x82` for AAC-ELD. A mismatch usually means a
     * wrong key.
     */
    fun isPlausibleFirstByte(
        compressionType: Int,
        firstByte: Int,
    ): Boolean =
        when (compressionType) {
            CT_ALAC -> firstByte == ALAC_FIRST_BYTE
            CT_AAC_ELD -> firstByte in ELD_CURRENT_FIRST..ELD_CURRENT_LAST || firstByte in ELD_OLD_FIRST..ELD_OLD_LAST
            else -> true
        }

    private fun putU32Be(
        out: ByteArray,
        offset: Int,
        value: Int,
    ) {
        out.putU16Be(offset, value ushr 16)
        out.putU16Be(offset + 2, value)
    }

    private const val ALAC_FIRST_BYTE = 0x20
    private const val ELD_CURRENT_FIRST = 0x8C
    private const val ELD_CURRENT_LAST = 0x8E
    private const val ELD_OLD_FIRST = 0x80
    private const val ELD_OLD_LAST = 0x82
}
