package one.monero.moneroone.ui.screens.chart

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.toSize
import androidx.test.ext.junit.runners.AndroidJUnit4
import one.monero.moneroone.core.locale.tr
import one.monero.moneroone.data.model.CurrentPrice
import one.monero.moneroone.data.model.PriceDataPoint
import one.monero.moneroone.ui.components.ChartPoint
import one.monero.moneroone.ui.theme.MoneroOneTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** The Price tab: the price alone, with no Chart/Portfolio switch (iOS PriceView). */
@RunWith(AndroidJUnit4::class)
class ChartScreenTest {
    @get:Rule val rule = createComposeRule()

    private val rangeRequests = mutableListOf<TimeRange>()
    private var refreshRequests = 0
    private val state = mutableStateOf(fixture())

    @Test
    fun rangesKeepTheLayoutAndShowOnlyThePriceChart() {
        showChart()
        val bounds = layoutBounds()
        rule.onNodeWithText(tr("1M")).performClick()
        rule.onNodeWithText(tr("1M")).assertIsSelected()
        assertEquals(bounds, layoutBounds())
        rule.onNodeWithContentDescription(tr("%s chart, %s", tr("Monero price"), tr("past month"))).assertExists()
        rule.onNodeWithText(tr("Price")).assertExists()
        rule.onAllNodesWithText(tr("Portfolio")).assertCountEquals(0)
        rule.onNodeWithText(tr("1Y")).performClick()
        assertEquals(bounds, layoutBounds())
        assertEquals(listOf(TimeRange.MONTH, TimeRange.YEAR), rangeRequests)
        assertEquals(0, refreshRequests)
    }

    @Test
    fun loadingAndMissingHistoryKeepTheLayout() {
        state.value = ChartUiState(isLoading = true)
        showChart()
        val bounds = layoutBounds()
        rule.runOnIdle { state.value = ChartUiState(isLoading = false) }
        assertEquals(bounds, layoutBounds())
        rule.onNodeWithText(tr("Unable to load chart")).assertExists()
        rule.onNodeWithText(tr("Statistics")).assertExists()
    }

    @Test
    fun largeTypeKeepsTheLayoutAcrossRanges() {
        showChart(fontScale = 1.5f)
        val bounds = layoutBounds()
        rule.onNodeWithText(tr("24H")).performClick()
        assertEquals(bounds, layoutBounds())
        rule.onNodeWithText(tr("All")).performClick()
        assertEquals(bounds, layoutBounds())
    }

    private fun layoutBounds(): List<Rect> =
        listOf("chart-value", "chart-range", "chart-plot", "chart-statistics").map {
            rule.onNodeWithTag(it).fetchSemanticsNode().let { node -> Rect(node.positionInRoot, node.size.toSize()) }
        }

    private fun showChart(fontScale: Float = 1f) {
        rule.setContent {
            MoneroOneTheme {
                val density = LocalDensity.current
                CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale)) {
                    ChartContent(
                        uiState = state.value,
                        onRangeSelected = {
                            rangeRequests += it
                            state.value = state.value.copy(range = it)
                        },
                        onPriceAlertsClick = {},
                        isRefreshing = false,
                        onRefresh = { refreshRequests++ }
                    )
                }
            }
        }
    }

    private fun fixture(): ChartUiState {
        val end = 1790611200000L
        val samples = List(50) { index -> PriceDataPoint(end - (49 - index) * 12096000, 150.0 + index) }
        return ChartUiState(
            seriesUsd = samples,
            points = samples.map { ChartPoint(it.timestamp, it.price) },
            currentPrice = CurrentPrice(199.0, 2.5, lastUpdated = end),
            isLoading = false,
            rangeChange = (199.0 - 150.0) / 150.0 * 100,
            close = 199.0
        )
    }
}
