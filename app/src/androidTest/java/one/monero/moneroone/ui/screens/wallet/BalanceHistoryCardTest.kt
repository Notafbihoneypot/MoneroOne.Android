package one.monero.moneroone.ui.screens.wallet

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.click
import androidx.compose.ui.test.swipeRight
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.horizontalsystems.monerokit.SyncState
import one.monero.moneroone.core.locale.tr
import one.monero.moneroone.data.model.PriceDataPoint
import one.monero.moneroone.data.util.XmrFormat
import one.monero.moneroone.ui.screens.chart.HistoryPrices
import one.monero.moneroone.ui.screens.chart.TimeRange
import one.monero.moneroone.ui.theme.MoneroOneTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.Locale
import kotlin.math.sin

/**
 * The balance card with History open keeps one height in every range and
 * state, while scrubbing too (iOS testCardKeepsOneHeightAcrossRangesAndScrubbing).
 */
@RunWith(AndroidJUnit4::class)
class BalanceHistoryCardTest {
    @get:Rule val rule = createComposeRule()

    private val xmr = 1_000_000_000_000L
    private val hour = 3_600_000L
    private val day = 24 * hour
    private val now = 1_790_611_200_000L

    private fun change(id: String, incoming: Boolean, amount: Long, fee: Long, at: Long) =
        BalanceChange.of(id, incoming, isPending = false, isFailed = false, amount = amount, fee = fee, timestampMs = at)!!

    // 2.49 XMR now: 1 in 200 days ago, 2 in 3 days ago, 0.5 plus a 0.01 fee out yesterday.
    private val ledger = BalanceLedger(
        balance = 2_490_000_000_000L,
        changes = listOf(
            change("a", incoming = true, amount = xmr, fee = 0, at = now - 200 * day),
            change("b", incoming = true, amount = 2 * xmr, fee = 0, at = now - 3 * day - hour / 2),
            change("c", incoming = false, amount = xmr / 2, fee = xmr / 100, at = now - day - hour / 2)
        )
    )

    private fun samples(count: Int, step: Long) =
        List(count) { i -> PriceDataPoint(now - (count - i) * step, 150.0 + 10 * sin(i / 5.0)) }

    private val loaded = HistoryPrices(
        cache = mapOf(
            TimeRange.DAY to samples(24, hour),
            TimeRange.WEEK to samples(168, hour),
            TimeRange.MONTH to samples(30, day),
            TimeRange.YEAR to samples(365, day),
            TimeRange.ALL to samples(260, 7 * day)
        ),
        tip = PriceDataPoint(now, 155.0),
        fetched = TimeRange.entries.toSet()
    )

    private val fetches = mutableListOf<TimeRange>()

    private val state = BalanceHistoryState()
    private val model = BalanceHistoryModel()

    @Test
    fun cardKeepsOneHeightAcrossRangesAndScrubbing() {
        showCard()
        open()
        val expected = cardHeight()
        val week = model.shown
        for (range in listOf(TimeRange.DAY, TimeRange.MONTH, TimeRange.YEAR, TimeRange.ALL, TimeRange.WEEK)) {
            select(range)
            assertEquals("$range", expected, cardHeight())
        }
        // Back on a range it built before, it shows what it kept.
        assertSame(week, model.shown)

        // A tap on the line picks a past moment; a finger sliding along it moves it.
        rule.onNodeWithTag("wallet.historyChart").performTouchInput { click(Offset(width * 0.3f, height / 2f)) }
        rule.waitForIdle()
        val tapped = state.timestamp
        assertNotNull("tap", tapped)
        rule.onNodeWithTag("wallet.historyChart").performTouchInput {
            swipeRight(startX = width * 0.3f, endX = width * 0.6f, durationMillis = 400)
        }
        rule.waitForIdle()
        assertTrue("slide", (state.timestamp ?: 0) > tapped!!)
        assertEquals(expected, cardHeight())
        rule.onNodeWithText(tr("Historical balance")).assertExists()
        rule.onNodeWithContentDescription(tr("Return to current balance and activity")).assertExists()

        // Every past sample, from before the first receive to yesterday.
        val points = model.shown!!.points
        for (point in listOf(points.first(), points[points.size / 2], points[points.size - 2])) {
            rule.runOnIdle { state.timestamp = point.timestamp }
            assertEquals(expected, cardHeight())
        }

        rule.onNodeWithContentDescription(tr("Return to current balance and activity")).performClick()
        rule.runOnIdle { assertNull(state.timestamp) }
        assertEquals(expected, cardHeight())
        rule.onNodeWithText(tr("Historical balance")).assertDoesNotExist()
    }

    @Test
    fun loadingAndEmptyRangesKeepTheHeight() {
        val prices = loaded.copy(
            cache = loaded.cache - TimeRange.YEAR - TimeRange.ALL,
            fetched = setOf(TimeRange.DAY, TimeRange.WEEK, TimeRange.MONTH, TimeRange.YEAR)
        )
        showCard(prices)
        open()
        val expected = cardHeight()

        // Fetched with nothing to draw: says so, at the same height.
        select(TimeRange.YEAR)
        rule.onNodeWithText(tr("History unavailable for this period")).assertExists()
        assertEquals(expected, cardHeight())

        // Still loading: the last line stays up under a spinner.
        rule.onNodeWithText(tr(TimeRange.ALL.label)).performClick()
        rule.waitForIdle()
        assertEquals(TimeRange.YEAR, model.shown?.range)
        rule.onNodeWithContentDescription(tr("Loading balance history…")).assertExists()
        assertEquals(expected, cardHeight())
        assertTrue(TimeRange.ALL in fetches)
    }

    @Test
    fun largeTextKeepsOneHeightAcrossRanges() {
        showCard(fontScale = 1.5f)
        open()
        val expected = cardHeight()
        for (range in listOf(TimeRange.DAY, TimeRange.ALL, TimeRange.WEEK)) {
            select(range)
            assertEquals("$range", expected, cardHeight())
        }
    }

    /**
     * A tap anywhere on the card opens History and closes it back at now,
     * as the History button does (iOS testTappingTheCardTogglesHistory).
     * In the open History a tap is the chart's: the line picks a time, and
     * the date line does nothing.
     */
    @Test
    fun tappingTheCardTogglesHistory() {
        showCard()
        // The Monero logo, at the start of the balance row.
        rule.onNodeWithTag("wallet.balanceValue").performTouchInput { click(Offset(24.dp.toPx(), height / 2f)) }
        rule.waitUntil(5_000) { model.shown?.range == state.range }
        rule.waitForIdle()
        rule.runOnIdle { assertTrue("the logo opens History", state.expanded) }

        rule.onNodeWithTag("wallet.historyChart").performTouchInput { click(Offset(width * 0.4f, height / 2f)) }
        rule.waitForIdle()
        val picked = state.timestamp
        assertNotNull("a tap on the chart picks a time", picked)
        rule.runOnIdle { assertTrue("a tap on the chart leaves History open", state.expanded) }

        rule.onNodeWithTag("wallet.historyDate").performClick()
        rule.runOnIdle {
            assertTrue("a tap on the date line leaves History open", state.expanded)
            assertEquals("a tap on the date line keeps the time", picked, state.timestamp)
        }

        rule.onNodeWithText(tr("Historical balance")).performClick()
        rule.runOnIdle {
            assertFalse("a tap on the status row closes History", state.expanded)
            assertNull("closing returns to now", state.timestamp)
        }

        // The card's bottom margin.
        rule.onNodeWithTag("wallet.balanceCard").performTouchInput { click(Offset(width / 2f, height - 12.dp.toPx())) }
        rule.runOnIdle { assertTrue("a tap on the card's margin opens History again", state.expanded) }
        rule.onNodeWithTag("wallet.balanceValue").performClick()
        rule.runOnIdle { assertFalse("a tap on the amount closes History", state.expanded) }
    }

    @Test
    fun talkBackOpensAndClosesHistoryFromTheBalance() {
        showCard()
        // The card's tap is one action on the balance; the card is no button.
        assertEquals(listOf(tr("Show history")), balanceActions())
        rule.onNodeWithTag("wallet.balanceCard").assert(SemanticsMatcher.keyNotDefined(SemanticsActions.OnClick))
        val toggle = rule.onNodeWithTag("wallet.historyToggle")
        assertEquals(tr("Collapsed"), toggle.fetchSemanticsNode().config[SemanticsProperties.StateDescription])
        balanceAction(tr("Show history")).action()
        rule.waitUntil(5_000) { model.shown?.range == TimeRange.WEEK }
        rule.runOnIdle { assertTrue(state.expanded) }
        assertEquals(tr("Expanded"), toggle.fetchSemanticsNode().config[SemanticsProperties.StateDescription])
        rule.onNodeWithTag("wallet.historyChart").assertExists()

        // A past moment speaks the balance held then.
        val live = balanceDescription()
        val first = model.shown!!.points.first()
        rule.runOnIdle { state.timestamp = first.timestamp }
        val past = balanceDescription()
        assertNotEquals(live, past)
        assertTrue(past, past.contains(XmrFormat.format(first.balance)))

        // Closing returns to Now, and the closed chart is gone for TalkBack and touch.
        balanceAction(tr("Hide history")).action()
        rule.waitForIdle()
        rule.runOnIdle {
            assertFalse(state.expanded)
            assertNull(state.timestamp)
        }
        rule.onNodeWithTag("wallet.historyChart").assertDoesNotExist()
    }

    private fun balanceDescription(): String =
        rule.onNodeWithTag("wallet.balanceValue").fetchSemanticsNode()
            .config[SemanticsProperties.ContentDescription].single()

    private fun balanceActions(): List<String> =
        rule.onNodeWithTag("wallet.balanceValue").fetchSemanticsNode()
            .config[SemanticsActions.CustomActions].map { it.label }

    private fun balanceAction(label: String) =
        rule.onNodeWithTag("wallet.balanceValue").fetchSemanticsNode()
            .config[SemanticsActions.CustomActions].single { it.label == label }

    private fun open() {
        rule.onNodeWithTag("wallet.historyToggle").performClick()
        rule.waitUntil(5_000) { model.shown?.range == state.range }
        rule.waitForIdle()
        rule.runOnIdle { assertTrue(state.expanded) }
    }

    private fun select(range: TimeRange) {
        rule.onNodeWithText(tr(range.label)).performClick()
        rule.waitUntil(5_000) { model.shown?.range == range }
        rule.waitForIdle()
    }

    private fun cardHeight(): Int = rule.onNodeWithTag("wallet.balanceCard").fetchSemanticsNode().size.height

    private fun showCard(prices: HistoryPrices = loaded, fontScale: Float = 1f) {
        rule.setContent {
            MoneroOneTheme {
                val density = LocalDensity.current
                CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale)) {
                    val point = model.shown
                        ?.takeIf { it.range == state.range }
                        ?.let { PortfolioHistory.historicalSelection(state.timestamp, it.points) }
                    BalanceCard(
                        balance = ledger.balance,
                        unlockedBalance = 2 * xmr,
                        formatXmr = { XmrFormat.format(it) },
                        liveFiat = { "$" + String.format(Locale.US, "%.2f", it / 1e12 * 155.0) },
                        formatFiat = { "$" + String.format(Locale.US, "%.2f", it) },
                        fiatMode = false,
                        syncState = SyncState.Synced,
                        isOnline = true,
                        history = state,
                        historicalPoint = point
                    ) { isActive ->
                        BalanceHistoryChart(
                            balance = ledger.balance,
                            ledger = ledger,
                            isActive = isActive,
                            model = model,
                            walletSessionId = 1L,
                            prices = prices,
                            state = state,
                            onFetchRange = { fetches += it }
                        )
                    }
                }
            }
        }
    }
}
