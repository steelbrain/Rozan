package ai.aneesiqbal.rozan.launcher.update

import ai.aneesiqbal.rozan.launcher.update.InstallTiming.Decision
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

class InstallTimingTest {
    private fun decide(
        sdkInt: Int = 31,
        userInitiated: Boolean = false,
        mayInstallApps: Boolean = true,
        rozanOnScreen: Boolean = false,
        airPlayInUse: Boolean = false,
    ) = InstallTiming.decide(sdkInt, userInitiated, mayInstallApps, rozanOnScreen, airPlayInUse)

    @Test
    fun `installs at once from API 31 when nothing holds it back`() {
        for (sdk in listOf(31, 33, 34, 36)) assertEquals(Decision.INSTALL, decide(sdkInt = sdk))
    }

    @Test
    fun `asks the user on Android 11 and earlier, and without the permission`() {
        for (sdk in 28..30) assertEquals(Decision.ASK_USER, decide(sdkInt = sdk))
        assertEquals(Decision.ASK_USER, decide(mayInstallApps = false))
        // Even when something else would hold it back: the user decides when.
        assertEquals(Decision.ASK_USER, decide(sdkInt = 28, rozanOnScreen = true, airPlayInUse = true))
    }

    @Test
    fun `waits for AirPlay first, then for the screen`() {
        assertEquals(Decision.WAIT_FOR_AIRPLAY, decide(airPlayInUse = true))
        assertEquals(Decision.WAIT_FOR_AIRPLAY, decide(airPlayInUse = true, rozanOnScreen = true))
        assertEquals(Decision.WAIT_FOR_SCREEN, decide(rozanOnScreen = true))
    }

    @Test
    fun `the user's choice installs whatever else is true`() {
        for (sdk in listOf(28, 30, 31, 36)) {
            for (flags in 0 until 8) {
                val decision =
                    decide(
                        sdkInt = sdk,
                        userInitiated = true,
                        mayInstallApps = flags and 1 != 0,
                        rozanOnScreen = flags and 2 != 0,
                        airPlayInUse = flags and 4 != 0,
                    )
                assertEquals(Decision.INSTALL, decision, "sdk $sdk flags $flags")
            }
        }
    }

    @Test
    fun `never installs unasked while Rozan is on screen or AirPlay is in use`() {
        for (sdk in 28..36) {
            for (flags in 0 until 8) {
                val onScreen = flags and 2 != 0
                val inUse = flags and 4 != 0
                val decision = decide(sdk, false, flags and 1 != 0, onScreen, inUse)
                if (onScreen || inUse) assert(decision != Decision.INSTALL) { "sdk $sdk flags $flags" }
            }
        }
    }
}
