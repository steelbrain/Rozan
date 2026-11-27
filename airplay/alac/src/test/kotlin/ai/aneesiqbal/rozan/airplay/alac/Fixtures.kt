package ai.aneesiqbal.rozan.airplay.alac

import java.io.ByteArrayOutputStream
import java.util.Locale
import kotlin.test.assertEquals
import kotlin.test.fail

/** Test fixtures from src/test/resources/fixtures (see scripts/generate-test-fixtures.sh). */
object Fixtures {
    /** 16-bit fixtures: CAF name to the raw s16le file with the same samples. */
    val PCM16: Map<String, String> =
        linkedMapOf(
            "sweep-stereo16" to "sweep-stereo16",
            "sweep-mono16" to "sweep-mono16",
            "noise-stereo16" to "noise-stereo16",
            "noise-stereo16-verbatim" to "noise-stereo16",
            "silence-mono16" to "silence-mono16",
            "silence-stereo16" to "silence-stereo16",
            "square-mono16" to "square-mono16",
            "square-stereo16" to "square-stereo16",
        )

    /** 24-bit fixtures: CAF name to the raw s24le file with the same samples. */
    val PCM24: Map<String, String> =
        linkedMapOf(
            "sweep-stereo24" to "sweep-stereo24",
            "sweep-stereo24-verbatim" to "sweep-stereo24",
            "noise-mono24" to "noise-mono24",
        )

    /** The AirPlay-shaped fixture: this many single-packet CAF files of 352 stereo frames. */
    const val AIRPLAY_PACKETS: Int = 16
    const val AIRPLAY_FMTP: String = "96 352 0 16 40 10 14 2 255 0 0 44100"

    fun bytes(name: String): ByteArray {
        val stream = Fixtures::class.java.getResourceAsStream("/fixtures/$name") ?: error("missing fixture $name")
        return stream.use { it.readBytes() }
    }

    fun caf(name: String): CafFile = CafFile.parse(bytes("$name.caf"))

    fun airplayPacket(index: Int): ByteArray =
        caf(String.format(Locale.ROOT, "airplay352-%02d", index)).packets.single()

    /** Every packet of every CAF fixture, with the config it was encoded with. */
    fun allPackets(): List<Pair<AlacConfig, ByteArray>> {
        val result = ArrayList<Pair<AlacConfig, ByteArray>>()
        for (name in PCM16.keys + PCM24.keys) {
            val caf = caf(name)
            val config = AlacConfig.fromMagicCookie(caf.cookie)
            caf.packets.forEach { result += config to it }
        }
        val airplay = AlacConfig.fromFmtp(AIRPLAY_FMTP)
        for (i in 0 until AIRPLAY_PACKETS) result += airplay to airplayPacket(i)
        return result
    }

    /** Decodes every packet of [caf] through the ShortArray API into s16le bytes. */
    fun decodeShorts(
        caf: CafFile,
        decoder: AlacDecoder,
    ): ByteArray {
        val out = ShortArray(decoder.minOutputSize)
        val pcm = ByteArrayOutputStream()
        caf.packets.forEachIndexed { index, packet ->
            val frames = decoder.decode(packet, 0, packet.size, out)
            assertEquals(caf.framesInPacket(index), frames, "frames in packet $index")
            for (i in 0 until frames * caf.channels) writeLittleEndian(pcm, out[i].toInt(), 2)
        }
        return trim(pcm.toByteArray(), caf, 2)
    }

    /** Decodes every packet of [caf] through the IntArray API into little-endian bytes of [bytesPerSample]. */
    fun decodeInts(
        caf: CafFile,
        decoder: AlacDecoder,
        bytesPerSample: Int,
    ): ByteArray {
        val out = IntArray(decoder.minOutputSize)
        val pcm = ByteArrayOutputStream()
        caf.packets.forEachIndexed { index, packet ->
            val frames = decoder.decode(packet, 0, packet.size, out)
            assertEquals(caf.framesInPacket(index), frames, "frames in packet $index")
            for (i in 0 until frames * caf.channels) writeLittleEndian(pcm, out[i], bytesPerSample)
        }
        return trim(pcm.toByteArray(), caf, bytesPerSample)
    }

    /** Fails with the first differing frame and channel unless [actual] equals [expected]. */
    fun assertPcmEquals(
        expected: ByteArray,
        actual: ByteArray,
        channels: Int,
        bytesPerSample: Int,
    ) {
        val sampleCount = minOf(expected.size, actual.size) / bytesPerSample
        for (s in 0 until sampleCount) {
            val e = readLittleEndian(expected, s * bytesPerSample, bytesPerSample)
            val a = readLittleEndian(actual, s * bytesPerSample, bytesPerSample)
            if (e != a) fail("frame ${s / channels} channel ${s % channels}: expected $e, decoded $a")
        }
        assertEquals(expected.size, actual.size, "PCM length")
    }

    private fun trim(
        pcm: ByteArray,
        caf: CafFile,
        bytesPerSample: Int,
    ): ByteArray {
        val frameBytes = caf.channels * bytesPerSample
        val start = caf.primingFrames * frameBytes
        return pcm.copyOfRange(start, start + (caf.validFrames * frameBytes).toInt())
    }

    private fun writeLittleEndian(
        out: ByteArrayOutputStream,
        value: Int,
        bytes: Int,
    ) {
        for (i in 0 until bytes) out.write(value ushr (8 * i))
    }

    /** Sign-extended little-endian integer of [bytes] bytes. */
    private fun readLittleEndian(
        data: ByteArray,
        offset: Int,
        bytes: Int,
    ): Int {
        var value = 0
        for (i in bytes - 1 downTo 0) value = (value shl 8) or (data[offset + i].toInt() and 0xff)
        val extend = 32 - 8 * bytes
        return (value shl extend) shr extend
    }
}
