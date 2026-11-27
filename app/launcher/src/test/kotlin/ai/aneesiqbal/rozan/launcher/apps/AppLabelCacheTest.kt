package ai.aneesiqbal.rozan.launcher.apps

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.File
import kotlin.random.Random
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AppLabelCacheTest {
    private val entries =
        listOf(
            AppLabelCache.Entry("com.example.tv/.Main", 42L, 1_700_000_000_000L, "Example TV"),
            AppLabelCache.Entry("org.kodi/org.kodi.Splash", 21_000L, 1_650_000_000_000L, "Kodi"),
            AppLabelCache.Entry("de.zattoo/.Start", 1L, 2L, "Écran ✓ 日本語"),
        )

    /** Every field of the entries in [map] (Entry has no value equality). */
    private fun fields(map: Map<String, AppLabelCache.Entry>): Map<String, List<Any>> =
        map.mapValues { (_, entry) -> listOf(entry.key, entry.versionCode, entry.updatedAt, entry.label) }

    @Test
    fun `entries read back as written, for the same locale`(
        @TempDir dir: File,
    ) {
        val cache = AppLabelCache(File(dir, "apps/labels"))
        cache.write("en-US", entries)

        assertEquals(fields(entries.associateBy { it.key }), fields(cache.read("en-US")))
    }

    @Test
    fun `another locale reads nothing`(
        @TempDir dir: File,
    ) {
        val cache = AppLabelCache(File(dir, "labels"))
        cache.write("en-US", entries)

        assertTrue(cache.read("de-DE").isEmpty())
    }

    @Test
    fun `a missing file reads nothing`(
        @TempDir dir: File,
    ) {
        assertTrue(AppLabelCache(File(dir, "labels")).read("en-US").isEmpty())
    }

    @Test
    fun `a rewrite replaces every entry`(
        @TempDir dir: File,
    ) {
        val cache = AppLabelCache(File(dir, "labels"))
        cache.write("en-US", entries)
        cache.write("en-US", entries.take(1))

        assertEquals(fields(entries.take(1).associateBy { it.key }), fields(cache.read("en-US")))
        assertEquals(listOf("labels"), dir.list()?.toList())
    }

    @Test
    fun `every truncation of a valid file is rejected`() {
        val bytes = assertNotNull(AppLabelCache.encode("en-US", entries))
        assertNotNull(AppLabelCache.parse(bytes, "en-US"))
        for (length in 0 until bytes.size) {
            assertNull(AppLabelCache.parse(bytes.copyOf(length), "en-US"), "truncated to $length bytes")
        }
    }

    @Test
    fun `trailing bytes are rejected`() {
        val bytes = assertNotNull(AppLabelCache.encode("en-US", entries))

        assertNull(AppLabelCache.parse(bytes + 0, "en-US"))
    }

    @Test
    fun `an oversized entry count is rejected without allocating for it`() {
        for (count in listOf(Int.MAX_VALUE, -1, 2049)) {
            val bytes =
                ByteArrayOutputStream().also { out ->
                    DataOutputStream(out).use {
                        it.writeInt(MAGIC)
                        it.writeUTF("en-US")
                        it.writeInt(count)
                    }
                }
            assertNull(AppLabelCache.parse(bytes.toByteArray(), "en-US"), "count $count")
        }
    }

    @Test
    fun `oversized keys and labels are neither written nor read`(
        @TempDir dir: File,
    ) {
        val long = "x".repeat(513)
        assertNull(AppLabelCache.encode("en-US", listOf(AppLabelCache.Entry(long, 1, 1, "Label"))))
        assertNull(AppLabelCache.encode("en-US", listOf(AppLabelCache.Entry("a/b", 1, 1, long))))
        val bytes =
            ByteArrayOutputStream().also { out ->
                DataOutputStream(out).use {
                    it.writeInt(MAGIC)
                    it.writeUTF("en-US")
                    it.writeInt(1)
                    it.writeUTF("a/b")
                    it.writeLong(1)
                    it.writeLong(1)
                    it.writeUTF(long)
                }
            }
        assertNull(AppLabelCache.parse(bytes.toByteArray(), "en-US"))
        val file = File(dir, "labels")
        file.writeBytes(bytes.toByteArray())
        assertTrue(AppLabelCache(file).read("en-US").isEmpty())
    }

    @Test
    fun `a file larger than the bound is not read`(
        @TempDir dir: File,
    ) {
        val file = File(dir, "labels")
        file.writeBytes(ByteArray(256 * 1024 + 1))

        assertTrue(AppLabelCache(file).read("en-US").isEmpty())
    }

    @Test
    fun `random garbage is rejected`() {
        val random = Random(7)
        repeat(2_000) {
            val bytes = random.nextBytes(random.nextInt(0, 512))
            assertNull(AppLabelCache.parse(bytes, "en-US"))
        }
        // Garbage behind a valid header.
        val header =
            ByteArrayOutputStream().also { out ->
                DataOutputStream(out).use {
                    it.writeInt(MAGIC)
                    it.writeUTF("en-US")
                }
            }
        repeat(2_000) {
            val bytes = header.toByteArray() + random.nextBytes(random.nextInt(0, 512))
            // Must not throw; a parse that succeeds by chance still has bounded entries.
            AppLabelCache.parse(bytes, "en-US")?.let { assertTrue(it.size <= 2048) }
        }
    }

    private companion object {
        const val MAGIC = 0x525A4C31
    }
}
