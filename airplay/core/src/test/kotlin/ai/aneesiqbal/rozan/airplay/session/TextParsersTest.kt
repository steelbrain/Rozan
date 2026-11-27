package ai.aneesiqbal.rozan.airplay.session

import java.io.ByteArrayOutputStream
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNull

class TextParsersTest {
    @Test
    fun `text parameters`() {
        val lines = TextParameters.lines("volume: -11.250000\r\nprogress: 1/2/3\r\n\r\n".toByteArray())
        assertEquals(listOf("volume: -11.250000", "progress: 1/2/3"), lines)
        assertEquals("-11.250000", TextParameters.value(lines, "Volume"))
        assertNull(TextParameters.value(lines, "vol"))
        assertEquals(-11.25f, TextParameters.volume("-11.250000"))
        assertNull(TextParameters.volume("NaN"))
        assertNull(TextParameters.volume("Infinity"))
        assertNull(TextParameters.volume("loud"))
        assertContentEquals(longArrayOf(1, 2, 3), TextParameters.progress("1/2/3"))
        assertContentEquals(longArrayOf(0, 4294967295, 7), TextParameters.progress(" 0 / 4294967295 /7"))
        assertNull(TextParameters.progress("1/2"))
        assertNull(TextParameters.progress("1/2/4294967296"))
        assertNull(TextParameters.progress("1/-2/3"))
        assertNull(TextParameters.progress("1/2/3/4"))
    }

    @Test
    fun `text parameter bodies are bounded`() {
        val many = (1..100).joinToString("\r\n") { "x$it: $it" }.toByteArray()
        assertEquals(TextParameters.MAX_LINES, TextParameters.lines(many).size)
        val long = ("volume: " + "1".repeat(500)).toByteArray()
        assertEquals(emptyList(), TextParameters.lines(long))
        assertEquals(emptyList(), TextParameters.lines(ByteArray(0)))
        TextParameters.lines(Random(1).nextBytes(4096))
    }

    @Test
    fun `RTP-Info fields are bounded and independent`() {
        val info = TextParameters.rtpInfo("seq=24561;rtptime=1844137298")
        assertEquals(24561, info.seq)
        assertEquals(1844137298L, info.rtpTime)
        assertNull(TextParameters.rtpInfo(null).seq)
        assertEquals(65535, TextParameters.rtpInfo("seq=65535").seq)
        assertNull(TextParameters.rtpInfo("seq=65536;rtptime=1").seq)
        assertEquals(1L, TextParameters.rtpInfo("seq=65536;rtptime=1").rtpTime)
        assertNull(TextParameters.rtpInfo("rtptime=4294967296").rtpTime)
        assertEquals(4294967295L, TextParameters.rtpInfo("rtptime=4294967295").rtpTime)
        assertNull(TextParameters.rtpInfo("seq=00000000001").seq)
        assertNull(TextParameters.rtpInfo("seq=-1").seq)
        assertNull(TextParameters.rtpInfo("seq=1e3").seq)
        assertNull(TextParameters.rtpInfo("garbage").seq)
        assertEquals(7, TextParameters.rtpInfo(" seq = 7 , rtptime=8").seq)
    }

    private fun item(
        tag: String,
        value: ByteArray,
    ): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(tag.toByteArray())
        out.write(
            java.nio.ByteBuffer
                .allocate(4)
                .putInt(value.size)
                .array(),
        )
        out.write(value)
        return out.toByteArray()
    }

    @Test
    fun `DMAP metadata from an mlit container`() {
        val body =
            item(
                "mlit",
                item("mikd", byteArrayOf(2)) +
                    item("minm", "Song – Ünïcode".toByteArray()) +
                    item("asar", "Artist".toByteArray()) +
                    item("asal", "Album".toByteArray()) +
                    item("astm", byteArrayOf(0, 0, 1, 0)),
            )
        val track = Dmap.parse(body)!!
        assertEquals("Song – Ünïcode", track.title)
        assertEquals("Artist", track.artist)
        assertEquals("Album", track.album)
        val flat = Dmap.parse(item("minm", "T".toByteArray()))!!
        assertEquals("T", flat.title)
        assertNull(flat.artist)
    }

    @Test
    fun `DMAP strings are cleaned and cut`() {
        val track = Dmap.parse(item("minm", ("a\u0000b\u001Bc" + "x".repeat(1000)).toByteArray()))!!
        assertEquals("abc" + "x".repeat(Dmap.MAX_TEXT_CHARS - 3), track.title)
        assertNull(Dmap.parse(item("minm", "  ".toByteArray()))!!.title)
    }

    @Test
    fun `malformed DMAP is rejected`() {
        val good = item("mlit", item("minm", "T".toByteArray()))
        for (cut in 1 until good.size) assertNull(Dmap.parse(good.copyOf(cut)), "cut $cut")
        val lying = good.copyOf().also { it[7] = 0x7F }
        assertNull(Dmap.parse(lying))
        val negative = item("minm", ByteArray(0)).also { it[4] = 0x80.toByte() }
        assertNull(Dmap.parse(negative))
        var deep = item("minm", "T".toByteArray())
        repeat(Dmap.MAX_DEPTH) { deep = item("mlit", deep) }
        assertNull(Dmap.parse(deep))
        assertNull(Dmap.parse(ByteArray(Dmap.MAX_BODY_BYTES + 1)))
        val manyItems = ByteArrayOutputStream()
        repeat(Dmap.MAX_ITEMS + 1) { manyItems.write(item("mikd", ByteArray(0))) }
        assertNull(Dmap.parse(manyItems.toByteArray()))
        val random = Random(7)
        repeat(500) { Dmap.parse(random.nextBytes(random.nextInt(64))) }
    }

    @Test
    fun `client text is cleaned`() {
        assertEquals("Anna's iPad", ClientText.clean("  Anna's\n iPad\u0007 "))
        assertNull(ClientText.clean(null))
        assertNull(ClientText.clean("\u0000\u0001"))
        assertEquals(64, ClientText.clean("y".repeat(200))!!.length)
        // A surrogate pair is never split.
        assertEquals("x".repeat(63), ClientText.clean("x".repeat(63) + "😀"))
    }
}
