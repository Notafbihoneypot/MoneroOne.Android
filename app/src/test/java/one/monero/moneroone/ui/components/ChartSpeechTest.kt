package one.monero.moneroone.ui.components

import one.monero.moneroone.data.model.Currency
import org.junit.Assert.assertEquals
import org.junit.Test

class ChartSpeechTest {

    private val portfolio = ChartSpeech("Portfolio", "past week", Currency.USD, note = "3 transactions")

    @Test
    fun `label names the chart and its span`() {
        assertEquals("Portfolio chart, past week", portfolio.label)
    }

    @Test
    fun `summary gives the ends, the change and the note`() {
        assertEquals("From \$100.00 to \$110.00, up 10.00%, 3 transactions", portfolio.summary(100.0, 110.0))
        // No change from zero.
        assertEquals("From \$0.00 to \$110.00, 3 transactions", portfolio.summary(0.0, 110.0))
        assertEquals(
            "From \$1,234.50 to \$1,234.50, unchanged",
            ChartSpeech("Monero price", "past 24 hours", Currency.USD).summary(1234.5, 1234.5)
        )
    }

    @Test
    fun `spoken change rounds as the header does`() {
        assertEquals("up 8.99%", ChartSpeech.spokenChange(8.991))
        assertEquals("down 1.20%", ChartSpeech.spokenChange(-1.2))
        assertEquals("unchanged", ChartSpeech.spokenChange(0.004))
        assertEquals("unchanged", ChartSpeech.spokenChange(-0.004))
    }
}
