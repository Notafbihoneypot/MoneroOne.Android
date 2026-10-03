package one.monero.moneroone.paymentlinks

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import io.horizontalsystems.monerokit.SyncState
import io.horizontalsystems.monerokit.model.TransactionInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import one.monero.moneroone.core.wallet.WalletManager
import timber.log.Timber

/** The envelope open on screen (claim sheet or link details). */
data class EnvelopeSession(
    val id: String,
    val phase: Phase,
    val snapshot: EnvelopeSnapshot? = null,
    val error: String? = null
) {
    enum class Phase { CHECKING, LIVE, SWEEPING, ERROR }
}

/** What the last sweep of the open envelope did. */
sealed class SweepOutcome {
    data class Sent(val txid: String?, val amount: Long) : SweepOutcome()
    object AlreadyClaimed : SweepOutcome()
    data class Failed(val message: String) : SweepOutcome()
}

/**
 * Payment links: drafts for Send, the records, the envelope on screen, and
 * the claim and reclaim sweeps. Activity-scoped, so a result outlives the
 * screen that asked for it.
 */
class PaymentLinksViewModel(application: Application) : AndroidViewModel(application) {

    private val context get() = getApplication<Application>()
    val store = PaymentLinkStore.get(application)
    val sent: StateFlow<List<SentLink>> = store.sent
    val received: StateFlow<List<ReceivedLink>> = store.received

    private val _session = MutableStateFlow<EnvelopeSession?>(null)
    val session: StateFlow<EnvelopeSession?> = _session.asStateFlow()

    private val _sweep = MutableStateFlow<SweepOutcome?>(null)
    val sweep: StateFlow<SweepOutcome?> = _sweep.asStateFlow()

    private val sessionLock = Mutex()
    private var envelope: EnvelopeWallet? = null
    private var sessionJob: Job? = null
    private var refreshJob: Job? = null

    init {
        // Files of settled or removed links go; live envelopes keep theirs.
        viewModelScope.launch(Dispatchers.IO) {
            if (envelope == null) EnvelopeFiles.sweep(context, store.liveEnvelopeIds())
        }
    }

    // ---------------------------------------------------------------- sender

    /**
     * A new envelope for [amount] (atomic). Needs the main wallet's daemon
     * height. Throws when the wallet is not connected or the restore check fails.
     */
    suspend fun prepare(amount: Long): EnvelopeDraft = withContext(Dispatchers.IO) {
        val height = WalletManager.kit?.lastBlockHeight?.takeIf { it > 0 }
            ?: throw IllegalStateException("Wallet is not connected yet. Wait for the wallet to reconnect and try again.")
        EnvelopeFactory.create(context, amount, height, System.currentTimeMillis() / 1000)
    }

    /** The draft was left before funding: nothing was saved, its files go. */
    fun discard(draft: EnvelopeDraft) {
        if (store.sent(draft.link.id) != null) return
        viewModelScope.launch(Dispatchers.IO) { EnvelopeFiles.delete(context, draft.link.id) }
    }

    /** Saves the record before any broadcast (spec "Sender flow" step 2). False: do not fund. */
    fun recordBeforeFunding(draft: EnvelopeDraft, walletId: String, feeReserve: Long): Boolean {
        val link = draft.link
        return store.putSent(
            SentLink(
                id = link.id, words = link.words, height = link.height, amount = link.amount, expiry = link.expiry,
                createdAtMillis = System.currentTimeMillis(), walletId = walletId, envelopeAddress = draft.address,
                feeReserve = feeReserve, state = SentState.CREATED
            )
        )
    }

    /** The funding broadcast ended. On success the funding txid is read from the wallet's history. */
    fun fundingFinished(id: String, success: Boolean) {
        store.updateSent(id) { it.copy(state = if (success) SentState.FUNDED else SentState.NOT_FUNDED) }
        if (!success) return
        viewModelScope.launch {
            val address = store.sent(id)?.envelopeAddress ?: return@launch
            val txid = withTimeoutOrNull(FUNDING_TXID_WAIT_MS) {
                var found: String? = null
                while (found == null) {
                    found = fundingTxid(address)
                    if (found == null) delay(1_000)
                }
                found
            }
            if (txid != null) store.updateSent(id) { it.copy(fundingTxid = txid) }
        }
    }

    /** The main wallet's outgoing transaction to [envelopeAddress], if its history holds one. */
    fun fundingTxid(envelopeAddress: String): String? = WalletManager.transactionsFlow.value.firstOrNull { tx ->
        tx.direction == TransactionInfo.Direction.Direction_Out && !tx.isFailed &&
            tx.transfers?.any { it.address == envelopeAddress } == true
    }?.hash

    fun removeSent(id: String) {
        viewModelScope.launch {
            if (_session.value?.id == id) closeSession()
            store.removeSent(id)
            withContext(Dispatchers.IO) { EnvelopeFiles.delete(context, id) }
        }
    }

    // ------------------------------------------------------------- recipient

    fun removeReceived(id: String) {
        viewModelScope.launch {
            if (_session.value?.id == id) closeSession()
            store.removeReceived(id)
            withContext(Dispatchers.IO) { EnvelopeFiles.delete(context, id) }
        }
    }

    // --------------------------------------------------------------- session

    /** Opens the envelope of [id] (sent or received) and keeps its state current until [closeSession]. */
    fun openSession(id: String) {
        if (_session.value?.id == id && _session.value?.phase != EnvelopeSession.Phase.ERROR) return
        _sweep.value = null
        refreshJob?.cancel()
        sessionJob?.cancel()
        sessionJob = viewModelScope.launch {
            sessionLock.withLock {
                closeEnvelopeLocked()
                val link = store.sent(id)?.link ?: store.received(id)?.link
                if (link == null) {
                    _session.value = null
                    return@withLock
                }
                _session.value = EnvelopeSession(id, EnvelopeSession.Phase.CHECKING)
                val opened = try {
                    EnvelopeWallet.open(context, link)
                } catch (e: Exception) {
                    Timber.w("PaymentLinks: envelope open failed (%s)", e.javaClass.simpleName)
                    _session.value = EnvelopeSession(id, EnvelopeSession.Phase.ERROR, error = e.message)
                    return@withLock
                }
                envelope = opened
                val first = opened.awaitSnapshot()
                if (first == null) {
                    val state = opened.syncState.value
                    val message = (state as? SyncState.NotSynced)?.error?.message
                    _session.value = EnvelopeSession(id, EnvelopeSession.Phase.ERROR, error = message)
                    return@withLock
                }
                publish(id, first)
            }
            // Live: confirmations and balance as blocks arrive.
            while (true) {
                delay(LIVE_INTERVAL_MS)
                val open = envelope ?: break
                val current = _session.value ?: break
                if (current.id != id || current.phase == EnvelopeSession.Phase.SWEEPING) continue
                if (open.syncState.value is SyncState.Synced) {
                    val next = open.snapshot()
                    if (next.copy(checkedAt = 0) != current.snapshot?.copy(checkedAt = 0)) publish(id, next)
                }
            }
        }
    }

    private fun publish(id: String, snapshot: EnvelopeSnapshot) {
        store.updateSent(id) { it.copy(lastCheck = snapshot) }
        store.updateReceived(id) { it.copy(lastCheck = snapshot) }
        _session.value = EnvelopeSession(id, EnvelopeSession.Phase.LIVE, snapshot)
    }

    fun closeSession() {
        sessionJob?.cancel()
        sessionJob = null
        _session.value = null
        viewModelScope.launch(NonCancellable) { sessionLock.withLock { closeEnvelopeLocked() } }
    }

    private suspend fun closeEnvelopeLocked() {
        val open = envelope ?: return
        envelope = null
        open.close()
        val id = open.id
        // A settled link's files are no longer needed.
        if (id !in store.liveEnvelopeIds()) withContext(Dispatchers.IO) { EnvelopeFiles.delete(context, id) }
    }

    /**
     * Sweeps the open envelope to [address]: a claim of a received link or a
     * reclaim of a sent one. [walletName] names the destination in the record.
     */
    fun sweepOpenEnvelope(address: String, walletName: String) {
        val current = _session.value ?: return
        val open = envelope ?: return
        if (current.phase != EnvelopeSession.Phase.LIVE) return
        val id = current.id
        val amount = current.snapshot?.unlocked ?: 0L
        _session.value = current.copy(phase = EnvelopeSession.Phase.SWEEPING)
        _sweep.value = null
        viewModelScope.launch(NonCancellable) {
            val outcome = try {
                SweepOutcome.Sent(open.sweepTo(address), amount)
            } catch (e: AlreadyClaimedException) {
                SweepOutcome.AlreadyClaimed
            } catch (e: Exception) {
                Timber.w("PaymentLinks: sweep failed (%s)", e.javaClass.simpleName)
                SweepOutcome.Failed(e.message ?: "")
            }
            when (outcome) {
                is SweepOutcome.Sent -> {
                    store.updateSent(id) { it.copy(reclaimTxid = outcome.txid ?: "") }
                    store.updateReceived(id) {
                        it.copy(
                            state = ReceivedState.CLAIMED, words = null, claimTxid = outcome.txid,
                            claimedAmount = amount, claimedTo = walletName
                        )
                    }
                }
                SweepOutcome.AlreadyClaimed -> store.updateSent(id) { it.copy(reclaimLostRace = true) }
                is SweepOutcome.Failed -> {}
            }
            _sweep.value = outcome
            val snapshot = open.snapshot()
            _session.update { it?.takeIf { s -> s.id == id }?.copy(phase = EnvelopeSession.Phase.LIVE, snapshot = snapshot) }
            if (outcome !is SweepOutcome.Failed) {
                // Done with this envelope: close it so the main wallet syncs again and sees the money.
                closeSession()
            }
        }
    }

    fun clearSweep() {
        _sweep.value = null
    }

    /**
     * Checks every sent link that can still change, one envelope at a time
     * (the list screen). Stops when a session opens or [stopRefresh] runs.
     */
    fun refreshSent() {
        if (refreshJob?.isActive == true || _session.value != null) return
        refreshJob = viewModelScope.launch {
            val now = System.currentTimeMillis()
            val due = store.sent.value.filter {
                !LinkStatusRules.isSettled(it) && now - (it.lastCheck?.checkedAt ?: 0) > REFRESH_AFTER_MS
            }
            for (link in due) {
                sessionLock.withLock {
                    val opened = try {
                        EnvelopeWallet.open(context, link.link)
                    } catch (e: Exception) {
                        return@withLock
                    }
                    try {
                        opened.awaitSnapshot()?.let { snapshot -> store.updateSent(link.id) { it.copy(lastCheck = snapshot) } }
                    } finally {
                        withContext(NonCancellable) { opened.close() }
                    }
                }
            }
        }
    }

    fun stopRefresh() {
        refreshJob?.cancel()
        refreshJob = null
    }

    /** Waits until the main wallet's kit runs and is synced again (after an envelope closed). */
    suspend fun awaitMainWalletSynced(timeoutMs: Long = 90_000): Boolean =
        withTimeoutOrNull(timeoutMs) { WalletManager.syncStateFlow.first { it is SyncState.Synced } } != null

    override fun onCleared() {
        super.onCleared()
        sessionJob?.cancel()
        refreshJob?.cancel()
        val open = envelope
        envelope = null
        if (open != null) {
            kotlinx.coroutines.CoroutineScope(Dispatchers.Main + NonCancellable).launch { open.close() }
        }
    }

    companion object {
        private const val LIVE_INTERVAL_MS = 3_000L
        private const val REFRESH_AFTER_MS = 20_000L
        private const val FUNDING_TXID_WAIT_MS = 60_000L
    }
}
