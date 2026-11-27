package ai.aneesiqbal.rozan.airplay.crypto

import java.math.BigInteger

/**
 * An SRP group: the safe prime modulus N and the generator g.
 *
 * Integer-to-byte conversions follow RFC 5054 section 2.1: an implicit conversion is big-endian with no
 * leading zero bytes, and [pad] left-pads to the byte length of N.
 *
 * @property prime the modulus N.
 * @property generator the generator g.
 */
public class SrpGroup(
    public val prime: BigInteger,
    public val generator: BigInteger,
) {
    init {
        require(prime.signum() > 0 && prime.testBit(0) && prime.bitLength() >= MIN_PRIME_BITS) {
            "prime must be an odd number of at least $MIN_PRIME_BITS bits"
        }
        require(generator > BigInteger.ONE && generator < prime) { "generator must be in [2, N - 1]" }
    }

    /** Length of N in bytes; the width that [pad] produces. */
    public val byteLength: Int = (prime.bitLength() + 7) / 8

    /**
     * Returns [value] (a big-endian unsigned integer) left-padded with zero bytes to [byteLength], the
     * `PAD()` operation of RFC 5054. Leading zero bytes in [value] are removed first.
     *
     * @throws IllegalArgumentException if the integer does not fit in [byteLength] bytes.
     */
    public fun pad(value: ByteArray): ByteArray = pad(BigInteger(1, value))

    internal fun pad(value: BigInteger): ByteArray {
        val bytes = value.toUnsignedBytes()
        require(bytes.size <= byteLength) { "value does not fit in $byteLength bytes" }
        if (bytes.size == byteLength) return bytes
        val out = ByteArray(byteLength)
        bytes.copyInto(out, byteLength - bytes.size)
        return out
    }

    /** The groups of RFC 5054 Appendix A used by AirPlay pairing (and the 1024-bit one its test vectors use). */
    public companion object {
        private const val MIN_PRIME_BITS = 512

        /** RFC 5054 Appendix A, 1024-bit group, g = 2. Only for interop with the RFC test vectors. */
        public val RFC5054_1024: SrpGroup =
            group(
                """
                EEAF0AB9 ADB38DD6 9C33F80A FA8FC5E8 60726187 75FF3C0B 9EA2314C
                9C256576 D674DF74 96EA81D3 383B4813 D692C6E0 E0D5D8E2 50B98BE4
                8E495C1D 6089DAD1 5DC7D7B4 6154D6B6 CE8EF4AD 69B15D49 82559B29
                7BCF1885 C529F566 660E57EC 68EDBC3C 05726CC0 2FD4CBF4 976EAA9A
                FD5138FE 8376435B 9FC61D2F C0EB06E3
                """,
                generator = 2,
            )

        /** RFC 5054 Appendix A, 2048-bit group, g = 2. */
        public val RFC5054_2048: SrpGroup =
            group(
                """
                AC6BDB41 324A9A9B F166DE5E 1389582F AF72B665 1987EE07 FC319294
                3DB56050 A37329CB B4A099ED 8193E075 7767A13D D52312AB 4B03310D
                CD7F48A9 DA04FD50 E8083969 EDB767B0 CF609517 9A163AB3 661A05FB
                D5FAAAE8 2918A996 2F0B93B8 55F97993 EC975EEA A80D740A DBF4FF74
                7359D041 D5C33EA7 1D281E44 6B14773B CA97B43A 23FB8016 76BD207A
                436C6481 F1D2B907 8717461A 5B9D32E6 88F87748 544523B5 24B0D57D
                5EA77A27 75D2ECFA 032CFBDB F52FB378 61602790 04E57AE6 AF874E73
                03CE5329 9CCC041C 7BC308D8 2A5698F3 A8D0C382 71AE35F8 E9DBFBB6
                94B5C803 D89F7AE4 35DE236D 525F5475 9B65E372 FCD68EF2 0FA7111F
                9E4AFF73
                """,
                generator = 2,
            )

        /** RFC 5054 Appendix A, 3072-bit group, g = 5 (the HomeKit pair-setup group). */
        public val RFC5054_3072: SrpGroup =
            group(
                """
                FFFFFFFF FFFFFFFF C90FDAA2 2168C234 C4C6628B 80DC1CD1 29024E08
                8A67CC74 020BBEA6 3B139B22 514A0879 8E3404DD EF9519B3 CD3A431B
                302B0A6D F25F1437 4FE1356D 6D51C245 E485B576 625E7EC6 F44C42E9
                A637ED6B 0BFF5CB6 F406B7ED EE386BFB 5A899FA5 AE9F2411 7C4B1FE6
                49286651 ECE45B3D C2007CB8 A163BF05 98DA4836 1C55D39A 69163FA8
                FD24CF5F 83655D23 DCA3AD96 1C62F356 208552BB 9ED52907 7096966D
                670C354E 4ABC9804 F1746C08 CA18217C 32905E46 2E36CE3B E39E772C
                180E8603 9B2783A2 EC07A28F B5C55DF0 6F4C52C9 DE2BCBF6 95581718
                3995497C EA956AE5 15D22618 98FA0510 15728E5A 8AAAC42D AD33170D
                04507A33 A85521AB DF1CBA64 ECFB8504 58DBEF0A 8AEA7157 5D060C7D
                B3970F85 A6E1E4C7 ABF5AE8C DB0933D7 1E8C94E0 4A25619D CEE3D226
                1AD2EE6B F12FFA06 D98A0864 D8760273 3EC86A64 521F2B18 177B200C
                BBE11757 7A615D6C 770988C0 BAD946E2 08E24FA0 74E5AB31 43DB5BFC
                E0FD108E 4B82D120 A93AD2CA FFFFFFFF FFFFFFFF
                """,
                generator = 5,
            )

        private fun group(
            primeHex: String,
            generator: Long,
        ): SrpGroup = SrpGroup(BigInteger(primeHex.filterNot { it.isWhitespace() }, 16), BigInteger.valueOf(generator))
    }
}
