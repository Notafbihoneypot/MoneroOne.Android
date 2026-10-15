package one.monero.moneroone.paymentlinks

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import io.horizontalsystems.monerokit.SyncState
import io.horizontalsystems.monerokit.model.TransactionInfo
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
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
    private var openCheckJob: Job? = null
    private var lastOpenCheckAt = 0L

    init {
        // Files of settled or removed links go; live envelopes keep theirs.
        viewModelScope.launch(Dispatchers.IO) {
            if (envelope == null) EnvelopeFiles.sweep(context, store.liveEnvelopeIds())
        }
        // A claim's secret goes once the claim is in a block, as the open wallet's history shows it.
        viewModelScope.launch {
            combine(WalletManager.transactionsFlow, store.received) { txs, received ->
                val mined = txs.filter { !it.isPending && !it.isFailed && it.blockheight > 0 }.mapNotNull { it.hash }.toSet()
                LinkStatusRules.claimWordsToForget(received, mined)
            }.collect { ids ->
                ids.forEach { id -> store.updateReceived(id) { it.forgettingSecret() } }
            }
        }
        // A sweep the app lost mid-flight (killed, swiped away): its envelope's spend shows up in the open wallet.
        viewModelScope.launch {
            combine(WalletManager.transactionsFlow, store.sent, store.received) { txs, sent, received ->
                val incoming = txs.filter { it.direction == TransactionInfo.Direction.Direction_In && !it.isFailed && it.hash != null }
                val hashes = incoming.mapNotNull { it.hash }.toSet()
                val open = WalletManager.currentWalletId
                Triple(LinkStatusRules.lostReclaims(sent, open, hashes), LinkStatusRules.lostClaims(received, open, hashes), incoming)
            }.collect { (reclaims, claims, incoming) ->
                reclaims.forEach { (id, txid) -> store.updateSent(id) { it.copy(reclaimTxid = txid, reclaimLostRace = false) } }
                claims.forEach { (id, txid) ->
                    val amount = incoming.firstOrNull { it.hash == txid }?.amount
                    store.updateReceived(id) {
                        it.copy(state = ReceivedState.CLAIMED, claimTxid = txid, claimedAmount = amount ?: it.claimedAmount, claimedTo = it.claimStartedTo)
                    }
                }
            }
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
                id = link.id, words = if (link.version == 1) link.words else emptyList(), secret = link.secretHex,
                height = link.height, amount = draft.amount, expiry = draft.expiry,
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

    /**
     * Before a retry funds [id] again: syncs its envelope once, so a broadcast
     * that went out after all (the error came after the node took it) is
     * seen, and records what it saw. Returns whether money reached the
     * envelope, or null when the check or the main wallet's restart failed.
     * The main wallet runs and is synced again when this returns true or false.
     */
    suspend fun fundingSeenNow(id: String): Boolean? {
        val link = store.sent(id) ?: return null
        closeSession()
        val seen = sessionLock.withLock {
            val opened = try {
                EnvelopeWallet.open(context, link.link)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                return@withLock null
            }
            try {
                opened.awaitSnapshot()?.also { snapshot -> store.updateSent(id) { it.copy(lastCheck = snapshot) } }?.fundingSeen
            } finally {
                withContext(NonCancellable) { opened.close() }
            }
        }
        if (!awaitMainWalletSynced()) return null
        return seen
    }

    /**
     * A retry from Payment Links: only from the wallet that funded it first (its
     * history is where a lost broadcast shows), after [fundingSeenNow] found nothing.
     */
    fun retryFunding(id: String, activeWalletId: String?): Boolean {
        val link = store.sent(id) ?: return false
        if (link.walletId != activeWalletId) return false
        return store.updateSent(id) { it.copy(state = SentState.CREATED) } != null
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
        openCheckJob?.cancel()
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
                } catch (e: CancellationException) {
                    throw e
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
                    // A check that failed or timed out holds nothing: close it so the main wallet runs again.
                    // Retry opens it anew.
                    closeEnvelopeLocked()
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
        Timber.d(
            "PaymentLinks: envelope check seen=%b mined=%b conf=%d unlocked=%b spent=%b",
            snapshot.fundingSeen, snapshot.fundingMined, snapshot.confirmations, snapshot.unlocked > 0, snapshot.spendSeen
        )
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
        if (refreshWanted) refreshSent()
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
        // Saved before the broadcast, so a sweep the app loses mid-flight is found later by its hash.
        val now = System.currentTimeMillis()
        val earlierAttempt = when (kind) {
            SweepKind.RECLAIM -> store.sent(id)?.reclaimStartedAt != null
            SweepKind.CLAIM -> store.received(id)?.claimStartedAt != null
        }
        val into = WalletManager.currentWalletId
        when (kind) {
            SweepKind.RECLAIM -> store.updateSent(id) { it.copy(reclaimStartedAt = it.reclaimStartedAt ?: now, reclaimWallet = into) }
            SweepKind.CLAIM -> store.updateReceived(id) {
                it.copy(claimStartedAt = it.claimStartedAt ?: now, claimStartedTo = walletName, claimWallet = into)
            }
        }
        sweeping = true
        closeAfterSweep = false
        _session.value = current.copy(phase = EnvelopeSession.Phase.SWEEPING)
        _sweep.value = null
        viewModelScope.launch(NonCancellable) {
            val outcome = try {
                val sweepTx = open.sweepTo(address)
                // What arrives: the sweep's amount after its fee; the unlocked total until the kit shows it.
                SweepOutcome.Sent(sweepTx?.hash, sweepTx?.amount?.takeIf { it > 0 } ?: amount)
            } catch (e: AlreadyClaimedException) {
                SweepOutcome.AlreadyClaimed
            } catch (e: Exception) {
                Timber.w("PaymentLinks: sweep failed (%s)", e.javaClass.simpleName)
                SweepOutcome.Failed(e.message ?: "")
            }
            record(id, kind, outcome, (outcome as? SweepOutcome.Sent)?.amount ?: amount, walletName, earlierAttempt)
            _sweep.value = SweepResult(id, kind, outcome)
            val snapshot = runCatching { open.snapshot() }.getOrNull()
            _session.update { s -> s?.takeIf { it.id == id }?.copy(phase = EnvelopeSession.Phase.LIVE, snapshot = snapshot ?: s.snapshot) }
            sweeping = false
            // Done with this envelope: close it so the main wallet syncs again and sees the money.
            if (outcome !is SweepOutcome.Failed || closeAfterSweep) closeSession()
        }
    }

    private fun record(id: String, kind: SweepKind, outcome: SweepOutcome, amount: Long, walletName: String, earlierAttempt: Boolean) {
        when (kind) {
            SweepKind.RECLAIM -> when (outcome) {
                is SweepOutcome.Sent -> store.updateSent(id) { it.copy(reclaimTxid = outcome.txid ?: "") }
                // After a reclaim the app lost, the spend that beat this one may be our own: the open
                // wallet's history decides (see init). Otherwise a claim won the race.
                SweepOutcome.AlreadyClaimed -> if (!earlierAttempt) store.updateSent(id) { it.copy(reclaimLostRace = true) }
                is SweepOutcome.Failed -> {}
            }
            SweepKind.CLAIM -> {
                if (outcome is SweepOutcome.Failed) return
                // The secret stays until the claim is mined (see init): a claim that never confirms leaves the money
                // in the envelope.
                val claimed: (ReceivedLink) -> ReceivedLink = {
                    if (outcome is SweepOutcome.Sent) {
                        it.copy(state = ReceivedState.CLAIMED, claimTxid = outcome.txid, claimedAmount = amount, claimedTo = walletName)
                    } else {
                        it.copy(state = ReceivedState.CLAIMED)
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
     * (the list screen), every [REFRESH_INTERVAL_MS] until [stopRefresh] runs.
     * Skips a round while a claim sheet or detail screen holds a session.
     */
    fun refreshSent() {
        refreshWanted = true
        if (refreshJob?.isActive == true) return
        refreshJob = viewModelScope.launch {
            // While the list shows: pending links turn ready, ready ones turn claimed.
            while (true) {
                checkDueSent()
                delay(REFRESH_INTERVAL_MS)
            }
        }
    }

    private suspend fun checkDueSent() {
        val now = System.currentTimeMillis()
        checkSent(store.sent.value.filter {
            !LinkStatusRules.isSettled(it) && now - (it.lastCheck?.checkedAt ?: 0) > REFRESH_AFTER_MS
        })
    }

    /**
     * The Wallet tab's look at the open sent links of [walletId] (spec Round 3), so the balance
     * card's line drops a claimed link: when the tab shows and as blocks arrive, at most once per
     * [OPEN_CHECK_EVERY_MS] (about a block), and only while the main wallet is synced, because a
     * look stops the main wallet for a moment. The Links filter's [refreshSent] covers it there.
     */
    fun checkOpenLinks(walletId: String?) {
        if (walletId == null || refreshJob?.isActive == true || openCheckJob?.isActive == true || _session.value != null) return
        if (WalletManager.syncStateFlow.value !is SyncState.Synced) return
        val now = System.currentTimeMillis()
        if (now - lastOpenCheckAt < OPEN_CHECK_EVERY_MS) return
        val due = store.sent.value.filter {
            it.walletId == walletId && OpenLinkRules.isOpen(it, now / 1000) &&
                now - (it.lastCheck?.checkedAt ?: 0) > OPEN_CHECK_EVERY_MS
        }
        if (due.isEmpty()) return
        lastOpenCheckAt = now
        openCheckJob = viewModelScope.launch { checkSent(due) }
    }

    private suspend fun checkSent(due: List<SentLink>) {
        for (link in due) {
            // A claim sheet or detail screen has the envelope runtime: leave it alone.
            if (_session.value != null) return
            sessionLock.withLock {
                val opened = try {
                    EnvelopeWallet.open(context, link.link)
                } catch (e: CancellationException) {
                    throw e
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

    fun stopRefresh() {
        refreshWanted = false
        refreshJob?.cancel()
        refreshJob = null
    }

    /** The list screen shows: a session that cancelled the refresh starts it again when it closes. */
    private var refreshWanted = false

    /** Waits until the main wallet's kit runs and is synced again (after an envelope closed). */
    suspend fun awaitMainWalletSynced(timeoutMs: Long = 90_000): Boolean =
        withTimeoutOrNull(timeoutMs) { WalletManager.syncStateFlow.first { it is SyncState.Synced } } != null

    override fun onCleared() {
        super.onCleared()
        sessionJob?.cancel()
        refreshJob?.cancel()
        openCheckJob?.cancel()
        val open = envelope
        envelope = null
        if (open != null) {
            kotlinx.coroutines.CoroutineScope(Dispatchers.Main + NonCancellable).launch { open.close() }
        }
    }

    companion object {
        private const val LIVE_INTERVAL_MS = 3_000L
        private const val REFRESH_AFTER_MS = 20_000L
        private const val REFRESH_INTERVAL_MS = 30_000L
        /** A little under a block (about 2 minutes). */
        private const val OPEN_CHECK_EVERY_MS = 100_000L
        private const val FUNDING_TXID_WAIT_MS = 60_000L
    }
}
