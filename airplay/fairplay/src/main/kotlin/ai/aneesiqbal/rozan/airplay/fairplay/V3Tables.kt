package ai.aneesiqbal.rozan.airplay.fairplay

import java.io.IOException
import java.io.InputStream
import java.security.GeneralSecurityException
import java.security.MessageDigest

/**
 * The FairPlay v3 data of `docs/protocol/vectors/fairplay-v3-tables.json`
 * (spec revision 2, sections 4.3, 4.5, 4.6 and 4.8). Revision 1's superseded
 * table-driven `decrypt_message` tables are not shipped.
 *
 * The data ships as the binary resource [RESOURCE], produced from the JSON by
 * `airplay/fairplay/tools/gen_v3_tables.py`: the fields below concatenated in
 * declaration order, no header. [instance] loads it once, on first use, and
 * rejects it unless its SHA-256 equals [SHA256].
 */
internal class V3Tables private constructor(
    data: ByteArray,
) {
    /** `decrypt_message` round keys per mode: 11 x 16 bytes each, one [ByteArray] per mode. */
    val messageRoundKeys: List<ByteArray>

    /** `message_iv[mode]`: 4 x 16 bytes, flat. */
    val messageIvs: ByteArray
    val buffer0Seed: ByteArray
    val buffer2Seed: ByteArray
    val buffer4Seed: ByteArray

    /** `i0_index`: 11 word indices into `buffer3`. */
    val i0Index: ByteArray
    val staticSource1: ByteArray
    val staticSource2: ByteArray
    val initialSessionKey: ByteArray
    val defaultSapTail: ByteArray
    val zKey: ByteArray
    val xKey: ByteArray
    val delta0: ByteArray
    val keyXor: ByteArray

    init {
        var offset = 0

        fun next(size: Int): ByteArray {
            val field = data.copyOfRange(offset, offset + size)
            offset += size
            return field
        }
        messageRoundKeys = List(MODES) { next(Aes.ROUND_KEYS_SIZE) }
        messageIvs = next(MODES * BLOCK)
        buffer0Seed = next(SapHash.BUFFER0_SIZE)
        buffer2Seed = next(SapHash.BUFFER2_SIZE)
        buffer4Seed = next(SapHash.BUFFER4_SIZE)
        i0Index = next(I0_INDEX_SIZE)
        staticSource1 = next(STATIC_SOURCE_1_SIZE)
        staticSource2 = next(STATIC_SOURCE_2_SIZE)
        initialSessionKey = next(BLOCK)
        defaultSapTail = next(DEFAULT_SAP_TAIL_SIZE)
        zKey = next(BLOCK)
        xKey = next(BLOCK)
        delta0 = next(BLOCK)
        keyXor = next(BLOCK)
        check(offset == SIZE)
    }

    companion object {
        const val RESOURCE = "/ai/aneesiqbal/rozan/airplay/fairplay/fairplay-v3-tables.bin"

        /** SHA-256 of [RESOURCE], printed by `gen_v3_tables.py`. */
        const val SHA256 = "59b5c102938f11e9cd1a76f158e303be2a3ec6e9e452bd0b0de73b45dd22110e"

        const val MODES = 4
        const val BLOCK = 16
        const val I0_INDEX_SIZE = 11
        const val STATIC_SOURCE_1_SIZE = 17
        const val STATIC_SOURCE_2_SIZE = 47
        const val DEFAULT_SAP_TAIL_SIZE = 128

        /** Size of [RESOURCE] in bytes. */
        const val SIZE =
            MODES * (Aes.ROUND_KEYS_SIZE + BLOCK) +
                SapHash.BUFFER0_SIZE + SapHash.BUFFER2_SIZE + SapHash.BUFFER4_SIZE + I0_INDEX_SIZE +
                STATIC_SOURCE_1_SIZE + STATIC_SOURCE_2_SIZE + BLOCK + DEFAULT_SAP_TAIL_SIZE + 4 * BLOCK

        /**
         * The tables, loaded from [RESOURCE] on first access. Throws
         * [FairPlayException] if the resource is missing or corrupt.
         */
        val instance: V3Tables by lazy { load() }

        private fun load(): V3Tables {
            val stream =
                V3Tables::class.java.getResourceAsStream(RESOURCE)
                    ?: throw FairPlayException("FairPlay v3 tables resource is missing")
            val data =
                try {
                    stream.use { readExactly(it) }
                } catch (e: IOException) {
                    throw FairPlayException("cannot read the FairPlay v3 tables resource", e)
                }
            return parse(data)
        }

        /** Validates [data] (size and SHA-256) and splits it into fields. */
        fun parse(data: ByteArray): V3Tables {
            if (data.size != SIZE) throw FairPlayException("FairPlay v3 tables resource has the wrong size")
            if (sha256Hex(data) != SHA256) throw FairPlayException("FairPlay v3 tables resource is corrupt")
            return V3Tables(data)
        }

        /** Reads at most `SIZE + 1` bytes, so an oversized resource is detected without reading it all. */
        private fun readExactly(stream: InputStream): ByteArray {
            val buffer = ByteArray(SIZE + 1)
            var filled = 0
            while (filled < buffer.size) {
                val read = stream.read(buffer, filled, buffer.size - filled)
                if (read < 0) break
                filled += read
            }
            return buffer.copyOf(filled)
        }

        fun sha256Hex(data: ByteArray): String {
            val digest =
                try {
                    MessageDigest.getInstance("SHA-256").digest(data)
                } catch (e: GeneralSecurityException) {
                    throw FairPlayException("platform SHA-256 failed", e)
                }
            return digest.joinToString("") { "%02x".format(it.toInt() and 0xFF) }
        }
    }
}
