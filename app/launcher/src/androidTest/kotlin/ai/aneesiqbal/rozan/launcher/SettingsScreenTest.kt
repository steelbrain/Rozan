package ai.aneesiqbal.rozan.launcher

import ai.aneesiqbal.rozan.launcher.backdrop.BackdropSource
import ai.aneesiqbal.rozan.launcher.notices.OpenSourceNotices
import ai.aneesiqbal.rozan.launcher.redirect.HomeMode
import ai.aneesiqbal.rozan.launcher.redirect.HomeSetupReader
import ai.aneesiqbal.rozan.launcher.redirect.RedirectClient
import ai.aneesiqbal.rozan.launcher.settings.AirPlaySection
import ai.aneesiqbal.rozan.launcher.settings.LauncherSettings
import ai.aneesiqbal.rozan.launcher.settings.STANDALONE_AIRPLAY_PACKAGE
import ai.aneesiqbal.rozan.launcher.settings.SettingsList
import ai.aneesiqbal.rozan.ui.SettingsRowView
import ai.aneesiqbal.rozan.ui.SettingsWarningView
import android.content.Intent
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.view.children
import androidx.core.view.isVisible
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Until
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeFalse
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.text.Collator
import java.util.Locale

@RunWith(AndroidJUnit4::class)
class SettingsScreenTest {
    private val settings get() = LauncherSettings(targetContext)

    @Before
    fun setUp() {
        resetLauncherState()
    }

    @After
    fun tearDown() {
        resetLauncherState()
    }

    @Test
    fun gearOpensSettingsAndChangesPersist() {
        launchHome().use { scenario ->
            device.pressDPadUp()
            waitUntil("focus on the settings button") { scenario.read { it.currentFocus?.id == R.id.settings_button } }
            device.pressDPadCenter()
            assertTrue("settings opened", device.wait(Until.hasObject(By.res(PACKAGE, "settings_list")), TIMEOUT))

            // The first row has focus: toggle "Keep screen awake" off.
            val keepAwakeOn = device.findObject(By.desc("Keep screen awake, On"))
            assertTrue("keep-awake row is focused", keepAwakeOn.isFocused)
            device.pressDPadCenter()
            waitUntil("keep awake saved as off") { !settings.keepScreenOn }
            assertTrue(device.wait(Until.hasObject(By.desc("Keep screen awake, Off")), TIMEOUT))
            assertEquals(0L, settings.screensaverDelayMs)

            // The screensaver delay row is disabled now, so Down skips to the backdrop interval.
            device.pressDPadDown()
            assertTrue(
                device.wait(Until.hasObject(By.desc("Change backdrop every, 10 minutes").focused(true)), TIMEOUT),
            )
            device.pressDPadRight()
            waitUntil("backdrop interval saved") { settings.backdropIntervalMinutes == 15 }
            device.pressDPadCenter()
            waitUntil("backdrop interval cycles on OK") { settings.backdropIntervalMinutes == 30 }

            // Back to home, then open settings again: the values were persisted.
            device.pressBack()
            assertTrue("home is back", device.wait(Until.hasObject(By.res(PACKAGE, "app_grid")), TIMEOUT))
            waitUntil("settings button focused again") { scenario.read { it.currentFocus?.id == R.id.settings_button } }
            device.pressDPadCenter()
            assertTrue(device.wait(Until.hasObject(By.res(PACKAGE, "settings_list")), TIMEOUT))
            assertTrue(device.wait(Until.hasObject(By.desc("Keep screen awake, Off")), TIMEOUT))
            assertTrue(device.hasObject(By.desc("Change backdrop every, 30 minutes")))
            assertFalse(settings.keepScreenOn)
            device.pressBack()
        }
    }

    @Test
    fun airPlayTileOpensOnlyTheAirPlaySettings() {
        launchHome().use { scenario ->
            device.pressDPadRight()
            device.pressDPadRight()
            waitUntil("focus on AirPlay") { scenario.focusedTileLabel() == "AirPlay" }
            device.pressDPadCenter()
            assertTrue(device.wait(Until.hasObject(By.res(PACKAGE, "settings_heading").text("AirPlay")), TIMEOUT))
            assertTrue(
                "AirPlay toggle is focused",
                device.wait(Until.hasObject(By.descStartsWith("Enable AirPlay").focused(true)), TIMEOUT),
            )
            assertTrue(device.hasObject(By.descStartsWith("Wake TV when someone connects")))
            assertTrue(device.hasObject(By.descStartsWith("Show over other apps")))
            if (!AirPlaySection.isInstalled(targetContext, STANDALONE_AIRPLAY_PACKAGE)) {
                assertFalse("no Rozan AirPlay warning", device.hasObject(By.descContains(STANDALONE_WARNING)))
            }
            // Only the AirPlay rows: nothing from the other sections of the launcher settings.
            assertFalse(device.hasObject(By.descStartsWith("Keep screen awake")))
            assertFalse(device.hasObject(By.descStartsWith("Language")))
            assertFalse(device.hasObject(By.text("Home screen")))
            device.pressBack()
            assertTrue("home is back", device.wait(Until.hasObject(By.res(PACKAGE, "app_grid")), TIMEOUT))
        }
    }

    @Test
    fun airPlayWarnsOnlyWhileTheStandaloneAppIsInstalled() {
        ActivityScenario.launch(AirPlaySettingsActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                // This test's own package stands in for an installed Rozan AirPlay.
                fun warning(standaloneApp: String): SettingsWarningView {
                    val list = LinearLayout(activity)
                    AirPlaySection(activity, SettingsList(list), withHeader = false, standaloneApp = standaloneApp)
                    val first = list.getChildAt(0)
                    assertTrue("the warning comes first", first is SettingsWarningView)
                    return first as SettingsWarningView
                }
                val installed = warning(activity.packageName)
                assertTrue("shown while installed", installed.isVisible)
                assertEquals(STANDALONE_WARNING, installed.title.toString())
                assertEquals("Warning, $STANDALONE_WARNING", installed.contentDescription?.toString())
                assertEquals(
                    "You don't need it. Rozan has AirPlay built in, and it works without the separate app. While " +
                        "both are installed, only one of them can receive. Select to uninstall the Rozan AirPlay app.",
                    installed.summary?.toString(),
                )
                assertFalse("hidden while not installed", warning("$PACKAGE.not.installed").isVisible)
            }
        }
    }

    @Test
    fun aboutOpensTheBackdropCredits() {
        val images = BackdropSource(targetContext).loadImages()
        assertTrue("photos are bundled", images.isNotEmpty())

        ActivityScenario.launch(LauncherSettingsActivity::class.java).use { scenario ->
            var credits: SettingsRowView? = null
            var sourceCode: SettingsRowView? = null
            scenario.onActivity { activity ->
                val rows =
                    activity
                        .findViewById<ViewGroup>(
                            R.id.settings_list,
                        ).children
                        .filterIsInstance<SettingsRowView>()
                assertTrue(
                    "no photo credited inline",
                    rows.none { row ->
                        images.any { it.title == row.title.toString() }
                    },
                )
                credits = rows.single { it.title == "Backdrop photos" }
                sourceCode = rows.single { it.title == "Source code" }
            }
            assertEquals("Author, license and source of each photo", credits?.summary?.toString())
            assertEquals("https://github.com/steelbrain/Rozan", sourceCode?.value?.toString())
            scenario.onActivity { credits?.performClick() }
            assertTrue(
                "credits opened",
                device.wait(Until.hasObject(By.res(PACKAGE, "settings_heading").text("Backdrop photos")), TIMEOUT),
            )
            device.pressBack()
        }
    }

    @Test
    fun backdropCreditsCreditEveryBundledPhoto() {
        val images = BackdropSource(targetContext).loadImages()
        assertTrue("photos are bundled", images.isNotEmpty())

        ActivityScenario.launch(BackdropCreditsActivity::class.java).use { scenario ->
            fun creditRows(): Map<String, String> {
                var rows = emptyMap<String, String>()
                scenario.onActivity { activity ->
                    val list = activity.findViewById<ViewGroup>(R.id.settings_list)
                    rows =
                        list.children
                            .filterIsInstance<SettingsRowView>()
                            .associate { it.title.toString() to it.summary.toString() }
                }
                return rows
            }
            waitUntil("credits loaded") { images.first().title in creditRows() }

            val rows = creditRows()
            assertEquals("one row per photo", images.size, rows.size)
            val alphabetical = images.map { it.title }.sortedWith(Collator.getInstance(Locale.ENGLISH))
            assertEquals("alphabetical", alphabetical, rows.keys.toList())
            for (image in images) {
                val summary = checkNotNull(rows[image.title]) { "no credit row for ${image.file}" }
                assertTrue("${image.file} credits its author", summary.contains(image.author))
                assertTrue("${image.file} credits its license", summary.contains(image.license))
            }
            assertTrue(
                "the first credit has focus",
                device.hasObject(By.descStartsWith(alphabetical.first()).focused(true)),
            )
        }
    }

    @Test
    fun openSourceNoticesListEveryLibraryAndOpenItsLicense() {
        ActivityScenario.launch(OpenSourceNoticesActivity::class.java).use { scenario ->
            var rows = emptyList<SettingsRowView>()
            scenario.onActivity { activity ->
                rows =
                    activity
                        .findViewById<ViewGroup>(
                            R.id.settings_list,
                        ).children
                        .filterIsInstance<SettingsRowView>()
                        .toList()
            }
            val names = OpenSourceNotices.LIBRARIES.map { it.name }
            assertEquals(names.sortedWith(Collator.getInstance(Locale.ENGLISH)), rows.map { it.title.toString() })
            assertTrue(
                "the first library has focus",
                device.hasObject(By.descStartsWith(rows.first().title.toString()).focused(true)),
            )

            val androidX = rows.single { it.title == "AndroidX" }
            assertEquals("The Android Open Source Project · Apache License 2.0", androidX.summary?.toString())
            scenario.onActivity { androidX.performClick() }
            assertTrue(device.wait(Until.hasObject(By.res(PACKAGE, "notices_heading").text("AndroidX")), TIMEOUT))
            assertTrue(device.hasObject(By.res(PACKAGE, "notices_text").textContains("TERMS AND CONDITIONS FOR USE")))
            assertTrue(device.hasObject(By.res(PACKAGE, "notices_about").textStartsWith("annotation, arch-core")))
            device.pressBack()

            val bouncyCastle = rows.single { it.title == "Bouncy Castle Crypto APIs for Java" }
            scenario.onActivity { bouncyCastle.performClick() }
            assertTrue(
                device.wait(
                    Until.hasObject(
                        By.res(PACKAGE, "notices_text").textContains("The Legion of the Bouncy Castle Inc."),
                    ),
                    TIMEOUT,
                ),
            )
            device.pressBack()
        }
    }

    @Test
    fun homeButtonRowShowsHowToTurnTheRedirectOn() {
        assumeTrue("Home opens another launcher", HomeSetupReader.read(targetContext).mode == HomeMode.REDIRECT)
        assumeFalse("the redirect is off", RedirectClient.isEnabled(targetContext))
        val intent =
            Intent(targetContext, LauncherSettingsActivity::class.java)
                .putExtra(LauncherSettingsActivity.EXTRA_SECTION, LauncherSettingsActivity.SECTION_HOME_BUTTON)
        ActivityScenario.launch<LauncherSettingsActivity>(intent).use {
            assertTrue(device.wait(Until.hasObject(By.desc("Home button, Off").focused(true)), TIMEOUT))
            val service = "$PACKAGE/.redirect.HomeRedirectService"
            val append = "settings put secure enabled_accessibility_services LIST:$service"
            assertTrue("the adb commands are shown", device.hasObject(By.textContains(append)))
            assertTrue(device.hasObject(By.desc("Open the stock home screen")))
        }
    }

    @Test
    fun showOverOtherAppsFollowsTheAppOp() {
        val command = "adb shell appops set $PACKAGE SYSTEM_ALERT_WINDOW allow"
        shell("appops set $PACKAGE SYSTEM_ALERT_WINDOW default")
        try {
            ActivityScenario.launch(LauncherSettingsActivity::class.java).use { scenario ->
                waitUntil("not allowed, with the command") {
                    overlayState(scenario) == "Not allowed" to true
                }
                assertTrue(overlayCommandShown(scenario, command))

                shell("appops set $PACKAGE SYSTEM_ALERT_WINDOW allow")
                waitUntil("allowed, without the command") { overlayState(scenario) == "Allowed" to false }
            }
        } finally {
            shell("appops set $PACKAGE SYSTEM_ALERT_WINDOW default")
        }
    }

    /** The "Show over other apps" row's value, and whether the adb command below it is shown. */
    private fun overlayState(scenario: ActivityScenario<LauncherSettingsActivity>): Pair<String?, Boolean> {
        var state: Pair<String?, Boolean> = null to false
        scenario.onActivity { activity ->
            val list = activity.findViewById<ViewGroup>(R.id.settings_list)
            val row = list.children.filterIsInstance<SettingsRowView>().first { it.title == "Show over other apps" }
            val command = list.getChildAt(list.indexOfChild(row) + 1)
            state = row.value?.toString() to command.isVisible
        }
        return state
    }

    private fun overlayCommandShown(
        scenario: ActivityScenario<LauncherSettingsActivity>,
        command: String,
    ): Boolean {
        var shown = false
        scenario.onActivity { activity ->
            val list = activity.findViewById<ViewGroup>(R.id.settings_list)
            shown =
                list.children.filterIsInstance<TextView>().any { it.text.toString() == command && it.isVisible }
        }
        return shown
    }

    private companion object {
        const val STANDALONE_WARNING = "A separate Rozan AirPlay app is installed"
        const val TIMEOUT = 5_000L
    }
}
