package ai.aneesiqbal.rozan.launcher.update

import org.junit.jupiter.api.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SameSignersTest {
    private val a = byteArrayOf(1, 2, 3)
    private val b = byteArrayOf(4, 5, 6)

    @Test
    fun `the same certificates match in any order`() {
        assertTrue(PackageVerifier.sameSigners(listOf(a), listOf(a.copyOf())))
        assertTrue(PackageVerifier.sameSigners(listOf(a, b), listOf(b, a)))
    }

    @Test
    fun `other, missing or extra certificates do not`() {
        assertFalse(PackageVerifier.sameSigners(listOf(a), listOf(b)))
        assertFalse(PackageVerifier.sameSigners(listOf(a), listOf(a, b)))
        assertFalse(PackageVerifier.sameSigners(listOf(a, b), listOf(a)))
        assertFalse(PackageVerifier.sameSigners(emptyList(), emptyList()))
        assertFalse(PackageVerifier.sameSigners(listOf(a, a), listOf(a, b)))
    }
}
