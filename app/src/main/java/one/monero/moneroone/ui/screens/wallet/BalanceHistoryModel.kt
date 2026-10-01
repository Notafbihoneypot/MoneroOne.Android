package one.monero.moneroone.ui.screens.wallet

import android.text.format.DateFormat
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import one.monero.moneroone.data.model.Currency
import one.monero.moneroone.data.model.PriceDataPoint
import one.monero.moneroone.data.util.ChartMath
import one.monero.moneroone.data.util.MoneyFormat
import one.monero.moneroone.ui.components.ChartMarker
import one.monero.moneroone.ui.components.ChartPoint
import one.monero.moneroone.ui.screens.chart.ChartDateFormats
import one.monero.moneroone.ui.screens.chart.ChartTimeAxis
import one.monero.moneroone.ui.screens.chart.TimeRange
import java.util.Locale
import java.util.TimeZone

/**
 * The balance card's history, kept by the screen that shows the card so it
 * outlives History closing and reopening (iOS BalanceHistoryModel). Each
 * range's series is built off the main thread and kept; while a range
 * builds, the last series stays up, so the chart is never torn down.
 */
class BalanceHistoryModel : ViewModel() {

    /** One range's line, its markers and its axis, ready to draw. */
    @Immutable
    data class Series(
        val range: TimeRange,
        val currency: Currency,
        val points: List<PortfolioPoint>,
        val chartPoints: List<ChartPoint>,
        val markers: List<ChartMarker>,
        val domain: ClosedFloatingPointRange<Double>,
        /** Ticks for the span drawn; "All" starts at the first holding. */
        val tickAxis: ChartTimeAxis,
        /** "3 transactions", for TalkBack; null for none. */
        val spokenCount: String?,
        /** Two samples or more, one of them holding XMR. */
        val isDrawable: Boolean
    )

    /** Everything a series is built from. */
    data class Inputs(
        val range: TimeRange,
        /** USD samples, oldest first, the live price last. */
        val prices: List<PriceDataPoint>,
        /** All's samples when the range can show All's window, else empty. */
        val allPrices: List<PriceDataPoint>,
        /** USD to [currency]. */
        val rate: Double,
        val currency: Currency,
        val ledger: BalanceLedger,
        val locale: Locale,
        val timeZone: TimeZone,
        val use24Hour: Boolean
    )

    /** What the chart draws. It belongs to the previous range until the selected one is built. */
    var shown by mutableStateOf<Series?>(null)
        private set

    private val built = HashMap<TimeRange, Pair<Inputs, Series>>()
    private var wanted: TimeRange? = null
    private var buildJob: Job? = null
    private var session: Long? = null

    /**
     * Shows [range]: its kept series when no input changed, else a new one
     * built off the main thread. Until then the current series stays up.
     * Null [inputs] means the range's prices are still loading, or All's for
     * a range that can show All's window. A new [session] (a wallet switch)
     * drops everything kept, which belonged to the old wallet.
     */
    fun show(session: Long, range: TimeRange, inputs: Inputs?) {
        if (this.session != session) {
            this.session = session
            buildJob?.cancel()
            built.clear()
            shown = null
        }
        wanted = range
        if (inputs == null) return
        buildJob?.cancel()
        built[range]?.let { (kept, series) ->
            if (kept == inputs) {
                shown = series
                return
            }
        }
        buildJob = viewModelScope.launch {
            val series = withContext(Dispatchers.Default) { makeSeries(inputs) }
            built[range] = inputs to series
            if (wanted == range) shown = series
        }
    }

    companion object {
        /**
         * The line, its markers and its axis for one range. Pure, so it can
         * run off the main thread; it formats dates with formatters of its
         * own, as the shared ones are main-thread only.
         */
        fun makeSeries(
            inputs: Inputs,
            bestPattern: (Locale, String) -> String = { locale, skeleton -> DateFormat.getBestDateTimePattern(locale, skeleton) }
        ): Series {
            val points = PortfolioHistory.points(
                range = inputs.range,
                prices = inputs.prices,
                allPrices = inputs.allPrices,
                rate = inputs.rate,
                ledger = inputs.ledger
            )
            val dates = ChartDateFormats(inputs.locale, inputs.timeZone, inputs.use24Hour) { bestPattern(inputs.locale, it) }
            val markers = PortfolioHistory.markers(
                points,
                formatValue = { MoneyFormat.format(it, inputs.currency, fractionDigits = 2) },
                formatWhen = { dates.abbreviatedDateTime(it) }
            )
            val first = points.firstOrNull()
            val last = points.lastOrNull()
            return Series(
                range = inputs.range,
                currency = inputs.currency,
                points = points,
                chartPoints = points.map { ChartPoint(it.timestamp, it.value) },
                markers = markers,
                domain = ChartMath.chartYDomain(points.map { it.value }),
                tickAxis = if (first != null && last != null) ChartTimeAxis.fitting(last.timestamp - first.timestamp) else inputs.range.axis,
                spokenCount = PortfolioHistory.spokenCount(points),
                isDrawable = points.size >= 2 && points.any { it.balance > 0 }
            )
        }
    }
}
