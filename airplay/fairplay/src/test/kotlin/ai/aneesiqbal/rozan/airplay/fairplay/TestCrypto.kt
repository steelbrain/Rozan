package ai.aneesiqbal.rozan.airplay.fairplay

import javax.crypto.Cipher
import javax.crypto.spec.SecretKeySpec

/** Test-only crypto: the AES-128 key schedule and the FairPlay v2 sender (spec section 5). */
object TestCrypto {
    /** FIPS-197 key expansion of a 16-byte key into 11 round keys (176 bytes). */
    fun expandKey(key: ByteArray): ByteArray {
        val out = ByteArray(Aes.ROUND_KEYS_SIZE)
        key.copyInto(out)
        var rcon = 1
        for (i in 4 until 44) {
            val t = IntArray(4) { out.u8(4 * (i - 1) + it) }
            if (i % 4 == 0) {
                val first = t[0]
                for (k in 0 until 3) t[k] = Aes.SBOX[t[k + 1]]
                t[3] = Aes.SBOX[first]
                t[0] = t[0] xor rcon
                rcon = (rcon shl 1).let { if (it and 0x100 != 0) it xor 0x11B else it }
            }
            for (k in 0 until 4) out[4 * i + k] = (out.u8(4 * (i - 4) + k) xor t[k]).toByte()
        }
        return out
    }

    // Single-block reference AES for comparisons; ECB is the raw block cipher here (see Aes.ecbDecrypt).
    @Suppress("GetInstance")
    fun aesEcb(
        mode: Int,
        key: ByteArray,
        block: ByteArray,
    ): ByteArray {
        val cipher = Cipher.getInstance("AES/ECB/NoPadding")
        cipher.init(mode, SecretKeySpec(key, "AES"))
        return cipher.doFinal(block)
    }

    fun xor(vararg parts: ByteArray): ByteArray {
        val out = parts[0].copyOf()
        for (part in parts.drop(1)) out.xorInPlace(0, part, 0, out.size)
        return out
    }

    /** The v2 handshake request a sender builds (spec section 5). */
    fun v2HandshakeRequest(
        mode: Int,
        senderSap: ByteArray,
        receiverSap: ByteArray,
    ): ByteArray {
        val macKey = FairPlayV2.macKey(senderSap, receiverSap)
        return hexToBytes("46504c590201030000000098") + byteArrayOf(mode.toByte()) + hexToBytes("8f1a9c") +
            FairPlayV2.sapEncrypt(mode, senderSap) + FairPlayV2.confirmation(macKey)
    }

    /** The 72-byte v2 key blob a sender builds (spec section 5). */
    fun v2KeyBlob(
        senderSap: ByteArray,
        receiverSap: ByteArray,
        mediaKey: ByteArray,
        blind: ByteArray,
    ): ByteArray {
        val macKey = FairPlayV2.macKey(senderSap, receiverSap)
        val wrappingKey = FairPlayV2.wrappingKey(senderSap, receiverSap)
        val wrapped = aesEcb(Cipher.ENCRYPT_MODE, wrappingKey, xor(mediaKey, blind))
        val header36 = hexToBytes("46504c5901020100" + "0000003c" + "00000000") + blind + hexToBytes("00000010")
        val authenticated = header36 + mediaKey
        val tag = FairPlayV2.hmacSha1(macKey, authenticated, 0, authenticated.size)
        return header36 + tag + wrapped
    }
}
