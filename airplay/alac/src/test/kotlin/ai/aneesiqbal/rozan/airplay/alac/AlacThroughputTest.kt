package ai.aneesiqbal.rozan.airplay.alac

import org.junit.jupiter.api.Assumptions.assumeTrue
import java.lang.management.ManagementFactory
import java.util.Locale
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Speed and allocation of the decode loop. The throughput numbers are printed for information only; they depend
 * on the machine and nothing fails on them.
 */
class AlacThroughputTest {
    private class Stream(
        val name: String,
        val config: AlacConfig,
        val packets: Array<ByteArray>,
    )

    private val streams =
        listOf(
            Fixtures.caf("sweep-stereo16").let {
                Stream(
                    "16-bit stereo, 4096-frame packets",
                    AlacConfig.fromMagicCookie(it.cookie),
                    it.packets.toTypedArray(),
                )
            },
            Stream(
                "16-bit stereo, AirPlay 352-frame packets",
                AlacConfig.fromFmtp(Fixtures.AIRPLAY_FMTP),
                Array(Fixtures.AIRPLAY_PACKETS) { Fixtures.airplayPacket(it) },
            ),
            Fixtures.caf("sweep-stereo24").let {
                Stream(
                    "24-bit stereo, 4096-frame packets",
                    AlacConfig.fromMagicCookie(it.cookie),
                    it.packets.toTypedArray(),
                )
            },
        )

    @Test
    fun `throughput in decoded seconds per wall-clock second`() {
        for (stream in streams) {
            val decoder = AlacDecoder(stream.config)
            val out = IntArray(decoder.minOutputSize)
            decodeFor(decoder, stream.packets, out, WARM_UP_NANOS)
            val start = System.nanoTime()
            val frames = decodeFor(decoder, stream.packets, out, MEASURE_NANOS)
            val seconds = (System.nanoTime() - start) / 1e9
            val audioSeconds = frames.toDouble() / stream.config.sampleRate
            val rate = String.format(Locale.ROOT, "%.0f", audioSeconds / seconds)
            println("ALAC throughput, ${stream.name}: $rate decoded seconds per second")
        }
    }

    @Test
    fun `decoding a packet does not allocate`() {
        val threads = ManagementFactory.getThreadMXBean()
        assumeTrue(threads is com.sun.management.ThreadMXBean, "needs HotSpot's per-thread allocation counter")
        val counter = threads as com.sun.management.ThreadMXBean
        for (stream in streams) {
            val decoder = AlacDecoder(stream.config)
            val shorts = ShortArray(decoder.minOutputSize)
            val ints = IntArray(decoder.minOutputSize)
            val packets = stream.packets
            repeat(2) { round ->
                val before = counter.currentThreadAllocatedBytes
                for (i in 0 until 2_000) {
                    val packet = packets[i % packets.size]
                    if (stream.config.bitDepth == 16 && i % 2 == 0) {
                        decoder.decode(packet, 0, packet.size, shorts)
                    } else {
                        decoder.decode(packet, 0, packet.size, ints)
                    }
                }
                val allocated = counter.currentThreadAllocatedBytes - before
                // The first round may include class loading and JIT bookkeeping.
                if (round == 1) assertEquals(0L, allocated, "${stream.name}: bytes allocated by 2000 decodes")
            }
        }
    }

    /** Decodes packets round-robin for at least [nanos]; returns the frames decoded. */
    private fun decodeFor(
        decoder: AlacDecoder,
        packets: Array<ByteArray>,
        out: IntArray,
        nanos: Long,
    ): Long {
        val end = System.nanoTime() + nanos
        var frames = 0L
        var i = 0
        while (System.nanoTime() < end) {
            val packet = packets[i]
            frames += decoder.decode(packet, 0, packet.size, out)
            i = (i + 1) % packets.size
        }
        return frames
    }

    private companion object {
        const val WARM_UP_NANOS = 500_000_000L
        const val MEASURE_NANOS = 1_000_000_000L
    }
}
