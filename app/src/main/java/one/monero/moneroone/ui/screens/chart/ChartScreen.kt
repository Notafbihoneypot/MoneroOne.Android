package one.monero.moneroone.ui.screens.chart

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
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
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import one.monero.moneroone.core.locale.tr
import one.monero.moneroone.data.util.ChartMath
import one.monero.moneroone.data.util.MoneyFormat
import one.monero.moneroone.ui.components.CapsuleShape
import one.monero.moneroone.ui.components.ChartAxes
import one.monero.moneroone.ui.components.ChartSpeech
import one.monero.moneroone.ui.components.GlassButton
import one.monero.moneroone.ui.components.GlassCard
import one.monero.moneroone.ui.components.GlassSegmentedPicker
import one.monero.moneroone.ui.components.MoneroRefreshIndicator
import one.monero.moneroone.ui.components.Motion
import one.monero.moneroone.ui.components.RollingText
import one.monero.moneroone.ui.components.SampledLineChart
import one.monero.moneroone.ui.components.ShrinkToFitText
import one.monero.moneroone.ui.components.rememberChartDateFormats
import one.monero.moneroone.ui.theme.ErrorRed
import one.monero.moneroone.ui.theme.MoneroOrange
import one.monero.moneroone.ui.theme.SuccessGreen
import one.monero.moneroone.ui.theme.TabularFigures
import java.util.Locale

/**
 * The Price tab: the Monero price over a range, with a touch-and-hold
 * readout, its statistics and the price alerts. Balance history lives in
 * the wallet's balance card.
 */
@Composable
fun ChartScreen(
    viewModel: ChartViewModel,
    onPriceAlertsClick: () -> Unit = {}
) {
    val uiState by viewModel.uiState.collectAsState()
    val scope = rememberCoroutineScope()
    var isRefreshing by remember { mutableStateOf(false) }

    LaunchedEffect(viewModel) { viewModel.showRange(viewModel.uiState.value.range) }

    ChartContent(
        uiState = uiState,
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ChartContent(
    uiState: ChartUiState,
    onRangeSelected: (TimeRange) -> Unit,
    onPriceAlertsClick: () -> Unit,
    isRefreshing: Boolean,
    onRefresh: () -> Unit
) {
    val formats = rememberChartDateFormats()
    val range = uiState.range
    val currency = uiState.currency
    val points = uiState.points
    // New points drop the readout; the chart keys its own on the same list.
    var selectedIndex by remember(points) { mutableStateOf<Int?>(null) }
    val selected = selectedIndex?.let { points.getOrNull(it) }
    val currentValue = uiState.currentPrice?.price ?: uiState.close
    val displayValue = selected?.value ?: currentValue
    val value = displayValue?.let { MoneyFormat.format(it, currency) } ?: tr("Loading...")
    val scrubLabel = selected?.let { formats.scrubLabel(range.axis, it.timestamp) }
    val high = remember(points) { points.maxOfOrNull { it.value } }
    val low = remember(points) { points.minOfOrNull { it.value } }
    val domain = remember(points) { ChartMath.chartYDomain(points.map { it.value }) }

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
                Text(
                    tr("Price"),
                    style = MaterialTheme.typography.headlineSmall,
                    modifier = Modifier.semantics { heading() }
                )
                GlassButton(onClick = onPriceAlertsClick, modifier = Modifier.size(44.dp)) {
                    Icon(
                        Icons.Outlined.Notifications,
                        contentDescription = tr("Price alerts"),
                        tint = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.size(24.dp)
                    )
                }
            }
            ChartValueCard(
                scrubLabel = scrubLabel,
                value = value,
                valueDescription = tr("Current Monero price, %s", value),
                change = uiState.rangeChange,
                range = range
            )
            GlassSegmentedPicker(
                options = TimeRange.entries,
                selectedOption = range,
                onOptionSelected = onRangeSelected,
                modifier = Modifier.fillMaxWidth().testTag("chart-range"),
                accessibilityLabel = { tr(it.axis.spokenName) },
                labelSelector = { tr(it.label) }
            )
            GlassCard(Modifier.fillMaxWidth().height(220.dp).testTag("chart-plot")) {
                if (points.isNotEmpty()) {
                    SampledLineChart(
                        points = points,
                        domain = domain,
                        axes = ChartAxes(range.axis, formats, currency),
                        speech = ChartSpeech(
                            title = tr("Monero price"),
                            span = tr(range.axis.spokenSpan),
                            currency = currency
                        ),
                        onSelect = { selectedIndex = it },
                        modifier = Modifier.fillMaxSize().padding(16.dp),
                        axisLabelWidth = 64.dp
                    )
                } else {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        if (uiState.isLoading) {
                            CircularProgressIndicator(Modifier.size(32.dp), color = MoneroOrange, strokeWidth = 3.dp)
                        } else {
                            Text(
                                tr("Unable to load chart"),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }
            Column(verticalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.testTag("chart-statistics")) {
                Text(tr("Statistics"), style = MaterialTheme.typography.titleMedium)
                GlassCard(Modifier.fillMaxWidth()) {
                    Row(Modifier.fillMaxWidth().padding(16.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                        ChartStat(
                            label = tr("%s High", tr(range.label)),
                            value = high?.let { MoneyFormat.format(it, currency) } ?: "—",
                            color = SuccessGreen,
                            modifier = Modifier.weight(1f)
                        )
                        ChartStat(
                            label = tr("%s Low", tr(range.label)),
                            value = low?.let { MoneyFormat.format(it, currency) } ?: "—",
                            color = ErrorRed,
                            modifier = Modifier.weight(1f)
                        )
                    }
                }
                Text(
                    text = uiState.currentPrice?.lastUpdated?.let { "${tr("Last Updated")}: ${formats.dateTime(it)}" } ?: "",
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
    /** The time of the sample under the finger; null shows Current Price. */
    scrubLabel: String?,
    value: String,
    valueDescription: String,
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
            // iOS fades the scrub label in and out as a touch starts and
            // ends; from sample to sample it changes at once.
            AnimatedContent(
                targetState = scrubLabel,
                modifier = Modifier.fillMaxWidth(),
                transitionSpec = {
                    val fade = tween<Float>(SCRUB_LABEL_FADE_MS, easing = Motion.EaseInOut)
                    (fadeIn(fade) togetherWith fadeOut(fade)).using(SizeTransform(clip = false))
                },
                contentAlignment = Alignment.CenterStart,
                contentKey = { it != null },
                label = "priceCaption"
            ) { label ->
                Text(
                    label ?: tr("Current Price"),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Spacer(Modifier.height(4.dp))
            Box(Modifier.fillMaxWidth().height(valueHeight), contentAlignment = Alignment.CenterStart) {
                // iOS .contentTransition(.numericText()) with .easeInOut(duration: 0.1).
                RollingText(
                    text = value,
                    style = valueStyle,
                    modifier = Modifier.fillMaxWidth().clearAndSetSemantics { contentDescription = valueDescription },
                    durationMillis = PRICE_DIGIT_MS,
                    minScale = 0.5f
                )
            }
            Spacer(Modifier.height(8.dp))
            Row(
                Modifier.fillMaxWidth().height(detailHeight),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (change != null) {
                    val color = if (change >= 0) SuccessGreen else ErrorRed
                    // iOS fades the change badge out while a finger scrubs, as the scrub label fades in.
                    val fade = tween<Float>(SCRUB_LABEL_FADE_MS, easing = Motion.EaseInOut)
                    AnimatedVisibility(visible = scrubLabel == null, enter = fadeIn(fade), exit = fadeOut(fade)) {
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
                                    contentDescription = tr("Change, %s", tr(range.axis.spokenSpan)) + ", " + ChartSpeech.spokenChange(change)
                                }
                        )
                    }
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

/** iOS rolls the price in 0.1 s. */
private const val PRICE_DIGIT_MS = 100

/** iOS fades the scrub label in and out in 0.15 s. */
private const val SCRUB_LABEL_FADE_MS = 150
