package ai.aneesiqbal.rozan.launcher.backdrop

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Runs on a device because the parser uses the platform's org.json. */
@RunWith(AndroidJUnit4::class)
class BackdropManifestTest {
    @Test
    fun parsesAllFields() {
        val images =
            BackdropManifest.parse(
                """[{"file":"a.jpg","title":"Lake","author":"Ann","license":"CC0","sourceUrl":"https://example.org/a"},
                   {"file":"b.jpg","title":"Hill","author":"Bo","license":"Public domain","sourceUrl":""}]""",
            )

        assertEquals(
            listOf(
                BackdropImage("a.jpg", "Lake", "Ann", "CC0", "https://example.org/a"),
                BackdropImage("b.jpg", "Hill", "Bo", "Public domain", ""),
            ),
            images,
        )
    }

    @Test
    fun missingOptionalFieldsAndNullsBecomeEmpty() {
        val images = BackdropManifest.parse("""[{"file":"a.jpg","title":null}]""")

        assertEquals(listOf(BackdropImage("a.jpg", "", "", "", "")), images)
    }

    @Test
    fun skipsEntriesWithoutAUsableFileName() {
        val images =
            BackdropManifest.parse(
                """[{"title":"no file"}, {"file":""}, {"file":"../etc/passwd"}, {"file":"sub/dir.jpg"},
                   {"file":".."}, "not an object", 42, {"file":"ok.jpg"}]""",
            )

        assertEquals(listOf("ok.jpg"), images.map { it.file })
    }

    @Test
    fun malformedOrTruncatedJsonGivesNothing() {
        assertTrue(BackdropManifest.parse("").isEmpty())
        assertTrue(BackdropManifest.parse("""[{"file":"a.jpg","""").isEmpty())
        assertTrue(BackdropManifest.parse("""{"file":"a.jpg"}""").isEmpty())
        assertTrue(BackdropManifest.parse("\u0000\u0001garbage").isEmpty())
    }

    @Test
    fun capsTheNumberOfEntries() {
        val json = (0 until BackdropManifest.MAX_ENTRIES + 50).joinToString(",", "[", "]") { """{"file":"$it.jpg"}""" }

        assertEquals(BackdropManifest.MAX_ENTRIES, BackdropManifest.parse(json).size)
    }

    @Test
    fun fileNameRules() {
        assertTrue(BackdropManifest.isSafeFileName("photo-01.jpg"))
        assertFalse(BackdropManifest.isSafeFileName("a/b.jpg"))
        assertFalse(BackdropManifest.isSafeFileName("a\\b.jpg"))
        assertFalse(BackdropManifest.isSafeFileName("."))
        assertFalse(BackdropManifest.isSafeFileName("bad\nname.jpg"))
        assertFalse(BackdropManifest.isSafeFileName("x".repeat(201)))
    }
}
