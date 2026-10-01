package one.monero.moneroone.ui.screens.wallet

import one.monero.moneroone.core.locale.tr
import one.monero.moneroone.data.model.Currency
import one.monero.moneroone.data.model.PriceDataPoint
import one.monero.moneroone.data.util.MoneyFormat
import one.monero.moneroone.ui.components.ChartMarker
import one.monero.moneroone.ui.screens.chart.ChartTimeAxis
import one.monero.moneroone.ui.screens.chart.TimeRange
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale
import java.util.TimeZone

class BalanceHistoryModelTest {

    private val xmr = 1_000_000_000_000L
    private val day = 86_400_000L

    /** 2026-09-01 00:00 UTC. */
    private val start = 1_788_220_800_000L

    // What getBestDateTimePattern gives for en-US.
    private val patterns = mapOf("yMMMdhmm" to "MMM d, y, h:mm a")

    private fun received(at: Long, amount: Long) = BalanceChange.of(
        id = "in$at", incoming = true, isPending = false, isFailed = false,
        amount = amount, fee = 0, timestampMs = at
    )!!

    private fun daily(count: Int, from: Long = start, price: Double = 100.0) =
        List(count) { PriceDataPoint(from + it * day, price) }

    private fun series(
        range: TimeRange,
        ledger: BalanceLedger,
        prices: List<PriceDataPoint>,
        allPrices: List<PriceDataPoint> = emptyList()
    ) =
        BalanceHistoryModel.makeSeries(
            BalanceHistoryModel.Inputs(
                range = range,
                prices = prices,
                allPrices = allPrices,
                rate = 2.0,
                currency = Currency.USD,
                ledger = ledger,
                locale = Locale.US,
                timeZone = TimeZone.getTimeZone("UTC"),
                use24Hour = false
            )
        ) { _, skeleton -> patterns.getValue(skeleton) }

    @Test
    fun `each raw sample is drawn at the display rate, with a marker per transaction`() {
        val ledger = BalanceLedger(xmr, listOf(received(start + 2 * day + 60_000, xmr)))
        val series = series(TimeRange.WEEK, ledger, daily(7))
        assertEquals(daily(7).map { it.timestamp }, series.chartPoints.map { it.timestamp })
        assertEquals(listOf(0.0, 0.0, 0.0, 200.0, 200.0, 200.0, 200.0), series.chartPoints.map { it.value })
        val marker = series.markers.single()
        assertEquals(start + 3 * day, marker.timestamp)
        assertEquals(ChartMarker.Style.RECEIVED, marker.style)
        // A lone transaction gives its own time, with the value in cents.
        assertEquals(
            tr("%s, portfolio %s", "Sep 3, 2026, 12:01 AM", MoneyFormat.format(200.0, Currency.USD, fractionDigits = 2)),
            marker.valueText
        )
        assertEquals("1 transaction", series.spokenCount)
        assertEquals(ChartTimeAxis.WEEK, series.tickAxis)
        assertTrue(series.isDrawable)
        assertTrue(series.domain.start < 0.0 && series.domain.endInclusive > 200.0)
    }

    @Test
    fun `All starts at the first holding and labels the span it draws`() {
        // Three years of samples; the wallet first held XMR 20 days ago.
        val prices = daily(3 * 365)
        val firstHolding = prices[prices.size - 20].timestamp - 1
        val series = series(TimeRange.ALL, BalanceLedger(xmr, listOf(received(firstHolding, xmr))), prices)
        assertEquals(21, series.points.size)
        assertEquals(0L, series.points.first().balance)
        assertEquals(ChartTimeAxis.MONTH, series.tickAxis)
        // Other ranges keep their whole span.
        assertEquals(prices.size, series(TimeRange.YEAR, BalanceLedger(xmr, listOf(received(firstHolding, xmr))), prices).points.size)
    }

    @Test
    fun `a wallet that never held anything has nothing to draw`() {
        val series = series(TimeRange.MONTH, BalanceLedger(0, emptyList()), daily(30))
        assertFalse(series.isDrawable)
        assertTrue(series.markers.isEmpty())
        assertNull(series.spokenCount)
    }

    @Test
    fun `one sample or none is not a line`() {
        assertFalse(series(TimeRange.DAY, BalanceLedger(xmr, emptyList()), daily(1)).isDrawable)
        val empty = series(TimeRange.DAY, BalanceLedger(xmr, emptyList()), emptyList())
        assertFalse(empty.isDrawable)
        assertEquals(ChartTimeAxis.DAY, empty.tickAxis)
    }
}
