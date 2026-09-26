package one.monero.moneroone.ui.screens.chart

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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.launch
import one.monero.moneroone.data.util.ChartMath
import one.monero.moneroone.data.util.MoneyFormat
import one.monero.moneroone.ui.components.ChartAxes
import one.monero.moneroone.ui.components.ChartSpeech
import one.monero.moneroone.ui.components.GlassButton
import one.monero.moneroone.ui.components.GlassCard
import one.monero.moneroone.ui.components.GlassSegmentedPicker
import one.monero.moneroone.ui.components.MoneroRefreshIndicator
import one.monero.moneroone.ui.components.SampledLineChart
import one.monero.moneroone.ui.components.rememberChartDateFormats
import one.monero.moneroone.ui.theme.ErrorRed
import one.monero.moneroone.ui.theme.MoneroOrange
import one.monero.moneroone.ui.theme.MoneroTheme
import one.monero.moneroone.ui.theme.SuccessGreen
import one.monero.moneroone.ui.theme.TabularFigures
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChartScreen(
    viewModel: ChartViewModel = viewModel(),
    onPriceAlertsClick: () -> Unit = {}
) {
    val uiState by viewModel.uiState.collectAsState()
    val scope = rememberCoroutineScope()
    var isRefreshing by remember { mutableStateOf(false) }
    val formats = rememberChartDateFormats()
    val range = uiState.range
    val currency = uiState.currency
    val points = uiState.points
    // Keyed on the points, as the chart keys its own selection.
    var selectedIndex by remember(points) { mutableStateOf<Int?>(null) }
    val selected = selectedIndex?.let { points.getOrNull(it) }

    LaunchedEffect(Unit) { viewModel.showRange(viewModel.uiState.value.range) }

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
        onRefresh = {
            scope.launch {
                isRefreshing = true
                try {
                    viewModel.refreshNow()
                } finally {
                    isRefreshing = false
                }
            }
        },
        modifier = Modifier
            .fillMaxSize()
            .windowInsetsPadding(WindowInsets.statusBars)
    ) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp)
            .verticalScroll(rememberScrollState())
    ) {
        Spacer(modifier = Modifier.height(16.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "Monero Price",
                style = MaterialTheme.typography.headlineSmall
            )
            // iOS: a round glass toolbar button with a label-color bell.
            GlassButton(
                onClick = onPriceAlertsClick,
                modifier = Modifier.size(44.dp)
            ) {
                Icon(
                    imageVector = Icons.Outlined.Notifications,
                    contentDescription = "Price Alerts",
                    tint = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.size(24.dp)
                )
            }
        }

        Spacer(modifier = Modifier.height(24.dp))

        // Price display card. Every line keeps its height, so the chart
        // below never moves when a scrub starts or a value comes in.
        GlassCard(
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(20.dp)) {
                Text(
                    text = selected?.let { formats.scrubLabel(range.axis, it.timestamp) } ?: "Current Price",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )

                Spacer(modifier = Modifier.height(4.dp))

                // The sample under the finger, else the live price, else the last sample.
                val displayPrice = selected?.value ?: uiState.currentPrice?.price ?: uiState.close
                if (displayPrice != null) {
                    val price = MoneyFormat.format(displayPrice, currency)
                    Text(
                        text = price,
                        style = MaterialTheme.typography.displayMedium.copy(fontFeatureSettings = TabularFigures),
                        modifier = Modifier.semantics { contentDescription = "Current Monero price, $price" }
                    )
                } else {
                    Text(
                        text = "Loading...",
                        style = MaterialTheme.typography.displayMedium,
                        color = MoneroTheme.colors.labelTertiary
                    )
                }

                Spacer(modifier = Modifier.height(8.dp))

                val change = uiState.rangeChange
                if (change != null) {
                    val up = change >= 0
                    val percent = "${if (up) "+" else ""}${String.format(Locale.US, "%.2f", change)}%"
                    Text(
                        text = "$percent (${range.label})",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = if (up) SuccessGreen else ErrorRed,
                        modifier = Modifier.semantics {
                            contentDescription = "Price change ${range.label}, ${ChartSpeech.spokenChange(change)}"
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
            onOptionSelected = { viewModel.showRange(it) },
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
            if (uiState.isLoading) {
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
                val domain = remember(points) { ChartMath.chartYDomain(points.map { it.value }) }
                SampledLineChart(
                    points = points,
                    domain = domain,
                    axes = ChartAxes(range.axis, formats, currency),
                    speech = ChartSpeech("Monero price", range.axis.spokenSpan, currency),
                    onSelect = { selectedIndex = it },
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(16.dp)
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

        // Price stats (High/Low/Open/Close), over the samples and the live price
        if (points.isNotEmpty()) {
            GlassCard(
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        StatItem(
                            label = "${range.label} High",
                            value = uiState.high?.let { MoneyFormat.format(it, currency) } ?: "-",
                            valueColor = SuccessGreen
                        )
                        StatItem(
                            label = "${range.label} Low",
                            value = uiState.low?.let { MoneyFormat.format(it, currency) } ?: "-",
                            valueColor = ErrorRed
                        )
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        StatItem(
                            label = "Open",
                            value = uiState.open?.let { MoneyFormat.format(it, currency) } ?: "-"
                        )
                        StatItem(
                            label = "Close",
                            value = uiState.close?.let { MoneyFormat.format(it, currency) } ?: "-"
                        )
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(32.dp))
    }
    } // PullToRefreshBox
}

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
