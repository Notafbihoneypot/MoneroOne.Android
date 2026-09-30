package one.monero.moneroone.core.wallet

import io.horizontalsystems.monerokit.MoneroKit.SyncError
import io.horizontalsystems.monerokit.SyncState
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SyncRecoveryTest {

    @Test fun `a start that found no network opens the wallet again`() =
        assertTrue(SyncRecovery.reopens(SyncState.NotSynced(SyncError.InvalidNode("Invalid node"))))

    @Test fun `a start that could not reach the node opens the wallet again`() =
        assertTrue(SyncRecovery.reopens(SyncState.NotSynced(SyncError.StartError("Status_Error: no connection to daemon"))))

    @Test fun `a failed refresh of a running kit opens the wallet again`() =
        assertTrue(SyncRecovery.reopens(SyncState.NotSynced(IllegalStateException("Status_Error: no connection to daemon"))))

    @Test fun `a wallet-level error does not`() {
        assertFalse(SyncRecovery.reopens(SyncState.NotSynced(SyncError.InvalidNode("Invalid wallet"))))
        assertFalse(SyncRecovery.reopens(SyncState.NotSynced(SyncError.StartError("Wallet recovery error: bad seed"))))
    }

    @Test fun `a kit that was never started, or is connecting, syncing or synced, does not`() {
        assertFalse(SyncRecovery.reopens(SyncState.NotSynced(SyncError.NotStarted)))
        assertFalse(SyncRecovery.reopens(SyncState.Connecting(waiting = false)))
        assertFalse(SyncRecovery.reopens(SyncState.Syncing(0.5, 100)))
        assertFalse(SyncRecovery.reopens(SyncState.Synced))
    }
}
