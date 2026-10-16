package one.monero.moneroone.paymentlinks

import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
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
import androidx.compose.material.icons.filled.UnfoldMore
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SheetValue
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleStartEffect
import one.monero.moneroone.core.locale.tr
import one.monero.moneroone.core.wallet.WalletViewModel
import one.monero.moneroone.core.wallet.addressesOf
import one.monero.moneroone.ui.components.CapsuleShape
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
    /** [amount]: the v1 link's amount or the balance a check saw; null for a v2 link not checked yet. */
    data class Checking(val amount: Long?) : ClaimUi

    /** [estimated]: [amount] is what a claim delivers (the balance less the sweep's fee estimate), shown with "~". */
    data class Waiting(val confirmations: Long, val minutes: Long, val amount: Long, val estimated: Boolean = false) : ClaimUi
    data class Ready(val amount: Long, val estimated: Boolean = false) : ClaimUi
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
                store.clearClaimWhenReady(id)
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
    val wallets by walletViewModel.wallets.collectAsState()
    val walletState by walletViewModel.walletState.collectAsState()
    val session by paymentLinks.session.collectAsState()
    val sweep by paymentLinks.sweep.collectAsState()

    // Own link: this phone sent it from the wallet that is open now.
    val own = sent != null && sent.walletId == activeWallet?.id
    val result = sweep?.takeIf { it.id == id }
    val claimedBefore = received?.state == ReceivedState.CLAIMED && result == null
    val reclaimedBefore = own && sent?.reclaimTxid != null && result == null
    val hasWords = sent != null || received?.link != null
    // Ours: the amount sent. Sent to us: what a claim delivers, else the v1 link's amount or the balance.
    val amount = sent?.amount ?: received?.claimAmount
    // Sent to us and checked: the balance less the claim sweep's fee estimate.
    val net = received?.netAmount?.takeIf { !own && sent == null && it > 0 }
    // A received v2 link carries no expiry (0).
    val expiry = (sent?.expiry ?: received?.expiry)?.takeIf { it > 0 }
    val receiveAddress = walletState.addressesOf(activeWallet?.id)?.shownAddress(walletState.receiveIndex)?.address
    // Where the sweep goes: the open wallet at its live receive address, or a closed wallet at its saved main address.
    val destinations = remember(wallets, activeWallet?.id, receiveAddress) {
        ClaimDestinations.eligible(wallets, activeWallet?.id, receiveAddress, ClaimDestinations::isSavedMainAddress)
    }
    var pickedWalletId by rememberSaveable(id) { mutableStateOf<String?>(null) }
    val destination = ClaimDestinations.chosen(destinations, pickedWalletId, reclaim = own, fundingWalletId = sent?.walletId)

    // The envelope is open only while the sheet is on screen and still has something to check: closing
    // the sheet or going to the background closes it, so the main wallet runs again. A finished claim
    // keeps its result on screen when the app comes back.
    val wantsSession = hasWords && !claimedBefore && !reclaimedBefore && result == null
    LifecycleStartEffect(id, wantsSession) {
        if (wantsSession) paymentLinks.openSession(id)
        onStopOrDispose { paymentLinks.closeSession() }
    }
    val dismiss by rememberUpdatedState(onDismiss)

    val current = session?.takeIf { it.id == id }
    val ui: ClaimUi = when {
        result != null -> when (val outcome = result.outcome) {
            is SweepOutcome.Sent -> ClaimUi.Done(outcome.amount, outcome.txid, result.walletName, result.kind == SweepKind.RECLAIM)
            SweepOutcome.AlreadyClaimed -> ClaimUi.AlreadyClaimed
            is SweepOutcome.Failed -> ClaimUi.Error(outcome.message)
        }
        claimedBefore -> ClaimUi.Done(received?.claimedAmount, received?.claimTxid, received?.claimedTo, reclaim = false)
        reclaimedBefore -> ClaimUi.Done(
            null,
            sent?.reclaimTxid?.takeIf { it.isNotEmpty() },
            wallets.firstOrNull { it.derivedWalletId != null && it.derivedWalletId == sent?.reclaimWallet }?.name,
            reclaim = true
        )
        !hasWords -> ClaimUi.Missing
        current == null || current.phase == EnvelopeSession.Phase.CHECKING -> ClaimUi.Checking(amount)
        current.phase == EnvelopeSession.Phase.ERROR -> ClaimUi.Error(current.error)
        current.phase == EnvelopeSession.Phase.SWEEPING -> ClaimUi.Claiming
        else -> current.snapshot?.let { snapshot ->
            when (val view = LinkStatusRules.claimView(snapshot)) {
                is ClaimView.Waiting -> ClaimUi.Waiting(view.confirmations, view.minutesLeft, net ?: snapshot.balance, estimated = net != null)
                is ClaimView.Ready -> ClaimUi.Ready(net ?: view.unlocked, estimated = net != null)
                ClaimView.AlreadyClaimed -> ClaimUi.AlreadyClaimed
                ClaimView.NoMoney -> ClaimUi.NoMoney
            }
        } ?: ClaimUi.Checking(amount)
    }

    // From a ready row at the top of Activity: claim once this sheet's own check finds the link ready.
    val claimWhenReady by paymentLinks.store.claimWhenReady.collectAsState()
    LaunchedEffect(ui, claimWhenReady, destination) {
        if (ui is ClaimUi.Ready && !own && claimWhenReady == id && destination?.address != null) {
            paymentLinks.store.clearClaimWhenReady(id)
            paymentLinks.sweepOpenEnvelope(SweepKind.CLAIM, destination)
        }
    }

    // The sweep's kit stays open until the node answers. The sheet state is keyed on its
    // confirmValueChange: a lambda that captured [ui] made a new, hidden state on each
    // recomposition, and the sheet slid in again (twice after a claim). One lambda, read through State.
    val claiming by rememberUpdatedState(ui == ClaimUi.Claiming)
    val keepWhileClaiming = remember { { value: SheetValue -> value != SheetValue.Hidden || !claiming } }
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true, confirmValueChange = keepWhileClaiming)
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
                    destination = destination,
                    destinations = destinations,
                    onPick = { pickedWalletId = it },
                    expiry = expiry,
                    canSweep = destination?.address != null,
                    formatXmr = walletViewModel::formatXmr,
                    onSweep = {
                        destination?.let { paymentLinks.sweepOpenEnvelope(if (own) SweepKind.RECLAIM else SweepKind.CLAIM, it) }
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
    destination: ClaimDestination?,
    destinations: List<ClaimDestination>,
    onPick: (String) -> Unit,
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
                state.amount?.let { EstimatedAmount(formatXmr(it)) }
                Secondary(tr("Checking the link…"))
            }
            is ClaimUi.Waiting -> {
                Disc(Icons.Default.HourglassTop, MoneroOrange)
                val shown = formatXmr(if (state.amount > 0) state.amount else 0)
                if (state.estimated) EstimatedAmount(shown) else Amount("$shown XMR")
                Text(
                    tr("%s/10 confirmations", state.confirmations),
                    style = MaterialTheme.typography.titleMedium,
                    color = MoneroOrange
                )
                Secondary(tr("Ready in about %s min", state.minutes))
                if (own) {
                    PrimaryButton(onClick = {}, enabled = false, modifier = Modifier.fillMaxWidth()) { Text(tr("Reclaim")) }
                }
            }
            is ClaimUi.Ready -> {
                Disc(Icons.Default.Link, MoneroOrange)
                if (state.estimated) EstimatedAmount(formatXmr(state.amount)) else Amount("${formatXmr(state.amount)} XMR")
                destination?.let { DestinationLine(own, it, destinations, onPick) }
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

/**
 * "Claim to Savings" / "Reclaim to Savings". With two or more wallets to choose from it opens
 * a menu of them (iOS: a Menu with a Picker, checkmark on the chosen one), the name and the
 * chevron in the brand color as a menu's current value; with one it is text.
 */
@Composable
private fun DestinationLine(own: Boolean, chosen: ClaimDestination, choices: List<ClaimDestination>, onPick: (String) -> Unit) {
    val text = if (own) tr("Reclaim to %s", chosen.name) else tr("Claim to %s", chosen.name)
    if (choices.size < 2) {
        Secondary(text)
        return
    }
    var open by remember { mutableStateOf(false) }
    // TalkBack: "Claim to, Savings", a drop-down list (iOS: the label "Claim to", the wallet name as its value).
    val spoken = (if (own) tr("Reclaim to") else tr("Claim to")) + ", " + chosen.name
    val line = buildAnnotatedString {
        append(text)
        val start = text.lastIndexOf(chosen.name)
        if (start >= 0) addStyle(SpanStyle(color = MoneroOrange), start, start + chosen.name.length)
    }
    Box {
        Row(
            modifier = Modifier
                .clip(CapsuleShape)
                .semantics(mergeDescendants = true) { contentDescription = spoken }
                .clickable(role = Role.DropdownList) { open = true }
                .heightIn(min = 48.dp)
                .padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Text(
                line,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier.clearAndSetSemantics {}
            )
            Icon(
                Icons.Default.UnfoldMore,
                contentDescription = null,
                tint = MoneroOrange,
                modifier = Modifier.size(14.dp)
            )
        }
        DropdownMenu(
            expanded = open,
            onDismissRequest = { open = false },
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh
        ) {
            choices.forEach { choice ->
                val selected = choice.walletId == chosen.walletId
                DropdownMenuItem(
                    text = { Text(choice.name) },
                    leadingIcon = {
                        if (selected) Icon(Icons.Default.Check, contentDescription = null) else Spacer(Modifier.size(24.dp))
                    },
                    onClick = {
                        open = false
                        onPick(choice.walletId)
                    },
                    modifier = Modifier.semantics { this.selected = selected }
                )
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
private fun Amount(text: String, spoken: String? = null) {
    Text(
        text,
        style = MaterialTheme.typography.headlineMedium,
        textAlign = TextAlign.Center,
        modifier = Modifier.semantics { if (spoken != null) contentDescription = spoken }
    )
}

/** An estimate: "~" on screen, "About" for TalkBack (it would read "tilde"). */
@Composable
private fun EstimatedAmount(xmr: String) {
    Amount(tr("~%s XMR", xmr), spoken = tr("About %s XMR", xmr))
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
