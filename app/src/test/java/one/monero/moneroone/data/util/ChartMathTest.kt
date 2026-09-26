package one.monero.moneroone.data.util

import one.monero.moneroone.data.model.CMCPoint
import one.monero.moneroone.data.model.PriceDataPoint
import one.monero.moneroone.ui.screens.chart.TimeRange
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ChartMathTest {

    private val nowS = 1_800_000_000L
    private val nowMs = nowS * 1000

    private fun point(seconds: Long, price: Double) = CMCPoint(seconds.toString(), listOf(price, 0.0, 0.0))

    private fun sample(ms: Long, price: Double) = PriceDataPoint(ms, price)

    private fun prices(samples: List<PriceDataPoint>) = samples.map { it.price }

    private fun assertRange(low: Double, high: Double, range: ClosedFloatingPointRange<Double>) {
        assertEquals(low, range.start, 1e-9)
        assertEquals(high, range.endInclusive, 1e-9)
    }

    @Test
    fun `samples drop garbage, keep the last duplicate, and come out sorted`() {
        val raw = listOf(
            point(nowS - 1_000, 504.0),
            CMCPoint(null, listOf(1.0)),
            CMCPoint("not a time", listOf(1.0)),
            CMCPoint("NaN", listOf(1.0)),
            CMCPoint((nowS - 4_000).toString(), null),
            CMCPoint((nowS - 4_000).toString(), emptyList()),
            point(nowS - 5_000, Double.NaN),
            point(nowS - 6_000, Double.POSITIVE_INFINITY),
            point(nowS - 7_000, 0.0),
            point(nowS - 8_000, -3.0),
            point(nowS - 2_000, 999.0),
            point(nowS - 3_000, 501.0),
            point(nowS - 2_000, 502.0)
        )
        val samples = ChartMath.chartSamples(raw, TimeRange.DAY, nowMs)
        assertEquals(listOf(501.0, 502.0, 504.0), prices(samples))
        assertEquals(listOf(nowMs - 3_000_000, nowMs - 2_000_000, nowMs - 1_000_000), samples.map { it.timestamp })
    }

    @Test
    fun `samples older than the range are cut, All keeps every one`() {
        val raw = listOf(point(nowS - 90_000, 400.0), point(nowS - 80_000, 410.0), point(nowS - 10, 420.0))
        assertEquals(listOf(410.0, 420.0), prices(ChartMath.chartSamples(raw, TimeRange.DAY, nowMs)))
        assertEquals(listOf(400.0, 410.0, 420.0), prices(ChartMath.chartSamples(raw, TimeRange.ALL, nowMs)))
    }

    @Test
    fun `an isolated spike is dropped, a real move stays`() {
        fun run(vararg values: Double) =
            prices(ChartMath.removingIsolatedSpikes(values.mapIndexed { i, v -> sample(i * 1_000L, v) }))

        assertEquals(listOf(100.0, 100.0, 100.0, 100.0), run(100.0, 100.0, 600.0, 100.0, 100.0))
        assertEquals(listOf(100.0, 100.0, 100.0), run(100.0, 10.0, 100.0, 100.0))
        // The next sample confirms the move.
        assertEquals(listOf(100.0, 600.0, 610.0), run(100.0, 600.0, 610.0))
        // An end has one neighbor and always stays.
        assertEquals(listOf(600.0, 100.0, 100.0), run(600.0, 100.0, 100.0))
        // Exactly five times off is not a spike.
        assertEquals(listOf(100.0, 500.0, 100.0), run(100.0, 500.0, 100.0))
    }

    @Test
    fun `the live price ends the series only when it is newer`() {
        val samples = listOf(sample(1_000, 500.0), sample(2_000, 510.0))
        assertEquals(samples + sample(3_000, 520.0), ChartMath.chartSeries(samples, sample(3_000, 520.0)))
        assertEquals(samples, ChartMath.chartSeries(samples, sample(2_000, 520.0)))
        assertEquals(samples, ChartMath.chartSeries(samples, sample(1_500, 520.0)))
        assertEquals(samples, ChartMath.chartSeries(samples, null))
        assertEquals(emptyList<PriceDataPoint>(), ChartMath.chartSeries(emptyList(), sample(3_000, 520.0)))
    }

    @Test
    fun `the Y domain pads the span and gives a flat line a band`() {
        assertRange(95.0, 205.0, ChartMath.chartYDomain(listOf(100.0, 200.0, 150.0)))
        assertRange(95.0, 205.0, ChartMath.chartYDomain(listOf(Double.NaN, 100.0, 200.0)))
        assertRange(95.0, 105.0, ChartMath.chartYDomain(listOf(100.0, 100.0)))
        assertRange(-0.5, 1.5, ChartMath.chartYDomain(listOf(0.5)))
        assertRange(0.0, 100.0, ChartMath.chartYDomain(emptyList()))
    }

    @Test
    fun `an empty wallet's portfolio axis stays at zero and above`() {
        // Flat at zero scales like 0..1, so the lowest round tick is $0.00.
        assertRange(-0.05, 1.05, ChartMath.portfolioYDomain(listOf(0.0, 0.0, 0.0)))
        // Any held value keeps the shared rule.
        assertRange(-5.0, 105.0, ChartMath.portfolioYDomain(listOf(0.0, 0.0, 100.0)))
        assertRange(0.0, 100.0, ChartMath.portfolioYDomain(emptyList()))
    }

    @Test
    fun `the sparkline has one value per half hour over a full day`() {
        // A sample every 5 minutes for 24 hours, the price rising 1 per sample.
        val samples = (0..288).map { sample(nowMs - (288 - it) * 5 * 60_000L, 100.0 + it) }
        val line = ChartMath.widgetSparkline(samples, rate = 2.0, nowMs = nowMs)
        assertEquals(48, line.size)
        // The last slot is now; the one before it is six samples earlier.
        assertEquals((100.0 + 288) * 2, line.last(), 1e-9)
        assertEquals((100.0 + 282) * 2, line[46], 1e-9)
        assertEquals((100.0 + 6) * 2, line.first(), 1e-9)
    }

    @Test
    fun `a short history draws a short sparkline, not a flat one`() {
        // Six hours of samples: slots from six hours ago to now.
        val samples = (0..72).map { sample(nowMs - (72 - it) * 5 * 60_000L, 500.0) }
        assertEquals(13, ChartMath.widgetSparkline(samples, rate = 1.0, nowMs = nowMs).size)
        assertTrue(ChartMath.widgetSparkline(emptyList(), rate = 1.0, nowMs = nowMs).isEmpty())
    }

    @Test
    fun `percent change runs first to last and needs a start above zero`() {
        assertEquals(10.0, ChartMath.percentChange(listOf(100.0, 90.0, 110.0)) { it }!!, 1e-9)
        assertNull(ChartMath.percentChange(listOf(100.0)) { it })
        assertNull(ChartMath.percentChange(listOf(0.0, 10.0)) { it })
        assertNull(ChartMath.percentChange(listOf(Double.NaN, 10.0)) { it })
    }

    @Test
    fun `nearest index by timestamp`() {
        val times = listOf(100L, 200L, 300L)
        assertEquals(-1, emptyList<Long>().nearestIndexByTimestamp(150) { it })
        assertEquals(0, times.nearestIndexByTimestamp(0) { it })
        assertEquals(0, times.nearestIndexByTimestamp(140) { it })
        // A tie goes to the later one.
        assertEquals(1, times.nearestIndexByTimestamp(150) { it })
        assertEquals(2, times.nearestIndexByTimestamp(1_000) { it })
        assertNull(emptyList<Long>().nearestByTimestamp(150) { it })
    }

    @Test
    fun `xmr amounts keep four decimals at least`() {
        assertEquals("0.0000", XmrFormat.format(0))
        assertEquals("1.0000", XmrFormat.format(1_000_000_000_000))
        assertEquals("0.0015", XmrFormat.format(1_500_000_000))
        assertEquals("1.234567890123", XmrFormat.format(1_234_567_890_123))
        assertEquals("0.000000000001", XmrFormat.format(1))
    }
}
