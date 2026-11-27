package ai.aneesiqbal.rozan.airplay.android.identity

import java.security.SecureRandom

/**
 * This install's own long-term receiver identity: a MAC-like device id and an
 * Ed25519 seed. Generated once per install and never sent anywhere except as
 * the receiver's public identity on the wire. Nothing about clients lives here.
 */
internal class ReceiverIdentity(
    deviceId: ByteArray,
    identitySeed: ByteArray,
) {
    private val id = deviceId.copyOf()
    private val seed = identitySeed.copyOf()

    init {
        require(id.size == DEVICE_ID_SIZE) { "deviceId must be $DEVICE_ID_SIZE bytes" }
        require(seed.size == SEED_SIZE) { "identitySeed must be $SEED_SIZE bytes" }
    }

    /** 6-byte device id (copy). */
    val deviceId: ByteArray get() = id.copyOf()

    /** 32-byte Ed25519 seed (copy). */
    val identitySeed: ByteArray get() = seed.copyOf()

    /** Device id as `AA:BB:CC:DD:EE:FF`. */
    fun deviceIdText(): String =
        buildString(DEVICE_ID_SIZE * 3) {
            for (i in id.indices) {
                if (i > 0) append(':')
                val value = id[i].toInt() and 0xFF
                append(HEX[value ushr 4]).append(HEX[value and 0x0F])
            }
        }

    /** Serialised form stored on disk: magic, version, device id, seed. */
    fun encode(): ByteArray {
        val out = ByteArray(ENCODED_SIZE)
        MAGIC.copyInto(out, 0)
        out[MAGIC.size] = VERSION
        id.copyInto(out, HEADER_SIZE)
        seed.copyInto(out, HEADER_SIZE + DEVICE_ID_SIZE)
        return out
    }

    companion object {
        const val DEVICE_ID_SIZE = 6
        const val SEED_SIZE = 32
        private val MAGIC = byteArrayOf('R'.code.toByte(), 'Z'.code.toByte(), 'I'.code.toByte(), 'D'.code.toByte())
        private const val VERSION: Byte = 1
        private const val HEX = "0123456789ABCDEF"
        private val HEADER_SIZE = MAGIC.size + 1
        val ENCODED_SIZE = HEADER_SIZE + DEVICE_ID_SIZE + SEED_SIZE

        /** Bit 1 of the first octet: locally administered address. */
        const val LOCALLY_ADMINISTERED = 0x02

        /** Bit 0 of the first octet: group (multicast) address. */
        const val MULTICAST = 0x01

        /**
         * A new random identity. The device id has the locally-administered bit
         * set and the multicast bit clear, so it can never collide with a
         * vendor-assigned (universal) MAC address and is a valid unicast address.
         */
        fun generate(random: SecureRandom): ReceiverIdentity {
            val id = ByteArray(DEVICE_ID_SIZE)
            random.nextBytes(id)
            id[0] = ((id[0].toInt() or LOCALLY_ADMINISTERED) and MULTICAST.inv()).toByte()
            val seed = ByteArray(SEED_SIZE)
            random.nextBytes(seed)
            return ReceiverIdentity(id, seed)
        }

        /** Parses [encode]'s output; null when [bytes] is not a valid identity record. */
        fun decode(bytes: ByteArray): ReceiverIdentity? {
            val sized = bytes.size == ENCODED_SIZE
            if (!sized || MAGIC.indices.any { bytes[it] != MAGIC[it] } || bytes[MAGIC.size] != VERSION) return null
            val id = bytes.copyOfRange(HEADER_SIZE, HEADER_SIZE + DEVICE_ID_SIZE)
            val first = id[0].toInt()
            if ((first and LOCALLY_ADMINISTERED) == 0 || (first and MULTICAST) != 0) return null
            return ReceiverIdentity(id, bytes.copyOfRange(HEADER_SIZE + DEVICE_ID_SIZE, ENCODED_SIZE))
        }
    }
}
