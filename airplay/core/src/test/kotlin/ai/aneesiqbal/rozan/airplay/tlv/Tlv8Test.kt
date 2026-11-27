package ai.aneesiqbal.rozan.airplay.tlv

import ai.aneesiqbal.rozan.airplay.hex
import ai.aneesiqbal.rozan.airplay.toHex
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.fail

class Tlv8Test {
    private val state: UByte = 0x06u
    private val publicKey: UByte = 0x03u
    private val salt: UByte = 0x02u

    @Test
    fun `encodes short items in order`() {
        val tlv =
            Tlv8
                .Builder()
                .add(state, 1u.toUByte())
                .add(salt, hex("0102030405"))
                .add(0x00u, ByteArray(0))
                .build()
        assertEquals("060101" + "02050102030405" + "0000", tlv.encode().toHex())
    }

    @Test
    fun `decodes items and reads values`() {
        val tlv = Tlv8.decode(hex("060102 0203aabbcc 0700 0203ddeeff"))
        assertEquals(listOf<UByte>(6u, 2u, 7u, 2u), tlv.items.map { it.type })
        assertEquals(2u.toUByte(), tlv.byteValue(state))
        assertContentEquals(hex("aabbcc"), tlv[salt])
        assertEquals(listOf("aabbcc", "ddeeff"), tlv.getAll(salt).map { it.toHex() })
        assertContentEquals(ByteArray(0), tlv[0x07u])
        assertNull(tlv[publicKey])
        assertNull(tlv.byteValue(publicKey))
        assertContentEquals(hex("aabbcc"), tlv.requireValue(salt))
        assertFailsWith<TlvException> { tlv.requireValue(publicKey) }
        assertFailsWith<TlvException> { tlv.byteValue(salt) }
        assertFailsWith<TlvException> { tlv.byteValue(0x07u) }
    }

    @Test
    fun `long values are split into 255-byte fragments and joined again`() {
        val key = ByteArray(384) { it.toByte() }
        val encoded =
            Tlv8
                .Builder()
                .add(publicKey, key)
                .add(state, 3u.toUByte())
                .build()
                .encode()
        assertEquals(2 + 255 + 2 + 129 + 3, encoded.size)
        assertEquals("03ff", encoded.copyOfRange(0, 2).toHex())
        assertEquals("0381", encoded.copyOfRange(257, 259).toHex())
        assertContentEquals(key.copyOfRange(255, 384), encoded.copyOfRange(259, 388))
        val decoded = Tlv8.decode(encoded)
        assertEquals(2, decoded.items.size)
        assertContentEquals(key, decoded[publicKey])
        assertEquals(3u.toUByte(), decoded.byteValue(state))
    }

    @Test
    fun `values of exactly 255 and 510 bytes use full fragments only`() {
        for (size in listOf(255, 510)) {
            val value = ByteArray(size) { 7 }
            val encoded =
                Tlv8
                    .Builder()
                    .add(publicKey, value)
                    .build()
                    .encode()
            assertEquals(size + 2 * (size / 255), encoded.size)
            assertContentEquals(value, Tlv8.decode(encoded)[publicKey])
        }
    }

    @Test
    fun `a fragment shorter than 255 ends the value`() {
        // 255 bytes, then 10 more of the same type: one value. Then the same type again: a new value.
        val bytes = byteArrayOf(3, -1) + ByteArray(255) + byteArrayOf(3, 10) + ByteArray(10) + byteArrayOf(3, 1, 9)
        val tlv = Tlv8.decode(bytes)
        assertEquals(listOf(265, 1), tlv.items.map { it.size })
    }

    @Test
    fun `separators keep list entries apart`() {
        val tlv =
            Tlv8
                .Builder()
                .add(0x01u, "a".toByteArray())
                .separator()
                .add(0x01u, "b".toByteArray())
                .build()
        assertEquals("010161" + "ff00" + "010162", tlv.encode().toHex())
        assertEquals(listOf("a", "b"), Tlv8.decode(tlv.encode()).getAll(0x01u).map { String(it) })
    }

    @Test
    fun `adjacent same-type values stay apart even when the first fills its last fragment`() {
        val first = ByteArray(255) { 1 }
        val tlv =
            Tlv8
                .Builder()
                .add(publicKey, first)
                .add(publicKey, byteArrayOf(2))
                .build()
        val encoded = tlv.encode()
        assertEquals("0300", encoded.copyOfRange(257, 259).toHex())
        assertEquals(tlv, Tlv8.decode(encoded))
        val empty =
            Tlv8
                .Builder()
                .add(publicKey, ByteArray(0))
                .add(publicKey, ByteArray(0))
                .build()
        assertEquals("03000300", empty.encode().toHex())
        assertEquals(empty, Tlv8.decode(empty.encode()))
    }

    @Test
    fun `decode honours offset and length`() {
        val bytes = hex("ffff 060101 ffff")
        assertEquals(1u.toUByte(), Tlv8.decode(bytes, offset = 2, length = 3).byteValue(state))
        assertEquals(0, Tlv8.decode(bytes, offset = 2, length = 0).items.size)
        assertFailsWith<IllegalArgumentException> { Tlv8.decode(bytes, offset = 5, length = 3) }
        assertFailsWith<IllegalArgumentException> { Tlv8.decode(bytes, offset = -1, length = 1) }
        assertFailsWith<IllegalArgumentException> { Tlv8.decode(bytes, offset = 1, length = Int.MAX_VALUE) }
    }

    @Test
    fun `truncated input is rejected`() {
        val encoded =
            Tlv8
                .Builder()
                .add(publicKey, ByteArray(300))
                .add(state, 1u.toUByte())
                .build()
                .encode()
        for (length in 1 until encoded.size) {
            val truncated = encoded.copyOf(length)
            // Cuts that fall exactly between items decode to a shorter message; every other cut must fail.
            val result = runCatching { Tlv8.decode(truncated) }
            val error = result.exceptionOrNull()
            if (error != null && error !is TlvException) fail("unexpected $error")
            if (length !in setOf(257, 2 + 255 + 2 + 45)) {
                assertFailsWith<TlvException>("length $length") { Tlv8.decode(truncated) }
            }
        }
    }

    @Test
    fun `limits are enforced`() {
        val many =
            Tlv8
                .Builder()
                .apply { repeat(5) { add(it.toUByte(), byteArrayOf(1)) } }
                .build()
                .encode()
        assertEquals(5, Tlv8.decode(many, limits = TlvLimits(maxItems = 5)).items.size)
        assertFailsWith<TlvException> { Tlv8.decode(many, limits = TlvLimits(maxItems = 4)) }
        val long =
            Tlv8
                .Builder()
                .add(publicKey, ByteArray(600))
                .build()
                .encode()
        assertEquals(600, Tlv8.decode(long, limits = TlvLimits(maxValueBytes = 600)).items[0].size)
        assertFailsWith<TlvException> { Tlv8.decode(long, limits = TlvLimits(maxValueBytes = 599)) }
        assertFailsWith<IllegalArgumentException> { TlvLimits(maxItems = 0) }
        assertFailsWith<IllegalArgumentException> { TlvLimits(maxValueBytes = -1) }
    }

    @Test
    fun `items copy their values and hide them from toString`() {
        val value = byteArrayOf(1, 2, 3)
        val item = TlvItem(salt, value)
        value[0] = 9
        assertContentEquals(byteArrayOf(1, 2, 3), item.value())
        item.value()[1] = 9
        assertContentEquals(byteArrayOf(1, 2, 3), item.value())
        assertEquals("TlvItem(type=2, 3 bytes)", item.toString())
        assertEquals(TlvItem(salt, byteArrayOf(1, 2, 3)), item)
        assertEquals(TlvItem(salt, byteArrayOf(1, 2, 3)).hashCode(), item.hashCode())
    }

    @Test
    fun `random messages round-trip`() {
        val random = Random(8)
        repeat(2_000) {
            val tlv =
                Tlv8(
                    List(random.nextInt(8)) {
                        val size = listOf(0, 1, 254, 255, 256, 510, 700).random(random)
                        TlvItem(random.nextInt(4).toUByte(), random.nextBytes(size))
                    },
                )
            assertEquals(tlv, Tlv8.decode(tlv.encode()))
        }
    }

    @Test
    fun `random garbage fails only with TlvException`() {
        val random = Random(9)
        repeat(20_000) {
            val bytes = random.nextBytes(random.nextInt(600))
            val limits = TlvLimits(maxItems = 16, maxValueBytes = 400)
            val error = runCatching { Tlv8.decode(bytes, limits = limits) }.exceptionOrNull()
            if (error != null && error !is TlvException) fail("${error::class.simpleName} for ${bytes.toHex()}", error)
        }
    }
}
