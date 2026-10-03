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
    val unlocked: Long
)

/**
 * A link this device created. [words] is the envelope secret: the record lives
 * only in the app's encrypted store (SecurePrefs) and [toString] never prints it.
 */
@Serializable
data class SentLink(
    val id: String,
    val words: List<String>,
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
    val lastCheck: EnvelopeSnapshot? = null
) {
    val link: PaymentLink get() = PaymentLink(words, height, amount, expiry, id)

    override fun toString(): String = "SentLink(id=$id, state=$state, a=$amount)"
}

/** Where a received link stands on this device. */
@Serializable
enum class ReceivedState {
    /** Opened, not claimed yet. */
    PENDING,

    /** This device swept it. The words are gone from the record. */
    CLAIMED
}

/** A link opened on this device. The words are deleted once this device claims it. */
@Serializable
data class ReceivedLink(
    val id: String,
    val words: List<String>?,
    val height: Long,
    val amount: Long,
    val expiry: Long,
    val receivedAtMillis: Long,
    val state: ReceivedState = ReceivedState.PENDING,
    val claimTxid: String? = null,
    val claimedAmount: Long? = null,
    val claimedTo: String? = null,
    val lastCheck: EnvelopeSnapshot? = null
) {
    /** The link, while the words are still held. */
    val link: PaymentLink? get() = words?.let { PaymentLink(it, height, amount, expiry, id) }

    override fun toString(): String = "ReceivedLink(id=$id, state=$state, a=$amount)"
}

/** The status a sent link shows (spec "Status"). [EXPIRED] is a label over pending or ready. */
enum class LinkStatus { NOT_FUNDED, PENDING, READY, CLAIMED, RECLAIMED, EXPIRED }

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
            // Funding mined, balance gone, and not by our reclaim: someone claimed it.
            check.balance == 0L -> LinkStatus.CLAIMED
            check.confirmations < CONFIRMATIONS -> LinkStatus.PENDING
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

    /** Reclaim needs spendable money in the envelope. */
    fun canReclaim(link: SentLink): Boolean {
        val check = link.lastCheck ?: return false
        return link.reclaimTxid == null && !link.reclaimLostRace && check.unlocked > 0L
    }

    /** The sender's link has settled: nothing more can move (its files can go). */
    fun isSettled(link: SentLink): Boolean =
        baseStatus(link).let { it == LinkStatus.CLAIMED || it == LinkStatus.RECLAIMED }

    fun claimView(check: EnvelopeSnapshot): ClaimView = when {
        !check.fundingSeen -> ClaimView.NoMoney
        check.fundingMined && check.balance == 0L -> ClaimView.AlreadyClaimed
        check.fundingMined && check.confirmations >= CONFIRMATIONS && check.unlocked > 0L -> ClaimView.Ready(check.unlocked)
        else -> {
            val confirmations = if (check.fundingMined) check.confirmations.coerceIn(0, CONFIRMATIONS) else 0L
            ClaimView.Waiting(confirmations, (CONFIRMATIONS - confirmations).coerceAtLeast(1) * MINUTES_PER_BLOCK)
        }
    }
}
