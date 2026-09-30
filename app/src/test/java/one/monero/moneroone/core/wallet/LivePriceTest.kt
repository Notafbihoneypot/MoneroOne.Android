package one.monero.moneroone.core.wallet

import one.monero.moneroone.data.model.Currency
import one.monero.moneroone.data.model.CurrentPrice
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LivePriceTest {
    private val usd = CurrentPrice(price = 543.89, change24h = -1.39, lastUpdated = 2_000L)

    @Test fun `the Price tab's price fills a card whose fetch at launch failed`() =
        assertEquals(usd, LivePrice.afterPriceTab(shown = null, selected = Currency.USD, fetched = usd, fetchedIn = Currency.USD))

    @Test fun `each new Price tab price replaces the card's`() {
        val earlier = CurrentPrice(price = 530.0, change24h = -2.0, lastUpdated = 1_000L)
        assertEquals(usd, LivePrice.afterPriceTab(earlier, Currency.USD, usd, Currency.USD))
    }

    @Test fun `a price in another currency never shows under the selected one`() {
        assertNull(LivePrice.afterPriceTab(null, Currency.EUR, usd, Currency.USD))
        val eur = CurrentPrice(price = 500.0, change24h = null, lastUpdated = 1_000L)
        assertEquals(eur, LivePrice.afterPriceTab(eur, Currency.EUR, usd, Currency.USD))
    }

    @Test fun `the card keeps its price while the Price tab waits for one`() =
        assertEquals(usd, LivePrice.afterPriceTab(usd, Currency.USD, null, Currency.USD))
}
