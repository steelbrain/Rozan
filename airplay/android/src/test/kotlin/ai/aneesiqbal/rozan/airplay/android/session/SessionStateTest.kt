package ai.aneesiqbal.rozan.airplay.android.session

import ai.aneesiqbal.rozan.airplay.receiver.ClientInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionStateTest {
    private val anna = ClientInfo("192.0.2.10", "Anna's MacBook Pro", "MacBookPro18,3")
    private val other = ClientInfo("192.0.2.11", "Other", null)

    @Test
    fun theFinishOfThePromptOnScreenClearsIt() {
        val state = SessionState()
        state.showPin("4817", anna)
        assertTrue(state.clearPin(anna))
        assertNull(state.pinPrompt)
    }

    @Test
    fun aFinishForAnotherClientLeavesThePromptAlone() {
        val state = SessionState()
        var changes = 0
        state.addListener { changes++ }
        state.showPin("4817", anna)
        val shown = state.pinPrompt

        assertFalse(state.clearPin(other))
        assertSame(shown, state.pinPrompt)
        assertEquals("only the show was a change", 1, changes)
    }

    @Test
    fun promptsAreMatchedByAddressNotByTheNameTheClientSends() {
        val state = SessionState()
        state.showPin("4817", anna)
        assertFalse(state.clearPin(anna.copy(address = "192.0.2.12")))
        assertTrue(state.clearPin(anna.copy(name = "Renamed", model = null)))
    }

    @Test
    fun aLateFinishForAnEarlierPromptDoesNotEndTheNextOne() {
        val state = SessionState()
        state.showPin("1111", other)
        state.showPin("2222", anna)
        assertFalse(state.clearPin(other))
        assertEquals("2222", state.pinPrompt?.pin)
    }

    @Test
    fun aFinishWithNothingOnScreenIsIgnored() {
        assertFalse(SessionState().clearPin(anna))
    }
}
