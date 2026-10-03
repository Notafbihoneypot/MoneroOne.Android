package one.monero.moneroone.paymentlinks

import android.content.Context
import android.content.Intent
import android.view.MotionEvent
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInteropFilter
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import one.monero.moneroone.core.locale.tr
import one.monero.moneroone.ui.components.GlassCard
import one.monero.moneroone.ui.components.PrimaryButton
import one.monero.moneroone.ui.theme.ErrorRed
import one.monero.moneroone.ui.theme.MoneroOrange
import one.monero.moneroone.ui.theme.SuccessGreen
import one.monero.moneroone.ui.theme.WarningYellow
import java.text.DateFormat
import java.util.Date

/** The spec's bearer warning, shown before every link is funded. */
internal val bearerWarning: String
    get() = tr(
        "Anyone who has this link can claim the money. Send it to one person in a private chat. You can take the money back until someone claims it."
    )

internal fun formatLinkDate(epochSeconds: Long): String =
    DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(epochSeconds * 1000))

internal fun formatRecordDate(epochMillis: Long): String =
    DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(epochMillis))

/** Opens the system share sheet with the spec's share text. The link never goes to the clipboard. */
internal fun shareLink(context: Context, amountXmr: String, link: PaymentLink) {
    val send = Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(Intent.EXTRA_TEXT, tr("I sent you %s XMR. Tap to claim it in Monero One: %s", amountXmr, link.url()))
    }
    context.startActivity(Intent.createChooser(send, null).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
}

/** Send's recipient-area control that turns the flow into a payment link. Styled like Scan QR and Paste. */
@Composable
fun SendWithLinkButton(onClick: () -> Unit, modifier: Modifier = Modifier) {
    PrimaryButton(onClick = onClick, modifier = modifier.fillMaxWidth()) {
        Icon(Icons.Default.Link, contentDescription = null, modifier = Modifier.size(20.dp))
        Text(tr("Send with a link"))
    }
}

/** The recipient area in link mode: a read-only field reading "Anyone with the link". */
@Composable
fun LinkRecipientField(onClear: () -> Unit, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = 54.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .padding(start = 16.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(Icons.Default.Link, contentDescription = null, tint = MoneroOrange, modifier = Modifier.size(20.dp))
        Spacer(modifier = Modifier.width(12.dp))
        Text(
            tr("Anyone with the link"),
            style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier.weight(1f)
        )
        IconButton(onClick = onClear) {
            Icon(
                Icons.Default.Close,
                contentDescription = tr("Send to an address"),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(20.dp)
            )
        }
    }
}

/** The yellow caution banner (15% tint, triangle glyph, label-color text). */
@Composable
internal fun CautionBanner(text: String, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(WarningYellow.copy(alpha = 0.15f))
            .padding(16.dp)
            .semantics(mergeDescendants = true) {},
        verticalAlignment = Alignment.Top
    ) {
        Icon(Icons.Default.Warning, contentDescription = null, tint = WarningYellow, modifier = Modifier.size(20.dp))
        Spacer(modifier = Modifier.width(12.dp))
        Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface)
    }
}

@Composable
private fun ReviewRow(label: String, value: @Composable () -> Unit) {
    Row(modifier = Modifier.fillMaxWidth().semantics(mergeDescendants = true) {}, verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(modifier = Modifier.weight(1f))
        value()
    }
}

@Composable
private fun RowDivider() {
    Spacer(modifier = Modifier.height(12.dp))
    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
    Spacer(modifier = Modifier.height(12.dp))
}

/**
 * Send's review step in link mode. Builds the envelope (with its restore
 * check), estimates the fee reserve and the funding fee, and shows the rows
 * the spec lists. [onConfirm] gets the draft and the fee reserve.
 */
@Composable
fun PaymentLinkReview(
    paymentLinks: PaymentLinksViewModel,
    amount: Long,
    unlockedBalance: Long,
    existingDraft: EnvelopeDraft?,
    onDraft: (EnvelopeDraft) -> Unit,
    formatXmr: (Long) -> String,
    estimateFee: (address: String, amount: Long) -> Long,
    xmrPrice: Double?,
    currencySymbol: String,
    sendInProgress: Boolean,
    onConfirm: (draft: EnvelopeDraft, feeReserve: Long) -> Unit
) {
    var draft by remember { mutableStateOf(existingDraft?.takeIf { it.link.amount == amount }) }
    var reserve by remember { mutableLongStateOf(0L) }
    var networkFee by remember { mutableLongStateOf(0L) }
    var error by remember { mutableStateOf<String?>(null) }
    var attempt by remember { mutableIntStateOf(0) }

    LaunchedEffect(attempt) {
        error = null
        try {
            val ready = draft ?: paymentLinks.prepare(amount).also { draft = it; onDraft(it) }
            val (r, f) = withContext(Dispatchers.IO) {
                val r = estimateFee(ready.address, amount)
                r to (if (r > 0) estimateFee(ready.address, amount + r) else 0L)
            }
            if (r <= 0 || f <= 0) error = tr("Fee estimation failed")
            reserve = r
            networkFee = f
        } catch (e: EnvelopeMismatchException) {
            error = tr("Could not create the link. Nothing was sent.")
        } catch (e: Exception) {
            error = e.message ?: tr("Fee estimation failed")
        }
    }

    val feeReady = error == null && reserve > 0 && networkFee > 0 && draft != null
    val total = amount + reserve + networkFee
    val overBalance = feeReady && total > unlockedBalance

    fun fiat(atomic: Long): String? = xmrPrice?.let { "≈ $currencySymbol${"%.2f".format(atomic / 1_000_000_000_000.0 * it)}" }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 24.dp)
    ) {
        Column(modifier = Modifier.weight(1f).verticalScroll(rememberScrollState())) {
            Spacer(modifier = Modifier.height(16.dp))
            GlassCard(
                modifier = Modifier.fillMaxWidth(),
                cornerRadius = 16.dp,
                shadow = false,
                color = MaterialTheme.colorScheme.surfaceVariant
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.semantics(mergeDescendants = true) {}) {
                        Box(
                            modifier = Modifier.size(40.dp).clip(CircleShape).background(MoneroOrange.copy(alpha = 0.15f)),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(Icons.Default.Link, contentDescription = null, tint = MoneroOrange, modifier = Modifier.size(20.dp))
                        }
                        Spacer(modifier = Modifier.width(12.dp))
                        Column {
                            Text(tr("Recipient"), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text(tr("Anyone with the link"), style = MaterialTheme.typography.bodyMedium)
                        }
                    }

                    RowDivider()

                    Column(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp).semantics(mergeDescendants = true) {},
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text("${formatXmr(amount)} XMR", style = MaterialTheme.typography.headlineMedium)
                        fiat(amount)?.let {
                            Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }

                    RowDivider()

                    ReviewRow(tr("Network Fee")) {
                        when {
                            error != null -> Text(tr("Error"), style = MaterialTheme.typography.bodyMedium, color = ErrorRed)
                            !feeReady -> CircularProgressIndicator(color = MoneroOrange, modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                            else -> Text("${formatXmr(networkFee)} XMR", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
                        }
                    }
                    Spacer(modifier = Modifier.height(12.dp))
                    ReviewRow(tr("Claim fee reserve")) {
                        if (feeReady) {
                            Text("${formatXmr(reserve)} XMR", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
                        } else if (error == null) {
                            CircularProgressIndicator(color = MoneroOrange, modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                        }
                    }
                    Text(
                        tr("Covers the fee the recipient pays to claim it."),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 2.dp)
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    ReviewRow(tr("Expires")) {
                        draft?.let {
                            Text(formatLinkDate(it.link.expiry), style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
                        }
                    }

                    RowDivider()

                    Row(modifier = Modifier.fillMaxWidth().semantics(mergeDescendants = true) {}) {
                        Text(tr("Total"), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                        Spacer(modifier = Modifier.weight(1f))
                        if (feeReady) {
                            Column(horizontalAlignment = Alignment.End) {
                                Text(
                                    "${formatXmr(total)} XMR",
                                    style = MaterialTheme.typography.titleSmall,
                                    fontWeight = FontWeight.SemiBold,
                                    color = MoneroOrange
                                )
                                fiat(total)?.let {
                                    Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            }
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(12.dp))
            CautionBanner(bearerWarning)

            if (overBalance) {
                Spacer(modifier = Modifier.height(12.dp))
                Text(
                    tr("Amount plus network fee exceeds your available balance"),
                    style = MaterialTheme.typography.bodyMedium,
                    color = ErrorRed
                )
            }
            error?.let { message ->
                Spacer(modifier = Modifier.height(12.dp))
                Text(message, style = MaterialTheme.typography.bodyMedium, color = ErrorRed)
                Spacer(modifier = Modifier.height(8.dp))
                PrimaryButton(onClick = { attempt++ }, modifier = Modifier.fillMaxWidth()) {
                    Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(20.dp))
                    Text(tr("Retry"))
                }
            }
            Spacer(modifier = Modifier.height(16.dp))
        }

        @OptIn(ExperimentalComposeUiApi::class)
        PrimaryButton(
            onClick = { draft?.let { onConfirm(it, reserve) } },
            modifier = Modifier
                .fillMaxWidth()
                .pointerInteropFilter { event -> (event.flags and MotionEvent.FLAG_WINDOW_IS_OBSCURED) != 0 },
            enabled = feeReady && !overBalance && !sendInProgress
        ) {
            Icon(Icons.AutoMirrored.Filled.Send, contentDescription = null, modifier = Modifier.size(20.dp))
            Text(tr("Send"))
        }
        Spacer(modifier = Modifier.height(40.dp))
    }
}

/** "Link ready" after the funding broadcast: Share (system share sheet) and Done. */
@Composable
fun PaymentLinkReadyPhase(link: PaymentLink, amountXmr: String, onDone: () -> Unit) {
    val context = LocalContext.current
    var showCheck by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { delay(200); showCheck = true }
    val checkScale by animateFloatAsState(
        if (showCheck) 1f else 0.3f,
        spring(dampingRatio = 0.6f, stiffness = Spring.StiffnessMediumLow),
        label = "linkCheckScale"
    )
    val checkAlpha by animateFloatAsState(if (showCheck) 1f else 0f, tween(300), label = "linkCheckAlpha")

    Box(
        modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(horizontal = 32.dp)) {
            Box(
                modifier = Modifier
                    .size(120.dp)
                    .graphicsLayer { scaleX = checkScale; scaleY = checkScale; alpha = checkAlpha }
                    .clip(CircleShape)
                    .background(SuccessGreen.copy(alpha = 0.15f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(Icons.Default.Check, contentDescription = null, tint = SuccessGreen, modifier = Modifier.size(80.dp))
            }
            Spacer(modifier = Modifier.height(40.dp))
            Text(
                tr("Link ready"),
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold,
                color = SuccessGreen
            )
            Spacer(modifier = Modifier.height(12.dp))
            Text(
                "$amountXmr XMR",
                style = MaterialTheme.typography.bodyLarge,
                textAlign = TextAlign.Center
            )
            Spacer(modifier = Modifier.height(24.dp))
            CautionBanner(bearerWarning)
            Spacer(modifier = Modifier.height(32.dp))
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                PrimaryButton(onClick = { shareLink(context, amountXmr, link) }, modifier = Modifier.weight(1f)) {
                    Icon(Icons.Default.Share, contentDescription = null, modifier = Modifier.size(20.dp))
                    Text(tr("Share"))
                }
                PrimaryButton(onClick = onDone, modifier = Modifier.weight(1f)) {
                    Text(tr("Done"))
                }
            }
        }
    }
}

/** TalkBack: one spoken label for a status chip. */
internal fun Modifier.spoken(label: String): Modifier = clearAndSetSemantics { contentDescription = label }
