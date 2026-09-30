package one.monero.moneroone.ui.screens.chart

import android.app.Application
import android.content.Context
import androidx.compose.runtime.Immutable
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import one.monero.moneroone.data.model.Currency
import one.monero.moneroone.data.model.CurrentPrice
import one.monero.moneroone.data.model.PriceDataPoint
import one.monero.moneroone.data.repository.MissingQuoteException
import one.monero.moneroone.data.repository.PriceRepository
import one.monero.moneroone.data.util.ChartMath
import one.monero.moneroone.ui.components.ChartPoint
import one.monero.moneroone.widget.PriceWidget
import one.monero.moneroone.widget.WidgetDataStore
import timber.log.Timber
import kotlin.coroutines.cancellation.CancellationException

/** What the price and portfolio charts draw. Fiat values are in [currency]. */
data class ChartUiState(
    val range: TimeRange = TimeRange.WEEK,
    val currency: Currency = Currency.USD,
    /** The range's samples in USD with the live price as the last point. */
    val seriesUsd: List<PriceDataPoint> = emptyList(),
    /** [seriesUsd] in [currency]; empty while [rate] is unknown. */
    val points: List<ChartPoint> = emptyList(),
    /** USD to [currency]; null until a price in [currency] has come in. */
    val rate: Double? = 1.0,
    val currentPrice: CurrentPrice? = null,
    /** True while there is nothing to draw yet and a fetch is running. */
    val isLoading: Boolean = true,
    /** First point to last over [range]. */
    val rangeChange: Double? = null,
    /** Over the 24h series, whatever [range] shows. */
    val priceChange24h: Double? = null,
    val high: Double? = null,
    val low: Double? = null,
    val open: Double? = null,
    val close: Double? = null
)

/**
 * What balance history draws from: every range's samples in USD with the
 * live price as the last point, and the rate into [currency].
 */
@Immutable
data class HistoryPrices(
    val currency: Currency = Currency.USD,
    /** USD to [currency]; null while unknown. */
    val rate: Double? = 1.0,
    /** Raw samples in USD, per range, without the live price. */
    val cache: Map<TimeRange, List<PriceDataPoint>> = emptyMap(),
    /** The live price in USD, the last point of every line. */
    val tip: PriceDataPoint? = null,
    val fetched: Set<TimeRange> = emptySet(),
    /** The rate into [currency] failed to load. */
    val rateFailed: Boolean = false
) {
    /** [range]'s samples in USD, the live price last; empty while the rate is unknown. */
    fun series(range: TimeRange): List<PriceDataPoint> =
        if (rate == null) emptyList() else ChartMath.chartSeries(cache[range].orEmpty(), tip)

    /**
     * True once [range] has samples, or its fetch finished without any, and
     * the rate is known or failed to load. A range that loaded with nothing
     * to draw says so instead of loading forever.
     */
    fun isLoaded(range: TimeRange): Boolean =
        (!cache[range].isNullOrEmpty() || range in fetched) && (rate != null || rateFailed)
}

/**
 * Price data for the charts and the price widget, as iOS PriceService keeps
 * it: raw samples per range with a TTL, one fetch per range at a time, the
 * live price added as the line's tip on every read, and a refresh every
 * five minutes while the app is in the foreground. Nothing goes on the
 * network before [start], which waits for a wallet to exist.
 */
class ChartViewModel(application: Application) : AndroidViewModel(application) {

    private data class Model(
        val range: TimeRange = TimeRange.WEEK,
        val currency: Currency = Currency.USD,
        /** Raw samples in USD, per range. Never includes the tip. */
        val cache: Map<TimeRange, List<PriceDataPoint>> = emptyMap(),
        val loading: Set<TimeRange> = emptySet(),
        val price: CurrentPrice? = null,
        val rate: Double = 1.0,
        /** The last price fetch for [currency] gave up. */
        val priceFailed: Boolean = false,
        /** Ranges whose fetch has finished once, with or without data. */
        val fetched: Set<TimeRange> = emptySet()
    )

    private val priceRepository = PriceRepository()
    private val prefs = application.getSharedPreferences("monero_wallet", Context.MODE_PRIVATE)

    private val model = MutableStateFlow(
        Model(
            currency = prefs.getString("selected_currency", Currency.USD.code)
                .let { saved -> Currency.entries.find { it.code == saved } } ?: Currency.USD
        )
    )

    val uiState: StateFlow<ChartUiState> = model
        .map { it.toUiState() }
        .stateIn(viewModelScope, SharingStarted.Eagerly, model.value.toUiState())

    /** Balance history's prices; it asks for its own ranges with [fetchRange]. */
    val historyPrices: StateFlow<HistoryPrices> = model
        .map { it.toHistoryPrices() }
        .stateIn(viewModelScope, SharingStarted.Eagerly, model.value.toHistoryPrices())

    private val fetchedAt = HashMap<TimeRange, Long>()
    private val inFlight = HashMap<TimeRange, Deferred<Unit>>()
    private var started = false
    private var startupJob: Job? = null
    private var refreshLoop: Job? = null
    private var priceJob: Job? = null
    private var currencyJob: Job? = null

    /**
     * Fetches the price and every range, then refreshes every five minutes.
     * Call once a wallet exists; calling again does nothing.
     */
    fun start() {
        if (started) return
        started = true
        startupJob = viewModelScope.launch {
            fetchPrice()
            // The shown range first, then the rest.
            for (range in (listOf(model.value.range) + PREFETCH_ORDER).distinct()) fetchChart(range)
        }
        startRefreshLoop()
    }

    /** Stops every fetch, for when the last wallet is gone. */
    fun stop() {
        started = false
        listOf(startupJob, refreshLoop, priceJob, currencyJob).forEach { it?.cancel() }
        inFlight.values.forEach { it.cancel() }
        inFlight.clear()
        model.update { it.copy(loading = emptySet()) }
    }

    /** The app went to the background: no refreshes until it comes back. */
    fun onBackground() {
        refreshLoop?.cancel()
        refreshLoop = null
    }

    /** The app came back: refresh what went stale and restart the loop. */
    fun onForeground() {
        if (!started) return
        startRefreshLoop()
        viewModelScope.launch {
            val price = model.value.price
            if (price == null || System.currentTimeMillis() - price.lastUpdated >= REFRESH_INTERVAL_MS) {
                fetchPrice()
            }
            fetchChart(model.value.range)
        }
    }

    /** Shows [range], fetching it when its cache is missing or stale. */
    fun showRange(range: TimeRange) {
        model.update { state ->
            state.copy(
                range = range,
                loading = if (state.cache[range].isNullOrEmpty()) state.loading + range else state.loading
            )
        }
        // Before start the range waits for the startup fetch.
        if (!started) return
        viewModelScope.launch { fetchChart(range) }
    }

    /**
     * Fetches [range] for balance history when it is missing or stale,
     * without changing the range the Price tab shows. Before [start] the
     * startup fetch brings every range.
     */
    fun fetchRange(range: TimeRange) {
        if (!started) return
        viewModelScope.launch { fetchChart(range) }
    }

    fun selectCurrency(currency: Currency) {
        if (model.value.currency == currency) return
        currencyJob?.cancel()
        priceJob?.cancel()
        prefs.edit().putString("selected_currency", currency.code).apply()
        // The samples are USD and stay; the price and the rate wait for the new currency.
        model.update { it.copy(currency = currency, price = null, rate = 1.0, priceFailed = false) }
        if (!started) return
        currencyJob = viewModelScope.launch {
            // Wait out quick taps through the currency list.
            delay(CURRENCY_DEBOUNCE_MS)
            fetchPrice()
        }
    }

    /** Pull to refresh: the price, then the shown range even when fresh. */
    suspend fun refreshNow() {
        if (!started) return
        fetchPrice()
        fetchChart(model.value.range, force = true)
    }

    private fun startRefreshLoop() {
        if (refreshLoop?.isActive == true) return
        refreshLoop = viewModelScope.launch {
            while (isActive) {
                delay(REFRESH_INTERVAL_MS)
                fetchPrice()
                fetchChart(model.value.range)
            }
        }
    }

    /** Fetches the price; a fetch already running is joined, not repeated. */
    private suspend fun fetchPrice() {
        priceJob?.takeIf { it.isActive }?.let { return it.join() }
        val job = viewModelScope.launch {
            val currency = model.value.currency
            model.update { it.copy(priceFailed = false) }
            val result = fetchPriceWithRetry(currency)
            if (model.value.currency != currency) return@launch
            if (result == null) {
                model.update { it.copy(priceFailed = true) }
                return@launch
            }
            model.update { it.copy(price = result, rate = result.usdToSelectedRate, priceFailed = false) }
            saveWidget()
        }
        priceJob = job
        job.join()
    }

    /** Three tries, 2 s then 4 s apart. A currency the API lacks is not retried. */
    private suspend fun fetchPriceWithRetry(currency: Currency): CurrentPrice? {
        var backoff = FIRST_RETRY_DELAY_MS
        repeat(PRICE_ATTEMPTS) { attempt ->
            Timber.d("Price fetch ${currency.code}, try ${attempt + 1}")
            priceRepository.fetchCurrentPrice(currency)
                .onSuccess { return it }
                .onFailure { error ->
                    if (error is CancellationException) throw error
                    if (error is MissingQuoteException || attempt == PRICE_ATTEMPTS - 1) return null
                }
            delay(backoff)
            backoff *= 2
        }
        return null
    }

    /**
     * Fetches [range] unless its samples are still fresh. A fetch of the
     * same range already running is joined, so a quick switch back and
     * forth costs one request.
     */
    private suspend fun fetchChart(range: TimeRange, force: Boolean = false) {
        val fresh = fetchedAt[range]?.let { System.currentTimeMillis() - it < range.cacheTtlMs } == true
        if (!force && fresh && !model.value.cache[range].isNullOrEmpty()) return

        inFlight[range]?.let { return it.await() }
        val task = viewModelScope.async(start = CoroutineStart.LAZY) {
            try {
                performChartFetch(range)
            } finally {
                inFlight.remove(range)
            }
        }
        inFlight[range] = task
        task.await()
    }

    private suspend fun performChartFetch(range: TimeRange) {
        model.update { it.copy(loading = it.loading + range) }
        Timber.d("Chart fetch ${range.apiRange}")
        try {
            // On failure the old samples stay on screen.
            val samples = priceRepository.fetchChartData(range).getOrElse { error ->
                if (error is CancellationException) throw error
                Timber.w(error, "Chart fetch failed for ${range.apiRange}")
                return
            }
            if (samples.size < 2) return
            fetchedAt[range] = System.currentTimeMillis()
            model.update { it.copy(cache = it.cache + (range to samples)) }
            if (range == TimeRange.DAY) saveWidget()
        } finally {
            model.update { it.copy(loading = it.loading - range, fetched = it.fetched + range) }
        }
    }

    /**
     * The price widget: the price and its 24h change, and a 24h line in 30
     * minute slots with the high and low of every sample. Written only
     * with a price, so the line never goes out in a currency the price
     * has not been fetched in.
     */
    private fun saveWidget() {
        val state = model.value
        val price = state.price ?: return
        val context = getApplication<Application>().applicationContext
        val day = ChartMath.chartSeries(state.cache[TimeRange.DAY].orEmpty(), state.tip())
        val now = System.currentTimeMillis()
        // Written here, in order; only the drawing leaves the main thread.
        WidgetDataStore.savePrice(context, price.price, price.change24h, state.currency.code, state.currency.symbol)
        if (day.isNotEmpty()) {
            WidgetDataStore.saveSparkline(
                context,
                points = ChartMath.widgetSparkline(day, state.rate, now),
                endMs = now,
                currencyCode = state.currency.code,
                high = day.maxOf { it.price } * state.rate,
                low = day.minOf { it.price } * state.rate
            )
        }
        viewModelScope.launch(Dispatchers.Default) { PriceWidget.updateAll(context) }
    }

    /** The live price as a USD point, for the end of every line. */
    private fun Model.tip(): PriceDataPoint? {
        val price = price ?: return null
        if (!(rate > 0)) return null
        return PriceDataPoint(price.lastUpdated, price.price / rate)
    }

    private fun Model.toHistoryPrices(): HistoryPrices = HistoryPrices(
        currency = currency,
        rate = rate.takeIf { currency == Currency.USD || price != null },
        cache = cache,
        tip = tip(),
        fetched = fetched,
        rateFailed = priceFailed
    )

    private fun Model.toUiState(): ChartUiState {
        val tip = tip()
        val series = ChartMath.chartSeries(cache[range].orEmpty(), tip)
        val day = ChartMath.chartSeries(cache[TimeRange.DAY].orEmpty(), tip)
        // USD samples drawn under another currency's symbol would be wrong,
        // so a line in that currency waits for its rate.
        val knownRate = rate.takeIf { currency == Currency.USD || price != null }
        val points = knownRate?.let { r -> series.map { ChartPoint(it.timestamp, it.price * r) } }.orEmpty()
        val awaitingRate = knownRate == null && !priceFailed
        return ChartUiState(
            range = range,
            currency = currency,
            seriesUsd = series,
            points = points,
            rate = knownRate,
            currentPrice = price,
            isLoading = points.isEmpty() && (range in loading || (awaitingRate && series.isNotEmpty())),
            rangeChange = ChartMath.percentChange(series) { it.price },
            priceChange24h = ChartMath.percentChange(day) { it.price },
            high = points.maxOfOrNull { it.value },
            low = points.minOfOrNull { it.value },
            open = points.firstOrNull()?.value,
            close = points.lastOrNull()?.value
        )
    }

    private companion object {
        const val REFRESH_INTERVAL_MS = 5 * 60 * 1000L
        const val CURRENCY_DEBOUNCE_MS = 300L
        const val PRICE_ATTEMPTS = 3
        const val FIRST_RETRY_DELAY_MS = 2_000L
        val PREFETCH_ORDER = listOf(TimeRange.WEEK, TimeRange.DAY, TimeRange.MONTH, TimeRange.YEAR, TimeRange.ALL)
    }
}
