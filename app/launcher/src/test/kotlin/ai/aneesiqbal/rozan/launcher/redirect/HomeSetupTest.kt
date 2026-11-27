package ai.aneesiqbal.rozan.launcher.redirect

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class HomeSetupTest {
    private val rozan = ActivityId("ai.aneesiqbal.rozan.launcher", "ai.aneesiqbal.rozan.launcher.HomeActivity")
    private val fireHome = ActivityId("com.amazon.tv.launcher", "com.amazon.tv.launcher.ui.HomeActivity_vNext")
    private val tvLauncher = ActivityId("com.google.android.tvlauncher", "com.google.android.tvlauncher.MainActivity")
    private val launcherX =
        ActivityId("com.google.android.apps.tv.launcherx", "com.google.android.apps.tv.launcherx.home.HomeActivity")

    @Test
    fun `Rozan as the resolved home is native mode`() {
        val setup = HomeSetup.decide(rozan, listOf(rozan), rozan)

        assertEquals(HomeMode.NATIVE, setup.mode)
        assertNull(setup.stockPackage)
        assertEquals(emptySet(), setup.stockHome)
    }

    @Test
    fun `a known stock launcher is redirect mode with exactly its home class`() {
        for (home in listOf(fireHome, tvLauncher, launcherX)) {
            val setup = HomeSetup.decide(home, listOf(home), rozan)

            assertEquals(HomeMode.REDIRECT, setup.mode)
            assertEquals(home.packageName, setup.stockPackage)
            assertEquals(setOf(home), setup.stockHome)
            assertEquals(emptySet(), setup.unrecognized)
        }
    }

    @Test
    fun `other HOME activities of the stock package join stockHome and are flagged as unrecognized`() {
        val kidsHome =
            ActivityId(launcherX.packageName, "com.google.android.apps.tv.launcherx.home.KidsDarkHomeActivity")

        val setup = HomeSetup.decide(launcherX, listOf(launcherX, kidsHome), rozan)

        assertEquals(setOf(launcherX, kidsHome), setup.stockHome)
        assertEquals(setOf(kidsHome), setup.unrecognized)
    }

    @Test
    fun `an unknown vendor home is used but shows up in diagnostics`() {
        val patchWall = ActivityId("com.mitv.tvhome", "com.mitv.tvhome.MainActivityUserMode")

        val setup = HomeSetup.decide(patchWall, emptyList(), rozan)

        assertEquals(HomeMode.REDIRECT, setup.mode)
        assertEquals(setOf(patchWall), setup.stockHome)
        assertEquals(setOf(patchWall), setup.unrecognized)
    }

    @Test
    fun `HOME activities of other packages and FallbackHome never join stockHome`() {
        val fallback = ActivityId("com.android.tv.settings", "com.android.tv.settings.system.FallbackHome")
        val ownFallback = ActivityId(tvLauncher.packageName, "com.google.android.tvlauncher.FallbackHome")

        val setup = HomeSetup.decide(tvLauncher, listOf(tvLauncher, fallback, ownFallback, rozan), rozan)

        assertEquals(setOf(tvLauncher), setup.stockHome)
    }

    @Test
    fun `homes that are never the home screen give no redirect`() {
        val notHomes =
            listOf(
                // The system's chooser when two HOME candidates tie.
                ActivityId("android", "com.android.internal.app.ResolverActivity"),
                ActivityId("com.android.tv.settings", "com.android.tv.settings.system.FallbackHome"),
                ActivityId("com.amazon.tv.settings.v2", "com.amazon.tv.settings.v2.system.FallbackHome"),
                ActivityId("com.amazon.firehomestarter", "com.amazon.firehomestarter.HomeStarterActivity"),
                ActivityId(
                    "com.google.android.tungsten.setupwraith",
                    "com.google.android.tungsten.setupwraith.RecoveryActivity",
                ),
                ActivityId("com.amazon.tv.oobe", "com.amazon.tv.oobe.OobeActivity"),
                // Amazon Kids home: Rozan must not offer a way out of a Kids profile.
                ActivityId("com.amazon.tahoe", "com.amazon.tahoe.ftv.HomeActivity"),
                // Another of Rozan's own activities.
                ActivityId(rozan.packageName, "ai.aneesiqbal.rozan.launcher.LauncherSettingsActivity"),
            )
        for (home in notHomes) {
            assertEquals(HomeSetup.NONE, HomeSetup.decide(home, listOf(home), rozan), "for $home")
        }
    }

    @Test
    fun `nothing resolved is no redirect`() {
        assertEquals(HomeSetup.NONE, HomeSetup.decide(null, emptyList(), rozan))
    }
}
