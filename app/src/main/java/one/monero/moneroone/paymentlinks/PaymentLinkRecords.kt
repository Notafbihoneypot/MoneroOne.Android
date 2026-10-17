package one.monero.moneroone.paymentlinks

import kotlinx.serialization.Serializable

/** Where a sent link's funding stands, as this device last recorded it. */
@Serializable
enum class SentState {
    /** Saved before the funding broadcast. */
    CREATED,

    /** The funding broadcast failed (a later check may still find funds). */
    NOT_FUNDED,

    /** The funding broadcast went out. */
    FUNDED
}

/** What an envelope sync saw. */
@Serializable
data class EnvelopeSnapshot(
    val checkedAt: Long,
    /** Any incoming transaction, in the pool or mined. */
    val fundingSeen: Boolean,
    /** An incoming transaction is in a block. */
    val fundingMined: Boolean,
    /** Confirmations of the oldest incoming transaction (0 in the pool). */
    val confirmations: Long,
    val balance: Long,
    val unlocked: Long,
    /**
     * The envelope's history shows a spend of its money (a claim or a reclaim).
     * A zero balance alone is not proof: the kit reports Synced before it
     * publishes the balance, and a link read as claimed loses its words.
     */
    val spendSeen: Boolean = false,
    /** The envelope's outgoing transactions: a sweep this app started and lost track of is found by its hash. */
    val spendTxids: List<String> = emptyList(),
    /**
     * Block height of the oldest mined incoming transaction; null while every
     * funding waits in the pool. With the open wallet's daemon height it gives a
     * live confirmation count without opening the envelope again.
     */
    val fundingHeight: Long? = null,
    /**
     * wallet2's fee estimate for the claim sweep of [balance], atomic units. Null when
     * the check did not ask for one (sent links), the envelope is empty or the estimate failed.
     */
    val sweepFee: Long? = null,
    /** The envelope's incoming transactions, oldest first (one in the pool last): the funding as the envelope sees it. */
    val fundingTxids: List<String> = emptyList(),
    /** The envelope's outgoing transactions that are in a block: a claim or reclaim that can no longer drop. */
    val minedSpendTxids: List<String> = emptyList()
)

/**
 * A link this device created. [secret] (v2) or [words] (v1) is the envelope
 * secret: the record lives only in the app's encrypted store (SecurePrefs) and
 * [toString] never prints it.
 */
@Serializable
data class SentLink(
    val id: String,
    /** v1 links: the 25 words. Empty for v2 links, whose words derive from [secret]. */
    val words: List<String> = emptyList(),
    val height: Long,
    val amount: Long,
    val expiry: Long,
    val createdAtMillis: Long,
    /** The wallet that funded it (WalletInfo.id). */
    val walletId: String,
    val envelopeAddress: String,
    val feeReserve: Long,
    val state: SentState,
    val fundingTxid: String? = null,
    /**
     * The open wallet's history marks [fundingTxid] failed (the network dropped or rejected it), so no
     * money left. Cleared when that history shows the transaction live again or a new funding.
     */
    val fundingFailed: Boolean = false,
    val reclaimTxid: String? = null,
    /** The reclaim lost a race to a claim: the link reads Claimed. */
    val reclaimLostRace: Boolean = false,
    val lastCheck: EnvelopeSnapshot? = null,
    /** A reclaim began (saved before its broadcast), so one the app lost mid-flight is still found. */
    val reclaimStartedAt: Long? = null,
    /** The kit wallet that reclaim swept into (WalletManager.currentWalletId). */
    val reclaimWallet: String? = null,
    /** v2 links: the 16-byte link secret, lowercase hex. Null for v1 links. */
    val secret: String? = null,
    /** This device's number for the link, shared with received links ([LinkNames]). */
    val number: Int? = null,
    /** The sender's own note. Local only: never in the link, its URL, the share text or a tx memo. */
    val note: String? = null
) {
    /** The note, else "Link 7". */
    val name: String get() = LinkNames.name(note, number)

    /** The link to share and to open the envelope with. A v2 link carries no amount and no expiry: [amount] and [expiry] stay here. */
    val link: PaymentLink get() = secret?.let { PaymentLink.v2(it, height) } ?: PaymentLink(words, height, amount, expiry, id)

    override fun toString(): String = "SentLink(id=$id, state=$state, a=$amount)"
}

/** Where a received link stands on this device. */
@Serializable
enum class ReceivedState {
    /** Opened, not claimed yet. */
    PENDING,

    /** This device swept it. The secret stays until Remove All. */
    CLAIMED
}

/**
 * A link opened on this device. The secret ([words] of a v1 link, [secret] of
 * a v2 link) stays after this device claims it, until Remove All: a reorg, or
 * money that arrives after the claim, can leave money that only the secret reaches.
 */
@Serializable
data class ReceivedLink(
    val id: String,
    /** v1 links: the 25 words. Null for v2 links, or legacy records that discarded their secret. */
    val words: List<String>?,
    val height: Long,
    val amount: Long,
    val expiry: Long,
    val receivedAtMillis: Long,
    val state: ReceivedState = ReceivedState.PENDING,
    val claimTxid: String? = null,
    val claimedAmount: Long? = null,
    val claimedTo: String? = null,
    val lastCheck: EnvelopeSnapshot? = null,
    /** A claim began (saved before its broadcast), so one the app lost mid-flight is still found. */
    val claimStartedAt: Long? = null,
    /** The wallet that claim went to: its name, and its kit wallet (WalletManager.currentWalletId). */
    val claimStartedTo: String? = null,
    val claimWallet: String? = null,
    /** v2 links: the 16-byte link secret, lowercase hex. Null for v1 links, or legacy records that discarded their secret. */
    val secret: String? = null,
    /**
     * What a claim delivers, atomic units: the last balance less wallet2's fee estimate
     * for the claim sweep. Null until a check (or the active wallet's estimate, for a
     * record checked before this field) saw the link funded.
     */
    val netAmount: Long? = null,
    /** This device's number for the link, shared with sent links ([LinkNames]). */
    val number: Int? = null,
    /** This device's own note. Local only. */
    val note: String? = null
) {
    /** The note, else "Link 7". */
    val name: String get() = LinkNames.name(note, number)

    /** The link, while its secret is still held. */
    val link: PaymentLink?
        get() = secret?.let { PaymentLink.v2(it, height) } ?: words?.let { PaymentLink(it, height, amount, expiry, id) }

    /** Legacy helper; automatic claim cleanup must never call this. */
    fun forgettingSecret(): ReceivedLink = copy(words = null, secret = null)

    /**
     * What the link holds as far as this device knows, atomic units: the
     * amount of a v1 link, else the balance the last check saw. Null when
     * unknown (a v2 link not checked yet).
     */
    val knownAmount: Long?
        get() = amount.takeIf { it > 0 } ?: lastCheck?.balance?.takeIf { it > 0 }

    /**
     * What a claim delivers, atomic units: the net amount when known, else the v1
     * link's amount (the sender put the claim fee on top), else the last balance.
     * Null when unknown.
     */
    val claimAmount: Long?
        get() = netAmount?.takeIf { it > 0 } ?: knownAmount

    /**
     * The record with [check] as its last check. A check with a sweep fee sets the net
     * amount; one without keeps the old net amount only while the balance holds.
     *
     * A settled link keeps its record unless [check] reopens it ([LinkStatusRules.reopens]):
     * a check made before the claim is in a block still sees the money, and must never turn
     * Claimed back into Ready (a second claim would fail as a double spend).
     */
    fun noting(check: EnvelopeSnapshot): ReceivedLink {
        if (isSettled && !LinkStatusRules.reopens(settleTxids, check)) return this
        val net = when {
            check.sweepFee != null -> net(check.balance, check.sweepFee)
            check.balance != lastCheck?.balance -> null
            else -> netAmount
        }
        // Past the guard, a settled link has money its settle left behind: it is open again.
        return copy(lastCheck = check, netAmount = net, state = if (isSettled) ReceivedState.PENDING else state)
    }

    /**
     * The spends that settled the link: our claim, else (claimed elsewhere) the envelope's spends as the
     * check that saw it empty read them. Empty when unknown: then the link never reopens.
     */
    val settleTxids: List<String>
        get() = when {
            state == ReceivedState.CLAIMED -> listOfNotNull(claimTxid?.takeIf { it.isNotEmpty() })
            else -> lastCheck?.spendTxids.orEmpty()
        }

    /** This device's own claim (its transaction or amount is known), not one that lost to another claim. */
    val claimedHere: Boolean
        get() = state == ReceivedState.CLAIMED && (claimTxid != null || claimedAmount != null)

    /** Checked before records had a net amount: the active wallet's fee estimate stands in for the envelope's. */
    val needsNetAmount: Boolean
        get() = !isSettled && link != null && netAmount == null && amount == 0L && (lastCheck?.balance ?: 0) > 0

    /** Nothing more can move here: claimed from this device, or seen empty after its funding. */
    val isSettled: Boolean
        get() = state == ReceivedState.CLAIMED || lastCheck?.let { LinkStatusRules.claimView(it) } == ClaimView.AlreadyClaimed

    override fun toString(): String = "ReceivedLink(id=$id, state=$state, a=$amount)"

    companion object {
        /** The balance less the claim sweep's fee; null when the fee takes it all. */
        fun net(balance: Long, fee: Long): Long? = if (balance > fee) balance - fee else null
    }
}

/**
 * The status a sent link shows (spec "Status"). [EXPIRED] is a label over pending or ready.
 * [NOT_FUNDED]: no funding anywhere ([LinkStatusRules.isHidden]); such a link shows nowhere.
 */
enum class LinkStatus { NOT_FUNDED, PENDING, READY, CLAIMED, RECLAIMED, EXPIRED }

/**
 * What a sent link's detail offers, in order. There is no Retry (nothing funds a saved link again) and
 * no Remove (a link's secret stays on this phone; Remove All Wallets wipes everything).
 */
enum class SentLinkAction { SHARE_AGAIN, RECLAIM }

/**
 * A sent link as its row, detail and Reclaim read it ([LinkStatusRules.sentView]). [confirmations]: the
 * funding's live count, null when unknown. [canReclaim]: ready (expired or not), not claimed or reclaimed.
 */
data class SentView(val status: LinkStatus, val confirmations: Long?, val canReclaim: Boolean)

/** What the claim sheet shows for an envelope sync. */
sealed class ClaimView {
    /** Funding seen, not spendable yet. */
    data class Waiting(val confirmations: Long, val minutesLeft: Long) : ClaimView()

    /** Spendable balance; the claim sweeps [unlocked]. */
    data class Ready(val unlocked: Long) : ClaimView()

    /** Funding seen and now empty. */
    object AlreadyClaimed : ClaimView()

    /** No funding yet. */
    object NoMoney : ClaimView()
}

object LinkStatusRules {
    /** Outputs unlock after 10 blocks. */
    const val CONFIRMATIONS = 10L

    /**
     * Reclaims the app started and lost (killed mid-flight) whose sweep now
     * shows as incoming ([incomingHashes]) in the wallet it went to, open now
     * as [openWallet]: id to txid. Proof by hash in that wallet, so a claim
     * that won the race (even into another wallet on this phone) is never read as ours.
     */
    fun lostReclaims(sent: List<SentLink>, openWallet: String?, incomingHashes: Set<String>): Map<String, String> =
        sent.filter { it.reclaimStartedAt != null && it.reclaimTxid == null && openWallet != null && it.reclaimWallet == openWallet }
            .mapNotNull { link -> link.lastCheck?.spendTxids?.firstOrNull { it in incomingHashes }?.let { link.id to it } }
            .toMap()

    /** Claims the app started and lost, found as for [lostReclaims]: id to txid. */
    fun lostClaims(received: List<ReceivedLink>, openWallet: String?, incomingHashes: Set<String>): Map<String, String> =
        received.filter { it.claimStartedAt != null && it.claimTxid == null && openWallet != null && it.claimWallet == openWallet }
            .mapNotNull { link -> link.lastCheck?.spendTxids?.firstOrNull { it in incomingHashes }?.let { link.id to it } }
            .toMap()

    /** About 2 minutes a block. */
    const val MINUTES_PER_BLOCK = 2L

    /**
     * A funding transaction exists for [link]: its txid is known, its broadcast went out, or a check saw
     * money arrive. [inWallet]: the open wallet's history holds an outgoing transaction to its envelope.
     * A funding the wallet marks failed does not count.
     */
    fun hasFunding(link: SentLink, inWallet: Boolean = false): Boolean =
        inWallet || link.lastCheck?.fundingSeen == true ||
            (!link.fundingFailed && (link.fundingTxid != null || link.state == SentState.FUNDED))

    /**
     * A link whose funding failed with no funding anywhere: no txid, no outgoing transaction to its
     * envelope in the open wallet, and no check saw money. It shows nowhere (Activity, the Links counts,
     * the balance card's line). Its record and secret stay: a funding found later brings it back as Waiting.
     */
    fun isHidden(link: SentLink, inWallet: Boolean = false): Boolean = baseStatus(link, inWallet) == LinkStatus.NOT_FUNDED

    /**
     * The status of a sent link, without the expiry label. A link with a funding transaction is never
     * Not funded: until a check sees the money, it waits ([LinkStatus.PENDING]). [inWallet]: as [hasFunding].
     */
    fun baseStatus(link: SentLink, inWallet: Boolean = false): LinkStatus {
        // Reclaimed, unless a check saw our reclaim in a block and money it left behind.
        if (link.reclaimTxid != null && !reopens(settleTxids(link), link.lastCheck)) return LinkStatus.RECLAIMED
        if (link.reclaimLostRace) return LinkStatus.CLAIMED
        val funded = hasFunding(link, inWallet)
        val check = link.lastCheck ?: return if (funded) LinkStatus.PENDING else LinkStatus.NOT_FUNDED
        return when {
            // The scan has not seen the funding (in the pool, or a check that ran too early).
            !check.fundingSeen -> if (funded) LinkStatus.PENDING else LinkStatus.NOT_FUNDED
            !check.fundingMined -> LinkStatus.PENDING
            // Funding mined, balance spent, and not by our reclaim: someone claimed it.
            check.balance == 0L && check.spendSeen -> LinkStatus.CLAIMED
            // wallet2 unlocks an output after 10 blocks: an unlocked balance is the ready signal.
            // The kit's chain height can trail by a block, so the count alone would lag it.
            check.unlocked > 0L -> LinkStatus.READY
            else -> LinkStatus.PENDING
        }
    }

    /** [baseStatus], labeled Expired once pending or ready outlives [SentLink.expiry]. */
    fun status(link: SentLink, nowSeconds: Long): LinkStatus {
        val base = baseStatus(link)
        val live = base == LinkStatus.PENDING || base == LinkStatus.READY
        return if (live && nowSeconds > link.expiry) LinkStatus.EXPIRED else base
    }

    /**
     * What a sent link's Activity row, detail and Reclaim read, so all three agree (iOS PaymentLinkDisplay).
     * [fundingConfirmations]: its funding transaction in the open wallet's history, a live count; null when
     * that wallet did not fund it, and then the last check's funding height and [daemonHeight] give the count.
     *
     * A pending link unlocks with its funding: at 10 confirmations it is ready, whatever the last check said
     * (one that failed, or ran before the unlock). Reclaim still opens the envelope and tells ready from
     * claimed before anything moves, and the next check corrects the record.
     */
    fun sentView(link: SentLink, fundingConfirmations: Long?, daemonHeight: Long?, nowSeconds: Long): SentView {
        // Its funding in the open wallet's history counts as a funding transaction.
        var base = baseStatus(link, inWallet = fundingConfirmations != null)
        val checked = link.lastCheck?.takeIf { it.fundingMined }?.confirmations
        val byHeight = link.lastCheck?.fundingHeight?.takeIf { fundingConfirmations == null }
            ?.let { height -> daemonHeight?.takeIf { it > height }?.minus(height) }
        // The open wallet's count stalls while an envelope holds the kit: the higher count wins.
        val confirmations = listOfNotNull(fundingConfirmations, checked, byHeight).maxOrNull()
        if (base == LinkStatus.PENDING && (confirmations ?: 0) >= CONFIRMATIONS) base = LinkStatus.READY
        val live = base == LinkStatus.PENDING || base == LinkStatus.READY
        val status = if (live && nowSeconds > link.expiry) LinkStatus.EXPIRED else base
        return SentView(status, confirmations, canReclaim = base == LinkStatus.READY)
    }

    /**
     * The detail's actions. A link with a funding transaction that is not claimed or reclaimed always
     * offers Share, whatever the last check said, so it can be claimed on any device.
     */
    fun sentActions(status: LinkStatus): List<SentLinkAction> = when (status) {
        LinkStatus.PENDING, LinkStatus.READY -> listOf(SentLinkAction.SHARE_AGAIN, SentLinkAction.RECLAIM)
        LinkStatus.EXPIRED -> listOf(SentLinkAction.RECLAIM, SentLinkAction.SHARE_AGAIN)
        LinkStatus.CLAIMED, LinkStatus.RECLAIMED, LinkStatus.NOT_FUNDED -> emptyList()
    }

    /**
     * The envelope check's log line: the link's number and what the check saw. Never the note, the
     * address, the secret or a hash.
     */
    fun checkLogLine(number: Int?, check: EnvelopeSnapshot): String =
        "link ${number ?: "?"} check seen=${check.fundingSeen} mined=${check.fundingMined} conf=${check.confirmations} " +
            "balance=${if (check.balance > 0) "yes" else "none"} unlocked=${if (check.unlocked > 0) "yes" else "none"} spent=${check.spendSeen}"

    /** A rescan starts this many blocks under the funding's block. */
    const val RESCAN_MARGIN = 10L

    /**
     * Where a sent link's envelope is scanned again: a full sync ([check]) did not see the funding that
     * the open wallet's history shows in block [fundingBlock] (a cache that started too late or went
     * stale). The rescan starts at the restore height or lower, never later than that block. Null: no
     * rescan (the check saw the funding, the funding is not in a block, or the link is settled).
     */
    fun rescanFrom(link: SentLink, check: EnvelopeSnapshot, fundingBlock: Long?): Long? {
        if (check.fundingSeen || fundingBlock == null || fundingBlock <= 0) return null
        if (link.reclaimTxid != null || link.reclaimLostRace) return null
        return scanFloor(link.height, fundingBlock)
    }

    /** Where an envelope's scan must start: its [restoreHeight], or under [fundingBlock] when that is lower. */
    fun scanFloor(restoreHeight: Long, fundingBlock: Long?): Long =
        fundingBlock?.takeIf { it > 0 }?.let { minOf(restoreHeight, it - RESCAN_MARGIN).coerceAtLeast(0) } ?: restoreHeight

    /**
     * Records saved without a funding txid (the app quit mid-broadcast, or the error came after the node
     * took it) whose funding the open wallet now shows: id to txid. [fundingTxidFor] finds the open
     * wallet's outgoing transaction to an envelope address (each envelope has its own address).
     */
    fun fundingsToAdopt(sent: List<SentLink>, fundingTxidFor: (String) -> String?): Map<String, String> =
        sent.filter { (it.fundingTxid == null || it.fundingFailed) && it.reclaimTxid == null }
            .mapNotNull { link -> fundingTxidFor(link.envelopeAddress)?.let { link.id to it } }
            .toMap()

    /**
     * Records whose [SentLink.fundingFailed] changes with the open wallet's history: id to the new value.
     * [failed]: hashes the wallet marks failed; [live]: every other hash it holds. A txid it does not hold
     * (another wallet funded the link) keeps its value.
     */
    fun fundingFailures(sent: List<SentLink>, failed: Set<String>, live: Set<String>): Map<String, Boolean> =
        sent.mapNotNull { link ->
            val txid = link.fundingTxid ?: return@mapNotNull null
            val now = when (txid) {
                in live -> false
                in failed -> true
                else -> return@mapNotNull null
            }
            if (now != link.fundingFailed) link.id to now else null
        }.toMap()

    /**
     * When a received link's 30 days end: its own expiry (v1 links carry one), else 30 days after this
     * device got it (v2 links carry none).
     */
    fun receivedExpiry(link: ReceivedLink): Long =
        link.expiry.takeIf { it > 0 } ?: (link.receivedAtMillis / 1000 + EnvelopeFactory.EXPIRY_SECONDS)

    /**
     * A received link whose money never arrived (no check saw any) and whose 30 days are over: it leaves
     * Open and the balance card's line for History, as Expired. It keeps its secret: money that shows up
     * later brings it back.
     */
    fun receivedExpired(link: ReceivedLink, nowSeconds: Long): Boolean =
        link.state != ReceivedState.CLAIMED && link.lastCheck?.fundingSeen != true && nowSeconds > receivedExpiry(link)

    /**
     * A sent record whose funding transaction is known reads funded: Created (the app quit mid-broadcast)
     * and Not funded (the error came after the node took it) would contradict the wallet's own history.
     */
    fun reconciled(link: SentLink): SentLink =
        if (link.state != SentState.FUNDED && link.fundingTxid != null && !link.fundingFailed) link.copy(state = SentState.FUNDED) else link

    /**
     * The record with [check] as its last check. A record without a funding txid (or with one the wallet
     * marks failed) takes the one the envelope saw (iOS parity), so its Activity row matches it.
     * A settled link keeps its record unless [check] reopens it ([reopens]).
     */
    fun withCheck(link: SentLink, check: EnvelopeSnapshot): SentLink {
        if (isSettled(link) && !reopens(settleTxids(link), check)) return link
        val seen = check.fundingTxids.firstOrNull()
        return if (seen != null && (link.fundingTxid == null || link.fundingFailed)) {
            link.copy(lastCheck = check, fundingTxid = seen, fundingFailed = false, state = SentState.FUNDED)
        } else {
            link.copy(lastCheck = check)
        }
    }

    /** The sender's link has settled: nothing more can move (its files can go). */
    fun isSettled(link: SentLink): Boolean =
        baseStatus(link).let { it == LinkStatus.CLAIMED || it == LinkStatus.RECLAIMED }

    /**
     * The spends that settled a sent link: our reclaim, else (claimed) the envelope's spends as the check
     * that saw it empty read them. Empty when unknown, and after a claim beat our reclaim: then the link
     * never reopens.
     */
    fun settleTxids(link: SentLink): List<String> = when {
        link.reclaimTxid != null -> listOfNotNull(link.reclaimTxid.takeIf { it.isNotEmpty() })
        link.reclaimLostRace -> emptyList()
        else -> link.lastCheck?.spendTxids.orEmpty()
    }

    /**
     * A settled link opens again (founder decision 2026-10-17) only when [check] sees every spend that
     * settled it ([txids]) in a block and spendable money still in the envelope: money that settle did
     * not spend (a later funding, or outputs still locked when it swept). A check from before the settle
     * is mined still sees the money it spends, so it never reopens the link.
     */
    fun reopens(txids: List<String>, check: EnvelopeSnapshot?): Boolean =
        check != null && txids.isNotEmpty() && check.unlocked > 0 && check.minedSpendTxids.containsAll(txids)

    fun claimView(check: EnvelopeSnapshot): ClaimView = when {
        !check.fundingSeen -> ClaimView.NoMoney
        check.fundingMined && check.balance == 0L && check.spendSeen -> ClaimView.AlreadyClaimed
        check.fundingMined && check.unlocked > 0L -> ClaimView.Ready(check.unlocked)
        else -> {
            // Still locked: at most 9 of 10, whatever the trailing height says.
            val confirmations = if (check.fundingMined) check.confirmations.coerceIn(0, CONFIRMATIONS - 1) else 0L
            ClaimView.Waiting(confirmations, (CONFIRMATIONS - confirmations).coerceAtLeast(1) * MINUTES_PER_BLOCK)
        }
    }
}
