package one.monero.moneroone.paymentlinks

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Link
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.horizontalsystems.monerokit.model.TransactionInfo
import one.monero.moneroone.core.locale.tr
import one.monero.moneroone.core.wallet.WalletManager
import one.monero.moneroone.ui.components.GlassCard
import one.monero.moneroone.ui.theme.MoneroOrange
import one.monero.moneroone.ui.theme.MoneroTheme
import one.monero.moneroone.ui.theme.SuccessGreen

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
            LinkActivityKind.FUNDING -> tr("Payment link")
            LinkActivityKind.RECLAIM -> tr("Link reclaimed")
            LinkActivityKind.CLAIM -> tr("Claimed link")
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
 * - sent record, outgoing tx == fundingTxid: "Payment link" (funding)
 * - sent record, incoming tx == reclaimTxid: "Link reclaimed"
 * - received record, incoming tx == claimTxid: "Claimed link"
 * Every other transaction keeps "Received" / "Sent". A link row shows a link
 * glyph in the disc of its direction and opens the link's detail.
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
     * A funding row's second line, in place of the date. The row is the funding
     * transaction itself, so "not funded" never shows: until a check sees the
     * money, the link waits to be claimed.
     */
    fun fundingStatusLine(status: LinkStatus, confirmations: Long?): String = when {
        status == LinkStatus.CLAIMED -> tr("Claimed")
        status == LinkStatus.RECLAIMED -> tr("Reclaimed")
        status == LinkStatus.EXPIRED -> tr("Expired")
        status == LinkStatus.READY || (confirmations ?: 0) >= LinkStatusRules.CONFIRMATIONS -> tr("Ready to claim")
        else -> tr("Waiting to be claimed")
    }

    /** TalkBack: "Payment link, minus 0.5 XMR, Waiting to be claimed, 2 hours ago, Confirmed". */
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
     * Links that may still hold money: funded and not settled (sent or
     * received), and received links not checked yet.
     */
    fun openCount(sent: List<SentLink>, received: List<ReceivedLink>): Int {
        val openSent = sent.count {
            val status = LinkStatusRules.baseStatus(it)
            status == LinkStatus.PENDING || status == LinkStatus.READY
        }
        val openReceived = received.count { link ->
            if (link.isSettled) return@count false
            val check = link.lastCheck ?: return@count link.link != null
            when (LinkStatusRules.claimView(check)) {
                is ClaimView.Waiting, is ClaimView.Ready -> true
                ClaimView.NoMoney, ClaimView.AlreadyClaimed -> false
            }
        }
        return openSent + openReceived
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
        PaymentLinkActivityRules.fundingStatusLine(LinkStatusRules.status(it, nowSeconds), tx.confirmations)
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
 * One received link at the top of Activity: "Payment link", its state, the
 * amount when known, and a Claim chip once it is ready. A tap opens the claim
 * sheet; when ready, the sheet claims as soon as its own check confirms it.
 */
@Composable
fun PendingPaymentLinkRow(link: ReceivedLink, walletName: String?, formatXmr: (Long) -> String) {
    val store = PaymentLinkStore.get(LocalContext.current)
    val daemonHeight = rememberDaemonHeight()
    val state = PaymentLinkActivityRules.pendingState(link, daemonHeight, System.currentTimeMillis())
    val ready = state == PendingLinkState.Ready
    val label = PaymentLinkActivityRules.pendingLabel(state)
    val amount = link.knownAmount
    val spoken = listOfNotNull(tr("Payment link"), amount?.let { tr("plus %s", "${formatXmr(it)} XMR") }, label).joinToString(", ")
    val hint = if (ready && !walletName.isNullOrEmpty()) tr("Claims the money to %s", walletName) else tr("Opens the link to claim it")
    val open = { store.openClaim(link.id, autoClaim = ready) }

    // One TalkBack node, as a transaction card: the card's click (with the hint as its label) and the row's text.
    GlassCard(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("activity.paymentLink.${link.id}")
            .semantics { onClick(label = hint) { open(); true } },
        onClick = open
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clearAndSetSemantics { contentDescription = spoken }
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier.size(40.dp).clip(CircleShape).background(SuccessGreen.copy(alpha = 0.15f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(Icons.Default.Link, contentDescription = null, tint = SuccessGreen, modifier = Modifier.size(20.dp))
            }
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(tr("Payment link"), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Medium, maxLines = 1)
                Spacer(Modifier.height(2.dp))
                Text(
                    label,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (ready) SuccessGreen else MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(4.dp)) {
                amount?.let {
                    Text(
                        "+${formatXmr(it)}",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                        color = SuccessGreen,
                        maxLines = 1
                    )
                }
                if (ready) {
                    Text(
                        tr("Claim"),
                        style = MaterialTheme.typography.bodySmall,
                        fontWeight = FontWeight.SemiBold,
                        color = MoneroOrange,
                        modifier = Modifier
                            .clip(RoundedCornerShape(50))
                            .background(MoneroOrange.copy(alpha = 0.15f))
                            .padding(horizontal = 12.dp, vertical = 6.dp)
                    )
                }
            }
            Spacer(Modifier.width(8.dp))
            Icon(
                Icons.Default.ChevronRight,
                contentDescription = null,
                tint = MoneroTheme.colors.labelTertiary,
                modifier = Modifier.size(16.dp)
            )
        }
    }
}

/**
 * Under "Send with a link": every link this device made or received, with the
 * number still open. Shown once there is at least one link.
 */
@Composable
fun YourLinksRow(onClick: () -> Unit) {
    val store = PaymentLinkStore.get(LocalContext.current)
    val sent by store.sent.collectAsState()
    val received by store.received.collectAsState()
    if (sent.isEmpty() && received.isEmpty()) return
    val open = PaymentLinkActivityRules.openCount(sent, received)
    val spoken = if (open > 0) tr("Your links, %s open", open) else tr("Your links")
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .clickable(onClick = onClick)
            .clearAndSetSemantics {
                contentDescription = spoken
                onClick(label = tr("Shows the payment links you made and received")) { onClick(); true }
            }
            .padding(horizontal = 16.dp, vertical = 14.dp)
            .testTag("send.yourLinksRow"),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Text(tr("Your links"), style = MaterialTheme.typography.bodyMedium)
        if (open > 0) {
            Text("·", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(tr("%s open", open), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Spacer(Modifier.weight(1f))
        Icon(
            Icons.Default.ChevronRight,
            contentDescription = null,
            tint = MoneroTheme.colors.labelTertiary,
            modifier = Modifier.size(16.dp)
        )
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
    val display = activity.sent?.let { sentDisplay(it, transactions) } ?: activity.received?.let { receivedDisplay(it, daemonHeight) }
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
