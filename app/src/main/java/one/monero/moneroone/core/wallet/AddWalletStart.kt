package one.monero.moneroone.core.wallet

import io.horizontalsystems.monerokit.SyncState
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withTimeoutOrNull

/**
 * When the create and restore screens may move on to the new wallet. The kit's start creates and
 * opens the wallet file, then makes wallet2's first contact with the node: setDaemon, init and a
 * connection check. That contact takes 2-5 s, and up to 20 s on a node that never answers. The add
 * used to wait for all of it before the screen moved on.
 */
internal object AddWalletStart {

    private const val POLL_MS = 50L

    /**
     * True once the new wallet's file is open and its primary address is the one that the stored seed
     * derives. Every wallet-level failure (seed recovery, a file wallet2 cannot load) comes before the
     * open, so the screen may move on while the node contact finishes. A file with other keys never
     * passes: the add then waits for the whole start, as before.
     */
    fun walletFileVerified(
        isOpen: Boolean,
        filePrimary: String?,
        seedPrimary: String?,
        state: SyncState
    ): Boolean =
        isOpen && filePrimary != null && filePrimary == seedPrimary &&
            SeedValidation.isPlausiblePrimaryAddress(filePrimary) &&
            !(state is SyncState.NotSynced && SyncRecovery.isWalletLevel(state.error))

    /**
     * Runs [start]. While it runs, calls [onOpened] once, as soon as [opened] is true.
     * Returns (or throws) when [start] does.
     */
    suspend fun <T> startSignallingOpen(
        start: suspend () -> T,
        opened: () -> Boolean,
        onOpened: () -> Unit,
        pollMs: Long = POLL_MS
    ): T = coroutineScope {
        val job = async { start() }
        while (withTimeoutOrNull(pollMs) { job.join() } == null) {
            if (opened()) {
                onOpened()
                break
            }
        }
        job.await()
    }
}
