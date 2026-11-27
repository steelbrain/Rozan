package ai.aneesiqbal.rozan.launcher

import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import android.view.View
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.text.Collator

@RunWith(AndroidJUnit4::class)
class HomeScreenTest {
    @Before
    fun setUp() {
        resetLauncherState()
    }

    @Test
    fun builtInsComeFirstThenAppsAlphabetically() {
        launchHome().use { scenario ->
            val labels = scenario.read { it.tileLabels() }

            assertEquals(BUILT_IN_LABELS, labels.take(BUILT_IN_LABELS.size))
            val apps = labels.drop(BUILT_IN_LABELS.size)
            val collator = Collator.getInstance().apply { strength = Collator.SECONDARY }
            assertEquals("apps are in alphabetical order", apps.sortedWith(collator), apps)
            assertEquals(
                "one tile per launchable package",
                launchablePackages(),
                scenario.read { home ->
                    home.apps().map { it.packageName }.toSet()
                },
            )
            assertFalse(
                "the launcher does not list itself",
                scenario.read { home ->
                    home.apps().any {
                        it.packageName ==
                            PACKAGE
                    }
                },
            )
        }
    }

    @Test
    fun dpadMovesFocusAcrossTilesRowsAndTheSettingsButton() {
        launchHome().use { scenario ->
            assertEquals("Settings", scenario.focusedTileLabel())

            device.pressDPadRight()
            waitUntil("focus on Network") { scenario.focusedTileLabel() == "Network" }

            // Down from the second column lands on the second app, and the grid scrolls up.
            val secondApp = scenario.read { it.apps().getOrNull(1) ?: it.apps().first() }.label
            device.pressDPadDown()
            waitUntil("focus on $secondApp") { scenario.focusedTileLabel() == secondApp }
            waitUntil("grid scrolled up") { scenario.read { it.findViewById<View>(R.id.app_grid).translationY < 0f } }

            device.pressDPadUp()
            waitUntil("focus back on Network") { scenario.focusedTileLabel() == "Network" }
            waitUntil("grid back at rest") { scenario.read { it.findViewById<View>(R.id.app_grid).translationY == 0f } }

            device.pressDPadUp()
            waitUntil("focus on the settings button") { scenario.read { it.currentFocus?.id == R.id.settings_button } }

            device.pressDPadDown()
            waitUntil("focus returns to the last tile") { scenario.focusedTileLabel() == "Network" }

            // No wrap-around at the start of a row.
            device.pressDPadLeft()
            device.pressDPadLeft()
            waitUntil("focus on Settings") { scenario.focusedTileLabel() == "Settings" }
            assertTrue(scenario.read { it.hasWindowFocus() })
        }
    }

    @Test
    fun backReturnsToTheFirstTile() {
        launchHome().use { scenario ->
            device.pressDPadDown()
            device.pressDPadRight()
            waitUntil("focus left the first row") {
                scenario.read {
                    it.findViewById<View>(R.id.app_grid).translationY <
                        0f
                }
            }

            device.pressBack()

            waitUntil("focus on the first tile") { scenario.focusedTileLabel() == "Settings" }
            assertEquals(PACKAGE, device.currentPackageName)
        }
    }

    @Test
    fun theSettingsAppBehindTheSettingsTileIsNotListedAgain() {
        val settingsPackage = settingsTilePackage()
        launchHome().use { scenario ->
            val labels = scenario.read { it.tileLabels() }

            assertEquals("Settings appears once", 1, labels.count { it == "Settings" })
            if (settingsPackage != null) {
                assertFalse(scenario.read { home -> home.apps().any { it.packageName == settingsPackage } })
            }
        }
    }

    /** What the Settings tile opens off Fire OS: the package `android.settings.SETTINGS` resolves to. */
    private fun settingsTilePackage(): String? {
        val pm = targetContext.packageManager
        val intent = Intent(Settings.ACTION_SETTINGS)
        val resolved =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                pm.resolveActivity(
                    intent,
                    PackageManager.ResolveInfoFlags.of(PackageManager.MATCH_DEFAULT_ONLY.toLong()),
                )
            } else {
                // The int-flags overload is the only one before API 33.
                @Suppress("DEPRECATION")
                pm.resolveActivity(intent, PackageManager.MATCH_DEFAULT_ONLY)
            }
        return resolved?.activityInfo?.packageName
    }

    private fun launchablePackages(): Set<String> {
        val pm = targetContext.packageManager
        val settingsPackage = settingsTilePackage()
        return listOf(Intent.CATEGORY_LEANBACK_LAUNCHER, Intent.CATEGORY_LAUNCHER)
            .flatMap { category ->
                val intent = Intent(Intent.ACTION_MAIN).addCategory(category)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    pm.queryIntentActivities(intent, PackageManager.ResolveInfoFlags.of(0L))
                } else {
                    // The int-flags overload is the only one before API 33.
                    @Suppress("DEPRECATION")
                    pm.queryIntentActivities(intent, 0)
                }
            }.map { it.activityInfo.packageName }
            .filter { it != PACKAGE && it != settingsPackage }
            .toSet()
    }
}
