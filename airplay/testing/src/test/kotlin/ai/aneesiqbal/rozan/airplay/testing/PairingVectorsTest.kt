package ai.aneesiqbal.rozan.airplay.testing

import ai.aneesiqbal.rozan.airplay.crypto.Ed25519KeyPair
import ai.aneesiqbal.rozan.airplay.crypto.X25519KeyPair
import ai.aneesiqbal.rozan.airplay.plist.BinaryPlist
import ai.aneesiqbal.rozan.airplay.plist.PlistDict
import ai.aneesiqbal.rozan.airplay.plist.buildPlistDict
import java.math.BigInteger
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** The sender's pairing halves against the vectors of docs/protocol/02-pairing.md §10. */
class PairingVectorsTest {
    private val user = "AA:BB:CC:DD:EE:FF"
    private val a = BigInteger("404142434445464748494a4b4c4d4e4f505152535455565758595a5b5c5d5e5f", 16)
    private val salt = hex("0102030405060708090a0b0c0d0e0f10")
    private val serverKey =
        hex(
            "2c526c063c05323b1d745de49fc362e25d87039874521bf4c030d749cfba4382" +
                "80c0b6fff41add825ded8edafd7ebe200608c7cd8b962326516870ac800771bd" +
                "606013954f5940a4a68ad29856f0e08d761944cda4abd7f300a1b43ee4d7eba4" +
                "1fde03c497cee3e61e37fac93f8d397cabd126f0ac504112c7d382d2ae5d42c5" +
                "18ab55eac38dd5cdf37d7f824e8325dd5d36c5413f54ccd75c4f588c266057f8" +
                "67d8f6bd722b75a13527cc61a4470ca6040e6f44b828b398eb65c3c4727f6702" +
                "5111498947ad8e6f6ddb617ecaeec9ca17b1e5ff9848fa025dcae11bb14d748e" +
                "ebad38ea33d8690c71fb657ab1c6d6df7abc4ef0b04c4723abe43bf6649e8659",
        )
    private val m2 = hex("d2a84ae2724b7fb5b0447d6ecce62ef74a865337")
    private val sender =
        Ed25519KeyPair.fromSeed(
            hex("606162636465666768696a6b6c6d6e6f707172737475767778797a7b7c7d7e7f"),
        )
    private val receiver =
        Ed25519KeyPair.fromSeed(
            hex("808182838485868788898a8b8c8d8e8f909192939495969798999a9b9c9d9e9f"),
        )

    @Test
    fun `SRP client reproduces A, M1 and K and checks M2`() {
        val srp = SrpClient(user.toByteArray(), "1234".toByteArray(), a)
        assertContentEquals(
            hex(
                "937c7b0538da06d03f23cdbc517a103be9e9bcc12d10ff4739a6d2ad4bf4659d" +
                    "15a34d340b4ebfff7004fbc2eed5572552bd03e4fdfc3be08cbf9156eff56637" +
                    "34e1a012a2a155e09259571d4f4541211480155063258c3861a98625b843d775" +
                    "206031fa1aa00d919ea734c984ac067654baca3007677125bcb4b187979306a5" +
                    "24c6b231a665dec628a7a03cd4cf4078e794a3d76b9f9134523adbf66393ec43" +
                    "49b38888d198159b7200abdb1383870fced29e72c8a79f04bab0cd39d5dc1bad" +
                    "e5ee28b627f22ef6078f72be3b583e799ac199bb6c7fd411debd53ceb120f5c5" +
                    "8c14ec4b58d5b26a6fb2c906d4f10ebc1acbd92793a4c86e341cafbf6bfa41f1",
            ),
            srp.publicKey,
        )
        assertContentEquals(hex("11fa191ea7c999c8029eba1ade3eeeefbe19f11f"), srp.proof(salt, serverKey))
        assertContentEquals(
            hex("d55b8d6406a174139f90b41473460ae2a0f55c2c753d8b2a7da31acca9420de28d2f7a5a43b447e4"),
            srp.sessionKey,
        )
        assertTrue(srp.verifyServer(m2))
        assertTrue(!srp.verifyServer(m2.copyOf().also { it[0] = 0 }))
    }

    @Test
    fun `PIN step 3 seals the sender key with IVc and opens the receiver key with IVr`() {
        val client = PinPairingClient("1234", user, sender, a)
        client.step2(plist { put("pk", serverKey).put("salt", salt) })
        val step3 = BinaryPlist.decode(client.step3(plist { put("proof", m2) })) as PlistDict
        assertContentEquals(hex("ea4fb875944de961bc74b3beb6337a5b5222dc13af795213989529587758b6b6"), step3.data("epk"))
        assertContentEquals(hex("2a74d8217486c694ab0674f529e9835b"), step3.data("authTag"))
        val response =
            plist {
                put("epk", hex("0d71689aeee69d453899fbf0883339825c4a291eaaef7fde146c1db8aee46851"))
                put("authTag", hex("ce15bf83b46484febee3d98af0747f56"))
            }
        assertContentEquals(receiver.publicKey, client.receiverKey(response))
    }

    @Test
    fun `a wrong M2 stops PIN pairing before step 3`() {
        val client = PinPairingClient("1234", user, sender, a)
        client.step2(plist { put("pk", serverKey).put("salt", salt) })
        assertFailsWith<SenderException> { client.step3(plist { put("proof", ByteArray(20)) }) }
    }

    @Test
    fun `pair-verify reproduces both requests and checks the receiver's signature`() {
        val ephemeral =
            X25519KeyPair.fromPrivateKey(
                hex("a0a1a2a3a4a5a6a7a8a9aaabacadaeafb0b1b2b3b4b5b6b7b8b9babbbcbdbebf"),
            )
        val client = PairVerifyClient(sender, ephemeral)
        assertContentEquals(
            hex(
                "01000000" + "605a725d2a4adfeeb1a29e17edd621c1b7593ee8cdbc44ac6c4ab6e2f805d23c" +
                    "174553b456dddfc6908ecab1c101fe6ab21e2baa0617795b7d43a63482993fd5",
            ),
            client.step1(),
        )
        val response =
            hex(
                "dc2cca31e8e43bbd91dff7e475cca3347eb478107d5bd765aba4ae4a30c35d44" +
                    "cd6aa83e570869d7247b7e30f5f7a1af53b5cd73a3275b6eb9bb0772c4002e79" +
                    "41d3b5876514c5eb385bb45daec98c1dcb86d5378ceb418f94544e748f0ec389",
            )
        assertContentEquals(
            hex(
                "00000000" + "dc4f6eeab56146ae0338dd729f58437d66b9f4e14011b28afcbdaaf856a941d1" +
                    "53b6901aab5499b42e1db996b58287b663ee651c2fe986ed4c7a05ebb0ad9d20",
            ),
            client.step2(response, receiver.publicKey),
        )
        assertContentEquals(
            hex("3b87c5035cce13678dd93d2dbc3a3d42039ebec86444a1ff82cd61b84c6fe968"),
            client.sharedSecret,
        )
        val impostor = Ed25519KeyPair.generate().publicKey
        assertFailsWith<SenderException> { PairVerifyClient(sender, ephemeral).step2(response, impostor) }
    }

    private fun plist(block: PlistDict.Builder.() -> Unit): ByteArray = BinaryPlist.encode(buildPlistDict(block))
}
