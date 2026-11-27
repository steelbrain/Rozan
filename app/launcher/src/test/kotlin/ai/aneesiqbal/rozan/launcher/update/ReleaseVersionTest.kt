package ai.aneesiqbal.rozan.launcher.update

import org.junit.jupiter.api.Test
import kotlin.random.Random
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ReleaseVersionTest {
    @Test
    fun `versionCode is major times a million plus minor times a thousand plus patch`() {
        assertEquals(1_000L, ReleaseVersion(0, 1, 0).code)
        assertEquals(1_002_003L, ReleaseVersion(1, 2, 3).code)
        assertEquals(999_999_999L, ReleaseVersion(999, 999, 999).code)
        assertEquals("1.2.3", ReleaseVersion(1, 2, 3).name)
    }

    @Test
    fun `parses tags and names`() {
        assertEquals(ReleaseVersion(0, 1, 0), ReleaseVersion.parseTag("v0.1.0"))
        assertEquals(ReleaseVersion(12, 0, 999), ReleaseVersion.parseTag("v12.0.999"))
        assertEquals(ReleaseVersion(1, 2, 3), ReleaseVersion.parse("1.2.3"))
        assertEquals(ReleaseVersion(1, 2, 3), ReleaseVersion.parse("001.02.3"))
    }

    @Test
    fun `refuses anything else`() {
        val bad =
            listOf(
                "",
                "v",
                "0.1.0",
                "v0.1",
                "v0.1.0.0",
                "v0..1",
                "v.1.0",
                "v0.1.",
                "v1000.0.0",
                "v0.1000.0",
                "v0.0.1000",
                "v-1.0.0",
                "v+1.0.0",
                "v1.0.0-rc.1",
                "v1.0.0+build",
                "v 1.0.0",
                "v1.0.0 ",
                "V1.0.0",
                "v1.0.٣",
                "v" + "1".repeat(ReleaseVersion.MAX_TAG_LENGTH),
            )
        for (tag in bad) assertNull(ReleaseVersion.parseTag(tag), tag)
    }

    @Test
    fun `orders by versionCode`() {
        assertTrue(ReleaseVersion(0, 1, 1) > ReleaseVersion(0, 1, 0))
        assertTrue(ReleaseVersion(0, 2, 0) > ReleaseVersion(0, 1, 999))
        assertTrue(ReleaseVersion(1, 0, 0) > ReleaseVersion(0, 999, 999))
        assertEquals(0, ReleaseVersion(1, 2, 3).compareTo(ReleaseVersion(1, 2, 3)))
    }

    @Test
    fun `garbage never throws`() {
        for (seed in 0 until 2_000) {
            val random = Random(seed)
            val text = String(CharArray(random.nextInt(0, 16)) { "v0123456789.-+ x".random(random) })
            ReleaseVersion.parseTag(text)
        }
    }
}
