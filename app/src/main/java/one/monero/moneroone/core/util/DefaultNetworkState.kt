package one.monero.moneroone.core.util

/**
 * The default network as its callbacks report it, and what each callback means for the node
 * connection. It has no Android types, so the unit tests can drive it. [NetworkMonitor] feeds it.
 *
 * [blockedStatusFollows]: from Android 10 each onAvailable is followed by the network's blocked
 * status. Until that status is in, the new network is not reported as usable.
 */
internal class DefaultNetworkState<N : Any>(
    private var network: N?,
    connected: Boolean,
    private val blockedStatusFollows: Boolean
) {

    /**
     * [switched]: another network replaced the default one, so connections on the old one are dead.
     * [returned]: the app can use the network again, so a start that failed without it can run.
     */
    data class Change(val switched: Boolean = false, val returned: Boolean = false)

    var connected = connected
        private set

    // Doze, App Standby and the background restrictions block the app on a network that is up.
    private var blocked = false
    private var awaitingBlockedStatus = false
    private var usable = connected

    fun onAvailable(network: N): Change {
        val previous = this.network
        this.network = network
        connected = true
        awaitingBlockedStatus = blockedStatusFollows
        return Change(switched = previous != null && previous != network, returned = settle())
    }

    fun onLost(): Change {
        connected = false
        awaitingBlockedStatus = false
        return Change(returned = settle())
    }

    fun onCapabilitiesChanged(internet: Boolean): Change {
        connected = internet
        return Change(returned = settle())
    }

    fun onBlockedStatusChanged(blocked: Boolean): Change {
        this.blocked = blocked
        awaitingBlockedStatus = false
        return Change(returned = settle())
    }

    /** True when the network became usable with this callback. */
    private fun settle(): Boolean {
        if (awaitingBlockedStatus) return false
        val wasUsable = usable
        usable = connected && !blocked
        return usable && !wasUsable
    }
}
