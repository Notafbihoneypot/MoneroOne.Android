package one.monero.moneroone.ui.screens.chart

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.toSize
import androidx.compose.ui.unit.Density
import androidx.test.ext.junit.runners.AndroidJUnit4
import one.monero.moneroone.core.locale.tr
import one.monero.moneroone.data.model.CurrentPrice
import one.monero.moneroone.data.model.PriceDataPoint
import one.monero.moneroone.ui.components.ChartPoint
import one.monero.moneroone.ui.screens.wallet.BalanceLedger
import one.monero.moneroone.ui.theme.MoneroOneTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ChartScreenTest {
    @get:Rule val rule = createComposeRule()

    private val rangeRequests = mutableListOf<TimeRange>()
    private var refreshRequests = 0
    private val state = mutableStateOf(fixture())

    @Test
    fun switchingModesKeepsGeometryRangeAndOnlyExposesTheActiveChart() {
        showChart()
        rule.onNodeWithText(tr("1M")).performClick()
        val bounds = layoutBounds()
        repeat(3) {
            rule.onNodeWithText(tr("Price")).performClick()
            assertEquals(bounds, layoutBounds())
            rule.onNodeWithText(tr("1M")).assertIsSelected()
            rule.onAllNodesWithContentDescription("${tr("Portfolio")} chart, ${tr("past month")}").assertCountEquals(0)
            rule.onNodeWithContentDescription("${tr("Monero price")} chart, ${tr("past month")}").assertExists()
            rule.onNodeWithText(tr("Portfolio")).performClick()
            assertEquals(bounds, layoutBounds())
            rule.onNodeWithText(tr("1M")).assertIsSelected()
            rule.onAllNodesWithContentDescription("${tr("Monero price")} chart, ${tr("past month")}").assertCountEquals(0)
            rule.onNodeWithContentDescription("${tr("Portfolio")} chart, ${tr("past month")}").assertExists()
        }
        assertEquals(listOf(TimeRange.MONTH), rangeRequests)
        assertEquals(0, refreshRequests)
    }

    @Test
    fun loadingAndMissingHistoryKeepBothModesAligned() {
        state.value = ChartUiState(isLoading = true)
        showChart(balance = 0)
        val bounds = layoutBounds()
        rule.onNodeWithText(tr("Price")).performClick()
        assertEquals(bounds, layoutBounds())
        rule.runOnIdle { state.value = ChartUiState(isLoading = false) }
        assertEquals(bounds, layoutBounds())
        rule.onNodeWithText(tr("Portfolio")).performClick()
        assertEquals(bounds, layoutBounds())
        rule.onNodeWithText(tr("Add XMR to see portfolio chart")).assertExists()
        rule.onNodeWithText(tr("Statistics")).assertExists()
    }

    @Test
    fun largeTypeAndLongPortfolioValuesKeepTheSameLayout() {
        showChart(balance = 123456789012345678, fontScale = 1.5f)
        val bounds = layoutBounds()
        rule.onNodeWithText(tr("Price")).performClick()
        assertEquals(bounds, layoutBounds())
        rule.onNodeWithText(tr("Portfolio")).performClick()
        assertEquals(bounds, layoutBounds())
    }

    private fun layoutBounds(): List<Rect> =
        listOf("chart-value", "chart-range", "chart-plot", "chart-statistics").map {
            rule.onNodeWithTag(it).fetchSemanticsNode().let { node -> Rect(node.positionInRoot, node.size.toSize()) }
        }

    private fun showChart(balance: Long = 2000000000000, fontScale: Float = 1f) {
        val ledger = BalanceLedger(balance, emptyList())
        rule.setContent {
            MoneroOneTheme {
                val density = LocalDensity.current
                CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale)) {
                    var mode by remember { mutableStateOf(ChartMode.PORTFOLIO) }
                    ChartContent(
                        uiState = state.value,
                        ledger = ledger,
                        balanceLabel = if (balance > 2000000000000) "123456.789012 XMR" else "2.000000 XMR",
                        selectedMode = mode,
                        onModeSelected = { mode = it },
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
