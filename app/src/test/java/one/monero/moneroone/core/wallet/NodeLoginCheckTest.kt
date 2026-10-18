package one.monero.moneroone.core.wallet

import io.horizontalsystems.monerokit.MoneroKit.SyncError
import io.horizontalsystems.monerokit.SyncState
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import one.monero.moneroone.core.node.NodeBenchmark
import one.monero.moneroone.core.node.NodeCredentials
import one.monero.moneroone.ui.components.syncErrorText
import org.junit.Assert.assertEquals
import org.junit.Test

/** The wallet's sync error on a node that rejects the RPC login (iOS friendlyErrorMessage parity). */
class NodeLoginCheckTest {

    private val node = "node.example.com:18089"
    private val otherNode = "rpc.example.org:18081"
    private val login = NodeCredentials("qa", "wrong-password")
    private val fixedLogin = NodeCredentials("qa", "right-password")

    private val measured = mutableListOf<Pair<String, NodeCredentials?>>()
    private val changes = mutableListOf<Boolean>()
    private var answer: suspend (login: NodeCredentials?) -> Long = { NodeBenchmark.UNAUTHORIZED }
    private val check = NodeLoginCheck(
        scope = CoroutineScope(Dispatchers.Unconfined),
        measure = { node, login -> measured += node to login; answer(login) },
        onChange = { changes += it }
    )

    // The kit reports each failure with a new error object.
    private fun startFailed() =
        SyncState.NotSynced(SyncError.StartError("Wallet.Status: Status_Ok//ConnectionStatus_Disconnected"))
    private fun refreshFailed() =
        SyncState.NotSynced(IllegalStateException("Wallet.Status: Status_Error/no connection to daemon/null"))

    @Test fun `a custom node that rejects the login turns the sync error into the login error`() {
        check.onSyncError(startFailed(), node, login)

        assertEquals(listOf(node to login), measured)
        assertEquals(listOf(true), changes)
        assertEquals("Node login failed. Check the username and password.", syncErrorText(nodeLoginFailed = true))
    }

    @Test fun `a dead node, or one that answers, keeps the generic error`() {
        answer = { NodeBenchmark.UNREACHABLE }
        check.onSyncError(startFailed(), node, login)
        answer = { 42L }
        check.onSyncError(startFailed(), otherNode, login)

        assertEquals(listOf(node to login, otherNode to login), measured)
        assertEquals(emptyList<Boolean>(), changes)
        assertEquals("Not connected", syncErrorText(nodeLoginFailed = false))
    }

    @Test fun `a refresh that fails on every pass is checked once, not on every pass`() {
        repeat(5) { check.onSyncError(refreshFailed(), node, login) }

        assertEquals(listOf(node to login), measured)
        assertEquals(listOf(true), changes)
    }

    @Test fun `once the sync leaves the error, the next error gets a new check`() {
        check.onSyncError(startFailed(), node, login)
        check.reset()
        check.onSyncError(startFailed(), node, login)

        assertEquals(listOf(node to login, node to login), measured)
        assertEquals(listOf(true, false, true), changes)
    }

    @Test fun `a fixed login clears the login error at the next check`() {
        check.onSyncError(startFailed(), node, login)
        check.reset()
        answer = { 8L }
        check.onSyncError(startFailed(), node, login)

        assertEquals(listOf(true, false), changes)
    }

    @Test fun `the wallet reopened with a fixed login on the same node gets a new check`() {
        answer = { if (it == fixedLogin) 8L else NodeBenchmark.UNAUTHORIZED }
        check.onSyncError(startFailed(), node, login)
        check.onSyncError(startFailed(), node, fixedLogin)

        assertEquals(listOf(node to login, node to fixedLogin), measured)
        assertEquals(listOf(true, false), changes)
    }

    @Test fun `an error on another node checks that node`() {
        check.onSyncError(startFailed(), node, login)
        check.onSyncError(startFailed(), otherNode, login)

        assertEquals(listOf(node to login, otherNode to login), measured)
        assertEquals(listOf(true, false, true), changes)
    }

    @Test fun `an answer that arrives after the error ended is dropped`() {
        val late = CompletableDeferred<Long>()
        answer = { late.await() }
        check.onSyncError(startFailed(), node, login)
        check.reset()
        late.complete(NodeBenchmark.UNAUTHORIZED)

        assertEquals(listOf(node to login), measured)
        assertEquals(emptyList<Boolean>(), changes)
    }

    @Test fun `default nodes take no login and get no check`() {
        check.onSyncError(startFailed(), DefaultNodes.ALL.first().uri, null)
        check.onSyncError(startFailed(), DefaultNodes.TOR.first().uri, null)

        assertEquals(emptyList<Pair<String, NodeCredentials?>>(), measured)
        assertEquals(emptyList<Boolean>(), changes)
    }

    @Test fun `a wallet-level error is not a node error and gets no check`() {
        check.onSyncError(SyncState.NotSynced(SyncError.InvalidNode("Invalid wallet")), node, login)
        check.onSyncError(SyncState.NotSynced(SyncError.StartError("Wallet recovery error: bad seed")), node, login)

        assertEquals(emptyList<Pair<String, NodeCredentials?>>(), measured)
    }

    @Test fun `a wallet-level error after a rejected login clears the login error`() {
        check.onSyncError(startFailed(), node, login)
        check.onSyncError(SyncState.NotSynced(SyncError.InvalidNode("Invalid wallet")), node, login)

        assertEquals(listOf(true, false), changes)
    }
}
