package ai.aneesiqbal.rozan.launcher

import ai.aneesiqbal.rozan.launcher.update.AutoUpdate
import ai.aneesiqbal.rozan.launcher.update.InstallPermission
import ai.aneesiqbal.rozan.launcher.update.UpdateFiles
import ai.aneesiqbal.rozan.launcher.update.UpdateJobs
import ai.aneesiqbal.rozan.launcher.update.UpdateState
import ai.aneesiqbal.rozan.ui.SettingsRowView
import android.app.job.JobScheduler
import android.content.Context
import android.view.ViewGroup
import android.widget.TextView
import androidx.core.content.edit
import androidx.core.view.children
import androidx.core.view.isVisible
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeFalse
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The Updates section. No test reaches GitHub: the switch is only turned on
 * with the metadata URL pointed at a closed local port, so the check fails at
 * once with "Could not reach GitHub."
 *
 * No test takes "install unknown apps" back: Android kills an app that loses
 * it ("REQUEST_INSTALL_PACKAGES changed" in the log), which would end this
 * instrumentation. A test run starts from a fresh install, where it is not
 * allowed, and uninstalls the app at the end.
 */
@RunWith(AndroidJUnit4::class)
class UpdatesSettingsTest {
    @Before
    fun setUp() {
        resetLauncherState()
        resetUpdates()
    }

    @After
    fun tearDown() {
        resetUpdates()
        resetLauncherState()
    }

    @Test
    fun updatesAreOffByDefaultAndScheduleNothing() {
        ActivityScenario.launch(LauncherSettingsActivity::class.java).use { scenario ->
            val toggle = scenario.row("Update automatically")
            assertEquals(false, toggle.first)
            assertEquals("Checks GitHub for a new Rozan release once a day and installs it", toggle.second)
            assertFalse("no Install update row", scenario.rowVisible("Install update"))
        }
        assertTrue("no update jobs", updateJobs().isEmpty())
    }

    @Test
    fun installUnknownAppsFollowsTheAppOp() {
        val command = "adb shell appops set $PACKAGE REQUEST_INSTALL_PACKAGES allow"
        assumeFalse("allowed by an earlier run on this install", InstallPermission.isGranted(targetContext))
        ActivityScenario.launch(LauncherSettingsActivity::class.java).use { scenario ->
            waitUntil("not allowed, with the command") { scenario.permissionState() == "Not allowed" to true }
            assertTrue(scenario.commandShown(command))
            shell("appops set $PACKAGE REQUEST_INSTALL_PACKAGES allow")
            waitUntil("allowed, without the command") { scenario.permissionState() == "Allowed" to false }
        }
    }

    @Test
    fun theSwitchSchedulesChecksAndTurningItOffCancelsThem() {
        DebugOverrides.updateMetadataUrl = CLOSED_PORT_URL
        ActivityScenario.launch(LauncherSettingsActivity::class.java).use { scenario ->
            scenario.onActivity { it.findRow("Update automatically").performClick() }
            assertTrue(AutoUpdate.isEnabled(targetContext))
            assertTrue("the daily check is scheduled", UpdateJobs.DAILY in updateJobs())
            waitUntil("the check failed", timeoutMs = CHECK_TIMEOUT_MS) {
                scenario.row("Update automatically").second?.endsWith("failed: Could not reach GitHub.") == true
            }

            scenario.onActivity { it.findRow("Update automatically").performClick() }
            assertFalse(AutoUpdate.isEnabled(targetContext))
            waitUntil("jobs cancelled and history cleared") {
                updateJobs().isEmpty() && UpdateState(targetContext).lastResult == null
            }
            assertEquals(
                "Checks GitHub for a new Rozan release once a day and installs it",
                scenario.row("Update automatically").second,
            )
            assertTrue("no update files", UpdateFiles.dir(targetContext).listFiles().isNullOrEmpty())
        }
    }

    private fun resetUpdates() {
        AutoUpdate.setEnabled(targetContext, false)
        DebugOverrides.updateMetadataUrl = null
        targetContext.getSharedPreferences(UpdateState.FILE_NAME, Context.MODE_PRIVATE).edit(commit = true) { clear() }
        waitUntil("update jobs cancelled") { updateJobs().isEmpty() }
    }

    private fun updateJobs(): List<Int> =
        targetContext
            .getSystemService(JobScheduler::class.java)
            .allPendingJobs
            .map { it.id }
            .filter { it in listOf(UpdateJobs.DAILY, UpdateJobs.CHECK_NOW, UpdateJobs.INSTALL) }

    private fun LauncherSettingsActivity.findRow(titleStart: String): SettingsRowView =
        findViewById<ViewGroup>(R.id.settings_list)
            .children
            .filterIsInstance<SettingsRowView>()
            .first { it.title.startsWith(titleStart) }

    /** The row's switch state and summary. */
    private fun ActivityScenario<LauncherSettingsActivity>.row(title: String): Pair<Boolean?, String?> {
        var state: Pair<Boolean?, String?> = null to null
        onActivity { activity ->
            val row = activity.findRow(title)
            state = row.checked to row.summary?.toString()
        }
        return state
    }

    private fun ActivityScenario<LauncherSettingsActivity>.rowVisible(titleStart: String): Boolean {
        var visible = false
        onActivity { visible = it.findRow(titleStart).isVisible }
        return visible
    }

    /** The "Install unknown apps" row's value, and whether the adb command below it is shown. */
    private fun ActivityScenario<LauncherSettingsActivity>.permissionState(): Pair<String?, Boolean> {
        var state: Pair<String?, Boolean> = null to false
        onActivity { activity ->
            val list = activity.findViewById<ViewGroup>(R.id.settings_list)
            val row = activity.findRow("Install unknown apps")
            val command = list.getChildAt(list.indexOfChild(row) + 1)
            state = row.value?.toString() to command.isVisible
        }
        return state
    }

    private fun ActivityScenario<LauncherSettingsActivity>.commandShown(command: String): Boolean {
        var shown = false
        onActivity { activity ->
            val list = activity.findViewById<ViewGroup>(R.id.settings_list)
            shown = list.children.filterIsInstance<TextView>().any { it.text.toString() == command && it.isVisible }
        }
        return shown
    }

    private companion object {
        /** Port 9 (discard) is closed on the emulator, so the connection is refused at once. */
        const val CLOSED_PORT_URL = "http://127.0.0.1:9/latest.json"
        const val CHECK_TIMEOUT_MS = 30_000L
    }
}
