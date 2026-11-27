package ai.aneesiqbal.rozan.airplay.fairplay

import java.security.GeneralSecurityException
import java.security.MessageDigest
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * FairPlay v2 (Apple Music, RAOP audio `fpaeskey`), spec section 3.
 *
 * Constants are interoperability data from `docs/protocol/vectors/fairplay-v2.json`
 * and spec section 3.4/3.5; tests check them against the JSON.
 */
internal object FairPlayV2 {
    const val SAP_SIZE = 128
    const val MAC_KEY_SIZE = 20
    const val WRAPPING_KEY_SIZE = 16
    const val TAG_SIZE = 20

    /** Eleven independent round keys per mode (section 3.2); not an AES key schedule. */
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

    /** Section 3.4 `mac_prefix`/`mac_suffix` and section 3.5 `wrapping_prefix`/`wrapping_suffix`. */
    val MAC_PREFIX: ByteArray = "asdiuAD8AKA9a>/?".toByteArray(Charsets.US_ASCII)
    val MAC_SUFFIX: ByteArray = "!D4H&2*203kjgha9".toByteArray(Charsets.US_ASCII)
    val WRAPPING_PREFIX: ByteArray = "#l*GY&cqnwo*&5sd".toByteArray(Charsets.US_ASCII)
    val WRAPPING_SUFFIX: ByteArray = "57bv&%niulqpdg3z".toByteArray(Charsets.US_ASCII)

    /** The HMAC message of the handshake confirmation (section 3.4 step 3). */
    private val CONFIRMATION_MESSAGE = byteArrayOf(0x02, 0x02)

    /** Test access to the constants, so they can be checked against the vector JSON. */
    fun roundKeys(mode: Int): ByteArray = ROUND_KEYS[mode].copyOf()

    fun inputMask(mode: Int): ByteArray = INPUT_MASKS[mode].copyOf()

    fun outputMask(mode: Int): ByteArray = OUTPUT_MASKS[mode].copyOf()

    fun chainMask(mode: Int): ByteArray = CHAIN_MASKS[mode].copyOf()

    /** `sap_encrypt(mode, plaintext)` of section 3.2; [plaintext] is 128 bytes. */
    fun sapEncrypt(
        mode: Int,
        plaintext: ByteArray,
    ): ByteArray {
        val keys = ROUND_KEYS[mode]
        val output = OUTPUT_MASKS[mode]
        val cipher = ByteArray(SAP_SIZE)
        val previous = ByteArray(Aes.BLOCK_SIZE)
        for (block in 0 until SAP_SIZE / Aes.BLOCK_SIZE) {
            val offset = block * Aes.BLOCK_SIZE
            plaintext.copyInto(cipher, offset, offset, offset + Aes.BLOCK_SIZE)
            cipher.xorInPlace(offset, INPUT_MASKS[mode], 0, Aes.BLOCK_SIZE)
            if (block > 0) {
                cipher.xorInPlace(offset, previous, 0, Aes.BLOCK_SIZE)
                cipher.xorInPlace(offset, CHAIN_MASKS[mode], 0, Aes.BLOCK_SIZE)
            }
            Aes.encryptBlock(cipher, offset, keys)
            cipher.copyInto(previous, 0, offset, offset + Aes.BLOCK_SIZE)
            cipher.xorInPlace(offset, output, 0, Aes.BLOCK_SIZE)
        }
        previous.fill(0)
        return cipher
    }

    /** `sap_decrypt(mode, cipher)` of section 3.2 on the 128 bytes of [message] at [offset]. */
    fun sapDecrypt(
        mode: Int,
        message: ByteArray,
        offset: Int,
    ): ByteArray {
        val keys = ROUND_KEYS[mode]
        val plain = ByteArray(SAP_SIZE)
        val current = ByteArray(Aes.BLOCK_SIZE)
        val previous = ByteArray(Aes.BLOCK_SIZE)
        for (block in 0 until SAP_SIZE / Aes.BLOCK_SIZE) {
            val at = block * Aes.BLOCK_SIZE
            message.copyInto(current, 0, offset + at, offset + at + Aes.BLOCK_SIZE)
            current.xorInPlace(0, OUTPUT_MASKS[mode], 0, Aes.BLOCK_SIZE)
            current.copyInto(plain, at)
            Aes.decryptBlock(plain, at, keys)
            plain.xorInPlace(at, INPUT_MASKS[mode], 0, Aes.BLOCK_SIZE)
            if (block > 0) {
                plain.xorInPlace(at, previous, 0, Aes.BLOCK_SIZE)
                plain.xorInPlace(at, CHAIN_MASKS[mode], 0, Aes.BLOCK_SIZE)
            }
            current.copyInto(previous)
        }
        current.fill(0)
        previous.fill(0)
        return plain
    }

    /** `mac_key = SHA1(mac_prefix || sender_sap || receiver_sap || mac_suffix)` (section 3.4 step 2). */
    fun macKey(
        senderSap: ByteArray,
        receiverSap: ByteArray,
    ): ByteArray = digest("SHA-1", MAC_PREFIX, senderSap, receiverSap, MAC_SUFFIX)

    /** `wrapping_key = MD5(wrapping_prefix || sender_sap || receiver_sap || wrapping_suffix)` (section 3.5 step 1). */
    fun wrappingKey(
        senderSap: ByteArray,
        receiverSap: ByteArray,
    ): ByteArray = digest("MD5", WRAPPING_PREFIX, senderSap, receiverSap, WRAPPING_SUFFIX)

    /** `HMAC-SHA1(mac_key, 02 02)` (section 3.4 step 3). */
    fun confirmation(macKey: ByteArray): ByteArray =
        hmacSha1(macKey, CONFIRMATION_MESSAGE, 0, CONFIRMATION_MESSAGE.size)

    /** HMAC-SHA1 with [key] over [length] bytes of [data] from [offset]. */
    fun hmacSha1(
        key: ByteArray,
        data: ByteArray,
        offset: Int,
        length: Int,
    ): ByteArray =
        try {
            val mac = Mac.getInstance("HmacSHA1")
            mac.init(SecretKeySpec(key, "HmacSHA1"))
            mac.update(data, offset, length)
            mac.doFinal()
        } catch (e: GeneralSecurityException) {
            throw FairPlayException("platform HMAC-SHA1 failed", e)
        }

    private fun digest(
        algorithm: String,
        vararg parts: ByteArray,
    ): ByteArray =
        try {
            val digest = MessageDigest.getInstance(algorithm)
            for (part in parts) digest.update(part)
            digest.digest()
        } catch (e: GeneralSecurityException) {
            throw FairPlayException("platform $algorithm failed", e)
        }
}
