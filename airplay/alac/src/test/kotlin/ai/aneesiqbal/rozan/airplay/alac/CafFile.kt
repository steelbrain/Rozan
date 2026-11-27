package ai.aneesiqbal.rozan.airplay.alac

import java.nio.ByteBuffer

/**
 * Minimal reader for Core Audio Format files, enough to feed ALAC test fixtures to the decoder: the `desc`, `kuki`,
 * `pakt` and `data` chunks. Test-only; it trusts its input.
 */
class CafFile(
    val sampleRate: Double,
    val formatId: String,
    val framesPerPacket: Int,
    val channels: Int,
    val cookie: ByteArray,
    val packets: List<ByteArray>,
    val validFrames: Long,
    val primingFrames: Int,
    val remainderFrames: Int,
) {
    /** Frames decoded from packet [index]: full packets, then whatever is left in the last one. */
    fun framesInPacket(index: Int): Int {
        val total = primingFrames + validFrames
        val before = index.toLong() * framesPerPacket
        return minOf(framesPerPacket.toLong(), total - before).toInt()
    }

    companion object {
        private const val CHUNK_HEADER_BYTES = 12

        fun parse(bytes: ByteArray): CafFile {
            val buf = ByteBuffer.wrap(bytes)
            require(fourCc(buf) == "caff") { "not a CAF file" }
            require(buf.short.toInt() == 1) { "unsupported CAF version" }
            buf.short // flags

            var desc: Desc? = null
            var cookie = ByteArray(0)
            var pakt: ByteBuffer? = null
            var data: ByteBuffer? = null
            while (buf.remaining() >= CHUNK_HEADER_BYTES) {
                val type = fourCc(buf)
                var size = buf.long
                if (size == -1L) size = buf.remaining().toLong() // data chunk of unknown size runs to EOF
                val body = ByteBuffer.wrap(bytes, buf.position(), size.toInt()).slice()
                buf.position(buf.position() + size.toInt())
                when (type) {
                    "desc" -> desc = Desc.parse(body)
                    "kuki" -> cookie = ByteArray(body.remaining()).also { body.get(it) }
                    "pakt" -> pakt = body
                    "data" -> data = body.also { it.int } // skip mEditCount
                }
            }
            val d = requireNotNull(desc) { "no desc chunk" }
            val table = requireNotNull(pakt) { "no pakt chunk" }
            val audio = requireNotNull(data) { "no data chunk" }

            val numberPackets = table.long
            val validFrames = table.long
            val priming = table.int
            val remainder = table.int
            val packets = ArrayList<ByteArray>()
            for (i in 0 until numberPackets) {
                val size = if (d.bytesPerPacket == 0) readVarInt(table) else d.bytesPerPacket.toLong()
                if (d.framesPerPacket == 0) readVarInt(table) // per-packet frame count; ALAC fixtures are constant
                packets += ByteArray(size.toInt()).also { audio.get(it) }
            }
            return CafFile(
                sampleRate = d.sampleRate,
                formatId = d.formatId,
                framesPerPacket = d.framesPerPacket,
                channels = d.channels,
                cookie = cookie,
                packets = packets,
                validFrames = validFrames,
                primingFrames = priming,
                remainderFrames = remainder,
            )
        }

        /** CAF variable-length integer: big-endian groups of 7 bits, high bit set on all but the last byte. */
        fun readVarInt(buf: ByteBuffer): Long {
            var value = 0L
            do {
                val b = buf.get().toInt() and 0xff
                value = (value shl 7) or (b and 0x7f).toLong()
            } while (b and 0x80 != 0)
            return value
        }

        private fun fourCc(buf: ByteBuffer): String = String(ByteArray(4).also { buf.get(it) }, Charsets.ISO_8859_1)
    }

    private class Desc(
        val sampleRate: Double,
        val formatId: String,
        val bytesPerPacket: Int,
        val framesPerPacket: Int,
        val channels: Int,
    ) {
        companion object {
            fun parse(buf: ByteBuffer): Desc {
                val sampleRate = buf.double
                val formatId = fourCc(buf)
                buf.int // mFormatFlags
                val bytesPerPacket = buf.int
                val framesPerPacket = buf.int
                val channels = buf.int
                return Desc(sampleRate, formatId, bytesPerPacket, framesPerPacket, channels)
            }
        }
    }
}
