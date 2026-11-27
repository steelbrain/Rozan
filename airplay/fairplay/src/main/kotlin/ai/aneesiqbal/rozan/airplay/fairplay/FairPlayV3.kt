package ai.aneesiqbal.rozan.airplay.fairplay

/**
 * FairPlay v3 (screen mirroring, AirPlay video `ekey`), spec section 4.
 *
 * Setup and handshake replies are fixed data. The key decrypt derives a
 * session key from the stored handshake request ([sessionKey]) and recovers
 * the media key with one standard AES-128 block decryption ([mediaKey]).
 */
internal object FairPlayV3 {
    const val MESSAGE_SIZE = 164
    const val DECRYPTED_MESSAGE_SIZE = 128
    const val KEY_SIZE = 16

    /** The four 142-byte setup replies of section 4.1, indexed by mode. */
    private val SETUP_REPLIES: List<ByteArray> =
        listOf(
            hex(
                "46504c59030102000000008202000f9f3f9e0a2521dbdf312ab2bfb29e8d232b6376a8c818701d22" +
                    "ae93d82737feaf9db4fdf41c2dba9d1f49caaabf6591ac1f7bc6f7e0663d21afe01565953eab81f4" +
                    "18ceed095adb7c3d0e254909a79831d49c3982973434facb42c63a1cd911a6fe941a8a6d4a743b46" +
                    "c3a7649e44c78955e49d8155009549c4e2f7a3f6d5ba",
            ),
            hex(
                "46504c5903010200000000820201cf32a25714b2524f8aa0ad7af164e37bcf4424e200047efc0ad6" +
                    "7afcd95ded1c2730bb591b962ed63a9c4ded88ba8fc78de64d91ccfd5c7b56da88e31f5cceafc743" +
                    "1995a01665a54e1939d25b94db64b9e45d8d063e1e6af07e9656162b0efa404275ea5a44d9591c72" +
                    "56b9fbe6513898b80227721988571650942ad946688a",
            ),
            hex(
                "46504c5903010200000000820202c169a352eeed35b18cdd9c58d64f16c1519a89eb5317bd0d4336" +
                    "cd68f638ff9d016a5b52b7fa9216b2b65482c78444118121a2c7fed83db7119e9182aad7d18c7063" +
                    "e2a457555910af9e0efc76347d164043807f581ee4fbe42ca9dedc1b5eb2a3aa3d2ecd59e7eee70b" +
                    "3629f22afd161d877353ddb99adc8e07006e56f850ce",
            ),
            hex(
                "46504c59030102000000008202039001e1727e0f57f9f5880db104a6257a23f5cfff1abbe1e93045" +
                    "251afb97eb9fc0011ebe0f3a81df5b691d76acb2f7a5c708e3d328f56bb39dbde5f29c8a17f48148" +
                    "7e3ae863c678325422e6f78e166d18aa7fd636258bce28726f661f738893ce44311e4be6c0535193" +
                    "e5ef72e8686233729c227d820c999445d89246c8c359",
            ),
        )

    /** The 12-byte handshake reply header of section 4.2. */
    val HANDSHAKE_REPLY_HEADER: ByteArray = hex("46504c590301040000000014")

    private const val MODE_OFFSET = 12
    private const val MESSAGE_OFFSET = 16
    private const val SESSION_KEY_ROUNDS = 5
    private const val FINAL_XOR = 0x79

    /** A copy of the fixed setup reply for [mode] (0..3). */
    fun setupReply(mode: Int): ByteArray = SETUP_REPLIES[mode].copyOf()

    /**
     * `decrypt_message(message3)` of section 4.6: the eight blocks at
     * `message3[16..143]` decrypted with the AES-128 inverse cipher under the
     * mode's eleven round keys, in CBC mode with `message_iv[mode]`. Returns 128
     * bytes.
     */
    fun decryptMessage(
        message3: ByteArray,
        tables: V3Tables,
    ): ByteArray {
        val mode = message3.u8(MODE_OFFSET)
        val roundKeys = tables.messageRoundKeys[mode]
        val dm = message3.copyOfRange(MESSAGE_OFFSET, MESSAGE_OFFSET + DECRYPTED_MESSAGE_SIZE)
        for (offset in 0 until DECRYPTED_MESSAGE_SIZE step Aes.BLOCK_SIZE) {
            Aes.decryptBlock(dm, offset, roundKeys)
            if (offset == 0) {
                dm.xorInPlace(0, tables.messageIvs, mode * Aes.BLOCK_SIZE, Aes.BLOCK_SIZE)
            } else {
                // The previous ciphertext block, C[i - 1].
                dm.xorInPlace(offset, message3, MESSAGE_OFFSET + offset - Aes.BLOCK_SIZE, Aes.BLOCK_SIZE)
            }
        }
        return dm
    }

    /**
     * `generate_session_key(message3)` of section 4.5 (the `sapKey`, after its
     * final `^ 0x79`); returns 16 bytes.
     */
    fun sessionKey(
        message3: ByteArray,
        tables: V3Tables,
    ): ByteArray {
        val dm = decryptMessage(message3, tables)
        val newSap = newSap(dm, tables)
        dm.fill(0)
        val key = tables.initialSessionKey.copyOf()
        for (round in 0 until SESSION_KEY_ROUNDS) {
            val offset = round * ModifiedMd5.BLOCK_SIZE
            val m = ModifiedMd5.compress(newSap, offset, key)
            val h = SapHash.hash(newSap, offset, tables)
            for (word in 0 until 4) key.putLe32(4 * word, h.le32(4 * word) + m.le32(4 * word))
            m.fill(0)
            h.fill(0)
        }
        newSap.fill(0)
        // Byte-reverse each 32-bit word, then XOR every byte with 0x79.
        val sapKey = ByteArray(KEY_SIZE) { i -> (key[(i and 3.inv()) + 3 - (i and 3)].toInt() xor FINAL_XOR).toByte() }
        key.fill(0)
        return sapKey
    }

    /** `newSap` of section 4.5: `static_source_1 || dm || default_sap_tail || static_source_2` (320 bytes). */
    fun newSap(
        dm: ByteArray,
        tables: V3Tables,
    ): ByteArray {
        val out =
            ByteArray(
                tables.staticSource1.size + dm.size + tables.defaultSapTail.size + tables.staticSource2.size,
            )
        var at = 0
        for (part in arrayOf(tables.staticSource1, dm, tables.defaultSapTail, tables.staticSource2)) {
            part.copyInto(out, at)
            at += part.size
        }
        return out
    }

    /** `aes_key = sapKey ^ 7979...79` of section 4.3. */
    fun aesKey(
        sapKey: ByteArray,
        tables: V3Tables,
    ): ByteArray {
        val key = sapKey.copyOf()
        key.xorInPlace(0, tables.keyXor, 0, KEY_SIZE)
        return key
    }

    /**
     * `AES128_Decrypt(aes_key, chunk2) ^ delta0` of section 4.3, where `chunk2`
     * is `ekey[56..71]`; returns 16 bytes.
     */
    fun blockOutput(
        aesKey: ByteArray,
        ekey: ByteArray,
        tables: V3Tables,
    ): ByteArray {
        val block = ByteArray(KEY_SIZE)
        Aes.ecbDecrypt(aesKey, ekey, 56, block, 0)
        block.xorInPlace(0, tables.delta0, 0, KEY_SIZE)
        return block
    }

    /** `media_key = block ^ chunk1 ^ x_key ^ z_key` of section 4.3, where `chunk1` is `ekey[16..31]`. */
    fun mediaKey(
        aesKey: ByteArray,
        ekey: ByteArray,
        tables: V3Tables,
    ): ByteArray {
        val key = blockOutput(aesKey, ekey, tables)
        key.xorInPlace(0, ekey, 16, KEY_SIZE)
        key.xorInPlace(0, tables.xKey, 0, KEY_SIZE)
        key.xorInPlace(0, tables.zKey, 0, KEY_SIZE)
        return key
    }
}
