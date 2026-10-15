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
    val sweepFee: Long? = null
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
    val reclaimTxid: String? = null,
    /** The reclaim lost a race to a claim: the link reads Claimed. */
    val reclaimLostRace: Boolean = false,
    val lastCheck: EnvelopeSnapshot? = null,
    /** A reclaim began (saved before its broadcast), so one the app lost mid-flight is still found. */
    val reclaimStartedAt: Long? = null,
    /** The kit wallet that reclaim swept into (WalletManager.currentWalletId). */
    val reclaimWallet: String? = null,
    /** v2 links: the 16-byte link secret, lowercase hex. Null for v1 links. */
    val secret: String? = null
) {
    /** The link to share and to open the envelope with. A v2 link carries no amount and no expiry: [amount] and [expiry] stay here. */
    val link: PaymentLink get() = secret?.let { PaymentLink.v2(it, height) } ?: PaymentLink(words, height, amount, expiry, id)

    override fun toString(): String = "SentLink(id=$id, state=$state, a=$amount)"
}

/** Where a received link stands on this device. */
@Serializable
enum class ReceivedState {
    /** Opened, not claimed yet. */
    PENDING,

    /** This device swept it. The words stay until the claim is mined ([LinkStatusRules.claimWordsToForget]). */
    CLAIMED
}

/**
 * A link opened on this device. The secret ([words] of a v1 link, [secret] of
 * a v2 link) stays after this device claims it, until the claim is in a block:
 * a claim that never confirms leaves the money in the envelope, and only the
 * secret reaches it.
 */
@Serializable
data class ReceivedLink(
    val id: String,
    /** v1 links: the 25 words. Null for v2 links, and once a claim from here is mined. */
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
    /** v2 links: the 16-byte link secret, lowercase hex. Null for v1 links, and once a claim from here is mined. */
    val secret: String? = null,
    /**
     * What a claim delivers, atomic units: the last balance less wallet2's fee estimate
     * for the claim sweep. Null until a check (or the active wallet's estimate, for a
     * record checked before this field) saw the link funded.
     */
    val netAmount: Long? = null
) {
    /** The link, while its secret is still held. */
    val link: PaymentLink?
        get() = secret?.let { PaymentLink.v2(it, height) } ?: words?.let { PaymentLink(it, height, amount, expiry, id) }

    /** The link with its secret dropped (a claim from here is mined). */
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
     */
    fun noting(check: EnvelopeSnapshot): ReceivedLink {
        val net = when {
            check.sweepFee != null -> net(check.balance, check.sweepFee)
            check.balance != lastCheck?.balance -> null
            else -> netAmount
        }
        return copy(lastCheck = check, netAmount = net)
    }

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

/** The status a sent link shows (spec "Status"). [EXPIRED] is a label over pending or ready. */
enum class LinkStatus { NOT_FUNDED, PENDING, READY, CLAIMED, RECLAIMED, EXPIRED }

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
     * Ids of claimed links whose claim is now in a block ([minedHashes]: the
     * open wallet's mined transactions): their words can go.
     */
    fun claimWordsToForget(received: List<ReceivedLink>, minedHashes: Set<String>): List<String> =
        received.filter { it.state == ReceivedState.CLAIMED && it.link != null && it.claimTxid in minedHashes }.map { it.id }

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

    /** The status of a sent link, without the expiry label. */
    fun baseStatus(link: SentLink): LinkStatus {
        if (link.reclaimTxid != null) return LinkStatus.RECLAIMED
        if (link.reclaimLostRace) return LinkStatus.CLAIMED
        val check = link.lastCheck
            ?: return if (link.state == SentState.FUNDED) LinkStatus.PENDING else LinkStatus.NOT_FUNDED
        return when {
            !check.fundingSeen -> LinkStatus.NOT_FUNDED
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
        var base = baseStatus(link)
        // Not checked yet, and its funding is in the open wallet's history: it waits to be claimed.
        if (base == LinkStatus.NOT_FUNDED && link.lastCheck == null && fundingConfirmations != null) base = LinkStatus.PENDING
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

    /** The sender's link has settled: nothing more can move (its files can go). */
    fun isSettled(link: SentLink): Boolean =
        baseStatus(link).let { it == LinkStatus.CLAIMED || it == LinkStatus.RECLAIMED }

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
