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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Link
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import one.monero.moneroone.core.locale.tr
import one.monero.moneroone.core.wallet.SendFlow
import one.monero.moneroone.core.wallet.SendState
import one.monero.moneroone.core.wallet.WalletViewModel
import one.monero.moneroone.core.wallet.addressesOf
import one.monero.moneroone.ui.components.AuthGateDialog
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
    const val LIST = "payment_links"
    const val DETAIL = "payment_link/{kind}/{id}"
    const val SENT = "sent"
    const val RECEIVED = "received"
    fun detail(kind: String, id: String) = "payment_link/$kind/$id"
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

private fun receivedLabel(link: ReceivedLink): String = when {
    link.state == ReceivedState.CLAIMED -> tr("Claimed")
    else -> when (link.lastCheck?.let { LinkStatusRules.claimView(it) }) {
        is ClaimView.Ready -> tr("Ready")
        ClaimView.AlreadyClaimed -> tr("Claimed")
        ClaimView.NoMoney -> tr("Not funded")
        else -> tr("Pending")
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
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .background(hue.copy(alpha = 0.15f))
            .padding(horizontal = 12.dp, vertical = 6.dp)
    )
}

@Composable
private fun SentStatusChip(status: LinkStatus) = when (status) {
    LinkStatus.NOT_FUNDED -> StatusChip(statusLabel(status), ErrorRed)
    LinkStatus.PENDING -> StatusChip(statusLabel(status), MoneroOrange)
    LinkStatus.READY -> StatusChip(statusLabel(status), SuccessGreen)
    LinkStatus.CLAIMED, LinkStatus.RECLAIMED -> StatusChip(statusLabel(status), MoneroTheme.colors.gray)
    LinkStatus.EXPIRED -> StatusChip(statusLabel(status), WarningYellow, labelInHue = false)
}

@Composable
private fun ReceivedStatusChip(link: ReceivedLink) {
    val label = receivedLabel(link)
    val hue = when (label) {
        tr("Ready") -> SuccessGreen
        tr("Claimed") -> MoneroTheme.colors.gray
        tr("Not funded") -> MoneroTheme.colors.gray
        else -> MoneroOrange
    }
    StatusChip(label, hue)
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
private fun SectionHeader(title: String) {
    Text(
        title,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(start = 16.dp, top = 24.dp, bottom = 8.dp).semantics { }
    )
}

@Composable
private fun LinkRow(amount: String, date: String, chip: @Composable () -> Unit, spoken: String, onClick: () -> Unit, divider: Boolean) {
    Column {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onClick)
                .clearAndSetSemantics { contentDescription = spoken }
                .padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(Modifier.weight(1f)) {
                Text(amount, style = MaterialTheme.typography.bodyLarge)
                Text(date, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            chip()
            Icon(
                Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = null,
                tint = MoneroTheme.colors.labelTertiary,
                modifier = Modifier.padding(start = 8.dp).size(20.dp)
            )
        }
        if (divider) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant, modifier = Modifier.padding(start = 16.dp))
    }
}

/** Settings > Payment Links: Sent and Received. */
@Composable
fun PaymentLinksScreen(
    paymentLinks: PaymentLinksViewModel,
    formatXmr: (Long) -> String,
    onBack: () -> Unit,
    onOpen: (kind: String, id: String) -> Unit
) {
    val sent by paymentLinks.sent.collectAsState()
    val received by paymentLinks.received.collectAsState()

    // Checks each open link's envelope, one at a time, while the list is up.
    LaunchedEffect(Unit) { paymentLinks.refreshSent() }
    DisposableEffect(Unit) { onDispose { paymentLinks.stopRefresh() } }

    LinksScaffold(tr("Payment Links"), onBack) {
        if (sent.isEmpty() && received.isEmpty()) {
            Spacer(Modifier.height(16.dp))
            GlassCard(modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 32.dp, horizontal = 16.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Icon(Icons.Default.Link, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(40.dp))
                    Text(tr("No payment links yet"), style = MaterialTheme.typography.titleMedium)
                    Text(
                        tr("Links you send or open show here."),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            return@LinksScaffold
        }
        if (sent.isNotEmpty()) {
            SectionHeader(tr("Sent"))
            GlassCard(modifier = Modifier.fillMaxWidth(), cornerRadius = 16.dp) {
                Column {
                    val rows = sent.sortedByDescending { it.createdAtMillis }
                    rows.forEachIndexed { index, link ->
                        val status = LinkStatusRules.status(link, nowSeconds())
                        val amount = "${formatXmr(link.amount)} XMR"
                        val date = formatRecordDate(link.createdAtMillis)
                        LinkRow(
                            amount = amount,
                            date = date,
                            chip = { SentStatusChip(status) },
                            spoken = tr("Sent %s, %s, %s", amount, date, statusLabel(status)),
                            onClick = { onOpen(PaymentLinkRoutes.SENT, link.id) },
                            divider = index < rows.lastIndex
                        )
                    }
                }
            }
        }
        if (received.isNotEmpty()) {
            SectionHeader(tr("Received"))
            GlassCard(modifier = Modifier.fillMaxWidth(), cornerRadius = 16.dp) {
                Column {
                    val rows = received.sortedByDescending { it.receivedAtMillis }
                    rows.forEachIndexed { index, link ->
                        val amount = "${formatXmr(link.claimedAmount ?: link.amount)} XMR"
                        val date = formatRecordDate(link.receivedAtMillis)
                        LinkRow(
                            amount = amount,
                            date = date,
                            chip = { ReceivedStatusChip(link) },
                            spoken = tr("Received %s, %s, %s", amount, date, receivedLabel(link)),
                            onClick = { onOpen(PaymentLinkRoutes.RECEIVED, link.id) },
                            divider = index < rows.lastIndex
                        )
                    }
                }
            }
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

@Composable
private fun DetailDivider() = HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant, modifier = Modifier.padding(start = 16.dp))

@Composable
private fun RemoveButton(onClick: () -> Unit, enabled: Boolean = true) {
    TextButton(onClick = onClick, enabled = enabled, modifier = Modifier.fillMaxWidth()) {
        Text(tr("Remove"), color = if (enabled) ErrorRed else MoneroTheme.colors.gray, style = MaterialTheme.typography.labelLarge)
    }
}

@Composable
private fun ConfirmRemove(message: String, onConfirm: () -> Unit, onCancel: () -> Unit) {
    AlertDialog(
        onDismissRequest = onCancel,
        title = { Text(tr("Remove this link?")) },
        text = { Text(message) },
        confirmButton = { TextButton(onClick = onConfirm) { Text(tr("Remove"), color = ErrorRed) } },
        dismissButton = { TextButton(onClick = onCancel) { Text(tr("Cancel")) } }
    )
}

/** One link: its status and the actions the spec's status table gives it. */
@Composable
fun PaymentLinkDetailScreen(
    paymentLinks: PaymentLinksViewModel,
    walletViewModel: WalletViewModel,
    kind: String,
    id: String,
    onBack: () -> Unit
) {
    if (kind == PaymentLinkRoutes.SENT) SentLinkDetail(paymentLinks, walletViewModel, id, onBack)
    else ReceivedLinkDetail(paymentLinks, walletViewModel, id, onBack)
}

@Composable
private fun SentLinkDetail(paymentLinks: PaymentLinksViewModel, walletViewModel: WalletViewModel, id: String, onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val sentList by paymentLinks.sent.collectAsState()
    val link = sentList.firstOrNull { it.id == id }
    val session by paymentLinks.session.collectAsState()
    val sweep by paymentLinks.sweep.collectAsState()
    val activeWallet by walletViewModel.activeWallet.collectAsState()
    val walletState by walletViewModel.walletState.collectAsState()
    val sharedSendState by walletViewModel.sendState.collectAsState()
    val flow = remember(id) { SendFlow(walletViewModel.activeWallet.value?.id) }
    val sendState = sharedSendState.takeIf { it.flow == flow }

    var showAuthGate by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    var confirmRemove by remember { mutableStateOf(false) }

    LaunchedEffect(id) { paymentLinks.openSession(id) }
    DisposableEffect(id) {
        onDispose {
            paymentLinks.closeSession()
            paymentLinks.clearSweep()
        }
    }
    LaunchedEffect(sendState) {
        when (sendState) {
            is SendState.Success -> {
                paymentLinks.fundingFinished(id, success = true)
                walletViewModel.resetSendState(flow)
                busy = false
                message = null
                paymentLinks.recheck(id)
            }
            is SendState.Error -> {
                paymentLinks.fundingFinished(id, success = false)
                message = sendState.message
                walletViewModel.resetSendState(flow)
                busy = false
            }
            else -> {}
        }
    }

    LinksScaffold(tr("Payment Link"), onBack) {
        if (link == null) return@LinksScaffold
        val status = LinkStatusRules.status(link, nowSeconds())
        val current = session?.takeIf { it.id == id }
        val checking = current == null || current.phase == EnvelopeSession.Phase.CHECKING
        val result = sweep?.takeIf { it.id == id }
        val amount = "${walletViewModel.formatXmr(link.amount)} XMR"
        val receiveAddress = walletState.addressesOf(activeWallet?.id)?.shownAddress(walletState.receiveIndex)?.address

        Spacer(Modifier.height(16.dp))
        GlassCard(modifier = Modifier.fillMaxWidth(), cornerRadius = 16.dp) {
            Column {
                Column(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 20.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(amount, style = MaterialTheme.typography.headlineMedium)
                    SentStatusChip(status)
                }
                DetailDivider()
                DetailRow(tr("Created"), formatRecordDate(link.createdAtMillis))
                DetailDivider()
                DetailRow(tr("Expires"), formatLinkDate(link.expiry))
                link.lastCheck?.takeIf { status == LinkStatus.PENDING && it.fundingMined }?.let {
                    DetailDivider()
                    DetailRow(tr("Confirmations"), tr("%s/10", it.confirmations.coerceAtMost(LinkStatusRules.CONFIRMATIONS - 1)))
                }
                link.fundingTxid?.let {
                    DetailDivider()
                    DetailRow(tr("Funding"), truncateMiddle(it), mono = true)
                }
                link.reclaimTxid?.takeIf { it.isNotEmpty() }?.let {
                    DetailDivider()
                    DetailRow(tr("Reclaim"), truncateMiddle(it), mono = true)
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
                    Icon(Icons.Default.Refresh, contentDescription = tr("Retry"), tint = MoneroOrange)
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
                is SweepOutcome.Sent -> tr("Reclaimed. On its way to %s.", activeWallet?.name.orEmpty())
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
        message?.let {
            Text(it, style = MaterialTheme.typography.bodyMedium, color = ErrorRed, modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp))
        }

        Spacer(Modifier.height(16.dp))
        val live = current?.phase == EnvelopeSession.Phase.LIVE
        val canReclaim = live && LinkStatusRules.canReclaim(link) && receiveAddress != null
        val reclaim = {
            receiveAddress?.let { paymentLinks.sweepOpenEnvelope(SweepKind.RECLAIM, it, activeWallet?.name.orEmpty()) }
            Unit
        }
        val share = { shareLink(context, walletViewModel.formatXmr(link.amount), link.link) }
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            when (status) {
                LinkStatus.NOT_FUNDED -> {
                    PrimaryButton(
                        onClick = {
                            scope.launch {
                                busy = true
                                message = null
                                if (!paymentLinks.resumeMainWallet()) {
                                    message = tr("Wallet is not connected yet. Wait for the wallet to reconnect and try again.")
                                    busy = false
                                } else if (paymentLinks.fundingAlreadySent(id)) {
                                    busy = false
                                    paymentLinks.recheck(id)
                                } else {
                                    showAuthGate = true
                                }
                            }
                        },
                        enabled = !busy && activeWallet?.isViewOnly == false,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        if (busy) CircularProgressIndicator(color = MoneroOrange, modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                        Text(tr("Retry"))
                    }
                    // Words go with the record: remove only after a check that found no funding.
                    RemoveButton(
                        onClick = { confirmRemove = true },
                        enabled = live && current?.snapshot?.fundingSeen == false && !busy
                    )
                }
                LinkStatus.PENDING, LinkStatus.READY -> {
                    PrimaryButton(onClick = share, modifier = Modifier.fillMaxWidth()) {
                        Icon(Icons.Default.Share, contentDescription = null, modifier = Modifier.size(20.dp))
                        Text(tr("Share again"))
                    }
                    PrimaryButton(onClick = reclaim, enabled = canReclaim, modifier = Modifier.fillMaxWidth()) {
                        Text(if (status == LinkStatus.PENDING) tr("Reclaim when unlocked") else tr("Reclaim"))
                    }
                }
                LinkStatus.EXPIRED -> {
                    ProminentButton(onClick = reclaim, enabled = canReclaim, modifier = Modifier.fillMaxWidth()) {
                        Text(tr("Reclaim"))
                    }
                    PrimaryButton(onClick = share, modifier = Modifier.fillMaxWidth()) {
                        Icon(Icons.Default.Share, contentDescription = null, modifier = Modifier.size(20.dp))
                        Text(tr("Share again"))
                    }
                }
                LinkStatus.CLAIMED, LinkStatus.RECLAIMED -> RemoveButton(onClick = { confirmRemove = true })
            }
        }
        if (confirmRemove) {
            ConfirmRemove(
                message = tr("The link stops showing here."),
                onConfirm = {
                    confirmRemove = false
                    paymentLinks.removeSent(id)
                    onBack()
                },
                onCancel = { confirmRemove = false }
            )
        }
    }

    if (showAuthGate && link != null) {
        AuthGateDialog(
            walletViewModel = walletViewModel,
            title = tr("Confirm transaction"),
            subtitle = tr("Confirm this transaction"),
            onAuthenticated = {
                showAuthGate = false
                val walletId = activeWallet?.id
                if (walletId == null) {
                    busy = false
                } else {
                    paymentLinks.retryFrom(id, walletId)
                    walletViewModel.send(flow, link.envelopeAddress, link.amount + link.feeReserve, memo = "Payment link")
                }
            },
            onCancel = {
                showAuthGate = false
                busy = false
            }
        )
    }
}

@Composable
private fun ReceivedLinkDetail(paymentLinks: PaymentLinksViewModel, walletViewModel: WalletViewModel, id: String, onBack: () -> Unit) {
    val receivedList by paymentLinks.received.collectAsState()
    val link = receivedList.firstOrNull { it.id == id }
    var confirmRemove by remember { mutableStateOf(false) }

    LinksScaffold(tr("Payment Link"), onBack) {
        if (link == null) return@LinksScaffold
        val amount = "${walletViewModel.formatXmr(link.claimedAmount ?: link.amount)} XMR"
        Spacer(Modifier.height(16.dp))
        GlassCard(modifier = Modifier.fillMaxWidth(), cornerRadius = 16.dp) {
            Column {
                Column(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 20.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(amount, style = MaterialTheme.typography.headlineMedium)
                    ReceivedStatusChip(link)
                }
                DetailDivider()
                DetailRow(tr("Received"), formatRecordDate(link.receivedAtMillis))
                if (link.state == ReceivedState.PENDING) {
                    DetailDivider()
                    DetailRow(tr("Claim by"), formatLinkDate(link.expiry))
                }
                link.claimedTo?.let {
                    DetailDivider()
                    DetailRow(tr("Wallet"), it)
                }
                link.claimTxid?.let {
                    DetailDivider()
                    DetailRow(tr("Transaction ID"), truncateMiddle(it), mono = true)
                }
            }
        }
        Spacer(Modifier.height(28.dp))
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (link.state == ReceivedState.PENDING) {
                PrimaryButton(onClick = { paymentLinks.store.setPendingOpen(id) }, modifier = Modifier.fillMaxWidth()) {
                    Text(tr("Claim"))
                }
            }
            RemoveButton(onClick = { confirmRemove = true })
        }
        if (confirmRemove) {
            ConfirmRemove(
                message = if (link.state == ReceivedState.PENDING) {
                    tr("You can't claim it from here after you remove it. Open the link again to claim it.")
                } else {
                    tr("The link stops showing here.")
                },
                onConfirm = {
                    confirmRemove = false
                    paymentLinks.removeReceived(id)
                    onBack()
                },
                onCancel = { confirmRemove = false }
            )
        }
    }
}
