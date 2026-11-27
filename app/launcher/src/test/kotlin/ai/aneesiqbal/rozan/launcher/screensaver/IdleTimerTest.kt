package ai.aneesiqbal.rozan.launcher.screensaver

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class IdleTimerTest {
    /** A manual clock: tasks run when [advance] passes their due time. */
    private class FakeScheduler : IdleTimer.Scheduler {
        var now = 0L
        private val tasks = mutableListOf<Pair<Long, Runnable>>()

        override fun schedule(
            task: Runnable,
            delayMs: Long,
        ) {
            tasks += (now + delayMs) to task
        }

        override fun cancel(task: Runnable) {
            tasks.removeAll { it.second === task }
        }

        fun advance(ms: Long) {
            now += ms
            while (true) {
                val due = tasks.filter { it.first <= now }.minByOrNull { it.first } ?: return
                tasks.remove(due)
                due.second.run()
            }
        }

        val pending: Int get() = tasks.size
    }

    private class Events : IdleTimer.Listener {
        val log = mutableListOf<String>()

        override fun onScreensaverStarted() {
            log += "start"
        }

        override fun onScreensaverStopped() {
            log += "stop"
        }
    }

    private val scheduler = FakeScheduler()
    private val events = Events()
    private val timer = IdleTimer(scheduler, events)

    private fun press(keyCode: Int): Pair<Boolean, Boolean> =
        timer.onKey(keyCode, down = true) to timer.onKey(keyCode, down = false)

    @Test
    fun `starts after the timeout without input`() {
        timer.resume(TIMEOUT)
        scheduler.advance(TIMEOUT - 1)
        assertFalse(timer.isScreensaverActive)

        scheduler.advance(1)

        assertTrue(timer.isScreensaverActive)
        assertEquals(listOf("start"), events.log)
    }

    @Test
    fun `a key press restarts the countdown and is not consumed`() {
        timer.resume(TIMEOUT)
        scheduler.advance(TIMEOUT - 10)

        assertEquals(false to false, press(KEY_RIGHT))
        scheduler.advance(TIMEOUT - 10)

        assertFalse(timer.isScreensaverActive)
        scheduler.advance(10)
        assertTrue(timer.isScreensaverActive)
    }

    @Test
    fun `any key stops the screensaver and its whole press is consumed`() {
        timer.resume(TIMEOUT)
        scheduler.advance(TIMEOUT)

        assertTrue(timer.onKey(KEY_CENTER, down = true))
        assertFalse(timer.isScreensaverActive)
        // Auto-repeat and the release of the same key are swallowed too.
        assertTrue(timer.onKey(KEY_CENTER, down = true))
        assertTrue(timer.onKey(KEY_CENTER, down = false))
        // The next press acts normally.
        assertEquals(false to false, press(KEY_CENTER))
        assertEquals(listOf("start", "stop"), events.log)
    }

    @Test
    fun `stopping restarts the countdown`() {
        timer.resume(TIMEOUT)
        scheduler.advance(TIMEOUT)
        press(KEY_CENTER)

        scheduler.advance(TIMEOUT)

        assertTrue(timer.isScreensaverActive)
        assertEquals(listOf("start", "stop", "start"), events.log)
    }

    @Test
    fun `a release left over from before the screensaver is consumed`() {
        timer.resume(TIMEOUT)
        timer.onKey(KEY_RIGHT, down = true)
        scheduler.advance(TIMEOUT)

        assertTrue(timer.onKey(KEY_RIGHT, down = false))
        assertTrue(timer.isScreensaverActive)
    }

    @Test
    fun `zero timeout disables the countdown`() {
        timer.resume(0)
        scheduler.advance(TIMEOUT * 100)

        assertFalse(timer.isScreensaverActive)
        assertEquals(0, scheduler.pending)
    }

    @Test
    fun `startNow works even with the countdown disabled`() {
        timer.resume(0)

        timer.startNow()

        assertTrue(timer.isScreensaverActive)
        assertTrue(timer.onKey(KEY_BACK, down = true))
        assertFalse(timer.isScreensaverActive)
    }

    @Test
    fun `startNow does nothing while paused`() {
        timer.startNow()

        assertFalse(timer.isScreensaverActive)
        assertTrue(events.log.isEmpty())
    }

    @Test
    fun `pause stops the screensaver and the countdown`() {
        timer.resume(TIMEOUT)
        scheduler.advance(TIMEOUT)

        timer.pause()
        scheduler.advance(TIMEOUT * 10)

        assertFalse(timer.isScreensaverActive)
        assertEquals(listOf("start", "stop"), events.log)
        assertEquals(0, scheduler.pending)
        assertFalse(timer.onKey(KEY_CENTER, down = false))
    }

    @Test
    fun `stopNow leaves the screensaver without swallowing the next key`() {
        timer.resume(TIMEOUT)
        scheduler.advance(TIMEOUT)

        timer.stopNow()

        assertFalse(timer.isScreensaverActive)
        assertEquals(false to false, press(KEY_CENTER))
        scheduler.advance(TIMEOUT)
        assertTrue(timer.isScreensaverActive)
    }

    @Test
    fun `user activity outside key events restarts the countdown`() {
        timer.resume(TIMEOUT)
        scheduler.advance(TIMEOUT - 1)

        timer.onUserActivity()
        scheduler.advance(TIMEOUT - 1)

        assertFalse(timer.isScreensaverActive)
    }

    @Test
    fun `resume with a new timeout replaces the old countdown`() {
        timer.resume(TIMEOUT)
        timer.pause()
        timer.resume(TIMEOUT * 2)

        scheduler.advance(TIMEOUT)
        assertFalse(timer.isScreensaverActive)
        scheduler.advance(TIMEOUT)
        assertTrue(timer.isScreensaverActive)
        assertEquals(1, events.log.size)
    }

    private companion object {
        const val TIMEOUT = 5 * 60_000L
        const val KEY_CENTER = 23
        const val KEY_RIGHT = 22
        const val KEY_BACK = 4
    }
}
