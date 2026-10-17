package one.monero.moneroone.paymentlinks

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.ui.text.style.TextOverflow
import one.monero.moneroone.ui.components.MoneroTextField
import one.monero.moneroone.ui.components.DismissTextButton
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import one.monero.moneroone.ui.components.fiatAt
import one.monero.moneroone.ui.theme.TabularFigures
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleStartEffect
import io.horizontalsystems.monerokit.model.TransactionInfo
import one.monero.moneroone.core.locale.tr
import one.monero.moneroone.core.wallet.WalletViewModel
import one.monero.moneroone.ui.components.GlassCard
import one.monero.moneroone.ui.components.PrimaryButton
import one.monero.moneroone.ui.components.ProminentButton
import one.monero.moneroone.ui.theme.ErrorRed
import one.monero.moneroone.ui.theme.MoneroOrange
import one.monero.moneroone.ui.theme.MoneroTheme
import one.monero.moneroone.ui.theme.MonoCaption
import one.monero.moneroone.ui.theme.SuccessGreen
import one.monero.moneroone.ui.theme.WarningYellow
import one.monero.moneroone.ui.theme.truncateMiddle

object PaymentLinkRoutes {
    const val SENT = "sent"
    const val RECEIVED = "received"

    /** A link's transaction, opened from the link's detail: the transaction itself, never the link again. */
    const val TRANSACTION = "payment_link_tx/{txId}"
    fun transaction(txId: String) = "payment_link_tx/$txId"
}

private fun nowSeconds() = System.currentTimeMillis() / 1000

internal fun statusLabel(status: LinkStatus): String = when (status) {
    LinkStatus.NOT_FUNDED -> tr("Not funded")
    LinkStatus.PENDING -> tr("Pending")
    LinkStatus.READY -> tr("Ready")
    LinkStatus.CLAIMED -> tr("Claimed")
    LinkStatus.RECLAIMED -> tr("Reclaimed")
    LinkStatus.EXPIRED -> tr("Expired")
}

/** A link's status chip: its label and hue (iOS PaymentLinkDisplay). */
internal class LinkDisplay(val label: String, val hue: LinkHue)

/** Chip hues; yellow keeps the label color, as the brand rule asks. */
internal enum class LinkHue { RED, BRAND, GREEN, GRAY, YELLOW }

/**
 * [link]'s funding in the open wallet's history: its funding txid, else an outgoing transaction to its
 * envelope (a record whose txid was never saved). Null when that wallet did not fund it.
 */
internal fun fundingTransaction(link: SentLink, transactions: List<TransactionInfo>): TransactionInfo? {
    val outgoing = transactions.filter { it.direction == TransactionInfo.Direction.Direction_Out && !it.isFailed }
    link.fundingTxid?.let { txid -> outgoing.firstOrNull { it.hash == txid }?.let { return it } }
    return outgoing.firstOrNull { tx -> tx.transfers?.any { it.address == link.envelopeAddress } == true }
}

/** The live confirmations of [link]'s funding in the open wallet's history; null when that wallet did not fund it. */
internal fun fundingConfirmations(link: SentLink, transactions: List<TransactionInfo>): Long? =
    fundingTransaction(link, transactions)?.confirmations

/** The network fee of [link]'s funding, from the open wallet's history; null when that wallet did not fund it. */
internal fun fundingFee(link: SentLink, transactions: List<TransactionInfo>): Long? =
    fundingTransaction(link, transactions)?.fee?.takeIf { it > 0 }

/** [LinkStatusRules.sentView] with the open wallet's history and daemon height. */
internal fun sentView(link: SentLink, transactions: List<TransactionInfo>, daemonHeight: Long?, nowSeconds: Long = nowSeconds()): SentView =
    LinkStatusRules.sentView(link, fundingConfirmations(link, transactions), daemonHeight, nowSeconds)

/** A sent link's chip, from the same view as its Activity row and Reclaim ([LinkStatusRules.sentView]). */
internal fun sentDisplay(view: SentView): LinkDisplay {
    val status = view.status
    val confirmations = view.confirmations
    return when (status) {
        LinkStatus.NOT_FUNDED -> LinkDisplay(statusLabel(status), LinkHue.RED)
        LinkStatus.PENDING -> LinkDisplay(
            confirmations?.let { tr("Pending %s/%s", it.coerceAtMost(LinkStatusRules.CONFIRMATIONS), LinkStatusRules.CONFIRMATIONS) }
                ?: statusLabel(status),
            LinkHue.BRAND
        )
        LinkStatus.READY -> LinkDisplay(statusLabel(status), LinkHue.GREEN)
        LinkStatus.CLAIMED -> LinkDisplay(statusLabel(status), LinkHue.GREEN)
        LinkStatus.RECLAIMED -> LinkDisplay(statusLabel(status), LinkHue.GRAY)
        LinkStatus.EXPIRED -> LinkDisplay(statusLabel(status), LinkHue.YELLOW)
    }
}

/** A received link's chip: Not checked, Pending n/10, Ready to claim, Claimed (here) or Already claimed. */
internal fun receivedDisplay(link: ReceivedLink, daemonHeight: Long?, nowSeconds: Long = nowSeconds()): LinkDisplay {
    if (link.state == ReceivedState.CLAIMED) return LinkDisplay(tr("Claimed"), LinkHue.GREEN)
    if (LinkStatusRules.receivedExpired(link, nowSeconds)) return LinkDisplay(tr("Expired"), LinkHue.YELLOW)
    val check = link.lastCheck ?: return LinkDisplay(tr("Not checked"), LinkHue.GRAY)
    val view = LinkStatusRules.claimView(check)
    val live = view is ClaimView.Waiting || view is ClaimView.Ready
    if (live && link.expiry > 0 && nowSeconds > link.expiry) return LinkDisplay(tr("Expired"), LinkHue.YELLOW)
    return when (view) {
        ClaimView.AlreadyClaimed -> LinkDisplay(tr("Already claimed"), LinkHue.GRAY)
        // The same words as the pinned row in Activity, so both say the same.
        ClaimView.NoMoney -> LinkDisplay(PaymentLinkActivityRules.pendingLabel(PendingLinkState.NoMoney), LinkHue.GRAY)
        is ClaimView.Ready -> LinkDisplay(tr("Ready to claim"), LinkHue.GREEN)
        is ClaimView.Waiting -> {
            if (PaymentLinkActivityRules.pendingState(link, daemonHeight, nowSeconds * 1000) == PendingLinkState.Ready) {
                return LinkDisplay(PaymentLinkActivityRules.pendingLabel(PendingLinkState.Ready), LinkHue.GREEN)
            }
            val confirmations = PaymentLinkActivityRules.receivedConfirmations(link, daemonHeight) ?: 0L
            LinkDisplay(
                tr("Pending %s/%s", confirmations.coerceAtMost(LinkStatusRules.CONFIRMATIONS), LinkStatusRules.CONFIRMATIONS),
                LinkHue.BRAND
            )
        }
    }
}

/** A status chip: 15% tint, caption semibold in the hue (yellow keeps the label color, as the brand rule asks). */
@Composable
private fun StatusChip(label: String, hue: Color, labelInHue: Boolean = true) {
    Text(
        label,
        style = MaterialTheme.typography.bodySmall,
        fontWeight = FontWeight.SemiBold,
        color = if (labelInHue) hue else MaterialTheme.colorScheme.onSurface,
        maxLines = 1,
        softWrap = false,
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .background(hue.copy(alpha = 0.15f))
            .padding(horizontal = 12.dp, vertical = 6.dp)
    )
}

@Composable
internal fun LinkStatusChip(display: LinkDisplay) = when (display.hue) {
    LinkHue.RED -> StatusChip(display.label, ErrorRed)
    LinkHue.BRAND -> StatusChip(display.label, MoneroOrange)
    LinkHue.GREEN -> StatusChip(display.label, SuccessGreen)
    LinkHue.GRAY -> StatusChip(display.label, MoneroTheme.colors.gray)
    LinkHue.YELLOW -> StatusChip(display.label, WarningYellow, labelInHue = false)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun LinksScaffold(title: String, onBack: () -> Unit, content: @Composable () -> Unit) {
    Scaffold(
        containerColor = MoneroTheme.colors.bgGrouped,
        contentWindowInsets = WindowInsets(0.dp),
        topBar = {
            TopAppBar(
                title = { Text(title, style = MaterialTheme.typography.titleMedium) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = tr("Back"))
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent),
                modifier = Modifier.windowInsetsPadding(WindowInsets.statusBars)
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp)
        ) {
            content()
            Spacer(Modifier.height(40.dp))
        }
    }
}

@Composable
private fun DetailRow(label: String, value: String, mono: Boolean = false) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 14.dp).semantics(mergeDescendants = true) {},
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(label, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.weight(1f))
        Text(value, style = if (mono) MonoCaption else MaterialTheme.typography.bodyMedium)
    }
}

/** A transaction row: it opens the transaction when the open wallet has it. */
@Composable
private fun TransactionRow(
    label: String,
    txid: String,
    incoming: Boolean,
    transactions: List<TransactionInfo>,
    onOpenTransaction: (String) -> Unit
) {
    val direction = if (incoming) TransactionInfo.Direction.Direction_In else TransactionInfo.Direction.Direction_Out
    val known = transactions.any { it.hash == txid && it.direction == direction }
    if (!known) {
        DetailRow(label, truncateMiddle(txid), mono = true)
        return
    }
    val open = { onOpenTransaction(txid) }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = open)
            .semantics(mergeDescendants = true) { onClick(label = tr("Shows the transaction")) { open(); true } }
            .padding(start = 16.dp, end = 8.dp, top = 14.dp, bottom = 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(label, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.weight(1f))
        Text(truncateMiddle(txid), style = MonoCaption)
        Icon(
            Icons.AutoMirrored.Filled.KeyboardArrowRight,
            contentDescription = null,
            tint = MoneroTheme.colors.labelTertiary,
            modifier = Modifier.padding(start = 4.dp).size(20.dp)
        )
    }
}

@Composable
private fun DetailDivider() = HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant, modifier = Modifier.padding(start = 16.dp))

/** The link's name over its amount (Apple Card: the merchant over the amount). */
@Composable
private fun LinkNameTitle(name: String) {
    Text(name, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurface)
}

/** The Note row: this phone's note, or "Add"; a tap renames the link. */
@Composable
private fun NoteRow(note: String?, onRename: () -> Unit) {
    val spoken = note?.let { tr("Note, %s", it) } ?: tr("Note, none")
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onRename)
            .clearAndSetSemantics {
                contentDescription = spoken
                role = Role.Button
                onClick(label = tr("Renames the link")) { onRename(); true }
            }
            .padding(start = 16.dp, end = 12.dp, top = 14.dp, bottom = 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(tr("Note"), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.width(16.dp))
        Text(
            note ?: tr("Add"),
            style = MaterialTheme.typography.bodyMedium,
            color = if (note == null) MoneroTheme.colors.labelTertiary else MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.End,
            modifier = Modifier.weight(1f)
        )
        Icon(
            Icons.Outlined.Edit,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = 8.dp).size(18.dp)
        )
    }
}

/** A fee line: the XMR amount over its value in the user's currency. [emphasis] is the Total line. */
@Composable
private fun FeeRow(label: String, xmr: String, fiat: String?, emphasis: Boolean = false) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 14.dp).semantics(mergeDescendants = true) {},
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            label,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = if (emphasis) FontWeight.SemiBold else null,
            color = if (emphasis) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.weight(1f))
        Column(horizontalAlignment = Alignment.End) {
            Text(
                xmr,
                style = MaterialTheme.typography.bodyMedium.copy(fontFeatureSettings = TabularFigures),
                fontWeight = if (emphasis) FontWeight.SemiBold else null,
                color = if (emphasis) MoneroOrange else MaterialTheme.colorScheme.onSurface
            )
            fiat?.let {
                Text("≈ $it", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

/** "Name this link": the note field (placeholder "Link 7"), Cancel and Save. */
@Composable
private fun RenameLinkDialog(note: String?, placeholder: String, onSave: (String) -> Unit, onDismiss: () -> Unit) {
    var text by remember { mutableStateOf(note.orEmpty()) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(tr("Name this link")) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                MoneroTextField(
                    value = text,
                    onValueChange = { text = it.take(LinkNames.NOTE_LIMIT) },
                    modifier = Modifier.fillMaxWidth(),
                    placeholder = { Text(placeholder) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences, imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { onSave(text) })
                )
                Text(
                    tr("Only this phone keeps the note."),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        },
        confirmButton = { TextButton(onClick = { onSave(text) }) { Text(tr("Save"), color = MoneroOrange) } },
        dismissButton = { DismissTextButton(onClick = onDismiss) { Text(tr("Cancel")) } }
    )
}

/**
 * One link: its status, amount, transactions and the actions the spec's
 * status table gives it. Opened from the link's transactions in Activity;
 * its transaction rows open the transaction.
 */
@Composable
fun PaymentLinkDetailScreen(
    paymentLinks: PaymentLinksViewModel,
    walletViewModel: WalletViewModel,
    kind: String,
    id: String,
    onBack: () -> Unit,
    onOpenTransaction: (String) -> Unit = {}
) {
    if (kind == PaymentLinkRoutes.SENT) SentLinkDetail(paymentLinks, walletViewModel, id, onBack, onOpenTransaction)
    else ReceivedLinkDetail(paymentLinks, walletViewModel, id, onBack, onOpenTransaction)
}

@Composable
private fun SentLinkDetail(
    paymentLinks: PaymentLinksViewModel,
    walletViewModel: WalletViewModel,
    id: String,
    onBack: () -> Unit,
    onOpenTransaction: (String) -> Unit
) {
    val context = LocalContext.current
    val sentList by paymentLinks.sent.collectAsState()
    val link = sentList.firstOrNull { it.id == id }
    val session by paymentLinks.session.collectAsState()
    val sweep by paymentLinks.sweep.collectAsState()
    val pendingOpen by paymentLinks.store.pendingOpen.collectAsState()
    val daemonHeight = rememberDaemonHeight()
    val walletState by walletViewModel.walletState.collectAsState()

    var renaming by remember { mutableStateOf(false) }
    val currentPrice by walletViewModel.currentPrice.collectAsState()
    val currency by walletViewModel.selectedCurrency.collectAsState()

    // The envelope is open only while this screen is on screen: leaving or going to the background
    // closes it, so the main wallet runs again. A settled link needs no check.
    val wantsSession = link != null && !LinkStatusRules.isSettled(link)
    // Reclaim opens the claim sheet over this screen (iOS parity): the sheet takes this envelope session
    // while it is up, and this screen checks again once it closes.
    val sheetOpen = pendingOpen == id
    val sheetUp by rememberUpdatedState(sheetOpen)
    LifecycleStartEffect(id, wantsSession, sheetOpen) {
        if (wantsSession && !sheetOpen) paymentLinks.openSession(id)
        onStopOrDispose { if (!sheetUp) paymentLinks.closeSession() }
    }
    DisposableEffect(id) { onDispose { paymentLinks.clearSweep() } }

    LinksScaffold(tr("Payment Link"), onBack) {
        if (link == null) return@LinksScaffold
        val view = sentView(link, walletState.transactions, daemonHeight)
        val status = view.status
        val current = session?.takeIf { it.id == id }
        val checking = current == null || current.phase == EnvelopeSession.Phase.CHECKING
        val result = sweep?.takeIf { it.id == id }
        val amount = "${walletViewModel.formatXmr(link.amount)} XMR"

        Spacer(Modifier.height(16.dp))
        GlassCard(modifier = Modifier.fillMaxWidth(), cornerRadius = 16.dp) {
            Column {
                Column(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 20.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    LinkNameTitle(link.name)
                    Text(amount, style = MaterialTheme.typography.headlineMedium)
                    LinkStatusChip(sentDisplay(view))
                }
                DetailDivider()
                NoteRow(LinkNames.cleanNote(link.note)) { renaming = true }
                DetailDivider()
                DetailRow(tr("Created"), formatRecordDate(link.createdAtMillis))
                DetailDivider()
                DetailRow(tr("Expires"), formatLinkDate(link.expiry))
                // Pending is under 10 confirmations: at 10 the link reads Ready.
                view.confirmations?.takeIf { status == LinkStatus.PENDING }?.let {
                    DetailDivider()
                    DetailRow(tr("Confirmations"), tr("%s/10", it.coerceAtMost(LinkStatusRules.CONFIRMATIONS - 1)))
                }
                // What left the wallet: the link's amount, the funding fee and the claim fee reserve.
                val costs = PaymentLinkActivityRules.sentCosts(link, fundingFee(link, walletState.transactions))
                val price = currentPrice?.price
                costs.networkFee?.let {
                    DetailDivider()
                    FeeRow(tr("Network fee"), "${walletViewModel.formatXmr(it)} XMR", fiatAt(it, price, currency))
                }
                DetailDivider()
                FeeRow(tr("Claim fee reserve"), "${walletViewModel.formatXmr(costs.claimFeeReserve)} XMR", fiatAt(costs.claimFeeReserve, price, currency))
                costs.total?.let {
                    DetailDivider()
                    FeeRow(tr("Total"), "${walletViewModel.formatXmr(it)} XMR", fiatAt(it, price, currency), emphasis = true)
                }
                link.fundingTxid?.let {
                    DetailDivider()
                    TransactionRow(tr("Funding transaction"), it, incoming = false, walletState.transactions, onOpenTransaction)
                }
                link.reclaimTxid?.takeIf { it.isNotEmpty() }?.let {
                    DetailDivider()
                    TransactionRow(tr("Reclaim transaction"), it, incoming = true, walletState.transactions, onOpenTransaction)
                }
            }
        }

        Spacer(Modifier.height(12.dp))
        when {
            checking && !LinkStatusRules.isSettled(link) -> Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.padding(horizontal = 16.dp)
            ) {
                CircularProgressIndicator(color = MoneroOrange, modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                Text(tr("Checking the link…"), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            current?.phase == EnvelopeSession.Phase.ERROR -> Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(horizontal = 16.dp)
            ) {
                Text(tr("Could not check this link."), style = MaterialTheme.typography.bodyMedium, color = ErrorRed, modifier = Modifier.weight(1f))
                IconButton(onClick = { paymentLinks.recheck(id) }) {
                    Icon(Icons.Default.Refresh, contentDescription = tr("Try Again"), tint = MoneroOrange)
                }
            }
            current?.phase == EnvelopeSession.Phase.SWEEPING -> Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.padding(horizontal = 16.dp)
            ) {
                CircularProgressIndicator(color = MoneroOrange, modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                Text(tr("Reclaiming…"), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        result?.let {
            val text = when (val outcome = it.outcome) {
                is SweepOutcome.Sent -> tr("Reclaimed. On its way to %s.", it.walletName)
                SweepOutcome.AlreadyClaimed -> tr("Already claimed")
                is SweepOutcome.Failed -> outcome.message.ifBlank { tr("Transaction Failed") }
            }
            Text(
                text,
                style = MaterialTheme.typography.bodyMedium,
                color = if (it.outcome is SweepOutcome.Failed) ErrorRed else MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
            )
        }

        Spacer(Modifier.height(16.dp))
        // Ready by the funding's count or by a check. Reclaim opens the claim sheet: it picks the wallet (the
        // funding wallet first), checks the envelope again, and its Reclaim button confirms.
        val canReclaim = view.canReclaim && current?.phase != EnvelopeSession.Phase.SWEEPING
        val reclaim = { paymentLinks.store.openClaim(id) }
        val share = { shareLink(context, walletViewModel.formatXmr(link.amount), link.link) }
        // No Retry (nothing funds a saved link again) and no Remove (the secret stays). Share and Reclaim
        // stay while the link has a funding transaction and is not claimed or reclaimed, whatever the last check said.
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            for (action in LinkStatusRules.sentActions(status)) when (action) {
                SentLinkAction.SHARE_AGAIN -> PrimaryButton(onClick = share, modifier = Modifier.fillMaxWidth()) {
                    Icon(Icons.Default.Share, contentDescription = null, modifier = Modifier.size(20.dp))
                    Text(tr("Share again"))
                }
                SentLinkAction.RECLAIM -> if (status == LinkStatus.EXPIRED) {
                    ProminentButton(onClick = reclaim, enabled = canReclaim, modifier = Modifier.fillMaxWidth()) {
                        Text(tr("Reclaim"))
                    }
                } else {
                    PrimaryButton(onClick = reclaim, enabled = canReclaim, modifier = Modifier.fillMaxWidth()) {
                        Text(if (status == LinkStatus.PENDING) tr("Reclaim when unlocked") else tr("Reclaim"))
                    }
                }
            }
        }
        if (renaming) {
            RenameLinkDialog(
                note = LinkNames.cleanNote(link.note),
                placeholder = LinkNames.name(null, link.number),
                onSave = { paymentLinks.rename(id, it); renaming = false },
                onDismiss = { renaming = false }
            )
        }
    }
}

/**
 * A received link (iOS receivedContent): the amount when known, the status,
 * the time left, the claim transaction and Claim.
 */
@Composable
private fun ReceivedLinkDetail(
    paymentLinks: PaymentLinksViewModel,
    walletViewModel: WalletViewModel,
    id: String,
    onBack: () -> Unit,
    onOpenTransaction: (String) -> Unit
) {
    val receivedList by paymentLinks.received.collectAsState()
    val walletState by walletViewModel.walletState.collectAsState()
    val daemonHeight = rememberDaemonHeight()
    val link = receivedList.firstOrNull { it.id == id }
    var renaming by remember { mutableStateOf(false) }

    LinksScaffold(tr("Payment Link"), onBack) {
        if (link == null) return@LinksScaffold
        val display = receivedDisplay(link, daemonHeight)
        val canClaim = !link.isSettled && link.link != null
        val waiting = link.lastCheck?.let { LinkStatusRules.claimView(it) } is ClaimView.Waiting
        val confirmations = PaymentLinkActivityRules.receivedConfirmations(link, daemonHeight) ?: 0L
        val amount = (link.claimedAmount ?: link.claimAmount)?.let { "${walletViewModel.formatXmr(it)} XMR" }
        Spacer(Modifier.height(16.dp))
        GlassCard(modifier = Modifier.fillMaxWidth(), cornerRadius = 16.dp) {
            Column {
                Column(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 20.dp).semantics(mergeDescendants = true) {},
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    LinkNameTitle(link.name)
                    amount?.let { Text(it, style = MaterialTheme.typography.headlineMedium) }
                    LinkStatusChip(display)
                    if (canClaim && waiting && confirmations < LinkStatusRules.CONFIRMATIONS) {
                        Text(
                            tr("Ready in about %s min", PaymentLinkActivityRules.minutesUntilReady(confirmations).coerceAtLeast(1)),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                DetailDivider()
                NoteRow(LinkNames.cleanNote(link.note)) { renaming = true }
                DetailDivider()
                DetailRow(tr("Received"), formatRecordDate(link.receivedAtMillis))
                if (canClaim && link.expiry > 0) {
                    DetailDivider()
                    DetailRow(tr("Claim by"), formatLinkDate(link.expiry))
                }
                link.claimTxid?.let {
                    DetailDivider()
                    TransactionRow(tr("Claim transaction"), it, incoming = true, walletState.transactions, onOpenTransaction)
                }
                link.claimedTo?.takeIf { link.claimTxid != null && it.isNotEmpty() }?.let {
                    DetailDivider()
                    DetailRow(tr("Claimed to"), it)
                }
            }
        }
        Spacer(Modifier.height(28.dp))
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (canClaim) {
                PrimaryButton(
                    onClick = { paymentLinks.store.openClaim(id) },
                    modifier = Modifier.fillMaxWidth().semantics {
                        contentDescription = tr("Claim the money")
                        onClick(label = tr("Opens the link and claims it when it is ready")) { paymentLinks.store.openClaim(id); true }
                    }
                ) {
                    Text(tr("Claim"))
                }
            }
            if (canClaim && waiting) {
                Text(
                    tr("Claim opens when the link is ready, after 10 confirmations."),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp)
                )
            }
        }
        if (renaming) {
            RenameLinkDialog(
                note = LinkNames.cleanNote(link.note),
                placeholder = LinkNames.name(null, link.number),
                onSave = { paymentLinks.rename(id, it); renaming = false },
                onDismiss = { renaming = false }
            )
        }
    }
}
