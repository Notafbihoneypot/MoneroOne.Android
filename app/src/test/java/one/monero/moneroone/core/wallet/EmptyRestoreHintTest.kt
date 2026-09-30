package one.monero.moneroone.core.wallet

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EmptyRestoreHintTest {
    private fun shows(
        showing: Boolean = false, restored: Boolean = true, transactions: Boolean = false,
        synced: Boolean = true, height: Long = 3_500_000, dismissed: Boolean = false
    ) = EmptyRestoreHint.shows(showing, restored, transactions, synced, height, dismissed)

    @Test fun `a synced seed restore with no transactions shows it`() = assertTrue(shows())

    @Test fun `a new wallet never shows it`() = assertFalse(shows(restored = false))

    @Test fun `transactions hide it`() = assertFalse(shows(showing = true, transactions = true))

    @Test fun `it waits for the sync and stays through the next one`() {
        assertFalse(shows(synced = false))
        assertTrue(shows(showing = true, synced = false))
    }

    @Test fun `a scan from the genesis block or a dismissed hint does not show it`() {
        assertFalse(shows(height = 0))
        assertFalse(shows(dismissed = true))
    }
}
