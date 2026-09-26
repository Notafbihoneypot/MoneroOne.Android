package one.monero.moneroone.data.util

import one.monero.moneroone.data.model.CMCPoint
import one.monero.moneroone.data.model.PriceDataPoint
import one.monero.moneroone.ui.screens.chart.TimeRange
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToLong

/**
 * The price chart's data rules, shared with iOS (PriceService). Charts draw
 * every real sample with straight segments: no smoothing, no downsampling.
 */
object ChartMath {

    /** A widget sparkline slot. */
    const val SPARKLINE_SLOT_MS = 30 * 60 * 1000L

    /**
     * Every usable sample of an API response, oldest first: one per
     * timestamp (the last one wins), none older than [range] keeps, no
     * garbage, and no isolated spikes.
     */
    fun chartSamples(points: List<CMCPoint>, range: TimeRange, nowMs: Long): List<PriceDataPoint> {
        val byTime = HashMap<Long, Double>(points.size)
        for (point in points) {
            val seconds = point.s?.toDoubleOrNull()?.takeIf { it.isFinite() } ?: continue
            val price = point.v?.firstOrNull()?.takeIf { it.isFinite() && it > 0 } ?: continue
            byTime[(seconds * 1000).roundToLong()] = price
        }
        val cutoff = range.spanMs?.let { nowMs - it }
        val samples = byTime.entries
            .filter { cutoff == null || it.key >= cutoff }
            .sortedBy { it.key }
            .map { PriceDataPoint(it.key, it.value) }
        return removingIsolatedSpikes(samples)
    }

    /**
     * Drops a sample that is [factor] times off both of its neighbors, a
     * bad print that no market moved to and back. A real move stays,
     * because its next sample is near it.
     */
    fun removingIsolatedSpikes(samples: List<PriceDataPoint>, factor: Double = 5.0): List<PriceDataPoint> {
        if (samples.size < 3) return samples
        val kept = ArrayList<PriceDataPoint>(samples.size)
        for ((i, sample) in samples.withIndex()) {
            if (i > 0 && i < samples.lastIndex) {
                val previous = samples[i - 1].price
                val next = samples[i + 1].price
                val farFromPrevious = sample.price > previous * factor || sample.price < previous / factor
                val farFromNext = sample.price > next * factor || sample.price < next / factor
                if (farFromPrevious && farFromNext) continue
            }
            kept.add(sample)
        }
        return kept
    }

    /**
     * The drawn series: the samples, then the live price as the last point
     * when it is newer than the last sample. Built on every read and never
     * cached, so the line always ends at the price in the header.
     */
    fun chartSeries(samples: List<PriceDataPoint>, tip: PriceDataPoint?): List<PriceDataPoint> {
        val last = samples.lastOrNull() ?: return samples
        if (tip == null || tip.timestamp <= last.timestamp) return samples
        return samples + tip
    }

    /** The Y range for [values]: 5% of the span above and below; a band for a flat line. */
    fun chartYDomain(values: List<Double>): ClosedFloatingPointRange<Double> {
        val finite = values.filter { it.isFinite() }
        val low = finite.minOrNull() ?: return 0.0..100.0
        val high = finite.maxOrNull() ?: return 0.0..100.0
        if (high > low) {
            val padding = (high - low) * 0.05
            return (low - padding)..(high + padding)
        }
        val padding = max(abs(low) * 0.05, 1.0)
        return (low - padding)..(high + padding)
    }

    /**
     * The Y range for a portfolio line. A wallet that held nothing over the
     * range draws flat at zero; scale it like an empty stretch of a funded
     * wallet, zero near the bottom, so the axis shows no negative money.
     */
    fun portfolioYDomain(values: List<Double>): ClosedFloatingPointRange<Double> =
        chartYDomain(if (values.isNotEmpty() && values.all { it == 0.0 }) listOf(0.0, 1.0) else values)

    /**
     * The widget's 24h line: one value per 30-minute slot ending at [nowMs],
     * each the nearest real sample times [rate]. Slots before the first one
     * a sample falls near are left out, so a short history draws short
     * rather than flat.
     */
    fun widgetSparkline(samples: List<PriceDataPoint>, rate: Double, nowMs: Long, slots: Int = 48): List<Double> {
        val times = (0 until slots).map { nowMs - (slots - 1 - it) * SPARKLINE_SLOT_MS }
        val firstCovered = times.indexOfFirst { at ->
            val nearest = samples.nearestByTimestamp(at) { it.timestamp }
            nearest != null && abs(nearest.timestamp - at) < SPARKLINE_SLOT_MS / 2
        }
        if (firstCovered < 0) return emptyList()
        return times.subList(firstCovered, times.size).mapNotNull { at ->
            samples.nearestByTimestamp(at) { it.timestamp }?.let { it.price * rate }
        }
    }

    /** Percent change from the first point to the last; null for fewer than two or a start at zero. */
    inline fun <T> percentChange(points: List<T>, value: (T) -> Double): Double? {
        if (points.size < 2) return null
        val first = value(points.first())
        if (!(first > 0)) return null
        return (value(points.last()) - first) / first * 100
    }
}
