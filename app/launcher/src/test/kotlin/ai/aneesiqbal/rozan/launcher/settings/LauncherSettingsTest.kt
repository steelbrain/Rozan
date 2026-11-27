package ai.aneesiqbal.rozan.launcher.settings

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LauncherSettingsTest {
    private val prefs = FakeSharedPreferences()
    private val settings = LauncherSettings(prefs)

    @AfterEach
    fun resetHook() {
        LauncherSettings.screensaverDelayOverrideMs = null
    }

    @Test
    fun `defaults`() {
        assertTrue(settings.keepScreenOn)
        assertEquals(5, settings.screensaverMinutes)
        assertEquals(10, settings.backdropIntervalMinutes)
        assertEquals(5 * 60_000L, settings.screensaverDelayMs)
        assertEquals(10 * 60_000L, settings.backdropIntervalMs)
    }

    @Test
    fun `changes persist in the preferences`() {
        settings.keepScreenOn = false
        settings.screensaverMinutes = 15
        settings.backdropIntervalMinutes = 30

        val reread = LauncherSettings(prefs)
        assertFalse(reread.keepScreenOn)
        assertEquals(15, reread.screensaverMinutes)
        assertEquals(30, reread.backdropIntervalMinutes)
    }

    @Test
    fun `screensaver is off while keep awake is off`() {
        settings.screensaverMinutes = 2
        settings.keepScreenOn = false

        assertEquals(0L, settings.screensaverDelayMs)
    }

    @Test
    fun `stored values outside the choices read as the default`() {
        prefs
            .edit()
            .putInt(LauncherSettings.KEY_SCREENSAVER_MINUTES, 7)
            .putInt(LauncherSettings.KEY_BACKDROP_INTERVAL_MINUTES, -1)
            .apply()

        assertEquals(LauncherSettings.DEFAULT_SCREENSAVER_MINUTES, settings.screensaverMinutes)
        assertEquals(LauncherSettings.DEFAULT_BACKDROP_INTERVAL_MINUTES, settings.backdropIntervalMinutes)
    }

    @Test
    fun `setters reject values outside the choices`() {
        assertFailsWith<IllegalArgumentException> { settings.screensaverMinutes = 3 }
        assertFailsWith<IllegalArgumentException> { settings.backdropIntervalMinutes = 0 }
        assertEquals(5, settings.screensaverMinutes)
    }

    @Test
    fun `defaults are among the choices`() {
        assertTrue(LauncherSettings.DEFAULT_SCREENSAVER_MINUTES in LauncherSettings.SCREENSAVER_CHOICES)
        assertTrue(LauncherSettings.DEFAULT_BACKDROP_INTERVAL_MINUTES in LauncherSettings.BACKDROP_INTERVAL_CHOICES)
        assertEquals(listOf(2, 5, 10, 15, 30), LauncherSettings.SCREENSAVER_CHOICES)
    }

    @Test
    fun `test hook overrides the screensaver delay only while keep awake is on`() {
        LauncherSettings.screensaverDelayOverrideMs = 1_500L

        assertEquals(1_500L, settings.screensaverDelayMs)
        settings.keepScreenOn = false
        assertEquals(0L, settings.screensaverDelayMs)
    }
}
