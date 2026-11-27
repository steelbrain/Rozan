package ai.aneesiqbal.rozan.launcher.apps

import org.junit.jupiter.api.Test
import java.util.Locale
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AppCatalogTest {
    private fun app(
        label: String,
        packageName: String = "pkg.${label.lowercase().filter(Char::isLetterOrDigit)}",
        activity: String = "Main",
        leanback: Boolean = true,
    ) = AppEntry(packageName, "$packageName.$activity", label, leanback, removable = true)

    @Test
    fun `sorts alphabetically ignoring case`() {
        val apps = listOf(app("youTube"), app("Apple TV"), app("netflix"), app("Kodi"), app("amazon Music"))

        val sorted = AppCatalog.merge(apps, emptyList(), setOf("self"), Locale.US)

        assertEquals(listOf("amazon Music", "Apple TV", "Kodi", "netflix", "youTube"), sorted.map { it.label })
    }

    @Test
    fun `accented letters sort with their base letter`() {
        val apps = listOf(app("Zattoo"), app("Écran"), app("Eurosport"), app("Dazn"))

        val sorted = AppCatalog.merge(apps, emptyList(), setOf("self"), Locale.FRANCE)

        assertEquals(listOf("Dazn", "Écran", "Eurosport", "Zattoo"), sorted.map { it.label })
    }

    @Test
    fun `collation follows the locale`() {
        val apps = listOf(app("Zebra"), app("Öresund"), app("Oslo"))

        assertEquals(
            listOf("Öresund", "Oslo", "Zebra"),
            AppCatalog.merge(apps, emptyList(), setOf("self"), Locale.US).map { it.label },
        )
        // Swedish sorts Ö after Z.
        assertEquals(
            listOf("Oslo", "Zebra", "Öresund"),
            AppCatalog.merge(apps, emptyList(), setOf("self"), Locale.forLanguageTag("sv-SE")).map { it.label },
        )
    }

    @Test
    fun `one entry per package, preferring the leanback activity`() {
        val tv = app("Player", packageName = "com.player", activity = "TvMain", leanback = true)
        val phone = app("Player Mobile", packageName = "com.player", activity = "PhoneMain", leanback = false)
        val other = app("Browser", packageName = "com.browser", leanback = false)

        val merged = AppCatalog.merge(listOf(tv), listOf(phone, other), setOf("self"), Locale.US)

        assertEquals(listOf("Browser", "Player"), merged.map { it.label })
        assertEquals("com.player.TvMain", merged.single { it.packageName == "com.player" }.activityName)
    }

    @Test
    fun `first leanback activity of a package wins over later ones`() {
        val first = app("Kodi", packageName = "org.kodi", activity = "First")
        val second = app("Kodi Extra", packageName = "org.kodi", activity = "Second")

        val merged = AppCatalog.merge(listOf(first, second), emptyList(), setOf("self"), Locale.US)

        assertEquals(listOf(first), merged)
    }

    @Test
    fun `excludes the launcher itself`() {
        val self = app("Rozan", packageName = "ai.aneesiqbal.rozan.launcher")
        val merged =
            AppCatalog.merge(
                listOf(self, app("Kodi")),
                listOf(self.copy(leanback = false)),
                setOf("ai.aneesiqbal.rozan.launcher"),
                Locale.US,
            )

        assertEquals(listOf("Kodi"), merged.map { it.label })
    }

    @Test
    fun `hides the settings app that the Settings tile opens, from both entry point lists`() {
        val tvSettings = app("Settings", packageName = "com.android.tv.settings")
        val phoneSettings = app("Settings", packageName = "com.android.settings").copy(leanback = false)
        val merged =
            AppCatalog.merge(
                listOf(tvSettings, app("Kodi")),
                listOf(phoneSettings, app("Browser", packageName = "com.browser").copy(leanback = false)),
                setOf("self", "com.android.tv.settings", "com.android.settings"),
                Locale.US,
            )

        assertEquals(listOf("Browser", "Kodi"), merged.map { it.label })
    }

    @Test
    fun `equal labels are ordered by package name`() {
        val b = app("Player", packageName = "com.b")
        val a = app("player", packageName = "com.a")

        val merged = AppCatalog.merge(listOf(b, a), emptyList(), setOf("self"), Locale.US)

        assertEquals(listOf("com.a", "com.b"), merged.map { it.packageName })
    }

    @Test
    fun `blank labels fall back to the package name and labels are trimmed`() {
        val blank = app("   ", packageName = "com.zzz")
        val padded = app("  Kodi ", packageName = "org.kodi")

        val merged = AppCatalog.merge(listOf(blank, padded), emptyList(), setOf("self"), Locale.US)

        assertEquals(listOf("com.zzz", "Kodi"), merged.map { it.label })
    }

    @Test
    fun `empty input gives an empty list`() {
        assertTrue(AppCatalog.merge(emptyList(), emptyList(), setOf("self"), Locale.US).isEmpty())
    }
}
