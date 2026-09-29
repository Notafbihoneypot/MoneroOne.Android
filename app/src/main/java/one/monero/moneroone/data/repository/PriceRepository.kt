package one.monero.moneroone.data.repository

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import one.monero.moneroone.data.model.Currency
import one.monero.moneroone.data.model.CurrentPrice
import one.monero.moneroone.data.model.MoneroOnePriceResponse
import one.monero.moneroone.data.model.PriceDataPoint
import one.monero.moneroone.data.model.CMCChartResponse
import one.monero.moneroone.data.util.ChartMath
import one.monero.moneroone.ui.screens.chart.TimeRange
import timber.log.Timber
import java.io.File
import one.monero.moneroone.data.model.PriceHistory
import one.monero.moneroone.data.model.PriceHistoryResponse
import one.monero.moneroone.data.model.HistoricalPrice

/** The price API has no quote for [currency]; asking again will not help. */
class MissingQuoteException(currency: Currency) : Exception("Price not available for ${currency.code}")

class PriceRepository {

    private val json = Json { ignoreUnknownKeys = true }

    companion object {
        private const val MONERO_ONE_API = "https://monero.one/api/v1"
    }

    /** Public price history, cached per currency for one hour. No wallet data is sent. */
    suspend fun fetchHistory(currency: Currency, cacheDir: File): Result<List<HistoricalPrice>> = withContext(Dispatchers.IO) {
        val cache = File(cacheDir, "price-history-${currency.code}.json")
        fun parse(raw: String): List<HistoricalPrice> {
            val response = json.decodeFromString<PriceHistoryResponse>(raw)
            require(response.currency == currency.code)
            return PriceHistory.samples(response).also { require(it.isNotEmpty()) }
        }
        val cached = runCatching { parse(cache.readText()) }.getOrNull()
        if (cached != null && System.currentTimeMillis() - cache.lastModified() < 3_600_000) {
            return@withContext Result.success(cached)
        }
        try {
            val raw = fetchUrl("$MONERO_ONE_API/history?currency=${currency.code}")
            val samples = parse(raw)
            runCatching { cache.writeText(raw) }
            Result.success(samples)
        } catch (e: Exception) {
            if (cached != null) Result.success(cached) else Result.failure(e)
        }
    }

    /**
     * Fetch prices for all supported currencies via monero.one API.
     */
    suspend fun fetchAllPrices(): Result<Map<Currency, Double>> = withContext(Dispatchers.IO) {
        try {
            val response = fetchUrl("$MONERO_ONE_API/price")
            val parsed = json.decodeFromString<MoneroOnePriceResponse>(response)

            val priceMap = mutableMapOf<Currency, Double>()
            Currency.entries.forEach { currency ->
                parsed.quotes[currency.code]?.let { quote ->
                    priceMap[currency] = quote.price
                }
            }

            Result.success(priceMap)
        } catch (e: Exception) {
            Timber.e(e, "Failed to fetch all prices")
            Result.failure(e)
        }
    }

    suspend fun fetchCurrentPrice(currency: Currency): Result<CurrentPrice> = withContext(Dispatchers.IO) {
        try {
            val response = fetchUrl("$MONERO_ONE_API/price")
            val parsed = json.decodeFromString<MoneroOnePriceResponse>(response)

            val quote = parsed.quotes[currency.code]
                ?: return@withContext Result.failure(MissingQuoteException(currency))

            val usdQuote = parsed.quotes["usd"]
            val usdPrice = usdQuote?.price ?: quote.price

            val usdToSelectedRate = if (currency == Currency.USD || usdPrice == 0.0) {
                1.0
            } else {
                quote.price / usdPrice
            }

            Result.success(CurrentPrice(quote.price, quote.change24h, usdToSelectedRate))
        } catch (e: Exception) {
            Timber.e(e, "Failed to fetch current price")
            Result.failure(e)
        }
    }

    /**
     * Every real USD sample of [range], oldest first. Nothing is smoothed or
     * dropped for size; see [ChartMath.chartSamples] for what is dropped.
     */
    suspend fun fetchChartData(range: TimeRange): Result<List<PriceDataPoint>> = withContext(Dispatchers.IO) {
        try {
            val response = fetchUrl("$MONERO_ONE_API/chart?range=${range.apiRange}")
            val parsed = json.decodeFromString<CMCChartResponse>(response)

            val points = parsed.data?.points
                ?: return@withContext Result.failure(Exception("No chart data available"))

            val samples = ChartMath.chartSamples(points, range, System.currentTimeMillis())
            if (samples.isEmpty()) {
                return@withContext Result.failure(Exception("No chart data available"))
            }

            Result.success(samples)
        } catch (e: Exception) {
            Timber.e(e, "Failed to fetch chart data")
            Result.failure(e)
        }
    }

    private fun fetchUrl(urlString: String): String = one.monero.moneroone.core.network.TorNetwork.withClient { client ->
        val request = okhttp3.Request.Builder().url(urlString)
            .header("Accept", "application/json").header("User-Agent", "Mozilla/5.0")
            .header("Cache-Control", "no-cache").build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw java.io.IOException("HTTP ${response.code}")
            response.body?.string() ?: throw java.io.IOException("Empty price response")
        }
    }
}
