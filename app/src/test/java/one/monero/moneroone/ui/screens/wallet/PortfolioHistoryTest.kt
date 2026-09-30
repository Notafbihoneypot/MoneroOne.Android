package one.monero.moneroone.ui.screens.wallet

import one.monero.moneroone.data.model.PriceDataPoint
import one.monero.moneroone.ui.components.ChartMarker
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PortfolioHistoryTest {

    private val xmr = 1_000_000_000_000L

    private fun prices(vararg times: Long, price: Double = 100.0) = times.map { PriceDataPoint(it, price) }

    private fun received(at: Long, amount: Long) = BalanceChange.of(
        id = "in$at", incoming = true, isPending = false, isFailed = false,
        amount = amount, fee = 0, timestampMs = at
    )!!

    private fun sent(at: Long, amount: Long, fee: Long = 0) = BalanceChange.of(
        id = "out$at", incoming = false, isPending = false, isFailed = false,
        amount = amount, fee = fee, timestampMs = at
    )!!

    @Test
    fun `each sample holds what the wallet held then`() {
        // Given out of order; the ledger sorts them.
        val ledger = BalanceLedger(3 * xmr, listOf(received(3_500, xmr), received(1_500, 2 * xmr)))
        val points = PortfolioHistory.points(prices(1_000, 2_000, 3_000, 4_000), rate = 1.0, ledger = ledger)
        assertEquals(listOf(0L, 2 * xmr, 2 * xmr, 3 * xmr), points.map { it.balance })
        assertEquals(listOf(0.0, 200.0, 200.0, 300.0), points.map { it.value })
        assertEquals(
            listOf(emptyList(), listOf(1_500L), emptyList(), listOf(3_500L)),
            points.map { point -> point.changes.map { it.timestampMs } }
        )
    }

    @Test
    fun `values use the rate, and fees count against the balance`() {
        // 1 XMR now; 0.5 XMR and a 0.01 XMR fee went out between the samples.
        val ledger = BalanceLedger(xmr, listOf(sent(1_500, xmr / 2, fee = xmr / 100)))
        val points = PortfolioHistory.points(prices(1_000, 2_000, price = 200.0), rate = 0.5, ledger = ledger)
        assertEquals(listOf(xmr + xmr / 2 + xmr / 100, xmr), points.map { it.balance })
        assertEquals(151.0, points[0].value, 1e-9)
        assertEquals(100.0, points[1].value, 1e-9)
    }

    @Test
    fun `pending incoming and failed transactions do not count`() {
        assertNull(BalanceChange.of("a", incoming = true, isPending = true, isFailed = false, amount = xmr, fee = 0, timestampMs = 1))
        assertNull(BalanceChange.of("b", incoming = false, isPending = false, isFailed = true, amount = xmr, fee = 0, timestampMs = 1))
        val pendingOut = BalanceChange.of("c", incoming = false, isPending = true, isFailed = false, amount = xmr, fee = 10, timestampMs = 1)
        assertEquals(-(xmr + 10), pendingOut!!.delta)
        val counted = BalanceChange.of(
            "d", incoming = true, isPending = true, isFailed = false, amount = xmr, fee = 0, timestampMs = 1,
            countsPendingIncoming = true
        )
        assertEquals(xmr, counted!!.delta)
    }

    @Test
    fun `a transaction newer than the last sample shows on it`() {
        val ledger = BalanceLedger(2 * xmr, listOf(received(9_000, xmr)))
        val points = PortfolioHistory.points(prices(1_000, 2_000), rate = 1.0, ledger = ledger)
        assertEquals(listOf(xmr, 2 * xmr), points.map { it.balance })
        assertEquals(listOf(9_000L), points.last().changes.map { it.timestampMs })
    }

    @Test
    fun `a transaction older than the first sample is already in its balance`() {
        val ledger = BalanceLedger(xmr, listOf(received(500, xmr)))
        val points = PortfolioHistory.points(prices(1_000, 2_000), rate = 1.0, ledger = ledger)
        assertEquals(listOf(xmr, xmr), points.map { it.balance })
        assertTrue(points.all { it.changes.isEmpty() })
    }

    @Test
    fun `a ledger that does not add up never goes below zero`() {
        // 1 XMR now, yet 2 XMR came in during the range.
        val ledger = BalanceLedger(xmr, listOf(received(1_500, 2 * xmr)))
        val points = PortfolioHistory.points(prices(1_000, 2_000), rate = 1.0, ledger = ledger)
        assertEquals(listOf(0L, xmr), points.map { it.balance })
        assertEquals(0.0, points[0].value, 0.0)
    }

    @Test
    fun `nothing is drawn before the balance is known`() {
        val ledger = BalanceLedger(xmr, emptyList(), knownSinceMs = 2_000)
        assertEquals(
            listOf(2_000L, 3_000L),
            PortfolioHistory.points(prices(1_000, 2_000, 3_000), rate = 1.0, ledger = ledger).map { it.timestamp }
        )
        assertTrue(PortfolioHistory.points(prices(1_000), rate = 1.0, ledger = ledger).isEmpty())
    }

    @Test
    fun `All starts at the last empty sample before the first holding`() {
        val ledger = BalanceLedger(xmr, listOf(received(3_500, xmr)))
        val all = PortfolioHistory.points(prices(1_000, 2_000, 3_000, 4_000), rate = 1.0, ledger = ledger, startAtFirstHolding = true)
        assertEquals(listOf(3_000L, 4_000L), all.map { it.timestamp })
        // A wallet that never held anything keeps the whole range.
        val empty = PortfolioHistory.points(prices(1_000, 2_000), rate = 1.0, ledger = BalanceLedger(0, emptyList()), startAtFirstHolding = true)
        assertEquals(2, empty.size)
    }

    @Test
    fun `markers sit on the samples with transactions`() {
        val ledger = BalanceLedger(xmr, listOf(received(1_500, 2 * xmr), sent(3_500, xmr)))
        val points = PortfolioHistory.points(prices(1_000, 2_000, 3_000, 4_000), rate = 1.0, ledger = ledger)
        val markers = PortfolioHistory.markers(points, formatValue = { "\$${it.toInt()}" }, formatWhen = { "t$it" })
        assertEquals(listOf(2_000L, 4_000L), markers.map { it.timestamp })
        assertEquals(listOf(200.0, 100.0), markers.map { it.value })
        assertEquals(listOf(ChartMarker.Style.RECEIVED, ChartMarker.Style.SENT), markers.map { it.style })
        assertEquals("Received 2.0000 XMR", markers[0].label)
        // A lone transaction gives its own time.
        assertEquals("t1500, portfolio \$200", markers[0].valueText)
        assertEquals("Sent 1.0000 XMR", markers[1].label)
        assertEquals("t3500, portfolio \$100", markers[1].valueText)
    }

    @Test
    fun `a few transactions on one sample are each named`() {
        val ledger = BalanceLedger(xmr / 2, listOf(received(1_100, xmr), sent(1_200, xmr / 2)))
        val points = PortfolioHistory.points(prices(1_000, 2_000), rate = 1.0, ledger = ledger)
        val marker = PortfolioHistory.markers(points, { "" }, { "" }).single()
        assertEquals("Received 1.0000 XMR, Sent 0.5000 XMR", marker.label)
        assertEquals("2 transactions", PortfolioHistory.summary(points.last().changes))
    }

    @Test
    fun `many transactions on one sample are summed up`() {
        val changes = listOf(received(1_100, xmr), received(1_200, xmr), received(1_300, xmr), sent(1_400, xmr / 2))
        val points = PortfolioHistory.points(prices(1_000, 2_000), rate = 1.0, ledger = BalanceLedger(xmr * 5 / 2, changes))
        val marker = PortfolioHistory.markers(points, { "" }, { "" }).single()
        assertEquals("4 transactions, received 3.0000 XMR, sent 0.5000 XMR", marker.label)
        assertEquals(ChartMarker.Style.RECEIVED, marker.style)
        assertEquals("4 transactions", PortfolioHistory.spokenCount(points))
        // Several transactions give the sample's own time.
        assertEquals(2_000L, PortfolioHistory.eventTime(points.last()))
    }

    @Test
    fun `a sample names its lone transaction and when it happened`() {
        val ledger = BalanceLedger(xmr * 3 / 2, listOf(received(1_500, xmr / 2)))
        val points = PortfolioHistory.points(prices(1_000, 2_000), rate = 1.0, ledger = ledger)
        assertNull(PortfolioHistory.summary(points[0].changes))
        assertEquals("Received 0.5000 XMR", PortfolioHistory.summary(points[1].changes))
        assertEquals(1_000L, PortfolioHistory.eventTime(points[0]))
        assertEquals(1_500L, PortfolioHistory.eventTime(points[1]))
        assertEquals("1 transaction", PortfolioHistory.spokenCount(points))
        assertNull(PortfolioHistory.spokenCount(points.take(1)))
    }

    @Test
    fun `a past selection is its own sample, and the last sample is Now`() {
        val ledger = BalanceLedger(xmr, listOf(received(1_500, xmr)))
        val points = PortfolioHistory.points(prices(1_000, 2_000, 3_000), rate = 1.0, ledger = ledger)
        assertNull(PortfolioHistory.historicalSelection(null, points))
        // The final sample holds the ledger now, so picking it is Now.
        assertNull(PortfolioHistory.historicalSelection(3_000, points))
        // A time with no sample (a refresh dropped it) falls back to Now.
        assertNull(PortfolioHistory.historicalSelection(2_500, points))
        assertEquals(points[0], PortfolioHistory.historicalSelection(1_000, points))
        assertEquals(xmr, PortfolioHistory.historicalSelection(2_000, points)!!.balance)
        assertNull(PortfolioHistory.historicalSelection(1_000, emptyList()))
    }

    @Test
    fun `ledgers with the same content are equal, so kept charts are reused`() {
        val ledger = BalanceLedger(xmr, listOf(received(2_000, xmr), sent(1_000, xmr / 2)))
        val same = BalanceLedger(xmr, listOf(sent(1_000, xmr / 2), received(2_000, xmr)))
        assertEquals(ledger, same)
        assertEquals(ledger.hashCode(), same.hashCode())
        assertNotEquals(ledger, BalanceLedger(2 * xmr, ledger.changes))
        assertNotEquals(ledger, BalanceLedger(xmr, ledger.changes.take(1)))
        assertNotEquals(ledger, BalanceLedger(xmr, ledger.changes, knownSinceMs = 500))
    }
}
