package one.monero.moneroone.ui.screens.chart

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Notifications
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import one.monero.moneroone.core.wallet.WalletViewModel
import one.monero.moneroone.data.util.ChartMath
import one.monero.moneroone.data.util.MoneyFormat
import one.monero.moneroone.ui.components.CapsuleShape
import one.monero.moneroone.ui.components.ChartAxes
import one.monero.moneroone.ui.components.ChartPoint
import one.monero.moneroone.ui.components.ChartSpeech
import one.monero.moneroone.ui.components.GlassButton
import one.monero.moneroone.ui.components.GlassCard
import one.monero.moneroone.ui.components.GlassSegmentedPicker
import one.monero.moneroone.ui.components.MoneroRefreshIndicator
import one.monero.moneroone.ui.components.SampledLineChart
import one.monero.moneroone.ui.components.ShrinkToFitText
import one.monero.moneroone.ui.components.rememberChartDateFormats
import one.monero.moneroone.ui.screens.wallet.BalanceLedger
import one.monero.moneroone.ui.screens.wallet.PortfolioHistory
import one.monero.moneroone.ui.screens.wallet.rememberBalanceLedger
import one.monero.moneroone.ui.theme.ErrorRed
import one.monero.moneroone.ui.theme.MoneroOrange
import one.monero.moneroone.ui.theme.SuccessGreen
import one.monero.moneroone.ui.theme.TabularFigures
import java.util.Locale

enum class ChartMode(val label: String) {
    PORTFOLIO("Portfolio"), PRICE("Price")
}

@Composable
fun ChartScreen(
    viewModel: ChartViewModel,
    walletViewModel: WalletViewModel,
    selectedMode: ChartMode,
    onModeSelected: (ChartMode) -> Unit,
    onPriceAlertsClick: () -> Unit = {}
) {
    val uiState by viewModel.uiState.collectAsState()
    val walletState by walletViewModel.walletState.collectAsState()
    val walletSessionId by walletViewModel.walletSessionId.collectAsState()
    val ledger = rememberBalanceLedger(walletState.balance.all, walletState.transactions, walletSessionId)
    val scope = rememberCoroutineScope()
    var isRefreshing by remember { mutableStateOf(false) }

    // Changing modes only changes what is drawn; it never requests history again.
    LaunchedEffect(viewModel) { viewModel.showRange(viewModel.uiState.value.range) }

    key(walletSessionId) {
        ChartContent(
            uiState = uiState,
            ledger = ledger,
            balanceLabel = "${walletViewModel.formatXmr(walletState.balance.all)} XMR",
            selectedMode = selectedMode,
            onModeSelected = onModeSelected,
            onRangeSelected = viewModel::showRange,
            onPriceAlertsClick = onPriceAlertsClick,
            isRefreshing = isRefreshing,
            onRefresh = {
                if (!isRefreshing) scope.launch {
                    isRefreshing = true
                    try {
                        viewModel.refreshNow()
                    } finally {
                        isRefreshing = false
                    }
                }
            }
        )
    }
}

/** One layout for both modes, with portfolio calculations retained across switches. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ChartContent(
    uiState: ChartUiState,
    ledger: BalanceLedger,
    balanceLabel: String,
    selectedMode: ChartMode,
    onModeSelected: (ChartMode) -> Unit,
    onRangeSelected: (TimeRange) -> Unit,
    onPriceAlertsClick: () -> Unit,
    isRefreshing: Boolean,
    onRefresh: () -> Unit
) {
    val formats = rememberChartDateFormats()
    val range = uiState.range
    val currency = uiState.currency
    val portfolio = remember(uiState.seriesUsd, uiState.rate, ledger, range) {
        uiState.rate?.let { rate ->
            PortfolioHistory.points(uiState.seriesUsd, rate, ledger, startAtFirstHolding = range == TimeRange.ALL)
        }.orEmpty()
    }
    val portfolioPoints = remember(portfolio) { portfolio.map { ChartPoint(it.timestamp, it.value) } }
    val portfolioChange = remember(portfolio) { ChartMath.percentChange(portfolio) { it.value } }
    val transactionCount = remember(portfolio) { PortfolioHistory.spokenCount(portfolio) }
    val markers = remember(portfolio, currency, formats) {
        PortfolioHistory.markers(
            portfolio,
            formatValue = { MoneyFormat.format(it, currency, fractionDigits = 2) },
            formatWhen = { formats.dateTime(it) }
        )
    }
    val isPortfolio = selectedMode == ChartMode.PORTFOLIO
    val points = if (isPortfolio) portfolioPoints else uiState.points
    var selectedIndex by remember(selectedMode, points) { mutableStateOf<Int?>(null) }
    val selected = selectedIndex?.let { points.getOrNull(it) }
    val selectedPortfolio = if (isPortfolio) selectedIndex?.let { portfolio.getOrNull(it) } else null
    val currentValue = if (isPortfolio) {
        if (ledger.balance == 0L) 0.0
        else uiState.currentPrice?.let { ledger.balance / 1e12 * it.price } ?: portfolio.lastOrNull()?.value
    } else {
        uiState.currentPrice?.price ?: uiState.close
    }
    val displayValue = selected?.value ?: currentValue
    val value = displayValue?.let { MoneyFormat.format(it, currency) } ?: "Loading..."
    val caption = selected?.let {
        formats.scrubLabel(range.axis, selectedPortfolio?.let(PortfolioHistory::eventTime) ?: it.timestamp)
    } ?: if (isPortfolio) "Current Value" else "Current Price"
    val details = if (isPortfolio) selectedPortfolio?.let(PortfolioHistory::scrubDetail) ?: balanceLabel else null
    val change = if (isPortfolio) portfolioChange else uiState.rangeChange
    val high = remember(points) { points.maxOfOrNull { it.value } }
    val low = remember(points) { points.minOfOrNull { it.value } }
    val domain = remember(points, isPortfolio) {
        if (isPortfolio) ChartMath.portfolioYDomain(points.map { it.value })
        else ChartMath.chartYDomain(points.map { it.value })
    }
    val axis = if (isPortfolio && range == TimeRange.ALL && points.isNotEmpty()) {
        ChartTimeAxis.fitting(points.last().timestamp - points.first().timestamp)
    } else range.axis

    val refreshState = rememberPullToRefreshState()
    PullToRefreshBox(
        isRefreshing = isRefreshing,
        state = refreshState,
        indicator = {
            MoneroRefreshIndicator(
                state = refreshState,
                isRefreshing = isRefreshing,
                modifier = Modifier.align(Alignment.TopCenter)
            )
        },
        onRefresh = onRefresh,
        modifier = Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.statusBars)
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Spacer(Modifier.height(0.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("Chart", style = MaterialTheme.typography.headlineSmall)
                GlassButton(onClick = onPriceAlertsClick, modifier = Modifier.size(44.dp)) {
                    Icon(
                        Icons.Outlined.Notifications,
                        contentDescription = "Price Alerts",
                        tint = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.size(24.dp)
                    )
                }
            }
            GlassSegmentedPicker(
                options = ChartMode.entries.toList(),
                selectedOption = selectedMode,
                onOptionSelected = onModeSelected,
                modifier = Modifier.fillMaxWidth().testTag("chart-mode"),
                labelSelector = { it.label }
            )
            ChartValueCard(
                caption = caption,
                value = value,
                valueDescription = String.format(if (isPortfolio) "Portfolio value, %s" else "Current Monero price, %s", value),
                details = details,
                change = change,
                range = range
            )
            GlassSegmentedPicker(
                options = TimeRange.entries.toList(),
                selectedOption = range,
                onOptionSelected = onRangeSelected,
                modifier = Modifier.fillMaxWidth().testTag("chart-range"),
                accessibilityLabel = { it.axis.spokenSpan },
                labelSelector = { it.label }
            )
            GlassCard(Modifier.fillMaxWidth().height(220.dp).testTag("chart-plot")) {
                if (points.isNotEmpty()) {
                    // Reset any pinned transaction when the mode changes, while the
                    // cached history and surrounding layout remain in composition.
                    key(selectedMode) {
                        SampledLineChart(
                            points = points,
                            domain = domain,
                            axes = ChartAxes(axis, formats, currency),
                            speech = ChartSpeech(
                                title = if (isPortfolio) "Portfolio" else "Monero price",
                                span = range.axis.spokenSpan,
                                currency = currency,
                                note = if (isPortfolio) transactionCount else null,
                                markerName = if (isPortfolio) "transaction" else "marker"
                            ),
                            onSelect = { selectedIndex = it },
                            modifier = Modifier.fillMaxSize().padding(16.dp),
                            markers = if (isPortfolio) markers else emptyList(),
                            axisLabelWidth = 64.dp
                        )
                    }
                } else {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        if (uiState.isLoading) {
                            CircularProgressIndicator(Modifier.size(32.dp), color = MoneroOrange, strokeWidth = 3.dp)
                        } else {
                            Text(
                                if (isPortfolio && ledger.balance == 0L) "Add XMR to see portfolio chart" else "Unable to load chart",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }
            Column(verticalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.testTag("chart-statistics")) {
                Text("Statistics", style = MaterialTheme.typography.titleMedium)
                GlassCard(Modifier.fillMaxWidth()) {
                    Row(Modifier.fillMaxWidth().padding(16.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                        ChartStat(
                            label = String.format("%s High", range.label),
                            value = high?.let { MoneyFormat.format(it, currency) } ?: "—",
                            color = SuccessGreen,
                            modifier = Modifier.weight(1f)
                        )
                        ChartStat(
                            label = String.format("%s Low", range.label),
                            value = low?.let { MoneyFormat.format(it, currency) } ?: "—",
                            color = ErrorRed,
                            modifier = Modifier.weight(1f)
                        )
                    }
                }
                Text(
                    text = uiState.currentPrice?.lastUpdated?.let { "Last Updated: ${formats.dateTime(it)}" } ?: "",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Spacer(Modifier.height(16.dp))
        }
    }
}

@Composable
private fun ChartValueCard(
    caption: String,
    value: String,
    valueDescription: String,
    details: String?,
    change: Double?,
    range: TimeRange
) {
    val density = LocalDensity.current
    val valueStyle = MaterialTheme.typography.displayMedium.copy(fontFeatureSettings = TabularFigures)
    val detailStyle = MaterialTheme.typography.titleSmall.copy(fontFeatureSettings = TabularFigures)
    val valueHeight = with(density) { (valueStyle.fontSize * 1.25f).toDp() }
    val detailHeight = with(density) { (detailStyle.fontSize * 1.4f).toDp() } + 12.dp
    GlassCard(Modifier.fillMaxWidth().testTag("chart-value")) {
        Column(Modifier.padding(20.dp)) {
            Text(
                caption,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(Modifier.height(4.dp))
            Box(Modifier.fillMaxWidth().height(valueHeight), contentAlignment = Alignment.CenterStart) {
                ShrinkToFitText(
                    text = value,
                    style = valueStyle,
                    modifier = Modifier.fillMaxWidth().clearAndSetSemantics { contentDescription = valueDescription },
                    minScale = 0.5f
                )
            }
            Spacer(Modifier.height(8.dp))
            Row(
                Modifier.fillMaxWidth().height(detailHeight),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (details != null) {
                    ShrinkToFitText(
                        details, style = detailStyle,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.weight(1f), minScale = 0.5f
                    )
                }
                if (change != null) {
                    val color = if (change >= 0) SuccessGreen else ErrorRed
                    Text(
                        text = "${if (change >= 0) "+" else ""}${String.format(Locale.getDefault(), "%.2f", change)}%",
                        style = detailStyle,
                        fontWeight = FontWeight.SemiBold,
                        color = color,
                        maxLines = 1,
                        modifier = Modifier
                            .background(color.copy(alpha = 0.15f), CapsuleShape)
                            .padding(horizontal = 10.dp, vertical = 6.dp)
                            .clearAndSetSemantics {
                                contentDescription = String.format("Change, %s", range.axis.spokenSpan) + ", " + ChartSpeech.spokenChange(change)
                            }
                    )
                }
            }
        }
    }
}

@Composable
private fun ChartStat(label: String, value: String, color: Color, modifier: Modifier = Modifier) {
    Column(modifier.clearAndSetSemantics { contentDescription = "$label, $value" }) {
        Text(
            label, style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis
        )
        Spacer(Modifier.height(4.dp))
        val style = MaterialTheme.typography.titleLarge.copy(fontFeatureSettings = TabularFigures)
        val height = with(LocalDensity.current) { (style.fontSize * 1.4f).toDp() }
        Box(Modifier.fillMaxWidth().height(height), contentAlignment = Alignment.CenterStart) {
            ShrinkToFitText(value, style = style, color = color, minScale = 0.5f)
        }
    }
}
