package ai.aneesiqbal.rozan.launcher.redirect

import ai.aneesiqbal.rozan.launcher.LauncherSettingsActivity
import ai.aneesiqbal.rozan.launcher.PACKAGE
import ai.aneesiqbal.rozan.launcher.device
import ai.aneesiqbal.rozan.launcher.inputs.FakePickerResolver
import ai.aneesiqbal.rozan.launcher.inputs.FakeTvInputSource
import ai.aneesiqbal.rozan.launcher.inputs.TvInputSources
import ai.aneesiqbal.rozan.launcher.inputs.focusInputsButton
import ai.aneesiqbal.rozan.launcher.inputs.inputsButtonVisible
import ai.aneesiqbal.rozan.launcher.instrumentation
import ai.aneesiqbal.rozan.launcher.launchHome
import ai.aneesiqbal.rozan.launcher.resetLauncherState
import ai.aneesiqbal.rozan.launcher.shell
import ai.aneesiqbal.rozan.launcher.targetContext
import ai.aneesiqbal.rozan.launcher.waitUntil
import android.app.Activity
import android.content.ComponentName
import android.content.Intent
import android.os.Bundle
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Until
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The Home button redirect on a real device: the service is enabled the way
 * the docs tell users to (appended to `enabled_accessibility_services`) and
 * restored afterwards. The emulator's own launcher is a real stock home;
 * everything else uses stand-ins from the test APK (see FakeActivities.kt).
 */
@RunWith(AndroidJUnit4::class)
class HomeRedirectTest {
    private val testPackage: String get() = instrumentation.context.packageName
    private var savedServices: String? = null
    private var savedEnabled: String? = null

    @Before
    fun setUp() {
        resetLauncherState()
        // A known window in front that is not a stock home, so connecting the service starts no probe.
        start(FakeAppActivity::class.java)
        waitUntil("the fake app is in front") { device.currentPackageName == testPackage }
        savedServices = secureSetting(ENABLED_SERVICES)
        savedEnabled = secureSetting(ACCESSIBILITY_ENABLED)
        val service = RedirectClient.serviceComponent(targetContext).flattenToShortString()
        val saved = savedServices
        val services = if (saved.isNullOrEmpty()) service else "$saved:$service"
        shell("settings put secure $ENABLED_SERVICES $services")
        shell("settings put secure $ACCESSIBILITY_ENABLED 1")
        waitUntil("the redirect service is connected", SERVICE_TIMEOUT) { status()?.running == true }
    }

    @After
    fun tearDown() {
        useStandIn(null)
        restore(ENABLED_SERVICES, savedServices)
        restore(ACCESSIBILITY_ENABLED, savedEnabled)
        waitUntil("the redirect service is gone", SERVICE_TIMEOUT) { status()?.running != true }
        resetLauncherState()
    }

    @Test
    fun homeFromAnAppOpensRozanInsteadOfTheEmulatorsLauncher() {
        assumeTrue("Home opens another launcher", HomeSetupReader.read(targetContext).mode == HomeMode.REDIRECT)
        useStandIn(null)

        device.pressHome()

        assertTrue("Rozan's home is in front", device.wait(Until.hasObject(By.res(PACKAGE, "app_grid")), TIMEOUT))
        val rozanHome = HomeSetupReader.rozanHome(targetContext).toString()
        waitUntil("the service saw Rozan's home") { status()?.lastActivity == rozanHome }
        assertEquals("exactly one redirect", 1, status()?.redirects)
    }

    @Test
    fun otherScreensOfTheStockLauncherPackageAreLeftAlone() {
        useStandIn(FakeStockHomeActivity::class.java)

        start(FakeStockSettingsActivity::class.java)
        waitUntil("the service saw the stock package's other screen") {
            status()?.lastActivity == id(FakeStockSettingsActivity::class.java)
        }
        assertEquals("no redirect", 0, status()?.redirects)
        assertEquals("still in front", testPackage, device.currentPackageName)

        // The stock home activity itself is redirected.
        start(FakeStockHomeActivity::class.java)
        assertTrue("Rozan's home is in front", device.wait(Until.hasObject(By.res(PACKAGE, "app_grid")), TIMEOUT))
        assertEquals("one redirect", 1, status()?.redirects)
    }

    @Test
    fun openTheStockHomeScreenFromSettingsStaysThere() {
        useStandIn(FakeStockHomeActivity::class.java)
        val intent =
            Intent(targetContext, LauncherSettingsActivity::class.java)
                .putExtra(LauncherSettingsActivity.EXTRA_SECTION, LauncherSettingsActivity.SECTION_HOME_BUTTON)
        ActivityScenario.launch<LauncherSettingsActivity>(intent).use {
            assertTrue(
                "the Home button row says On",
                device.wait(Until.hasObject(By.desc("Home button, On").focused(true)), TIMEOUT),
            )
            device.pressDPadDown()
            assertTrue(device.wait(Until.hasObject(By.desc("Open the stock home screen").focused(true)), TIMEOUT))

            device.pressDPadCenter()

            waitUntil(
                "the service saw the stock home",
            ) { status()?.lastWindow == id(FakeStockHomeActivity::class.java) }
            assertTrue("the choice is recorded", status()?.userStay == true)
            assertEquals("no redirect", 0, status()?.redirects)
            assertEquals("the stock home stays in front", testPackage, device.currentPackageName)
        }
    }

    @Test
    fun aCrashReportStopsRedirectsUntilRozanIsOpened() {
        useStandIn(FakeStockHomeActivity::class.java)

        RedirectClient.reportCrash(targetContext)
        assertTrue("the breaker tripped", status()?.tripped == true)
        start(FakeStockHomeActivity::class.java)
        waitUntil("the service saw the stock home") { status()?.lastWindow == id(FakeStockHomeActivity::class.java) }
        assertEquals("no redirect", 0, status()?.redirects)
        assertEquals("the stock home stays in front", testPackage, device.currentPackageName)

        launchHome().use { waitUntil("opening Rozan resets the breaker") { status()?.tripped == false } }
        assertFalse(status()?.tripped == true)
    }

    @Test
    fun aPickerThatTheInputsButtonOpensInsideTheStockHomeIsLeftAlone() {
        // As on Fire OS 8, where the inputs view is drawn by the stock home activity itself.
        useStandIn(FakeStockHomeActivity::class.java)
        TvInputSources.testSource = FakeTvInputSource(emptyList())
        TvInputSources.testFireOs = true
        TvInputSources.testPickerResolver =
            FakePickerResolver(listOf(ComponentName(testPackage, FakeStockHomeActivity::class.java.name)))
        try {
            launchHome().use { scenario ->
                waitUntil("button shown") { scenario.inputsButtonVisible }
                scenario.focusInputsButton()

                device.pressDPadCenter()

                waitUntil("the service saw the stock home") {
                    status()?.lastWindow == id(FakeStockHomeActivity::class.java)
                }
                assertEquals("no redirect", 0, status()?.redirects)
                assertEquals("the picker stays in front", testPackage, device.currentPackageName)
            }
        } finally {
            TvInputSources.testSource = null
            TvInputSources.testPickerResolver = null
            TvInputSources.testFireOs = null
        }
    }

    private fun status(): RedirectClient.Status? = RedirectClient.status(targetContext)

    private fun id(activity: Class<out Activity>): String = ActivityId(testPackage, activity.name).toString()

    /** Starts a test APK activity from the shell, which may start activities from the background. */
    private fun start(activity: Class<out Activity>) {
        shell("am start -W -n $testPackage/${activity.name}")
    }

    /** Points the redirect at [stockHome] in the test APK, or back at the device's real home. Resets its state. */
    private fun useStandIn(stockHome: Class<out Activity>?) {
        val extras =
            stockHome?.let {
                Bundle().apply {
                    putString(RedirectContract.KEY_STOCK_PACKAGE, testPackage)
                    putStringArrayList(RedirectContract.KEY_STOCK_HOME, arrayListOf(it.name))
                }
            }
        RedirectClient.call(targetContext, RedirectContract.METHOD_TEST_SETUP, extras)
    }

    private fun secureSetting(name: String): String? =
        shell("settings get secure $name").trim().takeUnless { it == "null" || it.isEmpty() }

    private fun restore(
        name: String,
        value: String?,
    ) {
        if (value == null) {
            shell("settings delete secure $name")
        } else {
            shell("settings put secure $name $value")
        }
    }

    private companion object {
        const val ENABLED_SERVICES = "enabled_accessibility_services"
        const val ACCESSIBILITY_ENABLED = "accessibility_enabled"
        const val TIMEOUT = 5_000L
        const val SERVICE_TIMEOUT = 10_000L
    }
}
