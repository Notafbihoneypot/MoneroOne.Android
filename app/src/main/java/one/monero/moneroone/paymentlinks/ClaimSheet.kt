package one.monero.moneroone.paymentlinks

import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.HourglassTop
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SheetValue
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import one.monero.moneroone.core.locale.tr
import one.monero.moneroone.core.wallet.WalletViewModel
import one.monero.moneroone.core.wallet.addressesOf
import one.monero.moneroone.ui.components.DismissTextButton
import one.monero.moneroone.ui.components.PrimaryButton
import one.monero.moneroone.ui.theme.ErrorRed
import one.monero.moneroone.ui.theme.MoneroOrange
import one.monero.moneroone.ui.theme.MoneroTheme
import one.monero.moneroone.ui.theme.MonoCaption
import one.monero.moneroone.ui.theme.SuccessGreen
import one.monero.moneroone.ui.theme.truncateMiddle

/** SwiftUI's default ease-in-out (0.35 s), the plain cross-fade iOS uses between states. */
private val CrossFade = tween<Float>(350, easing = CubicBezierEasing(0.42f, 0f, 0.58f, 1f))

/** What the claim sheet shows. */
private sealed interface ClaimUi {
    data class Checking(val amount: Long) : ClaimUi
    data class Waiting(val confirmations: Long, val minutes: Long, val amount: Long) : ClaimUi
    data class Ready(val unlocked: Long) : ClaimUi
    object Claiming : ClaimUi
    data class Done(val amount: Long?, val txid: String?, val wallet: String?, val reclaim: Boolean) : ClaimUi
    object AlreadyClaimed : ClaimUi
    object NoMoney : ClaimUi
    data class Error(val message: String?) : ClaimUi
    object Missing : ClaimUi
}

/**
 * Shows the claim sheet for the queued link once [canShow] (unlocked, a
 * wallet exists, no setup screen up), and the problem alert for a link that
 * cannot open.
 */
@Composable
fun ClaimSheetHost(paymentLinks: PaymentLinksViewModel, walletViewModel: WalletViewModel, canShow: Boolean) {
    val store = paymentLinks.store
    val pendingId by store.pendingOpen.collectAsState()
    val problem by store.intakeProblem.collectAsState()

    if (canShow) {
        problem?.let { parsed ->
            AlertDialog(
                onDismissRequest = store::clearIntakeProblem,
                title = { Text(tr("Can't Open Payment Link")) },
                text = {
                    Text(
                        if (parsed == PaymentLinkParse.NeedsUpdate) tr("Update Monero One to open this link.")
                        else tr("This link is not complete.")
                    )
                },
                confirmButton = { TextButton(onClick = store::clearIntakeProblem) { Text(tr("OK")) } }
            )
        }
    }
    val id = pendingId
    if (canShow && id != null) {
        ClaimSheet(
            id = id,
            paymentLinks = paymentLinks,
            walletViewModel = walletViewModel,
            onDismiss = {
                store.setPendingOpen(null)
                paymentLinks.closeSession()
                paymentLinks.clearSweep()
            }
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ClaimSheet(
    id: String,
    paymentLinks: PaymentLinksViewModel,
    walletViewModel: WalletViewModel,
    onDismiss: () -> Unit
) {
    val sentList by paymentLinks.sent.collectAsState()
    val receivedList by paymentLinks.received.collectAsState()
    val sent = sentList.firstOrNull { it.id == id }
    val received = receivedList.firstOrNull { it.id == id }
    val activeWallet by walletViewModel.activeWallet.collectAsState()
    val walletState by walletViewModel.walletState.collectAsState()
    val session by paymentLinks.session.collectAsState()
    val sweep by paymentLinks.sweep.collectAsState()

    // Own link: this phone sent it from the wallet that is open now.
    val own = sent != null && sent.walletId == activeWallet?.id
    val result = sweep?.takeIf { it.id == id }
    val claimedBefore = received?.state == ReceivedState.CLAIMED && result == null
    val reclaimedBefore = own && sent?.reclaimTxid != null && result == null
    val hasWords = sent != null || received?.link != null
    val amount = sent?.amount ?: received?.amount ?: 0L
    val expiry = sent?.expiry ?: received?.expiry
    val walletName = activeWallet?.name.orEmpty()
    val receiveAddress = walletState.addressesOf(activeWallet?.id)?.shownAddress(walletState.receiveIndex)?.address

    LaunchedEffect(id, hasWords, claimedBefore, reclaimedBefore) {
        if (hasWords && !claimedBefore && !reclaimedBefore) paymentLinks.openSession(id)
    }
    val dismiss by rememberUpdatedState(onDismiss)
    DisposableEffect(id) { onDispose { paymentLinks.closeSession() } }

    val current = session?.takeIf { it.id == id }
    val ui: ClaimUi = when {
        result != null -> when (val outcome = result.outcome) {
            is SweepOutcome.Sent -> ClaimUi.Done(outcome.amount, outcome.txid, walletName, result.kind == SweepKind.RECLAIM)
            SweepOutcome.AlreadyClaimed -> ClaimUi.AlreadyClaimed
            is SweepOutcome.Failed -> ClaimUi.Error(outcome.message)
        }
        claimedBefore -> ClaimUi.Done(received?.claimedAmount, received?.claimTxid, received?.claimedTo, reclaim = false)
        reclaimedBefore -> ClaimUi.Done(null, sent?.reclaimTxid?.takeIf { it.isNotEmpty() }, null, reclaim = true)
        !hasWords -> ClaimUi.Missing
        current == null || current.phase == EnvelopeSession.Phase.CHECKING -> ClaimUi.Checking(amount)
        current.phase == EnvelopeSession.Phase.ERROR -> ClaimUi.Error(current.error)
        current.phase == EnvelopeSession.Phase.SWEEPING -> ClaimUi.Claiming
        else -> current.snapshot?.let { snapshot ->
            when (val view = LinkStatusRules.claimView(snapshot)) {
                is ClaimView.Waiting -> ClaimUi.Waiting(view.confirmations, view.minutesLeft, snapshot.balance)
                is ClaimView.Ready -> ClaimUi.Ready(view.unlocked)
                ClaimView.AlreadyClaimed -> ClaimUi.AlreadyClaimed
                ClaimView.NoMoney -> ClaimUi.NoMoney
            }
        } ?: ClaimUi.Checking(amount)
    }

    val sheetState = rememberModalBottomSheetState(
        skipPartiallyExpanded = true,
        // The sweep's kit stays open until the node answers.
        confirmValueChange = { it != SheetValue.Hidden || ui != ClaimUi.Claiming }
    )
    ModalBottomSheet(
        onDismissRequest = { if (ui != ClaimUi.Claiming) dismiss() },
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surfaceContainer,
        contentColor = MaterialTheme.colorScheme.onSurface
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp).padding(bottom = 32.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Box(Modifier.fillMaxWidth()) {
                if (ui != ClaimUi.Claiming) {
                    DismissTextButton(onClick = dismiss, modifier = Modifier.align(Alignment.CenterStart)) { Text(tr("Close")) }
                }
                Text(
                    if (own) tr("This is your own link") else tr("Payment Link"),
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.align(Alignment.Center)
                )
            }
            Spacer(Modifier.height(16.dp))
            Crossfade(targetState = ui, animationSpec = CrossFade, label = "claimState") { state ->
                ClaimBody(
                    state = state,
                    own = own,
                    walletName = walletName,
                    expiry = expiry,
                    canSweep = receiveAddress != null,
                    formatXmr = walletViewModel::formatXmr,
                    onSweep = {
                        receiveAddress?.let {
                            paymentLinks.sweepOpenEnvelope(if (own) SweepKind.RECLAIM else SweepKind.CLAIM, it, walletName)
                        }
                    },
                    onRetry = {
                        paymentLinks.clearSweep()
                        paymentLinks.recheck(id)
                    },
                    onDone = dismiss
                )
            }
        }
    }
}

@Composable
private fun ClaimBody(
    state: ClaimUi,
    own: Boolean,
    walletName: String,
    expiry: Long?,
    canSweep: Boolean,
    formatXmr: (Long) -> String,
    onSweep: () -> Unit,
    onRetry: () -> Unit,
    onDone: () -> Unit
) {
    Column(
        modifier = Modifier.fillMaxWidth().heightIn(min = 320.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        when (state) {
            is ClaimUi.Checking -> {
                Spinner()
                Amount(tr("about %s XMR", formatXmr(state.amount)))
                Secondary(tr("Checking the link…"))
            }
            is ClaimUi.Waiting -> {
                Disc(Icons.Default.HourglassTop, MoneroOrange)
                Amount("${formatXmr(if (state.amount > 0) state.amount else 0)} XMR")
                Text(
                    tr("%d/10 confirmations", state.confirmations),
                    style = MaterialTheme.typography.titleMedium,
                    color = MoneroOrange
                )
                Secondary(tr("Ready in about %d min", state.minutes))
                if (own) {
                    PrimaryButton(onClick = {}, enabled = false, modifier = Modifier.fillMaxWidth()) { Text(tr("Reclaim")) }
                }
            }
            is ClaimUi.Ready -> {
                Disc(Icons.Default.Link, MoneroOrange)
                Amount("${formatXmr(state.unlocked)} XMR")
                Secondary(tr("To %s", walletName))
                expiry?.let { Secondary(tr("Claim by %s", formatLinkDate(it))) }
                Spacer(Modifier.height(8.dp))
                PrimaryButton(onClick = onSweep, enabled = canSweep, modifier = Modifier.fillMaxWidth()) {
                    Text(if (own) tr("Reclaim") else tr("Claim"))
                }
            }
            ClaimUi.Claiming -> {
                Spinner()
                Secondary(if (own) tr("Reclaiming…") else tr("Claiming…"))
            }
            is ClaimUi.Done -> {
                Disc(Icons.Default.Check, SuccessGreen)
                Text(
                    if (state.reclaim) tr("Reclaimed") else tr("Claimed"),
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold,
                    color = SuccessGreen
                )
                state.amount?.let { Amount("${formatXmr(it)} XMR") }
                state.txid?.takeIf { it.isNotEmpty() }?.let { txid ->
                    Text(truncateMiddle(txid), style = MonoCaption, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                state.wallet?.takeIf { it.isNotEmpty() }?.let { Secondary(tr("On its way to %s", it)) }
                Spacer(Modifier.height(8.dp))
                PrimaryButton(onClick = onDone, modifier = Modifier.fillMaxWidth()) { Text(tr("Done")) }
            }
            ClaimUi.AlreadyClaimed -> {
                Disc(Icons.Default.Check, MoneroTheme.colors.gray)
                Message(tr("This link was already claimed."))
                PrimaryButton(onClick = onDone, modifier = Modifier.fillMaxWidth()) { Text(tr("Done")) }
            }
            ClaimUi.NoMoney -> {
                Disc(Icons.Default.HourglassTop, MoneroTheme.colors.gray)
                Message(tr("No money in this link yet."))
                PrimaryButton(onClick = onRetry, modifier = Modifier.fillMaxWidth()) {
                    Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(20.dp))
                    Text(tr("Check Again"))
                }
            }
            is ClaimUi.Error -> {
                Disc(Icons.Default.Error, ErrorRed)
                Message(tr("Could not check this link."))
                state.message?.takeIf { it.isNotBlank() }?.let { Secondary(it) }
                PrimaryButton(onClick = onRetry, modifier = Modifier.fillMaxWidth()) {
                    Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(20.dp))
                    Text(tr("Retry"))
                }
            }
            ClaimUi.Missing -> {
                Disc(Icons.Default.Error, MoneroTheme.colors.gray)
                Message(tr("This link is not complete."))
                PrimaryButton(onClick = onDone, modifier = Modifier.fillMaxWidth()) { Text(tr("Done")) }
            }
        }
    }
}

@Composable
private fun Spinner() {
    Box(Modifier.size(64.dp), contentAlignment = Alignment.Center) {
        CircularProgressIndicator(color = MoneroOrange, modifier = Modifier.size(40.dp), strokeWidth = 3.dp)
    }
}

@Composable
private fun Disc(icon: ImageVector, hue: Color) {
    Box(
        modifier = Modifier.size(64.dp).clip(CircleShape).background(hue.copy(alpha = 0.15f)),
        contentAlignment = Alignment.Center
    ) {
        Icon(icon, contentDescription = null, tint = hue, modifier = Modifier.size(32.dp))
    }
}

@Composable
private fun Amount(text: String) {
    Text(text, style = MaterialTheme.typography.headlineMedium, textAlign = TextAlign.Center, modifier = Modifier.semantics {})
}

@Composable
private fun Message(text: String) {
    Text(text, style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center)
}

@Composable
private fun Secondary(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = TextAlign.Center
    )
}
