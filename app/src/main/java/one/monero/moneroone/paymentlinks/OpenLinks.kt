package one.monero.moneroone.paymentlinks

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import one.monero.moneroone.core.locale.pluralTr
import one.monero.moneroone.core.locale.tr
import one.monero.moneroone.core.wallet.WalletManager
import one.monero.moneroone.ui.components.CapsuleShape
import one.monero.moneroone.ui.components.GlassCard
import one.monero.moneroone.ui.theme.MoneroOrange
import one.monero.moneroone.ui.theme.MoneroTheme
import one.monero.moneroone.ui.theme.SuccessGreen
import one.monero.moneroone.ui.theme.TabularFigures

/**
 * The links of one wallet that still hold money (spec Round 3): the balance
 * card's lines and the Open Links group at the top of Activity's Links filter.
 */
class OpenLinks(
    /** Sent links of the wallet that someone can still claim: waiting, ready or expired. Newest first. */
    val sent: List<SentLink>,
    /** How many of [sent] expired. */
    val expired: Int,
    /** Received links waiting to be claimed, newest first. */
    val received: List<ReceivedLink>
) {
    /** What the sent links hold: the amount each link's detail shows. */
    val sentAmount: Long get() = sent.sumOf { it.amount }

    /** What claiming the received links delivers as far as this device knows ([ReceivedLink.claimAmount]); null when no amount is known. */
    val receivedAmount: Long?
        get() = received.mapNotNull { it.claimAmount }.takeIf { it.isNotEmpty() }?.sum()

    val isEmpty: Boolean get() = sent.isEmpty() && received.isEmpty()
}

/** One balance-card line: the amount (label color), the rest (secondary) and what TalkBack reads. */
data class OpenLinkLine(val amount: String, val rest: String, val spoken: String)

object OpenLinkRules {
    /** A sent link someone can still claim: neither claimed nor reclaimed, and funded (waiting, ready or expired). */
    fun isOpen(link: SentLink, nowSeconds: Long): Boolean = when (LinkStatusRules.status(link, nowSeconds)) {
        LinkStatus.PENDING, LinkStatus.READY, LinkStatus.EXPIRED -> true
        LinkStatus.NOT_FUNDED, LinkStatus.CLAIMED, LinkStatus.RECLAIMED -> false
    }

    /** The open links of [walletId]: its sent links, and every received link this device can still claim. */
    fun openLinks(sent: List<SentLink>, received: List<ReceivedLink>, walletId: String?, nowSeconds: Long): OpenLinks {
        val open = sent.filter { it.walletId == walletId && isOpen(it, nowSeconds) }.sortedByDescending { it.createdAtMillis }
        val expired = open.count { LinkStatusRules.status(it, nowSeconds) == LinkStatus.EXPIRED }
        return OpenLinks(open, expired, PaymentLinkActivityRules.pendingRecords(received))
    }

    /** "0.15 XMR" + "in 2 open links · 1 expired"; TalkBack reads "0.15 XMR in 2 open links, 1 expired". */
    fun sentLine(amountXmr: String, count: Int, expired: Int): OpenLinkLine {
        val amount = "$amountXmr XMR"
        val parts = listOfNotNull(pluralTr("in %s open links", count), if (expired > 0) tr("%s expired", expired) else null)
        return OpenLinkLine(amount, parts.joinToString(" · "), "$amount " + parts.joinToString(", "))
    }

    /** "0.03 XMR" + "to claim"; with no amount known, "1 payment link" + "to claim". */
    fun claimLine(amountXmr: String?, count: Int): OpenLinkLine {
        val amount = amountXmr?.let { "$it XMR" } ?: pluralTr("%s payment links", count)
        val rest = tr("to claim")
        return OpenLinkLine(amount, rest, "$amount $rest")
    }

    /** The Open Links group's order: links to claim, then expired links to reclaim, then the newest. */
    fun sentInGroupOrder(open: OpenLinks, nowSeconds: Long): List<SentLink> =
        open.sent.sortedWith(
            compareByDescending<SentLink> { LinkStatusRules.status(it, nowSeconds) == LinkStatus.EXPIRED }
                .thenByDescending { it.createdAtMillis }
        )
}

// ---------------------------------------------------------------- balance card

/**
 * The balance card's open-link lines, under a separator: "0.15 XMR in 2 open
 * links · 1 expired ›" for sent links, and "0.03 XMR to claim  Claim" for
 * received ones. A line opens Activity's Links filter; Claim claims the one
 * received link, or opens the Links filter when there are more.
 */
@Composable
fun OpenLinksLines(
    open: OpenLinks,
    formatXmr: (Long) -> String,
    enabled: Boolean,
    onOpenLinks: () -> Unit,
    onClaim: () -> Unit
) {
    val hint = tr("Shows your payment links")
    Column(modifier = Modifier.fillMaxWidth().padding(top = 16.dp).testTag("wallet.openLinks")) {
        HorizontalDivider(color = MoneroTheme.colors.separator)
        if (open.sent.isNotEmpty()) {
            val line = OpenLinkRules.sentLine(formatXmr(open.sentAmount), open.sent.size, open.expired)
            BalanceLine(
                line = line,
                glyphTint = MaterialTheme.colorScheme.onSurfaceVariant,
                enabled = enabled,
                onClick = onOpenLinks,
                // One button: the line, with its own words for TalkBack.
                modifier = Modifier.clearAndSetSemantics {
                    contentDescription = line.spoken
                    role = Role.Button
                    onClick(label = hint) { if (enabled) onOpenLinks(); enabled }
                }
            ) {
                Icon(
                    Icons.Default.ChevronRight,
                    contentDescription = null,
                    tint = MoneroTheme.colors.labelTertiary,
                    modifier = Modifier.size(16.dp)
                )
            }
        }
        if (open.received.isNotEmpty()) {
            val line = OpenLinkRules.claimLine(open.receivedAmount?.let(formatXmr), open.received.size)
            BalanceLine(
                line = line,
                glyphTint = SuccessGreen,
                enabled = enabled,
                onClick = onOpenLinks,
                onClickLabel = hint
            ) {
                // Claim is its own button.
                Text(
                    text = tr("Claim"),
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MoneroOrange,
                    maxLines = 1,
                    modifier = Modifier
                        .minimumInteractiveComponentSize()
                        .clip(CapsuleShape)
                        .clickable(enabled = enabled, role = Role.Button, onClick = onClaim)
                        .padding(horizontal = 4.dp)
                        .testTag("wallet.openLinks.claim")
                )
            }
        }
    }
}

@Composable
private fun BalanceLine(
    line: OpenLinkLine,
    glyphTint: Color,
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    onClickLabel: String? = null,
    trailing: @Composable () -> Unit
) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 44.dp)
            .graphicsLayer { alpha = if (pressed) 0.6f else 1f }
            .clickable(
                interactionSource = interaction,
                indication = null,
                enabled = enabled,
                onClickLabel = onClickLabel,
                role = Role.Button,
                onClick = onClick
            )
            .then(modifier),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Icon(Icons.Default.Link, contentDescription = null, tint = glyphTint, modifier = Modifier.size(16.dp))
        // The amount never truncates: at large text the line wraps.
        Text(
            text = buildAnnotatedString {
                withStyle(SpanStyle(color = MaterialTheme.colorScheme.onSurface, fontWeight = FontWeight.Medium, fontFeatureSettings = TabularFigures)) {
                    append(line.amount)
                }
                append(" ")
                append(line.rest)
            },
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f).padding(vertical = 4.dp)
        )
        trailing()
    }
}

// ---------------------------------------------------------------- Links view

/** The Links filter's "Open Links" heading. */
@Composable
fun OpenLinksHeader() {
    Text(
        text = tr("Open Links"),
        style = MaterialTheme.typography.titleMedium,
        fontWeight = FontWeight.SemiBold,
        modifier = Modifier.padding(top = 4.dp).semantics { heading() }
    )
}

/** A received link waiting to be claimed: its row opens the claim sheet, Claim claims it once ready. */
@Composable
fun OpenReceivedLinkRow(link: ReceivedLink, walletName: String?, formatXmr: (Long) -> String) {
    val store = PaymentLinkStore.get(LocalContext.current)
    val daemonHeight = rememberDaemonHeight()
    val state = PaymentLinkActivityRules.pendingState(link, daemonHeight, System.currentTimeMillis())
    val ready = state == PendingLinkState.Ready
    OpenLinkRow(
        id = link.id,
        incoming = true,
        amount = link.claimAmount?.let { "+${formatXmr(it)}" },
        amountSpoken = link.claimAmount?.let { tr("plus %s", "${formatXmr(it)} XMR") },
        status = PaymentLinkActivityRules.pendingLabel(state),
        statusColor = if (ready) SuccessGreen else MaterialTheme.colorScheme.onSurfaceVariant,
        hint = tr("Opens the link to claim it"),
        onOpen = { store.openClaim(link.id) },
        chip = tr("Claim"),
        chipIcon = null,
        chipHint = if (ready && !walletName.isNullOrEmpty()) tr("Claims the money to %s", walletName) else tr("Opens the link to claim it"),
        onChip = { store.openClaim(link.id, autoClaim = ready) }
    )
}

/** A sent link still open: Share while it waits, Reclaim once expired. Its row opens the link's detail. */
@Composable
fun OpenSentLinkRow(link: SentLink, nowSeconds: Long, formatXmr: (Long) -> String, onOpen: () -> Unit) {
    val context = LocalContext.current
    val transactions by WalletManager.transactionsFlow.collectAsState()
    val daemonHeight = rememberDaemonHeight()
    val status = sentView(link, transactions, daemonHeight, nowSeconds).status
    val expired = status == LinkStatus.EXPIRED
    OpenLinkRow(
        id = link.id,
        incoming = false,
        amount = formatXmr(link.amount),
        amountSpoken = "${formatXmr(link.amount)} XMR",
        status = PaymentLinkActivityRules.fundingStatusLine(status),
        statusColor = MaterialTheme.colorScheme.onSurfaceVariant,
        hint = tr("Opens the link"),
        onOpen = onOpen,
        chip = if (expired) tr("Reclaim") else tr("Share"),
        chipIcon = if (expired) Icons.AutoMirrored.Filled.Undo else Icons.Default.Share,
        // Reclaim needs the link's envelope open: its detail has the button.
        chipHint = if (expired) tr("Opens the link to reclaim the money") else tr("Shares the link again"),
        onChip = if (expired) onOpen else ({ shareLink(context, formatXmr(link.amount), link.link) })
    )
}

@Composable
private fun OpenLinkRow(
    id: String,
    incoming: Boolean,
    amount: String?,
    amountSpoken: String?,
    status: String,
    statusColor: Color,
    hint: String,
    onOpen: () -> Unit,
    chip: String,
    chipIcon: ImageVector?,
    chipHint: String,
    onChip: () -> Unit
) {
    val hue = if (incoming) SuccessGreen else MoneroOrange
    val spoken = listOfNotNull(tr("Payment link"), amountSpoken, status).joinToString(", ")
    GlassCard(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("activity.openLink.$id")
            .semantics { onClick(label = hint) { onOpen(); true } },
        onClick = onOpen
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(
                modifier = Modifier.weight(1f).clearAndSetSemantics { contentDescription = spoken },
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier.size(40.dp).clip(CircleShape).background(hue.copy(alpha = 0.15f)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(Icons.Default.Link, contentDescription = null, tint = hue, modifier = Modifier.size(20.dp))
                }
                Spacer(Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(tr("Payment link"), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Medium)
                    Text(status, style = MaterialTheme.typography.bodySmall, color = statusColor, maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
            }
            Spacer(Modifier.width(8.dp))
            Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(4.dp)) {
                if (amount != null) {
                    Text(
                        amount,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                        color = if (incoming) SuccessGreen else MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        modifier = Modifier.clearAndSetSemantics {}
                    )
                }
                // The chip is its own button (brand tint, caption semibold).
                Row(
                    modifier = Modifier
                        .clip(CapsuleShape)
                        .background(MoneroOrange.copy(alpha = 0.15f))
                        .clickable(role = Role.Button, onClickLabel = chipHint, onClick = onChip)
                        .padding(horizontal = 12.dp, vertical = 6.dp)
                        .testTag("activity.openLink.$id.chip"),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    chipIcon?.let { Icon(it, contentDescription = null, tint = MoneroOrange, modifier = Modifier.size(12.dp)) }
                    Text(chip, style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.SemiBold, color = MoneroOrange, maxLines = 1)
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
