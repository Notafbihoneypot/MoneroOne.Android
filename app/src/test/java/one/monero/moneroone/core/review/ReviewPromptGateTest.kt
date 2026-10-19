package one.monero.moneroone.core.review

import one.monero.moneroone.core.wallet.FakeSharedPreferences
import one.monero.moneroone.core.wallet.SendFlow
import one.monero.moneroone.core.wallet.SendState
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReviewPromptGateTest {
    private val flow = SendFlow("wallet-1")
    private val prefs = FakeSharedPreferences()
    private val gate = ReviewPromptGate(prefs)

    @Test
    fun `nothing is pending before a send`() {
        assertFalse(gate.isPending)
        assertFalse(gate.claim(homeIsIdle = true))
    }

    @Test
    fun `a successful send fires the prompt once`() {
        gate.sendStateChanged(SendState.Success(flow, ""))
        assertTrue(gate.claim(homeIsIdle = true))
        assertTrue(gate.isDone)

        gate.sendStateChanged(SendState.Success(SendFlow("wallet-2"), ""))
        assertFalse(gate.isPending)
        assertFalse(gate.claim(homeIsIdle = true))
    }

    @Test
    fun `it stays pending until the home is idle`() {
        gate.sendStateChanged(SendState.Success(flow, ""))
        assertFalse(gate.claim(homeIsIdle = false))
        assertTrue(gate.isPending)
        assertFalse(gate.isDone)

        // Another gate on the same store: a relaunch keeps the pending ask.
        assertTrue(ReviewPromptGate(prefs).claim(homeIsIdle = true))
    }

    @Test
    fun `a failed or unfinished send sets nothing`() {
        gate.sendStateChanged(SendState.Sending(flow))
        gate.sendStateChanged(SendState.Error(flow, "Transaction failed"))
        gate.sendStateChanged(SendState.Idle)
        assertFalse(gate.isPending)
        assertFalse(gate.claim(homeIsIdle = true))
    }

    @Test
    fun `the Settings row suppresses the prompt`() {
        gate.ratedFromSettings()
        gate.sendStateChanged(SendState.Success(flow, ""))
        assertFalse(gate.isPending)
        assertFalse(gate.claim(homeIsIdle = true))
    }

    @Test
    fun `the Settings row clears a pending prompt`() {
        gate.sendStateChanged(SendState.Success(flow, ""))
        gate.ratedFromSettings()
        assertFalse(gate.claim(homeIsIdle = true))
    }

    @Test
    fun `a dismiss leaves it done for good`() {
        gate.sendStateChanged(SendState.Success(flow, ""))
        assertTrue(gate.claim(homeIsIdle = true))
        // No Thanks, Back or a tap outside only close the dialog: the open already marked it done.
        assertTrue(prefs.getBoolean(ReviewPromptGate.DONE_KEY, false))
        assertFalse(prefs.contains(ReviewPromptGate.PENDING_KEY))
        gate.sendStateChanged(SendState.Success(flow, ""))
        assertFalse(ReviewPromptGate(prefs).claim(homeIsIdle = true))
    }
}
