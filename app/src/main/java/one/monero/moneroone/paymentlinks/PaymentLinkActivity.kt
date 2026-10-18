package one.monero.moneroone.paymentlinks

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.horizontalsystems.monerokit.model.TransactionInfo
import one.monero.moneroone.core.locale.tr
import one.monero.moneroone.core.wallet.WalletManager
import one.monero.moneroone.data.util.XmrFormat
import one.monero.moneroone.ui.components.StatusLabel
import one.monero.moneroone.ui.components.TransactionStatus
import one.monero.moneroone.ui.components.TransactionStatusIndicator
import one.monero.moneroone.ui.theme.MoneroOrange
import one.monero.moneroone.ui.theme.MoneroTheme
import one.monero.moneroone.ui.theme.SuccessGreen
import one.monero.moneroone.ui.theme.WarningYellow

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

    /** Its 30 days are over and no money arrived ([LinkStatusRules.receivedExpired]): History, as Expired. */
    object Expired : PendingLinkState
}

/**
 * Payment links in Activity, the same rules as iOS `PaymentLinkActivity`.
 * A transaction matches a link record on this device by txid:
 * - sent record, outgoing tx == fundingTxid or one of its signed attempt: "Sent by Link" (funding)
 * - sent record, incoming tx == a transaction of our reclaim: "Reclaimed by Link"
 * - received record, incoming tx == a transaction of our claim: "Received by Link"
 * A send wallet2 split in several transactions labels each of them.
 * Every other transaction keeps "Received" / "Sent". A link row is a normal
 * row (Apple Card): the direction disc with a link badge, the link's name over
 * the date, its amount and a status. The kind ("Sent by Link") is spoken only.
 */
object PaymentLinkActivityRules {

    fun match(txid: String, incoming: Boolean, sent: List<SentLink>, received: List<ReceivedLink>): LinkActivity? {
        for (link in sent) {
            if (!incoming && txid in LinkStatusRules.ownFundingTxids(link)) return LinkActivity(LinkActivityKind.FUNDING, link, null)
            if (incoming && txid in LinkStatusRules.ownReclaimTxids(link)) return LinkActivity(LinkActivityKind.RECLAIM, link, null)
        }
        for (link in received) {
            if (incoming && txid in link.ownClaimTxids) return LinkActivity(LinkActivityKind.CLAIM, null, link)
        }
        return null
    }

    /**
     * A funding row's status, from [LinkStatusRules.sentView]'s status (ready at 10 confirmations), so the
     * row, the detail and Reclaim agree. The row is the funding transaction itself, so "not funded" never
     * shows: until a check sees the money, the link waits to be claimed.
     */
    fun fundingRowState(status: LinkStatus): LinkRowState = when (status) {
        LinkStatus.CLAIMED -> LinkRowState.CLAIMED
        LinkStatus.RECLAIMED -> LinkRowState.RECLAIMED
        LinkStatus.EXPIRED -> LinkRowState.EXPIRED
        LinkStatus.READY -> LinkRowState.READY
        LinkStatus.NOT_FUNDED, LinkStatus.PENDING -> LinkRowState.WAITING
    }

    /** A received link not claimed yet: Ready to claim once it can be claimed, else Waiting. */
    fun pendingRowState(state: PendingLinkState): LinkRowState = when (state) {
        PendingLinkState.Ready -> LinkRowState.READY_TO_CLAIM
        PendingLinkState.Expired -> LinkRowState.EXPIRED
        else -> LinkRowState.WAITING
    }

    fun stateLabel(state: LinkRowState): String = when (state) {
        LinkRowState.WAITING -> tr("Waiting")
        LinkRowState.READY -> tr("Ready")
        LinkRowState.CLAIMED -> tr("Claimed")
        LinkRowState.RECLAIMED -> tr("Reclaimed")
        LinkRowState.EXPIRED -> tr("Expired")
        LinkRowState.READY_TO_CLAIM -> tr("Ready to claim")
    }

    /**
     * A link row's text. [amount] is the one amount of the link (atomic), null when unknown;
     * [estimate] marks a claim not made yet ("+~"). The amount follows fiat mode like every row.
     * TalkBack keeps the kind: "Sent by Link, Dinner, minus 0.02 XMR, Waiting, Oct 16".
     */
    fun rowText(
        kind: String,
        name: String,
        incoming: Boolean,
        amount: Long?,
        estimate: Boolean,
        fiatMode: Boolean,
        fiatValue: String?,
        state: LinkRowState?,
        transactionStatus: String,
        date: String
    ): LinkRowText {
        val statusWord = state?.let(::stateLabel) ?: transactionStatus
        if (amount == null) {
            return LinkRowText(name, incoming, null, null, state, listOf(kind, name, statusWord, date).joinToString(", "))
        }
        val about = if (estimate) "~" else ""
        val xmr = "${XmrFormat.format(amount)} XMR"
        val fiatFirst = fiatMode && fiatValue != null
        val sign = if (incoming) "+" else "−"
        val primary = sign + about + if (fiatFirst) fiatValue else xmr
        val secondary = if (fiatFirst) "$about${XmrFormat.compact(amount)} XMR" else fiatValue?.let { about + it }
        // TalkBack would read "tilde": the estimate is "about".
        val amountSpoken = if (fiatFirst) "$fiatValue, ${XmrFormat.compact(amount)} XMR" else xmr
        val signed = when {
            incoming && estimate -> tr("plus about %s", amountSpoken)
            incoming -> tr("plus %s", amountSpoken)
            else -> tr("minus %s", amountSpoken)
        }
        return LinkRowText(name, incoming, primary, secondary, state, listOf(kind, name, signed, statusWord, date).joinToString(", "))
    }

    /**
     * The row of a link's transaction. A funding row shows the link's amount (what the recipient
     * gets; the fees show in the detail) and the link's status; a reclaim or claim row shows its
     * own amount and the transaction's status.
     */
    fun transactionRow(
        activity: LinkActivity,
        txAmount: Long,
        txConfirmations: Long,
        incoming: Boolean,
        date: String,
        fiatMode: Boolean,
        fiatOf: (Long) -> String?,
        transactionStatus: String,
        nowSeconds: Long
    ): LinkRowText {
        val funding = activity.sent?.takeIf { activity.kind == LinkActivityKind.FUNDING }
        val amount = funding?.amount ?: txAmount
        val state = funding?.let { fundingRowState(LinkStatusRules.sentView(it, txConfirmations, null, nowSeconds).status) }
        val name = activity.sent?.name ?: activity.received?.name ?: tr("Payment link")
        return rowText(activity.title, name, incoming, amount, false, fiatMode, fiatOf(amount), state, transactionStatus, date)
    }

    /**
     * The row of an open sent link whose funding is not in the open wallet's history (the wallet that funded it
     * was deleted or restored under a new id): the funding row's text, from the link's record.
     */
    fun sentRow(link: SentLink, status: LinkStatus, date: String, fiatMode: Boolean, fiatValue: String?): LinkRowText =
        rowText(tr("Sent by Link"), link.name, false, link.amount, false, fiatMode, fiatValue, fundingRowState(status), "", date)

    /** The row of a received link not claimed yet (it has no transaction): its name, the claim estimate, Ready or Waiting. */
    fun pendingRow(link: ReceivedLink, state: PendingLinkState, date: String, fiatMode: Boolean, fiatValue: String?): LinkRowText =
        rowText(tr("Payment link"), link.name, true, link.claimAmount, true, fiatMode, fiatValue, pendingRowState(state), "", date)

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
        if (LinkStatusRules.receivedExpired(link, nowMillis / 1000)) return PendingLinkState.Expired
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
        PendingLinkState.Expired -> tr("Expired")
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

/**
 * One row of Activity's Links filter: a link's transaction, a received link with no transaction yet, or an
 * open sent link whose funding is not in this wallet's history ([sent]).
 */
class LinkListEntry(
    val transaction: TransactionInfo?,
    val pending: ReceivedLink?,
    val linkId: String,
    /** The link still holds money: a sent link Waiting, Ready or Expired, or a received link not claimed. */
    val open: Boolean,
    val atMillis: Long,
    val sent: SentLink? = null
) {
    val key: String get() = transaction?.hash ?: if (sent != null) "sent-$linkId" else "pending-$linkId"
}

/** The Links filter: Open (newest first, sent and received mixed), then History; each row once. */
class LinkSections(val open: List<LinkListEntry>, val history: List<LinkListEntry>, val linkCount: Int) {
    val openCount: Int get() = open.size
}

/**
 * Activity's Links filter (Apple Card): the summary card first ("7 links · 4
 * open"), then the links that still hold money under Open, then the rest under
 * History. A sent link is open while its funding row is Waiting, Ready or
 * Expired; its reclaim row and every claim row go to History.
 */
object LinkListRules {
    private val openStates = setOf(LinkRowState.WAITING, LinkRowState.READY, LinkRowState.EXPIRED, LinkRowState.READY_TO_CLAIM)

    /**
     * [openSent]: the open sent links of this wallet ([OpenLinkRules.belongsTo]); one with no row of its own
     * (its funding is not in this wallet's history) still shows under Open, so Share and Reclaim stay in reach.
     */
    fun sections(
        transactions: List<TransactionInfo>,
        index: PaymentLinkIndex,
        pending: List<ReceivedLink>,
        nowSeconds: Long,
        openSent: List<SentLink> = emptyList()
    ): LinkSections {
        // A funding wallet2 split in several transactions: one row for the link, its funding txid's when shown.
        val fundingRow = HashMap<String, TransactionInfo>()
        for (tx in transactions) {
            val link = index.match(tx)?.takeIf { it.kind == LinkActivityKind.FUNDING }?.sent ?: continue
            if (fundingRow[link.id] == null || tx.hash == link.fundingTxid) fundingRow[link.id] = tx
        }
        val rows = transactions.mapNotNull { tx ->
            val activity = index.match(tx) ?: return@mapNotNull null
            val funding = activity.sent?.takeIf { activity.kind == LinkActivityKind.FUNDING }
            if (funding != null && fundingRow[funding.id] !== tx) return@mapNotNull null
            val open = funding != null && PaymentLinkActivityRules.fundingRowState(
                LinkStatusRules.sentView(funding, tx.confirmations, null, nowSeconds).status
            ) in openStates
            LinkListEntry(tx, null, activity.id, open, tx.timestamp * 1000)
        }
        val shown = rows.mapTo(HashSet()) { it.linkId }
        // A received link with no money after its 30 days goes to History.
        val waiting = pending.filter { it.id !in shown }
            .map { LinkListEntry(null, it, it.id, !LinkStatusRules.receivedExpired(it, nowSeconds), it.receivedAtMillis) }
        val rowless = openSent.filter { it.id !in shown }
            .map { LinkListEntry(null, null, it.id, open = true, atMillis = it.createdAtMillis, sent = it) }
        val all = (rows + waiting + rowless).sortedByDescending { it.atMillis }
        return LinkSections(all.filter { it.open }, all.filterNot { it.open }, (shown + waiting.map { it.linkId } + rowless.map { it.linkId }).size)
    }
}

/** The payment link records of this device, to match Activity rows against. */
class PaymentLinkIndex(val sent: List<SentLink>, val received: List<ReceivedLink>) {
    /** A funding the wallet marks failed sent no money: it stays a plain failed transaction, and its link stays hidden. */
    fun match(tx: TransactionInfo): LinkActivity? =
        PaymentLinkActivityRules.match(tx.hash, tx.direction == TransactionInfo.Direction.Direction_In, sent, received)
            ?.takeUnless { tx.isFailed && it.kind == LinkActivityKind.FUNDING }
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

/** A link row's status when the row has no transaction status of its own. */
/** READY: a sent link the recipient can claim. READY_TO_CLAIM: a link sent to us that we can claim now. */
enum class LinkRowState { WAITING, READY, CLAIMED, RECLAIMED, EXPIRED, READY_TO_CLAIM }

/**
 * A link row: the name (title), the amount over the status and [secondary]
 * (fiat, or XMR in fiat mode), and the TalkBack words. [state] null: the row
 * shows the transaction's status.
 */
class LinkRowText(
    val title: String,
    val incoming: Boolean,
    val amount: String?,
    val secondary: String?,
    val state: LinkRowState?,
    val spoken: String
)

/**
 * The row text of [tx] when it belongs to a payment link, else null (the row
 * keeps "Received" / "Sent"). [fiatOf] gives an amount's value at the time of [tx].
 */
fun linkRowText(
    tx: TransactionInfo,
    index: PaymentLinkIndex,
    date: String,
    fiatMode: Boolean,
    fiatOf: (Long) -> String?,
    transactionStatus: String,
    nowSeconds: Long = System.currentTimeMillis() / 1000
): LinkRowText? {
    val activity = index.match(tx) ?: return null
    // A funding row's count is the live one the detail and Reclaim read too.
    return PaymentLinkActivityRules.transactionRow(
        activity, tx.amount, tx.confirmations, tx.direction == TransactionInfo.Direction.Direction_In,
        date, fiatMode, fiatOf, transactionStatus, nowSeconds
    )
}

/**
 * A link row's amounts, as a transaction row's ([TransactionAmounts]): the
 * amount, the status spot (the link's state, else [transactionStatus]) and the
 * secondary amount.
 */
@Composable
fun LinkRowAmounts(text: LinkRowText, transactionStatus: TransactionStatus?) {
    Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(2.dp)) {
        text.amount?.let {
            Text(
                text = it,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                color = if (text.incoming) SuccessGreen else MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                softWrap = false
            )
        }
        val state = text.state
        if (state != null) LinkStateLabel(state) else transactionStatus?.let { TransactionStatusIndicator(status = it) }
        text.secondary?.let {
            Text(
                text = it,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                softWrap = false
            )
        }
    }
}

/** Waiting orange, Ready, Ready to claim and Claimed green, Reclaimed gray, Expired a yellow dot with label-color text. */
@Composable
fun LinkStateLabel(state: LinkRowState) {
    val label = PaymentLinkActivityRules.stateLabel(state)
    when (state) {
        LinkRowState.WAITING -> StatusLabel(label, MoneroOrange)
        LinkRowState.READY, LinkRowState.READY_TO_CLAIM, LinkRowState.CLAIMED -> StatusLabel(label, SuccessGreen)
        LinkRowState.RECLAIMED -> StatusLabel(label, MoneroTheme.colors.gray)
        LinkRowState.EXPIRED -> StatusLabel(label, WarningYellow, textColor = MaterialTheme.colorScheme.onSurface)
    }
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
