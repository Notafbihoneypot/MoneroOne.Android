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
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.dropWhile
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

/** What the last sweep of envelope [id] did. [walletName]: the wallet it went to. */
data class SweepResult(val id: String, val kind: SweepKind, val outcome: SweepOutcome, val walletName: String = "")

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
    private var openLookJob: Job? = null
    private val _holdSyncStatus = MutableStateFlow<String?>(null)

    /**
     * The wallet whose Wallet-tab link check has the main wallet stopped, until the main wallet is
     * synced again after it ([HOLD_AFTER_MS] at most); null otherwise. The wallet was synced when the
     * check started, so the tab keeps saying so instead of "Reaching node" for each check.
     */
    val holdSyncStatus: StateFlow<String?> = _holdSyncStatus.asStateFlow()

    init {
        // Files of settled or removed links go; live envelopes keep theirs.
        viewModelScope.launch(Dispatchers.IO) {
            if (envelope == null) EnvelopeFiles.sweep(context, store.liveEnvelopeIds())
        }
        // Retain received secrets until explicit Remove All. A mined claim can
        // reorg or leave newer locked funding behind.
        // A record saved without its funding txid (the app quit mid-broadcast, or the error came after the node
        // took it): the open wallet's outgoing transaction to its envelope is its funding. The link shows again,
        // as Waiting, and the status never contradicts the wallet's own history.
        // A funding the open wallet marks failed (dropped or rejected) sent no money: the link reads as not funded.
        viewModelScope.launch {
            combine(WalletManager.transactionsFlow, store.sent) { txs, sent ->
                val (failed, live) = txs.filter { it.hash != null }.partition { it.isFailed }
                LinkStatusRules.fundingFailures(sent, failed.mapNotNull { it.hash }.toSet(), live.mapNotNull { it.hash }.toSet()) to
                    LinkStatusRules.fundingsToAdopt(sent) { address -> fundingTxid(address, txs) }
            }.collect { (failures, found) ->
                failures.forEach { (id, failed) -> store.updateSent(id) { it.copy(fundingFailed = failed) } }
                found.forEach { (id, txid) ->
                    store.updateSent(id) { it.copy(fundingTxid = txid, fundingFailed = false, state = SentState.FUNDED) }
                }
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

    /** The name the next link gets ("Link 9"). */
    fun nextName(): String = store.nextName()

    /** Saves this phone's note for a link (blank: back to "Link 7"). */
    fun rename(id: String, note: String?) = store.rename(id, note)

    /** The draft was left before funding: nothing was saved, its files go. */
    fun discard(draft: EnvelopeDraft) {
        if (store.sent(draft.link.id) != null) return
        viewModelScope.launch(Dispatchers.IO) { EnvelopeFiles.delete(context, draft.link.id) }
    }

    /** Saves the record before any broadcast (spec "Sender flow" step 2). False: do not fund. */
    fun recordBeforeFunding(draft: EnvelopeDraft, walletId: String, feeReserve: Long, note: String? = null): Boolean {
        val link = draft.link
        return store.createSent(
            SentLink(
                id = link.id, words = if (link.version == 1) link.words else emptyList(), secret = link.secretHex,
                height = link.height, amount = draft.amount, expiry = draft.expiry,
                createdAtMillis = System.currentTimeMillis(), walletId = walletId, envelopeAddress = draft.address,
                feeReserve = feeReserve, state = SentState.CREATED, note = LinkNames.cleanNote(note)
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
            if (txid != null) store.updateSent(id) { it.copy(fundingTxid = txid, fundingFailed = false) }
        }
    }

    /** The main wallet's outgoing transaction to [envelopeAddress], if its history ([transactions]) holds one. */
    fun fundingTxid(envelopeAddress: String, transactions: List<TransactionInfo> = WalletManager.transactionsFlow.value): String? =
        transactions.firstOrNull { tx ->
            tx.direction == TransactionInfo.Direction.Direction_Out && !tx.isFailed &&
                tx.transfers?.any { it.address == envelopeAddress } == true
        }?.hash

    /** The block of [link]'s funding in the open wallet's history; null when it is not in a block there. */
    private fun fundingBlock(link: SentLink): Long? =
        fundingTransaction(link, WalletManager.transactionsFlow.value)?.takeIf { !it.isPending && it.blockheight > 0 }?.blockheight

    /**
     * Rescans started in this run, per link. Two at most: a rescan cut short (a detail opened, the node
     * went, the sync timed out) gets one more, and one that still misses the funding is not repeated.
     */
    private val rescans = mutableMapOf<String, Int>()

    /**
     * Hidden links (no funding anywhere) checked in this run. Each check stops the main wallet, so a hidden
     * link gets one per run, in case money reached its envelope from outside this wallet's history.
     */
    private val hiddenChecked = mutableSetOf<String>()

    /**
     * Opens envelope [id] ([link]) and waits for its first full sync. [hold] gets each envelope as it
     * opens, so the caller closes it whatever happens. An envelope whose first scan never finished is
     * restored before it opens ([EnvelopeScanRules]). When the open wallet shows a sent link's funding in
     * a block that the sync did not see, the envelope is restored and scanned again from no later than
     * that block ([LinkStatusRules.rescanFrom]), [MAX_RESCANS] per run, with no UI change.
     * Call under [sessionLock]. Returns the first snapshot (null: the sync failed or timed out). Throws
     * when an open fails.
     */
    private suspend fun openChecked(id: String, link: PaymentLink, hold: (EnvelopeWallet) -> Unit): EnvelopeSnapshot? {
        // Read before the open: the main wallet stops while the envelope runs.
        val record = store.sent(id)
        val fundingBlock = record?.let(::fundingBlock)
        val opened = EnvelopeWallet.open(context, link).also(hold)
        val first = opened.awaitSnapshot() ?: return null
        val from = record?.let { LinkStatusRules.rescanFrom(it, first, fundingBlock) } ?: return first
        val tries = rescans[id] ?: 0
        if (tries >= MAX_RESCANS) return first
        rescans[id] = tries + 1
        Timber.w("PaymentLinks: link %s: funding in block %d not seen; rescanning from %d", record.number, fundingBlock, from)
        withContext(NonCancellable) {
            opened.close()
            withContext(Dispatchers.IO) { EnvelopeFiles.deleteWallet(context, id) }
        }
        val again = EnvelopeWallet.open(context, link, restoreHeight = from).also(hold)
        return again.awaitSnapshot()
    }

    // --------------------------------------------------------------- session

    /** Opens the envelope of [id] (sent or received) and keeps its state current until [closeSession]. */
    fun openSession(id: String) {
        // A second deep link must not close the wallet during a broadcast.
        if (sweeping) return
        if (_session.value?.id == id && _session.value?.phase != EnvelopeSession.Phase.ERROR) return
        _sweep.value = null
        refreshJob?.cancel()
        openLookJob?.cancel()
        sessionJob?.cancel()
        sessionJob = viewModelScope.launch {
            var estimatesClaim = false
            sessionLock.withLock {
                closeEnvelopeLocked()
                val link = store.sent(id)?.link ?: store.received(id)?.link
                if (link == null) {
                    _session.value = null
                    return@withLock
                }
                // A link sent to us: its checks carry the claim's fee estimate (what a claim delivers).
                estimatesClaim = store.sent(id) == null
                _session.value = EnvelopeSession(id, EnvelopeSession.Phase.CHECKING)
                val first = try {
                    openChecked(id, link) { envelope = it }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Timber.w(e, "PaymentLinks: envelope open failed")
                    closeEnvelopeLocked()
                    _session.value = EnvelopeSession(id, EnvelopeSession.Phase.ERROR, error = e.message)
                    return@withLock
                }
                val opened = checkNotNull(envelope)
                if (first == null) {
                    val state = opened.syncState.value
                    val message = (state as? SyncState.NotSynced)?.error?.message
                    // A check that failed or timed out holds nothing: close it so the main wallet runs again.
                    // Retry opens it anew.
                    closeEnvelopeLocked()
                    _session.value = EnvelopeSession(id, EnvelopeSession.Phase.ERROR, error = message)
                    return@withLock
                }
                publish(id, if (estimatesClaim) first.copy(sweepFee = opened.sweepFee(first.balance)) else first)
            }
            // Live: confirmations and balance as blocks arrive. A received link's claim fee is
            // estimated again when the balance moves, and every FEE_EVERY_MS.
            var feeAt = System.currentTimeMillis()
            while (true) {
                delay(LIVE_INTERVAL_MS)
                val open = envelope ?: break
                val current = _session.value ?: break
                if (current.id != id || current.phase == EnvelopeSession.Phase.SWEEPING) continue
                if (open.syncState.value is SyncState.Synced) {
                    val seen = open.snapshot()
                    val last = current.snapshot
                    val moved = seen.copy(checkedAt = 0, sweepFee = null) != last?.copy(checkedAt = 0, sweepFee = null)
                    val now = System.currentTimeMillis()
                    val next = if (estimatesClaim && (moved || now - feeAt >= FEE_EVERY_MS)) {
                        feeAt = now
                        seen.copy(sweepFee = open.sweepFee(seen.balance))
                    } else {
                        seen.copy(sweepFee = last?.sweepFee)
                    }
                    // The estimate suspends: a claim may have started meanwhile.
                    val still = _session.value
                    if (sweeping || still?.id != id || still.phase == EnvelopeSession.Phase.SWEEPING) continue
                    if (next.copy(checkedAt = 0) != last?.copy(checkedAt = 0)) publish(id, next)
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

    /** Debug builds only (Timber plants no tree in release). The line carries the link's number, never its note, address or secret. */
    private fun logCheck(number: Int?, snapshot: EnvelopeSnapshot) {
        Timber.d("PaymentLinks: %s", LinkStatusRules.checkLogLine(number, snapshot))
    }

    private fun publish(id: String, snapshot: EnvelopeSnapshot) {
        logCheck(store.sent(id)?.number ?: store.received(id)?.number, snapshot)
        store.updateSent(id) { LinkStatusRules.withCheck(it, snapshot) }
        store.updateReceived(id) { it.noting(snapshot) }
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
     * Sweeps the open envelope into [destination]: a [SweepKind.CLAIM] of a received link or a
     * [SweepKind.RECLAIM] of a sent one. The open wallet takes it at its live receive address, a
     * closed wallet at its saved main address (no wallet switch).
     */
    fun sweepOpenEnvelope(kind: SweepKind, destination: ClaimDestination) {
        val address = destination.address ?: return
        val walletName = destination.name
        val current = _session.value ?: return
        val open = envelope ?: return
        if (current.phase != EnvelopeSession.Phase.LIVE || sweeping) return
        val id = current.id
        if (open.id != id) return
        val amount = current.snapshot?.unlocked ?: 0L
        // Saved before the broadcast, so a sweep the app loses mid-flight is found later by its hash.
        val now = System.currentTimeMillis()
        val earlierAttempt = when (kind) {
            SweepKind.RECLAIM -> store.sent(id)?.reclaimStartedAt != null
            SweepKind.CLAIM -> store.received(id)?.claimStartedAt != null
        }
        // The kit wallet the money lands in: the open one, or the closed wallet's own when it next opens.
        val into = if (destination.isOpen) WalletManager.currentWalletId else destination.kitWalletId
        Timber.d("PaymentLinks: sweep %s into %s wallet", kind, if (destination.isOpen) "the open" else "a closed")
        val saved = when (kind) {
            SweepKind.RECLAIM -> store.updateSent(id) { it.copy(reclaimStartedAt = it.reclaimStartedAt ?: now, reclaimWallet = into) }
            SweepKind.CLAIM -> store.updateReceived(id) {
                it.copy(claimStartedAt = it.claimStartedAt ?: now, claimStartedTo = walletName, claimWallet = into)
            }
        }
        if (saved == null) {
            _sweep.value = SweepResult(id, kind, SweepOutcome.Failed("Could not save the claim. Nothing was sent."), walletName)
            return
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
                Timber.w(e, "PaymentLinks: sweep failed")
                SweepOutcome.Failed(e.message ?: "")
            }
            record(id, kind, outcome, (outcome as? SweepOutcome.Sent)?.amount ?: amount, walletName, earlierAttempt)
            _sweep.value = SweepResult(id, kind, outcome, walletName)
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
                // Retain the secret even after confirmation: a reorg or newer
                // locked funding can leave money in this envelope.
                val claimed: (ReceivedLink) -> ReceivedLink = {
                    if (outcome is SweepOutcome.Sent) {
                        it.copy(state = ReceivedState.CLAIMED, claimTxid = outcome.txid, claimedAmount = amount, claimedTo = walletName)
                    } else {
                        it.copy(state = ReceivedState.CLAIMED)
                    }
                }
                // Only a received record takes a claim: a link this phone sent is reclaimed (iOS parity), so it never
                // gets a received record.
                if (store.updateReceived(id, claimed) == null) Timber.w("PaymentLinks: a claim with no received record")
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
            !LinkStatusRules.isSettled(it) && now - (it.lastCheck?.checkedAt ?: 0) > REFRESH_AFTER_MS &&
                (!LinkStatusRules.isHidden(it) || it.id !in hiddenChecked)
        })
    }

    /**
     * The Wallet tab's look at the open sent links of [walletId] (spec Round 3), so the balance
     * card's line drops a claimed link. A look stops the main wallet for a moment, so the tab asks
     * for one only when it appears, comes back to the foreground or shows another wallet, then every
     * [WALLET_TAB_LOOK_EVERY_MS], and only while the wallet is synced, no send runs and History shows
     * now. Links looked at in the last [LOOK_AGAIN_AFTER_MS] wait. Runs in the caller's scope: the
     * look stops when the tab goes; a claim sheet that opens stops it too. The Links filter's
     * [refreshSent] covers that filter.
     */
    suspend fun lookAtOpenLinks(walletId: String) = coroutineScope {
        if (refreshJob?.isActive == true || _session.value != null) {
            Timber.d("PaymentLinks: open link check skipped: the envelope runtime is busy")
            return@coroutineScope
        }
        fillNetAmounts()
        val now = System.currentTimeMillis()
        val due = store.sent.value.filter {
            it.walletId == walletId && OpenLinkRules.isOpen(it, now / 1000) &&
                now - (it.lastCheck?.checkedAt ?: 0) > LOOK_AGAIN_AFTER_MS
        }
        if (due.isEmpty()) return@coroutineScope
        val look = launch {
            _holdSyncStatus.value = walletId
            try {
                checkSent(due)
                // The main wallet reconnects after the look: wait for it to leave Synced, then come back.
                withTimeoutOrNull(HOLD_AFTER_MS) {
                    WalletManager.syncStateFlow.dropWhile { it is SyncState.Synced }.first { it is SyncState.Synced }
                }
            } finally {
                _holdSyncStatus.value = null
            }
        }
        openLookJob = look
        look.join()
    }

    private var lastNetFillAt = 0L

    /**
     * Received links checked before records had a net amount: their last balance less the
     * active wallet's fee estimate for the claim sweep (one input, two outputs, at the
     * sweep's priority). The claim sheet's next check replaces it with the envelope's own
     * estimate. At most every 10 minutes.
     */
    private suspend fun fillNetAmounts() {
        val balance = store.received.value.firstOrNull { it.needsNetAmount }?.lastCheck?.balance ?: return
        if (WalletManager.syncStateFlow.value !is SyncState.Synced) return
        val now = System.currentTimeMillis()
        if (now - lastNetFillAt < NET_FILL_EVERY_MS) return
        lastNetFillAt = now
        val kit = WalletManager.kit ?: return
        val fee = withContext(Dispatchers.IO) {
            try {
                kit.estimateFee(balance, kit.receiveAddress, null, sweepAll = true, priority = EnvelopeWallet.SWEEP_PRIORITY)
            } catch (e: Exception) {
                Timber.w(e, "PaymentLinks: claim fee estimate failed (old links)")
                0L
            }
        }
        if (fee <= 0) return
        for (link in store.received.value.filter { it.needsNetAmount }) {
            store.updateReceived(link.id) { it.copy(netAmount = ReceivedLink.net(it.lastCheck?.balance ?: 0, fee)) }
        }
    }

    private suspend fun checkSent(due: List<SentLink>) {
        for (link in due) {
            // A claim sheet or detail screen has the envelope runtime: leave it alone.
            if (_session.value != null) return
            sessionLock.withLock {
                var opened: EnvelopeWallet? = null
                try {
                    openChecked(link.id, link.link) { opened = it }?.let { snapshot ->
                        logCheck(link.number, snapshot)
                        store.updateSent(link.id) { LinkStatusRules.withCheck(it, snapshot) }
                        if (LinkStatusRules.isHidden(link)) hiddenChecked.add(link.id)
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Timber.w(e, "PaymentLinks: link %s: envelope open failed (sent link check)", link.number)
                } finally {
                    withContext(NonCancellable) { opened?.close() }
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
        openLookJob?.cancel()
        val open = envelope
        envelope = null
        if (open != null) {
            kotlinx.coroutines.CoroutineScope(Dispatchers.Main + NonCancellable).launch { open.close() }
        }
    }

    companion object {
        private const val LIVE_INTERVAL_MS = 3_000L

        /** A received link's claim fee estimate, again while its sheet stays open (iOS polls every 15 s). */
        private const val FEE_EVERY_MS = 15_000L

        /** Old received records with no net amount: the active wallet's estimate, at most this often. */
        private const val NET_FILL_EVERY_MS = 10 * 60_000L
        private const val REFRESH_AFTER_MS = 20_000L
        private const val MAX_RESCANS = 2
        private const val REFRESH_INTERVAL_MS = 30_000L
        /** A little under a block (about 2 minutes). */
        /** A link the Wallet tab looked at this recently is not looked at again (a tab switch back and forth). */
        private const val LOOK_AGAIN_AFTER_MS = 120_000L
        /** How long after a check the Wallet tab may keep showing Synced while the main wallet reconnects. */
        private const val HOLD_AFTER_MS = 45_000L

        /** How often the Wallet tab looks at open links while it shows. */
        const val WALLET_TAB_LOOK_EVERY_MS = 10 * 60_000L
        private const val FUNDING_TXID_WAIT_MS = 60_000L
    }
}
