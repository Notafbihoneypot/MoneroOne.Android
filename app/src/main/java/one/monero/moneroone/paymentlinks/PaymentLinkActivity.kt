package one.monero.moneroone.paymentlinks

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.unit.dp
import io.horizontalsystems.monerokit.model.TransactionInfo
import one.monero.moneroone.core.locale.tr
import one.monero.moneroone.core.wallet.WalletManager
import one.monero.moneroone.data.util.XmrFormat

/** What a wallet transaction did for a payment link on this device. */
enum class LinkActivityKind {
    /** Our send that put money in a link we made. */
    FUNDING,

    /** Our sweep that took a sent link's money back. */
    RECLAIM,

    /** Our sweep that claimed a received link. */
    CLAIM
}

/** A transaction that belongs to a payment link record on this device. */
class LinkActivity(val kind: LinkActivityKind, val sent: SentLink?, val received: ReceivedLink?) {
    val id: String get() = sent?.id ?: received?.id.orEmpty()

    /** The detail route kind ([PaymentLinkRoutes.SENT] or [PaymentLinkRoutes.RECEIVED]). */
    val routeKind: String get() = if (sent != null) PaymentLinkRoutes.SENT else PaymentLinkRoutes.RECEIVED

    val title: String
        get() = when (kind) {
            LinkActivityKind.FUNDING -> tr("Sent by Link")
            LinkActivityKind.RECLAIM -> tr("Reclaimed by Link")
            LinkActivityKind.CLAIM -> tr("Received by Link")
        }
}

/** Where a received link at the top of Activity stands, from its record and the daemon height only. */
sealed interface PendingLinkState {
    /** Saved, never checked (the claim sheet closed before its check). */
    object Unchecked : PendingLinkState

    /** Checked: no money in the link yet. */
    object NoMoney : PendingLinkState

    /** Funded, not unlocked: about this many minutes left. */
    data class Waiting(val minutes: Long) : PendingLinkState

    /** 10 confirmations or more, as far as this device knows. */
    object Ready : PendingLinkState
}

/**
 * Payment links in Activity, the same rules as iOS `PaymentLinkActivity`.
 * A transaction matches a link record on this device by txid:
 * - sent record, outgoing tx == fundingTxid: "Sent by Link" (funding)
 * - sent record, incoming tx == reclaimTxid: "Reclaimed by Link"
 * - received record, incoming tx == claimTxid: "Received by Link"
 * Every other transaction keeps "Received" / "Sent". A link row shows a link
 * glyph in the disc of its direction and opens the link's detail. Activity's
 * Links filter shows these rows only, under the Open Links group ([OpenLinks]).
 */
object PaymentLinkActivityRules {

    fun match(txid: String, incoming: Boolean, sent: List<SentLink>, received: List<ReceivedLink>): LinkActivity? {
        for (link in sent) {
            if (!incoming && link.fundingTxid == txid) return LinkActivity(LinkActivityKind.FUNDING, link, null)
            if (incoming && !link.reclaimTxid.isNullOrEmpty() && link.reclaimTxid == txid) {
                return LinkActivity(LinkActivityKind.RECLAIM, link, null)
            }
        }
        for (link in received) {
            if (incoming && link.claimTxid == txid) return LinkActivity(LinkActivityKind.CLAIM, null, link)
        }
        return null
    }

    /**
     * A funding row's second line, in place of the date, from [LinkStatusRules.sentView]'s status (ready at
     * 10 confirmations), so the row, the detail and Reclaim agree. The row is the funding transaction
     * itself, so "not funded" never shows: until a check sees the money, the link waits to be claimed.
     */
    fun fundingStatusLine(status: LinkStatus): String = when (status) {
        LinkStatus.CLAIMED -> tr("Claimed")
        LinkStatus.RECLAIMED -> tr("Reclaimed")
        LinkStatus.EXPIRED -> tr("Expired")
        LinkStatus.READY -> tr("Ready to claim")
        LinkStatus.NOT_FUNDED, LinkStatus.PENDING -> tr("Waiting to be claimed")
    }

    /** TalkBack: "Sent by Link, minus 0.5 XMR, Waiting to be claimed, 2 hours ago, Confirmed". */
    fun spokenLabel(
        title: String,
        incoming: Boolean,
        amountSpoken: String,
        statusLine: String?,
        date: String,
        transactionStatus: String
    ): String {
        val amount = if (incoming) tr("plus %s", amountSpoken) else tr("minus %s", amountSpoken)
        return listOfNotNull(title, amount, statusLine, date, transactionStatus).joinToString(", ")
    }

    /**
     * What a sent link cost, for its detail only (rows and the home line show
     * [SentLink.amount]): the funding fee when the open wallet has the funding
     * transaction, the claim fee reserve, and the total that left the wallet.
     */
    fun sentCosts(link: SentLink, fundingFee: Long?): SentCosts =
        SentCosts(fundingFee, link.feeReserve, fundingFee?.let { link.amount + link.feeReserve + it })

    fun minutesUntilReady(confirmations: Long): Long =
        (LinkStatusRules.CONFIRMATIONS - confirmations).coerceAtLeast(0) * LinkStatusRules.MINUTES_PER_BLOCK

    /** Received links this device can still claim (it has the secret) and that are not settled, newest first. */
    fun pendingRecords(received: List<ReceivedLink>): List<ReceivedLink> =
        received.filter { !it.isSettled && it.link != null }.sortedByDescending { it.receivedAtMillis }

    /**
     * Confirmations come from the funding height the last check saw and the
     * open wallet's daemon height (2-minute blocks). Without a height (the
     * funding was still in the pool), the count of the last check less the
     * minutes since.
     */
    fun pendingState(link: ReceivedLink, daemonHeight: Long?, nowMillis: Long): PendingLinkState {
        val check = link.lastCheck ?: return PendingLinkState.Unchecked
        return when (LinkStatusRules.claimView(check)) {
            ClaimView.NoMoney -> PendingLinkState.NoMoney
            ClaimView.AlreadyClaimed -> PendingLinkState.Unchecked
            is ClaimView.Ready -> PendingLinkState.Ready
            is ClaimView.Waiting -> {
                val height = check.fundingHeight
                if (height != null && daemonHeight != null && daemonHeight > height) {
                    val confirmations = daemonHeight - height
                    if (confirmations >= LinkStatusRules.CONFIRMATIONS) return PendingLinkState.Ready
                    return PendingLinkState.Waiting(minutesUntilReady(confirmations).coerceAtLeast(1))
                }
                val minutes = minutesUntilReady(if (check.fundingMined) check.confirmations else 0)
                val elapsed = ((nowMillis - check.checkedAt) / 60_000).coerceAtLeast(0)
                if (elapsed >= minutes) PendingLinkState.Ready else PendingLinkState.Waiting((minutes - elapsed).coerceAtLeast(1))
            }
        }
    }

    fun pendingLabel(state: PendingLinkState): String = when (state) {
        PendingLinkState.Unchecked -> tr("Tap to check")
        PendingLinkState.NoMoney -> tr("No money yet")
        is PendingLinkState.Waiting -> tr("Ready in about %s min", state.minutes)
        PendingLinkState.Ready -> tr("Ready to claim")
    }

    /**
     * The Activity row of a received link not claimed yet (it has no
     * transaction): "Payment link", the amount a claim delivers as an estimate
     * that follows fiat mode like every row ("+~0.0492 XMR" over "~$12.35", or
     * "+~$12.35" over "~0.0492 XMR"), and [status] as the second line. With no
     * amount known yet, the row shows none.
     */
    fun pendingRowText(claimAmount: Long?, fiatMode: Boolean, fiatValue: String?, status: String): PendingLinkRowText {
        val title = tr("Payment link")
        if (claimAmount == null) return PendingLinkRowText(title, null, null, "$title, $status")
        val xmr = "${XmrFormat.format(claimAmount)} XMR"
        val fiatFirst = fiatMode && fiatValue != null
        val amount = "+~" + if (fiatFirst) fiatValue else xmr
        val secondary = if (fiatFirst) "~${XmrFormat.compact(claimAmount)} XMR" else fiatValue?.let { "~$it" }
        // TalkBack would read "tilde": the estimate is "about".
        val amountSpoken = if (fiatFirst) "$fiatValue, ${XmrFormat.compact(claimAmount)} XMR" else xmr
        return PendingLinkRowText(title, amount, secondary, listOf(title, tr("plus about %s", amountSpoken), status).joinToString(", "))
    }

    /**
     * A received link's live confirmation count: the last check's, or more
     * from its funding height and the open wallet's daemon height.
     */
    fun receivedConfirmations(link: ReceivedLink, daemonHeight: Long?): Long? {
        val check = link.lastCheck ?: return null
        val checked = if (check.fundingMined) check.confirmations else 0L
        val height = check.fundingHeight
        val live = if (height != null && daemonHeight != null && daemonHeight > height) daemonHeight - height else 0L
        return maxOf(checked, live)
    }
}

/** The payment link records of this device, to match Activity rows against. */
class PaymentLinkIndex(val sent: List<SentLink>, val received: List<ReceivedLink>) {
    fun match(tx: TransactionInfo): LinkActivity? =
        PaymentLinkActivityRules.match(tx.hash, tx.direction == TransactionInfo.Direction.Direction_In, sent, received)
}

@Composable
fun rememberPaymentLinkIndex(): PaymentLinkIndex {
    val store = PaymentLinkStore.get(LocalContext.current)
    val sent by store.sent.collectAsState()
    val received by store.received.collectAsState()
    return remember(sent, received) { PaymentLinkIndex(sent, received) }
}

/** The open wallet's daemon height (cached by the kit, null while disconnected); read again as the wallet syncs. */
@Composable
fun rememberDaemonHeight(): Long? {
    val sync by WalletManager.syncStateFlow.collectAsState()
    val transactions by WalletManager.transactionsFlow.collectAsState()
    return remember(sync, transactions) { WalletManager.kit?.lastBlockHeight?.takeIf { it > 0 } }
}

/** A sent link's fee lines: the funding fee (null when unknown), the claim fee reserve and the total (null without the fee). */
class SentCosts(val networkFee: Long?, val claimFeeReserve: Long, val total: Long?)

/** Title, amounts and TalkBack words of the Activity row of a received link not claimed yet. */
class PendingLinkRowText(val title: String, val amount: String?, val secondary: String?, val spoken: String)

/** Title, glyph and second line of an Activity row for a payment link transaction. */
class LinkRowText(val title: String, val subtitle: String, val subtitleIsStatus: Boolean, val spoken: String)

/**
 * The row text of [tx] when it belongs to a payment link, else null (the row
 * keeps "Received" / "Sent" and the date).
 */
fun linkRowText(
    tx: TransactionInfo,
    index: PaymentLinkIndex,
    date: String,
    amountSpoken: String,
    transactionStatus: String,
    nowSeconds: Long = System.currentTimeMillis() / 1000
): LinkRowText? {
    val activity = index.match(tx) ?: return null
    val statusLine = activity.sent?.takeIf { activity.kind == LinkActivityKind.FUNDING }?.let {
        // The row is the funding transaction: its count is the live one the detail and Reclaim read too.
        PaymentLinkActivityRules.fundingStatusLine(LinkStatusRules.sentView(it, tx.confirmations, null, nowSeconds).status)
    }
    val incoming = tx.direction == TransactionInfo.Direction.Direction_In
    return LinkRowText(
        title = activity.title,
        subtitle = statusLine ?: date,
        subtitleIsStatus = statusLine != null,
        spoken = PaymentLinkActivityRules.spokenLabel(activity.title, incoming, amountSpoken, statusLine, date, transactionStatus)
    )
}

/**
 * Transaction detail: a "Payment link" row with the link's status chip, for a
 * transaction that belongs to a link.
 */
@Composable
fun PaymentLinkTransactionRow(activity: LinkActivity) {
    val transactions by WalletManager.transactionsFlow.collectAsState()
    val daemonHeight = rememberDaemonHeight()
    val display = activity.sent?.let { sentDisplay(sentView(it, transactions, daemonHeight)) } ?: activity.received?.let { receivedDisplay(it, daemonHeight) }
        ?: return
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp)
            .clearAndSetSemantics { contentDescription = tr("Payment link: %s", display.label) },
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(tr("Payment link"), style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
        LinkStatusChip(display)
    }
}

/** Opens a transaction from Activity: the link's detail for a payment link transaction, else [transaction]. */
@Composable
fun ActivityTransactionDestination(
    txId: String,
    linkDetail: @Composable (kind: String, id: String) -> Unit,
    transaction: @Composable () -> Unit
) {
    val index = rememberPaymentLinkIndex()
    val transactions by WalletManager.transactionsFlow.collectAsState()
    val activity = transactions.firstOrNull { it.hash == txId }?.let(index::match)
    // A link removed while its detail is open falls back to the transaction.
    if (activity != null) linkDetail(activity.routeKind, activity.id) else transaction()
}
