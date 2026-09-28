package one.monero.moneroone.data.model

import kotlinx.serialization.Serializable

@Serializable
data class PriceHistoryResponse(val currency: String, val points: List<List<Double>>)

data class HistoricalPrice(val timestamp: Long, val price: Double)

/** Display quotes only. Wallet and fee calculations always use atomic XMR. */
object PriceHistory {
    fun samples(response: PriceHistoryResponse): List<HistoricalPrice> = response.points
        .filter { it.size >= 2 && it[0].isFinite() && it[0] > 0 && it[1].isFinite() && it[1] > 0 }
        .associate { it[0].toLong() to it[1] }
        .map { HistoricalPrice(it.key, it.value) }
        .sortedBy { it.timestamp }

    fun priceAt(timestamp: Long, points: List<HistoricalPrice>, livePrice: Double?, now: Long): Double? {
        val live = livePrice?.takeIf { it.isFinite() && it > 0 }
        if ((timestamp >= now - 86_400 || (points.lastOrNull()?.timestamp?.let { timestamp > it } == true)) && live != null) return live
        if (points.isEmpty()) return null
        val index = points.binarySearchBy(timestamp) { it.timestamp }
        if (index >= 0) return points[index].price
        val insertion = -index - 1
        if (insertion == 0) return points.first().price
        if (insertion == points.size) return points.last().price
        val before = points[insertion - 1]
        val after = points[insertion]
        return if (timestamp - before.timestamp <= after.timestamp - timestamp) before.price else after.price
    }
}
