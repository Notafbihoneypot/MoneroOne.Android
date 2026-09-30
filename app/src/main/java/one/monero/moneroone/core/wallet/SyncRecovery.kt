package one.monero.moneroone.core.wallet

import io.horizontalsystems.monerokit.MoneroKit
import io.horizontalsystems.monerokit.SyncState

/**
 * What a resume (or the network coming back) does for a wallet whose sync stopped.
 *
 * The kit gives up on a start that found no network: it closes the wallet and does not try again.
 * When a refresh of a running kit fails, the kit shows the error until wallet2 reports a new block,
 * even after a later refresh works. So kit.start() alone left "Not connected" on screen while the
 * phone was online. iOS refresh() restarts a wallet that is in .error, and Android now does the same.
 */
internal object SyncRecovery {

    /**
     * Kit start failures that are about the WALLET (seed recovery or creation, unreadable cache), not
     * the node. A node change or a new start cannot fix them.
     */
    fun isWalletLevel(error: Throwable): Boolean {
        val message = error.message ?: return false
        return when (error) {
            is MoneroKit.SyncError.StartError -> message.startsWith("Wallet recovery error")
            is MoneroKit.SyncError.InvalidNode -> message == "Invalid wallet"
            else -> false
        }
    }

    /** True when the wallet must be opened again: its sync stopped on a node error. */
    fun reopens(state: SyncState): Boolean =
        state is SyncState.NotSynced &&
            state.error !is MoneroKit.SyncError.NotStarted &&
            !isWalletLevel(state.error)
}
