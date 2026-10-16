package one.monero.moneroone.paymentlinks

import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Link
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import one.monero.moneroone.core.locale.pluralTr
import one.monero.moneroone.core.locale.tr
import one.monero.moneroone.ui.components.CapsuleShape
import one.monero.moneroone.ui.theme.MoneroOrange
import one.monero.moneroone.ui.theme.MoneroTheme
import one.monero.moneroone.ui.theme.SuccessGreen
import one.monero.moneroone.ui.theme.TabularFigures

/**
 * The links of one wallet that still hold money (spec Round 3): the balance
 * card's lines. Each opens Activity's Links filter, where a sent link shows as
 * its "Sent by Link" row and a received one waits at the top.
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
