package ai.aneesiqbal.rozan.airplay.android.identity

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermission
import java.security.SecureRandom

class IdentityTest {
    @get:Rule
    val temp = TemporaryFolder()

    @Test
    fun generatedDeviceIdIsLocallyAdministeredUnicast() {
        val random = SecureRandom()
        repeat(1000) {
            val first = ReceiverIdentity.generate(random).deviceId[0].toInt()
            assertTrue("locally administered bit set", first and ReceiverIdentity.LOCALLY_ADMINISTERED != 0)
            assertEquals("multicast bit clear", 0, first and ReceiverIdentity.MULTICAST)
        }
    }

    @Test
    fun generatedSizesMatchTheContract() {
        val identity = ReceiverIdentity.generate(SecureRandom())
        assertEquals(6, identity.deviceId.size)
        assertEquals(32, identity.identitySeed.size)
    }

    @Test
    fun deviceIdTextIsColonSeparatedUpperHex() {
        val identity =
            ReceiverIdentity(byteArrayOf(0x02, 0x1A, 0xFF.toByte(), 0x00, 0x7B, 0xC4.toByte()), ByteArray(32))
        assertEquals("02:1A:FF:00:7B:C4", identity.deviceIdText())
    }

    @Test
    fun encodeDecodeRoundTrips() {
        val identity = ReceiverIdentity.generate(SecureRandom())
        val decoded = ReceiverIdentity.decode(identity.encode())!!
        assertArrayEquals(identity.deviceId, decoded.deviceId)
        assertArrayEquals(identity.identitySeed, decoded.identitySeed)
    }

    @Test
    fun decodeRejectsTruncatedOversizedAndGarbage() {
        val encoded = ReceiverIdentity.generate(SecureRandom()).encode()
        assertNull(ReceiverIdentity.decode(encoded.copyOf(encoded.size - 1)))
        assertNull(ReceiverIdentity.decode(encoded.copyOf(encoded.size + 1)))
        assertNull(ReceiverIdentity.decode(ByteArray(0)))
        val random = java.util.Random(1)
        repeat(200) {
            val garbage = ByteArray(encoded.size).also(random::nextBytes)
            assertNull(ReceiverIdentity.decode(garbage))
        }
        val wrongVersion = encoded.copyOf().also { it[4] = 9 }
        assertNull(ReceiverIdentity.decode(wrongVersion))
        val multicast = encoded.copyOf().also { it[5] = (it[5].toInt() or 0x01).toByte() }
        assertNull(ReceiverIdentity.decode(multicast))
    }

    @Test
    fun storeCreatesOnceAndReloadsTheSameIdentity() {
        val file = File(temp.root, "airplay/identity")
        val first = IdentityStore(file).loadOrCreate()
        val second = IdentityStore(file).loadOrCreate()
        assertArrayEquals(first.deviceId, second.deviceId)
        assertArrayEquals(first.identitySeed, second.identitySeed)
        assertEquals(ReceiverIdentity.ENCODED_SIZE.toLong(), file.length())
    }

    @Test
    fun storedFileIsOwnerOnly() {
        val file = File(temp.root, "airplay/identity")
        IdentityStore(file).loadOrCreate()
        val permissions = Files.getPosixFilePermissions(file.toPath())
        assertEquals(setOf(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE), permissions)
        assertFalse(File(file.parentFile, "identity.tmp").exists())
    }

    @Test
    fun corruptFileIsReplacedWithAFreshIdentity() {
        val file = File(temp.root, "identity")
        file.writeBytes(ByteArray(ReceiverIdentity.ENCODED_SIZE) { 0x55 })
        val identity = IdentityStore(file).loadOrCreate()
        assertNotEquals(0x55.toByte(), identity.deviceId[1])
        assertArrayEquals(identity.encode(), file.readBytes())
    }
}
