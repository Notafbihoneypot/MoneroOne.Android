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

enum class SweepKind { CLAIM, RECLAIM }

/** What the last sweep of envelope [id] did. */
data class SweepResult(val id: String, val kind: SweepKind, val outcome: SweepOutcome)

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

    private val _sweep = MutableStateFlow<SweepResult?>(null)
    val sweep: StateFlow<SweepResult?> = _sweep.asStateFlow()

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

    /**
     * Before a retry funds [id] again: true when a funding of this envelope is
     * already known (in the envelope's last check or the main wallet's history),
     * so nothing may be sent twice. Marks the record funded in that case.
     */
    fun fundingAlreadySent(id: String): Boolean {
        val link = store.sent(id) ?: return false
        val txid = fundingTxid(link.envelopeAddress)
        if (txid == null && link.lastCheck?.fundingSeen != true) return false
        store.updateSent(id) { it.copy(state = SentState.FUNDED, fundingTxid = txid ?: it.fundingTxid) }
        return true
    }

    /** A retry from Payment Links funds from the active wallet: the record follows it. */
    fun retryFrom(id: String, walletId: String) {
        store.updateSent(id) { it.copy(walletId = walletId, state = SentState.CREATED) }
    }

    /**
     * Closes the open envelope so the main wallet runs again, then waits for it
     * to sync: a funding needs the main kit (the kit runs one wallet at a time).
     */
    suspend fun resumeMainWallet(): Boolean {
        closeSession()
        sessionLock.withLock { }
        return awaitMainWalletSynced()
    }

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

    /** Opens the envelope of [id] again for a fresh check (Retry, Check Again). */
    fun recheck(id: String) {
        if (sweeping) return
        sessionJob?.cancel()
        _session.value = null
        openSession(id)
    }

    private fun publish(id: String, snapshot: EnvelopeSnapshot) {
        store.updateSent(id) { it.copy(lastCheck = snapshot) }
        store.updateReceived(id) { it.copy(lastCheck = snapshot) }
        _session.value = EnvelopeSession(id, EnvelopeSession.Phase.LIVE, snapshot)
    }

    fun closeSession() {
        // A sweep in flight keeps its kit open; it closes the session when it ends.
        if (sweeping) {
            closeAfterSweep = true
            return
        }
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

    @Volatile
    private var sweeping = false
    private var closeAfterSweep = false

    /**
     * Sweeps the open envelope to [address] (the active wallet's receive
     * address): a [SweepKind.CLAIM] of a received link or a [SweepKind.RECLAIM]
     * of a sent one. [walletName] names the destination in the record.
     */
    fun sweepOpenEnvelope(kind: SweepKind, address: String, walletName: String) {
        val current = _session.value ?: return
        val open = envelope ?: return
        if (current.phase != EnvelopeSession.Phase.LIVE || sweeping) return
        val id = current.id
        val amount = current.snapshot?.unlocked ?: 0L
        sweeping = true
        closeAfterSweep = false
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
            record(id, kind, outcome, amount, walletName)
            _sweep.value = SweepResult(id, kind, outcome)
            val snapshot = runCatching { open.snapshot() }.getOrNull()
            _session.update { s -> s?.takeIf { it.id == id }?.copy(phase = EnvelopeSession.Phase.LIVE, snapshot = snapshot ?: s.snapshot) }
            sweeping = false
            // Done with this envelope: close it so the main wallet syncs again and sees the money.
            if (outcome !is SweepOutcome.Failed || closeAfterSweep) closeSession()
        }
    }

    private fun record(id: String, kind: SweepKind, outcome: SweepOutcome, amount: Long, walletName: String) {
        when (kind) {
            SweepKind.RECLAIM -> when (outcome) {
                is SweepOutcome.Sent -> store.updateSent(id) { it.copy(reclaimTxid = outcome.txid ?: "") }
                SweepOutcome.AlreadyClaimed -> store.updateSent(id) { it.copy(reclaimLostRace = true) }
                is SweepOutcome.Failed -> {}
            }
            SweepKind.CLAIM -> {
                if (outcome is SweepOutcome.Failed) return
                val claimed: (ReceivedLink) -> ReceivedLink = {
                    if (outcome is SweepOutcome.Sent) {
                        it.copy(state = ReceivedState.CLAIMED, words = null, claimTxid = outcome.txid, claimedAmount = amount, claimedTo = walletName)
                    } else {
                        it.copy(state = ReceivedState.CLAIMED, words = null)
                    }
                }
                if (store.updateReceived(id, claimed) == null) {
                    // Claimed by another wallet on this phone from a link it sent: keep a received entry too.
                    store.sent(id)?.let { sent ->
                        store.putReceived(
                            claimed(ReceivedLink(sent.id, null, sent.height, sent.amount, sent.expiry, System.currentTimeMillis()))
                        )
                    }
                }
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
