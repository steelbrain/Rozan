package ai.aneesiqbal.rozan.airplay.testing

import ai.aneesiqbal.rozan.airplay.crypto.Crypto
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * The sender side of FairPlay v2 (docs/protocol/04-fairplay.md §3 and the sender-side construction in
 * §5), the variant a sender uses for `fpaeskey`; the receiver accepts its key blob as the SETUP `ekey`
 * as well (04 §1.5). Written from the spec only.
 *
 * Usage, one instance per control connection:
 *
 *     val fp = FairPlayV2Sender()
 *     val reply1 = post("/fp-setup", fp.setupRequest())
 *     val reply2 = post("/fp-setup", fp.handshakeRequest(reply1))
 *     fp.checkHandshakeReply(reply2)
 *     val ekey = fp.keyBlob(mediaKey)        // 72 bytes; the receiver recovers mediaKey
 *
 * @param mode the FairPlay mode byte, 0..3.
 * @param random the source of the sender SAP and key blinds.
 */
public class FairPlayV2Sender(
    private val mode: Int = 0,
    private val random: SecureRandom = SecureRandom(),
) {
    init {
        require(mode in 0 until MODES) { "mode must be 0..3" }
    }

    private val senderSap: ByteArray = ByteArray(SAP_BYTES).also(random::nextBytes)
    private var receiverSap: ByteArray? = null

    /** The 16-byte setup request (04 §1.1). */
    public fun setupRequest(): ByteArray = hex("46504c590201010000000004020000bb").also { it[14] = mode.toByte() }

    /**
     * The 164-byte handshake request for the receiver's 142-byte [setupReply]: the sender knows the SAP
     * cipher, so it reads the receiver SAP back out of the reply and proves it with the confirmation tag.
     *
     * @throws SenderException if the reply is not a v2 setup reply for this mode.
     */
    public fun handshakeRequest(setupReply: ByteArray): ByteArray {
        val prefix = hex("46504c5902010200000000820200").also { it[13] = mode.toByte() }
        if (setupReply.size != SETUP_REPLY_BYTES || !setupReply.copyOf(prefix.size).contentEquals(prefix)) {
            throw SenderException("not a FairPlay v2 setup reply for mode $mode (${setupReply.size} bytes)")
        }
        val sap = SapCipher.decrypt(mode, setupReply.copyOfRange(prefix.size, SETUP_REPLY_BYTES))
        receiverSap = sap
        return hex("46504c590201030000000098") + byteArrayOf(mode.toByte()) + hex("8f1a9c") +
            SapCipher.encrypt(mode, senderSap) + confirmation()
    }

    /**
     * Checks the 32-byte handshake reply: the receiver recomputed the same confirmation tag (04 §1.4).
     *
     * @throws SenderException if it did not.
     */
    public fun checkHandshakeReply(reply: ByteArray) {
        val expected = hex("46504c590201040000000014") + confirmation()
        if (!Crypto.constantTimeEquals(expected, reply)) {
            throw SenderException("the FairPlay handshake reply does not confirm this handshake")
        }
    }

    /**
     * The 72-byte key blob carrying [mediaKey] (04 §5): the key XOR [blind], wrapped with AES-128-ECB
     * under the wrapping key, authenticated with HMAC-SHA1 under the MAC key.
     */
    public fun keyBlob(
        mediaKey: ByteArray,
        blind: ByteArray = ByteArray(BLOCK).also(random::nextBytes),
    ): ByteArray {
        require(mediaKey.size == BLOCK && blind.size == BLOCK) { "the key and the blind are 16 bytes" }
        val receiver = checkNotNull(receiverSap) { "keyBlob before handshakeRequest" }
        val wrappingKey = digest("MD5", WRAPPING_PREFIX, senderSap, receiver, WRAPPING_SUFFIX)
        val wrapped = ecbEncrypt(wrappingKey, xor(mediaKey, blind))
        val header36 = hex("46504c5901020100" + "0000003c" + "00000000") + blind + hex("00000010")
        val tag = hmacSha1(macKey(receiver), header36 + mediaKey)
        return header36 + tag + wrapped
    }

    private fun confirmation(): ByteArray = hmacSha1(macKey(checkNotNull(receiverSap)), byteArrayOf(0x02, 0x02))

    private fun macKey(receiver: ByteArray): ByteArray = digest("SHA-1", MAC_PREFIX, senderSap, receiver, MAC_SUFFIX)

    private companion object {
        const val MODES = 4
        const val SAP_BYTES = 128
        const val BLOCK = 16
        const val SETUP_REPLY_BYTES = 142

        val MAC_PREFIX = "asdiuAD8AKA9a>/?".toByteArray(Charsets.US_ASCII)
        val MAC_SUFFIX = "!D4H&2*203kjgha9".toByteArray(Charsets.US_ASCII)
        val WRAPPING_PREFIX = "#l*GY&cqnwo*&5sd".toByteArray(Charsets.US_ASCII)
        val WRAPPING_SUFFIX = "57bv&%niulqpdg3z".toByteArray(Charsets.US_ASCII)

        fun digest(
            algorithm: String,
            vararg parts: ByteArray,
        ): ByteArray {
            val md = MessageDigest.getInstance(algorithm)
            for (part in parts) md.update(part)
            return md.digest()
        }

        fun hmacSha1(
            key: ByteArray,
            data: ByteArray,
        ): ByteArray {
            val mac = Mac.getInstance("HmacSHA1")
            mac.init(SecretKeySpec(key, "HmacSHA1"))
            return mac.doFinal(data)
        }

        // One raw AES block, as 04 §3.5 and §5 specify; no chaining is involved.
        @Suppress("GetInstance")
        fun ecbEncrypt(
            key: ByteArray,
            block: ByteArray,
        ): ByteArray {
            val cipher = Cipher.getInstance("AES/ECB/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"))
            return cipher.doFinal(block)
        }
    }
}

/**
 * The FairPlay v2 SAP cipher (04 §3.2): the AES-128 round function with eleven given round keys per mode
 * (not a key schedule, so no platform cipher can run it), chained with three masks per mode. Constants
 * are interoperability data from docs/protocol/vectors/fairplay-v2.json.
 */
internal object SapCipher {
    private const val BLOCK = 16
    private const val ROUNDS = 10

    private val ROUND_KEYS: List<ByteArray> =
        listOf(
            hex(
                "523263286e2de137049c00832cd656070628a0846816a5207280964167be739daaa7fe01c2b15b21" +
                    "b031cd60d78fbefddd09aa0f1fb8f12eaf893c4e780682b3ba1ac7b3a5a2369d0a2b0ad3722d8860" +
                    "72de17f3d77c216edd572bbdaf7aa3dd88d4d68a5fa8f7e482ffdc592d857f845f06895200ae7eb6" +
                    "8251a2efafd4dd6b97c7f62b9769889d15382a72baecf71942af22dfd5c6aa42c0fe80307a127729" +
                    "6a7826bfc28ca7b268baa64b2d9d49bf",
            ),
            hex(
                "d5a9ce40a0f13f2229f9fc95e08eec568e837ccb618bcbd7015ec427a8d2509339d0a009585b6bde" +
                    "5905aff9f1d7ff6a33c6a2a86b9dc9763298668fc34f99e5bf287b86d4b5b2f0e62dd47f25624d9a" +
                    "05cbc3b9d17e71493753a5361231e8ace2505270332e2339047d860f164c6ea38bcf5837b8e17b0e" +
                    "bc9cfd01aad093a27b13629bc3f219957f6ee494d5be7736cee667980d147e0d727a9a99a7c4edaf" +
                    "afd5919340a4e654640182428969293a",
            ),
            hex(
                "86989d3be7920dc2f5e37fd5f61124af7494da7735e813a22e6ac2d994cda344cb9ec155fe76d2f7" +
                    "d01c102e44d1b36af1f3c34e0f8511b9df9901979b48b2fdabc4975aa44186e37bd88774e0903589" +
                    "db5230bb7f13b65804cb312ce45b04a5c2a036d2bdb3808ab978b1a65d23b503a4754d9e19c6cd14" +
                    "a0be7cb2fd9dc9b17aa885ca636e48dec3d0346c3e4dfddd82fc4478e1920ca6224238ca1c0fc517" +
                    "7eedff55ed156b1c0842aafbfbe63371",
            ),
            hex(
                "48cb53065e61a30ea823779991ed11e35c6b5d40f0605667e9369b7a54370eb8c4c0316034a06707" +
                    "dd96fc7d89a1f2c5f24997c7c6e9f0c01b7f0cbd92defe78e7f22b88211bdb483a64d7f5a8ba298d" +
                    "0357764a224cad0218287af7b092537a6cbaacad4ef601af56de7b58e64c2822058e3f234b783e8c" +
                    "1da645d4fbea6df602b27d2c49ca43a0546c0674af866b825dcd6e5514072df5406b2b81efed4003" +
                    "3d2d5e53386c7df2add2e1aff7294558",
            ),
        )

    private val INPUT_MASKS: List<ByteArray> =
        listOf(
            hex("00000000000000000000000000000000"),
            hex("00000000000000000000000000000000"),
            hex("080e315dccc14e78902754d816da4138"),
            hex("00000000000000000000000000000000"),
        )

    private val OUTPUT_MASKS: List<ByteArray> =
        listOf(
            hex("d722a1baaa108af5c0d80b3cffed93e1"),
            hex("4b668f57a903869dffdc7812b5703ec5"),
            hex("bcb74bb1ceddd35e09c82a73e66376ee"),
            hex("03e94bd912af458dc77af251726c16a5"),
        )

    private val CHAIN_MASKS: List<ByteArray> =
        listOf(
            hex("30e1e44faa036e66ded238dec605203a"),
            hex("756eb00fe6fa0ea3b6f08b77fc724627"),
            hex("1b5a6354a4f25931778ed005bc0f72e4"),
            hex("6a6360e5e0c5eda4760f48d55e809284"),
        )

    private val SBOX = IntArray(256)
    private val INV_SBOX = IntArray(256)

    init {
        // The S-box from its definition: the inverse in GF(2^8), then the affine map.
        val exp = IntArray(255)
        val log = IntArray(256)
        var x = 1
        for (i in 0 until 255) {
            exp[i] = x
            log[x] = i
            x = x xor xtime(x)
        }
        for (a in 0 until 256) {
            val inverse = if (a == 0) 0 else exp[(255 - log[a]) % 255]
            var s = inverse
            for (shift in 1..4) s = s xor (((inverse shl shift) or (inverse ushr (8 - shift))) and 0xFF)
            s = s xor 0x63
            SBOX[a] = s
            INV_SBOX[s] = a
        }
    }

    /** `sap_encrypt(mode, plaintext[128])`. */
    fun encrypt(
        mode: Int,
        plaintext: ByteArray,
    ): ByteArray {
        val out = ByteArray(plaintext.size)
        var previous = ByteArray(BLOCK)
        for (b in 0 until plaintext.size / BLOCK) {
            var s = xor(plaintext.copyOfRange(b * BLOCK, b * BLOCK + BLOCK), INPUT_MASKS[mode])
            if (b > 0) s = xor(xor(s, previous), CHAIN_MASKS[mode])
            s = encryptBlock(s, ROUND_KEYS[mode])
            previous = s
            xor(s, OUTPUT_MASKS[mode]).copyInto(out, b * BLOCK)
        }
        return out
    }

    /** `sap_decrypt(mode, cipher[128])`. */
    fun decrypt(
        mode: Int,
        cipher: ByteArray,
    ): ByteArray {
        val out = ByteArray(cipher.size)
        var previous = ByteArray(BLOCK)
        for (b in 0 until cipher.size / BLOCK) {
            val current = xor(cipher.copyOfRange(b * BLOCK, b * BLOCK + BLOCK), OUTPUT_MASKS[mode])
            var p = xor(decryptBlock(current, ROUND_KEYS[mode]), INPUT_MASKS[mode])
            if (b > 0) p = xor(xor(p, previous), CHAIN_MASKS[mode])
            p.copyInto(out, b * BLOCK)
            previous = current
        }
        return out
    }

    private fun encryptBlock(
        block: ByteArray,
        keys: ByteArray,
    ): ByteArray {
        val s = IntArray(BLOCK) { block.u8(it) }
        addRoundKey(s, keys, 0)
        for (round in 1 until ROUNDS) {
            substitute(s, SBOX)
            shiftRows(s, inverse = false)
            mixColumns(s, MIX)
            addRoundKey(s, keys, round)
        }
        substitute(s, SBOX)
        shiftRows(s, inverse = false)
        addRoundKey(s, keys, ROUNDS)
        return ByteArray(BLOCK) { s[it].toByte() }
    }

    private fun decryptBlock(
        block: ByteArray,
        keys: ByteArray,
    ): ByteArray {
        val s = IntArray(BLOCK) { block.u8(it) }
        addRoundKey(s, keys, ROUNDS)
        for (round in ROUNDS - 1 downTo 1) {
            shiftRows(s, inverse = true)
            substitute(s, INV_SBOX)
            addRoundKey(s, keys, round)
            mixColumns(s, INV_MIX)
        }
        shiftRows(s, inverse = true)
        substitute(s, INV_SBOX)
        addRoundKey(s, keys, 0)
        return ByteArray(BLOCK) { s[it].toByte() }
    }

    private fun addRoundKey(
        s: IntArray,
        keys: ByteArray,
        round: Int,
    ) {
        for (i in 0 until BLOCK) s[i] = s[i] xor keys.u8(round * BLOCK + i)
    }

    private fun substitute(
        s: IntArray,
        box: IntArray,
    ) {
        for (i in 0 until BLOCK) s[i] = box[s[i]]
    }

    /** State byte `4 * column + row`; row r rotates left by r columns (right for the inverse). */
    private fun shiftRows(
        s: IntArray,
        inverse: Boolean,
    ) {
        val copy = s.copyOf()
        for (column in 0 until 4) {
            for (row in 1 until 4) {
                val source = if (inverse) (column - row + 4) % 4 else (column + row) % 4
                s[4 * column + row] = copy[4 * source + row]
            }
        }
    }

    /** The MixColumns matrix rows, and InvMixColumns'. */
    private val MIX = intArrayOf(2, 3, 1, 1)
    private val INV_MIX = intArrayOf(0x0E, 0x0B, 0x0D, 0x09)

    /** Multiplies each column by the circulant matrix whose first row is [row]. */
    private fun mixColumns(
        s: IntArray,
        row: IntArray,
    ) {
        for (c in 0 until 4) {
            val a = IntArray(4) { s[4 * c + it] }
            for (r in 0 until 4) {
                var v = 0
                for (k in 0 until 4) v = v xor mul(a[k], row[(k - r + 4) % 4])
                s[4 * c + r] = v
            }
        }
    }

    private fun xtime(a: Int): Int {
        val shifted = a shl 1
        return if (shifted and 0x100 != 0) shifted xor 0x11B else shifted
    }

    private fun mul(
        a: Int,
        b: Int,
    ): Int {
        var result = 0
        var x = a
        var y = b
        while (y != 0) {
            if (y and 1 != 0) result = result xor x
            x = xtime(x)
            y = y ushr 1
        }
        return result
    }
}
