package one.monero.moneroone.ui.screens.wallet

import one.monero.moneroone.data.model.PriceDataPoint
import one.monero.moneroone.data.util.XmrFormat
import one.monero.moneroone.ui.components.ChartMarker

/** A transaction as it moved the balance. Amounts are atomic units. */
data class BalanceChange(
    val id: String,
    val incoming: Boolean,
    val timestampMs: Long,
    /** What the transaction moved, fee not included. */
    val amount: Long,
    /** What the balance moved: the amount in, or the amount and fee out. */
    val delta: Long
) {
    companion object {
        /**
         * Null for a transaction the balance does not count: a failed one,
         * or an incoming one still pending unless [countsPendingIncoming].
         */
        fun of(
            id: String,
            incoming: Boolean,
            isPending: Boolean,
            isFailed: Boolean,
            amount: Long,
            fee: Long,
            timestampMs: Long,
            countsPendingIncoming: Boolean = false
        ): BalanceChange? {
            if (isFailed) return null
            if (incoming && isPending && !countsPendingIncoming) return null
            val delta = if (incoming) amount else -(amount + fee)
            return BalanceChange(id, incoming, timestampMs, amount, delta)
        }
    }
}

/**
 * The balance now and the transactions that led to it. Before
 * [knownSinceMs] the balance is unknown.
 */
class BalanceLedger(
    val balance: Long,
    changes: List<BalanceChange>,
    val knownSinceMs: Long? = null
) {
    /** Oldest first. */
    val changes: List<BalanceChange> = changes.sortedBy { it.timestampMs }
}

data class PortfolioPoint(
    val timestamp: Long,
    val value: Double,
    /** Atomic units held at this sample. */
    val balance: Long,
    /** Transactions this sample is the first to include, oldest first. */
    val changes: List<BalanceChange>
)

/**
 * Portfolio value over time from real data only: at every price sample, the
 * XMR held at that moment times that sample's price. Nothing is
 * interpolated; a transaction between two samples shows as the straight
 * segment between them. Matches iOS PortfolioHistory.
 */
object PortfolioHistory {

    /**
     * [prices] is the drawn price series in USD, oldest first, its last
     * point being now. [rate] converts it to the display currency. With
     * [startAtFirstHolding] ("All"), the years before the wallet first held
     * anything are cut, keeping the one empty sample the line rises from.
     */
    fun points(
        prices: List<PriceDataPoint>,
        rate: Double,
        ledger: BalanceLedger,
        startAtFirstHolding: Boolean = false
    ): List<PortfolioPoint> {
        // Before knownSince the balance is unknown; draw nothing rather than a guess.
        val samples = ledger.knownSinceMs?.let { since -> prices.filter { it.timestamp >= since } } ?: prices
        if (samples.isEmpty()) return emptyList()
        val last = samples.lastIndex

        // Walk back from the balance now. The last sample is now, so every
        // change after the sample before it shows there; each earlier sample
        // holds what was left before the changes after it.
        val held = LongArray(samples.size)
        val changes = Array(samples.size) { ArrayList<BalanceChange>() }
        var running = ledger.balance
        var next = ledger.changes.lastIndex
        held[last] = running
        for (i in last downTo 1) {
            val previous = samples[i - 1].timestamp
            while (next >= 0 && ledger.changes[next].timestampMs > previous) {
                running -= ledger.changes[next].delta
                changes[i].add(ledger.changes[next])
                next--
            }
            held[i - 1] = running
        }

        var start = 0
        if (startAtFirstHolding) {
            val first = held.indexOfFirst { it > 0 }
            if (first >= 0) start = maxOf(first - 1, 0)
        }
        return (start..last).map { i ->
            // A ledger that does not add up can dip below zero; no one holds less than nothing.
            val balance = maxOf(held[i], 0L)
            PortfolioPoint(
                timestamp = samples[i].timestamp,
                value = balance.toDouble() / ATOMIC_UNITS_PER_XMR * samples[i].price * rate,
                balance = balance,
                changes = changes[i].asReversed().toList()
            )
        }
    }

    /** "3 transactions" on the chart, for its TalkBack summary; null for none. */
    fun spokenCount(points: List<PortfolioPoint>): String? =
        when (val count = points.sumOf { it.changes.size }) {
            0 -> null
            1 -> "1 transaction"
            else -> "$count transactions"
        }

    /** "Received 1.2500 XMR", "Sent 0.5000 XMR" or "3 transactions"; null for none. */
    fun summary(changes: List<BalanceChange>): String? {
        val first = changes.firstOrNull() ?: return null
        if (changes.size != 1) return "${changes.size} transactions"
        return describe(first)
    }

    /**
     * Under the value while a sample is selected: the transactions that
     * landed on it, or else the XMR held then. iOS puts this and
     * [eventTime] on one line; Android keeps the time in the label above.
     */
    fun scrubDetail(point: PortfolioPoint): String =
        summary(point.changes) ?: "${XmrFormat.format(point.balance)} XMR"

    /** When a sample happened: a lone transaction gives its own time rather than the sample's. */
    fun eventTime(point: PortfolioPoint): Long =
        point.changes.singleOrNull()?.timestampMs ?: point.timestamp

    /**
     * A badge on every sample that includes a transaction, green when the
     * balance went up there. TalkBack reads the amounts, when, and the
     * portfolio value after.
     */
    fun markers(
        points: List<PortfolioPoint>,
        formatValue: (Double) -> String,
        formatWhen: (Long) -> String
    ): List<ChartMarker> = points.mapNotNull { point ->
        if (point.changes.isEmpty()) return@mapNotNull null
        val net = point.changes.sumOf { it.delta }
        ChartMarker(
            timestamp = point.timestamp,
            value = point.value,
            style = if (net >= 0) ChartMarker.Style.RECEIVED else ChartMarker.Style.SENT,
            label = spokenSummary(point.changes),
            valueText = "${formatWhen(eventTime(point))}, portfolio ${formatValue(point.value)}"
        )
    }

    /** Each amount when there are a few, the totals when there are many. */
    private fun spokenSummary(changes: List<BalanceChange>): String {
        if (changes.size <= 3) return changes.joinToString(", ") { describe(it) }
        val received = changes.filter { it.incoming }.sumOf { it.amount }
        val sent = changes.filter { !it.incoming }.sumOf { it.amount }
        val parts = mutableListOf("${changes.size} transactions")
        if (received > 0) parts.add("received ${XmrFormat.format(received)} XMR")
        if (sent > 0) parts.add("sent ${XmrFormat.format(sent)} XMR")
        return parts.joinToString(", ")
    }

    private fun describe(change: BalanceChange): String =
        if (change.incoming) "Received ${XmrFormat.format(change.amount)} XMR"
        else "Sent ${XmrFormat.format(change.amount)} XMR"

    private const val ATOMIC_UNITS_PER_XMR = 1e12
}
