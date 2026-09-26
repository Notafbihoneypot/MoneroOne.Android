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
import java.net.HttpURLConnection
import java.net.URL

/** The price API has no quote for [currency]; asking again will not help. */
class MissingQuoteException(currency: Currency) : Exception("Price not available for ${currency.code}")

class PriceRepository {

    private val json = Json { ignoreUnknownKeys = true }

    companion object {
        private const val MONERO_ONE_API = "https://monero.one/api/v1"
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

    private fun fetchUrl(urlString: String): String {
        val url = URL(urlString)
        val connection = url.openConnection() as HttpURLConnection

        return try {
            connection.requestMethod = "GET"
            connection.setRequestProperty("Accept", "application/json")
            // Generic UA on purpose: the platform default advertises Android and
            // device model, and naming the app ties this IP to Monero ownership.
            connection.setRequestProperty("User-Agent", "Mozilla/5.0")
            connection.connectTimeout = 10000
            connection.readTimeout = 10000
            // Prices go stale in minutes; never answer from an HTTP cache.
            connection.useCaches = false

            if (connection.responseCode !in 200..299) {
                throw Exception("HTTP ${connection.responseCode}: ${connection.responseMessage}")
            }

            connection.inputStream.bufferedReader().use { it.readText() }
        } finally {
            connection.disconnect()
        }
    }
}
