package one.monero.moneroone.paymentlinks

import android.content.Context
import io.horizontalsystems.monerokit.MoneroKit
import io.horizontalsystems.monerokit.Seed
import io.horizontalsystems.monerokit.SyncState
import io.horizontalsystems.monerokit.model.TransactionInfo
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import one.monero.moneroone.core.network.TorNetwork
import one.monero.moneroone.core.network.isOnionNode
import one.monero.moneroone.core.wallet.WalletManager
import timber.log.Timber

/** The node refused the sweep because the envelope's outputs are already spent: someone claimed it first. */
class AlreadyClaimedException : IllegalStateException("Already claimed")

/**
 * One envelope wallet, open in its own kit instance with its own files
 * ([EnvelopeFiles]), on the main wallet's node and proxy, syncing from the
 * link's restore height only.
 *
 * The kit runs one wallet at a time process-wide (KitManager), so the main
 * wallet's kit gives way while an envelope is open and starts again when it
 * closes. One envelope is open at a time: [open] closes the previous one.
 */
class EnvelopeWallet private constructor(
    val id: String,
    private val kit: MoneroKit,
    private val pausedMain: MoneroKit?
) {
    val syncState: StateFlow<SyncState> get() = kit.syncStateFlow

    @Volatile
    private var closed = false

    /**
     * Waits for the first full sync (or a failure). Returns what the envelope
     * holds, or null when the node did not answer within [timeoutMs].
     */
    suspend fun awaitSnapshot(timeoutMs: Long = SYNC_TIMEOUT_MS): EnvelopeSnapshot? {
        val state = withTimeoutOrNull(timeoutMs) {
            kit.syncStateFlow.first { it is SyncState.Synced || it is SyncState.NotSynced && it.error !is MoneroKit.SyncError.NotStarted }
        }
        if (state !is SyncState.Synced) {
            // The kit keeps only the message of what failed: log it, with the state, for the bug report.
            val error = (state as? SyncState.NotSynced)?.error
            if (error != null) Timber.w(error, "PaymentLinks: envelope check failed: %s", state) else Timber.w("PaymentLinks: envelope check timed out (%s)", kit.syncStateFlow.value)
            return null
        }
        // The kit publishes Synced, then the balance, in the same pass: let the balance land.
        delay(BALANCE_SETTLE_MS)
        return snapshot()
    }

    /** What the envelope holds now. Confirmations come from the chain height: the kit's history is refreshed only on money events. */
    fun snapshot(): EnvelopeSnapshot {
        val incoming = kit.allTransactionsFlow.value.filter {
            it.direction == TransactionInfo.Direction.Direction_In && !it.isFailed
        }
        val mined = incoming.filter { !it.isPending && it.blockheight > 0 }
        val chainHeight = kit.lastBlockHeight ?: 0L
        val confirmations = mined.maxOfOrNull { maxOf(it.confirmations, chainHeight - it.blockheight) } ?: 0L
        val balance = kit.balance
        val spent = kit.allTransactionsFlow.value.any {
            it.direction == TransactionInfo.Direction.Direction_Out && !it.isFailed
        }
        return EnvelopeSnapshot(
            checkedAt = System.currentTimeMillis(),
            fundingSeen = incoming.isNotEmpty(),
            fundingMined = mined.isNotEmpty(),
            confirmations = confirmations.coerceAtLeast(0),
            balance = balance.all,
            unlocked = balance.unlocked,
            spendSeen = spent,
            spendTxids = outgoingHashes().sorted(),
            fundingHeight = mined.minOfOrNull { it.blockheight }
        )
    }

    /**
     * Sweeps every unlocked output to [address]. Returns the sweep's txid and
     * the amount that arrives (fee paid), or null when the kit's history did not
     * show it in time. Throws [AlreadyClaimedException] when the node rejects a
     * double spend.
     */
    suspend fun sweepTo(address: String): TransactionInfo? {
        val before = outgoingHashes()
        try {
            withContext(Dispatchers.IO) { kit.send(0, address, null, sweepAll = true) }
        } catch (e: Exception) {
            val message = e.message.orEmpty().lowercase()
            if ("double spend" in message || "double_spend" in message || "already spent" in message) {
                throw AlreadyClaimedException()
            }
            throw e
        }
        return withTimeoutOrNull(TXID_WAIT_MS) {
            var sweep: TransactionInfo? = null
            while (sweep == null) {
                sweep = outgoing().firstOrNull { it.hash !in before }
                if (sweep == null) delay(500)
            }
            sweep
        }
    }

    private fun outgoing(): List<TransactionInfo> = kit.allTransactionsFlow.value
        .filter { it.direction == TransactionInfo.Direction.Direction_Out && !it.isFailed && it.hash != null }

    private fun outgoingHashes(): Set<String> = outgoing().mapNotNull { it.hash }.toSet()

    /** Stops and closes the envelope kit, then starts the main wallet's kit again. */
    suspend fun close() {
        if (closed) return
        closed = true
        withContext(NonCancellable) {
            runtimeLock.withLock {
                withContext(Dispatchers.IO) {
                    // The stop frees the kit's running slot for the main wallet: a failed release must not skip it.
                    try {
                        kit.release()
                    } catch (e: Exception) {
                        Timber.w(e, "PaymentLinks: envelope release failed")
                    }
                    try {
                        kit.stop()
                    } catch (e: Exception) {
                        Timber.w(e, "PaymentLinks: envelope stop failed")
                    }
                }
                if (current === this@EnvelopeWallet) current = null
                resumeMain(pausedMain)
            }
        }
    }

    companion object {
        private const val SYNC_TIMEOUT_MS = 120_000L
        private const val TXID_WAIT_MS = 30_000L
        private const val SLOT_WAIT_MS = 1_500L
        private const val BALANCE_SETTLE_MS = 750L

        private val runtimeLock = Mutex()
        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
        private var current: EnvelopeWallet? = null

        /**
         * Opens the envelope of [link] and starts its sync. The main wallet's kit
         * stops first and starts again on [close].
         */
        suspend fun open(context: Context, link: PaymentLink): EnvelopeWallet {
            current?.close()
            // One unit: a cancel between the main kit's stop and the return would leave the
            // envelope kit running and the main wallet stopped, with no one to close them.
            var opened: EnvelopeWallet? = null
            try {
                withContext(NonCancellable) { opened = openLocked(context, link) }
            } catch (e: CancellationException) {
                opened?.close()
                throw e
            }
            val envelope = checkNotNull(opened)
            if (!currentCoroutineContext().isActive) {
                envelope.close()
                throw CancellationException("Envelope open cancelled")
            }
            return envelope
        }

        /** Closes the open envelope, if any (Remove All). */
        suspend fun closeCurrent() {
            current?.close()
        }

        private suspend fun openLocked(context: Context, link: PaymentLink): EnvelopeWallet =
            runtimeLock.withLock {
                val app = context.applicationContext
                val node = EnvelopeNode.kitNodeString(app)
                val proxy = TorNetwork.route.walletProxy
                check(proxy.isNotEmpty() || !isOnionNode(node)) { "Tor is required for this node" }
                val kit = withContext(Dispatchers.IO) {
                    MoneroKit.getInstance(
                        context = app,
                        seed = Seed.Electrum(link.words, ""),
                        restoreDateOrHeight = link.height.toString(),
                        walletId = EnvelopeFiles.kitWalletId(app, link.id),
                        node = node,
                        trustNode = false,
                        proxyAddress = proxy
                    )
                }
                val paused = pauseMain()
                val envelope = EnvelopeWallet(link.id, kit, paused)
                current = envelope
                envelope.startKit()
                envelope
            }

        private suspend fun pauseMain(): MoneroKit? {
            val main = WalletManager.kit ?: return null
            withContext(Dispatchers.IO) {
                try {
                    main.stop()
                } catch (e: Exception) {
                    Timber.w(e, "PaymentLinks: main kit stop failed")
                }
            }
            return main
        }

        private suspend fun resumeMain(paused: MoneroKit?) {
            // A switch or node change replaced the kit meanwhile: the new one starts on its own.
            if (paused == null || WalletManager.kit !== paused) return
            try {
                WalletManager.start()
            } catch (e: Exception) {
                Timber.w(e, "PaymentLinks: main kit restart failed")
            }
        }
    }

    private fun startKit() {
        // Off the main thread: the start resolves the node's name and opens the wallet file, and the
        // system refuses network on the main thread (NetworkOnMainThreadException with a host name).
        val start: Job = scope.launch(Dispatchers.IO) { kit.start() }
        // A resume can start the main kit again between its stop and this start; then this kit waits for
        // the running slot. Stopping the main kit hands the slot over (KitManager promotes the waiting kit).
        scope.launch {
            delay(SLOT_WAIT_MS)
            while (start.isActive && !closed) {
                val state = kit.syncStateFlow.value
                if (state is SyncState.Connecting && state.waiting) {
                    Timber.d("PaymentLinks: envelope waits for the kit slot; pausing the main kit again")
                    pauseMain()
                }
                delay(SLOT_WAIT_MS)
            }
        }
    }
}
