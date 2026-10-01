package one.monero.moneroone.ui.screens.wallet

import android.text.format.DateFormat
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.ShowChart
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import one.monero.moneroone.core.locale.tr
import one.monero.moneroone.ui.components.ChartAxes
import one.monero.moneroone.ui.components.ChartSpeech
import one.monero.moneroone.ui.components.GlassSegmentedPicker
import one.monero.moneroone.ui.components.SampledLineChart
import one.monero.moneroone.ui.components.ShrinkToFitText
import one.monero.moneroone.ui.components.rememberChartDateFormats
import one.monero.moneroone.ui.screens.chart.ChartTimeAxis
import one.monero.moneroone.ui.screens.chart.HistoryPrices
import one.monero.moneroone.ui.screens.chart.TimeRange
import one.monero.moneroone.ui.theme.MoneroOrange
import java.util.Locale
import java.util.TimeZone

/**
 * Whether History is open, its range and the moment picked on it. The
 * screen that hosts the wallet tab keeps it, so a tab switch or a visit to
 * a transaction keeps the card as it was, as the iOS tab view does.
 */
@Stable
class BalanceHistoryState(expanded: Boolean = false, range: TimeRange = TimeRange.WEEK, timestamp: Long? = null) {
    var expanded by mutableStateOf(expanded)

    var range by mutableStateOf(range)
        private set

    /** The selected sample's time; null is Now. */
    var timestamp by mutableStateOf(timestamp)

    /** A new range starts at Now. */
    fun selectRange(next: TimeRange) {
        if (next == range) return
        range = next
        timestamp = null
    }

    companion object {
        val Saver: Saver<BalanceHistoryState, Any> = listSaver(
            save = { listOf(it.expanded, it.range.name, it.timestamp ?: NOW) },
            restore = { saved ->
                BalanceHistoryState(
                    expanded = saved[0] as Boolean,
                    range = TimeRange.valueOf(saved[1] as String),
                    timestamp = (saved[2] as Long).takeIf { it != NOW }
                )
            }
        )

        private const val NOW = -1L
    }
}

/** History for one wallet session: a wallet switch closes it and returns to Now. */
@Composable
fun rememberBalanceHistoryState(walletSessionId: Long): BalanceHistoryState =
    rememberSaveable(walletSessionId, saver = BalanceHistoryState.Saver) { BalanceHistoryState() }

/**
 * The chart inside the wallet's balance card (iOS BalanceHistoryChart). The
 * card owns the one balance shown and the activity cutoff through
 * [BalanceHistoryState.timestamp]. Every row keeps one height in every range
 * and state (loading, empty, scrubbing), so the card never jumps; the chart
 * stays composed between ranges and while History is closed.
 */
@Composable
internal fun BalanceHistoryChart(
    balance: Long,
    ledger: BalanceLedger,
    /** False while History is closed: the chart stays composed but idle. */
    isActive: Boolean,
    model: BalanceHistoryModel,
    walletSessionId: Long,
    prices: HistoryPrices,
    state: BalanceHistoryState,
    onFetchRange: (TimeRange) -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val locale = LocalConfiguration.current.locales[0]
    val use24Hour = DateFormat.is24HourFormat(context)
    val timeZoneId = TimeZone.getDefault().id
    val dates = rememberChartDateFormats()
    val haptic = LocalHapticFeedback.current
    val range = state.range
    val shown = model.shown
    val isCurrent = shown?.range == range
    val selected = if (isCurrent) PortfolioHistory.historicalSelection(state.timestamp, shown!!.points) else null
    val currentFetch by rememberUpdatedState(onFetchRange)

    LaunchedEffect(isActive, range) {
        if (isActive) {
            currentFetch(range)
            // All loads alongside, for a range that starts before the wallet
            // first held XMR; the range's own samples never wait for it.
            if (range != TimeRange.ALL) currentFetch(TimeRange.ALL)
        }
    }
    val inputs = remember(prices, range, ledger, locale, use24Hour, timeZoneId) {
        val own = prices.series(range)
        val needsAll = PortfolioHistory.needsAll(range, own, ledger)
        if (!prices.isLoaded(range) || (needsAll && !prices.isLoaded(TimeRange.ALL))) null
        else BalanceHistoryModel.Inputs(
            range = range,
            prices = own,
            allPrices = if (needsAll) prices.series(TimeRange.ALL) else emptyList(),
            rate = prices.rate ?: 1.0,
            currency = prices.currency,
            ledger = ledger,
            locale = locale,
            timeZone = TimeZone.getTimeZone(timeZoneId),
            use24Hour = use24Hour
        )
    }
    LaunchedEffect(isActive, walletSessionId, range, inputs) {
        if (isActive) model.show(walletSessionId, range, inputs)
    }
    // A refresh keeps the same instant when its real sample survives; the
    // new ledger may change what was held then or its value.
    LaunchedEffect(shown) {
        val series = shown ?: return@LaunchedEffect
        if (series.range == state.range && state.timestamp != null &&
            PortfolioHistory.historicalSelection(state.timestamp, series.points) == null
        ) {
            state.timestamp = null
        }
    }

    Column(modifier.padding(top = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        // Header: the moment picked, or the span shown; Now returns to the present.
        Row(
            Modifier.fillMaxWidth().defaultMinSize(minHeight = 44.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            ShrinkToFitText(
                text = selected?.let { dates.abbreviatedDateTime(it.timestamp) } ?: capitalizedWords(tr(range.axis.spokenSpan), locale),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                minScale = 0.75f,
                modifier = Modifier.weight(1f).testTag("wallet.historyDate")
            )
            Spacer(Modifier.width(4.dp))
            if (selected != null) {
                val returnToNow = {
                    state.timestamp = null
                    haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                }
                Row(
                    modifier = Modifier
                        .testTag("wallet.historyNow")
                        .defaultMinSize(minWidth = 44.dp, minHeight = 44.dp)
                        .clickable(role = Role.Button, onClick = returnToNow)
                        .clearAndSetSemantics {
                            contentDescription = tr("Return to current balance and activity")
                            role = Role.Button
                            onClick { returnToNow(); true }
                        },
                    horizontalArrangement = Arrangement.spacedBy(4.dp, Alignment.CenterHorizontally),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(Icons.Filled.History, contentDescription = null, tint = MoneroOrange, modifier = Modifier.size(14.dp))
                    Text(
                        tr("Now"),
                        style = MaterialTheme.typography.bodySmall,
                        fontWeight = FontWeight.SemiBold,
                        color = MoneroOrange,
                        maxLines = 1
                    )
                }
            } else {
                Text(
                    prices.currency.code.uppercase(Locale.ROOT),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    modifier = Modifier.clearAndSetSemantics {}
                )
            }
        }

        // Scales with the text, as the axis labels in it do (iOS @ScaledMetric).
        val chartHeight = 152.dp * LocalDensity.current.fontScale
        Box(Modifier.fillMaxWidth().height(chartHeight)) {
            HistoryLine(
                series = shown ?: PLACEHOLDER,
                isLive = isCurrent && shown?.isDrawable == true,
                isActive = isActive,
                selectedTimestamp = selected?.timestamp,
                onSelect = { timestamp -> state.timestamp = timestamp },
                dates = dates
            )
            HistoryOverlay(
                series = shown,
                isCurrent = isCurrent,
                isNewWallet = balance == 0L && ledger.changes.isEmpty()
            )
        }

        GlassSegmentedPicker(
            options = TimeRange.entries,
            selectedOption = range,
            onOptionSelected = { state.selectRange(it) },
            modifier = Modifier.fillMaxWidth().testTag("wallet.historyRange"),
            accessibilityLabel = { tr(it.axis.spokenName) },
            labelSelector = { tr(it.label) }
        )
    }
}

/**
 * Always composed: loading, empty and range changes fade it rather than
 * remove it, so the chart is never rebuilt from nothing.
 */
@Composable
private fun HistoryLine(
    series: BalanceHistoryModel.Series,
    isLive: Boolean,
    /** False while History is closed or closing: no touches. */
    isActive: Boolean,
    selectedTimestamp: Long?,
    onSelect: (Long?) -> Unit,
    dates: one.monero.moneroone.ui.screens.chart.ChartDateFormats
) {
    val alpha by animateFloatAsState(
        targetValue = if (isLive) 1f else if (series.isDrawable) 0.3f else 0f,
        animationSpec = tween(200, easing = EaseInOut),
        label = "historyLineAlpha"
    )
    // Hidden from TalkBack and touch while it is not the range picked.
    Box(
        Modifier
            .fillMaxSize()
            .graphicsLayer { this.alpha = alpha }
            .then(if (isLive) Modifier else Modifier.clearAndSetSemantics {})
    ) {
    SampledLineChart(
        points = series.chartPoints,
        domain = series.domain,
        axes = ChartAxes(series.tickAxis, dates, series.currency),
        speech = ChartSpeech(
            title = tr("Balance history"),
            span = tr(series.range.axis.spokenSpan),
            currency = series.currency,
            note = series.spokenCount,
            markerName = "transaction"
        ),
        onSelect = { index ->
            // The last sample is Now; the chart reports it as null already.
            onSelect(index?.let { series.points.getOrNull(it) }?.timestamp)
        },
        modifier = Modifier.fillMaxSize().testTag("wallet.historyChart"),
        markers = series.markers,
        insetsForMarkers = true,
        persistsSelection = true,
        selectedTimestamp = selectedTimestamp,
        enabled = isLive && isActive
    )
    }
}

@Composable
private fun HistoryOverlay(series: BalanceHistoryModel.Series?, isCurrent: Boolean, isNewWallet: Boolean) {
    when {
        series == null -> Column(
            Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterVertically),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Spinner()
            Text(
                tr("Loading balance history…"),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        !isCurrent -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Spinner(Modifier.semantics { contentDescription = tr("Loading balance history…") })
        }
        !series.isDrawable -> Column(
            Modifier.fillMaxSize().padding(horizontal = 8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterVertically),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Icon(
                Icons.Filled.ShowChart,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(28.dp)
            )
            Text(
                tr(if (isNewWallet) "Your history starts here" else "History unavailable for this period"),
                style = MaterialTheme.typography.titleSmall,
                textAlign = TextAlign.Center,
                maxLines = 2
            )
            Text(
                tr(if (isNewWallet) "Receive XMR to start tracking your wallet’s value." else "Try another time range."),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                maxLines = 2
            )
        }
    }
}

@Composable
private fun Spinner(modifier: Modifier = Modifier) {
    CircularProgressIndicator(modifier.size(24.dp), color = MoneroOrange, strokeWidth = 2.5.dp)
}

/** "Past Week", as iOS `capitalized` gives the span in the chart's header. */
private fun capitalizedWords(text: String, locale: Locale): String =
    text.split(' ').joinToString(" ") { word -> word.replaceFirstChar { it.titlecase(locale) } }

/** SwiftUI's default `.easeInOut`. */
private val EaseInOut = CubicBezierEasing(0.42f, 0f, 0.58f, 1f)

/** Drawn, hidden, before the first series is built. */
private val PLACEHOLDER = BalanceHistoryModel.Series(
    range = TimeRange.WEEK,
    currency = one.monero.moneroone.data.model.Currency.USD,
    points = emptyList(),
    chartPoints = emptyList(),
    markers = emptyList(),
    domain = 0.0..1.0,
    tickAxis = ChartTimeAxis.WEEK,
    spokenCount = null,
    isDrawable = false
)
