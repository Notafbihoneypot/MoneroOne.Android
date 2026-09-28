package one.monero.moneroone.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.horizontalsystems.monerokit.model.TransactionInfo
import one.monero.moneroone.data.model.Currency
import one.monero.moneroone.data.model.HistoricalPrice
import one.monero.moneroone.data.model.PriceHistory
import one.monero.moneroone.data.util.MoneyFormat
import one.monero.moneroone.data.util.XmrFormat
import one.monero.moneroone.ui.theme.SuccessGreen

fun transactionFiat(tx: TransactionInfo, history: List<HistoricalPrice>, live: Double?, currency: Currency): String? =
    PriceHistory.priceAt(tx.timestamp, history, live, System.currentTimeMillis() / 1000)?.let {
        MoneyFormat.format(tx.amount / 1_000_000_000_000.0 * it, currency, fractionDigits = 2)
    }

/** Shared by the dashboard and history so their amount and spoken order agree. */
@Composable
fun TransactionAmounts(transaction: TransactionInfo, fiatMode: Boolean, fiatValue: String?) {
    val incoming = transaction.direction == TransactionInfo.Direction.Direction_In
    val sign = if (incoming) "+" else "−"
    val fiatFirst = fiatMode && fiatValue != null
    val status = when {
        transaction.isFailed -> TransactionStatus.Failed
        transaction.confirmations == 0L -> TransactionStatus.Pending
        transaction.confirmations < 10 -> TransactionStatus.Locked
        else -> TransactionStatus.Confirmed
    }
    Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(
            text = sign + if (fiatFirst) fiatValue else "${XmrFormat.format(transaction.amount)} XMR",
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
            color = if (incoming) SuccessGreen else MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            softWrap = false
        )
        TransactionStatusIndicator(status = status)
        val secondary = if (fiatFirst) "${XmrFormat.compact(transaction.amount)} XMR" else fiatValue
        if (secondary != null) Text(
            text = secondary,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            softWrap = false
        )
    }
}
