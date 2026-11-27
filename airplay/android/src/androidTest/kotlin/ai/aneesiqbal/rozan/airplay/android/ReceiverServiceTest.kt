package ai.aneesiqbal.rozan.airplay.android

import android.app.ActivityManager
import android.os.Process
import android.system.Os
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * The receiver service in its `:airplay` process, controlled from this (the
 * main) process through the shared settings file.
 */
@RunWith(AndroidJUnit4::class)
class ReceiverServiceTest {
    private val settings = AirPlaySettings(testContext)
    private val status = ReceiverStatus(testContext)

    @After
    fun tearDown() {
        settings.enabled = false
    }

    @Test
    fun serviceRunsInItsOwnProcessAndFollowsSettingsWrittenHere() {
        settings.wakeOnConnect = true
        settings.deviceName = "Rozan Test One"
        settings.enabled = true
        ReceiverService.start(testContext)
        val first = awaitStatus { it.running && it.receiverName == "Rozan Test One" }
        assertNotNull(first.deviceId)

        val receiverProcess = airplayProcess()
        assertNotNull("service runs in :airplay", receiverProcess)
        assertNotEquals(Process.myPid(), receiverProcess?.pid)

        // Written in this process; the service in the other process notices and restarts the receiver.
        settings.deviceName = "Rozan Test Two"
        val renamed = awaitStatus { it.running && it.receiverName == "Rozan Test Two" }
        assertEquals(first.deviceId, renamed.deviceId)

        settings.enabled = false
        awaitStatus { !it.running }
    }

    @Test
    fun identityPersistsAcrossServiceAndProcessRestarts() {
        settings.enabled = true
        ReceiverService.start(testContext)
        val first = awaitStatus { it.running && it.deviceId != null }

        settings.enabled = false
        awaitStatus { !it.running }
        settings.enabled = true
        ReceiverService.start(testContext)
        assertEquals(first.deviceId, awaitStatus { it.running }.deviceId)

        // Cold start: kill the receiver process and start again.
        val pid = requireNotNull(airplayProcess()).pid
        Process.killProcess(pid)
        assertTrue("process gone", eventually { airplayProcess()?.pid != pid })
        ReceiverService.start(testContext)
        assertTrue(eventually { airplayProcess()?.pid.let { it != null && it != pid } && status.current.running })
        assertEquals(first.deviceId, status.current.deviceId)

        val identity = File(testContext.noBackupFilesDir, "airplay/identity")
        assertEquals("identity is owner-only", "600", Integer.toOctalString(Os.stat(identity.path).st_mode and 0x1FF))
    }

    @Test
    fun settingsWrittenInOneInstanceAreSeenByListenersOfAnother() {
        val other = AirPlaySettings(testContext)
        val changed = CountDownLatch(1)
        val listener = AirPlaySettings.Listener { if (other.deviceName == "Listener Check") changed.countDown() }
        instrumentation.runOnMainSync { other.addListener(listener) }
        try {
            settings.deviceName = "Listener Check"
            assertTrue(changed.await(10, TimeUnit.SECONDS))
        } finally {
            instrumentation.runOnMainSync { other.removeListener(listener) }
        }
    }

    private fun airplayProcess(): ActivityManager.RunningAppProcessInfo? =
        testContext
            .getSystemService(ActivityManager::class.java)
            .runningAppProcesses
            ?.firstOrNull { it.processName == "${testContext.packageName}:airplay" }

    /** Waits, via the status file's change events, until [predicate] holds. */
    private fun awaitStatus(predicate: (ReceiverStatus.Snapshot) -> Boolean): ReceiverStatus.Snapshot {
        val reached = CountDownLatch(1)
        val listener = ReceiverStatus.Listener { if (predicate(it)) reached.countDown() }
        instrumentation.runOnMainSync { status.addListener(listener) }
        try {
            if (!predicate(
                    status.current,
                )
            ) {
                assertTrue("status never matched: ${status.current}", reached.await(20, TimeUnit.SECONDS))
            }
        } finally {
            instrumentation.runOnMainSync { status.removeListener(listener) }
        }
        return status.current
    }
}
