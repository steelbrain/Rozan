package ai.aneesiqbal.rozan.launcher.update

import ai.aneesiqbal.rozan.launcher.settings.FakeSharedPreferences
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class UpdateStateTest {
    private val prefs = FakeSharedPreferences()
    private val state = UpdateState(prefs)

    @Test
    fun `is off and empty by default`() {
        assertFalse(state.enabled)
        assertNull(state.lastResult)
        assertNull(state.pending)
        assertEquals(0L, state.lastCheckAt)
    }

    @Test
    fun `keeps the pending update and its stage`() {
        val pending = UpdateState.Pending("0.2.0", 2_000, "ab".repeat(32), stage = null)
        state.setPending(pending)
        assertEquals(pending, state.pending)
        state.setStage(UpdateState.Stage.ASK_USER)
        assertEquals(pending.copy(stage = UpdateState.Stage.ASK_USER), state.pending)
        state.clearPending()
        assertNull(state.pending)
    }

    @Test
    fun `records the last result`() {
        state.record(5L, UpdateState.Result.FAILED, null, UpdateState.Failure.SERVER, 404)
        assertEquals(UpdateState.Result.FAILED, state.lastResult)
        assertEquals(UpdateState.Failure.SERVER, state.failure)
        assertEquals(404, state.failureStatus)
        assertEquals(5L, state.lastCheckAt)
        state.record(6L, UpdateState.Result.UP_TO_DATE, "0.1.0")
        assertEquals(UpdateState.Result.UP_TO_DATE, state.lastResult)
        assertEquals("0.1.0", state.lastVersion)
        assertNull(state.failure)
    }

    @Test
    fun `clearing the history keeps the switch`() {
        state.enabled = true
        state.record(5L, UpdateState.Result.UP_TO_DATE, "0.1.0")
        state.setPending(UpdateState.Pending("0.2.0", 2_000, "00", UpdateState.Stage.INSTALLING))
        state.clearHistory()
        assertTrue(state.enabled)
        assertNull(state.lastResult)
        assertNull(state.pending)
    }

    @Test
    fun `values from elsewhere read as nothing`() {
        prefs
            .edit()
            .putString("result", "SOMETHING")
            .putString("pending_stage", "LATER")
            .apply()
        assertNull(state.lastResult)
        prefs
            .edit()
            .putString("pending_name", "0.2.0")
            .putString("pending_sha256", "00")
            .apply()
        assertNull(state.pending?.stage)
    }
}
