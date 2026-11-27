package ai.aneesiqbal.rozan.launcher.redirect

import ai.aneesiqbal.rozan.launcher.redirect.HomeRedirector.Action.NOTHING
import ai.aneesiqbal.rozan.launcher.redirect.HomeRedirector.Action.START_ROZAN
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Event sequences for the section 6.4 rules. Each scenario shows windows the
 * way the platform reports them: [show] makes a window the active one and
 * sends its `TYPE_WINDOW_STATE_CHANGED`, [late] sends an event for a window
 * that is no longer in front.
 */
class HomeRedirectorTest {
    private val rozan = ActivityId(ROZAN, "$ROZAN.HomeActivity")
    private val rozanSettings = ActivityId(ROZAN, "$ROZAN.LauncherSettingsActivity")

    private val fireHome = ActivityId(FIRE_LAUNCHER, "$FIRE_LAUNCHER.ui.HomeActivity_vNext")
    private val fireSettings = ActivityId(FIRE_LAUNCHER, "$FIRE_LAUNCHER.ui.MainSettingsActivity")
    private val fireSettingsTrampoline = ActivityId(FIRE_LAUNCHER, "$FIRE_LAUNCHER.ui.SettingsActivity")
    private val fireNetwork = ActivityId(FIRE_SETTINGS, "$FIRE_SETTINGS.tv.network.NetworkActivity")
    private val fireAccessibility = ActivityId(FIRE_SETTINGS, "$FIRE_SETTINGS.tv.accessibility.AccessibilityActivity")
    private val tvLauncher = ActivityId(TV_LAUNCHER, "$TV_LAUNCHER.MainActivity")
    private val launcherX = ActivityId(LAUNCHER_X, "$LAUNCHER_X.home.HomeActivity")
    private val netflix = ActivityId("com.netflix.ninja", "com.netflix.ninja.MainActivity")
    private val youtube =
        ActivityId("com.google.android.youtube.tv", "com.google.android.apps.youtube.tv.activity.ShellActivity")

    private val windows = FakeWindows()
    private val redirector = HomeRedirector(rozan)

    private fun fireOs() = redirector.configure(HomeSetup.decide(fireHome, listOf(fireHome), rozan))

    private fun androidTv() = redirector.configure(HomeSetup.decide(tvLauncher, listOf(tvLauncher), rozan))

    private fun googleTv() = redirector.configure(HomeSetup.decide(launcherX, listOf(launcherX), rozan))

    private fun show(
        window: ActivityId,
        contentChangeTypes: Int = 0,
    ): HomeRedirector.Action {
        windows.active = window.packageName
        return redirector.onWindowStateChanged(window.packageName, window.className, contentChangeTypes, windows)
    }

    private fun late(window: ActivityId): HomeRedirector.Action =
        redirector.onWindowStateChanged(window.packageName, window.className, 0, windows)

    private fun home(): HomeRedirector.Action = redirector.onHomePressed(windows)

    /** Rozan comes up after a redirect, as the service's start would make it. */
    private fun rozanAppears() = assertEquals(NOTHING, show(rozan))

    // Redirects that must happen.

    @Test
    fun `Home from an app redirects once, and again on the next press`() {
        for (setUp in listOf(::fireOs, ::androidTv, ::googleTv)) {
            setUp()
            val stockHome = redirector.setup.stockHome.single()
            assertEquals(NOTHING, show(netflix))
            assertEquals(START_ROZAN, show(stockHome))
            rozanAppears()
            assertEquals(NOTHING, show(youtube))
            assertEquals(START_ROZAN, show(stockHome))
            rozanAppears()
        }
    }

    @Test
    fun `the first home window after boot is redirected`() {
        fireOs()

        assertEquals(START_ROZAN, show(fireHome))
    }

    @Test
    fun `Fire OS repeats home events for one press, and only the first starts Rozan`() {
        fireOs()
        show(netflix)

        assertEquals(START_ROZAN, show(fireHome))
        repeat(3) { assertEquals(NOTHING, show(fireHome)) }
        rozanAppears()
        assertFalse(redirector.awaitingRozan)
    }

    @Test
    fun `Home from a Settings page that Rozan opened goes back to Rozan`() {
        fireOs()
        show(rozan)
        show(fireSettings)
        show(fireNetwork)

        assertEquals(START_ROZAN, show(fireHome))
    }

    @Test
    fun `homekey evidence redirects when the stock home is already in front`() {
        androidTv()
        show(netflix)
        // The window event is held by a hand-over (here: the Input key) ...
        redirector.onKeyObserved(KEYCODE_TV_INPUT)
        assertEquals(NOTHING, show(tvLauncher))
        assertTrue(redirector.handoverHold)

        // ... and a Home press on the stock home sends no new window event, only H.
        assertEquals(START_ROZAN, home())
        assertFalse(redirector.handoverHold)
        assertEquals(NOTHING, home())
    }

    @Test
    fun `homekey evidence before the window event overrides a hand-over`() {
        fireOs()
        show(ActivityId("com.amazon.venezia", "com.amazon.venezia.grid.AppsGridLauncherActivity"))

        // AOSP sends the homekey broadcast before it starts the home: the Apps grid is still in front.
        assertEquals(NOTHING, home())

        assertEquals(START_ROZAN, show(fireHome))
    }

    @Test
    fun `Home pressed on a stock launcher screen redirects at once`() {
        fireOs()
        show(fireSettings)

        assertEquals(START_ROZAN, home())
        assertEquals(NOTHING, show(fireHome))
    }

    @Test
    fun `without key observation Back into the Fire OS 7 Settings tab lands in Rozan, once`() {
        fireOs()
        show(fireNetwork)

        assertEquals(START_ROZAN, show(fireHome))
        assertEquals(NOTHING, show(fireHome))
    }

    // Settings: never redirected, never a loop.

    @Test
    fun `Settings opened from Rozan's Settings tile is left alone on Fire OS`() {
        fireOs()
        show(rozan)

        // MainSettingsActivity and the other Settings classes share the stock launcher's package.
        for (page in listOf(fireSettings, fireSettingsTrampoline, fireNetwork, fireAccessibility, fireSettings)) {
            assertEquals(NOTHING, show(page), "for $page")
        }
        assertFalse(redirector.awaitingRozan)
    }

    @Test
    fun `Settings opened from Rozan is left alone on Android TV and Google TV`() {
        val tvSettings = ActivityId("com.android.tv.settings", "com.android.tv.settings.MainSettings")
        val wifi = ActivityId("com.android.tv.settings", "com.android.tv.settings.connectivity.NetworkActivity")
        for (setUp in listOf(::androidTv, ::googleTv)) {
            setUp()
            show(rozan)
            assertEquals(NOTHING, show(tvSettings))
            assertEquals(NOTHING, show(wifi))
            assertEquals(NOTHING, show(tvSettings))
        }
    }

    @Test
    fun `the ACTION_SETTINGS trampoline through the Fire OS home is held, not redirected`() {
        fireOs()
        show(rozan)

        assertEquals(NOTHING, show(ActivityId(FIRE_SETTINGS, "$FIRE_SETTINGS.compliance.CTSDummySettingsHandler")))
        assertEquals(NOTHING, show(fireHome))
        assertTrue(redirector.handoverHold)
        assertEquals(NOTHING, show(fireSettings))
        assertFalse(redirector.handoverHold)
    }

    @Test
    fun `Back from a Settings page into the Fire OS 7 Settings tab is held when Back was observed`() {
        fireOs()
        show(fireNetwork)
        redirector.onKeyObserved(KEYCODE_BACK)

        assertEquals(NOTHING, show(fireHome))
    }

    @Test
    fun `a stock launcher Settings page with Back observed is a hand-over too`() {
        fireOs()
        show(fireSettings)
        redirector.onKeyObserved(KEYCODE_DPAD_DOWN)
        redirector.onKeyObserved(KEYCODE_BACK)

        assertEquals(NOTHING, show(fireHome))
    }

    @Test
    fun `Back observed from an ordinary app is not a hand-over`() {
        fireOs()
        show(netflix)
        redirector.onKeyObserved(KEYCODE_BACK)

        assertEquals(START_ROZAN, show(fireHome))
    }

    // Other system screens.

    @Test
    fun `the app library is left alone`() {
        androidTv()
        show(rozan)
        // Long-press Home on Android TV opens the stock launcher's apps view.
        assertEquals(NOTHING, show(ActivityId(TV_LAUNCHER, "$TV_LAUNCHER.appsview.AppsViewActivity")))

        fireOs()
        show(rozan)
        // The Fire OS 8 Apps button opens the Appstore's grid, which then starts the home itself.
        assertEquals(
            NOTHING,
            show(ActivityId("com.amazon.venezia", "com.amazon.venezia.grid.AppsGridLauncherActivity")),
        )
        assertEquals(NOTHING, show(fireHome))
        assertTrue(redirector.handoverHold)
    }

    @Test
    fun `notification panels are left alone`() {
        androidTv()
        show(rozan)
        assertEquals(
            NOTHING,
            show(ActivityId(TV_LAUNCHER, "$TV_LAUNCHER.notifications.NotificationsSidePanelActivity")),
        )

        fireOs()
        show(rozan)
        val center =
            ActivityId(
                "com.amazon.tv.notificationcenter",
                "com.amazon.tv.notificationcenter.NotificationCenterActivity",
            )
        assertEquals(NOTHING, show(center))
    }

    @Test
    fun `input pickers and input switching are left alone`() {
        androidTv()
        show(rozan)
        assertEquals(NOTHING, show(ActivityId(TV_LAUNCHER, "$TV_LAUNCHER.inputs.InputsPanelActivity")))

        // Fire TV smart TV: StartAmazonInputActivity -> home briefly -> the HDMI player.
        fireOs()
        show(rozan)
        val start =
            ActivityId(
                "com.amazon.tv.inputpreference.service",
                "com.amazon.tv.inputpreference.service.StartAmazonInputActivity",
            )
        val player =
            ActivityId(
                "com.amazon.tv.inputpreference.service",
                "com.amazon.tv.inputpreference.service.player.PassthroughPlayerActivity",
            )
        assertEquals(NOTHING, show(start))
        assertEquals(NOTHING, show(fireHome))
        assertEquals(NOTHING, show(player))

        val inputSwitch =
            ActivityId(
                "com.amazon.android.amazoninputswitch",
                "com.amazon.android.amazoninputswitch.InputSwitchActivity",
            )
        assertEquals(NOTHING, show(inputSwitch))
        assertEquals(NOTHING, show(fireHome))
    }

    @Test
    fun `the Input key opening the stock home's inputs view is a hand-over`() {
        fireOs()
        show(netflix)
        redirector.onKeyObserved(KEYCODE_TV_INPUT)

        assertEquals(NOTHING, show(fireHome))
    }

    @Test
    fun `the inputs view that Rozan opens inside the Fire OS home is a hand-over`() {
        fireOs()
        show(rozan)
        redirector.onRozanOpensPicker()

        assertEquals(NOTHING, show(fireHome))
        assertTrue(redirector.handoverHold)
        // Home pressed in the inputs view still goes to Rozan.
        assertEquals(START_ROZAN, home())
        rozanAppears()

        // The next arrival of the home without Rozan opening anything is redirected again.
        assertEquals(START_ROZAN, show(fireHome))
    }

    @Test
    fun `picking an input in the view Rozan opened is left alone all the way`() {
        fireOs()
        show(rozan)
        redirector.onRozanOpensPicker()
        assertEquals(NOTHING, show(fireHome))

        val start =
            ActivityId(
                "com.amazon.tv.inputpreference.service",
                "com.amazon.tv.inputpreference.service.StartAmazonInputActivity",
            )
        assertEquals(NOTHING, show(start))
        assertEquals(NOTHING, show(fireHome))
    }

    @Test
    fun `a picker of its own app ends the hand-over`() {
        fireOs()
        show(rozan)
        redirector.onRozanOpensPicker()
        val chooser =
            ActivityId(
                "com.amazon.tv.inputpreference.service",
                "com.amazon.tv.inputpreference.player.InputChooserActivity",
            )
        assertEquals(NOTHING, show(chooser))
        assertFalse(redirector.handoverHold)

        // Home pressed from the chooser: the stock home that follows is redirected.
        assertEquals(START_ROZAN, show(fireHome))
    }

    @Test
    fun `a picker that did not open leaves the next home redirected`() {
        fireOs()
        show(rozan)
        redirector.onRozanOpensPicker()
        redirector.onRozanPickerFailed()

        assertEquals(START_ROZAN, show(fireHome))
    }

    @Test
    fun `profile pickers are left alone`() {
        fireOs()
        assertEquals(
            NOTHING,
            show(ActivityId("com.amazon.ftv.profilepicker", "com.amazon.ftv.profilepicker.ProfilePickerActivity")),
        )
        // The home window after the choice is redirected.
        assertEquals(START_ROZAN, show(fireHome))

        googleTv()
        show(rozan)
        val chooser = ActivityId(LAUNCHER_X, "$LAUNCHER_X.profile.chooser.ProfileChooserActivity")
        assertEquals(NOTHING, show(chooser))
    }

    @Test
    fun `the Google TV profile button shows the home and then the chooser, and is a hand-over`() {
        googleTv()
        show(netflix)
        redirector.onKeyObserved(KEYCODE_PROFILE_SWITCH)

        assertEquals(NOTHING, show(launcherX))
        assertEquals(NOTHING, show(ActivityId(LAUNCHER_X, "$LAUNCHER_X.profile.chooser.ProfileChooserActivity")))
    }

    @Test
    fun `the long-press Home panel is a hand-over on every Fire OS UI`() {
        fireOs()
        show(rozan)
        assertEquals(NOTHING, show(ActivityId(FIRE_SETTINGS, "$FIRE_SETTINGS.hud.HudActivity")))
        assertEquals(NOTHING, show(fireHome))

        show(rozan)
        // Fire OS 8.1.8.0 and later: an overlay window, not an activity.
        windows.notActivities += ActivityId(QUICK_SETTINGS, "$QUICK_SETTINGS.QuickSettingsPanel")
        assertEquals(NOTHING, show(ActivityId(QUICK_SETTINGS, "$QUICK_SETTINGS.QuickSettingsPanel")))
        assertEquals(NOTHING, show(fireHome))

        show(rozan)
        assertEquals(NOTHING, show(ActivityId(FIRE_LAUNCHER, "$FIRE_LAUNCHER.util.QuickSettingsProxyActivity")))
        assertEquals(NOTHING, show(fireHome))
    }

    @Test
    fun `Amazon Kids is never redirected`() {
        fireOs()
        assertEquals(NOTHING, show(ActivityId(KIDS, "$KIDS.ftv.HomeActivity")))
        assertEquals(NOTHING, show(ActivityId(KIDS, "$KIDS.ftv.pin.ui.PinActivity")))
        assertEquals(NOTHING, home())

        // In a Kids profile the resolved home is Amazon Kids itself: no redirect at all.
        val kidsHome = ActivityId(KIDS, "$KIDS.ftv.HomeActivity")
        redirector.configure(HomeSetup.decide(kidsHome, listOf(kidsHome), rozan))
        assertEquals(NOTHING, show(kidsHome))
        assertEquals(NOTHING, home())
    }

    @Test
    fun `dialogs, panes, the IME and system UI windows are ignored`() {
        fireOs()
        show(netflix)
        val dialog = ActivityId(FIRE_LAUNCHER, "android.app.AlertDialog")
        val ime = ActivityId("com.amazon.tv.ime", "android.inputmethodservice.SoftInputWindow")
        windows.notActivities += setOf(dialog, ime)

        assertEquals(NOTHING, show(dialog))
        assertEquals(NOTHING, show(ime))
        assertEquals(NOTHING, show(ActivityId("com.android.systemui", "com.android.systemui.tv.pip.PipMenuActivity")))
        assertEquals(NOTHING, show(ActivityId("android", "com.android.internal.app.ResolverActivity")))
        // A pane title change inside the stock home is not the home window.
        assertEquals(NOTHING, show(fireHome, contentChangeTypes = CONTENT_CHANGE_TYPE_PANE_APPEARED))
        assertEquals(netflix, redirector.lastActivity)

        assertEquals(START_ROZAN, show(fireHome))
    }

    @Test
    fun `Rozan's own windows other than its home do not count as another activity`() {
        fireOs()
        show(netflix)
        assertEquals(START_ROZAN, show(fireHome))

        assertEquals(NOTHING, show(rozanSettings))
        assertTrue(redirector.awaitingRozan)
        assertEquals(netflix, redirector.lastActivity)
    }

    // Crashes, stale events and the user's choice.

    @Test
    fun `Rozan crashing repeatedly leaves the user on the stock home until they open Rozan`() {
        fireOs()
        show(netflix)
        assertEquals(START_ROZAN, show(fireHome))
        redirector.onRozanCrashed()
        assertTrue(redirector.tripped)
        assertFalse(redirector.awaitingRozan)

        assertEquals(NOTHING, show(fireHome))
        assertEquals(NOTHING, home())
        assertEquals(NOTHING, show(netflix))
        assertEquals(NOTHING, show(fireHome))
        redirector.onRozanCrashed()
        assertEquals(NOTHING, show(fireHome))

        // The user opens Rozan from the stock app row: the breaker resets.
        rozanAppears()
        assertFalse(redirector.tripped)
        show(netflix)
        assertEquals(START_ROZAN, show(fireHome))
    }

    @Test
    fun `a crash a few seconds after Rozan showed also trips the breaker`() {
        androidTv()
        show(netflix)
        assertEquals(START_ROZAN, show(tvLauncher))
        rozanAppears()
        redirector.onRozanCrashed()

        // The system falls back to the stock home after the crash.
        assertEquals(NOTHING, show(tvLauncher))
    }

    @Test
    fun `a start that fails silently costs one attempt, and the next needs the user to leave`() {
        androidTv()
        show(netflix)
        assertEquals(START_ROZAN, show(tvLauncher))
        // Rozan never appears and reports nothing: the home keeps refocusing.
        repeat(5) { assertEquals(NOTHING, show(tvLauncher)) }

        assertEquals(NOTHING, show(youtube))
        assertEquals(START_ROZAN, show(tvLauncher))
    }

    @Test
    fun `a stale home event that arrives after Rozan is in front is ignored`() {
        fireOs()
        show(netflix)
        assertEquals(START_ROZAN, show(fireHome))
        rozanAppears()

        // Home on Fire issue #11: a second home event about 1 s late.
        assertEquals(NOTHING, late(fireHome))
        assertFalse(redirector.awaitingRozan)
    }

    @Test
    fun `a stale home event while another app is in front is ignored`() {
        androidTv()
        show(netflix)

        assertEquals(NOTHING, late(tvLauncher))
        assertEquals(NOTHING, show(youtube))
        assertEquals(NOTHING, late(tvLauncher))
    }

    @Test
    fun `the user choosing the stock home stays there until another app opens`() {
        androidTv()
        show(rozan)
        redirector.onStayOnStockHome()

        assertEquals(NOTHING, show(tvLauncher))
        assertEquals(NOTHING, home())
        // Screens of the stock launcher itself keep the choice.
        assertEquals(NOTHING, show(ActivityId(TV_LAUNCHER, "$TV_LAUNCHER.appsview.AppsViewActivity")))
        assertEquals(NOTHING, show(tvLauncher))
        assertTrue(redirector.userStay)

        assertEquals(NOTHING, show(netflix))
        assertFalse(redirector.userStay)
        assertEquals(START_ROZAN, show(tvLauncher))
    }

    @Test
    fun `choosing the stock home on Fire OS survives a visit to its Settings`() {
        fireOs()
        show(rozan)
        redirector.onStayOnStockHome()
        show(fireHome)

        show(fireSettings)
        assertEquals(NOTHING, show(fireHome))
        assertTrue(redirector.userStay)
    }

    // Modes.

    @Test
    fun `native mode never redirects`() {
        redirector.configure(HomeSetup.decide(rozan, listOf(rozan), rozan))

        assertEquals(NOTHING, show(tvLauncher))
        assertEquals(NOTHING, home())
        assertEquals(NOTHING, show(rozan))
        assertEquals(NOTHING, home())
    }

    @Test
    fun `no redirect before the first evaluation or when Home opens a chooser`() {
        assertEquals(NOTHING, show(tvLauncher))
        assertEquals(NOTHING, home())

        val chooser = ActivityId("android", "com.android.internal.app.ResolverActivity")
        redirector.configure(HomeSetup.decide(chooser, listOf(chooser), rozan))
        assertEquals(NOTHING, show(tvLauncher))
        assertEquals(NOTHING, home())
    }

    @Test
    fun `a reconfiguration keeps the state`() {
        fireOs()
        show(netflix)
        assertEquals(START_ROZAN, show(fireHome))

        fireOs()
        assertTrue(redirector.awaitingRozan)
        assertEquals(NOTHING, show(fireHome))
    }

    private class FakeWindows : HomeRedirector.Windows {
        var active: String? = null
        val notActivities = mutableSetOf<ActivityId>()

        override fun isActivity(activity: ActivityId): Boolean = activity !in notActivities

        override fun activeWindowPackage(): String? = active
    }

    private companion object {
        const val ROZAN = "ai.aneesiqbal.rozan.launcher"
        const val FIRE_LAUNCHER = "com.amazon.tv.launcher"
        const val FIRE_SETTINGS = "com.amazon.tv.settings.v2"
        const val QUICK_SETTINGS = "com.amazon.tv.quicksettings.ui"
        const val TV_LAUNCHER = "com.google.android.tvlauncher"
        const val LAUNCHER_X = "com.google.android.apps.tv.launcherx"
        const val KIDS = "com.amazon.tahoe"

        const val KEYCODE_BACK = 4
        const val KEYCODE_DPAD_DOWN = 20
        const val KEYCODE_TV_INPUT = 178
        const val KEYCODE_PROFILE_SWITCH = 288
        const val CONTENT_CHANGE_TYPE_PANE_APPEARED = 16
    }
}
