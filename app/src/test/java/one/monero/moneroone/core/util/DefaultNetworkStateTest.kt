package one.monero.moneroone.core.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DefaultNetworkStateTest {
    private val none = DefaultNetworkState.Change()

    private fun online(network: String = "wifi") =
        DefaultNetworkState(network, connected = true, blockedStatusFollows = true)

    @Test fun `a launch with no network reports the network coming back`() {
        val state = DefaultNetworkState<String>(network = null, connected = false, blockedStatusFollows = true)
        assertEquals(none, state.onAvailable("wifi"))
        assertEquals(none, state.onCapabilitiesChanged(internet = true))
        assertTrue(state.connected)
        assertTrue(state.onBlockedStatusChanged(false).returned)
    }

    @Test fun `the network coming back after a loss is reported`() {
        val state = online()
        assertEquals(none, state.onLost())
        assertFalse(state.connected)
        assertEquals(DefaultNetworkState.Change(switched = true), state.onAvailable("cellular"))
        assertTrue(state.onBlockedStatusChanged(false).returned)
    }

    @Test fun `an app the system stops blocking reports the network coming back`() {
        val state = online()
        assertEquals(none, state.onBlockedStatusChanged(true))
        assertTrue(state.onBlockedStatusChanged(false).returned)
    }

    @Test fun `a network the app is blocked on is not a return until the block ends`() {
        val state = online()
        state.onLost()
        assertFalse(state.onAvailable("cellular").returned)
        assertFalse(state.onCapabilitiesChanged(internet = true).returned)
        assertFalse(state.onBlockedStatusChanged(true).returned)
        assertTrue(state.onBlockedStatusChanged(false).returned)
    }

    @Test fun `before Android 10 a network that comes back is a return at once`() {
        val state = DefaultNetworkState<String>(network = null, connected = false, blockedStatusFollows = false)
        assertTrue(state.onAvailable("wifi").returned)
    }

    @Test fun `a switch between two networks that work is not a return`() {
        val state = online()
        assertEquals(DefaultNetworkState.Change(switched = true), state.onAvailable("cellular"))
        assertEquals(none, state.onBlockedStatusChanged(false))
        assertEquals(none, state.onAvailable("cellular"))
        assertEquals(none, state.onCapabilitiesChanged(internet = true))
    }
}
