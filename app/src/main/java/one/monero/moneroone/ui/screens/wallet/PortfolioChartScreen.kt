package one.monero.moneroone.ui.screens.wallet

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
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.horizontalsystems.monerokit.model.TransactionInfo
import one.monero.moneroone.core.wallet.WalletViewModel
import one.monero.moneroone.data.util.ChartMath
import one.monero.moneroone.data.util.MoneyFormat
import one.monero.moneroone.ui.components.ChartAxes
import one.monero.moneroone.ui.components.ChartPoint
import one.monero.moneroone.ui.components.ChartSpeech
import one.monero.moneroone.ui.components.GlassCard
import one.monero.moneroone.ui.components.GlassSegmentedPicker
import one.monero.moneroone.ui.components.SampledLineChart
import one.monero.moneroone.ui.components.rememberChartDateFormats
import one.monero.moneroone.ui.screens.chart.ChartTimeAxis
import one.monero.moneroone.ui.screens.chart.ChartViewModel
import one.monero.moneroone.ui.screens.chart.TimeRange
import one.monero.moneroone.ui.theme.ErrorRed
import one.monero.moneroone.ui.theme.MoneroOrange
import one.monero.moneroone.ui.theme.MoneroTheme
import one.monero.moneroone.ui.theme.SuccessGreen
import one.monero.moneroone.ui.theme.TabularFigures
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PortfolioChartScreen(
    walletViewModel: WalletViewModel,
    onBack: () -> Unit,
    chartViewModel: ChartViewModel
) {
    val walletState by walletViewModel.walletState.collectAsState()
    val chartUiState by chartViewModel.uiState.collectAsState()
    val formats = rememberChartDateFormats()
    val range = chartUiState.range
    val currency = chartUiState.currency
    val rate = chartUiState.rate
    val balance = walletState.balance.all

    // The balance now and every transaction that led to it; the kit holds
    // the wallet's whole history, so the ledger reaches back to the start.
    val ledger = remember(balance, walletState.transactions) {
        BalanceLedger(balance, walletState.transactions.mapNotNull { it.toBalanceChange() })
    }
    // The XMR held at each real price sample times that sample's price, in
    // the selected currency. Nothing is drawn until that currency's rate is in.
    val portfolio = remember(chartUiState.seriesUsd, rate, ledger, range) {
        if (rate == null) emptyList()
        else PortfolioHistory.points(chartUiState.seriesUsd, rate, ledger, startAtFirstHolding = range == TimeRange.ALL)
    }
    val points = remember(portfolio) { portfolio.map { ChartPoint(it.timestamp, it.value) } }
    val transactionCount = remember(portfolio) { PortfolioHistory.spokenCount(portfolio) }
    val markers = remember(portfolio, currency, formats) {
        PortfolioHistory.markers(
            portfolio,
            formatValue = { MoneyFormat.format(it, currency, fractionDigits = 2) },
            formatWhen = { formats.dateTime(it) }
        )
    }
    // Keyed on the points, as the chart keys its own selection.
    var selectedIndex by remember(points) { mutableStateOf<Int?>(null) }
    val selected = selectedIndex?.let { portfolio.getOrNull(it) }

    LaunchedEffect(Unit) { chartViewModel.showRange(chartViewModel.uiState.value.range) }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        contentWindowInsets = WindowInsets(0.dp),
        topBar = {
            TopAppBar(
                title = { Text("Portfolio", style = MaterialTheme.typography.titleMedium) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = Color.Transparent
                ),
                modifier = Modifier.windowInsetsPadding(WindowInsets.statusBars)
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp)
                .verticalScroll(rememberScrollState())
        ) {
            Spacer(modifier = Modifier.height(8.dp))

            // Portfolio value display card. Every line keeps its height, so
            // the chart below never moves when a scrub starts or a value comes in.
            GlassCard(
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(20.dp)) {
                    Text(
                        text = selected?.let { formats.scrubLabel(range.axis, PortfolioHistory.eventTime(it)) }
                            ?: "Current Value",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )

                    Spacer(modifier = Modifier.height(4.dp))

                    // The sample under the finger, else the balance at the live
                    // price, else the balance at the last sample.
                    val currentValue = chartUiState.currentPrice?.let { balance / ATOMIC_UNITS_PER_XMR * it.price }
                    val displayValue = selected?.value ?: currentValue ?: portfolio.lastOrNull()?.value
                    if (displayValue != null) {
                        val value = MoneyFormat.format(displayValue, currency)
                        Text(
                            text = value,
                            style = MaterialTheme.typography.displayMedium.copy(fontFeatureSettings = TabularFigures),
                            modifier = Modifier.semantics { contentDescription = "Portfolio value, $value" }
                        )
                    } else {
                        Text(
                            text = "Loading...",
                            style = MaterialTheme.typography.displayMedium,
                            color = MoneroTheme.colors.labelTertiary
                        )
                    }

                    Spacer(modifier = Modifier.height(4.dp))

                    Text(
                        text = selected?.let { PortfolioHistory.scrubDetail(it) }
                            ?: "${walletViewModel.formatXmr(balance)} XMR",
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )

                    Spacer(modifier = Modifier.height(8.dp))

                    val change = remember(portfolio) { ChartMath.percentChange(portfolio) { it.value } }
                    if (change != null) {
                        val up = change >= 0
                        Text(
                            text = "${if (up) "+" else ""}${String.format(Locale.US, "%.2f", change)}% (${range.label})",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold,
                            color = if (up) SuccessGreen else ErrorRed,
                            modifier = Modifier.clearAndSetSemantics {
                                contentDescription = "Change, ${range.axis.spokenSpan}"
                                stateDescription = ChartSpeech.spokenChange(change)
                            }
                        )
                    } else {
                        Text(
                            text = "",
                            style = MaterialTheme.typography.titleMedium,
                            modifier = Modifier.clearAndSetSemantics { }
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Time range selector
            GlassSegmentedPicker(
                options = TimeRange.entries.toList(),
                selectedOption = range,
                onOptionSelected = { chartViewModel.showRange(it) },
                modifier = Modifier.fillMaxWidth(),
                labelSelector = { it.label }
            )

            Spacer(modifier = Modifier.height(16.dp))

            // Chart area
            GlassCard(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(220.dp)
            ) {
                if (chartUiState.isLoading && points.isEmpty()) {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center
                    ) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(32.dp),
                            color = MoneroOrange,
                            strokeWidth = 3.dp
                        )
                    }
                } else if (points.isNotEmpty()) {
                    val domain = remember(points) { ChartMath.portfolioYDomain(points.map { it.value }) }
                    // "All" starts when the wallet first held XMR, so its span runs from weeks to years.
                    val axis = if (range == TimeRange.ALL) {
                        ChartTimeAxis.fitting(points.last().timestamp - points.first().timestamp)
                    } else {
                        range.axis
                    }
                    SampledLineChart(
                        points = points,
                        domain = domain,
                        axes = ChartAxes(axis, formats, currency),
                        speech = ChartSpeech(
                            title = "Portfolio",
                            span = range.axis.spokenSpan,
                            currency = currency,
                            note = transactionCount,
                            markerName = "transaction"
                        ),
                        onSelect = { selectedIndex = it },
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(16.dp),
                        markers = markers
                    )
                } else {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = "Unable to load chart",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Portfolio stats (High/Low)
            if (points.isNotEmpty()) {
                GlassCard(
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        val high = remember(points) { points.maxOf { it.value } }
                        val low = remember(points) { points.minOf { it.value } }

                        StatItem(
                            label = "${range.label} High",
                            value = MoneyFormat.format(high, currency),
                            valueColor = SuccessGreen
                        )
                        StatItem(
                            label = "${range.label} Low",
                            value = MoneyFormat.format(low, currency),
                            valueColor = ErrorRed
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(32.dp))
        }
    }
}

/**
 * The transaction as it moved the balance the app shows. The kit's balance
 * leaves pending incoming out, as wallet2's does (iOS software wallets).
 */
private fun TransactionInfo.toBalanceChange(): BalanceChange? = BalanceChange.of(
    id = hash,
    incoming = direction == TransactionInfo.Direction.Direction_In,
    isPending = isPending,
    isFailed = isFailed,
    amount = amount,
    fee = fee,
    timestampMs = timestamp * 1000
)

private const val ATOMIC_UNITS_PER_XMR = 1e12

@Composable
private fun StatItem(
    label: String,
    value: String,
    valueColor: Color = MaterialTheme.colorScheme.onSurface
) {
    Column(modifier = Modifier.clearAndSetSemantics { contentDescription = "$label, $value" }) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = value,
            style = MaterialTheme.typography.titleLarge.copy(fontFeatureSettings = TabularFigures),
            color = valueColor
        )
    }
}
