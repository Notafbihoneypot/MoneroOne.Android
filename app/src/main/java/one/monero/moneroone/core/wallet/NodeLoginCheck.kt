package one.monero.moneroone.core.wallet

import io.horizontalsystems.monerokit.SyncState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import one.monero.moneroone.core.node.NodeBenchmark
import one.monero.moneroone.core.node.NodeCredentials

/**
 * Tells a node that rejects the RPC login from a node that is down, for the wallet's sync error.
 *
 * wallet2 reports a rejected digest login (HTTP 401) as a lost connection, so a wrong password showed
 * "Not connected", the same as a dead node. When the sync fails on a custom node, one
 * [NodeBenchmark.measure] with the login the wallet uses tells the two apart, as on iOS. Default nodes
 * take no login, so they get no check.
 *
 * One check per error: a failing refresh repeats its error on every pass, and the repeats keep the
 * answer. [reset] (the sync left the error, or the kit went away) clears it, so the next error gets a
 * new check.
 */
internal class NodeLoginCheck(
    private val scope: CoroutineScope,
    /** [NodeBenchmark.measure] of the node, with the login the wallet uses on it. */
    private val measure: suspend (node: String, login: NodeCredentials?) -> Long,
    /** Gets true when the node rejected the login, and false when that answer is cleared. */
    private val onChange: (loginFailed: Boolean) -> Unit
) {
    private var checked: Pair<String, NodeCredentials?>? = null
    private var check: Job? = null
    private var loginFailed = false

    /**
     * The wallet's sync stopped with [state] on [node] (a bare host:port), which the wallet opened with
     * [login]: the running wallet's own node, not the saved setting, which can already name another one.
     */
    fun onSyncError(state: SyncState.NotSynced, node: String, login: NodeCredentials?) {
        if (!SyncRecovery.reopens(state) || !isCustom(node)) return reset()
        val target = node to login
        if (target == checked) return
        reset()
        checked = target
        check = scope.launch {
            if (measure(node, login) == NodeBenchmark.UNAUTHORIZED) update(true)
        }
    }

    fun reset() {
        check?.cancel()
        check = null
        checked = null
        update(false)
    }

    private fun update(failed: Boolean) {
        if (failed == loginFailed) return
        loginFailed = failed
        onChange(failed)
    }

    private fun isCustom(node: String): Boolean =
        DefaultNodes.ALL.none { it.uri == node } && DefaultNodes.TOR.none { it.uri == node }
}
