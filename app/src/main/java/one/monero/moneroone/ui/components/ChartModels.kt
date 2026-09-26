package one.monero.moneroone.ui.components

import androidx.compose.runtime.Immutable
import one.monero.moneroone.data.model.Currency
import one.monero.moneroone.data.util.MoneyFormat
import one.monero.moneroone.ui.screens.chart.ChartDateFormats
import one.monero.moneroone.ui.screens.chart.ChartTimeAxis
import java.util.Locale
import kotlin.math.abs

/** One real sample on a chart line. */
@Immutable
data class ChartPoint(val timestamp: Long, val value: Double)

/**
 * A badge on a real sample of the line that stands for an event, such as a
 * transaction on the portfolio chart. [timestamp] is the sample's time.
 */
@Immutable
data class ChartMarker(
    val timestamp: Long,
    val value: Double,
    val style: Style,
    /** "Received 2.0000 XMR". */
    val label: String,
    /** "Sep 20, 2026 at 3:05 PM, portfolio $1,234.56". */
    val valueText: String
) {
    /** Colors and arrows follow the activity rows: green in, orange out. */
    enum class Style { RECEIVED, SENT }
}

/** Time and currency labels. Null axes on a chart hide both. */
@Immutable
data class ChartAxes(
    val time: ChartTimeAxis,
    val formats: ChartDateFormats,
    val currency: Currency
)

/**
 * What TalkBack says for a chart. The chart is one node: the label names
 * it, the state sums the line up, and when the chart has markers the
 * custom actions step through them.
 */
@Immutable
data class ChartSpeech(
    /** "Portfolio", "Monero price". */
    val title: String,
    /** [ChartTimeAxis.spokenSpan]: "past week". */
    val span: String,
    val currency: Currency,
    /** Said after the numbers: "3 transactions". */
    val note: String? = null,
    /** What a marker is, for the custom actions: "Next transaction". */
    val markerName: String = "marker"
) {
    /** "Portfolio chart, past week". */
    val label: String get() = "$title chart, $span"

    /** A value on the line: "$1,234.56". */
    fun format(amount: Double): String = MoneyFormat.format(amount, currency, fractionDigits = 2)

    /**
     * "From $504.46 to $550.29, up 8.99%, 3 transactions". No change is
     * given from zero, as the headers give none.
     */
    fun summary(first: Double, last: Double): String {
        val parts = mutableListOf("From ${format(first)} to ${format(last)}")
        if (first > 0) parts.add(spokenChange((last - first) / first * 100))
        note?.let { parts.add(it) }
        return parts.joinToString(", ")
    }

    companion object {
        /** A percent change as the headers round it: "up 8.99%", "down 1.20%", "unchanged". */
        fun spokenChange(percent: Double): String {
            val size = String.format(Locale.US, "%.2f", abs(percent))
            if (size == "0.00") return "unchanged"
            return if (percent > 0) "up $size%" else "down $size%"
        }
    }
}
