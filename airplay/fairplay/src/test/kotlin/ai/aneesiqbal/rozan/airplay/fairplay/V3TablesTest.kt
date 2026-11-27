package ai.aneesiqbal.rozan.airplay.fairplay

import java.io.File
import java.security.MessageDigest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class V3TablesTest {
    private val resource: ByteArray =
        checkNotNull(V3Tables::class.java.getResourceAsStream(V3Tables.RESOURCE)).use { it.readBytes() }

    @Test
    fun `resource checksum matches the constant`() {
        assertEquals(V3Tables.SIZE, resource.size)
        val digest = MessageDigest.getInstance("SHA-256").digest(resource).toHex()
        assertEquals(V3Tables.SHA256, digest)
    }

    @Test
    fun `resource is the tables JSON in the documented layout`() {
        val t = Vectors.tables
        val dm = t.obj("decryptMessage")
        val sh = t.obj("sap_hash")
        val sk = t.obj("session_key")
        val fa = t.obj("final_aes")
        // Revision 1's superseded table_s2, table_s9, table_s10 and message_key are not shipped.
        val expected =
            dm
                .obj("round_keys")
                .list("bytes")
                .map { hexToBytes(it as String) }
                .reduce(ByteArray::plus) +
                dm
                    .obj("message_iv")
                    .list("bytes")
                    .map { hexToBytes(it as String) }
                    .reduce(ByteArray::plus) +
                sh.obj("buffer0_seed").hex("bytes") +
                sh.obj("buffer2_seed").hex("bytes") +
                sh.obj("buffer4_seed").hex("bytes") +
                sh
                    .obj("i0_index")
                    .list("values")
                    .map { (it as Long).toInt().toByte() }
                    .toByteArray() +
                sk.obj("static_source_1").hex("bytes") +
                sk.obj("static_source_2").hex("bytes") +
                sk.obj("initial_session_key").hex("bytes") +
                sk.obj("default_sap_tail").hex("bytes") +
                fa.obj("z_key").hex("bytes") +
                fa.obj("x_key").hex("bytes") +
                fa.obj("delta0").hex("bytes") +
                fa.obj("key_xor").hex("bytes")
        assertContentEquals(expected, resource)
    }

    @Test
    fun `parsed fields land where the layout says`() {
        val tables = V3Tables.instance
        val dm = Vectors.tables.obj("decryptMessage")
        val roundKeys = dm.obj("round_keys").list("bytes")
        assertEquals(4, roundKeys.size)
        for (mode in 0 until 4) {
            assertContentEquals(hexToBytes(roundKeys[mode] as String), tables.messageRoundKeys[mode])
        }
        assertContentEquals(
            dm
                .obj("message_iv")
                .list("bytes")
                .joinToString("") {
                    it as String
                }.let(::hexToBytes),
            tables.messageIvs,
        )
        val sk = Vectors.tables.obj("session_key")
        assertContentEquals(sk.obj("default_sap_tail").hex("bytes"), tables.defaultSapTail)
        assertContentEquals(sk.obj("static_source_2").hex("bytes"), tables.staticSource2)
    }

    @Test
    fun `corrupt or resized data is rejected`() {
        assertContentEquals(V3Tables.instance.defaultSapTail, V3Tables.parse(resource.copyOf()).defaultSapTail)
        for (index in listOf(0, 777, resource.size - 1)) {
            val corrupt = resource.copyOf()
            corrupt[index] = (corrupt[index].toInt() xor 1).toByte()
            assertFailsWith<FairPlayException> { V3Tables.parse(corrupt) }
        }
        assertFailsWith<FairPlayException> { V3Tables.parse(resource.copyOf(resource.size - 1)) }
        assertFailsWith<FairPlayException> { V3Tables.parse(resource + byteArrayOf(0)) }
        assertFailsWith<FairPlayException> { V3Tables.parse(ByteArray(0)) }
    }

    @Test
    fun `test vector copies equal docs-protocol-vectors`() {
        val docs = File(checkNotNull(System.getProperty("rozan.fairplay.docsVectors")))
        val names =
            listOf(
                "fairplay-v2.json",
                "fairplay-v3.json",
                "fairplay-v3-tables.json",
                "fairplay-v3-coverage.json",
                "fairplay-sessions.json",
            )
        for (name in names) {
            assertContentEquals(File(docs, name).readBytes(), Vectors.resourceBytes(name), name)
        }
    }
}
